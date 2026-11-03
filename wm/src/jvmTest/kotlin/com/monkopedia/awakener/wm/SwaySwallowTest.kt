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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * A window opened in a docked tab must not be swallowed by the split `attach` made (#187).
 *
 * The session is configured exactly as #187's reproduction is — `workspace_layout tabbed` and
 * nothing else of note — so the tab strip is a `tabbed` container under the workspace rather than
 * the workspace itself, which is the shape adolin's tree has and the one in which a flattened
 * surface's parent is still a `con`. sway places a new window beside the focused one, inside that
 * window's parent; a split left holding only the surface is therefore a container the next window
 * walks into, and with the dock hidden nothing on screen says it is there.
 *
 * Exercised against **sway 1.12** on the headless wlroots backend.
 */
class SwaySwallowTest {
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
        stateDir = createTempDirectory("awakener-wm-swallow")
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

    /**
     * #187 as it happens through awakener: dock a surface, hide the dock, open something.
     *
     * Before the fix the hide left `app` alone in its `splith`, and the intruder landed in it —
     * two unrelated programs sharing one tab, which is what adolin showed with `handy`.
     */
    @Test
    fun `a window opened while the dock is hidden gets a tab of its own`() = swayTest {
        val other = openSurface("aw-other")
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        dock.hide()

        val intruder = openSurface("aw-intruder")

        val strip = tabStrip()
        assertEquals(
            listOf(other.raw, app.raw, intruder.raw),
            strip.nodes.map { it.id },
            "every window is a tab of its own; a hidden dock's split adopting the newcomer is " +
                "#187. Tree under the strip: ${describe(strip)}",
        )
        assertFalse(inTab(app, intruder), "the newcomer is not sharing the surface's tab")
    }

    /** The other half: a show has to rebuild the split the hide took down, not open a tab. */
    @Test
    fun `a flattened hide still shows the dock beside its own surface`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        dock.hide()
        val intruder = openSurface("aw-intruder")

        assertTrue(dock.show(), "the show is what changed things")

        val tab = assertNotNull(tabHolding(app))
        assertEquals("splith", tab.layout, "the surface is split again. ${describe(tabStrip())}")
        assertEquals(
            listOf(app.raw, dock.dockId.raw),
            tab.nodes.map { it.id },
            "holding the surface and its dock and nothing else",
        )
        assertFalse(inTab(app, intruder), "the window opened meanwhile kept its own tab")
    }

    /**
     * What a press-hide-press user does all day, which #187's "Not verified" asks about: a chain
     * of single-child containers would show here as a split count that grows with the cycles.
     */
    @Test
    fun `hide and show cycles leave no container behind`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val docked = describe(tabStrip())

        repeat(CYCLES) {
            dock.hide()
            assertEquals(
                app.raw,
                assertNotNull(tabHolding(app)).id,
                "hidden, the surface is a bare tab. ${describe(tabStrip())}",
            )
            dock.show()
            assertEquals(docked, describe(tabStrip()), "shown, the tab is exactly as attach left it")
        }
        assertEquals(0, singleChildSplits(), "and no single-child split survives anywhere")
    }

    /**
     * A detach of a flattened hidden dock has no container to collapse, and must not go looking
     * for one: the surface's parent is now the tab strip, and `split none` on a tab with siblings
     * is refused.
     */
    @Test
    fun `detaching a flattened hidden dock leaves the tab strip alone`() = swayTest {
        val other = openSurface("aw-other")
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        dock.hide()

        dock.detach()

        val strip = tabStrip()
        assertEquals("tabbed", strip.layout)
        assertEquals(listOf(other.raw, app.raw), strip.nodes.map { it.id }, describe(strip))
    }

    /** The flag is the switch back to the old shape, and the old shape is the swallow. */
    @Test
    fun `with flatten_on_hide off the hidden tab keeps its split`() = swayTest {
        store.put(WmFlags.flattenOnHide, false)
        openSurface("aw-other")
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        dock.hide()

        val tab = assertNotNull(tabHolding(app))
        assertEquals("splith", tab.layout, "the split stands with the dock away")
        assertEquals(listOf(app.raw), tab.nodes.map { it.id })

        assertTrue(dock.show())
        assertEquals(listOf(app.raw, dock.dockId.raw), assertNotNull(tabHolding(app)).nodes.map { it.id })
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

    private suspend fun inTab(surface: SurfaceId, node: SurfaceId): Boolean =
        tabHolding(surface)?.find(node.raw) != null

    private suspend fun singleChildSplits(): Int {
        fun count(node: Node): Int =
            (if (node.type == "con" && node.layout?.startsWith("split") == true && node.nodes.size == 1) 1 else 0) +
                node.children.sumOf { count(it) }
        return count(assertNotNull(wm.tree().workspace("1")))
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
        const val CYCLES = 5
    }
}
