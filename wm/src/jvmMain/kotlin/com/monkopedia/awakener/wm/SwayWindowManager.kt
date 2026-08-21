package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.ResidueOutcome
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.asIdentity
import java.io.File
import java.security.SecureRandom
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
    private class Session(val connection: SwayConnection, val generation: Long)

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
     * A list rather than a set, and never pruned: two surfaces failing to dispose of residue for
     * the same reason are two models still on disk, and collapsing them would lose one. Ordered so
     * that a reader taking the last entry gets the most recent.
     *
     * Appended to **before** [WmFlags.detachResidueFailure] decides whether to raise, so a caller
     * that catches the raise and a caller that never sees one read the same list.
     */
    val residueDisposalFailures: StateFlow<List<ResidueDisposalFailure>> =
        residueFailures.asStateFlow()

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
        treeEditLock.withLock { TreeEdit(session()).edit() }

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
         */
        suspend fun normalizeContainer(survivor: SurfaceId) {
            if (tree().find(survivor.raw) == null) return
            focus(survivor)
            run("split none")
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
            run("""no_focus [app_id="$appId"]""")
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
         * may `exec` while the lock is held, which is the same exclusion [awaitWindow]'s
         * [PendingDock.standing] set rests on.
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
                    // Focus first: sway's split applies to the focused container, and the dock has
                    // to land inside this surface's tab rather than wherever focus happened to be.
                    focus(surface)
                    // Recorded before the command rather than after it, for the same reason
                    // `pending` is: a fact recorded on the far side of a round trip is one the
                    // failure that needs it can arrive ahead of — a cancellation landing on this
                    // acknowledgement would otherwise leave the container built and unrecorded.
                    // Rolled back only where sway itself refuses, which is the one failure that
                    // means the container certainly does not exist.
                    container = true
                    try {
                        run("split horizontal")
                    } catch (rejected: IllegalStateException) {
                        container = false
                        throw rejected
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
                    // exec before the claim.
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
                    if (cfg[WmFlags.dockSide] == DockSide.LEFT) {
                        run("[con_id=${dockId.raw}] move left")
                    }
                    run("[con_id=${dockId.raw}] resize set width ${cfg[WmFlags.dockSizePpt]} ppt")

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
                    dockId to session
                } catch (failure: Throwable) {
                    unwindAttach(surface, spawned, pending, container, failure)
                    throw failure
                }
            }

            // Outside the section on purpose: this is not a tree edit, and in the hotkey case it
            // mints, which reaches a spanreed subprocess. Holding the compositor across a process
            // spawn would stall every other attach and detach behind it — the same call
            // `FileBindingStore.bind` makes one module down, for the same reason.
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
                failures += IllegalStateException(
                    "reaping dock ${node.id}, bound to the gone surface $boundTo, failed",
                    failure,
                )
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
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

        override suspend fun detach() {
            checkSession("detach")
            val cfg = config
            treeEdit {
                val parent = tree().parentOf(dockId.raw)
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
                    return@treeEdit
                }
                // Only once the node has actually left the tree: a dock that outlived its kill is
                // still a dock, and forgetting it here would hand the wedged panel back to
                // enumeration as a bindable surface.
                docks.forget(dockId)

                if (!cfg[WmFlags.normalizeContainerOnDetach]) return@treeEdit
                val survivor =
                    parent?.children?.firstOrNull { it.id != dockId.raw } ?: return@treeEdit
                // Raised rather than caught: the orphan sweep's aggregate is what a normalisation
                // sway refuses is reported through, and it tags the dock the failure came from.
                normalizeContainer(SurfaceId(survivor.id))
            }
            // Outside the section for the same reason as attach's bind: the registry is not the
            // tree, and the dock is already down by here.
            if (cfg[WmFlags.forgetBindingOnDetach] && key != null) {
                // The `Forget` is read rather than discarded. Its residue half exists so that a
                // disposal which failed with the binding already durably gone can be told from one
                // that worked; dropping it made that failure loud through
                // `awakener-registry forget`, which exits 3 for it, and silent here — on the path
                // nobody invokes deliberately, since a dock closing takes it automatically (#115).
                val forget = registry.unbind(key)
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
         * The source spawn tokens are drawn from.
         *
         * One instance for the process: [SecureRandom] is thread-safe, and constructing one per
         * attach would put a seeding cost on the hotkey path for nothing.
         */
        val spawnTokens = SecureRandom()
    }
}
