package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.asIdentity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
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
    private val editor = TreeEdit()

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
     * Why a receiver and not a lock each caller remembers to take: [TreeEdit.run] is the only way
     * to issue a sway command anywhere in this class, and it is reachable only as this block's
     * receiver. Serialisation is therefore not a convention a newly added public method can
     * forget — to focus, split, mark, move or kill anything at all, it has to be in here. Held as
     * a convention it was forgotten three separate times, once per entry point that exists.
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
        treeEditLock.withLock { editor.edit() }

    /**
     * The only way to change the tree. See [treeEdit] for why it is a receiver.
     *
     * Everything here assumes the lock is held, which is exactly what being reachable only
     * through [treeEdit] guarantees.
     */
    private inner class TreeEdit {
        suspend fun run(command: String) {
            val raw = commands.request(I3Ipc.Request.RUN_COMMAND, command)
            val results = swayJson.decodeFromString<List<CommandResult>>(raw)
            val failure = results.firstOrNull { !it.success }
            check(failure == null) { "sway rejected '$command': ${failure?.error}" }
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
     * Every window that is not a dock. The dock mark is the discriminator, because a dock is
     * a genuine tree node and is otherwise indistinguishable from a surface needing an agent.
     */
    override suspend fun surfaces(): List<Surface> {
        val prefix = config[WmFlags.dockMarkPrefix]
        return tree().windows
            .filterNot { node -> node.marks.any { it.startsWith(prefix) } }
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

        val dockId = treeEdit {
            // Focus first: sway's split applies to the focused container, and the dock has to land
            // inside this surface's tab rather than wherever focus happened to be.
            focus(surface)
            run("split horizontal")

            // Must precede the exec — sway evaluates focus rules when the window maps, so issuing
            // this afterwards would be too late to prevent the steal.
            if (!cfg[WmFlags.dockFocusOnMap]) {
                run("""no_focus [app_id="$appId"]""")
            }

            // Taken after the no_focus rule and before the exec, so it is exactly the set of docks
            // that were already standing. Matching the spawned dock on app_id alone would resolve
            // to whichever of them sway happens to list first, since in production every dock is
            // the same panel program and they all report the same name. The snapshot only
            // identifies anything because nothing else can exec before the claim.
            val standing = tree().windows.filter { it.appId == appId }.map { it.id }.toSet()
            run("exec $command")
            val dockNode = awaitWindow(appId, standing)
                ?: error("dock '$appId' never appeared; command was: $command")
            val dockId = SurfaceId(dockNode.id)

            run("[con_id=${dockId.raw}] mark --add ${cfg[WmFlags.dockMarkPrefix]}${surface.raw}")
            if (cfg[WmFlags.dockSide] == DockSide.LEFT) {
                run("[con_id=${dockId.raw}] move left")
            }
            run("[con_id=${dockId.raw}] resize set width ${cfg[WmFlags.dockSizePpt]} ppt")
            if (cfg[WmFlags.restoreFocusAfterAttach]) settleFocus(surface, dockId)
            dockId
        }

        // Outside the section on purpose: this is not a tree edit, and in the hotkey case it mints,
        // which reaches a spanreed subprocess. Holding the compositor across a process spawn would
        // stall every other attach and detach behind it — the same call `FileBindingStore.bind`
        // makes one module down, for the same reason.
        //
        // Still recorded only once the dock is standing, so a failed attach leaves no durable
        // binding to an agent that has no panel. A null agent is the hotkey case: the registry
        // resolves the surface's existing Lifeless or mints one, which is the only moment an
        // identity is ever minted — a trigger on window creation would spawn an agent for every
        // window glanced at and closed.
        val bound = registry.bind(key, agent?.asIdentity())
        return SwayDockHandle(surface, bound.agent, dockId, key)
    }

    override val changes: Flow<SurfaceChange> = callbackFlow {
        if (!config[WmFlags.eventsEnabled]) {
            close()
            return@callbackFlow
        }
        val events = connect()
        val job = scope.launch {
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
     */
    suspend fun reapOrphans() {
        if (config[WmFlags.orphanPolicy] != OrphanPolicy.CLOSE) return
        val prefix = config[WmFlags.dockMarkPrefix]
        val root = tree()
        val live = root.windows.map { it.id }.toSet()
        root.windows.forEach { node ->
            val mark = node.marks.firstOrNull { it.startsWith(prefix) } ?: return@forEach
            val boundTo = mark.removePrefix(prefix).toLongOrNull() ?: return@forEach
            if (boundTo !in live) {
                // No key: the surface is already gone, so there is nothing left to derive one
                // from. Reaping a dock is a window-tree repair and never touches the registry.
                SwayDockHandle(SurfaceId(boundTo), AgentId(""), SurfaceId(node.id), key = null)
                    .detach()
            }
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
                val root = tree()
                val parent = root.parentOf(dockId.raw)
                if (root.find(dockId.raw) != null) run("[con_id=${dockId.raw}] kill")

                if (!cfg[WmFlags.normalizeContainerOnDetach]) return@treeEdit
                // sway does not collapse a split container back down when it drops to one child,
                // and the leftover container silently adopts the next window opened in that tab.
                val survivor =
                    parent?.children?.firstOrNull { it.id != dockId.raw } ?: return@treeEdit
                if (awaitGone(dockId) && tree().find(survivor.id) != null) {
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
