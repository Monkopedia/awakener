package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.BusOutcome
import com.monkopedia.awakener.registry.ResidueOutcome
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.asIdentity
import java.io.File
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * sway's implementation of the binding interface.
 *
 * Everything sway-specific lives here — criteria strings, split containers, focus memory —
 * so that nothing above [WindowManager] has to know any of it.
 *
 * **It has a lifecycle as well as an interface**, and the two are deliberately not the same thing:
 * [close] retires one manager, and the compositor session it is speaking to can end and be replaced
 * underneath it without either being visible to anything above [WindowManager]. Neither is on the
 * interface — a caller that has to close a manager is the one that built it, and [WindowManager] is
 * held to what `docs/design.md` says it is (see #94, which is where that agreement is being
 * settled). What a caller above does see is [CompositorSessionEnded], which is compositor-agnostic
 * and was already part of the change stream's contract.
 */
class SwayWindowManager(
    private val connect: () -> SwayConnection,
    private val store: ConfigStore,
    /**
     * Where bindings actually live. Previously an in-memory map, which made every binding a
     * fact about this process rather than about the desktop — the agent was forgotten the
     * moment awakener restarted, taking its accumulated model with it.
     */
    private val registry: BindingStore,
    /**
     * The lifetime this manager is granted: `DockHandle.close()` runs here, and so does the repair
     * collector this constructor starts ([repairing]).
     *
     * **Under the default flags nothing this manager launches fails this scope**, so a plain
     * `CoroutineScope(Job())` is a legitimate thing to hand in. A collector failure is contained
     * and reported through [repairs] instead — see [CollectorFailure], which is also how a caller
     * asks for the opposite. Set [WmFlags.collectorFailure] to `PROPAGATE` and this scope must
     * tolerate a child failing, since a constructor-started job leaves nowhere to put a `try`.
     *
     * Nothing runs on it *directly* any more: everything this manager launches goes on [lifetime],
     * a child of this scope, so that [close] can retire one manager without touching the rest of
     * what a caller put here. Cancelling this scope still ends the manager, which is what makes it
     * a grant of a lifetime rather than a place to put coroutines.
     */
    private val scope: CoroutineScope,
) : WindowManager {
    /**
     * This manager's own lifetime, and the reason [close] can promise anything.
     *
     * A child [Job] of [scope] rather than a `SupervisorJob`: the parent relationship is what makes
     * cancelling the caller's scope still end this manager, and a plain `Job` is what keeps
     * [CollectorFailure.PROPAGATE] meaning what it says — a supervisor here would stop a collector
     * failure ever reaching the caller's scope, which is that flag's entire purpose.
     *
     * The context is otherwise inherited, so a caller's dispatcher and its
     * `CoroutineExceptionHandler` still apply to everything launched here.
     */
    private val lifetime =
        CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

    /**
     * One compositor session as this manager sees it.
     *
     * The connection is the identity, not merely a field of it: the design note's rule is that the
     * dock table's lifetime *is* the IPC connection's lifetime, and a handle or a tree edit that
     * wants to say "the session I was made against" has nothing else to point at.
     *
     * [generation] is that identity said out loud, and it exists because it has a reader:
     * [SwayDockHandle.checkSession] names both numbers when it refuses, so a caller holding a stale
     * handle is told *which* session it is from and which one the manager has moved to. Two
     * connections are otherwise indistinguishable to anybody reading a message, and "this handle is
     * from a session that ended" leaves a reader unable to tell one boundary from three.
     */
    private class Session(val connection: SwayConnection, val generation: Long) {
        /**
         * How this compositor shares out a `resize set width`, read the first time a show or an
         * attach needs it and kept for the session: the rule is a fact about the build, and a
         * successor session may be a different build (#269).
         */
        @Volatile
        var resizeShare: ResizeShare? = null
    }

    /**
     * The live session, or null before the first command and after a boundary nothing has yet
     * reconnected past.
     *
     * Volatile and read without the lock on the paths that only *compare* it — [SwayDockHandle]
     * asking whether it is stale — so that a stale handle is refused without acquiring anything.
     * Every write, and every read that may acquire, goes through [sessionLock].
     */
    @Volatile
    private var liveSession: Session? = null

    /**
     * The boundary this manager is stopped at, or null if it is not stopped at one.
     *
     * Held here as well as on [repairs] because the two answer different questions and one of them
     * has to be race-free against acquisition: this is written under [sessionLock] by the collector
     * and cleared under it by the reconnect, so "is this an acquisition or a reacquisition" is
     * decided by the same lock that does the acquiring. [DockRepairStatus.sessionEnded] is the
     * reporting copy, and a reader watching it can lag this one by an instant.
     */
    private var boundary: CompositorSessionEnded? = null

    private var generation = 0L

    @Volatile
    private var retired = false

    private val sessionLock = Mutex()

    private val config: Config get() = store.config.value

    private val treeEditLock = Mutex()

    /**
     * The docks this process knows about. See [DockTable]; it is read on every enumeration, and
     * written by [attach], by a teardown, and by [dockedTo] adopting a dock it found by mark.
     *
     * `internal` rather than private only so that the tests in this module can assert on it
     * directly. That used to be the only way to check it at all: discarding it at the session
     * boundary had no observable consequence, because by then the connection every read would go
     * through was dead. It does now — a manager that reconnects (see [WmFlags.sessionReconnect])
     * enumerates the successor session against an empty table and rebuilds by adopting the marks it
     * finds, which is exactly what the design note asks for and is behaviour a test can watch.
     */
    internal val docks = DockTable()

    private val unrecognisedMarks = MutableStateFlow<Set<String>>(emptySet())

    private val repairState = MutableStateFlow(DockRepairStatus())

    /**
     * What this manager's repair collector has done, and what stopped it.
     *
     * The collector answers to the compositor rather than to a caller, so this is where anything
     * it would otherwise swallow surfaces — see [DockRepairStatus].
     */
    val repairs: StateFlow<DockRepairStatus> = repairState.asStateFlow()

    /**
     * Marks under [WmFlags.dockMarkPrefix] that are not the marked node's own dock mark, as seen
     * by every enumeration and every sweep this instance has run.
     *
     * Such a mark is a user's own — sway's mark namespace is one global, user-facing set — so the
     * window keeps being enumerated and is named here instead of being silently hidden. Names
     * accumulate for the lifetime of this instance and are never pruned, so a mark that has since
     * been removed is still listed.
     *
     * It is also where a dock marked under the other [WmFlags.dockMarkScheme] shows up — after an
     * awakener upgrade over standing docks, or a flip of that flag — since such a mark is under
     * the prefix and is not a mark this build recognises. That is the whole of the diagnosis for
     * a dock the flip stranded.
     */
    val unrecognisedDockMarks: StateFlow<Set<String>> = unrecognisedMarks.asStateFlow()

    /**
     * The compositor's own words for refusing to name a container's tab, or null while it has not.
     *
     * **A capability, discovered rather than configured, and the reason it is a field is that the
     * answer differs between sways that are both current somewhere.** Measured: sway 1.12 accepts
     * `title_format` on a split container and 1.9 — what Ubuntu noble ships, and what this
     * repository's CI runs — refuses it with *"Only views can have a title_format"*. There is no
     * IPC request that asks, so the only way to find out is to try.
     *
     * Latched, so a compositor that has said no is asked once per session rather than once per
     * attach and once per title change; cleared with the session in [invalidate], since a
     * successor is a different compositor process and may be a different build.
     */
    private val tabTitleRefusal = MutableStateFlow<String?>(null)

    private val residueFailures = MutableStateFlow<List<ResidueDisposalFailure>>(emptyList())

    /**
     * Residue disposals a detach asked for and did not get, oldest first.
     *
     * `unbind` returns a `Forget` whose residue half can say the disposal failed while the binding
     * really did go, and [SwayDockHandle.detach] discarded it — so the failure that
     * `awakener-registry forget` exits 3 for was silent through the manager (#115). This is where
     * it surfaces, and it is the same shape as [unrecognisedDockMarks] and [repairs] for the same
     * reason: the operation continued and something in it did not happen.
     *
     * **A list rather than a set** because the *same* surface can fail twice — a rebind, another
     * detach, another failed disposal — and two attempts on one model are two facts a reader may
     * want, where a set would keep only the first. (Two *different* surfaces would not collide in
     * a set anyway; their [ResidueDisposalFailure.key]s differ. That is why the reason has to be
     * about repeats rather than about distinctness.) Ordered oldest first, so a reader taking the
     * last entry gets the most recent.
     *
     * **Nothing clears it and nothing drains it, and the growth that allows is bounded by who
     * causes it.** An entry costs one detach that a caller made — a person closing a panel — so
     * the list grows at human pace and only when a disposal genuinely failed. That is the whole
     * difference from [DockRepairStatus.lastFailure], which keeps one failure rather than a list
     * precisely because *its* appends are collector-driven: that one is fed by compositor events
     * for as long as a session lasts, and nobody is pressing anything. Appends here are safe
     * without a lock — [MutableStateFlow.update] is a compare-and-set loop — and a reader is
     * expected to take a snapshot rather than to be handed a drained queue.
     *
     * Appended to **before** [WmFlags.detachResidueFailure] decides whether to raise, so a caller
     * that catches the raise and a caller that never sees one read the same list.
     */
    /**
     * Why this compositor will not carry a woken tab's title, or null while nothing says it will
     * not.
     *
     * Non-null means `wm.dock.tab_title=SURFACE` is doing nothing on this desktop: the tab of a
     * woken surface keeps the layout description sway builds for it, which is #177 unfixed. It is
     * reported rather than raised because the title is **cosmetic and the dock is not** — failing
     * an attach over a tab's name would cost the user their agent panel on every press, on a
     * compositor where everything else works.
     *
     * Read it, and say so, rather than treating an empty tab-title feature as a bug in awakener.
     */
    val tabTitleUnsupported: StateFlow<String?> = tabTitleRefusal.asStateFlow()

    val residueDisposalFailures: StateFlow<List<ResidueDisposalFailure>> =
        residueFailures.asStateFlow()

    private val busLeft = MutableStateFlow<List<BusEntryLeft>>(emptyList())

    /**
     * Bus entries a detach's forget left on spanreed's roster, oldest first — the bus half of
     * the `Forget` that [residueDisposalFailures] is the residue half of, kept as a list for the
     * same reasons. Reported, never raised: unlike a residue the user asked to have deleted, an
     * entry left behind is a leak to see, not a failed key press.
     */
    val busEntriesLeft: StateFlow<List<BusEntryLeft>> = busLeft.asStateFlow()

    /**
     * The session every command in this manager rides on, acquiring one if there is none.
     *
     * This replaced `by lazy { connect() }`, and the difference is the whole of #33's first bullet:
     * a lazy connection is acquired once and is then whatever it was, so a manager whose compositor
     * restarted spent the rest of the process talking to a socket nothing was listening on. Here
     * the absence of a session is a state that can be *left*, and [WmFlags.sessionReconnect]
     * decides whether leaving it is this manager's job or the caller's.
     *
     * Double-checked against [liveSession] before taking [sessionLock], because this is on the
     * path of every tree read and enumeration takes no other lock at all.
     */
    private suspend fun session(): Session =
        liveSession ?: sessionLock.withLock { liveSession ?: acquire() }

    /**
     * Opens a connection and makes it this manager's session, restarting the repair collector if
     * this is a reconnection rather than the first one.
     *
     * **The collector restart belongs here and not in the collector**, which is what keeps the
     * design's rule about unattended action intact. A collector that reconnected itself would be
     * looping — it would wake with no compositor to read, fail, and have to decide how long to
     * wait before trying again, which is a schedule. Reconnecting from an acquisition means the
     * work is done because a caller asked for something, once per asking, and a desktop nobody
     * touches does nothing at all.
     *
     * [connect] raising leaves this manager exactly where it was — no session, the boundary still
     * recorded — so the next call tries again. That is the only retry there is, and a caller made
     * it.
     */
    private suspend fun acquire(): Session {
        check(!retired) {
            "this manager has been closed and will not open another connection; build a new one"
        }
        val resuming = boundary != null
        check(!resuming || config[WmFlags.sessionReconnect] == SessionReconnect.ON_DEMAND) {
            "the compositor session this manager was built against has ended, and " +
                "wm.session.reconnect=NEVER: this manager will not acquire a successor " +
                "connection. Retire it with close() and build one against the new session."
        }
        val fresh = Session(connect(), generation + 1)
        generation = fresh.generation
        liveSession = fresh
        if (resuming) {
            boundary = null
            // Reported before the collector is relaunched, so a reader that sees `sessionEnded`
            // cleared cannot also see a manager with no collector.
            repairState.update {
                it.copy(sessionEnded = null, reconnects = it.reconnects + 1)
            }
            repairing = lifetime.launch { collectRepairs() }
        }
        return fresh
    }

    /**
     * Drops the session the compositor has ended, closing what is left of it.
     *
     * Run before the table is discarded and before the boundary is reported, so that a reader who
     * sees [DockRepairStatus.sessionEnded] set can rely on both: there is no live session, and the
     * table is empty. The connection is closed rather than dropped because a manager that goes on
     * to reconnect would otherwise leak one file descriptor per compositor restart, and because a
     * caller blocked in a request on it should learn now.
     */
    private suspend fun invalidate(ended: CompositorSessionEnded) {
        // The successor is a different compositor process and may be a different build, so what
        // the dead one refused says nothing about it. Cleared here rather than left latched
        // because the cost of being wrong is one wasted round trip and the cost of the other way
        // is a feature that stays off for the rest of the process.
        tabTitleRefusal.value = null
        val dead = sessionLock.withLock {
            boundary = ended
            liveSession.also { liveSession = null }
        }
        dead?.connection?.close()
    }

    /**
     * Retires this manager: it stops collecting, gives up its connection and abandons its table,
     * and every later call on it raises.
     *
     * **What it guarantees is that the collector has stopped, not that it has been asked to**, and
     * that distinction is the whole reason this exists rather than being left to the scope a caller
     * handed in. `cancel` only asks: a collector inside a sweep goes on deciding what to reap until
     * the cancellation reaches a suspension point, so a caller that cancelled and immediately built
     * a replacement had two managers over one tree for exactly as long as that took — the race #56
     * was, narrowed rather than closed. So this closes the command connection first, which wakes a
     * collector blocked in a socket read, and then *joins*, bounded by [WmFlags.closeWaitMs]. A
     * wait that expires raises, because a manager that has been asked to stop and has not is the
     * state this call exists to make impossible.
     *
     * What it does not do is take the desktop apart. The docks stay standing under the default
     * [WmFlags.closeReapsDocks]: a dock's mark is what a successor adopts it by, so an awakener
     * restart over a live sway is meant to leave the panels where they are. That flag is the other
     * choice, for a shutdown with nothing coming after it.
     *
     * **Call it from outside the scope this manager was given.** It joins its own children, so a
     * call made *from* one of them — from inside a sweep, say — waits for itself until
     * [WmFlags.closeWaitMs] expires and then raises.
     *
     * Idempotent in the ordinary sense: a second call returns having done nothing. Two concurrent
     * first calls are a caller error, not a case this defends against.
     */
    suspend fun close() {
        if (retired) return
        // Before the retirement, not after: a teardown talks to the compositor, and a retired
        // manager refuses to hand out the session it would need to.
        val failure = if (config[WmFlags.closeReapsDocks]) detachAll() else null
        retired = true
        val job = lifetime.coroutineContext.job
        // Asked first and woken second. The cancellation is what the collector is waiting to
        // notice; closing the connection is what stops it waiting on a socket read to notice it.
        job.cancel()
        val dead = sessionLock.withLock { liveSession.also { liveSession = null } }
        dead?.connection?.close()
        try {
            val wait = config[WmFlags.closeWaitMs]
            if (wait > 0) {
                check(withTimeoutOrNull(wait) { job.join() } != null) {
                    "this manager's collector was still running ${wait}ms after close() " +
                        "cancelled it; a manager that has been asked to stop and has not is the " +
                        "overlap close() exists to prevent (wm.manager.close_wait_ms)"
                }
            }
        } finally {
            // In a `finally` so that a join that timed out still leaves the table abandoned: the
            // entries name a session this manager has stopped speaking for either way.
            docks.discard()
        }
        failure?.let { throw it }
    }

    /**
     * Tears down every dock in this manager's table, returning what failed rather than raising.
     *
     * Returned rather than thrown because [close] must finish retiring whatever this does: a wedged
     * panel is a thing to report, and a collector left running because a panel would not close is a
     * thing that goes on reaping the desktop. Each failure names its dock for the same reason
     * [reapOrphans] tags its own — nothing underneath knows which window a `split none` refusal
     * came from.
     */
    private suspend fun detachAll(): Throwable? {
        val session = liveSession ?: return null
        val failures = mutableListOf<Throwable>()
        docks.snapshot().entries.forEach { (dock, entry) ->
            try {
                SwayDockHandle(entry.surface, AgentId(""), SurfaceId(dock), null, session).detach()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += IllegalStateException(
                    "closing dock $dock, bound to surface ${entry.surface.raw}, failed while " +
                        "retiring this manager",
                    failure,
                )
            }
        }
        return failures.firstOrNull()?.also { first ->
            failures.drop(1).forEach(first::addSuppressed)
        }
    }

    /** Whether awakener's own memory counts as evidence, or only what it wrote into the tree. */
    private val Config.consultsTable: Boolean
        get() = this[WmFlags.dockRecognition] == DockRecognition.MARK_OR_TABLE

    /**
     * The surface [node] is the dock for, or null if it is a window a caller may bind an agent to.
     *
     * The union of the two sources, because each is reliable in one direction only: the table is
     * ahead of the mark during an attach — the mark lands a round trip after the window maps —
     * and the mark is ahead of the table after an awakener restart, since it is what a standing
     * dock still carries. A false negative is the expensive direction, so recognising by either
     * is deliberate.
     *
     * **Recognising a dock from its mark records it.** Adoption has to materialise, not merely be
     * answered: a mark can be taken off a dock by a hand that is not awakener's, and a union
     * computed afresh on every read has nothing left when it is. Measured, two managers against
     * one sway: attach, restart awakener, enumerate — correct either way — then take the mark off
     * the dock, and a non-recording union hands the agent panel back as a bindable surface. That
     * is this note's expensive false negative arriving through the mechanism built to prevent it,
     * and it is worse than the original: the panel is invisible to [reapOrphans] for the same
     * reason, so nothing can take it down again. Under [DockMarkScheme.SURFACE] awakener produced
     * that loss itself, by a second attach on the same surface (#14).
     *
     * The mark is therefore read *before* the table is consulted rather than after. That costs a
     * list scan on a hit and buys two things: an adopted entry, and a user's mark under the
     * prefix being reported on a node that is already a known dock, which a table-first order
     * made depend on when in a dock's life it was looked at.
     *
     * Writing on a read path is deliberate and is not a lock: [DockTable] is a compare-and-set
     * over an immutable snapshot, the same one [unrecognisedMarks] already does two lines up. A
     * concurrent `attach` that has just evicted a failed dock's entry can be immediately followed
     * here by an adoption of that same node — correctly, since it is a node still wearing the
     * mark, which is exactly what adoption is for.
     *
     * Reports rather than hides a mark under the prefix that is not this node's own dock mark:
     * that is a user's mark on a genuine window, and treating it as a dock is what made such a
     * window unreachable by every code path at once, and — once a sweep ran on every window close
     * — destroyed by one (#15).
     *
     * **What recording costs, stated where the recording happens.** It is one-way: a node
     * recognised here stays recognised whatever its marks say afterwards. So the residual #15
     * leaves — a user's own mark shaped exactly like that window's own dock mark — stops being
     * transient. Before this, `swaymsg unmark` handed the window straight
     * back to enumeration; now it does not, and only `wm.dock.recognition=MARK_ONLY` or an
     * awakener restart releases it. **That latch is a window hidden, which is recoverable, and it
     * is not a window destroyed:** [reapOrphans] does not kill on this recognition alone under the
     * default [WmFlags.reapEvidence].
     *
     * **That sentence is about the latch, and the residual itself is worse than the latch.** While
     * a mark is still on the window it is exactly the evidence [WmFlags.reapEvidence]`=CURRENT`
     * asks for, so when the `con_id` after `_for_` closes the sweep **destroys** that window — and
     * #18 gave the sweep a caller on every window close. What the default
     * [WmFlags.dockMarkScheme] buys is that such a mark is not one anybody writes by accident: it
     * has to carry a nonce-shaped field as well as this node's own `con_id` (#35). It does not
     * make the mark unforgeable, because no mark is — sway sets marks through the same
     * `RUN_COMMAND` `swaymsg` sends, measured on 1.12 — so a nonce copied out of the tree and
     * re-marked still reaches the sweep. `SwayBindingTest.a nonce-shaped user mark is still
     * destroyed, and only the reap evidence closes that` pins that, and
     * [ReapEvidence.STOOD_UP] is what closes it, at the price stated there.
     */
    private fun dockedTo(node: Node, table: DockTableSnapshot, cfg: Config): SurfaceId? {
        val reading = node.dockMark(cfg[WmFlags.dockMarkPrefix], cfg[WmFlags.dockMarkScheme])
        val unrecognised = reading.unrecognised
        if (unrecognised.isNotEmpty()) unrecognisedMarks.update { it + unrecognised }
        if (!cfg.consultsTable) return reading.surface
        table.entries[node.id]?.let { return it.surface }
        val adopted = reading.surface ?: return null
        docks.record(SurfaceId(node.id), adopted, DockOrigin.ADOPTED)
        return adopted
    }

    /**
     * Whether [node] is a dock on evidence that exists *now*, rather than on a recognition
     * [dockedTo] latched at some earlier read.
     *
     * The distinction only matters where the answer is destructive, which is [reapOrphans] and
     * nowhere else — hiding a window on a stale recognition costs a hotkey that says "no such
     * surface", and killing one costs the window. The note's own bar, set for `RECLAIM` and
     * applying unchanged here: a user's window *is not recoverable at all*.
     *
     * Costs a second mark scan of the node, on the sweep's candidates only.
     *
     * [ReapEvidence.STOOD_UP] is the value that does not ask the tree anything *here*. Every mark
     * sway holds is writable from `swaymsg` — the same `RUN_COMMAND` and the same parser awakener
     * uses, measured on 1.12 — so a mark is evidence that is only ever *unlikely* to be somebody
     * else's, never impossible.
     *
     * **Not asking the tree here is not the same as the entry being unproducible by a desktop**,
     * and reading it as such is #96: `attach` writes the entry, and the window it writes it for is
     * the one that answered a wait on an `app_id` that window's own client declared. So this
     * predicate is exactly as strong as [WmFlags.stoodUpProof] makes the entry, and the two flags
     * have to be read together. What `STOOD_UP` costs is stated at the flag and is the mark's own
     * purpose: a dock adopted after a restart is never reaped.
     */
    private fun currentlyADock(node: Node, table: DockTableSnapshot, cfg: Config): Boolean {
        val stoodUp = table.entries[node.id]?.origin == DockOrigin.STOOD_UP
        return when (cfg[WmFlags.reapEvidence]) {
            ReapEvidence.RECOGNITION -> true
            ReapEvidence.CURRENT -> stoodUp ||
                node.dockMark(cfg[WmFlags.dockMarkPrefix], cfg[WmFlags.dockMarkScheme])
                    .surface != null
            ReapEvidence.STOOD_UP -> stoodUp
        }
    }

    /**
     * Whether every tiled child of [split] other than [surface] is one of [surface]'s docks, and
     * there is at least one — the split a dock of [surface] made and is still standing in.
     *
     * Asked from the table and the mark, like [dockedTo], but without its adoption side effect:
     * this decides where a split goes, not what a window is, so it records nothing. A split that
     * holds any other window — the user's own, or a dock of somebody else's — answers false, and
     * the caller treats it as it always did.
     */
    private fun holdsOnlyDocksOf(split: Node, surface: SurfaceId, cfg: Config): Boolean {
        val others = split.nodes.filter { it.id != surface.raw }
        return others.isNotEmpty() && others.all { isDockOf(it, surface, cfg) }
    }

    /** Whether [node] is one of [surface]'s docks, by [holdsOnlyDocksOf]'s test. */
    private fun isDockOf(node: Node, surface: SurfaceId, cfg: Config): Boolean {
        val recorded = docks.snapshot().entries[node.id]?.surface
        val marked = node.dockMark(cfg[WmFlags.dockMarkPrefix], cfg[WmFlags.dockMarkScheme]).surface
        return (if (cfg.consultsTable) recorded ?: marked else marked) == surface
    }

    /**
     * Whether an attach in flight has reserved the `app_id` [node] reports.
     *
     * Distinct from [dockedTo] because a reservation names no surface: it covers the window
     * between the `exec` and the moment the dock is identified, which is before there is a
     * `con_id` to bind to anything.
     */
    private fun reserved(node: Node, table: DockTableSnapshot, cfg: Config): Boolean =
        cfg.consultsTable && table.reserves(node)

    /**
     * A fresh spawn token for one attach — see [WmFlags.stoodUpProof].
     *
     * From [SecureRandom] and not from the source [dockMarkFor]'s nonce uses, and the difference is
     * the reader rather than the length. A mark's nonce is in `swaymsg -t get_tree` the moment it
     * is written, so guessing one buys nothing that reading one does not; this string is never put
     * in the tree at all, which makes predicting it the *only* route that does not require reading
     * `/proc`. That is a small door and it is worth shutting; it is not a claim that the token
     * cannot be obtained, which [WmFlags.stoodUpProof] states plainly it can.
     */
    private fun newSpawnToken(): String {
        val bytes = ByteArray(SPAWN_TOKEN_BYTES)
        spawnTokens.nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * Whether the process behind [node] carries this attach's [token] in its environment.
     *
     * Two reads, answered by two different things, and the difference is the whole value:
     *
     * - an `app_id` is a string the client sets about itself — `xdg_toplevel.set_app_id` is a
     *   request in `xdg-shell.xml`, three mentions of `app_id` in that file;
     * - a `pid` is not something a client can state. There is no request carrying one anywhere in
     *   `wayland.xml` or `xdg-shell.xml` — zero matches for `pid` in either, against 463 for
     *   `surface` and 3 for `app_id` as the controls that the files were read (checked on kaladin,
     *   wayland-protocols as shipped). What it *is* is the pid of "the application that owns the
     *   window", `sway-ipc(7)`'s wording.
     *
     * A node matched on `app_id` is necessarily an xdg-shell window, which is what makes the two
     * safe to read together: `sway-ipc(7)` documents `app_id` as "for an xdg-shell window, the
     * name of the application, if set. Otherwise, null", and its own worked example shows
     * `"shell": "xwayland"` beside `"app_id": null`. An xwayland view, where the pid comes from a
     * client-set X property, can therefore never be the window this reads.
     *
     * `/proc/<pid>/environ` is readable because the dock runs as the same user awakener does —
     * which is the same fact that stops any of this being a privilege boundary.
     *
     * **The token is delivered across a wider surface than the one it is read from, and the
     * margin that makes that harmless is worth stating rather than re-deriving.** It goes out as
     * `env AWAKENER_DOCK_TOKEN=<t> <command>`, so between the `exec` and `env`'s own exec it sits
     * in that process's argv. Measured 2026-08-08 with `stat -c '%a'`, on kaladin *and* on adolin:
     * `/proc/self/environ` is `400` and `/proc/self/cmdline` is `444`, and `/proc/mounts` carries
     * no `hidepid`. So the read this function does is owner-only, while the delivery is briefly
     * legible to *any* local user. It does not widen anything, because reading a token is not
     * spending one: spending it means mapping a window into the sway session, which needs the
     * socket under `/run/user/<uid>` — `700` on both hosts by the same measurement. A different
     * local user can therefore obtain the token and provably cannot use it, and the same-user case
     * was never a boundary to begin with (above, and [WmFlags.stoodUpProof]).
     *
     * **Unreadable is not proven.** A process that has already exited, a `/proc` that is not
     * mounted, an environment the dock program scrubbed: all of them answer false, and the default
     * [StoodUpProof.TOKEN_OR_ADOPTED] turns that into an [DockOrigin.ADOPTED] entry rather than
     * into a failed attach. Degrading toward "we did not stand this up" is the safe direction —
     * it costs a panel that outlives its surface, never a window destroyed.
     *
     * The token is matched as a whole entry rather than by `contains`, so a *different* variable
     * whose value ends in the token is not a match.
     */
    private fun carriesSpawnToken(node: Node, token: String): Boolean {
        val pid = node.pid ?: return false
        val environ = runCatching { File("/proc/$pid/environ").readBytes() }.getOrNull()
            ?: return false
        val entry = "$SPAWN_TOKEN_VAR=$token"
        // NUL-separated, because that is how the kernel lays `environ` out.
        return environ.decodeToString().split(NUL).any { it == entry }
    }

    /**
     * Runs [edit] with exclusive use of the window tree.
     *
     * None of this class's sequences is atomic in sway: each is a run of IPC round trips, and a
     * coroutine can be descheduled at every one of them. [attach] is the sharpest case — its
     * snapshot of the docks already standing only identifies the window it is about to spawn if
     * nothing else can `exec` between the snapshot and the claim, and sway maps the spawned window
     * into whatever is focused *when it maps*, so a stray `focus` anywhere in that span hands the
     * dock to a different surface's tab. Both leave one node carrying two marks or an unmarked
     * panel beside it, which is the failure [DockIdentity] exists to fix arriving by another route.
     *
     * Why a receiver and not a lock each caller remembers to take: [TreeEdit] is constructed
     * *inside* the critical section and nowhere else, so there is no long-lived receiver in scope
     * for the rest of the class to call through. A tree edit therefore cannot be written
     * unserialised **by accident** — the obvious way to focus, split, mark, move or kill anything
     * is to be in here. Held as a plain convention it was forgotten three separate times, once per
     * entry point that exists.
     *
     * What this is not: a guarantee. `treeEdit { this }` still smuggles the receiver out, and
     * [session] and [connect] stay in scope for the whole class, so a determined author can still
     * drive sway unlocked. The claim is only that doing so takes deliberate effort rather than
     * inattention.
     *
     * **One tree edit is one session.** The session is acquired here, inside the lock, and every
     * command and every tree read the block makes goes through that one — so a transaction cannot
     * be half against a compositor that has since died and half against its successor, which is a
     * shape that became possible the moment a manager could reconnect. It is also what lets
     * [attach] stamp the handle it returns with the session its `con_id`s came from.
     *
     * Two things are deliberately *outside*. Reads ([tree] and everything built on it) never take
     * the lock, so enumerating surfaces does not queue behind an attach that is waiting on a dock
     * to map. And nothing that leaves the compositor belongs in here: `registry.bind` can shell
     * out to spanreed, which `FileBindingStore.bind` already keeps out of its own lock for the
     * same reason. The bound this section imposes on every other caller is one dock's map time.
     *
     * A [Mutex] is not reentrant, so [TreeEdit] holds the unlocked form of everything a locked
     * section needs — `settleFocus`, called from the end of `attach`, in particular.
     */
    private suspend fun <T> treeEdit(edit: suspend TreeEdit.() -> T): T =
        exclusively { treeEditLock.withLock { TreeEdit(session()).edit() } }

    /**
     * Why the last cross-process exclusion this manager took fell back to the in-process mutex,
     * or null if it did not. Set only under [WmFlags.attachLockRequired]`=false`, where the edit
     * went ahead anyway; `awakener-invoke` prints it, because #199's race is silent otherwise.
     */
    @Volatile
    var lockDegraded: String? = null
        private set

    /**
     * Runs [block] holding this compositor's attach lock, across processes (#199).
     *
     * Every [treeEdit] takes it, which is what makes [attach]'s claim sound against another
     * process's attach. A caller holds it across more than one call when the calls have to be
     * atomic together: `awakener-invoke` holds it for a whole press, so that a second press
     * queued behind the first sees the first one's dock and applies `invoke.when_animated` to it
     * instead of attaching a second. Reentrant for the coroutine holding it and its children.
     *
     * Not on [WindowManager]: how presses are serialised is a fact about this implementation,
     * and the interface stays at three calls. [WmFlags.attachLockScope]`=PROCESS` makes this run
     * [block] directly. Raises [CompositorLock.Contended] with nothing changed when another holder
     * keeps the lock past [WmFlags.attachLockWaitMs].
     */
    suspend fun <T> exclusively(block: suspend () -> T): T {
        val cfg = config
        if (cfg[WmFlags.attachLockScope] == AttachLockScope.PROCESS) return block()
        return CompositorLock.withLock(
            file = CompositorLock.fileFor(session().connection.path),
            waitMs = cfg[WmFlags.attachLockWaitMs],
            required = cfg[WmFlags.attachLockRequired],
            degraded = { lockDegraded = it },
            block = block,
        )
    }

    /**
     * What identifies the dock an `attach` has `exec`'d but has not found yet: the `app_id` it
     * will report, and the windows already carrying that name when the exec went out.
     *
     * Filed before the window it describes can exist, like the reservation and for the same
     * reason — a `con_id` is minted when the dock maps, so nothing keyed on one can cover the
     * dock across the span in which it becomes visible to a reader of the tree. Unlike the
     * reservation it is never consulted by a read path: [TreeEdit.strayDock] is its only reader,
     * and only where the attach has already failed.
     */
    private class PendingDock(
        val appId: String,
        val standing: Set<Long>,
        /**
         * The same predicate the wait used, so the unwind cannot kill a window the wait refused.
         *
         * Under [StoodUpProof.TOKEN_REQUIRED] that is the whole point: an attach that passes over
         * an interloper and then fails must not take it down on the way out, which is the second
         * route into the same window that [WmFlags.dockIdentity] already names. Under the other
         * two values this is `{ true }` and the unwind behaves exactly as it did.
         */
        val accept: (Node) -> Boolean,
    )

    /**
     * The only way to change the tree. See [treeEdit] for why it is a receiver.
     *
     * Everything here assumes the lock is held. Constructing it is [treeEdit]'s job alone: do not
     * hold an instance in a field or return one out of the block, because either puts the receiver
     * back in scope where a caller can reach it with no lock at all.
     */
    private inner class TreeEdit(
        /**
         * The compositor session this transaction is against, pinned by [treeEdit] as the lock was
         * taken. Everything in here goes through it rather than through whatever is live now.
         */
        val session: Session,
    ) {
        /**
         * The tree as this transaction's session reports it.
         *
         * Deliberately shadows the manager's own [SwayWindowManager.tree], so that every read
         * inside a tree edit — the waits, the unwind's look for a stray dock, the flatten's check —
         * is answered by the compositor the edit is being made against. An edit that read the tree
         * from a successor connection would be reasoning about one session's ids against another's.
         */
        suspend fun tree(): Node = tree(session)

        /** [Session.resizeShare], asked of the compositor once per session. */
        suspend fun resizeShare(): ResizeShare =
            session.resizeShare ?: session.connection.resizeShare().also { session.resizeShare = it }

        suspend fun run(command: String) {
            val failure = attempt(command)
            check(failure == null) { "sway rejected '$command': ${failure?.error}" }
        }

        /** Runs [command], returning sway's complaint if it rejected it. */
        private suspend fun attempt(command: String): CommandResult? {
            val raw = session.connection.request(I3Ipc.Request.RUN_COMMAND, command)
            return swayJson.decodeFromString<List<CommandResult>>(raw).firstOrNull { !it.success }
        }

        /**
         * Kills the window [id] and waits for it to leave the tree; false if it was still there
         * after [WmFlags.unmapWaitMs].
         *
         * The acknowledgement is not the thing to check in either direction, which is why the
         * wait belongs in the primitive rather than at whichever call site remembers it.
         *
         * On success sway acknowledges as soon as it has *asked the client to close*, not when
         * the window unmaps, so a caller reading a successful acknowledgement as "the node is
         * gone" is wrong nearly every time, and wrong in the way that got this filed: the next
         * teardown of the same dock finds it in the tree and kills it a second time.
         *
         * On rejection the tree gets the last word. sway rejects criteria that match nothing, and
         * *the window not being there* is precisely what a kill is asking for — that is the window
         * having died of its own accord between the read that found it and this command.
         */
        suspend fun kill(id: SurfaceId): Boolean {
            val failure = attempt("[con_id=${id.raw}] kill") ?: return awaitGone(id)
            check(tree().find(id.raw) == null) {
                "sway rejected killing ${id.raw}: ${failure.error}"
            }
            return true
        }

        suspend fun focus(id: SurfaceId) = run("[con_id=${id.raw}] focus")

        /**
         * Names the tab of the container [surface] is docked in after [surface]'s own title.
         *
         * **The container, not the window.** Attaching a dock puts the surface inside a split, and
         * sway names a split out of its layout description — so the tab reads
         * `H[chromium awakener-dock]` where a page title used to be, which is the whole of #177.
         * `%title` is no use for putting it back: on a container it expands to that same synthetic
         * name (measured), so awakener has to interpolate the surface's title itself. What that
         * costs, and the escaping it therefore needs, is [TabTitle]'s.
         *
         * **The dock is not named here, and not looked for either.** Every caller has already
         * established that this surface has one; what this needs is the container, and the
         * container is not always the dock's parent — with wm.dock.flatten_on_hide off, hiding a
         * dock leaves the split standing with a single child (measured on 1.12: sway does not
         * collapse it), so a hidden dock's tab is still synthetic and still wants this. Asking
         * from the surface's side answers both cases; with the flag on, the hidden surface has no
         * split above it and there is nothing to name.
         *
         * That the parent is a split [CON] is checked rather than assumed because
         * [Node.parentOf] answers about whatever the tree currently holds: a surface with no
         * container above it is a child of its **workspace**, and naming that would put a page
         * title where the workspace name goes; a surface directly in a `tabbed` strip is a tab
         * already, and naming the strip names the wrong thing. See [dockSplitOf].
         *
         * Silent where there is nothing to name — no such surface, or a surface with no split
         * above it — because both mean the tab is a leaf already showing the right thing.
         */
        suspend fun applyTabTitle(surface: SurfaceId) {
            val cfg = config
            if (cfg[WmFlags.dockTabTitle] != TabTitleSource.SURFACE) return
            if (tabTitleRefusal.value != null) return
            val tree = tree()
            val node = tree.find(surface.raw) ?: return
            // A split, specifically: under wm.dock.flatten_on_hide a hidden dock's surface sits
            // directly in the tab strip, and a `tabbed` or `stacked` parent is the user's tab
            // strip rather than a container attach made — naming it would put a page title on
            // the wrong thing.
            // And only awakener's own (#248): a user's split the surface sits in — once a hide has
            // lifted it out of the split attach made — is theirs to name.
            val container = ownSplitOf(tree, surface, cfg) ?: return
            // app_id is the fallback because it is what sway's own leaf title falls back to, so a
            // window with no title keeps reading the way it read before the dock existed. With
            // neither, there is nothing to say that beats what sway would put there itself.
            val title = node.name ?: node.appId ?: return
            val target = SurfaceId(container.id)
            nameTab(target, TabTitle.command(target, cfg[WmFlags.dockTabTitleFormat], title))
        }

        /**
         * Issues a tab-naming command, recording a refusal instead of raising one.
         *
         * **The one place in this class where a rejected command does not fail its caller**, and
         * the exception is argued rather than convenient: every other command here changes what
         * the user can *do* — a dock that did not spawn, a window that did not move — whereas this
         * one changes what a tab says. sway 1.9 refuses it outright, so raising would turn every
         * attach on Ubuntu's current sway into a failed hotkey press over a cosmetic name.
         *
         * Not swallowed either: [tabTitleUnsupported] carries the compositor's own words, and a
         * caller that wants to know why its tabs are unnamed has somewhere to look. The latch it
         * sets is also what stops this being retried on every title event for the rest of the
         * session.
         */
        private suspend fun nameTab(container: SurfaceId, command: String) {
            // `attempt` answers with the first result that was NOT a success, so a non-null
            // return is already the refusal; there is no second check to make here.
            val failure = attempt(command) ?: return
            // **Which refusal it was decides whether anything is latched, and the two are not
            // distinguishable from the reply.** A compositor that will not name containers and a
            // container that left the tree between the read above and this command both come back
            // `success:false` — measured, a dead `con_id` answers `No matching node.` So the tree
            // is asked, rather than the message parsed: a node that is gone is a race this attach
            // lost, which costs one tab its title, while a node that is still standing and was
            // still refused is the capability. Latching on the race would turn one lost race into
            // tab titles switched off for the rest of the session, on a compositor that supports
            // them perfectly well.
            if (tree().find(container.raw) == null) return
            tabTitleRefusal.value = failure.error
                ?: "the compositor rejected '$command' without saying why"
        }

        /**
         * Hands [container]'s tab back to the compositor's own naming.
         *
         * Gated by no flag, and that asymmetry with [applyTabTitle] is deliberate: the flag decides
         * whether awakener *sets* a title, and a container still carrying one set before the flag
         * was turned off is exactly the state a reset exists for. `%title` is sway's own default,
         * so a container that was never titled is unchanged by this.
         */
        suspend fun resetTabTitle(container: SurfaceId) {
            // Through the same tolerant path, and not gated on the latch: a compositor that
            // refuses to *set* a container title refuses to reset one too, and a detach must not
            // fail on either. Where a title really was set, this is the command that takes it off.
            nameTab(container, TabTitle.resetCommand(container))
        }

        /**
         * Flattens the split container [survivor] is the last window in.
         *
         * One routine for both paths on purpose: #6 is the failure path of a job the success path
         * already does correctly, and two copies of it would drift. sway does not collapse a
         * split container when it drops to one child, and the leftover silently adopts the next
         * window opened in that tab.
         *
         * **It reports; the caller decides.** sway refuses `split none` on a node that still has
         * siblings — "Can only flatten a child container with no siblings" — which is a genuine
         * failure rather than a target-already-gone, so it is raised. `detach` lets it out, since
         * the orphan sweep's aggregate exists to collect exactly that; an unwind suppresses it
         * rather than replacing the diagnosis of why the attach failed. Swallowing it here would
         * take that choice away from both.
         *
         * A [survivor] that has left the tree is not a failure: the container it was alone in
         * went with it.
         *
         * **Only splits are flattened.** Where `split none` would also take a tab strip awakener
         * did not make — see [tabAbove] — the survivor is lifted into that tab strip instead,
         * which empties the splits below it and leaves the strip standing (#226).
         *
         * **Only awakener's own split** under the default [WmFlags.splitOwnership] (#248): a
         * survivor whose split carries no provenance mark is left in it, and the flatten stops at
         * the marked split rather than walking on into whatever single-child containers stand
         * above it — see [liftTarget].
         */
        suspend fun normalizeContainer(survivor: SurfaceId, docked: Rect? = null): FloatingSettle? {
            val cfg = config
            val tree = tree()
            if (tree.find(survivor.raw) == null) return null
            val tab = when (cfg[WmFlags.splitOwnership]) {
                SplitOwnership.SHAPE -> tabAbove(tree, survivor)
                SplitOwnership.MARKED -> {
                    val split = ownSplitOf(tree, survivor, cfg) ?: return null
                    liftTarget(tree, split, survivor, cfg)
                }
            }
            if (tab != null) {
                run(liftInto(tab, survivor))
                return null
            }
            val floating = tree.parentOf(survivor.raw)?.takeIf { it.type == FLOATING_CON }
            focus(survivor)
            run("split none")
            val rect = floating?.rect ?: return null
            // Every width awakener's own attach and show put the surface at inside the split,
            // which is what a late commit reverts to: the even split sway makes when the dock
            // first lands beside it, before the `resize set … ppt`, and the share left after it —
            // read off the tree when the detach saw the dock standing, and worked out from the
            // configured share either way, since a hidden dock leaves nothing to read.
            val stale = setOfNotNull(
                docked?.width,
                rect.width * (100 - cfg[WmFlags.dockSizePpt]) / 100,
                rect.width / 2,
            )
            return FloatingSettle(survivor, rect, stale)
        }

        /**
         * Takes off every split mark [isInertSplitMark] refuses, and every claim's anchor, after a
         * message that may have placed them; returns the tree it judged. Inert marks are already
         * ignored by every ownership check; this is so that a user's container does not go on
         * carrying awakener's name. Judged and cleared in one read, so a split mark's verdict is
         * fixed by the unmarking before the anchor it was judged by goes.
         */
        suspend fun settleSplitMarks(): Node {
            fun stale(node: Node): List<String> =
                node.marks.filter {
                    it.startsWith(ANCHOR_PREFIX) ||
                        it.startsWith(SPLIT_MARK_PREFIX) && node.isInertSplitMark(it)
                }.map { "[con_id=${node.id}] unmark $it" } + node.children.flatMap { stale(it) }
            val tree = tree()
            val commands = stale(tree)
            if (commands.isNotEmpty()) run(commands.joinToString("; "))
            return tree
        }

        /**
         * Leaves [split] to the user once it holds a window that is neither [surface] nor one of
         * its docks, by taking every split mark off it; returns whether it did (#256).
         *
         * Such a window gets in by being opened while the surface or [dock] is focused, or by
         * being moved there, which another client can do mid-attach as easily as the user can by
         * hand. `split none` is refused on a split with two children left in it, and flattening
         * it would have taken the user's window's container away besides. So once [dock] is out
         * of it, a split holding user content stops being awakener's: nothing later collapses,
         * re-lays, names or shares it, and a later attach wraps the surface in a split of its own
         * inside it, as for any split of the user's (#248).
         */
        suspend fun disownIfShared(split: Node, surface: SurfaceId, dock: SurfaceId): Boolean {
            val rest = split.copy(nodes = split.nodes.filter { it.id != dock.raw })
            if (rest.nodes.size < 2 || holdsOnlyDocksOf(rest, surface, config)) return false
            val marks = split.marks.filter { it.startsWith(SPLIT_MARK_PREFIX) }
            if (marks.isNotEmpty()) run(marks.joinToString("; ") { "[con_id=${split.id}] unmark $it" })
            return true
        }

        /**
         * Issues the `no_focus` rule for [appId], unless this session already has one.
         *
         * sway has no verb that revokes one, so a second rule for a name that already carries one
         * suppresses nothing extra and outlives everything — which is the accumulating half of
         * #4. What it cannot fix is the rule's reach: the first rule is still permanent and, under
         * the shared `app_id` of [DockIdentity.NEW_NODE], still covers every dock spawned
         * afterwards. That is why [FocusSuppression.NO_FOCUS_RULE] is not the default.
         *
         * The record is written after sway accepts the command and both happen under the tree
         * lock, so a rejected rule is not remembered as installed.
         *
         * **That order is the one place `attach` records a fact later than the failure that
         * needs it and is left that way on purpose** — see [unwindAttach] for the two where it
         * was not. A cancellation landing on this acknowledgement installs a rule nothing
         * remembers, and the next attach under the same name issues a second one, which is #4's
         * accumulation arriving through a one-round-trip door. Recording first would close it and
         * open a worse one: a rule that never installed but is remembered as installed suppresses
         * nothing for the rest of the session, for every dock under that name, with no correction
         * running either — where the residue here is one redundant rule that suppresses exactly
         * what the first already did.
         */
        suspend fun suppressFocusFor(appId: String) {
            if (appId in docks.snapshot().focusRules) return
            run("no_focus ${Criteria.appId(appId)}")
            docks.recordFocusRule(appId)
        }

        /**
         * Takes back the tree edits of an [attach] that could not finish: the dock window it
         * spawned, and the split container it created around [surface] if [container] says it
         * did.
         *
         * Runs inside the section the attach already holds, rather than as a `catch` around the
         * whole call. Releasing the tree to re-take it would hand a concurrent attach a window in
         * which to map its own dock into the half-built container, which is the class of bug the
         * serialisation exists to prevent.
         *
         * **Which node the dock is comes from the tree, not only from [spawned].** `spawned` is
         * assigned once [awaitWindow] has returned, so it is null across the whole of the map
         * deadline — and the likeliest failure of all is that deadline expiring, at the moment a
         * slow dock is most likely to be mapping. A dock that maps in that gap would otherwise be
         * a window this unwind does not know exists: the kill is skipped, and the flatten is then
         * refused on a container that has acquired a second child, which reinstates exactly the
         * leftover `splith` (#6) the unwind exists to remove. [pending] is the record that exists
         * early enough — it is filed with the `exec` — and [strayDock] turns it back into a
         * `con_id`. This is the note's "tolerate by checking the post-condition in the tree",
         * applied to the precondition instead.
         *
         * **Two flattens at most while a dock is still unidentified, and the second is not
         * padding.** The dock program maps one window, and each pass divides time into three: it
         * maps before that pass's tree read, which adopts and kills it; or between the read and
         * the `split none`, which sway then refuses — and the next pass's read finds it already
         * standing; or after a flatten that has already succeeded, in which case the container is
         * gone and the dock arrives as its own tab. Only the last of the three is left, and it is
         * the design note's late dock, which is #32's rather than this transaction's.
         *
         * **Best-effort, and [cause] is what propagates.** A compensation that fails is attached
         * to [cause] as a suppressed exception and otherwise ignored: it must not replace the
         * diagnosis of why the attach failed, and it must not turn one failure into two. The
         * failure that matters most here is correlated rather than independent — a socket that
         * died mid-attach is a leading cause of the attach failing and fails every compensation
         * after it.
         *
         * Cancellation is a failed attach like any other, and its compensations are IPC calls a
         * cancelled coroutine cannot make, so they run under [NonCancellable]. That is bounded by
         * the same window wait a `kill` already costs.
         *
         * **A dock that outlives its kill ends the unwind, and what it leaves does not get
         * repaired later.** The container stays a `splith` holding [surface] beside a panel that
         * will not close, and `attach`'s bookkeeping has by then evicted that panel's table entry
         * — so once the user closes it by hand, the leftover container is one nothing in this
         * class recognises as a dock's and nothing will normalise. Reported rather than repaired:
         * the flatten is a command sway refuses while the container holds two windows, so trying
         * it would add a second failure saying nothing the first did not.
         *
         * What it does not restore: focus. `attach` moves focus to [surface] as its first act and
         * does not put it back on the success path either, so an unwind leaving it there is the
         * tree in the state a completed attach would also have left it.
         */
        suspend fun unwindAttach(
            surface: SurfaceId,
            spawned: SurfaceId?,
            pending: PendingDock?,
            container: Boolean,
            cause: Throwable,
        ) {
            if (!config[WmFlags.unwindFailedAttach]) return
            withContext(NonCancellable) {
                // First, because a cancellation landing on a claim's acknowledgement skips the
                // settle after it, which would leave the claim's anchor on the user's container.
                compensate(cause) { settleSplitMarks() }
                // A dock that outlives its kill stops the normalisation: a container that still
                // holds two windows is one sway refuses to flatten, so attempting it would report
                // a second failure that says nothing the first did not.
                if (spawned != null && !compensate(cause) { killDock(spawned) }) return@withContext
                if (!container) return@withContext

                // An `exec` whose window the attach never identified is the only thing that can
                // still arrive in the container while the unwind is working in it.
                val outstanding = if (spawned == null) pending else null
                val flattens = if (outstanding == null) 1 else FLATTEN_PASSES
                repeat(flattens) { pass ->
                    val stray = outstanding?.let { strayDock(surface, it, cause) }
                    if (stray != null && !compensate(cause) { killDock(stray) }) return@withContext
                    // A refusal that is not the last word is not reported: it means a window
                    // arrived between the read above and this command, and the next pass is what
                    // takes that window down. Reporting it would put a repaired problem into the
                    // diagnosis of why the attach failed.
                    val last = pass == flattens - 1
                    val report = if (last) cause else null
                    if (compensate(report) { normalizeContainer(surface) }) return@withContext
                }
            }
        }

        /**
         * [kill] as a compensation: a dock that outlives the wait is a compensation that failed,
         * and the message has to say which dock, since nothing under here does.
         */
        private suspend fun killDock(dock: SurfaceId) = check(kill(dock)) {
            "dock ${dock.raw} was still in the tree ${config[WmFlags.unmapWaitMs]}ms after the " +
                "unwind killed it; its client is not servicing the close request"
        }

        /**
         * The window an [attach] spawned and never identified, if it is in [surface]'s container
         * now.
         *
         * Scoped to that container rather than to the whole tree, and both halves of that are
         * load-bearing. `attach` focuses the surface and holds the lock throughout, so sway maps
         * a dock of its own in there and nowhere else; and a window elsewhere reporting the same
         * `app_id` is not this attach's to kill — under [DockIdentity.NEW_NODE] that name is
         * shared with every other dock and with anything the user launched by hand. Nothing else
         * may `exec` while the lock is held — the in-process mutex and, under the default
         * [WmFlags.attachLockScope], the cross-process lock with it (#199) — which is the same
         * exclusion [awaitWindow]'s [PendingDock.standing] set rests on.
         *
         * What the scoping does not remove is [DockIdentity.NEW_NODE]'s disclosed trust in the
         * shared name, which this is a second route to: a window a user launches *into this
         * surface's tab* while the attach is waiting reports the same `app_id`, is not in
         * [PendingDock.standing], and is killed here. Named on [WmFlags.dockIdentity] alongside
         * the adoption route `attach` and `detach` already had, and narrowed to the accident by
         * [DockIdentity.PER_SURFACE_APP_ID].
         *
         * [PendingDock.accept] is the other half of that, and it is why the predicate is carried
         * here rather than recomputed: under [StoodUpProof.TOKEN_REQUIRED] the wait passed this
         * window over, and an unwind that killed it anyway would have shut the front door and left
         * this one open.
         *
         * Reports a failed read onto [cause] rather than raising it, for [compensate]'s reason:
         * losing the original diagnosis to a compensation's own failure is what that rule exists
         * to prevent, and a dead socket fails this read and every repair after it alike.
         */
        private suspend fun strayDock(
            surface: SurfaceId,
            pending: PendingDock,
            cause: Throwable,
        ): SurfaceId? = try {
            tree().parentOf(surface.raw)?.children
                ?.firstOrNull {
                    it.id != surface.raw &&
                        it.appId == pending.appId &&
                        it.id !in pending.standing &&
                        pending.accept(it)
                }
                ?.let { SurfaceId(it.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            cause.addSuppressed(failure)
            null
        }

        /**
         * Runs [action], reporting a failure onto [cause] instead of raising it.
         *
         * A null [cause] discards the failure instead, which is only for a step whose caller is
         * about to try again: an attempt whose refusal is the signal to retry is not a
         * compensation failure, and reporting it would name a problem that was then repaired.
         */
        private suspend fun compensate(
            cause: Throwable?,
            action: suspend () -> Unit,
        ): Boolean = try {
            action()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            cause?.addSuppressed(failure)
            false
        }

        /**
         * Whether [id] is currently in sway's scratchpad.
         *
         * Answered from the tree and from nothing else, which is why [DockHandle.hidden] can
         * promise what it does. A scratchpad window is a `floating_con` parented to the
         * `__i3_scratch` workspace of the `__i3` output, and both are ordinary nodes reachable
         * from the root — `Node.windows` walks `floating_nodes` as well as `nodes`, so a hidden
         * dock is still enumerated, still carries its mark, and is still reachable by the orphan
         * sweep. That is measured rather than assumed; see `SwayHideTest`.
         *
         * The name is sway's, inherited from i3, and it is hard-coded rather than configurable
         * because it is a fact about the compositor and not a choice awakener gets to make.
         */
        suspend fun inScratchpad(id: SurfaceId): Boolean =
            tree().workspace(SCRATCH_WORKSPACE)?.find(id.raw) != null

        /**
         * Moves [dock] into the scratchpad, and waits until the tree agrees.
         *
         * The wait is the same discipline `kill` keeps and for the same reason: sway acknowledges
         * a `move` before the tree it describes has settled, and this section must not end over a
         * dock that a concurrent press would still read as visible — which is precisely a second
         * hotkey seeing "visible", issuing another hide, and the pair cancelling out.
         *
         * Nothing is done about focus here. sway hands focus back to whatever the dock was sharing
         * its container with, which for a dock is the surface: measured on headless sway 1.12 with
         * the dock focused at the moment of the move, focus landed on the surface.
         */
        suspend fun hideDock(surface: SurfaceId, dock: SurfaceId): Boolean {
            if (inScratchpad(dock)) return false
            val cfg = config
            val before = tree()
            val floating = floatingAncestorOf(before, dock)
            val hide = when {
                floating != null -> floatingHideCommands(
                    before,
                    floating,
                    surface,
                    dock,
                    cfg[WmFlags.flattenFloatingOnHide] && ownsSplit(floating, cfg),
                )
                // #187, the visible-dock half. Planned from one read and sent in the same message
                // as the move, for #172's reason: one message, one present, so the user never
                // sees the windows leaving the tab as a separate frame from the panel going away.
                cfg[WmFlags.evictOnHide] ->
                    evictionCommands(before, surface, dock, cfg) { isDockOf(it, surface, cfg) } + "[con_id=${dock.raw}] move scratchpad"
                else -> listOf("[con_id=${dock.raw}] move scratchpad")
            }
            // #264: the share the dock had, for the show to give it back at. Before the move, in
            // the same message, because the move is what takes the dock out of the split the
            // share is of; and whatever wm.dock.show_width says, so a flip of it between this
            // hide and the show still finds the width there.
            val record = widthMarksOn(before.find(dock.raw)).map { "[con_id=${dock.raw}] unmark $it" } +
                listOfNotNull(widthMarkFor(before, surface, dock)?.let { "[con_id=${dock.raw}] mark --add $it" })
            run((record + hide).joinToString("; "))
            check(pollTree(cfg[WmFlags.unmapWaitMs]) { if (inScratchpad(dock)) true else null } == true) {
                "dock ${dock.raw} was still outside the scratchpad " +
                    "${cfg[WmFlags.unmapWaitMs]}ms after being moved into it"
            }
            // A split the dock stood in that a window of the user's got into is theirs from here
            // on (#256): the show makes a split of its own rather than coming back into it. The
            // dock's split rather than the surface's, tiled or floating, because the two can
            // differ: a show beside a second dock lands in that dock's split, inside the one the
            // user's window is in, and that outer one would otherwise keep the mark for good.
            // Judged as the hide left it, since an eviction may have emptied it of the user's.
            val tree = tree()
            before.parentOf(dock.raw)?.takeIf { it.isSplit() }
                ?.let { tree.find(it.id) }
                ?.let { disownIfShared(it, surface, dock) }
            // The floating hide has already flattened, or kept, the split as the flag says.
            if (floating != null) return true
            // #187. With the dock gone the surface is alone in the split attach made, and sway
            // places a new window beside the focused one *inside its parent* — so the next window
            // opened in this tab would land in that split, sharing the tab with the surface,
            // rather than getting a tab of its own. Collapsing it here leaves nothing for a
            // newcomer to walk into; `showDock` splits again on the way back.
            //
            // Only a split holding nothing but the surface. One that already took a window in
            // while the dock was visible cannot be collapsed (`split none` is refused while the
            // surface has siblings), and moving that window out is a decision about somebody
            // else's window that a hide makes only under wm.dock.evict_on_hide — which, when on,
            // has already moved it out above, so the split is down to the surface by now unless
            // no side of it could take the window. Targeted by criteria rather than by focus, so
            // the hide leaves focus exactly where sway put it. And only the split: with the
            // surface alone on a `workspace_layout tabbed` workspace, `split none` would take the
            // tab strip too (#226), so there the surface is lifted into the strip instead.
            //
            // And only awakener's own split (#248), collapsed no further than itself: a surface
            // the user put alone in a split of theirs got one of awakener's inside it at attach,
            // and that is the one this takes away.
            val split = ownSplitOf(tree, surface, cfg)
            if (cfg[WmFlags.flattenOnHide] && split?.nodes?.size == 1) {
                val tab = liftTarget(tree, split, surface, cfg)
                run(if (tab != null) liftInto(tab, surface) else "[con_id=${surface.raw}] split none")
            }
            return true
        }

        /**
         * Brings a hidden dock back beside [surface], with its configured side and width.
         *
         * **Four commands, not one, and each of the extra three is a measurement rather than
         * defensiveness.** `scratchpad show` returns the window *floating* — `type=floating_con`,
         * `floating=user_on`, centred at whatever size the scratchpad kept — so a restore that
         * stopped there would leave the panel hovering over the surface instead of docked beside
         * it. And `floating disable` re-tiles the window next to whatever is **focused**, not next
         * to where it came from, so the surface has to be focused first or the dock reattaches in
         * a stranger's tab. Both on headless sway 1.12.
         *
         * **They go out as one IPC message, and that is #172.** sway executes a `;`-separated
         * sequence in a single `RUN_COMMAND` from end to end before it presents anything, whereas
         * a separate round trip per command presents each half-finished layout to the user.
         * Measured on headless sway 1.12 by sampling the dock's geometry continuously across a run
         * of restores, with the un-batched sequence and this one as the only difference: unbatched,
         * **39 of 218 readings** were an intermediate layout — 23 with the panel visible, floating
         * and centred over the surface, 16 with it tiled 50/50 — against **0 of 170** batched. #172
         * reports the same thing for a single restore, at 84 floating and 1 tiled out of 400.
         * Jason reported it as *"a bit of a flicker in the toggle"*, and the flicker is a real
         * layout that reached the screen rather than a rendering artefact.
         *
         * The order inside the batch is the order the separate commands ran in, `scratchpad show`
         * first and the surface focused between it and `floating disable`. That is deliberate: the
         * show focuses the window it reveals, so focusing the surface *before* it would leave the
         * dock focused at the moment `floating disable` picks a neighbour, which is the failure
         * `a restore lands beside its own surface and not beside whatever is focused` covers.
         *
         * **The wait that used to sit mid-sequence cannot survive that, so it is now a readback of
         * the outcome** — see [dockFault]. It asks a strictly stronger question than the poll
         * it replaces: that one established only that the window had left the scratchpad and said
         * nothing about where it landed, so a `floating disable` that never took would have passed
         * it. The two focus flags stay outside the batch, and only they: [settleFocus] is *defined*
         * by a tree read — it refuses to focus a target that has left the tree — so it cannot be
         * written as a blind command, and neither it nor [WmFlags.dockFocusOnShow] may run before
         * the readback has confirmed there is a restored dock to focus. Neither changes geometry,
         * which is what the sampling above measures.
         *
         * The `con_id` is what every command here targets. Under the default
         * [WmFlags.dockIdentity] every dock reports the same `app_id`, so once two docks can be
         * hidden at once an `app_id` criterion names an arbitrary one of them.
         */
        suspend fun showDock(surface: SurfaceId, dock: SurfaceId): Boolean {
            if (!inScratchpad(dock)) return false
            val cfg = config
            val before = tree()
            val hidden = before.find(dock.raw)
            val asHidden = cfg[WmFlags.showWidth] == ShowWidth.AS_HIDDEN
            // Read before the batch, because the batch cannot ask: whether the surface still sits
            // in a split decides whether one has to be made. A surface with no split above it —
            // the hide collapsed it under wm.dock.flatten_on_hide, or a user flattened the tab
            // by hand — would otherwise have `floating disable` tile the dock in beside it *as a
            // tab of its own*, measured on headless sway 1.12, rather than beside it. Decided by
            // the tree rather than by the flag, because the flag can have been flipped between
            // the hide and this show and the tree is what the batch will actually meet.
            val floating = floatingAncestorOf(before, surface)
            // And a split is only the dock's to come back into when it holds nothing else: the
            // surface alone, or beside its other docks. One the surface shares with a window of
            // the user's is split again, the same rule attach applies (#171) — tiling the dock
            // into it put the panel inside the user's container, and the detach after then had
            // `split none` refused for the user's window.
            //
            // And only a split that is awakener's (#248). Where the surface sits alone in a split
            // of the user's — the hide lifted it out of attach's — `split` would re-lay theirs in
            // place, so the dock is tiled in beside the surface first and the surface wrapped
            // after, as attach does.
            val split = dockSplitOf(before, surface)
            val usable = split?.takeIf {
                ownsSplit(it, cfg) && (it.nodes.size == 1 || holdsOnlyDocksOf(it, surface, cfg))
            }
            val wrapAfter = usable == null && split?.nodes?.size == 1
            val resplit = usable == null && !wrapAfter
            // Every split the batch makes is marked inside it, by [splitAndClaim], for attach's
            // reason: a split made here and marked by a later call is one a failure between the
            // two leaves unmarked, and so standing for good.
            val wraps = splitWraps(before, surface)
            val dockFirst = cfg[WmFlags.dockSide] == DockSide.LEFT
            // #267. Coming back into a split of the surface's own inside the one it was hidden
            // from, which the user's window took over (#256): that one is widened by the dock's
            // width first, taken back from every window the hide gave it to.
            val across = hidden?.takeIf { asHidden && floating == null }
                ?.let { acrossSplits(before, it, surface, usable, resplit && wraps, dockFirst) }
            // #272. Coming back into the surface's own split, at the dock's home width's share
            // of the windows it meets there.
            val home = hidden?.takeIf { asHidden && floating == null && across == null }
                ?.let { homeWidthOf(before, it, surface, usable) }
            val homeWidth = home?.dockWidth
            val dockWidth = across?.dockWidth ?: homeWidth
            // #264. The share the dock was hidden at, where it is coming back into the split it
            // was hidden from.
            val sizePpt = across?.ppt
                ?: home?.ppt
                ?: hiddenWidthOf(hidden, usable)?.takeIf { asHidden }
                ?: cfg[WmFlags.dockSizePpt]
            // Only into a split the dock shares with something besides the surface: beside
            // the surface alone, sway's equal share is the surface's whole share, and the plain
            // resize below is already exact.
            val proportional = across?.size ?: usable?.takeIf { asHidden && floating == null && it.nodes.size > 1 }
                ?.let { it.proportionalRestore(surface.raw, dock.raw, homeWidth ?: ((it.rect?.width ?: 0) * sizePpt / 100), dockFirst) }
            val restore = buildList {
                // Read above; gone before anything can fail, so no later show finds it stale.
                widthMarksOn(hidden).forEach { add("[con_id=${dock.raw}] unmark $it") }
                add("[con_id=${dock.raw}] scratchpad show")
                // Before `floating disable`, because that re-tiles beside the focused node.
                add("[con_id=${surface.raw}] focus")
                // Before the split, so that where the batch makes one around the surface it
                // inherits the surface's widened share, and before the dock is tiled in, which
                // is what [acrossSplits] planned the inner split from.
                across?.let { addAll(it.grow) }
                if (floating == null) {
                    // After the focus, because `split` applies to the focused node — the same
                    // order attach uses, and inside the batch for #172's reason: one message,
                    // one present.
                    if (resplit) add(if (wraps) splitAndClaim(surface, before) else "split horizontal")
                    add("[con_id=${dock.raw}] floating disable")
                    if (wrapAfter) {
                        // Beside the surface first, for attach's reason: `floating disable` tiles
                        // beside the focused node, which the read above cannot promise is still
                        // the surface.
                        add(moveToMarkOn(surface.raw, dock.raw))
                        add(splitAndClaim(surface, before))
                        add(moveToMarkOn(surface.raw, dock.raw))
                    }
                } else {
                    // #229. `floating disable` tiles beside the focused *tiled* node, and a
                    // floating surface is not one, so the dock is tiled wherever that puts it and
                    // then moved beside the surface by mark — into the floating split, which a
                    // surface the hide left floating alone gets back first. A `split` on a
                    // floating window makes a floating split on its rectangle, measured on 1.12.
                    if (floating.id == surface.raw) add(splitAndClaim(surface, before))
                    add("[con_id=${dock.raw}] floating disable")
                    add(moveToMarkOn(surface.raw, dock.raw))
                }
                // The same two commands attach ends with, because a restore owes the caller the
                // dock's geometry back: the scratchpad keeps neither side nor width. The width is
                // the share it was hidden at under wm.dock.show_width=AS_HIDDEN, and beside other
                // docks it is walked in proportionally rather than set (#264).
                if (dockFirst) add("[con_id=${dock.raw}] move left")
                when {
                    proportional != null -> addAll(proportional)
                    homeWidth != null -> add("[con_id=${dock.raw}] resize set width $homeWidth px")
                    else -> add("[con_id=${dock.raw}] resize set width $sizePpt ppt")
                }
            }.joinToString("; ")
            val claimed = if (floating == null) (resplit && wraps) || wrapAfter else floating.id == surface.raw
            // Together, so a cancellation landing on the restore's acknowledgement cannot skip the
            // settle and leave the claim's anchor behind: show has no unwind to settle it in.
            withContext(NonCancellable) {
                run(restore)
                if (claimed) settleSplitMarks()
            }

            checkDocked(surface, dock, sizePpt, dockWidth, "show", cfg[WmFlags.unmapWaitMs])
            takeRefusedWidthFromSurface(surface, dock, sizePpt)

            // Re-issued rather than relied upon. Under wm.dock.flatten_on_hide the hide collapsed
            // the split, so the `split horizontal` above made a *new* container that has never
            // been named; with the flag off the old one usually survives a hide and this changes
            // nothing. A user who flattened the tab by hand lands in the first case either way.
            applyTabTitle(surface)

            if (cfg[WmFlags.dockFocusOnShow]) focus(dock)
            if (cfg[WmFlags.restoreFocusAfterShow]) settleFocus(surface, dock)
            return true
        }

        /**
         * Fails unless [dock] stands beside [surface] at the width [action] gave it, naming what
         * is wrong — see [dockFault]. Polled for up to [waitMs] where that is given, and read
         * once where it is not.
         *
         * Held from inside the poll rather than re-read after it: a diagnosis taken once the
         * deadline has expired races a tree that may have settled in the meantime, and would
         * report "nothing wrong" for the run that just failed.
         */
        suspend fun checkDocked(
            surface: SurfaceId,
            dock: SurfaceId,
            sizePpt: Int,
            dockWidth: Int?,
            action: String,
            waitMs: Long? = null,
        ) {
            var fault: String? = null
            val share = resizeShare()
            val read: suspend () -> Boolean? = {
                fault = dockFault(surface, dock, sizePpt, share, dockWidth, action)
                if (fault == null) true else null
            }
            val landed = (if (waitMs == null) read() else pollTree(waitMs, read)) == true
            check(landed) {
                "dock ${dock.raw} was not docked beside surface ${surface.raw} " +
                    (waitMs?.let { "${it}ms " } ?: "") + "after the $action went out: $fault"
            }
        }

        /**
         * What is wrong with [dock] as a dock placed by [action], or null when nothing is.
         *
         * Four questions in the order that makes each one's answer mean something, because sway
         * reports a `percent` for a floating window too — 0.37 for a panel hovering over the
         * surface, measured on 1.12 — and none at all for one in the scratchpad. So a width test
         * on its own would pass a dock that never re-tiled.
         *
         * The tolerance is not slack. `resize set width 30 ppt` reads back as
         * `0.29999999999999999`, and the layouts this is here to reject are nowhere near: the
         * 50/50 sway leaves behind when only `floating disable` has run is `0.5`.
         *
         * A dock short of its width is not a fault where sway refused the resize ([refusedResize],
         * #261): the restore did everything it could, and the layout is one sway will not leave.
         * That is decided by sway's own rule rather than by a wider tolerance, so the same dock
         * short of its width in a split sway *would* have resized still fails the readback. And
         * only in [surface]'s own split: a dock that ended up in some other crowded split is
         * misplaced, however reasonable sway's refusal there was.
         *
         * The width asked for is [dockWidth] px of the dock's split where the show gave it one
         * ([acrossSplits], #267), and [sizePpt] of it otherwise.
         */
        private suspend fun dockFault(
            surface: SurfaceId,
            dock: SurfaceId,
            sizePpt: Int,
            share: ResizeShare,
            dockWidth: Int?,
            action: String,
        ): String? {
            val tree = tree()
            val node = tree.find(dock.raw) ?: return "it is not in the tree at all"
            if (tree.workspace(SCRATCH_WORKSPACE)?.find(dock.raw) != null) {
                return "it is still in the scratchpad"
            }
            if (node.type == FLOATING_CON) {
                return "it is floating over the surface rather than tiled beside it"
            }
            val percent = node.percent
                ?: return "sway reports no width fraction for it, so it is in no tiled container"
            val parent = tree.parentOf(dock.raw)
            // A dock stacked over or under its surface — another client's `split vertical`
            // around the surface re-laid the split it is in (#265) — reports a *height* fraction,
            // which no width can be compared with.
            if (parent?.layout == "splitv") return "it is stacked in a vertical split rather than beside the surface"
            val wanted = dockWidth?.let { px -> parent?.rect?.width?.takeIf { it > 0 }?.let { px.toDouble() / it } }
                ?: (sizePpt / 100.0)
            val excused = parent?.id == tree.parentOf(surface.raw)?.id &&
                tree.refusedResize(dock.raw, sizePpt, share) != null
            if (abs(percent - wanted) > PPT_TOLERANCE && !excused) {
                return "it is ${percent * 100}% of its container wide rather than the " +
                    (dockWidth?.let { "$it px" } ?: "$sizePpt%") + " the $action gave it"
            }
            return null
        }

        /**
         * Under [WmFlags.crowdedWidth] `FROM_SURFACE`, gives [dock] the width sway refused it
         * (#261) out of [surface] alone, which it sits right beside.
         *
         * `resize grow left|right` takes the growth from the one neighbour on that side, so the
         * docks squeezed towards the floor are not asked for any of it. Skipped where the
         * surface itself would end under the floor, which sway would refuse too — the dock then
         * keeps the share it has, as under `AS_SWAY_LEAVES`.
         */
        suspend fun takeRefusedWidthFromSurface(surface: SurfaceId, dock: SurfaceId, sizePpt: Int) {
            if (config[WmFlags.crowdedWidth] != CrowdedWidth.FROM_SURFACE) return
            val tree = tree()
            val shortfall = tree.refusedResize(dock.raw, sizePpt, resizeShare())?.takeIf { it > 0 } ?: return
            val siblings = tree.parentOf(dock.raw)?.nodes ?: return
            val index = siblings.indexOfFirst { it.id == dock.raw }
            val edge = when (surface.raw) {
                siblings.getOrNull(index - 1)?.id -> "left"
                siblings.getOrNull(index + 1)?.id -> "right"
                else -> return
            }
            val room = siblings.first { it.id == surface.raw }.rect?.width ?: return
            if (room - shortfall < MIN_SANE_WIDTH) return
            run("[con_id=${dock.raw}] resize grow $edge $shortfall px")
        }

        /**
         * Leaves the tab focused on whichever child the resting-focus flag names.
         *
         * This is the fix for the sharpest hazard the probe found: sway remembers the last
         * focused child per container, so a tab left resting on the dock means the *next*
         * switch into that tab puts the user's keystrokes into the agent panel instead of the
         * application.
         */
        suspend fun settleFocus(surface: SurfaceId, dockId: SurfaceId) {
            val target = when (config[WmFlags.restingFocus]) {
                RestingFocus.APP -> surface
                RestingFocus.DOCK -> dockId
            }
            if (tree().find(target.raw) != null) focus(target)
        }

        /**
         * Waits for a window with [appId] that is not one of [standing] to appear.
         *
         * Polls rather than listening for the `new` event so that [attach] does not depend on
         * [WmFlags.eventsEnabled]; attaching a dock has to keep working with events off. What
         * the polling costs, and why it is not a bare spin, is [pollTree]'s.
         */
        suspend fun awaitWindow(
            appId: String,
            standing: Set<Long>,
            timeoutMs: Long = config[WmFlags.mapWaitMs],
            /**
             * A second predicate the window has to satisfy, defaulting to none.
             *
             * This is the hook [StoodUpProof.TOKEN_REQUIRED] uses, and it is here rather than in
             * `attach` because the difference between the two token values is precisely *where*
             * the check runs: applied to the answer it decides what the entry says, applied to
             * the wait it decides whether there is an answer at all.
             */
            accept: (Node) -> Boolean = { true },
        ): Node? = pollTree(timeoutMs) {
            tree().windows.firstOrNull { it.appId == appId && it.id !in standing && accept(it) }
        }

        suspend fun awaitGone(id: SurfaceId, timeoutMs: Long = config[WmFlags.unmapWaitMs]): Boolean =
            pollTree(timeoutMs) { if (tree().find(id.raw) == null) true else null } ?: false
    }

    /**
     * Re-reads the tree until [read] answers, or until [timeoutMs] is up.
     *
     * **Paced, not spun**, and the pacing is the whole of #49. A loop whose only concession was
     * `yield()` issued 6,637–11,085 `get_tree` round trips per second against headless sway 1.12,
     * for 59% of a compositor core and 41% of a client core against a 0.0%/0.0% idle control — so
     * a 5s deadline that expired cost roughly 33,000 round trips, 200 MB of JSON and 2.9s of
     * compositor CPU to establish that a dock had not appeared. Every one of those reads is a
     * full serialisation of the layout tree, and they are issued on the same connection every
     * other read shares.
     *
     * **The obvious hypothesis was tested and refuted, so this is a trade rather than a free
     * win.** Spinning does not starve the compositor of the time it needs to map the very window
     * being waited for: over 8 alternated trials the spin detected a dock at a median of 16.5ms
     * against 26.1ms at a 25ms poll — about 10ms *sooner*. That 10ms is on the hotkey path, which
     * is why the default keeps it where it is worth having: [WmFlags.pollSpinMs] of unpaced reads
     * covers the window in which a dock almost always maps, and [WmFlags.pollIntervalMs] takes
     * over for the long tail, where the reads are buying nothing but heat. Counted at the socket
     * on the same headless sway 1.12 — see `a dock that never maps costs a paced poll, not a
     * spin`, which is what does the counting — a 5s deadline that expires costs **1,719** tree
     * reads at the stock defaults against **~27,500** spun, and 43 against 5,516 over a 1s
     * deadline paced from the first read.
     *
     * Both flags are re-read on every iteration rather than captured, because a wait can outlive
     * the snapshot it started under the moment anything holds one. That is a property of the
     * design rather than of today's binary: `:config` has the reload mechanism —
     * `FileConfigStore.watch` — and nothing calls it, because every entry point is one-shot
     * (#43). So this is written forward, not against an observed reload. [timeoutMs] is
     * necessarily read once by the caller: it is the deadline's own definition, and a deadline
     * that moved while being waited on would not be one.
     *
     * **Nothing is coerced here**, deliberately. Each flag declares `Flags.atLeast(0)`, so a
     * negative is rejected in `Config.of`, reported through `Config.problems`, and resolved by
     * `wm`-agnostic policy — degraded to the default or clamped, per `config.invalid_value`.
     * Coercing again at the read site is what #69 removed elsewhere and for the reason that
     * applies here too: it silently supplies a value the operator did not type while
     * `awakener-config` reports a clean file.
     *
     * Nothing here is the design's forbidden unattended action: this runs only inside a call a
     * caller made, and it ends when that call does.
     */
    /**
     * What a detach that collapsed a floating split leaves to watch (#253): [surface], now
     * floating alone, owed [target] — the split's rectangle — and liable to a late commit of one
     * of [stale], the widths it was given beside its dock.
     */
    private class FloatingSettle(val surface: SurfaceId, val target: Rect, val stale: Set<Int>)

    /**
     * Puts [settle]'s surface back to its full size if, within [WmFlags.floatingDetachSettleMs],
     * its client reverts it to the size it had beside the dock (#253).
     *
     * The collapse is the one moment the surface becomes a floating window of its own, and sway
     * resizes a floating window to whatever its client next commits. A client that had not yet
     * committed the narrower size the split gave it — a detach soon after an attach or a show —
     * commits it after the collapse, and the window would keep the dock's share of its width lost
     * for good: #232's loss, met by the detach rather than the hide. Nothing over IPC says when a
     * client has committed, so this watches the outcome.
     *
     * **Only that outcome is corrected, and only its size** (the review of #255). A client can
     * change its size and never its position, so the one thing put back is a window that is
     * still where the split was, at its height, and at one of the widths awakener gave it beside
     * the dock, which is grown back to the split's width from its right edge, so that nothing
     * moves it. Any other change — the user moving it, dragging it, resizing it — is the user's, and
     * ends the watch at once, so the settle never fights them. Unlocked between corrections: it
     * reads the tree the way every reader does, and each correction is its own short edit.
     */
    private suspend fun holdFloatingSize(settle: FloatingSettle) {
        val cfg = config
        val window = cfg[WmFlags.floatingDetachSettleMs].milliseconds
        var corrections = 0
        var held = TimeSource.Monotonic.markNow()
        while (held.elapsedNow() < window) {
            val now = tree().find(settle.surface.raw)?.takeIf { it.type == FLOATING_CON }?.rect ?: return
            when {
                now.near(settle.target) -> Unit
                corrections < SETTLE_CORRECTIONS && now.isStaleOf(settle) -> {
                    treeEdit {
                        // Grown from its right edge rather than `resize set`, which grows a
                        // floating window about its centre (measured on 1.12: a 697 → 995
                        // correction left it 149 px left of the split) and would then need a
                        // move — one that undoes a user's own move landing between this read
                        // and the command. Growing moves no edge but the right one.
                        run(
                            "[con_id=${settle.surface.raw}] resize grow right " +
                                "${settle.target.width - now.width} px",
                        )
                    }
                    corrections++
                    held = TimeSource.Monotonic.markNow()
                }
                else -> return
            }
            delay(cfg[WmFlags.pollIntervalMs].milliseconds)
        }
    }

    /** Within [GEOMETRY_SLACK] of [other] on every side. */
    private fun Rect.near(other: Rect): Boolean =
        maxOf(abs(x - other.x), abs(y - other.y), abs(width - other.width), abs(height - other.height)) <=
            GEOMETRY_SLACK

    /**
     * Where [settle]'s split was, at its height and one of its stale widths: a client's revert,
     * not a user's edit.
     */
    private fun Rect.isStaleOf(settle: FloatingSettle): Boolean =
        settle.stale.any { near(settle.target.copy(width = it)) }

    private suspend fun <T : Any> pollTree(timeoutMs: Long, read: suspend () -> T?): T? =
        withTimeoutOrNull(timeoutMs) {
            val started = TimeSource.Monotonic.markNow()
            while (true) {
                read()?.let { return@withTimeoutOrNull it }
                val cfg = config
                if (started.elapsedNow() < cfg[WmFlags.pollSpinMs].milliseconds) {
                    yield()
                } else {
                    delay(cfg[WmFlags.pollIntervalMs].milliseconds)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    suspend fun tree(): Node = tree(session())

    /** [tree] against one named session; see [TreeEdit.tree] for why that is ever worth naming. */
    private suspend fun tree(session: Session): Node =
        swayJson.decodeFromString(session.connection.request(I3Ipc.Request.GET_TREE))

    /**
     * A tree node as the compositor-agnostic thing above this module sees.
     *
     * One conversion for both readers, which is the point of it existing: [surfaces] wants the
     * whole [Surface] — [Surface.focused] included, which is what the hotkey entry point reads
     * to mean "this window" (#55) — and [resolve] wants only [Surface.descriptor], where
     * `focused` is deliberately absent because two windows of one app are the same surface
     * whichever is focused now. Keeping them one function is what stops the two from drifting
     * on which fields a node contributes.
     */
    private fun Node.asSurface(): Surface = Surface(SurfaceId(id), appId, name, pid, focused)

    /**
     * The durable key for a live **bindable** window, or null if it has gone or is a dock.
     *
     * The whole translation from compositor handle to durable identity happens here and in
     * [resolve], and nowhere else, which is what keeps `:registry` from ever learning what a
     * `con_id` is.
     *
     * This is the enumerating form, and `attach` is what wants it: standing a dock beside a dock
     * is not an attach, so the filter is the precondition rather than an accident of reuse — a
     * `keyFor` that answered for any node at all would turn `attach`'s "no such surface" check
     * into one that passes on the agent panel. `resolve` deliberately does **not** come through
     * here under the default [WmFlags.resolveKeySource]; see there.
     */
    suspend fun keyFor(surface: SurfaceId): SurfaceKey? =
        surfaces().firstOrNull { it.id == surface }
            ?.let { SurfaceKey.of(it.descriptor, config) }

    /**
     * Every window that is not a dock, since a dock is a genuine tree node and is otherwise
     * indistinguishable from a surface needing an agent.
     *
     * Dock-ness is [dockedTo]'s union, plus the `app_id` of any attach still in flight. What that
     * buys is the window this used to answer wrong: the mark lands a round trip after the dock
     * maps, and a dock reported here is a dock `resolve` calls a Drab and a hotkey mints an agent
     * for.
     *
     * Takes no lock and waits on nothing, so the tree it read may be a round trip out of date by
     * the time this returns — that is the deliberate trade, since enumeration is the first thing
     * a hotkey does and an attach holds the tree for as long as a dock takes to map.
     */
    override suspend fun surfaces(): List<Surface> {
        val cfg = config
        val windows = tree().windows
        // Read after the tree, not before: an attach that records its dock between the two reads
        // is then covered by this snapshot, where the opposite order could see neither.
        val table = docks.snapshot()
        return windows
            .filter { dockedTo(it, table, cfg) == null && !reserved(it, table, cfg) }
            .map { it.asSurface() }
    }

    /**
     * The agent bound to [surface], from the durable registry.
     *
     * **The dock table is not in this path**, which is the design note's tripwire made
     * mechanical rather than promised (#52). Under the default [WmFlags.resolveKeySource] the
     * whole of `resolve` is: read the tree, derive the key from facts that outlive the window,
     * ask `:registry`. Nothing in it can reach [docks], so no future edit can quietly make the
     * answer depend on this compositor session — a `con_id` table is meaningless after a reboot
     * and the binding it would be answering about is not.
     *
     * That it was previously reached *through* `surfaces()` was not a durability defect — the
     * answer was always the registry's, since the table holds no agent — but it did make the set
     * of windows `resolve` would answer for depend on session state, in the direction that
     * costs: a surface the table is hiding resolved as a Drab however durably it was bound, and
     * a caller acting on that mints a second agent for a surface that already has one. The two
     * ways to be hidden are a recognition the table latched at some past read (see
     * [WmFlags.reapEvidence], where that latch is the stated residual) and an in-flight attach's
     * reservation over a shared dock `app_id` (see [WmFlags.dockIdentity]).
     *
     * What it gives up is that `resolve` used to answer nothing for a dock. Under `TREE` a dock
     * is an ordinary node and resolves to whatever the registry holds under the key its `app_id`
     * yields — nothing, unless something bound that key. Callers obtain surface ids from
     * [surfaces], which excludes docks under both values, so this is a difference in what the
     * call *would* say rather than in what any caller asks it.
     */
    override suspend fun resolve(surface: SurfaceId): AgentId? {
        val cfg = config
        val key = when (cfg[WmFlags.resolveKeySource]) {
            ResolveKeySource.TREE -> tree().windows.firstOrNull { it.id == surface.raw }
                ?.let { SurfaceKey.of(it.asSurface().descriptor, cfg) }
            ResolveKeySource.ENUMERATION -> keyFor(surface)
        } ?: return null
        return registry.resolve(key)?.agent
    }

    /**
     * Stands a dock up beside [surface], as a transaction over the tree.
     *
     * It returns a handle whose `detach` owns everything the attach did, or it throws having
     * taken its own tree edits back — the split container it created, and the dock window
     * whether or not the dock mapped in time for the attach to identify it (see
     * [TreeEdit.unwindAttach], which reads the tree for one it never saw). The compensations are
     * best-effort: one that fails is suppressed onto the exception that caused the unwind rather
     * than replacing it, so "took its edits back" is what was attempted and reported, not a
     * guarantee sway can be held to.
     *
     * Three things are outside that, and each is named where it is spent rather than designed
     * around:
     *
     * - a `no_focus` rule under [FocusSuppression.NO_FOCUS_RULE], which sway has no verb to
     *   revoke. Not the default, and issued at most once per `app_id` per session;
     * - the dock program, which is `exec`'d before anything can fail and which sway acknowledges
     *   with nothing to cancel it by. Nothing stops it mapping after the attach has given up, and
     *   the unwind can only take down a window that is already in the tree when it looks. A dock
     *   that maps after the unwind's last look stands as a panel in no table, carrying no mark,
     *   which enumeration reports as bindable. Nothing collects it: [collectRepairs] collects
     *   [changes] now, but the sweep it drives recognises a dock by mark or by table and this
     *   window is in neither. What reaches it is the claim the design note specifies — which this
     *   attach does not file and nothing reads (#32);
     * - [WmFlags.unwindFailedAttach] set to false, which leaves the tree wreckage standing
     *   deliberately.
     *
     * awakener's own bookkeeping — the reservation and the dock's table entry — is cleared on
     * both paths and is gated by no flag, because a leak there is invisible in the tree while
     * blinding [surfaces].
     */
    override suspend fun attach(
        surface: SurfaceId,
        dock: DockSpec,
        agent: AgentId?,
    ): DockHandle {
        val cfg = config
        val key = keyFor(surface) ?: error("no such surface: ${surface.raw}")
        val appId = when (cfg[WmFlags.dockIdentity]) {
            DockIdentity.NEW_NODE -> dock.appId
            DockIdentity.PER_SURFACE_APP_ID -> {
                check(dock.command.contains(DockSpec.APP_ID_PLACEHOLDER)) {
                    "wm.dock.identity=PER_SURFACE_APP_ID needs the dock command to carry " +
                        "'${DockSpec.APP_ID_PLACEHOLDER}', or the dock reports '${dock.appId}' " +
                        "like every other dock and the name is no identifier; command was: " +
                        dock.command
                }
                "${dock.appId}-${surface.raw}"
            }
        }
        val command = dock.command.replace(DockSpec.APP_ID_PLACEHOLDER, appId)

        // Drawn per attach, and drawn at all only where something will read it. See
        // WmFlags.stoodUpProof: what it proves is that the window which answered the wait is the
        // program this attach exec'd, which the app_id — a string that window's own client
        // declares about itself — never did.
        val proof = cfg[WmFlags.stoodUpProof]
        val token = if (proof == StoodUpProof.NONE) null else newSpawnToken()
        // `env` rather than a shell assignment prefix because sway hands the whole string to
        // `sh -c` and an assignment binds to the first simple command only; `env` is equally
        // defeated by a command that is a pipeline or a list, which is why the flag says the
        // token has to reach the process that owns the surface for TOKEN_REQUIRED to work at all.
        // The dock command in `:cli` is already of this shape — `foot -a {app_id} -- env … claude`
        // — so the environment reaching foot is the case that matters and it is the one that does.
        val spawnCommand = if (token == null) command else "env $SPAWN_TOKEN_VAR=$token $command"
        // One predicate, used by the wait and by the unwind's look for a window it never
        // identified. They have to be the same one: an attach that refuses to *adopt* an
        // interloper and then kills it on the way out has closed one route into that window and
        // left the other open, which is the shape `strayDock` already documents.
        //
        // A named local rather than a lambda so that `token`'s nullability is discharged by a
        // branch the compiler smart-casts, instead of by a `!!` resting on a fact stated one line
        // up. The third branch cannot be reached — `token` is null under exactly one value of the
        // flag and TOKEN_REQUIRED is not it — and is written out anyway so that the case which
        // cannot happen still falls the safe way: refusing to adopt costs a failed attach, while
        // adopting on no proof costs the window this flag exists to protect.
        fun acceptsAsDock(node: Node): Boolean = when {
            proof != StoodUpProof.TOKEN_REQUIRED -> true
            token != null -> carriesSpawnToken(node, token)
            else -> false
        }

        val acceptDock: (Node) -> Boolean = ::acceptsAsDock

        // Bookkeeping, not compensation, which is why it is a `finally` around the whole method
        // and is gated by no flag: a reservation left behind is invisible in `swaymsg -t get_tree`
        // and hides every window under the dock's app_id until its deadline
        // (wm.wait.reservation_grace_ms, 5s by default), and a failed attach's table entry names
        // a node nothing owns. Tree repair is a different job, done under the lock, and is not
        // here (#6).
        //
        // The bound is real and this eviction is still unconditional, which is the part worth
        // stating because the comment used to say "for the life of the process" and that was
        // never true — `DockReservation.covers` has consulted the deadline since the reservation
        // was introduced in #23, and `DockTableTest` pins it (#108). The argument survives the
        // correction and does not need the overstatement: the grace is a backstop for an attach
        // that *died* mid-flight, not a substitute for eviction by one that merely failed, and a
        // reservation whose attach has already returned is suppressing on evidence known to be
        // stale. Five seconds of blinding one app_id is a cost to avoid, not one to accept
        // because it is finite.
        var reservation: DockReservation? = null
        var recorded: SurfaceId? = null
        var attached = false
        val suppression = cfg[WmFlags.dockFocusSuppression]
        try {
            // The session comes back out with the dock id because the handle has to carry it: a
            // `con_id` only means anything against the session that minted it, and a manager that
            // can reconnect will outlive this one. See [WmFlags.staleHandles].
            val (dockId, dockSession) = treeEdit {
                // What this attach has put into the tree so far, which is what the unwind takes
                // back. Tracked rather than re-derived: a `split none` on a container this attach
                // did not create is a different edit, and sway would refuse it anyway.
                var container = false
                var spawned: SurfaceId? = null
                // What identifies the dock between the exec and `spawned` being assigned. See
                // PendingDock: without it the unwind cannot name a window that maps in that span.
                var pending: PendingDock? = null
                try {
                    // Before anything is touched, focus included, so a refusal leaves the tree
                    // exactly as the press found it (#253).
                    refuseByFlag(tree(), surface, cfg)
                    // Focus first: sway's split applies to the focused container, and the dock has
                    // to land inside this surface's tab rather than wherever focus happened to be.
                    focus(surface)
                    // A surface that already sits in its own dock split — a second dock, under
                    // invoke.when_animated=SECOND_DOCK or a press the bus could not yet answer —
                    // takes the new dock into that split rather than being wrapped in another
                    // (#171). The wrapper was the leak: sway's `split` on a window with siblings
                    // makes a fresh container, so the surface went one level deeper per extra
                    // dock, and a dock closed by hand runs no awakener code to take the level
                    // back. Two docks closed by hand compounded 2 → 6 single-child splits over
                    // five rounds. Not this attach's container, so the unwind leaves it alone.
                    //
                    // Only a split that is awakener's (#248). A user's split holding the surface
                    // and a dock a build before the provenance mark put there is not shared.
                    val before = tree()
                    val split = dockSplitOf(before, surface)
                    val sharing = cfg[WmFlags.shareDockSplit] && split != null &&
                        ownsSplit(split, cfg) && holdsOnlyDocksOf(split, surface, cfg)
                    // #248. sway's `split` on the only child of a split does not wrap it: it
                    // re-lays the parent in place — a user's `splitv` turned `splith` — and the
                    // dock then maps into the user's container, which the last detach used to
                    // flatten away. There is no command that wraps an only child, so this split
                    // is made once the dock has mapped beside the surface and given it a sibling.
                    var wrapAfterMap = !sharing && split != null && split.nodes.size == 1 &&
                        !ownsSplit(split, cfg)
                    // Recorded before the command rather than after it, for the same reason
                    // `pending` is: a fact recorded on the far side of a round trip is one the
                    // failure that needs it can arrive ahead of — a cancellation landing on this
                    // acknowledgement would otherwise leave the container built and unrecorded.
                    // Rolled back only where sway itself refuses, which is the one failure that
                    // means the container certainly does not exist.
                    container = !sharing && !wrapAfterMap
                    if (container) {
                        val claims = splitWraps(before, surface)
                        try {
                            // Marked in the same message as it is made, where it makes one: a
                            // split left unmarked is one nothing under wm.dock.split_ownership=
                            // MARKED ever flattens, and a message is the one unit no
                            // cancellation or process death can split. See [splitAndClaim].
                            run(if (claims) splitAndClaim(surface, before) else "split horizontal")
                        } catch (rejected: IllegalStateException) {
                            // Rolled back only where no split of awakener's stands: the message
                            // carries more than the split, and a refusal of a later command in it
                            // leaves the split made and marked.
                            container =
                                dockSplitOf(settleSplitMarks(), surface)?.carriesLiveSplitMark() == true
                            throw rejected
                        }
                        val settled = settleSplitMarks()
                        if (claims && settled.parentOf(surface.raw)?.carriesLiveSplitMark() != true) {
                            // #252: the split re-laid a container that stood when the message ran
                            // — another client's, made around the surface since the read — so
                            // the claim was refused and nothing of awakener's was made. The
                            // surface is alone in that container now, which is wrap-after-map's
                            // case exactly.
                            container = false
                            wrapAfterMap = true
                        }
                    }

                    // Must precede the exec — sway evaluates focus rules when the window maps, so
                    // issuing this afterwards would be too late to prevent the steal. The other
                    // mechanism corrects the steal instead and so waits until the dock is up.
                    if (!cfg[WmFlags.dockFocusOnMap] &&
                        suppression == FocusSuppression.NO_FOCUS_RULE
                    ) {
                        suppressFocusFor(appId)
                    }

                    // Taken after the no_focus rule and before the exec, so it is exactly the set
                    // of docks that were already standing. Matching the spawned dock on app_id
                    // alone would resolve to whichever of them sway happens to list first, since
                    // in production every dock is the same panel program and they all report the
                    // same name. The snapshot only identifies anything because nothing else can
                    // exec before the claim — in this process or any other, which is why
                    // treeEdit holds the cross-process lock and not only the mutex (#199).
                    val standing =
                        tree().windows.filter { it.appId == appId }.map { it.id }.toSet()

                    // Filed before the window it describes can exist, which is the whole of it: a
                    // con_id is minted when the dock maps, so nothing keyed on one can cover the
                    // dock at the moment it becomes visible to a reader of the tree.
                    if (cfg[WmFlags.dockPendingSuppression]) {
                        reservation = docks.reserve(
                            appId,
                            standing,
                            cfg[WmFlags.reservationGraceMs].milliseconds,
                        )
                    }
                    // Filed before the exec for the reason above it: from the moment the command
                    // goes out this attach may have a window in the tree, and the map deadline
                    // can expire before anything has identified it. Ungated, because it is not a
                    // suppression — nothing reads it but the unwind.
                    pending = PendingDock(appId, standing, acceptDock)
                    // The other route by which the token would outlive the attach, and it is the
                    // same argument as the `command` below: `run` quotes back what it was given,
                    // so a sway rejection of this exec would put `spawnCommand` — token and all —
                    // into an exception message. Harmless in itself, since a rejected exec starts
                    // no process and so no window can ever carry that token, but the two paths out
                    // of this one call should not disagree about what they are willing to print.
                    // The cause is dropped rather than chained for the same reason: its message is
                    // the string being kept out of the log.
                    try {
                        run("exec $spawnCommand")
                    } catch (rejected: IllegalStateException) {
                        throw IllegalStateException(
                            "sway rejected the dock exec; command was: $command",
                        )
                    }
                    // Under TOKEN_REQUIRED the proof is part of the wait, so a window that cannot
                    // show the token is passed over and this attach keeps waiting for its own
                    // dock. Under the other two values the wait is unchanged and the proof — if
                    // there is one — decides only what the table records below. `command` and not
                    // `spawnCommand` in the message: the token is a secret for the length of one
                    // attach, and an exception message is the one place it would outlive it.
                    val dockNode = awaitWindow(appId, standing, accept = acceptDock) ?: error(
                        if (proof == StoodUpProof.TOKEN_REQUIRED) {
                            "no window under '$appId' proved it was the program this attach " +
                                "spawned, which wm.dock.stood_up_proof=TOKEN_REQUIRED demands; " +
                                "a dock command that does not pass its environment through to " +
                                "the process owning the surface can never satisfy it. Command " +
                                "was: $command"
                        } else {
                            "dock '$appId' never appeared; command was: $command"
                        },
                    )
                    val dockId = SurfaceId(dockNode.id)
                    spawned = dockId

                    // Before the mark, and this order is the fix: the mark is a round trip away
                    // and enumeration does not take this lock, so a reader landing in between
                    // would be handed the agent panel as a bindable surface.
                    // What the entry *says* is the whole of #96. This attach has a dock either
                    // way — it is marked, moved and torn down by the handle below regardless —
                    // and the only reader of the origin is the orphan sweep under
                    // WmFlags.reapEvidence, which is about to be given permission to destroy this
                    // window when the surface closes. A window that answered on a name it
                    // declared about itself has not earned that; it is ADOPTED, which is what
                    // every other claim resting on something outside this process is.
                    val origin = when {
                        // TOKEN_REQUIRED proved it in the wait, so re-reading /proc here would
                        // only introduce a race with a dock that has since exited.
                        token == null || proof == StoodUpProof.TOKEN_REQUIRED ->
                            DockOrigin.STOOD_UP
                        carriesSpawnToken(dockNode, token) -> DockOrigin.STOOD_UP
                        else -> DockOrigin.ADOPTED
                    }
                    docks.record(dockId, surface, origin)
                    recorded = dockId

                    // Names the dock as well as the surface, and under the default scheme carries
                    // a nonce besides. A sway mark identifier is globally unique, so a mark naming
                    // only the surface is one two docks of that surface both want and the second
                    // attach takes it off the first (#14); the nonce is what keeps the shape out
                    // of reach of a mark somebody wrote for their own purposes (#35).
                    val mark = dockMarkFor(
                        dockId,
                        surface,
                        cfg[WmFlags.dockMarkPrefix],
                        cfg[WmFlags.dockMarkScheme],
                    )
                    run("[con_id=${dockId.raw}] mark --add $mark")
                    // #253. The dock maps beside whatever is focused when it maps, and a user who
                    // clicked another window during the start-up has put focus there: the panel
                    // then stood as a tab beside that window, or for a floating surface in the
                    // tiled layout behind it, and the split made for it was left holding the
                    // surface alone. Moved beside the surface by mark, which puts it into that
                    // split whether it floats or not; the window it mapped beside is left as it
                    // was. Not for wrapAfterMap, whose own message starts with this same move.
                    val mapped = tree()
                    if (!wrapAfterMap && mapped.parentOf(dockId.raw)?.id != mapped.parentOf(surface.raw)?.id) {
                        run(moveToMarkOn(surface.raw, dockId.raw))
                    }
                    if (wrapAfterMap) {
                        // The dock maps beside whatever is focused when it maps, which is the
                        // surface only if nobody moved focus during the start-up; so it is moved
                        // beside the surface first, which gives the surface the sibling `split`
                        // needs to wrap rather than re-lay the user's split, then into the new
                        // split beside it. One message, so nothing in between is presented. Focus
                        // goes back where the map left it, so the focus flags below see what they
                        // would have seen without the wrap. Recorded first for `container`'s
                        // reason above.
                        container = true
                        val focused = mapped.focusedId() ?: surface.raw
                        run(
                            moveToMarkOn(surface.raw, dockId.raw) + "; " +
                                splitAndClaim(surface, mapped, refocus = focused) + "; " +
                                moveToMarkOn(surface.raw, dockId.raw),
                        )
                        settleSplitMarks()
                    }
                    if (cfg[WmFlags.dockSide] == DockSide.LEFT) {
                        run("[con_id=${dockId.raw}] move left")
                    }
                    run("[con_id=${dockId.raw}] resize set width ${cfg[WmFlags.dockSizePpt]} ppt")
                    // #265. Read back as a show's restore is, with the same excuse for a width
                    // sway refused in the surface's own split: a dock another client put wrong
                    // meanwhile fails the attach, and the unwind below takes it down. Read once
                    // rather than polled: the dock has mapped, and sway applies a command before
                    // it replies, so nothing is left to settle and a fault is one waiting cannot
                    // cure — only stretch, by wm.wait.unmap_ms, before the unwind starts.
                    checkDocked(surface, dockId, cfg[WmFlags.dockSizePpt], null, "attach")
                    takeRefusedWidthFromSurface(surface, dockId, cfg[WmFlags.dockSizePpt])

                    // After the dock is in its final place, so the container this names is the one
                    // the user will be looking at. Inside the unwind's reach on purpose: a title
                    // set on a container a failed attach then flattens would otherwise be the one
                    // edit the unwind could not take back.
                    applyTabTitle(surface)

                    // The correction is part of the suppression, not of resting focus, so it runs
                    // whatever restore_after_attach says: that flag decides whether the
                    // resting-focus rule is applied, and with it off the one outcome
                    // focus_on_map=false asked for is the one that would not happen. Where focus
                    // *ends* is still settleFocus's to decide, which is why this comes first.
                    if (!cfg[WmFlags.dockFocusOnMap] &&
                        suppression == FocusSuppression.REFOCUS_AFTER_MAP
                    ) {
                        focus(surface)
                    }
                    if (cfg[WmFlags.restoreFocusAfterAttach]) settleFocus(surface, dockId)
                    // Last, because nothing above it would notice (#197). The mark, move and
                    // resize address the dock, and the title and focus steps are silent about a
                    // surface that has gone — so a surface closed during the map wait used to come
                    // back as a dock "beside" it, standing as a tab of its own until a reap. A
                    // close landing after this read is the orphan the next sweep exists for.
                    check(tree().find(surface.raw) != null) {
                        "surface ${surface.raw} closed while its dock was being stood up"
                    }
                    dockId to session
                } catch (failure: Throwable) {
                    unwindAttach(surface, spawned, pending, container, failure)
                    throw failure
                }
            }

            // Outside the tree edit on purpose: this is not one, and with a null agent it mints,
            // which reaches a spanreed subprocess. Holding the in-process tree mutex across a
            // process spawn would stall this manager's other edits behind it — the same call
            // `FileBindingStore.bind` makes one module down, for the same reason.
            //
            // That is the mutex, not the compositor. A caller inside [exclusively] still holds the
            // cross-process attach lock here, and `awakener-invoke` is one on purpose: it holds
            // that lock across the whole press, including its own mint before this attach, so a
            // second press queued behind it sees this dock rather than racing it (#199). Every
            // other press waits through the spawn; wm.attach.lock_wait_ms is sized for that.
            //
            // Still recorded only once the dock is standing, so a failed attach leaves no durable
            // binding to an agent that has no panel. A null agent is the hotkey case: the registry
            // resolves the surface's existing Lifeless or mints one, which is the only moment an
            // identity is ever minted — a trigger on window creation would spawn an agent for
            // every window glanced at and closed.
            val bound = try {
                registry.bind(key, agent?.asIdentity())
            } catch (failure: Throwable) {
                // The one compensation that cannot run inside the section that built the dock,
                // because that section ended before the bind began. Re-entering is safe in the
                // way the rejected top-level unwind is not: what stands here is a *complete* dock
                // in a complete container, an ordinary shape for another attach to interleave
                // with, rather than the half-built one the serialisation exists to hide.
                withContext(NonCancellable) {
                    // No pending dock: this attach identified its own, so there is no window of
                    // its making left for the unwind to find in the tree.
                    treeEdit {
                        unwindAttach(surface, dockId, pending = null, container = true, failure)
                    }
                }
                throw failure
            }
            attached = true
            return SwayDockHandle(surface, bound.agent, dockId, key, dockSession)
        } finally {
            reservation?.let(docks::release)
            // The dock this entry names is a window nothing holds a handle to: either it never
            // mapped, or the attach failed after it did and the tree unwind (#6) will take it
            // down. Suppressing it for the life of the process on the strength of a failed attach
            // is the leak this eviction exists to prevent.
            if (!attached) recorded?.let(docks::forget)
        }
    }

    override val changes: Flow<SurfaceChange> = callbackFlow {
        if (!config[WmFlags.eventsEnabled]) {
            close()
            return@callbackFlow
        }
        val events = connect()
        val job = lifetime.launch {
            // However the subscription ends, it ends this flow — and with the reason attached.
            // A job that simply finished left the channel open, so a collector saw a compositor
            // that had gone away as a desktop on which nothing was happening. Caught rather than
            // left to the scope for the same reason: an exception delivered to `scope` is one the
            // collector never learns about.
            val failure = try {
                events.subscribe(listOf("window")) { _, payload ->
                    val event = swayJson.decodeFromString<WindowEvent>(payload)
                    val container = event.container ?: return@subscribe
                    val id = SurfaceId(container.id)
                    when (event.change) {
                        "new" -> trySend(
                            SurfaceChange.Appeared(
                                id,
                                Surface(
                                    id,
                                    container.appId,
                                    container.name,
                                    container.pid,
                                    container.focused,
                                ),
                            ),
                        )
                        "close" -> trySend(SurfaceChange.Vanished(id))
                        "focus" -> trySend(SurfaceChange.Focused(id))
                        // The event that keeps a woken tab honest: a page retitles itself and the
                        // container awakener named after it is now naming something that has
                        // moved. sway reports this against the *view*, which is the surface, so
                        // the id here is the one the dock table is keyed on.
                        "title" -> trySend(SurfaceChange.Retitled(id, container.name))
                    }
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
            close(failure)
        }
        awaitClose {
            job.cancel()
            events.close()
        }
    }

    /**
     * The repair collector: the thing that was missing, and the reason [reapOrphans] and the
     * session boundary both read as covered without ever running.
     *
     * `reapOrphans` and the `CompositorSessionEnded` that [changes] now reports were both built
     * against a caller that did not exist — measured on `main`, `reapOrphans` had exactly two
     * mentions in the tree, its own declaration and one test, and `changes` was collected nowhere
     * outside tests. Repair code with no caller is indistinguishable from no repair code, except
     * that it reads as covered, so this is deliberately started by the constructor rather than
     * offered as a method for a caller to remember: forgetting to wire it is the defect, and a
     * `start()` nobody calls would reproduce it exactly.
     *
     * **Why this is not the unattended autonomous action the design forbids.** That agreement is
     * about what an agent does between requests — "they don't poll, don't loop, don't act on a
     * schedule" — and this does none of the three. There is no timer and no interval; the coroutine
     * is parked on a socket read and costs nothing until sway writes to it; and the thing it reacts
     * to is the user closing a window, which is the user acting, one layer down from the hotkey.
     * It also takes no decision of its own: it runs a repair whose policy is entirely in flags a
     * caller set. `attach`'s own late-dock claim was settled the same way and for the same reason.
     * What it is *not* allowed to become is a periodic sweep, which would be that rule broken.
     *
     * It runs on the scope the caller handed this manager, which is that caller's grant of a
     * lifetime: cancelling it ends the collection and closes the subscription's connection.
     *
     * **Nothing but a cancellation leaves this function under the default flags,** and that is
     * load-bearing rather than tidiness. A constructor started this job, so there is no call for a
     * caller to wrap in a `try` and no result for it to await; a failure that escaped would land in
     * the caller's scope and — an ordinary `Job()` scope being no supervisor — cancel every
     * unrelated coroutine on it, while [repairs] stayed empty. Measured with a `connect()` that
     * raises: the scope and a sibling coroutine both went down and the status reported nothing.
     * [WmFlags.collectorFailure] is what decides that, and [CollectorFailure.PROPAGATE] is the
     * caller that would rather have it loud.
     */
    private suspend fun collectRepairs() {
        try {
            changes.collect { change ->
                // Only a close can orphan a dock, and it is the exact moment one becomes an
                // orphan. Read per event rather than captured once, because a collector outlives
                // any snapshot it took at startup — and `config` here is a StateFlow read, so it
                // picks up whatever replaced that snapshot, from a test's `put` today and from
                // `FileConfigStore.watch` once something calls it (nothing does: #43).
                if (change is SurfaceChange.Vanished && config[WmFlags.sweepOnClose]) sweep()
                if (change is SurfaceChange.Retitled) refreshTabTitle(change.id)
            }
        } catch (ended: CompositorSessionEnded) {
            // The session boundary, mechanically: connection loss *is* the boundary, and #20 is
            // what makes it distinguishable from an idle desktop. Drop the dead session and
            // discard before reporting, so that a reader who sees `sessionEnded` set can rely on
            // both — the table is empty, and there is no live connection behind it.
            //
            // The collection ends here whatever `wm.session.reconnect` says. A collector that
            // reconnected itself would be a loop with a wait in it — there is nothing to connect
            // to at the instant a compositor dies — and the design forbids exactly that. The
            // successor is acquired by the next caller, which restarts this (see `acquire`).
            invalidate(ended)
            docks.discard()
            repairState.update { it.copy(sessionEnded = ended) }
        } catch (cancelled: CancellationException) {
            // The caller withdrawing the lifetime it granted, which is not a failure and must not
            // be recorded as one — nor swallowed, or this job would complete rather than cancel.
            throw cancelled
        } catch (failure: Exception) {
            // A close that took the connection away underneath its own collector is not a
            // collector failure and must not be recorded as one: `close` cancels and then closes,
            // so the read this collector was parked on fails on the way out. What ended the
            // collection there is the retirement, which the caller already knows about.
            if (retired) return
            // Everything else: a connect that raised, a subscription sway refused, a payload that
            // would not parse. The table is deliberately *not* discarded — none of these says the
            // session ended, and emptying a table that still describes a live session would make
            // every standing dock read as a bindable window. Recorded before the collection ends,
            // so `repairing` completing and `collectorFailure` being set are never observable in
            // the other order.
            repairState.update { it.copy(collectorFailure = failure) }
            if (config[WmFlags.collectorFailure] == CollectorFailure.PROPAGATE) throw failure
        }
    }

    /**
     * One sweep, with its outcome recorded either way.
     *
     * A sweep that raises has already tried every orphan — that isolation is `reapOrphans`'s — so
     * the only question left is whether the *collector* survives it, and that is
     * [WmFlags.sweepFailure]'s. Under `STOP` the failure leaves this function, which ends the
     * collection and hence the subscription; it is recorded here first so that stopping does not
     * also cost the diagnosis. Where it goes after that is [collectRepairs]'s and
     * [WmFlags.collectorFailure]'s — under the default it stops there, recorded a second time as
     * what ended the collection.
     */
    private suspend fun sweep() {
        try {
            reapOrphans()
            repairState.update { it.copy(sweeps = it.sweeps + 1) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Same rule as `collectRepairs`: a sweep that failed because `close` closed the
            // connection under it is the retirement, not a repair that went wrong.
            if (retired) return
            repairState.update {
                it.copy(
                    sweeps = it.sweeps + 1,
                    failures = it.failures + 1,
                    lastFailure = failure,
                )
            }
            if (config[WmFlags.sweepFailure] == SweepFailure.STOP) throw failure
        }
    }

    /**
     * [collectRepairs] running on [scope], started as this manager is built.
     *
     * Declared last on purpose: property initialisers run in declaration order, so by the time
     * this one launches, every field the collector touches is set — including [changes], which is
     * a `val` further up. `internal` so that a test can see whether it is still running, which is
     * the whole of what [WmFlags.sweepFailure]`=STOP` changes.
     *
     * With `wm.events.enabled` off, [changes] closes immediately and this returns without opening
     * anything. Otherwise it holds one IPC connection — the subscription's — for the life of
     * [scope], which is the cost of every manager now having a collector rather than only the one
     * a daemon would have wired.
     *
     * **This job completing is normal.** It ends when the session ends, when a sweep raises under
     * [SweepFailure.STOP], or on any other failure under [CollectorFailure.REPORT] — and in every
     * one of those cases [repairs] says which. It is a `var` because one of those endings is now
     * reversible: [acquire] starts a fresh collector when a caller reconnects past a session
     * boundary, so a manager that outlives a compositor restart has a collector again, on the
     * successor connection. Nothing else restarts it, and nothing restarts it on a schedule.
     *
     * **Retiring one before then is [close]'s**, which is what #85 settled. A caller replacing a
     * manager closes the outgoing one and gets a promise that its collector has *stopped* — not
     * that it has been asked to — because the cancellation a caller can issue for itself only asks,
     * and a predecessor still inside a sweep is the whole hazard. Giving each manager a scope of
     * its own still works and is still what the tests do; the difference is that `close` also
     * abandons the table and gives up the connection, and that cancelling a shared scope no longer
     * has to be the lever.
     *
     * **Two managers over one tree are not harmless, and their sweeps are not idempotent (#72).**
     * This said the opposite — that both sweeps were idempotent against the same tree, so a
     * per-manager shutdown bought nothing — and that was measurably false. A sweep asks
     * [currentlyADock], which answers partly from the *asking* manager's [DockTable]: a dock is
     * [DockOrigin.STOOD_UP] to the manager that stood it up and [DockOrigin.ADOPTED], or nothing at
     * all, to every other manager, which has only the tree to read. One close event therefore gets
     * two different answers — one manager reaps the dock, the other refuses — and whichever sweep
     * lands first decides, with nothing recording that the other disagreed. That is not
     * idempotence; it is a race whose result is manager-relative.
     *
     * **It is reachable at stock flags, not only under [ReapEvidence.STOOD_UP].** Stated here
     * because the first correction of this KDoc got it wrong in the same direction the original
     * was wrong — it claimed `STOOD_UP` was the only [WmFlags.reapEvidence] value with the
     * property, which reads as "you have to opt in to be exposed". You do not. Under the default
     * [ReapEvidence.CURRENT] the test is `stoodUp || <a dock mark readable now>`, and the first
     * disjunct is memory. Any node a manager stood up is reapable *by that manager* however
     * unreadable the tree has become, so a successor that cannot recognise the node at all still
     * has a predecessor that will kill it. Measured, at stock flags, with two managers left
     * overlapping and the race forced: a dock marked under a scheme this build no longer reads is
     * left standing by the manager that adopted nothing and reaped by the manager that stood it
     * up — 3 runs out of 3, in `a dock marked under the other scheme is reported and left
     * standing`. What `STOOD_UP` changes is the *size* of the divergence, not its existence: there
     * every adopted dock diverges rather than only the ones whose mark has gone or changed shape,
     * which is why #56 surfaced there first and nowhere else.
     *
     * That was the mechanism behind #56, the repo's one known flaky test: it simulated an awakener
     * restart by building a second manager and leaving the first collecting, and the leaked
     * collector killed the dock the manager under test had correctly left standing. `a retired
     * manager sweeps nothing, so a restart cannot race it` holds the retirement that fixes it.
     *
     * **What the product does about that is now decided (#85), and the decision is the first of the
     * three candidates**: a [close] that stops the collector, gives up the connection and abandons
     * the table. Two things make it the answer rather than the sweep-reads-durable-state one. The
     * table is consulted *twice* on the sweep path — [dockedTo] before [currentlyADock] — so a fix
     * aimed at the second leaves [ReapEvidence.RECOGNITION] fully exposed, where the whole
     * divergence comes from the first; and a sweep that would not consult the table at all is a
     * different sweep, one that stops reaping any dock whose mark it cannot read, which is a cost
     * paid by every single-manager desktop to fix something only a second manager can cause.
     *
     * **The other half of the decision is that a compositor restart no longer produces a second
     * manager at all.** That was the concrete case where a predecessor could still be subscribed:
     * reconnection had no owner (#33), so the only way past a session boundary was to build a
     * successor manager and hope the caller retired the old one. Under
     * [WmFlags.sessionReconnect]`=ON_DEMAND` the manager acquires the successor connection itself
     * and restarts this job on it, so there is one manager across the boundary and nothing to
     * overlap. Under `NEVER` the caller builds the successor, and `close` is the lever that makes
     * doing so safe.
     */
    internal var repairing: Job = lifetime.launch { collectRepairs() }
        private set

    /**
     * Applies [OrphanPolicy] to any dock whose surface is gone.
     *
     * Driven off [changes] by [collectRepairs] rather than run on a timer, since sway emits
     * `close` for the surface and that is the exact moment the dock becomes an orphan. Callable
     * directly as well, which is what `wm.repair.sweep_on_close=false` leaves.
     *
     * One dock that will not come down does not cost the rest of the sweep. This is the whole
     * mechanism for the probe's Hazard 2, so a teardown that throws partway through used to leave
     * every orphan after it standing — turning one transient failure into tree damage that no
     * later event repairs, because the `close` that would have triggered the next sweep has
     * already been and gone.
     *
     * What it guarantees: every orphan is swept whatever the ones before it did, and the failures
     * collected along the way are raised only once the sweep is complete — the first thrown, the
     * rest attached to it as suppressed. Each names the dock it came from, since nothing under it
     * does: a normalisation refusal reads `sway rejected 'split none': ...` and identifies no
     * window at all, so an aggregate over N docks would otherwise say three teardowns failed
     * without saying which three.
     *
     * A dock that outlives its own kill is one of those failures **while
     * [WmFlags.wedgedDockFailsDetach] is on**, which is what it defaults to. Turn that flag off
     * and such a dock is left standing while this sweep says nothing about it — the deliberate
     * choice for a panel program that is merely slow to exit, and the only case in which this
     * returns having repaired less than it says.
     *
     * Which nodes are docks is [dockedTo]'s union, the same one enumeration answers from — but a
     * sweep kills, so under the default [WmFlags.reapEvidence] it will not act on a recognition
     * with nothing behind it any more (see [currentlyADock]). A dock this process stood up is
     * reaped whatever became of its mark; a dock adopted after a restart is reaped while it still
     * carries the mark that identified it, and left standing if that mark has since gone. Under
     * the default [WmFlags.dockMarkScheme] the only thing that takes a dock's mark away is a hand
     * doing it, since the mark names the dock rather than the surface — under
     * [DockMarkScheme.SURFACE] a second attach on the same surface does it (#14). Under
     * [ReapEvidence.STOOD_UP] no mark is evidence for a kill at all and an adopted dock is never
     * reaped. A dock an attach has reserved but not yet identified is not swept either: nothing
     * knows yet which surface it belongs to, so nothing can know it is an orphan.
     */
    /**
     * Re-issues the tab title for [surface], if a dock of this manager's is standing beside it.
     *
     * The other half of `wm.dock.tab_title=SURFACE`: a page title moves under the tab that was
     * named after it, so a title set once at attach goes stale the first time the user follows a
     * link. This is what #177 means by "re-issued on `window::title`".
     *
     * **A read of the dock table, so it answers only for surfaces this manager docked.** A title
     * event arrives for every window on the desktop and the overwhelming majority are nobody's
     * business here; the lookup is what makes this cost a map read rather than a compositor round
     * trip for each one. It also means it does nothing for a dock this process did not stand up —
     * an adopted one from a previous manager is in the table, a dock nothing recorded is not.
     *
     * Not a poll and not a schedule: it runs when the user's window says it changed, which is the
     * same grant [collectRepairs] documents.
     */
    private suspend fun refreshTabTitle(surface: SurfaceId) {
        if (config[WmFlags.dockTabTitle] != TabTitleSource.SURFACE) return
        if (docks.snapshot().entries.none { (_, entry) -> entry.surface == surface }) return
        treeEdit { applyTabTitle(surface) }
    }

    suspend fun reapOrphans() {
        val cfg = config
        if (cfg[WmFlags.orphanPolicy] != OrphanPolicy.CLOSE) return
        // One session for the whole sweep, and it is the session the handles below are stamped
        // with: the `con_id`s this sweep is about to act on came out of the tree it read here.
        val session = session()
        val root = tree(session)
        val table = docks.snapshot()
        val live = root.windows.map { it.id }.toSet()
        val failures = mutableListOf<Throwable>()
        root.windows.forEach { node ->
            val boundTo = dockedTo(node, table, cfg)?.raw ?: return@forEach
            if (boundTo in live) return@forEach
            // Last, and deliberately not folded into the line above: enumeration and the sweep
            // must keep answering from the same predicate — that they disagreed is #15 — so this
            // narrows what is *killed*, not what is a dock.
            if (!currentlyADock(node, table, cfg)) return@forEach
            try {
                // No key: the surface is already gone, so there is nothing left to derive one
                // from. Reaping a dock is a window-tree repair and never touches the registry.
                SwayDockHandle(
                    SurfaceId(boundTo),
                    AgentId(""),
                    SurfaceId(node.id),
                    key = null,
                    session = session,
                ).detach()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Tagged here because this is the only place that still knows which dock the
                // teardown was for: `run` reports the command it sent and nothing else, so a
                // `split none` refusal arrives anonymous. The sweep's usual caller is the repair
                // collector, which nobody is watching, so this message is the whole of what
                // reaches `repairs` for a human to work from.
                //
                // And it says whether the dock is still there (#224). A detach kills first and
                // tidies after, so a refusal from the tidying arrives with the dock already gone,
                // and "reaping failed" would then send the reader after a panel that is not on
                // screen. Asked of the tree rather than of where the throw came from, because the
                // tree is what the user sees; a read that itself fails says nothing either way,
                // and the message falls back to the reap failing.
                val reaped = try {
                    tree(session).find(node.id) == null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                failures += IllegalStateException(
                    if (reaped) {
                        "dock ${node.id}, bound to the gone surface $boundTo, was reaped, but " +
                            "tidying the tree after it failed"
                    } else {
                        "reaping dock ${node.id}, bound to the gone surface $boundTo, failed"
                    },
                    failure,
                )
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    /**
     * A handle on the dock currently standing for [surface], or null if there is none.
     *
     * **The call a one-shot hotkey needs and could not previously have.** `attach` is the only
     * thing that mints a [DockHandle], and `awakener-invoke` exits between presses, so the second
     * press held nothing that knew which node the dock was — which is what `WhenAnimated.REUSE`'s
     * doc means when it says raising is "what a daemon adds". Recovering the handle from the tree
     * is what makes hide-and-restore reachable from a key press instead.
     *
     * **Deliberately not a fourth call on [WindowManager].** The working agreement holds that
     * interface to three, and this is the same shape as [reapOrphans]: a concrete method that the
     * composition root — the one place that already knows it has a sway — passes down as a lambda.
     * `Awakening` therefore still holds a `WindowManager` and still learns nothing about
     * compositors. See #94.
     *
     * Recognition is [dockedTo]'s, which is the same union enumeration answers from: this session's
     * dock table first, and the mark otherwise. The mark is what makes this work **across an
     * awakener restart** — a fresh process has an empty table, reads the mark off the node, and
     * adopts it. Marks survive a scratchpad move, so a dock hidden by one awakener is re-adoptable
     * by its successor; that is measured in `SwayHideTest`, not assumed.
     *
     * Hidden docks are found for the same reason enumeration excludes them: sway's scratchpad is
     * an ordinary workspace on the `__i3` output, so a hidden dock is still in the tree this reads.
     *
     * Where two docks stand for one surface — [WhenAnimated.SECOND_DOCK] — this answers with the
     * first the tree lists, and the caller gets one of them rather than a refusal. That is the
     * honest shape for a hotkey: the alternative is a key press that does nothing and says why.
     */
    suspend fun dockFor(surface: SurfaceId): DockHandle? {
        val cfg = config
        val session = session()
        val root = tree(session)
        val table = docks.snapshot()
        val node = root.windows.firstOrNull { dockedTo(it, table, cfg) == surface } ?: return null
        return adoptedHandle(surface, node, session)
    }

    /**
     * A handle on a dock this process did not attach, recovered from the tree.
     *
     * The key and agent are derived here rather than carried, because no attach made this
     * handle: the key is what `detach` needs to forget the binding, and the agent is what a caller
     * prints.
     */
    private suspend fun adoptedHandle(surface: SurfaceId, node: Node, session: Session): DockHandle {
        val key = keyFor(surface)
        val agent = key?.let { registry.resolve(it)?.agent } ?: AgentId("")
        return SwayDockHandle(surface, agent, SurfaceId(node.id), key, session)
    }

    /**
     * A handle on the focused window if it is a dock, or null if it is no dock this can name a
     * surface for. The handle's [DockHandle.surface] is the surface it is docked beside.
     *
     * [dockFor]'s question asked the other way round, so a press made from inside a panel can
     * act on the panel's own surface (#196) — and on **that** panel, not on whichever of the
     * surface's panels [dockFor] would list first (#220). Recognition is the same [dockedTo]
     * union, which is what makes it work from a one-shot process: the table is empty there, and
     * the mark names the surface. A window that only *looks* like a dock — the configured
     * `app_id` with no mark, which is a dock still being stood up or one #199 left unclaimed —
     * names nothing, and gets null rather than a guess from its `app_id`.
     *
     * A concrete method passed down as a lambda, for [dockFor]'s reasons; see #94.
     */
    suspend fun focusedDock(): DockHandle? {
        val cfg = config
        val session = session()
        val focused = tree(session).windows.firstOrNull { it.focused } ?: return null
        val surface = dockedTo(focused, docks.snapshot(), cfg) ?: return null
        return adoptedHandle(surface, focused, session)
    }

    private inner class SwayDockHandle(
        override val surface: SurfaceId,
        override val agent: AgentId,
        override val dockId: SurfaceId,
        /** Captured at attach time — by detach the window may be gone and underivable. */
        private val key: SurfaceKey?,
        /**
         * The compositor session [dockId] and [surface] are `con_id`s of.
         *
         * Held so this handle can tell whether it still describes anything. A `con_id` is minted
         * from a counter that restarts with the compositor, so across a session boundary it does
         * not become meaningless — it becomes somebody else's window. See [WmFlags.staleHandles].
         */
        private val session: Session,
    ) : DockHandle {
        /**
         * Refuses this handle if the session its ids came from has ended.
         *
         * Compares [liveSession] rather than asking for one, and that is the point: a stale handle
         * must not be the thing that acquires a successor connection, since the only work it could
         * do on one is issue a dead session's ids at a live compositor. That field is null between
         * a boundary and the reconnect past it, which is stale as well.
         */
        private fun checkSession(action: String) {
            if (config[WmFlags.staleHandles] == StaleHandle.ACT) return
            val current = liveSession
            if (current === session) return
            val now = current?.let { "on session ${it.generation}" }
                ?: "on no session at all, the compositor having gone away"
            throw CompositorSessionEnded(
                "refusing to $action dock ${dockId.raw}: this handle is from session " +
                    "${session.generation} and the manager is $now. A compositor allocates " +
                    "con_ids from a counter that restarts with it, so in a later session this id " +
                    "names a different window rather than no window. Obtain a new handle, or set " +
                    "wm.session.stale_handles=ACT to issue it anyway.",
            )
        }

        override suspend fun focus() {
            checkSession("focus")
            treeEdit { focus(dockId) }
        }

        override suspend fun settleFocus() {
            checkSession("settle focus on")
            treeEdit { settleFocus(surface, dockId) }
        }

        override suspend fun hidden(): Boolean {
            checkSession("ask about")
            // A read, so it takes the tree lock only for consistency with the two edits it is
            // the query half of: `hidden()` immediately followed by `hide()` from one caller
            // must not be able to interleave with another caller's `show()` between them any
            // more than any other pair of tree operations can.
            return treeEdit { inScratchpad(dockId) }
        }

        override suspend fun hide(): Boolean {
            checkSession("hide")
            return treeEdit { hideDock(surface, dockId) }
        }

        override suspend fun show(): Boolean {
            checkSession("show")
            return treeEdit { showDock(surface, dockId) }
        }

        override suspend fun detach() {
            checkSession("detach")
            val cfg = config
            val settle = treeEdit {
                // Before anything is read, so the read below finds the split down to the surface.
                if (cfg[WmFlags.evictOnDetach] && !inScratchpad(dockId)) {
                    val evict = evictionCommands(tree(), surface, dockId, cfg) { isDockOf(it, surface, cfg) }
                    if (evict.isNotEmpty()) run(evict.joinToString("; "))
                }
                // Which container has to be flattened afterwards depends on where the dock *is*.
                // Beside its surface, the dock's own parent is the split container `attach` made
                // and the surface is the survivor in it. In the scratchpad, the dock's parent is
                // the `__i3_scratch` workspace, whose other children are unrelated windows — so
                // asking `parentOf(dockId)` there answers about somebody else's tree, and the
                // split container the dock came out of is left standing to adopt the next window
                // opened in that tab, which is exactly what wm.dock.normalize_container_on_detach
                // exists to prevent. Asking from the surface's side answers both cases: for an
                // orphan whose surface is already gone it is null, and sway has by then collapsed
                // the empty container itself.
                //
                // From the surface's side, the parent is only the dock's container if it is still
                // a split: under wm.dock.flatten_on_hide the hide already collapsed it, and the
                // surface's parent is then the tab strip, whose title is not awakener's to reset
                // and whose "survivor" is some other tab that `split none` would be refused on.
                //
                // The dock's own side is held to the same test (#224). A dock standing directly on
                // the workspace — a tab the user flattened by hand — or in a tab strip it was
                // dragged into has no split of awakener's above it, and the window that happens to
                // be beside it is no survivor of one: flattening it is refused, after the kill.
                val parent = if (inScratchpad(dockId)) {
                    dockSplitOf(tree(), surface)
                } else {
                    tree().parentOf(dockId.raw)?.takeIf { it.isSplit() }
                }
                // The surface's size beside its standing dock, for the settle below (#253).
                val docked = parent?.takeIf { it.type == FLOATING_CON && it.find(dockId.raw) != null }
                    ?.let { split -> split.find(surface.raw)?.rect }
                // Ungated, and **before** the kill, which is where it has to be rather than
                // where it reads most naturally. A dock whose surface has already gone is the
                // ordinary reap case, and there the container's only remaining child is the dock
                // itself — so the kill takes the container with it and a reset issued afterwards
                // names a node that has stopped existing, which sway refuses and `run` raises. The
                // whole orphan sweep went red on exactly that.
                //
                // What it is for is the container that *survives*: under
                // `wm.dock.normalize_container_on_detach=false` it stands there holding only the
                // surface, and a title left on it is frozen at whatever the page said when the
                // panel closed — a tab that looks right and is lying, which is worse than the
                // synthetic name it replaced. Where the container does go, this is one round trip
                // spent on a node about to be collapsed.
                //
                // The cost of the early placement is narrow and named: a dock that then refuses to
                // die leaves a still-standing panel in a container sway has gone back to naming
                // itself. That is a wedged dock either way, and `wm.dock.wedged_dock_fails_detach`
                // is what reports it.
                parent?.let { resetTabTitle(SurfaceId(it.id)) }

                // `kill` waits, and holding the tree across that wait is the point: the critical
                // section must not end over a dock that is still standing, or the next teardown
                // of it — a second detach, or the next orphan sweep — finds it in the tree and
                // kills it again. Previously the no-survivor case (which is every orphan, since
                // the surface is what died) left on the acknowledgement by exactly that route.
                if (!kill(dockId)) {
                    // And a dock that outlives the wait is a *failed* teardown, not a quiet one.
                    // Returning normally here was the same defect one door along: reapOrphans
                    // collected nothing, the sweep reported a repair it had not made, and the one
                    // dock that genuinely refuses to die was the single failure its aggregate
                    // could not see — with no later close event to bring a sweep back for it.
                    // Raising also leaves the durable binding alone, since the unbind below is
                    // never reached: forgetting a binding whose panel is still on screen strands
                    // the panel.
                    if (cfg[WmFlags.wedgedDockFailsDetach]) {
                        error(
                            "dock ${dockId.raw} was still in the tree " +
                                "${cfg[WmFlags.unmapWaitMs]}ms after it was killed; its client " +
                                "is not servicing the close request",
                        )
                    }
                    return@treeEdit null
                }
                // Only once the node has actually left the tree: a dock that outlived its kill is
                // still a dock, and forgetting it here would hand the wedged panel back to
                // enumeration as a bindable surface.
                docks.forget(dockId)

                parent ?: return@treeEdit null
                // A split a window of the user's got into is theirs now, flag or no flag: the dock
                // is out of it, and the split stays (#256). `split none` would be refused for the
                // user's window, after the kill.
                if (disownIfShared(parent, surface, dockId)) return@treeEdit null
                if (!cfg[WmFlags.normalizeContainerOnDetach]) return@treeEdit null
                // A split still holding another of this surface's docks is not done with: the
                // last dock out is the one that flattens it. Asking `split none` now would be
                // refused for the sibling, after the kill (#171) — the shape wm.dock.share_split
                // makes, and one a restore beside a second dock already made before it.
                if (parent.nodes.count { it.id != dockId.raw } > 1) return@treeEdit null
                val survivor = parent.children.firstOrNull { it.id != dockId.raw } ?: return@treeEdit null
                // Raised rather than caught: the orphan sweep's aggregate is what a normalisation
                // sway refuses is reported through, and it tags the dock the failure came from.
                normalizeContainer(SurfaceId(survivor.id), docked)
            }
            // Outside the lock: it is a watch over a window the detach is already done with, and
            // the next press should not queue behind it. Each correction takes the lock itself.
            settle?.let { holdFloatingSize(it) }
            // Outside the section for the same reason as attach's bind: the registry is not the
            // tree, and the dock is already down by here.
            if (cfg[WmFlags.forgetBindingOnDetach] && key != null) {
                // The `Forget` is read rather than discarded, both halves. Its residue half exists
                // so that a disposal which failed with the binding already durably gone can be told
                // from one that worked; dropping it made that failure loud through
                // `awakener-registry forget`, which exits 3 for it, and silent here — on the path
                // nobody invokes deliberately, since a dock closing takes it automatically (#115).
                // Its bus half is the same shape: an entry left on the roster here has no binding
                // left to be forgotten by (#188). `sessionEnding`, because the kill above ended the
                // panel's session and its process outlives its window by a few hundred ms.
                val forget = registry.unbind(key, sessionEnding = true)
                val bus = forget.bus
                if (bus is BusOutcome.StillLive || bus is BusOutcome.Failed) {
                    busLeft.update { it + BusEntryLeft(surface, dockId, key.canonical, bus) }
                }
                (forget.residue as? ResidueOutcome.Failed)?.let { failed ->
                    reportResidueFailure(key, failed, cfg)
                }
            }
        }

        /**
         * Records [failed] and, under [DetachResidueFailure.RAISE], fails the detach with it.
         *
         * The record goes in first. Under `RAISE` the throw is the caller's signal and the list is
         * the reader's, and a reader who only ever sees the list must not be able to tell which
         * flag was set by whether an entry is there.
         */
        private fun reportResidueFailure(
            key: SurfaceKey,
            failed: ResidueOutcome.Failed,
            cfg: Config,
        ) {
            val failure = ResidueDisposalFailure(
                surface = surface,
                dock = dockId,
                key = key.canonical,
                path = failed.path,
                reason = failed.reason,
            )
            residueFailures.update { it + failure }
            if (cfg[WmFlags.detachResidueFailure] == DetachResidueFailure.RAISE) {
                error(
                    "$failure; set ${WmFlags.detachResidueFailure.key}=REPORT to let a detach " +
                        "carry on past a disposal it could not make",
                )
            }
        }

        override fun close() {
            lifetime.launch { detach() }
        }
    }

    private companion object {
        /**
         * sway's `type` for a container, as opposed to a workspace, an output or the root.
         *
         * The one question `applyTabTitle` asks before naming a node: `parentOf` a surface with no
         * dock is its *workspace*, and titling that would put a page title where the workspace
         * name goes.
         */
        private const val CON = "con"

        /**
         * The split container [surface] sits in, or null when its parent is anything else.
         *
         * A split is what `attach` makes — `split horizontal`, or `splitv` once a user has toggled
         * it — so this is the shape of the container a dock lives in. A `tabbed` or `stacked`
         * parent is a tab strip, and a workspace parent is no container at all; under
         * [WmFlags.flattenOnHide] both are where a hidden dock's surface ordinarily sits, and
         * neither is awakener's to name, flatten, or treat as holding a survivor.
         */
        private fun dockSplitOf(tree: Node, surface: SurfaceId): Node? =
            tree.parentOf(surface.raw)?.takeIf { it.isSplit() }

        /** [dockSplitOf], when that split is awakener's under [WmFlags.splitOwnership]. */
        private fun ownSplitOf(tree: Node, surface: SurfaceId, cfg: Config): Node? =
            dockSplitOf(tree, surface)?.takeIf { ownsSplit(it, cfg) }

        /**
         * A split container — the shape `attach` makes — as opposed to a tab strip or workspace.
         * A floating one counts (#253): `split` on a floating window makes a floating split on its
         * rectangle, and that is the split attach made around a floating surface, which a detach
         * has to collapse and a second dock has to share like any other.
         */
        private fun Node.isSplit(): Boolean =
            (type == CON || type == FLOATING_CON) && (layout == "splith" || layout == "splitv")

        /**
         * Fails an attach that [WmFlags.floatingSurface] or [WmFlags.fullscreenSurface] refuses,
         * from the read [tree], before it has changed anything.
         */
        private fun refuseByFlag(tree: Node, surface: SurfaceId, cfg: Config) {
            val floating = floatingAncestorOf(tree, surface) != null
            check(!floating || cfg[WmFlags.floatingSurface] != FloatingSurface.REFUSE) {
                "surface ${surface.raw} is floating, which wm.dock.floating_surface=REFUSE declines"
            }
            val fullscreen = generateSequence(tree.find(surface.raw)) { tree.parentOf(it.id) }
                .takeWhile { it.type == CON || it.type == FLOATING_CON }
                .any { it.fullscreenMode != 0 }
            val mode = cfg[WmFlags.fullscreenSurface]
            val refused = fullscreen && when (mode) {
                FullscreenSurface.DOCK -> false
                FullscreenSurface.DOCK_TILED -> floating
                FullscreenSurface.REFUSE -> true
            }
            check(!refused) {
                "surface ${surface.raw} is fullscreen${if (floating) " and floating" else ""}, " +
                    "which wm.dock.fullscreen_surface=$mode declines"
            }
        }

        /**
         * The provenance mark an attach or a show puts on a split it made (#248): the record that
         * the split is awakener's, since one a user made can be identical in shape.
         *
         * Any mark under [SPLIT_MARK_PREFIX] counts; each split's is its own nonce, because a sway
         * mark is one global set and a name held by two containers is taken off the first by the
         * second — a per-surface name would move off an outer split of awakener's the moment it
         * wrapped the surface again inside it. See [splitAndClaim]. The leading underscore is
         * sway's rule for a mark never drawn in a title bar, and `_awakener_` is the namespace
         * [moveToMarkOn] already uses, which no dock mark falls under.
         */
        private const val SPLIT_MARK_PREFIX = "_awakener_split_"

        /**
         * The mark a hide leaves on a dock to say how wide it was (#264): this prefix, the dock's
         * con_id — a mark name is one global set, so it has to be the dock's own — which split
         * that was a share of, and the share in ppt. A mark because the process that shows a dock
         * is routinely not the one that hid it, and the tree is the one record both can read;
         * and gone again once the show has read it, so a dock put in the scratchpad by anything
         * else comes back at wm.dock.size_ppt rather than at a width from some older hide.
         */
        private const val WIDTH_MARK_PREFIX = "_awakener_width_"

        /**
         * What a width mark names for a split that held the surface and the dock alone. The hide
         * collapses that one under wm.dock.flatten_on_hide, so there is no con_id to come back to,
         * but any split holding the two of them alone is the same share of the same width.
         */
        private const val ALONE = "alone"

        /**
         * The mark recording [dock]'s share of the split it stands in beside [surface], or null
         * when it is not in a horizontal split beside anything, where a share means nothing to
         * come back to. Never a share that would put it under sway's floor in that split, which
         * rounding to a whole ppt can, and which sway would then refuse it.
         */
        private fun widthMarkFor(tree: Node, surface: SurfaceId, dock: SurfaceId): String? {
            val split = tree.parentOf(dock.raw)?.takeIf { it.layout == "splith" && it.nodes.size > 1 } ?: return null
            val percent = split.nodes.firstOrNull { it.id == dock.raw }?.percent ?: return null
            val floor = split.rect?.width?.takeIf { it > 0 }?.let { ceil(MIN_SANE_WIDTH * 100.0 / it).toInt() } ?: 1
            val ppt = (percent * 100).roundToInt().coerceAtLeast(floor).coerceIn(1, 99)
            val alone = split.nodes.size == 2 && split.nodes.any { it.id == surface.raw }
            val from = "$WIDTH_MARK_PREFIX${dock.raw}_${if (alone) ALONE else split.id}_$ppt"
            // #270's reviews: every window's width in the split as it would stand with every
            // dock hidden out of it back — its home width — so a show coming back across splits
            // can tell what awakener's own hides and shows did to a window from a width the user
            // set since, and give back exactly what they handed out. sway renormalises fractions
            // on a hide, so a window's width is its home width scaled by T / (T − hidden).
            //
            // #272: the docks already hidden out of the split too, at the home width their own
            // marks hold, and alone as well. A show back into the surface's own split then has
            // the home width of every window it may meet there, whichever of the others are back
            // by then, and after the collapse below has taken the split's con_id with it.
            val widths = split.nodes.map { it.id to (it.rect?.width ?: return from) }
            val total = widths.sumOf { it.second }
            val hidden = hiddenFrom(tree, split.id)
            val home = total - hidden.values.sum()
            if (total <= 0 || home <= 0) return from
            val sizes = widths.map { (id, width) -> id to (width.toDouble() * home / total).roundToInt() } + hidden.toList()
            return "${from}_" + sizes.joinToString(".") { (id, width) -> "${id}x$width" }
        }

        /** The width marks on [node], from [widthMarkFor]. */
        private fun widthMarksOn(node: Node?): List<String> =
            node?.marks.orEmpty().filter { it.startsWith("$WIDTH_MARK_PREFIX${node?.id}_") }

        /**
         * The share in ppt the hide recorded on [node], where the show is bringing it back into
         * the same split: [split], the surface's own one it is coming back into, or null where it
         * gets a new one beside the surface alone. A share of some other split is no width to
         * come back to, which is where a show lands after a window of the user's took the old one
         * over (#256); [acrossSplits] is how that show gets the dock's width back.
         */
        private fun hiddenWidthOf(node: Node?, split: Node?): Int? = recordedWidthOf(node)?.let { recorded ->
            val from = recorded.from
            val same = if (from == ALONE) split == null || split.nodes.size == 1 else from.toLongOrNull() == split?.id
            recorded.ppt.takeIf { same }
        }

        /**
         * The width [hidden] comes back at in the surface's own split (#272): its home width's
         * share of what the windows it will stand beside there — [usable]'s, or the surface alone
         * where the batch makes a split around it — are at home, taken of that split's width now.
         * Null where the hide did not record the home width of every one of them.
         *
         * A share of the split the dock was hidden from is not that once several docks are hidden
         * together: it is of a split the ones hidden first had already left, or of the surface
         * and this dock alone, which wm.dock.flatten_on_hide then collapses. Shown back in any
         * order, each dock came in at a share of a different set of windows from the one it
         * met, and the surface paid: 619 → 556 px with two docks, 322 → 202 with four, on 1.12.
         * Home widths are of one layout whichever docks are back, so the share is of the windows
         * actually there. A dock attach squeezed under sway's floor comes back at it, as
         * [restoredAround]'s does.
         *
         * Not where the split it was hidden from is another one still standing: that is a split
         * the user's window took over (#256), and [acrossSplits]'s to plan or decline.
         */
        private fun homeWidthOf(tree: Node, hidden: Node, surface: SurfaceId, usable: Node?): HomeWidth? {
            val recorded = recordedWidthOf(hidden) ?: return null
            val home = recorded.home ?: return null
            if (recorded.from.toLongOrNull()?.takeIf { it != usable?.id }?.let { tree.find(it) } != null) return null
            val beside = usable ?: tree.find(surface.raw) ?: return null
            val width = beside.rect?.width?.takeIf { it > 0 } ?: return null
            val members = (usable?.nodes?.map { it.id } ?: listOf(surface.raw)) + hidden.id
            val shares = members.map { home[it] ?: return null }
            val total = shares.sum().takeIf { it > 0 } ?: return null
            val px = (width.toDouble() * shares.last() / total).roundToInt().coerceAtLeast(MIN_SANE_WIDTH + PREDICTION_SLACK)
            return HomeWidth(px, (px * 100.0 / width).roundToInt())
        }

        /** What [homeWidthOf] gives the dock: [dockWidth] px, which is [ppt] of the split it comes back into. */
        private class HomeWidth(val dockWidth: Int, val ppt: Int)

        /**
         * What a width mark says: the split the dock was a share of, or [ALONE]; the share; and
         * the home width of every window in that split, the dock's own included, where the mark
         * recorded them ([widthMarkFor]).
         */
        private class RecordedWidth(val from: String, val ppt: Int, val home: Map<Long, Int>?)

        /**
         * The docks in the scratchpad that were hidden out of [split], each with its home width
         * there, from its own width mark. Any surface's: what decides how sway shared out the
         * split's width is which windows left it, not whose they were.
         */
        private fun hiddenFrom(tree: Node, split: Long): Map<Long, Int> {
            fun all(node: Node): Sequence<Node> = sequenceOf(node) + node.children.asSequence().flatMap { all(it) }
            val scratch = tree.workspace(SCRATCH_WORKSPACE) ?: return emptyMap()
            return all(scratch).mapNotNull { node ->
                recordedWidthOf(node)?.takeIf { it.from == split.toString() }?.home?.get(node.id)?.let { node.id to it }
            }.toMap()
        }

        /** The width mark on [node], read. */
        private fun recordedWidthOf(node: Node?): RecordedWidth? = widthMarksOn(node).firstNotNullOfOrNull { mark ->
            val parts = mark.substringAfter("$WIDTH_MARK_PREFIX${node?.id}_").split('_')
            if (parts.size !in 2..3) return@firstNotNullOfOrNull null
            val others = parts.getOrNull(2)?.split('.')?.associate { entry ->
                val (id, px) = entry.split('x').takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
                (id.toLongOrNull() ?: return@firstNotNullOfOrNull null) to (px.toIntOrNull() ?: return@firstNotNullOfOrNull null)
            }
            parts[1].toIntOrNull()?.let { RecordedWidth(parts[0], it, others) }
        }

        /**
         * The widths every child of [split] but [side] goes back to once [dock] is shown out of
         * it, and the dock's own width; or null where some window there is not where awakener's
         * own hides and shows put it, which is a change of the user's (#270's reviews).
         *
         * Every child but [side] has to be one [home] recorded, at its home width scaled by
         * T / (T − hidden) for the docks hidden out of [split] now, to within [UNTOUCHED_SLACK]
         * px; and every one [home] recorded that is no longer a child has to be awakener's: a
         * dock hidden out of it, or inside [side] — the surface itself, wrapped in a split of its
         * own since, or a dock shown in beside it. So a window the user resized, opened or closed
         * is a change, and another dock hidden or shown in between is not. The targets are the
         * same home widths scaled for the docks still hidden once [dock] is back.
         */
        private fun restoredAround(split: Node, side: Node, dock: Long, home: Map<Long, Int>, hidden: Map<Long, Int>): Pair<Map<Long, Int>, Int>? {
            val now = split.nodes.associate { it.id to (it.rect?.width ?: return null) }
            val total = now.values.sum()
            val own = hidden[dock] ?: return null
            val away = hidden.values.sum()
            if (total - away <= 0) return null
            val others = now - side.id
            if (others.keys.any { it !in home }) return null
            if (home.keys.any { it !in now && it !in hidden && side.find(it) == null }) return null
            val scale = total.toDouble() / (total - away)
            if (others.any { (id, width) -> abs(width - home.getValue(id) * scale) > UNTOUCHED_SLACK }) return null
            val back = total.toDouble() / (total - away + own)
            // A dock attach squeezed under sway's floor comes back at it, which sway would refuse
            // it under: four docks leave the oldest at 99 px, measured on 1.12.
            val width = (own * back).roundToInt().coerceAtLeast(MIN_SANE_WIDTH + PREDICTION_SLACK)
            return others.mapValues { (id, _) -> (home.getValue(id) * back).roundToInt() } to width
        }

        /**
         * What [acrossSplits] plans: [grow] widens the surface's side of the split the dock was
         * hidden from, [size] then sizes the dock inside the surface's own, at [dockWidth] px,
         * which is [ppt] of it.
         */
        private class AcrossSplits(val grow: List<String>, val size: List<String>, val dockWidth: Int, val ppt: Int)

        /**
         * The restore of [hidden] when it is coming back into a different split from the one it
         * was hidden from, which the user's window took over (#256), or null where the show
         * sizes it as before (#267).
         *
         * The hide gave the dock's width to every window left in that split in proportion, the
         * user's included, and the show puts the dock one level down: into [usable], the
         * surface's own split inside the old one, or into the split the batch makes around the
         * surface where [wraps]. A width taken in there alone comes out of the surface and its
         * other docks, once per dock, while the user's window keeps what it was given — 268 px
         * of surface down to 116 over the first round of four docks on 1.12. So the surface's
         * side of the old split is widened by the dock's width first ([walkedTo]), and the dock
         * then takes exactly that width inside, which leaves every window where it was before
         * the hide. Nothing is shown back into the old split, and nothing marks it: it is the
         * user's.
         *
         * Only where the old split is directly around the surface's side of it, since a width
         * given to anything further down is shared out again on the way; only where the
         * widening keeps every window off sway's floor; and only where every window in the old
         * split is where awakener's own hides and shows left it ([restoredAround]). Its widths
         * are then awakener's hand-out and nothing else, whereas a window the user has resized
         * since holds a width they chose, and a show that took the dock's width out of it would
         * undo that (#255's lesson, #270's review: a user window narrowed by hand to 383 px ended
         * at 306). Other docks hidden or shown in between are awakener's, and are allowed for.
         */
        private fun acrossSplits(
            tree: Node,
            hidden: Node,
            surface: SurfaceId,
            usable: Node?,
            wraps: Boolean,
            dockFirst: Boolean,
        ): AcrossSplits? {
            val recorded = recordedWidthOf(hidden) ?: return null
            val home = recorded.home ?: return null
            val outer = recorded.from.toLongOrNull()?.let { tree.find(it) }?.takeIf { it.id != usable?.id } ?: return null
            val side = when {
                usable != null -> usable.takeIf { tree.parentOf(it.id)?.id == outer.id }
                wraps -> tree.find(surface.raw)?.takeIf { tree.parentOf(it.id)?.id == outer.id }
                else -> null
            } ?: return null
            val (targets, dockWidth) = restoredAround(outer, side, hidden.id, home, hiddenFrom(tree, outer.id)) ?: return null
            val growth = outer.walkedTo(side.id, targets) ?: return null
            if (growth.width - dockWidth < MIN_SANE_WIDTH + PREDICTION_SLACK) return null
            // Planned from the split as the widening leaves it: sway keeps every child's fraction
            // of a split it resizes. Beside the surface alone sway's equal share is the surface's
            // whole share, so the plain resize is exact; beside docks too narrow for the walk it
            // is the fallback #264's is, and the split's windows still get back together what
            // the widening gave them.
            val walked = side.rect?.takeIf { side.nodes.size > 1 && it.width > 0 }?.let { rect ->
                val scale = growth.width.toDouble() / rect.width
                side.copy(
                    rect = rect.copy(width = growth.width),
                    nodes = side.nodes.map { it.copy(rect = it.rect?.let { r -> r.copy(width = (r.width * scale).roundToInt()) }) },
                ).proportionalRestore(surface.raw, hidden.id, dockWidth, dockFirst)
            }
            val size = walked ?: listOf("[con_id=${hidden.id}] resize set width $dockWidth px")
            return AcrossSplits(growth.commands, size, dockWidth, (dockWidth * 100.0 / growth.width).roundToInt())
        }

        /**
         * The mark [splitAndClaim] puts on what holds the surface before it splits, named for the
         * claim's nonce, so the split mark can be checked against where the surface stood. Taken
         * off by [settleSplitMarks] in every outcome but a killed process — after the claim, or in
         * [unwindAttach] when the attach fails or is cancelled first — and a leftover one only
         * keeps the verdict it already gave.
         */
        private const val ANCHOR_PREFIX = "_awakener_anchor_"

        /** Whether [split] is awakener's to share, collapse, re-lay or name. See [SplitOwnership]. */
        private fun ownsSplit(split: Node, cfg: Config): Boolean =
            cfg[WmFlags.splitOwnership] == SplitOwnership.SHAPE || split.carriesLiveSplitMark()

        /**
         * Whether this node carries a split mark that is not [inert][isInertSplitMark] — the
         * provenance under [SplitOwnership.MARKED], whatever the flag says.
         */
        private fun Node.carriesLiveSplitMark(): Boolean =
            marks.any { it.startsWith(SPLIT_MARK_PREFIX) && !isInertSplitMark(it) }

        /**
         * Whether [mark], a split mark on this node, is one this node cannot have earned. Either
         * of two things proves the split was not made by the message that marked it, and so is a
         * container `split` re-laid in place — the user's — that `focus parent` happened to reach:
         * - The node is no younger than the newest container the claiming message's tree read had
         *   seen, which [splitAndClaim] writes into the mark. sway mints `con_id`s in increasing
         *   order, so one that is not younger stood before the read (#250's review: a dock mapping
         *   beside a window focused during its start-up, or the surface's last sibling closing
         *   between the read and the message).
         * - The claim's anchor is on this node. That catches what the first cannot, a container
         *   another client made around the surface *after* the read (#252), which is younger than
         *   anything the read saw. See [splitAndClaim].
         *
         * A mark that does not parse as one this build writes is inert too.
         */
        private fun Node.isInertSplitMark(mark: String): Boolean {
            val claim = mark.removePrefix(SPLIT_MARK_PREFIX)
            val newest = claim.substringBefore('_').toLongOrNull()
            return newest == null || id <= newest || ANCHOR_PREFIX + claim.substringAfter('_') in marks
        }

        /**
         * The largest container `con_id` in the tree — the high-water mark [splitAndClaim]
         * records. Containers only: sway gives the `__i3` output and its scratchpad workspace
         * `INT_MAX` and `INT_MAX - 1` (measured on 1.12), which would make every mark inert.
         */
        private fun Node.newestId(): Long = children.fold(
            if (type == CON || type == FLOATING_CON) id else 0L,
        ) { acc, child -> maxOf(acc, child.newestId()) }

        /**
         * One message that splits [surface] and marks the new split as awakener's, leaving focus
         * on [refocus].
         *
         * **One message, because the mark is only worth anything if it cannot be missing.** sway
         * runs a message to the end before it reads another, so no cancellation, killed process
         * or failed readback can land between the split and its mark; as two round trips, a
         * cancellation swept across the gap left unmarked splits nothing ever flattened, nesting
         * a level per interrupted attach — #171's chain, with nothing to collapse it. The split's
         * `con_id` does not exist until the split does, so the mark is a nonce, named before the
         * message — random for [moveToMarkOn]'s reason, so it is never one already placed, and
         * one per split so that marking one never takes the mark off another.
         *
         * Only for a split that will wrap — see [splitWraps]. Where sway re-lays the parent in
         * place instead, `focus parent` reaches a workspace and sway refuses to mark it,
         * measured on 1.12.
         *
         * **And the mark is only good on a split this message made**, which the read [before]
         * cannot promise: that read is a round trip old by the time the message runs, and a split
         * that re-lays rather than wraps puts the mark on a container that already stood — the
         * user's, or another client's made since the read. sway has no conditional command, so
         * the message cannot check; it leaves evidence instead, and [isInertSplitMark] judges the
         * mark by it on every read:
         * - The mark carries [before]'s newest `con_id`, which refuses any container that stood at
         *   the read.
         * - Before the split, the message puts an anchor named for the same nonce on the
         *   container holding the surface as it stands *then*: up two parents and back down one
         *   child, which is that container where the surface's parent is one, and the surface
         *   itself where its parent is a workspace — sway refuses to mark a workspace, and the
         *   refusal is a parse error that ends the message, so `focus parent` once cannot be the
         *   route (measured on 1.12, as is that `focus parent` on a workspace is accepted and
         *   stays, and that `focus child` goes back the way focus came). A split that wrapped
         *   leaves the anchor on the new split's parent, or on the surface inside it; one that
         *   re-laid leaves it on the marked container itself, which is what refuses a container
         *   another client made around the surface since the read. The anchor stays until
         *   [settleSplitMarks] takes it off with any mark it condemns, so a process dying in
         *   between leaves the verdict standing rather than undone.
         *
         * No anchor for a floating surface, alone or inside a floating split: the walk leaves the
         * floating container for the workspace, and `focus child` from there goes to a tiled
         * window — both with the surface floating alone and with another client's floating split
         * around it, so the anchor cannot tell the two apart — or, on a workspace with no tiled
         * window, to nothing, so the `mark` is refused and ends the message (all measured on
         * 1.12; #254's review). There a container another client wraps the surface in between
         * the read and the message can still be claimed.
         *
         * Even a refused claim has re-laid that container in place, so another client's `splitv`
         * is left a `splith`. It survives; no command in the message can avoid the re-lay.
         */
        private fun splitAndClaim(surface: SurfaceId, before: Node, refocus: Long = surface.raw): String {
            val nonce = Random.nextLong().toULong().toString(16)
            val mark = SPLIT_MARK_PREFIX + before.newestId() + "_" + nonce
            val anchor = if (floatingAncestorOf(before, surface) != null) {
                ""
            } else {
                "[con_id=${surface.raw}] focus; focus parent; focus parent; focus child; " +
                    "mark --add $ANCHOR_PREFIX$nonce; "
            }
            return anchor + "[con_id=${surface.raw}] split horizontal; [con_id=${surface.raw}] focus; " +
                "focus parent; mark --add $mark; [con_id=$refocus] focus"
        }

        /**
         * Whether `split` on [surface] makes a new container rather than re-laying its parent in
         * place: it wraps a window with siblings, a window in a tab strip — a container's or a
         * tabbed workspace's own — and a floating window, and re-lays the parent of an only child
         * in a split container or on a split workspace — measured on headless sway 1.12.
         */
        private fun splitWraps(tree: Node, surface: SurfaceId): Boolean {
            if (floatingAncestorOf(tree, surface)?.id == surface.raw) return true
            val parent = tree.parentOf(surface.raw) ?: return false
            return parent.nodes.size > 1 || parent.layout == "tabbed" || parent.layout == "stacked"
        }

        /** The `con_id` of the node sway reports focused, or null when none is. */
        private fun Node.focusedId(): Long? =
            if (focused) id else children.firstNotNullOfOrNull { it.focusedId() }

        /**
         * The container a flatten of [split] around [survivor] has to lift the survivor into
         * rather than `split none`, or null when `split none` stops where it should.
         *
         * Under [SplitOwnership.MARKED] that is any single-child container directly above
         * [split]: sway's flatten walks on through every one of them ([tabAbove]), and none is
         * awakener's — a split of the user's the surface sat alone in, floating or not (#253), or a
         * chain no build has a record of making. Under [SplitOwnership.SHAPE] the walk is wanted, and only a tab strip
         * stops it, as before #248.
         */
        private fun liftTarget(tree: Node, split: Node, survivor: SurfaceId, cfg: Config): Node? =
            when (cfg[WmFlags.splitOwnership]) {
                SplitOwnership.SHAPE -> tabAbove(tree, survivor)
                SplitOwnership.MARKED -> tree.parentOf(split.id)?.takeIf {
                    (it.type == CON || it.type == FLOATING_CON) && it.nodes.size == 1
                }
            }

        /**
         * The tab strip a `split none` on [survivor] would destroy along with the split it is
         * meant for, or null when the flatten would stop at splits.
         *
         * **sway's flatten does not stop at one level.** It replaces the survivor's parent with
         * the survivor, then does the same to each ancestor above for as long as that one too has
         * a single child, whatever its layout — measured on headless sway 1.12. Across a run of
         * splits that is the chain collapse `SwaySplitChainTest` relies on. But with the surface
         * the only window on a `workspace_layout tabbed` workspace, the strip sway made for it
         * holds nothing but attach's split, and the walk takes the strip as well: the next window
         * then opens beside the surface instead of as a tab, and every dock after stands directly
         * on the workspace (#226). A second tab stops the walk at the strip, which is why only a
         * lone window ever showed it.
         *
         * So this walks the same run sway will — the survivor's parent and each single-child
         * container above it — and answers with the lowest that is not a split.
         */
        private fun tabAbove(tree: Node, survivor: SurfaceId): Node? =
            generateSequence(tree.parentOf(survivor.raw)) { tree.parentOf(it.id) }
                .takeWhile { it.type == CON && it.nodes.size == 1 }
                .firstOrNull { !it.isSplit() }

        /**
         * One message that moves [survivor] into [tab] as a direct child, which leaves the splits
         * it sat in empty for sway to reap and [tab] standing — the flatten, stopped at the strip.
         *
         * sway names a destination only by mark, and a `move … to mark` onto a container puts the
         * window *inside* it (measured on 1.12, as [evictionCommands] records), so [tab] is marked
         * for the length of the message. The mark is random so it is never one a user already
         * placed — `mark --add` would move theirs and the `unmark` would then delete it — and the
         * `unmark` runs even when the move is refused, since sway carries on through a message
         * past a failed command (measured).
         */
        private fun liftInto(tab: Node, survivor: SurfaceId): String =
            moveToMarkOn(tab.id, survivor.raw)

        /**
         * One message moving [mover] to [target] by a mark held for the length of that message:
         * *into* [target] when it is a container, beside it when it is a window (measured on 1.12).
         */
        private fun moveToMarkOn(target: Long, mover: Long): String {
            val mark = "_awakener_move_${Random.nextLong().toULong().toString(16)}"
            return "[con_id=$target] mark --add $mark; " +
                "[con_id=$mover] move container to mark $mark; unmark $mark"
        }

        /**
         * The floating container [id] is in — [id] itself when it floats alone — or null when it
         * is tiled. Only the outermost container of a floating subtree is a `floating_con`, so the
         * first one up the parents is the whole floating window.
         */
        private fun floatingAncestorOf(tree: Node, id: SurfaceId): Node? =
            generateSequence(tree.find(id.raw)) { tree.parentOf(it.id) }
                .firstOrNull { it.type == FLOATING_CON }

        /**
         * #229: the hide for a dock inside [floating], a floating window — which is where attach
         * puts it beside a floating surface, since a split of a floating window floats.
         *
         * **`move scratchpad` cannot be aimed at the dock there.** On any child of a floating
         * container sway moves the whole container, so the plain hide took the surface into the
         * scratchpad with its panel. `floating enable` and `move right` on that child do nothing
         * for the same reason, and a move to a mark on the window puts it back inside — all
         * measured on headless sway 1.12.
         *
         * **A move to another workspace does take the dock out alone, and is the only command
         * found that does.** So the dock goes to a workspace of its own and straight back to the
         * floating window's workspace, where it arrives as an ordinary tiled window — beside the
         * workspace's last-focused *tiled* node, never inside the floating split — that
         * `move scratchpad` then takes by itself. The return is what disposes of the passage: a
         * workspace the dock merely *leaves for the scratchpad* is left standing, empty, while one
         * it leaves by a move to another workspace is destroyed. A `move … to output` will not do
         * for the return: it lands the dock beside the output's focused node, which is the surface,
         * and so back inside the split. All measured on 1.12, and all one message, so nothing in
         * between is presented (#172).
         *
         * **Nothing here changes the surface's floating state**, and that is the point of the
         * shape (the review of #232). The first fix tiled the floating window for the length of
         * the message and floated it again on its old rectangle, and the surface's client then
         * committed the size it had held inside the split *after* sway had laid the window out —
         * which sway keeps for a floating window — so a hide cost the window the panel's share of
         * its width, compounding across cycles; at `scale 1.5` on nearly every run. The floating
         * window here never leaves the float, its rectangle is never re-asserted, and the surface
         * is resized within it by layout, which a tiled client's commits do not override.
         *
         * Under [flatten] — [WmFlags.flattenFloatingOnHide], off by default for the reason its
         * description gives — and when nothing but the surface and dock were in the window, the
         * window is then collapsed onto the surface: the collapse #187 makes for a tiled split,
         * since a new window maps inside a focused floating split as well, which is how attach's
         * own dock gets there. `split none` on the only child of a floating split leaves it
         * floating alone on the split's rectangle, measured on 1.12. That collapse is the one
         * step here that does make the surface a floating window, which is why it is not the
         * default.
         */
        private fun floatingHideCommands(
            tree: Node,
            floating: Node,
            surface: SurfaceId,
            dock: SurfaceId,
            flatten: Boolean,
        ): List<String> {
            val name = checkNotNull(workspaceOf(tree, floating)?.name) {
                "floating window ${floating.id} holding dock ${dock.raw} is on no workspace"
            }
            // Refused rather than hidden the old way, which would take the surface with it.
            val workspace = checkNotNull(quotedWorkspace(name)) {
                "cannot hide dock ${dock.raw}: its workspace '$name' holds both kinds of quote, " +
                    "so no sway command can name it"
            }
            val passage = "__awakener_hide_${Random.nextLong().toULong().toString(16)}"
            val alone = floating.nodes.map { it.id }.toSet() == setOf(surface.raw, dock.raw)
            return buildList {
                add("[con_id=${dock.raw}] move container to workspace $passage")
                add("[con_id=${dock.raw}] move container to workspace $workspace")
                add("[con_id=${dock.raw}] move scratchpad")
                if (flatten && alone) add("[con_id=${surface.raw}] split none")
            }
        }

        /** The workspace [node] is on, or null when it is on none. */
        private fun workspaceOf(tree: Node, node: Node): Node? =
            generateSequence(tree.parentOf(node.id)) { tree.parentOf(it.id) }
                .firstOrNull { it.type == WORKSPACE }

        /**
         * [name] as a single sway argument, or null when it cannot be written as one.
         *
         * sway does not unescape inside quotes — `"a \"q\" b"` names the workspace `a \"q\" b`,
         * backslashes and all, measured on 1.12 — so a quote can only appear inside the *other*
         * kind of quote, and a name holding both kinds has no spelling at all.
         */
        private fun quotedWorkspace(name: String): String? = when {
            '"' !in name -> "\"$name\""
            '\'' !in name -> "'$name'"
            else -> null
        }

        /**
         * The commands that move every window sharing [dock]'s split, other than [surface] and
         * [dock], out of it — empty when there is nothing to move, no split that is the dock's, or
         * no side of it a window can leave by without landing in someone else's container.
         *
         * **Whose split.** The dock is visible when this is asked, so its parent is the container
         * attach put it in, and that is the only container this will empty; the surface has to
         * sit in it too, so a dock a user dragged into some other split of theirs leaves that
         * split alone. Asked of the dock rather than from the surface's side ([dockSplitOf]),
         * because a surface's parent split is not evidence that awakener made it.
         *
         * **Where they go.** sway has no command that names a destination beside a container: a
         * `move … to mark` onto a split puts the window *inside* it, and onto a leaf beside the
         * leaf. A `move` in a direction, though, takes a window that is at the edge of its
         * container out to the parent's level — and where it lands there depends on the split's
         * neighbour on that side, measured on headless sway 1.12. With no neighbour, or a
         * neighbour that is a single window, the window lands directly beside the split, as its
         * sibling. With a neighbour that is a **container** — a user's own split tab — sway moves
         * the window *into* that container, which is somebody else's tab and a split awakener
         * did not make. So the side is chosen: after the split when that side is clear, else
         * before it when that one is, and when both neighbours are containers nothing moves at
         * all, leaving the window visible beside the dock where it arrived rather than putting
         * it anywhere wrong.
         *
         * The direction is along the *parent's* axis, and the count is one step per sibling
         * between the window and that edge, plus the one that leaves — or just the one when the
         * split runs across that axis, since then the window is at the edge already. A step past
         * a sibling is a swap only when the sibling is a leaf (sway walks into a container), which
         * is why the window nearest the edge goes first: by the time each moves, everything
         * between it and the edge is the surface or the dock, both of them windows. It also keeps
         * their order — each lands directly beside the split, on the far side of the ones that
         * left before it.
         *
         * **Focus.** Every command targets by `con_id`, and a criteria-targeted `move` leaves
         * focus where it was — measured on 1.12 with the surface focused and an intruder moved.
         */
        private fun evictionCommands(
            tree: Node,
            surface: SurfaceId,
            dock: SurfaceId,
            cfg: Config,
            isDock: (Node) -> Boolean,
        ): List<String> {
            // Awakener's own split only (#248): a dock standing in a split of the user's — one a
            // build before the provenance mark attached — does not make their windows its to move.
            val split = tree.parentOf(dock.raw)?.takeIf { it.isSplit() && ownsSplit(it, cfg) }
                ?: return emptyList()
            if (split.nodes.none { it.id == surface.raw }) return emptyList()
            val parent = tree.parentOf(split.id) ?: return emptyList()
            val horizontal = when (parent.layout) {
                "splith", "tabbed" -> true
                "splitv", "stacked" -> false
                else -> return emptyList()
            }
            val at = parent.nodes.indexOfFirst { it.id == split.id }
            if (at < 0) return emptyList()
            // A side is clear when a window leaving by it lands beside the split: nothing there,
            // or a single window. A container there would take the window in.
            fun clear(neighbour: Node?) = neighbour == null || neighbour.nodes.isEmpty()
            val forward = when {
                clear(parent.nodes.getOrNull(at + 1)) -> true
                clear(parent.nodes.getOrNull(at - 1)) -> false
                else -> return emptyList()
            }
            val direction = when {
                horizontal && forward -> "right"
                horizontal -> "left"
                forward -> "down"
                else -> "up"
            }
            val along = (split.layout == "splith") == horizontal
            val order = split.nodes.map { it.id }
            // Every dock of the surface stays, not only the one this is for: a second dock sharing
            // the split (wm.dock.share_split) is no intruder (#260's review).
            val docked = split.nodes.filter { it.id == dock.raw || isDock(it) }.map { it.id }.toSet()
            val staying = { id: Long -> id == surface.raw || id in docked }
            // Nearest the exit first, so that only the surface and the dock are ever stepped past.
            val sequence = if (forward) order.indices.reversed() else order.indices
            return sequence
                .filter { !staying(order[it]) }
                .flatMap { index ->
                    // Siblings still between this one and the exit: the ones staying, since every
                    // intruder nearer the exit has already gone.
                    val between = if (forward) order.drop(index + 1) else order.take(index)
                    val steps = if (along) between.count(staying) + 1 else 1
                    List(steps) { "[con_id=${order[index]}] move $direction" }
                }
        }

        /**
         * How many times an unwind will try to flatten the container it is taking back while the
         * dock it spawned is still unidentified. Two, and the second is what covers a dock that
         * maps between the unwind's read of the tree and its `split none` — see
         * [TreeEdit.unwindAttach]. A third would cover nothing: the dock program maps one window,
         * and by the second pass that window is either down or was never going to arrive in time.
         */
        const val FLATTEN_PASSES = 2

        /**
         * The environment variable [WmFlags.stoodUpProof]'s token travels in.
         *
         * Prefixed and specific so that a dock program passing its environment on to a child does
         * not collide with anything, and so that a reader of `/proc` sees what it is looking at.
         */
        const val SPAWN_TOKEN_VAR = "AWAKENER_DOCK_TOKEN"

        /**
         * How many random bytes a spawn token is. Sixteen — 128 bits — because unlike the mark's
         * nonce this one is never published, so guessing is a route rather than a formality.
         */
        const val SPAWN_TOKEN_BYTES = 16

        /** How the kernel separates the entries in `/proc/<pid>/environ`. */
        const val NUL = '\u0000'

        /**
         * The workspace sway parks scratchpad windows on.
         *
         * i3's name, kept by sway, and a fact about the compositor rather than a setting: there is
         * no command that renames it and no configuration that moves it. Hard-coded here — inside
         * the one class that is allowed to know what a scratchpad is — rather than in `Tree.kt`,
         * which models sway's tree but is shared with `commonMain`.
         */
        const val SCRATCH_WORKSPACE = "__i3_scratch"

        /**
         * The `type` sway gives a node that is floating rather than tiled.
         *
         * A fact about the compositor like [SCRATCH_WORKSPACE], and the one a restore reads back:
         * a panel that came out of the scratchpad and was never re-tiled is `type=floating_con`,
         * `floating=user_on`, centred over the surface. Measured on 1.12.
         */
        const val FLOATING_CON = "floating_con"

        /** How far a floating window may sit from a rectangle it was put back on, in pixels. */
        const val GEOMETRY_SLACK = 2

        /** How many late commits one floating detach's settle puts right before it gives up. */
        const val SETTLE_CORRECTIONS = 3

        /** The `type` sway gives a workspace node. */
        const val WORKSPACE = "workspace"

        /**
         * How far [Node.percent] may sit from the configured fraction and still count as landed.
         *
         * Half a percentage point. It absorbs the double sway reports — 30 ppt comes back as
         * `0.29999999999999999` — and nothing else: the intermediate layouts a restore has to
         * reject differ by tens of points, not by a rounding error.
         */
        const val PPT_TOLERANCE = 0.005

        /**
         * The source spawn tokens are drawn from.
         *
         * One instance for the process: [SecureRandom] is thread-safe, and constructing one per
         * attach would put a seeding cost on the hotkey path for nothing.
         */
        val spawnTokens = SecureRandom()
    }
}

/**
 * sway's floor on a tiled window's width, `MIN_SANE_W` in `include/sway/tree/node.h` (100 in both
 * 1.11 and 1.12).
 * A resize that would take any window under it is not made.
 */
internal const val MIN_SANE_WIDTH = 100

/**
 * Which windows sway takes a `resize set width` from, which is the one thing about the rule that
 * changed between the two sways awakener runs on (#269). `container_resize_tiled` in
 * `sway/commands/resize.c`, as rewritten by swaywm/sway@340505bb ("make resize consider all
 * siblings"), first released in 1.12. Edge resizes (`resize grow|shrink left|right`) take from
 * the one neighbour on that side under both, so only this differs.
 */
internal enum class ResizeShare {
    /**
     * Before 1.12: from the window's neighbours alone, half each rounded up, or all of it from the
     * one neighbour a window at either end of the split has. CI's sway, Ubuntu's 1.11.
     */
    NEIGHBOURS,

    /** 1.12 on: from every other window in the split, an equal share each rounded up. */
    EVERY_SIBLING,
    ;

    companion object {
        /**
         * What a compositor whose version cannot be read is taken to do: the rule of the sway the
         * hosts run, and of every release since. A wrong guess costs a show that throws on a short
         * dock sway refused, or one that excuses a dock sway would have resized; the guess most
         * likely to be right is the newest rule.
         */
        val UNKNOWN_VERSION = EVERY_SIBLING

        /**
         * The rule sway [version] applies. sway fills `major` and `minor` by `sscanf`ing its own
         * version string (`sway/ipc-json.c`), so a release candidate reads as its release:
         * `1.12-rc1` is 1.12, and swaywm/sway@340505bb is already in it. Three cases read wrong
         * or not at all, and are stated rather than guessed at:
         *
         * - A `1.12-dev` git build from before swaywm/sway@340505bb reports 1.12 and still has the
         *   old rule. Only a development build between the version bump and that commit is there;
         *   no release is.
         * - A version string sway could not parse reaches IPC as 0.0. That says nothing about the
         *   build, so it is [UNKNOWN_VERSION] rather than "older than 1.12".
         * - A reply without `major` or `minor`, or one that is not a version object at all
         *   ([of] taking the raw reply), is [UNKNOWN_VERSION] too. A show does not fail over it.
         */
        fun of(version: SwayVersion): ResizeShare {
            val major = version.major ?: return UNKNOWN_VERSION
            val minor = version.minor ?: return UNKNOWN_VERSION
            return when {
                major == 0 -> UNKNOWN_VERSION
                major > 1 || major == 1 && minor >= 12 -> EVERY_SIBLING
                else -> NEIGHBOURS
            }
        }

        /** [of] the raw `GET_VERSION` [reply], or [UNKNOWN_VERSION] where it is not one. */
        fun of(reply: String): ResizeShare =
            runCatching { swayJson.decodeFromString<SwayVersion>(reply) }.map(::of).getOrDefault(UNKNOWN_VERSION)
    }
}

/** The [ResizeShare] of the compositor at the other end of this connection, from its version. */
internal suspend fun SwayConnection.resizeShare(): ResizeShare = ResizeShare.of(request(I3Ipc.Request.GET_VERSION))

/**
 * How many pixels short of [sizePpt] sway leaves the window [dock] because it refused the
 * `resize set width` that asked for it — negative where it was refused a narrowing, which would
 * have put the dock itself under the floor — or null when sway would have made that resize
 * (#261).
 *
 * This is sway's own decision, recomputed from the tree it acted on rather than allowed for with
 * slack. `resize set width` on a tiled window converts the percentage to pixels of the nearest
 * horizontal split, then takes the growth from the windows [share] names — and when any one of
 * them, or the dock, would end under [MIN_SANE_WIDTH], it changes nothing and still reports
 * success (`resize_set_tiled` and `container_resize_tiled` in `sway/commands/resize.c`). A
 * refused resize leaves the tree exactly as it was, so the tree read afterwards is the one sway
 * judged.
 *
 * Several docks on one surface is what reaches it: each new one is tiled beside the surface and
 * the oldest is squeezed towards the floor, so the restore's resize is refused and the dock
 * keeps the share `floating disable` gave it — measured on headless 1.12 at 25% of a split of
 * four and 20% of a split of five, against 30 configured.
 *
 * Null for anything sway would have resized, so a dock short of its width in a split with room
 * to give it — the resize never sent, or undone by another client — is still a fault. That is
 * why [share] is the running compositor's and not the union of both rules: either rule alone
 * refuses resizes the other makes (#269).
 */
internal fun Node.refusedResize(dock: Long, sizePpt: Int, share: ResizeShare): Int? {
    val split = parentOf(dock)?.takeIf { it.layout == "splith" } ?: return null
    val index = split.nodes.indexOfFirst { it.id == dock }.takeIf { it >= 0 } ?: return null
    if (split.nodes.size < 2) return null
    val splitWidth = split.rect?.width ?: return null
    val width = split.nodes[index].rect?.width ?: return null
    val growth = splitWidth * sizePpt / 100 - width
    // The dock is in sway's check too: one that would be *narrowed* under the floor is refused
    // the same way, which a show into a narrow split reaches (#264's map, four docks and a
    // window of the user's).
    if (width + growth < MIN_SANE_WIDTH) return growth
    if (growth <= 0) return null
    val givers = when (share) {
        ResizeShare.EVERY_SIBLING -> split.nodes.filterIndexed { i, _ -> i != index }
        ResizeShare.NEIGHBOURS -> listOfNotNull(split.nodes.getOrNull(index - 1), split.nodes.getOrNull(index + 1))
    }
    val each = (growth + givers.size - 1) / givers.size
    val refused = givers.any { (it.rect?.width ?: return null) - each < MIN_SANE_WIDTH }
    return growth.takeIf { refused }
}

/**
 * How far a width this predicts may be from the one sway arrives at, in px: each is a rounding
 * of a fraction sway holds and the tree does not show, and a boundary sits after several.
 */
private const val PREDICTION_SLACK = 2

/**
 * How far a window in a split a dock was hidden from may be from where the hide's proportional
 * hand-out put it and still count as untouched: each width is a rounding of a fraction, twice —
 * once beside the dock, once after it — and a hand resize is tens of px, not a few.
 */
private const val UNTOUCHED_SLACK = 4

/**
 * The `resize` commands that bring a dock back into the horizontal split [this] at [dockWidth] px
 * of it, with every window already there giving up width in proportion to its own (#264); or null
 * where that cannot be planned, and the caller falls back to `resize set width`.
 *
 * [this] is the split as it stands before the show, the dock still in the scratchpad. The dock
 * comes back right after [surface], which is where `floating disable` tiles it beside the focused
 * window, or right before it when [dockFirst] says a `move left` follows.
 *
 * Proportional because that is how the hide handed the dock's width out: sway keeps each
 * window's fraction of the split and scales the rest up to fill it. So taking it back the same
 * way, at the share the dock was hidden at, puts every window where it was before the hide.
 * `resize set width` cannot: it takes the growth from every sibling in an *equal* share (sway
 * 1.12; before it, from the dock's neighbours alone — [ResizeShare]), so each
 * show takes more from a window narrower than the average than the hide gave it, and with several
 * docks on one surface the surface is walked down to sway's floor (measured on headless 1.12,
 * 217 px to 100 over twelve rounds of four docks).
 *
 * sway has no proportional resize, so this plans the final widths and walks the boundaries
 * left to right: `resize grow|shrink right` moves one window's right edge, taking from or giving
 * to its right neighbour alone (`container_resize_tiled` with an edge, sway 1.12), and the
 * boundary after window i moves by the sum of the changes the windows up to i need. The widths
 * it walks from are predicted, because the plan has to go out in the same message as the
 * `floating disable` (#172): sway gives the window it tiles in the mean of the existing
 * fractions, so with n windows already there the dock gets 1/(n+1) of the split and every other
 * window keeps its share of the rest. A window that stood under the floor before the hide comes
 * back at it instead ([floored]). A boundary that would take either window beside it under
 * [MIN_SANE_WIDTH], which sway refuses while reporting failure, makes the plan null rather than a
 * command that would fail the batch.
 */
internal fun Node.proportionalRestore(surface: Long, dock: Long, dockWidth: Int, dockFirst: Boolean): List<String>? {
    if (layout != "splith") return null
    val before = nodes.map { node -> node.id to (node.rect?.width ?: return null) }
    val total = before.sumOf { it.second }
    val surfaceAt = before.indexOfFirst { it.first == surface }
    if (surfaceAt < 0 || total <= 0) return null
    val count = before.size
    val ids = before.map { it.first }.toMutableList().apply { add(if (dockFirst) surfaceAt else surfaceAt + 1, dock) }
    val index = before.toMap()
    fun widths(dockShare: Int, share: (Int) -> Int): List<Int> {
        val widths = ids.map { if (it == dock) dockShare else share(index.getValue(it)) }
        // sway gives the last window whatever the rest leave, and so does this.
        return widths.dropLast(1) + (total - widths.dropLast(1).sum())
    }
    val current = widths((total.toDouble() / (count + 1)).roundToInt()) {
        (it.toDouble() * count / (count + 1)).roundToInt()
    }
    val target = widths(dockWidth) { (it.toDouble() * (total - dockWidth) / total).roundToInt() }
    return floored(target, keep = ids.indexOf(dock))?.let { walk(ids, current, it) }
}

/**
 * [target] with every width but [keep]'s raised to the least a walk can leave a window at, the
 * difference taken from the others in proportion to their own; or null where they cannot spare
 * it.
 *
 * A window can stand under sway's floor — `floating disable` tiles a window in at the mean share
 * whatever that leaves the others, and several docks on one surface reach it — and a plan putting
 * it back there is one sway refuses. Measured on CI's sway 1.11 (#267): four docks beside the
 * surface, the oldest at 97 px, so the walk was not planned and the show fell back to `resize set
 * width`, whose equal share took 13 px of the surface to the narrow docks.
 */
private fun floored(target: List<Int>, keep: Int): List<Int>? {
    val floor = MIN_SANE_WIDTH + PREDICTION_SLACK
    val short = target.indices.filter { it != keep && target[it] < floor }
    if (short.isEmpty()) return target
    val donors = target.indices.filter { it != keep && it !in short }
    val pool = donors.sumOf { target[it] }
    val deficit = short.sumOf { floor - target[it] }
    if (donors.isEmpty() || pool - deficit < donors.size * floor) return null
    val out = target.toMutableList()
    short.forEach { out[it] = floor }
    donors.forEach { out[it] = target[it] - (deficit.toDouble() * target[it] / pool).roundToInt() }
    out[donors.last()] += target.sum() - out.sum()
    return out
}

/**
 * The boundary walk taking the children [ids] of a horizontal split from [current] widths to
 * [target] ones, left to right, or null where a step would take a window under the floor: each
 * `resize grow|shrink right` moves one window's right edge against its right neighbour alone, so
 * the boundary after window i moves by the sum of the changes the windows up to i need.
 */
private fun walk(ids: List<Long>, current: List<Int>, target: List<Int>): List<String>? {
    if (target.any { it < MIN_SANE_WIDTH + PREDICTION_SLACK }) return null
    var moved = 0
    return ids.dropLast(1).mapIndexedNotNull { i, id ->
        moved += target[i] - current[i]
        if (current[i + 1] - moved < MIN_SANE_WIDTH + PREDICTION_SLACK) return null
        when {
            moved > 0 -> "[con_id=$id] resize grow right $moved px"
            moved < 0 -> "[con_id=$id] resize shrink right ${-moved} px"
            else -> null
        }
    }
}

/** What [walkedTo] plans: the walk, and the width the side ends at. */
internal class Growth(val commands: List<String>, val width: Int)

/**
 * The `resize` commands that take every child of this horizontal split but [side] to its width
 * in [targets], [side] taking whatever they leave; or null where that cannot be planned (#267).
 *
 * This is the hide's distribution run backwards one split up. A dock hidden out of a split that
 * holds a window of the user's leaves that split to the user (#256), and sway hands the dock's
 * width to every window left in it in proportion, the user's included. The show then brings the
 * dock back into a split of the surface's own one level down — [side], in the old split — and a
 * width taken inside that split alone comes out of the surface and its other docks while the
 * user's window keeps what the hide gave it. Walking the others back to what they had first
 * takes it back from where it went. Unlike [proportionalRestore] nothing here is predicted: no
 * window is tiled into this split, so the widths walked from are the ones the tree reports.
 */
internal fun Node.walkedTo(side: Long, targets: Map<Long, Int>): Growth? {
    if (layout != "splith" || nodes.size < 2) return null
    val ids = nodes.map { it.id }
    val current = nodes.map { it.rect?.width ?: return null }
    val at = ids.indexOf(side)
    if (at < 0 || targets.keys != ids.toSet() - side) return null
    val target = ids.map { targets[it] ?: 0 }.toMutableList()
    target[at] = current.sum() - target.sum()
    return walk(ids, current, target)?.let { Growth(it, target[at]) }
}
