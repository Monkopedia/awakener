package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.math.abs
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
 * equal share (1.11 from its neighbours alone, [ResizeShare]), and does nothing — while
 * reporting success — when any one of them would end
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
     * than the configured 30%".
     *
     * #267: that first show, into a split of the surface's own inside the user's, used to take
     * the dock's width from the surface alone while the user's window kept what the hide gave it
     * — 268 px to 116 here on 1.12. Held from round 1 now, as every [rounds] case is.
     */
    @Test
    fun `twelve rounds of four docks and a user window that joined after them`() =
        rounds(docks = 4, userWindow = true, joinsAfter = true)

    /** #267's table, the rows not already above. */
    @Test
    fun `twelve rounds of three docks and a user window that joined after them`() =
        rounds(docks = 3, userWindow = true, joinsAfter = true)

    @Test
    fun `twelve rounds of two docks and a user window that joined after them`() =
        rounds(docks = 2, userWindow = true, joinsAfter = true)

    @Test
    fun `twelve rounds of two docks and a user window leave the surface where it was`() =
        rounds(docks = 2, userWindow = true)

    /**
     * #270's second review: every dock hidden, then every dock shown, which is one press per
     * panel from inside each. A check for the user's hand that counted awakener's own hide of a
     * second dock, or show of the first, as a change turned #267's fix off: back to 268 → 197 px
     * of surface and 213 → 567 of the user's window with four docks on 1.12.
     *
     * And #272: from round 2 on — and from round 1 with no user window — every dock leaves and
     * re-enters the surface's own split, and a share recorded of a split the others had already
     * left, or one the last dock's hide collapsed, gave the surface's width to the docks: 619 →
     * 556 px with two docks in reverse, 322 → 202 with four, 268 → 169 with four and a user
     * window, on 1.12.
     */
    @Test
    fun `two docks hidden together and shown in order come back where they were`() = hideAllShowAll(docks = 2, reversed = false, userWindow = false)

    @Test
    fun `two docks hidden together and shown in reverse come back where they were`() = hideAllShowAll(docks = 2, reversed = true, userWindow = false)

    @Test
    fun `three docks hidden together and shown in order come back where they were`() = hideAllShowAll(docks = 3, reversed = false, userWindow = false)

    @Test
    fun `three docks hidden together and shown in reverse come back where they were`() = hideAllShowAll(docks = 3, reversed = true, userWindow = false)

    @Test
    fun `four docks hidden together and shown in order come back where they were`() = hideAllShowAll(docks = 4, reversed = false, userWindow = false)

    @Test
    fun `four docks hidden together and shown in reverse come back where they were`() = hideAllShowAll(docks = 4, reversed = true, userWindow = false)

    @Test
    fun `two docks hidden together and shown in order beside a user window come back where they were`() = hideAllShowAll(docks = 2, reversed = false, userWindow = true)

    @Test
    fun `two docks hidden together and shown in reverse beside a user window come back where they were`() = hideAllShowAll(docks = 2, reversed = true, userWindow = true)

    @Test
    fun `three docks hidden together and shown in order beside a user window come back where they were`() = hideAllShowAll(docks = 3, reversed = false, userWindow = true)

    @Test
    fun `three docks hidden together and shown in reverse beside a user window come back where they were`() = hideAllShowAll(docks = 3, reversed = true, userWindow = true)

    @Test
    fun `four docks hidden together and shown in order beside a user window come back where they were`() = hideAllShowAll(docks = 4, reversed = false, userWindow = true)

    @Test
    fun `four docks hidden together and shown in reverse beside a user window come back where they were`() = hideAllShowAll(docks = 4, reversed = true, userWindow = true)

    /**
     * #275's review: the dock hidden last is the one whose hide finds the surface beside it
     * alone, and in every shape above it stands at wm.dock.size_ppt, which is also what a show
     * without its home widths falls back to. Widened by hand first, it has to come back at the
     * width the user gave it: without the `alone` mark's home widths, 500 px came back as 384.
     */
    @Test
    fun `two docks hidden together and shown in order after the newest was widened by hand come back where they were`() =
        hideAllShowAll(docks = 2, reversed = false, userWindow = false, widenNewest = true)

    @Test
    fun `two docks hidden together and shown in reverse after the newest was widened by hand come back where they were`() =
        hideAllShowAll(docks = 2, reversed = true, userWindow = false, widenNewest = true)

    @Test
    fun `four docks hidden together and shown in order after the newest was widened by hand come back where they were`() =
        hideAllShowAll(docks = 4, reversed = false, userWindow = false, widenNewest = true)

    @Test
    fun `four docks hidden together and shown in reverse after the newest was widened by hand come back where they were`() =
        hideAllShowAll(docks = 4, reversed = true, userWindow = false, widenNewest = true)

    /**
     * Stands [docks] docks, with the user's window moved in after them where [userWindow], then
     * hides every dock and shows every dock — in the order they were hidden, or [reversed] — for
     * [ALL_ROUNDS] rounds. After **every** round the surface and every dock are within
     * [ROUND_TRIP_PX] of where the round started and within [DRIFT_PX] of where the first
     * started, and the user's window is within [ROUND_TRIP_PX] of where it started, with no split
     * mark of awakener's above it. Where [widenNewest], the newest dock is first widened by hand
     * to [WIDENED_PX], out of the dock beside it.
     */
    private fun hideAllShowAll(docks: Int, reversed: Boolean, userWindow: Boolean, widenNewest: Boolean = false) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val mine = if (userWindow) openSurface("aw-mine") else null
        val handles = List(docks) { wm.attach(app, dockSpec(), AGENT) }
        if (mine != null) moveBeside(mine, app)
        if (widenNewest) {
            val newest = handles.last().dockId
            val width = assertNotNull(wm.tree().find(newest.raw)?.rect?.width, "the newest dock")
            command("[con_id=${newest.raw}] resize grow left ${WIDENED_PX - width} px")
            val widened = assertNotNull(wm.tree().find(newest.raw)?.rect?.width, "the newest dock")
            assertTrue(abs(widened - WIDENED_PX) <= ROUND_TRIP_PX, "the newest dock was widened to $widened px, not $WIDENED_PX: ${describe()}")
        }
        val windows = listOf(app) + handles.map { it.dockId }
        fun widths(tree: Node) = windows.map { assertNotNull(tree.find(it.raw)?.rect?.width, "window ${it.raw}") }
        val start = wm.tree()
        val first = widths(start)
        val joined = mine?.let { assertNotNull(start.find(it.raw)?.rect?.width, "the user's window") }
        var previous = first
        for (round in 1..ALL_ROUNDS) {
            handles.forEachIndexed { i, dock -> assertEquals(true, dock.hide(), "round $round: hide ${i + 1}") }
            (if (reversed) handles.reversed() else handles).forEach { dock ->
                assertEquals(true, dock.show(), "round $round: show ${handles.indexOf(dock) + 1}")
            }
            val after = wm.tree()
            val now = widths(after)
            windows.indices.forEach { i ->
                val name = if (i == 0) "the surface" else "dock $i of $docks"
                assertTrue(abs(previous[i] - now[i]) <= ROUND_TRIP_PX, "round $round: $name went from ${previous[i]} to ${now[i]} px: ${describe()}")
                assertTrue(abs(first[i] - now[i]) <= DRIFT_PX, "round $round: $name drifted from ${first[i]} to ${now[i]} px: ${describe()}")
            }
            previous = now
            if (mine != null) {
                val theirs = assertNotNull(after.find(mine.raw)?.rect?.width, "the user's window")
                assertTrue(abs(theirs - joined!!) <= ROUND_TRIP_PX, "round $round: the user's window went from $joined to $theirs px: ${describe()}")
                val marked = generateSequence(after.parentOf(mine.raw)) { after.parentOf(it.id) }
                    .filter { node -> node.marks.any { it.startsWith("_awakener_split_") } }.toList()
                assertTrue(marked.isEmpty(), "round $round: a split holding the user's window is marked awakener's: ${marked.map { it.marks }}")
            }
        }
    }

    /**
     * #270's review: a user window resized by hand while the dock is hidden keeps the width the
     * user chose. The show across splits used to take the dock's width out of whatever width the
     * old split's windows had, the user's choice included: 383 px chosen ended at 306, 683 at 546.
     */
    @Test
    fun `a user window narrowed while one dock is hidden keeps its width`() = handResizedWhileHidden(docks = 1, by = -150)

    @Test
    fun `a user window widened while one dock is hidden keeps its width`() = handResizedWhileHidden(docks = 1, by = 150)

    @Test
    fun `a user window narrowed while one of two docks is hidden keeps its width`() =
        handResizedWhileHidden(docks = 2, by = -150)

    @Test
    fun `a user window widened while one of two docks is hidden keeps its width`() =
        handResizedWhileHidden(docks = 2, by = 150)

    private fun handResizedWhileHidden(docks: Int, by: Int) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val mine = openSurface("aw-mine")
        val handles = List(docks) { wm.attach(app, dockSpec(), AGENT) }
        moveBeside(mine, app)
        val dock = handles.first()
        assertEquals(true, dock.hide(), "the hide")
        val hidden = assertNotNull(wm.tree().find(mine.raw)?.rect?.width, "the user's window")
        command("[con_id=${mine.raw}] resize set width ${hidden + by} px")
        val chosen = assertNotNull(wm.tree().find(mine.raw)?.rect?.width, "the user's window")
        assertTrue(abs(chosen - (hidden + by)) <= ROUND_TRIP_PX, "the hand resize took: $hidden to $chosen px: ${describe()}")
        assertEquals(true, dock.show(), "the show")
        val after = wm.tree()
        val now = assertNotNull(after.find(mine.raw)?.rect?.width, "the user's window")
        assertTrue(abs(now - chosen) <= ROUND_TRIP_PX, "the user chose $chosen px and the show left $now: ${describe()}")
        val marked = generateSequence(after.parentOf(mine.raw)) { after.parentOf(it.id) }
            .filter { node -> node.marks.any { it.startsWith("_awakener_split_") } }.toList()
        assertTrue(marked.isEmpty(), "a split holding the user's window is marked awakener's: ${marked.map { it.marks }}")
    }

    /**
     * #269: under wm.dock.show_width=CONFIGURED every show asks for wm.dock.size_ppt again, so
     * every round re-runs the restore's `resize set width` against a split the last round left
     * more crowded, and the readback has to tell a refusal from a misplacement each time. The
     * refusal rule is the compositor's and it changed in sway 1.12: 1.11 takes the growth from the
     * dock's neighbours alone, 1.12 from every sibling. A model of one version's rule passes a
     * single show on the other and throws within three rounds, which is why these are rounds.
     */
    @Test
    fun `under CONFIGURED twelve rounds of four docks and a user window each show`() = configuredRounds(docks = 4, userWindow = true)

    @Test
    fun `under CONFIGURED twelve rounds of three docks and a user window each show`() = configuredRounds(docks = 3, userWindow = true)

    @Test
    fun `under CONFIGURED twelve rounds of four docks each show`() = configuredRounds(docks = 4, userWindow = false)

    @Test
    fun `under CONFIGURED twelve rounds of three docks each show`() = configuredRounds(docks = 3, userWindow = false)

    @Test
    fun `under CONFIGURED twelve rounds of four docks and a user window that joined after them each show`() =
        configuredRounds(docks = 4, userWindow = true, joinsAfter = true)

    @Test
    fun `under CONFIGURED and FROM_SURFACE twelve rounds of four docks and a user window each show`() {
        store.put(WmFlags.crowdedWidth, CrowdedWidth.FROM_SURFACE)
        configuredRounds(docks = 4, userWindow = true)
    }

    private fun configuredRounds(docks: Int, userWindow: Boolean, joinsAfter: Boolean = false) {
        store.put(WmFlags.showWidth, ShowWidth.CONFIGURED)
        crowded(docks, userWindow, shown = List(ROUNDS) { 0 until docks }.flatten(), joinsAfter = joinsAfter)
    }

    /**
     * #269, against the compositor rather than a model of it: four plain windows in a split, and
     * a `resize set width` on the third in two trees where 1.11's rule and 1.12's disagree — a
     * narrow first window, beyond the third's neighbours, which only 1.12 asks for a share; and a
     * narrow second window, which can spare 1.12's third of the growth but not 1.11's half.
     * Whichever sway this is, [refusedResize] under the [ResizeShare] it reports must say what
     * sway then did. The trees are made with edge resizes, which both versions apply alike, so
     * the two runs start from the same widths.
     */
    @Test
    fun `the refusal rule read from the version is the one sway applies`() = swayTest {
        val share = sway.connection().use { it.resizeShare() }
        val cases = listOf("resize shrink right ${QUARTER_PX - NARROW_PX} px" to 0, "resize shrink left ${QUARTER_PX - 130} px" to 1)
        for ((index, case) in cases.withIndex()) {
            val (narrowing, narrowed) = case
            val workspace = "${3 + index}"
            command("workspace $workspace")
            val first = openSurface("aw-rule-$index-0")
            command("[con_id=${first.raw}] layout splith")
            val windows = listOf(first) + (1..3).map { openSurface("aw-rule-$index-$it") }
            command("[con_id=${windows[narrowed].raw}] $narrowing")
            val target = windows[2].raw
            val before = wm.tree()
            val stage = "$narrowing on window ${narrowed + 1}: ${describe(before.workspace(workspace))}"
            assertTrue(
                (before.refusedResize(target, RULE_PPT, ResizeShare.NEIGHBOURS) == null) !=
                    (before.refusedResize(target, RULE_PPT, ResizeShare.EVERY_SIBLING) == null),
                "the two rules must disagree here, or this tells them apart from nothing: $stage",
            )
            val refused = before.refusedResize(target, RULE_PPT, share) != null
            command("[con_id=$target] resize set width $RULE_PPT ppt")
            val moved = wm.tree().find(target)?.rect?.width != before.find(target)?.rect?.width
            assertEquals(!refused, moved, "under $share, refused=$refused but sway moved=$moved: $stage")
        }
    }

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
     * Every show must succeed, and every hide and show must leave the surface no more than
     * [ROUND_TRIP_PX] narrower than it was before the hide; across all of them, no more than
     * [DRIFT_PX] narrower than before the first. That includes a show into a different split from
     * the one the dock was hidden from, which a window of the user's in the old one causes (#256),
     * and which used to take the dock's whole width from the surface (#267).
     *
     * The user's window, where there is one, is held to the same per-round bound: #264 was filed
     * as the user's window being squeezed, and a show that widened the surface out of it would
     * pass a check on the surface alone. It is never narrower than it was before the first hide,
     * and never more than [DRIFT_PX] wider either: the hide
     * gives it a share of the dock's width, and #267 was the show not taking that back. And no
     * container holding it carries a split mark of awakener's, which is #256's rule.
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
        val start = wm.tree()
        val first = assertNotNull(start.find(app.raw)?.rect?.width, "the surface")
        val joined = mine?.let { assertNotNull(start.find(it.raw)?.rect?.width, "the user's window") }
        var last = first
        for (round in 1..ROUNDS) {
            for ((index, dock) in handles.withIndex()) {
                val stage = "round $round, dock ${index + 1} of $docks"
                val tree = wm.tree()
                val surface = assertNotNull(tree.find(app.raw)?.rect?.width, stage)
                val theirs = mine?.let { assertNotNull(tree.find(it.raw)?.rect?.width, stage) }
                assertEquals(true, dock.hide(), "$stage: the hide")
                assertEquals(true, dock.show(), "$stage: the show")
                val after = wm.tree()
                last = assertNotNull(after.find(app.raw)?.rect?.width, stage)
                assertTrue(surface - last <= ROUND_TRIP_PX, "$stage: the surface went from $surface to $last px: ${describe()}")
                if (mine != null) {
                    val now = assertNotNull(after.find(mine.raw)?.rect?.width, stage)
                    assertTrue(theirs!! - now <= ROUND_TRIP_PX, "$stage: the user's window went from $theirs to $now px: ${describe()}")
                    assertTrue(joined!! - now <= ROUND_TRIP_PX, "$stage: the user's window went from $joined to $now px: ${describe()}")
                    assertTrue(now - joined <= DRIFT_PX, "$stage: the user's window kept the dock's width, $joined to $now px: ${describe()}")
                    val marked = generateSequence(after.parentOf(mine.raw)) { after.parentOf(it.id) }
                        .filter { node -> node.marks.any { it.startsWith("_awakener_split_") } }.toList()
                    assertTrue(marked.isEmpty(), "$stage: a split holding the user's window is marked awakener's: ${marked.map { it.marks }}")
                }
            }
        }
        assertTrue(first - last <= DRIFT_PX, "the surface drifted from $first to $last px: ${describe()}")
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
                tree.refusedResize(dock.dockId.raw, 30, sway.connection().use { it.resizeShare() }),
                "the foreign split is one sway refuses the dock 30% in, or this tests nothing: ${describe(tree.workspace("2"))}",
            )
        }
    }

    /**
     * #265: attach reads its dock's width back as show does. A fourth dock beside a user's window
     * attaches short of 30% because sway refused the resize, and that is excused: the attach
     * succeeds, beside the surface. Asserted short and refused, so the excuse is what this
     * exercises rather than a dock that happened to fit.
     */
    @Test
    fun `a fourth dock beside a user window attaches where sway refused it its width`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val mine = openSurface("aw-mine")
        val handles = mutableListOf(wm.attach(app, dockSpec(), AGENT))
        moveBeside(mine, app)
        repeat(3) { handles += wm.attach(app, dockSpec(), AGENT) }
        val tree = wm.tree()
        val fourth = handles.last().dockId.raw
        assertEquals(tree.parentOf(app.raw)?.id, tree.parentOf(fourth)?.id, "beside the surface: ${describe()}")
        val percent = assertNotNull(tree.find(fourth)?.percent, "a width fraction")
        assertTrue(percent < 0.29, "the fourth dock is short of 30%, or this tests nothing: ${describe()}")
        assertNotNull(
            tree.refusedResize(fourth, 30, sway.connection().use { it.resizeShare() }),
            "sway refused it the width: ${describe()}",
        )
    }

    /**
     * #265: the attach's readback still fires on a real misplacement. The first tree read after
     * the attach's resize is held while another client narrows the dock in a split with room; the
     * attach must fail and unwind to the tree it started from.
     */
    @Test
    fun `a dock narrowed during its attach fails the attach and leaves nothing behind`() =
        misplacedDuringAttach { dock -> command("[con_id=$dock] resize set width 15 ppt") }

    /** And a dock moved into a crowded split elsewhere, which sway would refuse 30% there too. */
    @Test
    fun `a dock moved into a crowded split elsewhere during its attach fails the attach and leaves nothing behind`() {
        var narrow = SurfaceId(0)
        misplacedDuringAttach(
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
                    "[con_id=$dock] move window to mark _test_foreign; unmark _test_foreign",
            )
            val tree = wm.tree()
            assertNotNull(
                tree.refusedResize(dock, 30, sway.connection().use { it.resizeShare() }),
                "the foreign split is one sway refuses the dock 30% in, or this tests nothing: ${describe(tree.workspace("2"))}",
            )
        }
    }

    /**
     * One dock attached to a surface alone, with the first tree read after the attach's resize
     * held at the valve while [misplace] puts the dock wrong. The attach must fail on its width,
     * and its unwind must leave no dock window, no split and no awakener mark anywhere.
     */
    private fun misplacedDuringAttach(prepare: suspend () -> Unit = {}, misplace: suspend (Long) -> Unit) = swayTest {
        store.put(WmFlags.unmapWaitMs, MISPLACED_WAIT_MS)
        prepare()
        SwayValve.open(sway.socket).use { valve ->
            val valved = valvedCommands(valve)
            val app = openSurface("aw-app")
            val before = describe()
            var resized = false
            valve.holdNext { type, payload ->
                if (type == I3Ipc.Request.RUN_COMMAND && "resize set width" in payload) resized = true
                resized && type == I3Ipc.Request.GET_TREE
            }
            val attach = scope.async { runCatching { valved.attach(app, dockSpec(), AGENT) } }
            valve.awaitHeld(WAIT_MS)
            val dock = assertNotNull(wm.tree().windows.firstOrNull { it.appId == "aw-dock" }, "the dock mapped").id
            misplace(dock)
            valve.release()
            val failure = assertNotNull(attach.await().exceptionOrNull(), "the attach must fail: ${describe()}")
            assertContains(assertNotNull(failure.message), "rather than the 30% the attach gave it")
            val after = wm.tree()
            assertTrue(after.windows.none { it.appId == "aw-dock" }, "no dock window is left: ${describe()}")
            fun all(node: Node): Sequence<Node> = sequenceOf(node) + node.children.asSequence().flatMap { all(it) }
            val marked = all(after).filter { node -> node.marks.any { "awakener" in it } }.map { it.id to it.marks }.toList()
            assertTrue(marked.isEmpty(), "no awakener mark is left: $marked")
            assertEquals(before, describe(), "the surface's workspace is as it was before the attach")
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
            // In px since #272: the show gives the dock its home width's share of the split, and
            // the readback names what it asked for.
            assertContains(assertNotNull(failure.message), "rather than the $CONFIGURED_PX px the show gave it")
        }
    }

    /**
     * Stands [docks] docks on one surface — a user's window moved into the split after the first,
     * as #260's review did, when [userWindow] — then hides and shows each of [shown] in order.
     * Every show must succeed and leave its dock tiled in the surface's own split.
     */
    private fun crowded(
        docks: Int,
        userWindow: Boolean,
        shown: List<Int>,
        exact: Boolean = false,
        joinsAfter: Boolean = false,
    ) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val mine = if (userWindow) openSurface("aw-mine") else null
        val handles = mutableListOf(wm.attach(app, dockSpec(), AGENT))
        if (mine != null && !joinsAfter) moveBeside(mine, app)
        repeat(docks - 1) {
            handles += wm.attach(app, dockSpec(), AGENT)
            if (exact) assertAtWidth(handles.last(), "attach ${handles.size} of $docks")
        }
        if (mine != null && joinsAfter) moveBeside(mine, app)
        for ((step, index) in shown.withIndex()) {
            val dock = handles[index]
            val stage = "show ${step + 1} of ${shown.size}, dock ${index + 1} of $docks"
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

        /** Rounds of hiding every dock and then showing every dock. */
        const val ALL_ROUNDS = 3

        /** 30%, wm.dock.size_ppt's default, of the 1280 px the test output is wide at either scale. */
        const val CONFIGURED_PX = 384

        /** What a hand resize takes the newest dock to: anything but the 384 px of wm.dock.size_ppt. */
        const val WIDENED_PX = 500

        /** A quarter of the 1280 px a split of four on the test output starts at. */
        const val QUARTER_PX = 320

        /** 31% of 1280 is 396, so the third window asks for 76 px: 26 a sibling on 1.12, 38 on 1.11. */
        const val RULE_PPT = 31

        /** Each of the plan's widths is a rounding of a fraction sway holds; a few px is that. */
        const val ROUND_TRIP_PX = 4

        /** And they do not add up: measured on 1.12, at most 5 px over a run of twelve rounds. */
        const val DRIFT_PX = 8
        val AGENT = AgentId("agent-1")
    }
}
