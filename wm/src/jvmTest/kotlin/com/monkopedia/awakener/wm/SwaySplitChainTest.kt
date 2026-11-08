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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Single-child split containers must not pile up across dock lifecycles (#171).
 *
 * adolin's tree showed a dozen nested `splith`s, each holding one child, above a single window.
 * Every `attach` makes a split, so every way a dock can go away has to take that split back with
 * it. A test that tears one dock down passes against a leak that only compounds, so each path here
 * runs [CYCLES] times on the same surface and the count is checked after **every** cycle, not only
 * at the end: a leak that grows and a leak that sits at one both read as non-zero.
 *
 * What these establish, measured on 1.12 before this suite was written: `split none` on a window
 * whose ancestors are a run of single-child splits collapses the **whole run** in one command, not
 * one level of it. So any path that ends in a flatten — detach, a hide under
 * wm.dock.flatten_on_hide, an unwound attach — also clears a chain it did not make, which is the
 * last group of tests.
 *
 * Not covered, because they are not sequential paths: two `awakener-invoke` processes attaching at
 * once nest a second split inside the first, since each `split horizontal` lands on a surface the
 * other's dock already shares a split with (#199). A dock closed by hand while visible leaves its
 * surface in one single-child split until the next press, because nothing runs between presses;
 * that one does not compound, and the last test here holds it to that.
 *
 * Exercised against **sway 1.12** on the headless wlroots backend, `workspace_layout tabbed` as on
 * adolin.
 */
class SwaySplitChainTest {
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
        stateDir = createTempDirectory("awakener-wm-split-chain")
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

    @Test
    fun `attach and detach cycles leave no single-child split`() = cycles { app ->
        wm.attach(app, dockSpec(), AGENT).detach()
    }

    @Test
    fun `attach, hide, show and detach cycles leave no single-child split`() = cycles { app ->
        val dock = wm.attach(app, dockSpec(), AGENT)
        dock.hide()
        dock.show()
        dock.detach()
    }

    @Test
    fun `detaching a hidden dock, cycled, leaves no single-child split`() = cycles { app ->
        val dock = wm.attach(app, dockSpec(), AGENT)
        dock.hide()
        dock.detach()
    }

    /** The same with the flatten off, so the hidden detach is what collapses the split. */
    @Test
    fun `detaching a hidden dock with flatten_on_hide off leaves no single-child split`() {
        store.put(WmFlags.flattenOnHide, false)
        cycles { app ->
            val dock = wm.attach(app, dockSpec(), AGENT)
            dock.hide()
            dock.show()
            dock.hide()
            dock.detach()
        }
    }

    @Test
    fun `an attach whose dock never maps, cycled, leaves no single-child split`() {
        store.put(WmFlags.mapWaitMs, NEVER_MAPS_WAIT_MS)
        cycles { app ->
            assertFailsWith<IllegalStateException> {
                wm.attach(app, DockSpec("aw-never", "sleep 3600"), AGENT)
            }
        }
    }

    /** Each cycle docks a fresh surface and closes it, so the sweep is what tears the dock down. */
    @Test
    fun `a surface closing under its dock, cycled, leaves no single-child split`() =
        cycles(freshSurface = true) { app ->
            val dock = wm.attach(app, dockSpec(), AGENT)
            close(app)
            wm.reapOrphans()
            awaitGone(dock.dockId)
        }

    @Test
    fun `a surface closing under its hidden dock, cycled, leaves no single-child split`() =
        cycles(freshSurface = true) { app ->
            val dock = wm.attach(app, dockSpec(), AGENT)
            dock.hide()
            close(app)
            wm.reapOrphans()
            awaitGone(dock.dockId)
        }

    /** Hidden then closed by hand: the hide already flattened, so the next attach starts clean. */
    @Test
    fun `a hidden dock closed by hand, cycled, leaves no single-child split`() = cycles { app ->
        val dock = wm.attach(app, dockSpec(), AGENT)
        dock.hide()
        close(dock.dockId)
    }

    /**
     * adolin's chain is exactly the shape repeated `focus parent` + `split` builds, so focus is
     * left on a container before every call here, to see whether any of them splits whatever
     * happens to be focused.
     *
     * Not a guard on attach's own `focus(surface)`: with that line deleted this still passes,
     * because the tree comes back to baseline either way. What it holds is the outcome — no
     * split survives a cycle begun with focus on a container.
     */
    @Test
    fun `focus left on a container does not nest splits`() = cycles { app ->
        command("focus parent")
        val dock = wm.attach(app, dockSpec(), AGENT)
        command("focus parent")
        dock.hide()
        command("focus parent")
        dock.show()
        command("focus parent")
        dock.hide()
        command("focus parent")
        dock.show()
        command("focus parent")
        dock.detach()
    }

    @Test
    fun `detach collapses a chain it did not make`() = chainCollapsedBy { app ->
        wm.attach(app, dockSpec(), AGENT).detach()
    }

    @Test
    fun `hide collapses a chain it did not make`() = chainCollapsedBy { app ->
        wm.attach(app, dockSpec(), AGENT).hide()
    }

    @Test
    fun `an unwound attach collapses a chain it did not make`() {
        store.put(WmFlags.mapWaitMs, NEVER_MAPS_WAIT_MS)
        chainCollapsedBy { app ->
            assertFailsWith<IllegalStateException> {
                wm.attach(app, DockSpec("aw-never", "sleep 3600"), AGENT)
            }
        }
    }

    /**
     * The one sequential path that leaves a split behind, held to not compounding: the next
     * attach reuses the leftover (sway's `split` on an only child re-lays its parent rather than
     * wrapping it), so the count stays at one however many times it happens, and the first
     * detach takes it away.
     */
    @Test
    fun `a visible dock closed by hand leaves one split that does not compound`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        repeat(CYCLES) { cycle ->
            close(wm.attach(app, dockSpec(), AGENT).dockId)
            assertEquals(1, singleChildSplits(), "after cycle ${cycle + 1}: ${describe()}")
        }
        wm.attach(app, dockSpec(), AGENT).detach()
        assertEquals(0, singleChildSplits(), "the next detach clears it: ${describe()}")
    }

    // -- helpers ------------------------------------------------------------------------

    private fun swayTest(body: suspend () -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking { body() }
    }

    /**
     * Runs [cycle] [CYCLES] times beside a second tab, asserting after each one that no
     * single-child split is left and — where the surface outlives the cycle — that the workspace
     * is back to exactly the shape it started in. The count alone would pass a cycle that put the
     * dock somewhere wrong without leaving an only child behind.
     */
    private fun cycles(freshSurface: Boolean = false, cycle: suspend (SurfaceId) -> Unit) =
        swayTest {
            openSurface("aw-other")
            var app = openSurface("aw-app0")
            val baseline = describe()
            assertEquals(0, singleChildSplits(), "baseline: $baseline")
            for (n in 1..CYCLES) {
                if (freshSurface && n > 1) app = openSurface("aw-app$n")
                cycle(app)
                assertEquals(0, singleChildSplits(), "after cycle $n: ${describe()}")
                if (!freshSurface) assertEquals(baseline, describe(), "after cycle $n")
            }
        }

    /** Builds adolin's shape — [CHAIN] single-child splits above the surface — then runs [act]. */
    private fun chainCollapsedBy(act: suspend (SurfaceId) -> Unit) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        command("[con_id=${app.raw}] focus")
        repeat(CHAIN) { command("split horizontal; focus parent") }
        assertEquals(CHAIN, singleChildSplits(), "the chain stands: ${describe()}")

        act(app)

        assertEquals(0, singleChildSplits(), describe())
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        swayJson.decodeFromString<List<CommandResult>>(raw)
            .firstOrNull { !it.success }
            ?.let { error("sway rejected '$text': ${it.error}") }
    }

    private suspend fun close(id: SurfaceId) {
        command("[con_id=${id.raw}] kill")
        awaitGone(id)
    }

    private suspend fun awaitGone(id: SurfaceId) {
        val gone = withTimeoutOrNull(WAIT_MS) {
            while (wm.tree().find(id.raw) != null) delay(POLL_MS)
            true
        }
        assertNotNull(gone, "window ${id.raw} never left the tree")
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

    private fun dockSpec() = DockSpec("aw-dock", sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    /** Split containers on workspace 1 holding exactly one child — the shape #171 counted. */
    private suspend fun singleChildSplits(): Int {
        fun count(node: Node): Int =
            (if (node.type == "con" && node.layout?.startsWith("split") == true && node.nodes.size == 1) 1 else 0) +
                node.children.sumOf { count(it) }
        return count(workspace())
    }

    private suspend fun workspace(): Node = assertNotNull(wm.tree().workspace("1"), "workspace 1 exists")

    /** Workspace 1 as layouts and app_ids, so a failed count shows the shape it counted. */
    private suspend fun describe(): String {
        fun describe(node: Node): String =
            if (node.children.isEmpty()) {
                "${node.appId ?: node.id}"
            } else {
                "${node.layout}[${node.children.joinToString(" ") { describe(it) }}]"
            }
        return describe(workspace())
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
        const val CYCLES = 5
        const val CHAIN = 5

        /** Long enough for an exec to be accepted, short enough that five failures stay cheap. */
        const val NEVER_MAPS_WAIT_MS = 600L
        val AGENT = AgentId("agent-1")
    }
}
