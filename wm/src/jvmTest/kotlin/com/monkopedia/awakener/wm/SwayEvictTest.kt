package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * A window opened while the dock is *visible* is moved out of the dock's split on the next hide,
 * under `wm.dock.evict_on_hide` (#187, the half #203 left open).
 *
 * sway places a new window beside the focused one inside that window's parent, and with the dock
 * up the focused window is the surface or the dock — both inside the split attach made. So the
 * newcomer shares their tab, and nothing short of moving it gives it a tab back. Configured as
 * [SwaySwallowTest] is, `workspace_layout tabbed`, which is #187's reproduction and adolin's shape.
 *
 * Exercised against **sway 1.12** on the headless wlroots backend.
 */
class SwayEvictTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var stateDir: Path

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start("workspace_layout tabbed")
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-evict")
        val registry = FileBindingStore(
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

    /** The precondition every test here stands on, asserted so a sway that changed it says so. */
    private suspend fun dockedWithIntruders(vararg intruders: String): Triple<SurfaceId, DockHandle, List<SurfaceId>> {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val opened = intruders.map { appId ->
            command("[con_id=${app.raw}] focus")
            openSurface(appId)
        }
        val tab = assertNotNull(tabHolding(app))
        for (intruder in opened) {
            assertTrue(tab.nodes.any { it.id == intruder.raw }, "sway put it in the dock's split: ${describe(tab)}")
        }
        command("[con_id=${app.raw}] focus")
        return Triple(app, dock, opened)
    }

    /**
     * The red test. Before the flag existed nothing moved the newcomer, and the hide left it in
     * the split beside the surface — so it also kept `flatten_on_hide` from collapsing that split.
     */
    @Test
    fun `with evict_on_hide on a hide gives the intruder its own tab`() = swayTest {
        store.put(WmFlags.evictOnHide, true)
        val other = openSurface("aw-other")
        val (app, dock, intruders) = dockedWithIntruders("aw-intruder")

        assertTrue(dock.hide())

        val strip = tabStrip()
        assertEquals(
            listOf(other.raw, app.raw, intruders.single().raw),
            strip.nodes.map { it.id },
            "the intruder is a tab of its own, directly after the surface's, and the surface's " +
                "split collapsed under flatten_on_hide. ${describe(strip)}",
        )
        assertEquals(app.raw, focusedId(), "focus stays on the surface the press was made from")
    }

    @Test
    fun `several intruders keep their order and the show still docks beside the surface`() = swayTest {
        store.put(WmFlags.evictOnHide, true)
        val other = openSurface("aw-other")
        val (app, dock, intruders) = dockedWithIntruders("aw-in1", "aw-in2")
        val before = assertNotNull(tabHolding(app)).nodes.map { it.id }.filter { it in intruders.map(SurfaceId::raw) }

        assertTrue(dock.hide())
        assertEquals(
            listOf(other.raw, app.raw) + before,
            tabStrip().nodes.map { it.id },
            "each intruder is a tab, in the order it had in the split. ${describe(tabStrip())}",
        )

        assertTrue(dock.show())
        assertEquals(
            listOf(app.raw, dock.dockId.raw),
            assertNotNull(tabHolding(app)).nodes.map { it.id },
            "the shown dock is beside its own surface and nothing else. ${describe(tabStrip())}",
        )
    }

    /** The two flags are independent: eviction without the flatten leaves the split standing, emptied. */
    @Test
    fun `with flatten_on_hide off the evicted split keeps only the surface`() = swayTest {
        store.put(WmFlags.evictOnHide, true)
        store.put(WmFlags.flattenOnHide, false)
        openSurface("aw-other")
        val (app, dock, intruders) = dockedWithIntruders("aw-intruder")

        assertTrue(dock.hide())

        val tab = assertNotNull(tabHolding(app))
        assertEquals("splith", tab.layout, "the split stands, as flatten_on_hide=false asks")
        assertEquals(listOf(app.raw), tab.nodes.map { it.id }, describe(tabStrip()))
        assertTrue(tabStrip().nodes.any { it.id == intruders.single().raw }, "and the intruder is a tab")
    }

    /** The default: today's behaviour, the newcomer left exactly where sway put it. */
    @Test
    fun `with evict_on_hide off the intruder is left in the split`() = swayTest {
        openSurface("aw-other")
        val (app, dock, intruders) = dockedWithIntruders("aw-intruder")

        assertTrue(dock.hide())

        val tab = assertNotNull(tabHolding(app))
        assertEquals(
            listOf(app.raw, intruders.single().raw),
            tab.nodes.map { it.id },
            "nothing moved it, and the split it holds could not be collapsed. ${describe(tabStrip())}",
        )
    }

    /** A dock split a user toggled to `splitv` runs across the strip; one step takes a window out. */
    @Test
    fun `a vertical dock split is evicted too`() = swayTest {
        store.put(WmFlags.evictOnHide, true)
        val other = openSurface("aw-other")
        val (app, dock, intruders) = dockedWithIntruders("aw-intruder")
        command("[con_id=${app.raw}] focus; layout splitv")
        assertEquals("splitv", assertNotNull(tabHolding(app)).layout)

        assertTrue(dock.hide())

        assertEquals(
            listOf(other.raw, app.raw, intruders.single().raw),
            tabStrip().nodes.map { it.id },
            describe(tabStrip()),
        )
    }

    /**
     * sway 1.12 moves a window leaving a split by its edge *into* the neighbour on that side when
     * the neighbour is a container. A user's split tab after the dock's must not take the intruder
     * in, so it leaves by the other side and lands before the dock's split instead.
     */
    @Test
    fun `a split tab after the dock's is not entered`() = swayTest {
        store.put(WmFlags.evictOnHide, true)
        val app = openSurface("aw-app1")
        val (x, y) = userSplitTab("aw-x", "aw-y")
        val (dock, intruder) = dockWithIntruder(app)
        assertEquals(
            listOf(app.raw, x.raw),
            tabStrip().nodes.map { it.nodes.firstOrNull()?.id },
            "the dock's split is followed by the user's: ${describe(tabStrip())}",
        )

        assertTrue(dock.hide())

        val strip = tabStrip()
        assertEquals(
            listOf(intruder.raw, app.raw, null),
            strip.nodes.map { it.id.takeIf { _ -> it.nodes.isEmpty() } },
            "the intruder is a tab before the surface's, and the user's tab follows. ${describe(strip)}",
        )
        assertEquals(
            listOf(x.raw, y.raw),
            strip.nodes.last().nodes.map { it.id },
            "the user's split holds what it held. ${describe(strip)}",
        )
    }

    /** A single window after the dock's split is a clear side: the intruder lands between. */
    @Test
    fun `a single-window tab after the dock's keeps its place`() = swayTest {
        store.put(WmFlags.evictOnHide, true)
        val app = openSurface("aw-app1")
        val x = openSurface("aw-x")
        val (dock, intruder) = dockWithIntruder(app)

        assertTrue(dock.hide())

        assertEquals(
            listOf(app.raw, intruder.raw, x.raw),
            tabStrip().nodes.map { it.id },
            describe(tabStrip()),
        )
    }

    /** Containers on both sides: no exit is safe, so the press leaves the window where it is. */
    @Test
    fun `with split tabs on both sides nothing is moved`() = swayTest {
        store.put(WmFlags.evictOnHide, true)
        val (b1, b2) = userSplitTab("aw-b1", "aw-b2")
        command("[con_id=${b1.raw}] focus; focus parent")
        val app = openSurface("aw-app1")
        val (x, y) = userSplitTab("aw-x", "aw-y")
        val (dock, intruder) = dockWithIntruder(app)
        assertEquals(
            listOf(b1.raw, app.raw, x.raw),
            tabStrip().nodes.map { it.nodes.firstOrNull()?.id },
            "user split, dock split, user split: ${describe(tabStrip())}",
        )

        assertTrue(dock.hide())

        val strip = tabStrip()
        assertEquals(listOf(b1.raw, b2.raw), strip.nodes[0].nodes.map { it.id }, describe(strip))
        assertEquals(listOf(app.raw, intruder.raw), strip.nodes[1].nodes.map { it.id }, describe(strip))
        assertEquals(listOf(x.raw, y.raw), strip.nodes[2].nodes.map { it.id }, describe(strip))
    }

    /** Opens [first] and [second] as one `splitv` tab after whatever is focused. */
    private suspend fun userSplitTab(first: String, second: String): Pair<SurfaceId, SurfaceId> {
        val a = openSurface(first)
        command("[con_id=${a.raw}] focus; split vertical")
        val b = openSurface(second)
        return a to b
    }

    private suspend fun dockWithIntruder(app: SurfaceId): Pair<DockHandle, SurfaceId> {
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        command("[con_id=${app.raw}] focus")
        val intruder = openSurface("aw-intruder")
        assertTrue(
            assertNotNull(tabHolding(app)).nodes.any { it.id == intruder.raw },
            "sway put it in the dock's split: ${describe(tabStrip())}",
        )
        command("[con_id=${app.raw}] focus")
        return dock to intruder
    }

    // -- helpers ------------------------------------------------------------------------

    private fun swayTest(body: suspend () -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking { body() }
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        swayJson.decodeFromString<List<CommandResult>>(raw)
            .firstOrNull { !it.success }
            ?.let { error("sway rejected '$text': ${it.error}") }
    }

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

    private fun dockSpec(appId: String) =
        DockSpec(appId, sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    /** `workspace_layout tabbed` puts the tabs under one `tabbed` container on the workspace. */
    private suspend fun tabStrip(): Node {
        val workspace = assertNotNull(wm.tree().workspace("1"), "workspace 1 exists")
        return assertNotNull(
            workspace.nodes.singleOrNull { it.layout == "tabbed" },
            "workspace 1 holds one tab strip: ${describe(workspace)}",
        )
    }

    private suspend fun tabHolding(surface: SurfaceId): Node? =
        tabStrip().nodes.firstOrNull { it.find(surface.raw) != null }

    private suspend fun focusedId(): Long? {
        fun find(node: Node): Node? = if (node.focused) node else node.children.firstNotNullOfOrNull { find(it) }
        return find(wm.tree())?.id
    }

    /** The tree below [node] as layouts and ids, so a failed shape assertion shows the shape. */
    private fun describe(node: Node): String =
        if (node.children.isEmpty()) {
            "${node.appId ?: node.id}"
        } else {
            "${node.layout}[${node.children.joinToString(" ") { describe(it) }}]"
        }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
