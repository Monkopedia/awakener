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
import kotlin.test.assertTrue
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
     * stated limit rather than something to assert on here. Under wm.dock.show_width=CONFIGURED,
     * because that is what asks a show for 30%: under AS_HIDDEN it asks for the share the dock
     * was hidden at.
     */
    @Test
    fun `under FROM_SURFACE three docks and a user window are each at their width`() {
        store.put(WmFlags.crowdedWidth, CrowdedWidth.FROM_SURFACE)
        store.put(WmFlags.showWidth, ShowWidth.CONFIGURED)
        crowded(docks = 3, userWindow = true, shown = listOf(0, 2), exact = true)
    }

    @Test
    fun `under FROM_SURFACE four docks and a user window are each at their width`() {
        store.put(WmFlags.crowdedWidth, CrowdedWidth.FROM_SURFACE)
        store.put(WmFlags.showWidth, ShowWidth.CONFIGURED)
        crowded(docks = 4, userWindow = true, shown = listOf(2), exact = true)
    }

    @Test
    fun `under FROM_SURFACE four docks are each at their width`() {
        store.put(WmFlags.crowdedWidth, CrowdedWidth.FROM_SURFACE)
        store.put(WmFlags.showWidth, ShowWidth.CONFIGURED)
        crowded(docks = 4, userWindow = false, shown = listOf(1), exact = true)
    }

    /**
     * #264, the issue's repro: four docks and a window of the user's, every dock hidden and shown
     * in turn for twelve rounds. Each show used to take its width from every window in the split
     * in an equal share, which takes more from the surface than the hide gave it, and walked it
     * from 217 px down to sway's 100 px floor at either scale, under either
     * wm.dock.crowded_width.
     */
    @Test
    fun `twelve rounds of four docks and a user window leave the surface where it was`() =
        rounds(docks = 4, userWindow = true)

    @Test
    fun `twelve rounds of three docks and a user window leave the surface where it was`() =
        rounds(docks = 3, userWindow = true)

    @Test
    fun `twelve rounds of two docks leave the surface where it was`() = rounds(docks = 2, userWindow = false)

    @Test
    fun `twelve rounds of three docks leave the surface where it was`() = rounds(docks = 3, userWindow = false)

    @Test
    fun `twelve rounds of four docks leave the surface where it was`() = rounds(docks = 4, userWindow = false)

    @Test
    fun `under FROM_SURFACE twelve rounds of four docks leave the surface where it was`() {
        store.put(WmFlags.crowdedWidth, CrowdedWidth.FROM_SURFACE)
        rounds(docks = 4, userWindow = false)
    }

    /**
     * #264's map: the user's window moved in after all four docks, so each dock's first show
     * leaves the split it shares with it (#256). One of those shows lands in a split too narrow
     * to narrow the dock to 30% without taking the dock itself under the floor, which sway
     * refuses too, and the readback took for a misplaced dock: `94c57c8` threw "33.3% … rather
     * than the configured 30%". The surface pays for each dock's move into its own split once,
     * which is #256's, so only the rounds after it are held to the surface's width.
     */
    @Test
    fun `twelve rounds of four docks and a user window that joined after them`() =
        rounds(docks = 4, userWindow = true, joinsAfter = true)

    /**
     * wm.dock.show_width: a dock the user widened by hand comes back at that width under
     * AS_HIDDEN, at wm.dock.size_ppt under CONFIGURED, and either way without the mark that
     * carried the width across the hide.
     */
    @Test
    fun `a dock widened by hand comes back at that width`() = handResized(ShowWidth.AS_HIDDEN, expected = 0.40)

    @Test
    fun `under CONFIGURED a dock widened by hand comes back at the configured width`() =
        handResized(ShowWidth.CONFIGURED, expected = 0.30)

    private fun handResized(showWidth: ShowWidth, expected: Double) = swayTest {
        store.put(WmFlags.showWidth, showWidth)
        val app = openSurface("aw-app")
        val dock = wm.attach(app, dockSpec(), AGENT)
        command("[con_id=${dock.dockId.raw}] resize set width 40 ppt")
        assertEquals(true, dock.hide(), "the hide")
        assertEquals(true, dock.show(), "the show")
        val node = assertNotNull(wm.tree().find(dock.dockId.raw), "the dock: ${describe()}")
        assertEquals(expected, assertNotNull(node.percent, describe()), 0.005, describe())
        assertEquals(1, node.marks.size, "the dock mark alone: ${node.marks}")
    }

    /**
     * Stands [docks] docks on one surface as [crowded] does — the user's window moved in after the
     * last of them instead when [joinsAfter] — and hides and shows each in turn for twelve rounds.
     * Every show must succeed, and every hide and show of a dock that came back into the split it
     * was hidden from must leave the surface no more than [ROUND_TRIP_PX] narrower than it was
     * before the hide; across all of them, no more than [DRIFT_PX] narrower than where the first
     * such one found it. Counted, so a run in which no dock ever came back to its own split
     * cannot pass.
     *
     * The user's window, where there is one, is held to the same per-round bound: #264 was filed
     * as the user's window being squeezed, and a show that widened the surface out of it would
     * pass a check on the surface alone.
     *
     * Narrower only, because that is #264: a surface already at sway's floor leaves the
     * proportional plan no room, and the show then falls back to `resize set width`, whose
     * equal share can hand the surface a few px more than the hide took. Measured on CI's sway
     * 1.11 in the late-join case, 101 to 112 px; 1.12 never reaches the floor there.
     */
    private fun rounds(docks: Int, userWindow: Boolean, joinsAfter: Boolean = false) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val mine = if (userWindow) openSurface("aw-mine") else null
        val handles = mutableListOf(wm.attach(app, dockSpec(), AGENT))
        if (mine != null && !joinsAfter) moveBeside(mine, app)
        repeat(docks - 1) { handles += wm.attach(app, dockSpec(), AGENT) }
        if (mine != null && joinsAfter) moveBeside(mine, app)
        var held = 0
        var first: Int? = null
        var last = 0
        for (round in 1..ROUNDS) {
            for ((index, dock) in handles.withIndex()) {
                val stage = "round $round, dock ${index + 1} of $docks"
                val tree = wm.tree()
                val split = tree.parentOf(dock.dockId.raw)?.id
                val surface = assertNotNull(tree.find(app.raw)?.rect?.width, stage)
                val theirs = mine?.let { assertNotNull(tree.find(it.raw)?.rect?.width, stage) }
                assertEquals(true, dock.hide(), "$stage: the hide")
                assertEquals(true, dock.show(), "$stage: the show")
                val after = wm.tree()
                if (after.parentOf(dock.dockId.raw)?.id != split) continue
                last = assertNotNull(after.find(app.raw)?.rect?.width, stage)
                assertTrue(surface - last <= ROUND_TRIP_PX, "$stage: the surface went from $surface to $last px: ${describe()}")
                if (mine != null) {
                    val now = assertNotNull(after.find(mine.raw)?.rect?.width, stage)
                    assertTrue(theirs!! - now <= ROUND_TRIP_PX, "$stage: the user's window went from $theirs to $now px: ${describe()}")
                }
                if (first == null) first = surface
                held++
            }
        }
        assertTrue(held >= (ROUNDS - 1) * docks, "only $held of ${ROUNDS * docks} shows came back to their own split")
        assertTrue(first!! - last <= DRIFT_PX, "the surface drifted from $first to $last px: ${describe()}")
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
    /**
     * The same with a second dock beside the surface, so the restore sizes the dock by walking
     * the split's boundaries rather than by `resize set width` (#264), and the readback has to
     * catch a dock put wrong after that as well.
     */
    @Test
    fun `a dock narrowed after a proportional restore fails the readback`() =
        misplacedDuringReadback(docks = 2) { dock -> command("[con_id=${dock.dockId.raw}] resize set width 15 ppt") }

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
        docks: Int = 1,
        misplace: suspend (DockHandle) -> Unit,
    ) = swayTest {
        store.put(WmFlags.unmapWaitMs, MISPLACED_WAIT_MS)
        prepare()
        SwayValve.open(sway.socket).use { valve ->
            val valved = valvedCommands(valve)
            val app = openSurface("aw-app")
            val dock = List(docks) { valved.attach(app, dockSpec(), AGENT) }.last()
            assertEquals(true, dock.hide(), "the hide")
            var restored = false
            var walked = false
            valve.holdNext { type, payload ->
                if (type == I3Ipc.Request.RUN_COMMAND && "scratchpad show" in payload) {
                    restored = true
                    walked = " right " in payload
                }
                restored && type == I3Ipc.Request.GET_TREE
            }
            val show = scope.async { runCatching { dock.show() } }
            valve.awaitHeld(WAIT_MS)
            misplace(dock)
            valve.release()
            assertEquals(docks > 1, walked, "the restore is the one this case is for")
            val failure = assertNotNull(show.await().exceptionOrNull(), "the show must fail: ${describe()}")
            assertContains(assertNotNull(failure.message), "rather than the 30% the show gave it")
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

        const val ROUNDS = 12

        /** Each of the plan's widths is a rounding of a fraction sway holds; a few px is that. */
        const val ROUND_TRIP_PX = 4

        /** And they do not add up: measured on 1.12, at most 5 px over a run of twelve rounds. */
        const val DRIFT_PX = 8
        val AGENT = AgentId("agent-1")
    }
}
