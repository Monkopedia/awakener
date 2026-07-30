package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
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
    private val scope: CoroutineScope,
) : WindowManager {
    private val commands: SwayConnection by lazy { connect() }
    private val bindings = mutableMapOf<SurfaceId, AgentId>()

    private val config: Config get() = store.config.value

    suspend fun tree(): Node =
        swayJson.decodeFromString(commands.request(I3Ipc.Request.GET_TREE))

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

    override suspend fun resolve(surface: SurfaceId): AgentId? = bindings[surface]

    override suspend fun attach(
        surface: SurfaceId,
        agent: AgentId,
        dock: DockSpec,
    ): DockHandle {
        val cfg = config
        check(tree().find(surface.raw) != null) { "no such surface: ${surface.raw}" }

        // Focus first: sway's split applies to the focused container, and the dock has to land
        // inside this surface's tab rather than wherever focus happened to be.
        run("[con_id=${surface.raw}] focus")
        run("split horizontal")

        // Must precede the exec — sway evaluates focus rules when the window maps, so issuing
        // this afterwards would be too late to prevent the steal.
        if (!cfg[WmFlags.dockFocusOnMap]) {
            run("""no_focus [app_id="${dock.appId}"]""")
        }

        run("exec ${dock.command}")
        val dockNode = awaitWindow(dock.appId)
            ?: error("dock '${dock.appId}' never appeared; command was: ${dock.command}")
        val dockId = SurfaceId(dockNode.id)

        run("""[con_id=${dockId.raw}] mark --add ${cfg[WmFlags.dockMarkPrefix]}${surface.raw}""")
        if (cfg[WmFlags.dockSide] == DockSide.LEFT) {
            run("[con_id=${dockId.raw}] move left")
        }
        run("[con_id=${dockId.raw}] resize set width ${cfg[WmFlags.dockSizePpt]} ppt")

        bindings[surface] = agent
        val handle = SwayDockHandle(surface, agent, dockId)
        if (cfg[WmFlags.restoreFocusAfterAttach]) handle.settleFocus()
        return handle
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
                SwayDockHandle(SurfaceId(boundTo), AgentId(""), SurfaceId(node.id)).detach()
            }
        }
    }

    private suspend fun run(command: String) {
        val raw = commands.request(I3Ipc.Request.RUN_COMMAND, command)
        val results = swayJson.decodeFromString<List<CommandResult>>(raw)
        val failure = results.firstOrNull { !it.success }
        check(failure == null) { "sway rejected '$command': ${failure?.error}" }
    }

    /**
     * Waits for a window with [appId] to appear.
     *
     * Polls rather than listening for the `new` event so that [attach] does not depend on
     * [WmFlags.eventsEnabled]; attaching a dock has to keep working with events off.
     */
    private suspend fun awaitWindow(appId: String, timeoutMs: Long = WINDOW_WAIT_MS): Node? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                tree().windows.firstOrNull { it.appId == appId }?.let { return@withTimeoutOrNull it }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    private inner class SwayDockHandle(
        override val surface: SurfaceId,
        override val agent: AgentId,
        override val dockId: SurfaceId,
    ) : DockHandle {
        override suspend fun focus() = run("[con_id=${dockId.raw}] focus")

        /**
         * Leaves the tab focused on whichever child the resting-focus flag names.
         *
         * This is the fix for the sharpest hazard the probe found: sway remembers the last
         * focused child per container, so a tab left resting on the dock means the *next*
         * switch into that tab puts the user's keystrokes into the agent panel instead of the
         * application.
         */
        override suspend fun settleFocus() {
            val target = when (config[WmFlags.restingFocus]) {
                RestingFocus.APP -> surface
                RestingFocus.DOCK -> dockId
            }
            if (tree().find(target.raw) != null) run("[con_id=${target.raw}] focus")
        }

        override suspend fun detach() {
            val cfg = config
            val root = tree()
            val parent = root.parentOf(dockId.raw)
            if (root.find(dockId.raw) != null) run("[con_id=${dockId.raw}] kill")
            bindings.remove(surface)

            if (!cfg[WmFlags.normalizeContainerOnDetach]) return
            // sway does not collapse a split container back down when it drops to one child,
            // and the leftover container silently adopts the next window opened in that tab.
            val survivor = parent?.children?.firstOrNull { it.id != dockId.raw } ?: return
            if (awaitGone(dockId) && tree().find(survivor.id) != null) {
                run("[con_id=${survivor.id}] focus")
                run("split none")
            }
        }

        override fun close() {
            scope.launch { detach() }
        }
    }

    private suspend fun awaitGone(id: SurfaceId, timeoutMs: Long = WINDOW_WAIT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (tree().find(id.raw) != null) yield()
            true
        } ?: false

    private companion object {
        const val WINDOW_WAIT_MS = 5_000L
    }
}
