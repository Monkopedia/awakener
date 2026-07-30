package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Guards the three hazards found by the 2026-07-30 probe. Each one is a way the tree degrades
 * if `attach` does not own the split container it creates, and each was observed on sway 1.12
 * before any of this code existed — see `docs/findings/2026-07-30-sway-binding-probe.md`.
 */
class SwayBindingTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore

    private val enabled get() = SwayHarness.available()

    @BeforeTest
    fun setUp() {
        if (!enabled) return
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        wm = SwayWindowManager({ sway.connection() }, store, scope)
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        scope.cancel()
        sway.close()
    }

    @Test
    fun `dock is a sibling inside the tab, not a sibling of the tab`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")

        wm.attach(app1, AgentId("agent-1"), dockFor("aw-dock1"))

        val workspace = assertNotNull(wm.tree().workspace("1"))
        assertEquals(
            2,
            workspace.children.size,
            "the tabbed workspace must still hold exactly two tabs; a dock that became a " +
                "sibling of the tab would show up as a third",
        )
        val tab = assertNotNull(workspace.children.firstOrNull { it.find(app1.raw) != null })
        assertEquals("splith", tab.layout, "tab 1's contents should be a split container")
        assertEquals(
            setOf(app1.raw, dockId("aw-dock1")),
            tab.children.map { it.id }.toSet(),
            "app and dock share the tab",
        )
        assertNotNull(workspace.children.firstOrNull { it.id == app2.raw }, "tab 2 is untouched")
    }

    @Test
    fun `surfaces excludes docks`() = swayTest {
        val app = openSurface("aw-app1")
        wm.attach(app, AgentId("agent-1"), dockFor("aw-dock1"))

        val appIds = wm.surfaces().map { it.appId }
        assertEquals(listOf("aw-app1"), appIds, "the dock is a real tree node but not a surface")
    }

    /**
     * Hazard 1. sway remembers the last-focused child per container, so a tab left resting on
     * the dock sends the next tab switch — and the user's next keystrokes — into the agent
     * panel rather than the application.
     */
    @Test
    fun `tab switch lands on the app, not the dock`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        wm.attach(app1, AgentId("agent-1"), dockFor("aw-dock1"))

        command("[con_id=${app2.raw}] focus")
        command("focus left")

        assertEquals(app1.raw, focusedId(), "switching into tab 1 must land on the application")
    }

    @Test
    fun `resting focus is switchable to the dock`() = swayTest {
        store.put(WmFlags.restingFocus, RestingFocus.DOCK)
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        val handle = wm.attach(app1, AgentId("agent-1"), dockFor("aw-dock1"))

        command("[con_id=${app2.raw}] focus")
        command("focus left")

        assertEquals(handle.dockId.raw, focusedId(), "the flag must actually change behaviour")
    }

    /** Hazard 2: sway leaves the dock standing when its surface dies. */
    @Test
    fun `orphaned dock is reaped when its surface closes`() = swayTest {
        val app = openSurface("aw-app1")
        val handle = wm.attach(app, AgentId("agent-1"), dockFor("aw-dock1"))

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        wm.reapOrphans()
        awaitGone(handle.dockId)

        assertNull(wm.tree().find(handle.dockId.raw), "the dock must not outlive its surface")
    }

    /**
     * Hazard 3, the sharpest of the three: sway does not collapse a single-child split
     * container, and the leftover container silently adopts the next window opened in that tab.
     */
    @Test
    fun `detaching normalises the container so later windows are not swallowed`() = swayTest {
        val app1 = openSurface("aw-app1")
        val handle = wm.attach(app1, AgentId("agent-1"), dockFor("aw-dock1"))
        handle.detach()

        val newSurface = openSurface("aw-app3")

        val workspace = assertNotNull(wm.tree().workspace("1"))
        assertTrue(
            workspace.children.any { it.id == newSurface.raw },
            "a window opened after detach must become its own tab, not get swallowed into the " +
                "container the dock left behind",
        )
        assertEquals(2, workspace.children.size, "two surfaces means two tabs")
    }

    @Test
    fun `container normalisation is switchable off`() = swayTest {
        store.put(WmFlags.normalizeContainerOnDetach, false)
        val app1 = openSurface("aw-app1")
        val handle = wm.attach(app1, AgentId("agent-1"), dockFor("aw-dock1"))
        handle.detach()

        val tab = assertNotNull(wm.tree().workspace("1")).children.single()
        assertEquals(
            "splith",
            tab.layout,
            "with normalisation off the leftover container should still be there — this is the " +
                "hazard, kept reproducible on purpose",
        )
    }

    // -- helpers ------------------------------------------------------------------------

    private fun swayTest(body: suspend () -> Unit) {
        if (!enabled) {
            println("skipping: sway/foot not installed")
            return
        }
        runBlocking {
            command("workspace 1; layout tabbed")
            body()
        }
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        val results = swayJson.decodeFromString<List<CommandResult>>(raw)
        results.firstOrNull { !it.success }?.let { error("sway rejected '$text': ${it.error}") }
    }

    private suspend fun openSurface(appId: String): SurfaceId {
        command("exec ${sway.windowCommand(appId)}")
        return SurfaceId(assertNotNull(awaitWindow(appId), "window '$appId' never appeared"))
    }

    private fun dockFor(appId: String) = DockSpec(appId, sway.windowCommand(appId))

    private suspend fun dockId(appId: String): Long =
        assertNotNull(wm.tree().windows.firstOrNull { it.appId == appId }).id

    private suspend fun focusedId(): Long? = wm.tree().windows.firstOrNull { it.focused }?.id

    private suspend fun awaitWindow(appId: String): Long? = withTimeoutOrNull(WAIT_MS) {
        while (true) {
            wm.tree().windows.firstOrNull { it.appId == appId }?.let { return@withTimeoutOrNull it.id }
            yield()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private suspend fun awaitGone(id: SurfaceId) {
        withTimeoutOrNull(WAIT_MS) {
            while (wm.tree().find(id.raw) != null) yield()
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
