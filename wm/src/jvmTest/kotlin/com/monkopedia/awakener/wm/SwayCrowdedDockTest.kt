package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Several docks on one surface, each hidden and shown (#261).
 *
 * sway 1.12's `resize set width` on a tiled window takes the growth from **every** sibling in an
 * equal share, and does nothing — while reporting success — when any one of them would end
 * narrower than 100 px. With three or four docks in a split the oldest is squeezed towards that
 * floor, so the restore's resize is refused and the dock stays at the share `floating disable`
 * gave it: 25% of a split of four, 20% of a split of five. The show's width readback took that
 * for a misplaced dock and threw.
 *
 * Each case here is a cell of the map in #261's PR that threw on `9e80694`, or one the issue
 * asked to be checked. Exercised against **sway 1.12** on the headless wlroots backend,
 * `workspace_layout tabbed` as on adolin, at scale 1 here and at scale 1.5 by
 * [SwayCrowdedDockScaledTest].
 */
open class SwayCrowdedDockTest {
    /** Extra sway configuration for the session, which is how a subclass sets the output scale. */
    protected open val outputConfig: String = ""

    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var stateDir: Path
    private lateinit var registry: FileBindingStore

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start("workspace_layout tabbed\n$outputConfig")
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-crowded")
        registry = FileBindingStore(
            configStore = store,
            identities = { key, residuePath -> DerivedAgentIdentities(store).mint(key, residuePath) },
            path = stateDir.resolve("bindings.json"),
        )
        wm = SwayWindowManager({ sway.connection() }, store, registry, scope)
    }

    @AfterTest
    fun tearDown() {
        if (!SwayHarness.available()) return
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    /** The issue's sequence: three docks, a window moved in, the first shown, then the third. */
    @Test
    fun `three docks and a user window show the first and then the third`() =
        crowded(docks = 3, userWindow = true, shown = listOf(0, 2))

    @Test
    fun `two docks and a user window show each in turn`() =
        crowded(docks = 2, userWindow = true, shown = listOf(0, 1))

    @Test
    fun `three docks show each in turn`() = crowded(docks = 3, userWindow = false, shown = listOf(0, 2, 1))

    @Test
    fun `four docks show the second`() = crowded(docks = 4, userWindow = false, shown = listOf(1))

    @Test
    fun `four docks show the third`() = crowded(docks = 4, userWindow = false, shown = listOf(2))

    @Test
    fun `four docks and a user window show each in turn`() =
        crowded(docks = 4, userWindow = true, shown = listOf(0, 1, 2, 3))

    @Test
    fun `four docks and a user window show the third`() =
        crowded(docks = 4, userWindow = true, shown = listOf(2))

    @Test
    fun `four docks and a user window show the fourth`() =
        crowded(docks = 4, userWindow = true, shown = listOf(3))

    /**
     * Under wm.dock.crowded_width=FROM_SURFACE the refused width is taken from the surface, so
     * every attach and every show ends at the configured 30% — the cells the default leaves at
     * 25% and 20%, including the attach of a fourth dock beside a user's window. One show per
     * run: each one the surface pays for narrows it, and a second in a split of five finds it
     * unable to spare the width and leaves the dock as the default would, which is the flag's
     * stated limit rather than something to assert on here.
     */
    @Test
    fun `under FROM_SURFACE three docks and a user window are each at their width`() {
        store.put(WmFlags.crowdedWidth, CrowdedWidth.FROM_SURFACE)
        crowded(docks = 3, userWindow = true, shown = listOf(0, 2), exact = true)
    }

    @Test
    fun `under FROM_SURFACE four docks and a user window are each at their width`() {
        store.put(WmFlags.crowdedWidth, CrowdedWidth.FROM_SURFACE)
        crowded(docks = 4, userWindow = true, shown = listOf(2), exact = true)
    }

    @Test
    fun `under FROM_SURFACE four docks are each at their width`() {
        store.put(WmFlags.crowdedWidth, CrowdedWidth.FROM_SURFACE)
        crowded(docks = 4, userWindow = false, shown = listOf(1), exact = true)
    }

    /**
     * The readback still fires on a dock at the wrong width in a split sway *would* have
     * resized. The tree read that follows the restore is held at the valve while another client
     * narrows the dock to 15%: one dock beside its surface, nowhere near the floor, so
     * [refusedResize] has no excuse to make for it and the show must fail naming the width.
     */
    @Test
    fun `a dock narrowed after its restore in an uncrowded split fails the readback`() =
        misplacedDuringReadback { dock -> command("[con_id=${dock.dockId.raw}] resize set width 15 ppt") }

    /**
     * #263's review: the excuse is for the surface's own split only. A dock another client moves
     * into a crowded split on another workspace during the readback is misplaced, and sway
     * refusing it 30% there must not pass it. Head `36b8fd9` returned true here, with the dock
     * left on workspace 2.
     */
    @Test
    fun `a dock moved into a crowded split elsewhere after its restore fails the readback`() {
        var narrow = SurfaceId(0)
        misplacedDuringReadback(
            prepare = {
                command("workspace 2")
                narrow = openSurface("aw-narrow")
                command("[con_id=${narrow.raw}] layout splith")
                openSurface("aw-wide1")
                openSurface("aw-wide2")
                command("[con_id=${narrow.raw}] resize set width $NARROW_PX px")
                command("workspace 1")
            },
        ) { dock ->
            command(
                "[con_id=${narrow.raw}] mark --add _test_foreign; " +
                    "[con_id=${dock.dockId.raw}] move window to mark _test_foreign; unmark _test_foreign",
            )
            val tree = wm.tree()
            assertNotNull(
                tree.refusedResize(dock.dockId.raw, 30),
                "the foreign split is one sway refuses the dock 30% in, or this tests nothing: ${describe(tree.workspace("2"))}",
            )
        }
    }

    /**
     * One dock beside its surface, hidden, and shown with the first tree read after the restore
     * held at the valve while [misplace] puts the dock wrong. The show must fail on its width.
     */
    private fun misplacedDuringReadback(
        prepare: suspend () -> Unit = {},
        misplace: suspend (DockHandle) -> Unit,
    ) = swayTest {
        store.put(WmFlags.unmapWaitMs, MISPLACED_WAIT_MS)
        prepare()
        SwayValve.open(sway.socket).use { valve ->
            val valved = valvedCommands(valve)
            val app = openSurface("aw-app")
            val dock = valved.attach(app, dockSpec(), AGENT)
            assertEquals(true, dock.hide(), "the hide")
            var restored = false
            valve.holdNext { type, payload ->
                if (type == I3Ipc.Request.RUN_COMMAND && "scratchpad show" in payload) restored = true
                restored && type == I3Ipc.Request.GET_TREE
            }
            val show = scope.async { runCatching { dock.show() } }
            valve.awaitHeld(WAIT_MS)
            misplace(dock)
            valve.release()
            val failure = assertNotNull(show.await().exceptionOrNull(), "the show must fail: ${describe()}")
            assertContains(assertNotNull(failure.message), "rather than the configured 30%")
        }
    }

    /**
     * Stands [docks] docks on one surface — a user's window moved into the split after the first,
     * as #260's review did, when [userWindow] — then hides and shows each of [shown] in order.
     * Every show must succeed and leave its dock tiled in the surface's own split.
     */
    private fun crowded(docks: Int, userWindow: Boolean, shown: List<Int>, exact: Boolean = false) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val mine = if (userWindow) openSurface("aw-mine") else null
        val handles = mutableListOf(wm.attach(app, dockSpec(), AGENT))
        if (mine != null) moveBeside(mine, app)
        repeat(docks - 1) {
            handles += wm.attach(app, dockSpec(), AGENT)
            if (exact) assertAtWidth(handles.last(), "attach ${handles.size} of $docks")
        }
        for (index in shown) {
            val dock = handles[index]
            val stage = "dock ${index + 1} of $docks"
            assertEquals(true, dock.hide(), "$stage: the hide")
            assertEquals(true, dock.show(), "$stage: the show")
            val tree = wm.tree()
            val node = assertNotNull(tree.find(dock.dockId.raw), "$stage: in the tree")
            assertNotEquals("floating_con", node.type, "$stage: tiled: ${describe()}")
            assertEquals(tree.parentOf(app.raw)?.id, tree.parentOf(node.id)?.id, "$stage: beside the surface: ${describe()}")
            if (exact) assertAtWidth(dock, stage)
        }
    }

    private suspend fun assertAtWidth(dock: DockHandle, stage: String) {
        val percent = assertNotNull(wm.tree().find(dock.dockId.raw)?.percent, "$stage: a width fraction")
        assertEquals(0.30, percent, 0.005, "$stage: at the configured width: ${describe()}")
    }

    /**
     * A manager whose **commands** go through [valve] and whose event subscription does not, for
     * `SwayLifecycleTest.valvedCommands`'s reason: the valve relays strictly one reply per
     * request, and the subscription is the first connection a manager opens.
     */
    private suspend fun valvedCommands(valve: SwayValve): SwayWindowManager {
        val connects = AtomicInteger(0)
        val manager = SwayWindowManager(
            {
                if (connects.getAndIncrement() == 0) sway.connection()
                else SwayConnection.open(valve.socket.absolutePathString())
            },
            store,
            registry,
            scope,
        )
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (connects.get() == 0) delay(POLL_MS)
                true
            },
            "the collector never opened its subscription",
        )
        return manager
    }

    // -- helpers ------------------------------------------------------------------------

    private fun swayTest(body: suspend CoroutineScope.() -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking { body() }
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        swayJson.decodeFromString<List<CommandResult>>(raw)
            .firstOrNull { !it.success }
            ?.let { error("sway rejected '$text': ${it.error}") }
    }

    /** Moves [window] beside [app], into whatever holds it — the dock's split, while docked. */
    private suspend fun moveBeside(window: SurfaceId, app: SurfaceId) = command(
        "[con_id=${app.raw}] mark --add _test_beside; " +
            "[con_id=${window.raw}] move window to mark _test_beside; unmark _test_beside",
    )

    private suspend fun openSurface(appId: String): SurfaceId {
        command("exec ${sway.windowCommand(appId)}")
        val id = withTimeoutOrNull(WAIT_MS) {
            while (true) {
                wm.tree().windows.firstOrNull { it.appId == appId }
                    ?.let { return@withTimeoutOrNull it.id }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        return SurfaceId(assertNotNull(id, "window '$appId' never appeared"))
    }

    private fun dockSpec() = DockSpec("aw-dock", sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    /** Workspace 1 as layouts, app_ids and width fractions, so a failure shows the shape. */
    private suspend fun describe(): String = describe(assertNotNull(wm.tree().workspace("1"), "workspace 1 exists"))

    private fun describe(workspace: Node?): String {
        fun describe(node: Node): String {
            val share = node.percent?.let { "@%.3f".format(it) }.orEmpty()
            return if (node.children.isEmpty()) {
                "${node.appId ?: node.id}$share"
            } else {
                "${node.layout}$share[${node.children.joinToString(" ") { describe(it) }}]"
            }
        }
        return workspace?.let { describe(it) } ?: "no such workspace"
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L

        /** Long enough for a restore to land, short enough that the expected failure is quick. */
        const val MISPLACED_WAIT_MS = 1_000L

        /** Narrow enough that any resize taking a share from it is refused once the dock joins. */
        const val NARROW_PX = 102
        val AGENT = AgentId("agent-1")
    }
}
