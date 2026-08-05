package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.asIdentity
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
     */
    private val scope: CoroutineScope,
) : WindowManager {
    private val commands: SwayConnection by lazy { connect() }

    private val config: Config get() = store.config.value

    private val treeEditLock = Mutex()

    /**
     * The docks this process knows about. See [DockTable]; it is read on every enumeration, and
     * written by [attach], by a teardown, and by [dockedTo] adopting a dock it found by mark.
     *
     * `internal` rather than private only so that the tests in this module can assert on it
     * directly. Discarding it at the session boundary has no observable consequence from outside —
     * by then the connection every read would go through is dead — so this is the one invariant
     * that cannot be checked through behaviour.
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
     * [ReapEvidence.STOOD_UP] is the value that does not ask the tree anything. Every mark sway
     * holds is writable from `swaymsg` — the same `RUN_COMMAND` and the same parser awakener uses,
     * measured on 1.12 — so a mark is evidence that is only ever *unlikely* to be somebody else's,
     * never impossible; awakener's own record of standing the dock up is the one thing here that a
     * desktop cannot write. What it costs is stated at the flag, and it is the mark's own purpose:
     * a dock adopted after a restart is then never reaped.
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
     * [commands] and [connect] stay in scope for the whole class, so a determined author can still
     * drive sway unlocked. The claim is only that doing so takes deliberate effort rather than
     * inattention.
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
        treeEditLock.withLock { TreeEdit().edit() }

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
    private class PendingDock(val appId: String, val standing: Set<Long>)

    /**
     * The only way to change the tree. See [treeEdit] for why it is a receiver.
     *
     * Everything here assumes the lock is held. Constructing it is [treeEdit]'s job alone: do not
     * hold an instance in a field or return one out of the block, because either puts the receiver
     * back in scope where a caller can reach it with no lock at all.
     */
    private inner class TreeEdit {
        suspend fun run(command: String) {
            val failure = attempt(command)
            check(failure == null) { "sway rejected '$command': ${failure?.error}" }
        }

        /** Runs [command], returning sway's complaint if it rejected it. */
        private suspend fun attempt(command: String): CommandResult? {
            val raw = commands.request(I3Ipc.Request.RUN_COMMAND, command)
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
         * the adoption route `attach` and `detach` already had, and removed by construction under
         * [DockIdentity.PER_SURFACE_APP_ID].
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
                        it.id !in pending.standing
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
        ): Node? = pollTree(timeoutMs) {
            tree().windows.firstOrNull { it.appId == appId && it.id !in standing }
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
     * Both flags are re-read on every iteration rather than captured, because `:config` reloads
     * against a live daemon and a wait can outlive the snapshot it started under. [timeoutMs] is
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

    suspend fun tree(): Node =
        swayJson.decodeFromString(commands.request(I3Ipc.Request.GET_TREE))

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

        // Bookkeeping, not compensation, which is why it is a `finally` around the whole method
        // and is gated by no flag: a reservation left behind is invisible in `swaymsg -t get_tree`
        // and hides every window under the dock's app_id for the life of the process, and a
        // failed attach's table entry names a node nothing owns. Tree repair is a different job,
        // done under the lock, and is not here (#6).
        var reservation: DockReservation? = null
        var recorded: SurfaceId? = null
        var attached = false
        val suppression = cfg[WmFlags.dockFocusSuppression]
        try {
            val dockId = treeEdit {
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
                    pending = PendingDock(appId, standing)
                    run("exec $command")
                    val dockNode = awaitWindow(appId, standing)
                        ?: error("dock '$appId' never appeared; command was: $command")
                    val dockId = SurfaceId(dockNode.id)
                    spawned = dockId

                    // Before the mark, and this order is the fix: the mark is a round trip away
                    // and enumeration does not take this lock, so a reader landing in between
                    // would be handed the agent panel as a bindable surface.
                    docks.record(dockId, surface, DockOrigin.STOOD_UP)
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
                    dockId
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
            return SwayDockHandle(surface, bound.agent, dockId, key)
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
        val job = scope.launch {
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
                // orphan. Read per event rather than captured once, because `:config` reloads
                // against a live daemon and a collector outlives any snapshot it took at startup.
                if (change is SurfaceChange.Vanished && config[WmFlags.sweepOnClose]) sweep()
            }
        } catch (ended: CompositorSessionEnded) {
            // The session boundary, mechanically: connection loss *is* the boundary, and #20 is
            // what makes it distinguishable from an idle desktop. Discard before reporting, so
            // that a reader who sees `sessionEnded` set can rely on the table already being empty.
            docks.discard()
            repairState.update { it.copy(sessionEnded = ended) }
        } catch (cancelled: CancellationException) {
            // The caller withdrawing the lifetime it granted, which is not a failure and must not
            // be recorded as one — nor swallowed, or this job would complete rather than cancel.
            throw cancelled
        } catch (failure: Exception) {
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
     * **This job completing is normal, and there is no `close()` to retire it early.** It ends when
     * the session ends, when a sweep raises under [SweepFailure.STOP], or on any other failure
     * under [CollectorFailure.REPORT] — and in every one of those cases [repairs] says which. The
     * only lever a caller has for stopping a collector before then is cancelling the scope it gave,
     * which retires everything else on that scope too.
     *
     * **So a caller that will ever replace a manager must give each one a scope of its own** — a
     * child of the caller's, cancelled as the replacement is built, rather than one scope shared by
     * both. Skipping that leaves the outgoing manager subscribed and sweeping, and the two sweeps
     * do *not* agree. Restarting a manager is reconnection (#33), and that is where this stops
     * being a rule about tests.
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
     * Whether the product should *prevent* overlap rather than requiring a caller to retire — a
     * `close()` that unsubscribes and abandons the table, a sweep reading durable state instead of
     * the asking manager's table, or overlap declared unsupported and enforced — is open in #72 and
     * is deliberately not settled here. **Whoever settles it should know the blast radius is the
     * default configuration**, not an opt-in: every argument for leaving overlap to the caller has
     * to hold for a desktop that has changed no flags at all.
     */
    internal val repairing: Job = scope.launch { collectRepairs() }

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
        val root = tree()
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
                SwayDockHandle(SurfaceId(boundTo), AgentId(""), SurfaceId(node.id), key = null)
                    .detach()
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
    ) : DockHandle {
        override suspend fun focus() = treeEdit { focus(dockId) }

        override suspend fun settleFocus() = treeEdit { settleFocus(surface, dockId) }

        override suspend fun detach() {
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
            if (cfg[WmFlags.forgetBindingOnDetach] && key != null) registry.unbind(key)
        }

        override fun close() {
            scope.launch { detach() }
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
    }
}
