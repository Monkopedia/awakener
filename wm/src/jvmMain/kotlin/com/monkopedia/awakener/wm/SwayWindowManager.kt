package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.asIdentity
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
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
    private val scope: CoroutineScope,
) : WindowManager {
    private val commands: SwayConnection by lazy { connect() }

    private val config: Config get() = store.config.value

    private val treeEditLock = Mutex()

    /**
     * The docks this process knows about. See [DockTable]; it is read on every enumeration, and
     * written by [attach], by a teardown, and by [dockedTo] adopting a dock it found by mark.
     */
    private val docks = DockTable()

    private val unparsedMarks = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Marks under [WmFlags.dockMarkPrefix] whose suffix is not a `con_id`, as seen by every
     * enumeration and every sweep this instance has run.
     *
     * Such a mark is a user's own — sway's mark namespace is one global, user-facing set — so the
     * window keeps being enumerated and is named here instead of being silently hidden. Names
     * accumulate for the lifetime of this instance and are never pruned, so a mark that has since
     * been removed is still listed.
     */
    val unrecognisedDockMarks: StateFlow<Set<String>> = unparsedMarks.asStateFlow()

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
     * answered: sway moves a dock mark to the next dock attached to the same surface (#14), so a
     * union computed afresh on every read has nothing left once it does. Measured, two managers
     * against one sway: attach, restart awakener, enumerate — correct either way — then attach a
     * second dock to that surface, and a non-recording union hands the first agent panel back as
     * a bindable surface. That is this note's expensive false negative arriving through the
     * mechanism built to prevent it, and it is worse than the original: the panel is invisible to
     * [reapOrphans] for the same reason, so nothing can take it down again.
     *
     * The mark is therefore read *before* the table is consulted rather than after. That costs a
     * list scan on a hit and buys two things: an adopted entry, and a user's mark under the
     * prefix being reported on a node that is already a known dock, which a table-first order
     * made depend on when in a dock's life it was looked at.
     *
     * Writing on a read path is deliberate and is not a lock: [DockTable] is a compare-and-set
     * over an immutable snapshot, the same one [unparsedMarks] already does two lines up. A
     * concurrent `attach` that has just evicted a failed dock's entry can be immediately followed
     * here by an adoption of that same node — correctly, since it is a node still wearing the
     * mark, which is exactly what adoption is for.
     *
     * Reports rather than hides a mark under the prefix that does not parse: that is a user's
     * mark on a genuine window, and treating it as a dock is what made such a window unreachable
     * by every code path at once (#15).
     */
    private fun dockedTo(node: Node, table: DockTableSnapshot, cfg: Config): SurfaceId? {
        val reading = node.dockMark(cfg[WmFlags.dockMarkPrefix])
        if (reading.unparsed.isNotEmpty()) unparsedMarks.update { it + reading.unparsed }
        if (!cfg.consultsTable) return reading.surface
        table.entries[node.id]?.let { return it }
        val adopted = reading.surface ?: return null
        docks.record(SurfaceId(node.id), adopted)
        return adopted
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
         * after [WINDOW_WAIT_MS].
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
         * [WmFlags.eventsEnabled]; attaching a dock has to keep working with events off.
         */
        suspend fun awaitWindow(
            appId: String,
            standing: Set<Long>,
            timeoutMs: Long = WINDOW_WAIT_MS,
        ): Node? = withTimeoutOrNull(timeoutMs) {
            while (true) {
                tree().windows.firstOrNull { it.appId == appId && it.id !in standing }
                    ?.let { return@withTimeoutOrNull it }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

        suspend fun awaitGone(id: SurfaceId, timeoutMs: Long = WINDOW_WAIT_MS): Boolean =
            withTimeoutOrNull(timeoutMs) {
                while (tree().find(id.raw) != null) yield()
                true
            } ?: false
    }

    suspend fun tree(): Node =
        swayJson.decodeFromString(commands.request(I3Ipc.Request.GET_TREE))

    /**
     * The durable key for a live window, or null if the window has gone.
     *
     * The whole translation from compositor handle to durable identity happens here and nowhere
     * else, which is what keeps `:registry` from ever learning what a `con_id` is.
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
            .map { Surface(SurfaceId(it.id), it.appId, it.name, it.pid) }
    }

    override suspend fun resolve(surface: SurfaceId): AgentId? =
        keyFor(surface)?.let { registry.resolve(it)?.agent }

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
        try {
            val dockId = treeEdit {
                // Focus first: sway's split applies to the focused container, and the dock has to
                // land inside this surface's tab rather than wherever focus happened to be.
                focus(surface)
                run("split horizontal")

                // Must precede the exec — sway evaluates focus rules when the window maps, so
                // issuing this afterwards would be too late to prevent the steal.
                if (!cfg[WmFlags.dockFocusOnMap]) {
                    run("""no_focus [app_id="$appId"]""")
                }

                // Taken after the no_focus rule and before the exec, so it is exactly the set of
                // docks that were already standing. Matching the spawned dock on app_id alone
                // would resolve to whichever of them sway happens to list first, since in
                // production every dock is the same panel program and they all report the same
                // name. The snapshot only identifies anything because nothing else can exec
                // before the claim.
                val standing = tree().windows.filter { it.appId == appId }.map { it.id }.toSet()

                // Filed before the window it describes can exist, which is the whole of it: a
                // con_id is minted when the dock maps, so nothing keyed on one can cover the dock
                // at the moment it becomes visible to a reader of the tree.
                if (cfg[WmFlags.dockPendingSuppression]) {
                    reservation = docks.reserve(appId, standing, WINDOW_WAIT_MS.milliseconds)
                }
                run("exec $command")
                val dockNode = awaitWindow(appId, standing)
                    ?: error("dock '$appId' never appeared; command was: $command")
                val dockId = SurfaceId(dockNode.id)

                // Before the mark, and this order is the fix: the mark is a round trip away and
                // enumeration does not take this lock, so a reader landing in between would be
                // handed the agent panel as a bindable surface.
                docks.record(dockId, surface)
                recorded = dockId

                val mark = "${cfg[WmFlags.dockMarkPrefix]}${surface.raw}"
                run("[con_id=${dockId.raw}] mark --add $mark")
                if (cfg[WmFlags.dockSide] == DockSide.LEFT) {
                    run("[con_id=${dockId.raw}] move left")
                }
                run("[con_id=${dockId.raw}] resize set width ${cfg[WmFlags.dockSizePpt]} ppt")
                if (cfg[WmFlags.restoreFocusAfterAttach]) settleFocus(surface, dockId)
                dockId
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
            val bound = registry.bind(key, agent?.asIdentity())
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
                                Surface(id, container.appId, container.name, container.pid),
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
     * Applies [OrphanPolicy] to any dock whose surface is gone.
     *
     * Driven by the caller off [changes] rather than run on a timer, since sway emits `close`
     * for the surface and that is the exact moment the dock becomes an orphan.
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
     * Which nodes are docks is [dockedTo]'s union, the same one enumeration answers from, so a
     * dock whose mark a later attach took off it (#14) is still reaped for as long as this
     * process remembers it — which, since recognising a dock by its mark records it, covers a
     * dock adopted after a restart and not only one this process stood up. A dock an attach has
     * reserved but not yet identified is
     * not swept: nothing knows yet which surface it belongs to, so nothing can know it is an
     * orphan.
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
                // `split none` refusal arrives anonymous. reapOrphans has no production caller
                // yet, so this message is the whole of what whoever wires it up gets to work
                // from.
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
                            "dock ${dockId.raw} was still in the tree ${WINDOW_WAIT_MS}ms after " +
                                "it was killed; its client is not servicing the close request",
                        )
                    }
                    return@treeEdit
                }
                // Only once the node has actually left the tree: a dock that outlived its kill is
                // still a dock, and forgetting it here would hand the wedged panel back to
                // enumeration as a bindable surface.
                docks.forget(dockId)

                if (!cfg[WmFlags.normalizeContainerOnDetach]) return@treeEdit
                // sway does not collapse a split container back down when it drops to one child,
                // and the leftover container silently adopts the next window opened in that tab.
                val survivor =
                    parent?.children?.firstOrNull { it.id != dockId.raw } ?: return@treeEdit
                if (tree().find(survivor.id) != null) {
                    focus(SurfaceId(survivor.id))
                    run("split none")
                }
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
        const val WINDOW_WAIT_MS = 5_000L
    }
}
