package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Flag
import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Concurrent attaches are covered too, by separate managers — separate in-process locks, which is
 * what separate `awakener-invoke` processes are. Before the cross-process attach lock (#199) they
 * claimed one dock between them, nested the surface a split deeper every round, and had detach's
 * `split none` refused. `ConcurrentInvokeTest` in `:cli` is the same question asked of real
 * processes. A dock closed by hand while visible leaves its surface in one single-child split
 * until the next press, because nothing runs between presses; that one does not compound, and a
 * test here holds it to that.
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
    private lateinit var registry: FileBindingStore

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start("workspace_layout tabbed")
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-split-chain")
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

    /**
     * The three chain tests are wm.dock.split_ownership=SHAPE's: a chain no build marked is a
     * user's as far as provenance can tell, so under the default MARKED it stands (#248) — the
     * `a chain no build marked` tests below hold that side.
     */
    @Test
    fun `detach collapses a chain it did not make`() = chainCollapsedBy { app ->
        store.put(WmFlags.splitOwnership, SplitOwnership.SHAPE)
        wm.attach(app, dockSpec(), AGENT).detach()
    }

    @Test
    fun `hide collapses a chain it did not make`() = chainCollapsedBy { app ->
        store.put(WmFlags.splitOwnership, SplitOwnership.SHAPE)
        wm.attach(app, dockSpec(), AGENT).hide()
    }

    @Test
    fun `an unwound attach collapses a chain it did not make`() {
        store.put(WmFlags.mapWaitMs, NEVER_MAPS_WAIT_MS)
        store.put(WmFlags.splitOwnership, SplitOwnership.SHAPE)
        chainCollapsedBy { app ->
            assertFailsWith<IllegalStateException> {
                wm.attach(app, DockSpec("aw-never", "sleep 3600"), AGENT)
            }
        }
    }

    @Test
    fun `a chain no build marked survives a detach`() = chainSurvives { app ->
        wm.attach(app, dockSpec(), AGENT).detach()
    }

    @Test
    fun `a chain no build marked survives a hide, a show and a detach`() = chainSurvives { app ->
        val dock = wm.attach(app, dockSpec(), AGENT)
        dock.hide()
        dock.show()
        dock.detach()
    }

    @Test
    fun `a chain no build marked survives an unwound attach`() {
        store.put(WmFlags.mapWaitMs, NEVER_MAPS_WAIT_MS)
        chainSurvives { app ->
            assertFailsWith<IllegalStateException> {
                wm.attach(app, DockSpec("aw-never", "sleep 3600"), AGENT)
            }
        }
    }

    /**
     * #248: a split the user put the surface alone in. sway's `split` on an only child re-lays its
     * parent rather than wrapping it, so the dock used to land inside the user's container — a
     * `splitv` turned `splith` — and the last detach flattened it away. Each shape is run through
     * every lifecycle, and the user's containers must come back with the same ids, layouts and
     * children, with the dock while it stood in a split of awakener's own inside them.
     */
    @Test
    fun `a horizontal split the user put the surface alone in survives`() =
        usersSplitSurvives("split horizontal")

    @Test
    fun `a vertical split the user put the surface alone in survives`() =
        usersSplitSurvives("split vertical")

    @Test
    fun `nested splits the user put the surface alone in survive`() =
        usersSplitSurvives("split vertical; focus parent; split horizontal")

    /**
     * The mark has to be atomic with the split. An attach cancelled between making its split and
     * marking it left a split nothing flattens under MARKED, and the next attach nested inside
     * it — measured by the #250 review at 19 leaks in 180 cancellations. Swept across the first
     * [INTERRUPT_DELAYS_MS] of the attach for [INTERRUPT_ROUNDS] rounds, every single-child split
     * left after each must carry its mark, no claim's anchor may be left (#254's review: a
     * cancellation landing on the claim's acknowledgement skipped the settle), and one attach and
     * detach must then bring the workspace back to its baseline.
     */
    @Test
    fun `an interrupted attach leaves no split without its mark`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val baseline = describe()
        repeat(INTERRUPT_ROUNDS) { round ->
            val attach = scope.async { wm.attach(app, dockSpec(), AGENT) }
            delay(round % INTERRUPT_DELAYS_MS)
            attach.cancel()
            runCatching { attach.await() }.getOrNull()?.detach()
            // A dock that maps after the unwind's last look is #32's late dock, not this test's:
            // closed by hand, which runs no awakener code and so leaves the split as it found it.
            delay(LATE_DOCK_MS)
            wm.tree().windows.filter { it.appId == "aw-dock" }.forEach { close(SurfaceId(it.id)) }
            val stage = "round ${round + 1}, cancelled after ${round % INTERRUPT_DELAYS_MS}ms"
            assertEquals(emptyList(), unmarkedSingleChildSplits(), "$stage: ${describe()}")
            assertEquals(emptyList(), anchorMarks(), "$stage: no anchor is left")
        }
        wm.attach(app, dockSpec(), AGENT).detach()
        assertEquals(baseline, describe(), "one attach and detach clears whatever was left")
    }

    /**
     * The #250 review's deterministic case: the dock maps beside whatever is focused when it maps,
     * and a user who moves focus while the panel starts up has it map away from the surface. The
     * wrap then found the surface still alone in the user's split, re-laid that split in place,
     * marked it, and the detach destroyed it.
     */
    @Test
    fun `a dock that maps while focus is elsewhere leaves the user's split standing`() = swayTest {
        val other = openSurface("aw-other")
        val app = openSurface("aw-app")
        command("[con_id=${app.raw}] focus; split vertical")
        val users = usersSplits(app)
        val baseline = describe()
        val slow = DockSpec(
            "aw-dock",
            "sh -c 'sleep $SLOW_DOCK_S; exec ${sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER)}'",
        )
        repeat(3) { cycle ->
            val attach = scope.async { wm.attach(app, slow, AGENT) }
            delay(FOCUS_MOVE_MS)
            command("[con_id=${other.raw}] focus")
            val dock = attach.await()
            val tree = wm.tree()
            val own = assertNotNull(tree.parentOf(dock.dockId.raw), "cycle ${cycle + 1}: dock's split")
            assertEquals(listOf(app.raw, dock.dockId.raw), own.nodes.map { it.id }, describe())
            assertEquals(users.first().id, tree.parentOf(own.id)?.id, describe())
            assertEquals(users.first().layout, tree.find(users.first().id)?.layout, describe())
            dock.detach()
            assertEquals(users, usersSplits(app), "cycle ${cycle + 1}: ${describe()}")
            assertEquals(baseline, describe(), "cycle ${cycle + 1}")
            assertEquals(emptyList(), splitMarks(), "cycle ${cycle + 1}: no split mark is left")
        }
    }

    /**
     * The #250 review's race: the surface's last sibling in the user's split closes between the
     * tree read attach decides from and the message that splits. `split` then re-lays the user's
     * split rather than wrapping the surface, and the mark landed on the user's container. It
     * now lands inert, so the detach leaves the split standing — re-laid, as on `main`, but
     * there. Swept across the first few milliseconds of the attach.
     */
    @Test
    fun `the user's split survives its last sibling closing as an attach starts`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        command("[con_id=${app.raw}] focus; split vertical")
        val users = assertNotNull(wm.tree().parentOf(app.raw), "the user's split").id
        repeat(KILL_RACE_ROUNDS) { round ->
            val offset = (round % KILL_RACE_STEPS) * KILL_RACE_STEP_NS
            command("[con_id=${app.raw}] focus")
            val mine = openSurface("aw-mine$round")
            assertEquals(users, wm.tree().parentOf(mine.raw)?.id, "round ${round + 1}: ${describe()}")
            val attach = scope.async { wm.attach(app, dockSpec(), AGENT) }
            LockSupport.parkNanos(offset)
            command("[con_id=${mine.raw}] kill")
            val dock = attach.await()
            awaitGone(mine)
            dock.detach()
            val stage = "round ${round + 1}, killed after ${offset / 1000}us"
            assertEquals(users, wm.tree().parentOf(app.raw)?.id, "$stage: ${describe()}")
            assertEquals(emptyList(), splitMarks(), "$stage: no split mark is left")
        }
    }

    /**
     * #252: another client splits the surface between the tree read attach decides from and the
     * message that splits. Its container is younger than anything that read saw, so the age guard
     * alone took it for awakener's when attach's `split` re-laid it and the mark landed on it, and
     * the detach destroyed it. Swept across the first [USER_SPLIT_STEPS] × [USER_SPLIT_STEP_NS] of
     * the attach. Which container is the user's is read off the user's own message, with the same
     * two marks awakener uses: one beside the surface before the split and one on its parent
     * after, so a split that re-laid what stood (awakener's) makes nothing of the user's to look
     * for.
     */
    @Test
    fun `a split another client makes as an attach starts is never claimed`() = userSplitRace()

    /**
     * The same race with the surface directly on a split workspace, which sway refuses to mark, so
     * the claim's anchor goes on the surface instead of its parent. The user's message cannot mark
     * a workspace either, so it marks the next sibling. That climbs from an only child to the
     * nearest ancestor of the same orientation (measured on 1.12), so the workspace is vertical:
     * from the surface alone in awakener's `splith` it then stays on the surface.
     */
    @Test
    fun `a split another client makes on a split workspace is never claimed`() {
        SwayHarness.assumeAvailable()
        sway.close()
        sway = SwayHarness.start("default_orientation vertical")
        wm = SwayWindowManager({ sway.connection() }, store, registry, scope)
        userSplitRace(beside = "focus next sibling")
    }

    /**
     * [USER_SPLIT_ROUNDS] attaches, each with a second connection splitting the surface a little
     * later into it. [beside] is how the user's message reaches what it marks before splitting.
     */
    private fun userSplitRace(beside: String = "focus parent") = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val baseline = describe()
        var made = 0
        repeat(USER_SPLIT_ROUNDS) { round ->
            val offset = (round % USER_SPLIT_STEPS) * USER_SPLIT_STEP_NS
            val stage = "round ${round + 1}, split after ${offset / 1000}us"
            assertEquals(baseline, describe(), "$stage, before")
            command("[con_id=${app.raw}] focus")
            val attach = scope.async { wm.attach(app, dockSpec(), AGENT) }
            LockSupport.parkNanos(offset)
            command(
                "[con_id=${app.raw}] focus; $beside; mark --add user_before; " +
                    "[con_id=${app.raw}] split vertical; [con_id=${app.raw}] focus; focus parent; " +
                    "mark --add user_after; [con_id=${app.raw}] focus",
            )
            // Read before the attach ends, because an unwind's flatten takes `user_after` with
            // it where that mark is on awakener's own split; a user's split it took would then
            // fail the survival check below, as a detach's would.
            val users = wm.tree().let { tree ->
                val after = tree.find(assertNotNull(tree.holderOf("user_after"), stage))
                after?.id?.takeUnless { after.holdsMark("user_before") }
            }
            if (users != null) made++
            // #265: a `split vertical` that lands on the surface after attach's own split and
            // before the dock maps re-lays that split, so the dock is moved in *under* the
            // surface; the attach's readback refuses that and unwinds. Most rounds here, since
            // the dock takes far longer to map than the sweep's 10 ms. Either outcome must leave
            // the same tree.
            val dock = runCatching { attach.await() }
            dock.exceptionOrNull()?.let { failure ->
                assertContains(assertNotNull(failure.message, stage), "stacked in a vertical split", message = stage)
                assertTrue(wm.tree().windows.none { it.appId == "aw-dock" }, "$stage: the unwind took the dock down: ${describe()}")
            }
            // And one that attached stands beside the surface: base reported success for the dock
            // stacked under it, which is what the readback now refuses.
            dock.getOrNull()?.let { handle ->
                assertEquals(
                    "splith",
                    wm.tree().parentOf(handle.dockId.raw)?.layout,
                    "$stage: a dock that attached stands beside the surface: ${describe()}",
                )
            }
            dock.getOrNull()?.detach()
            val tree = wm.tree()
            if (users != null) {
                assertNotNull(tree.find(users), "$stage: the user's split $users survives: ${describe()}")
            }
            assertEquals(emptyList(), splitMarks(), "$stage: no split mark is left: ${describe()}")
            assertEquals(emptyList(), anchorMarks(), "$stage: no anchor mark is left")
            command("unmark user_before; unmark user_after")
            if (tree.parentOf(app.raw)?.let { it.type == "con" && it.nodes.size == 1 } == true) {
                command("[con_id=${app.raw}] focus; split none")
            }
        }
        assert(made > 0) { "no round had the user's split land first; the sweep tests nothing" }
    }

    /**
     * #254's review: a surface inside a floating split — its second dock's, or the user's own —
     * walks from the floating container to the workspace, where `focus child` finds no tiled
     * window when the workspace holds none and the anchor's `mark` is refused, which ends the
     * message and fails the attach. Each case with and without a tiled window beside it.
     */
    @Test
    fun `a second dock on a floating surface attaches with no tiled window`() =
        floatingSecondDock(tiled = false)

    @Test
    fun `a second dock on a floating surface attaches beside a tiled window`() =
        floatingSecondDock(tiled = true)

    @Test
    fun `a user's floating split with no tiled window survives an attach`() =
        floatingUserSplit(tiled = false)

    @Test
    fun `a user's floating split beside a tiled window survives an attach`() =
        floatingUserSplit(tiled = true)

    private fun floatingSecondDock(tiled: Boolean) = swayTest {
        if (tiled) openSurface("aw-other")
        val app = openFloating("aw-app")
        repeat(3) { cycle ->
            val first = wm.attach(app, dockSpec(), AGENT)
            val second = wm.attach(app, dockSpec(), AGENT)
            assertEquals(emptyList(), anchorMarks(), "cycle ${cycle + 1}: ${describe()}")
            second.detach()
            first.detach()
            assertNotNull(wm.tree().find(app.raw), "cycle ${cycle + 1}: the surface stands")
            assertEquals(emptyList(), anchorMarks(), "cycle ${cycle + 1}: ${describe()}")
        }
    }

    private fun floatingUserSplit(tiled: Boolean) = swayTest {
        if (tiled) openSurface("aw-other")
        val app = openFloating("aw-app")
        command("[con_id=${app.raw}] focus; split vertical")
        val mine = openSurface("aw-mine")
        val users = assertNotNull(wm.tree().parentOf(app.raw), "the user's floating split")
        assertEquals(listOf(app.raw, mine.raw), users.nodes.map { it.id }, describe())
        repeat(3) { cycle ->
            wm.attach(app, dockSpec(), AGENT).detach()
            val after = wm.tree().find(users.id)
            assertEquals(users.layout, after?.layout, "cycle ${cycle + 1}: ${describe()}")
            assertEquals(users.nodes.map { it.id }, after?.nodes?.map { it.id }, describe())
            assertEquals(emptyList(), anchorMarks(), "cycle ${cycle + 1}: ${describe()}")
        }
    }

    /**
     * A split mark on a container no younger than the high-water mark it names is one the
     * message that placed it cannot have made the container for — the user's own, re-laid, or
     * forged. Ignored by every ownership check, and taken off at the next attach.
     */
    @Test
    fun `a split mark on a container older than it names is inert`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        command("[con_id=${app.raw}] focus; split vertical")
        val users = usersSplits(app)
        val baseline = describe()
        val id = users.first().id
        command("[con_id=$id] mark --add _awakener_split_${id}_beef")
        val dock = wm.attach(app, dockSpec(), AGENT)
        assertEquals(users.first().layout, wm.tree().find(id)?.layout, describe())
        assertEquals(emptyList(), splitMarks().filter { it.first == id }, "the inert mark is taken off")
        dock.detach()
        assertEquals(users, usersSplits(app), describe())
        assertEquals(baseline, describe())
    }

    /**
     * A dock a build before #248 attached: its split carries no provenance mark. Under MARKED the
     * detach leaves the surface standing in it rather than guessing whose it is.
     */
    @Test
    fun `a dock whose split carries no mark leaves the split standing`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val dock = wm.attach(app, dockSpec(), AGENT)
        val split = unmarkSplitOf(app)
        dock.detach()
        val tree = wm.tree()
        assertEquals(split, tree.parentOf(app.raw)?.id, "the unmarked split stands: ${describe()}")
        assertEquals(1, singleChildSplits(), describe())
    }

    /** The same dock under SHAPE is flattened by shape, as before #248. */
    @Test
    fun `under SHAPE a dock whose split carries no mark is flattened`() = swayTest {
        store.put(WmFlags.splitOwnership, SplitOwnership.SHAPE)
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val baseline = describe()
        val dock = wm.attach(app, dockSpec(), AGENT)
        unmarkSplitOf(app)
        dock.detach()
        assertEquals(baseline, describe())
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

    /**
     * #171's remaining leak, as the #217 reviewer measured it: two docks on one surface, attached
     * one at a time, then both closed by hand. Each further dock used to wrap the surface in a
     * split of its own, and a hand close runs no awakener code, so every round left one more
     * wrapper — 2 → 6 single-child splits over five rounds. Held to the one a single hand-closed
     * dock leaves, and to a depth that does not move.
     */
    @Test
    fun `two docks closed by hand newest first do not compound`() =
        twoDocksClosedByHand(newestFirst = true)

    @Test
    fun `two docks closed by hand oldest first do not compound`() =
        twoDocksClosedByHand(newestFirst = false)

    /** The control: with the split not shared, the same rounds compound, as on `main`. */
    @Test
    fun `with share_split off two docks closed by hand compound`() = swayTest {
        store.put(WmFlags.shareDockSplit, false)
        openSurface("aw-other")
        val app = openSurface("aw-app")
        repeat(3) {
            val first = wm.attach(app, dockSpec(), AGENT)
            val second = wm.attach(app, dockSpec(), AGENT)
            close(second.dockId)
            close(first.dockId)
        }
        assertEquals(4, singleChildSplits(), describe())
    }

    /**
     * Two docks in one split, torn down in every order: the first detach must leave the split to
     * the dock still standing — `split none` there is refused for the sibling — and the last one
     * must take it back.
     */
    @Test
    fun `two docks detached newest first leave no single-child split`() = cycles { app ->
        val first = wm.attach(app, dockSpec(), AGENT)
        val second = wm.attach(app, dockSpec(), AGENT)
        second.detach()
        first.detach()
    }

    /**
     * The shared split by the other road: a restore lands beside a second dock that was stood up
     * while the first was hidden. Before #171 the first detach then raised "Can only flatten a
     * child container with no siblings" after its kill.
     */
    @Test
    fun `a dock restored beside a second dock detaches cleanly`() = cycles { app ->
        val first = wm.attach(app, dockSpec(), AGENT)
        first.hide()
        val second = wm.attach(app, dockSpec(), AGENT)
        first.show()
        first.detach()
        second.detach()
    }

    /** Both docks orphaned at once: the sweep reaps them and nothing of the split is left. */
    @Test
    fun `a surface closing under two docks, cycled, leaves no single-child split`() {
        store.put(WmFlags.sweepOnClose, false)
        cycles(freshSurface = true) { app ->
            val first = wm.attach(app, dockSpec(), AGENT)
            val second = wm.attach(app, dockSpec(), AGENT)
            close(app)
            wm.reapOrphans()
            awaitGone(first.dockId)
            awaitGone(second.dockId)
        }
    }

    /**
     * A split the user made is never awakener's to take. The surface shares a `splith` with a
     * window of the user's, and every lifecycle above runs inside it: the user's container must
     * still be standing, holding the surface's side and the user's window, after every round, and
     * the second dock must not be let into it.
     */
    @Test
    fun `a split the user made survives every lifecycle and is never shared`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        command("[con_id=${app.raw}] focus; split horizontal")
        val mine = openSurface("aw-mine")
        val users = assertNotNull(wm.tree().parentOf(mine.raw), "the user's split")
        assertEquals("splith[tabbed[aw-other splith[aw-app aw-mine]]]", describe(), "baseline")
        val baseline = describe()

        fun assertUsersSplitStands(stage: String) = runBlocking {
            val tree = wm.tree()
            val node = assertNotNull(tree.find(users.id), "$stage: the user's split: ${describe()}")
            assertEquals("splith", node.layout, "$stage: ${describe()}")
            assertEquals(2, node.nodes.size, "$stage: ${describe()}")
            assertEquals(users.id, tree.parentOf(mine.raw)?.id, "$stage: ${describe()}")
        }

        repeat(CYCLES) { cycle ->
            val first = wm.attach(app, dockSpec(), AGENT)
            val second = wm.attach(app, dockSpec(), AGENT)
            val tree = wm.tree()
            assertEquals(
                false,
                tree.parentOf(second.dockId.raw)?.id == users.id,
                "cycle ${cycle + 1}: the second dock is kept out of the user's split: ${describe()}",
            )
            assertUsersSplitStands("cycle ${cycle + 1}, docked")
            close(second.dockId)
            close(first.dockId)
            assertUsersSplitStands("cycle ${cycle + 1}, closed by hand")
            assertEquals(1, singleChildSplits(), "cycle ${cycle + 1}: ${describe()}")

            val again = wm.attach(app, dockSpec(), AGENT)
            again.hide()
            again.show()
            again.detach()
            assertUsersSplitStands("cycle ${cycle + 1}, detached")
            assertEquals(baseline, describe(), "cycle ${cycle + 1}")
        }
    }

    private fun usersSplitSurvives(split: String) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        command("[con_id=${app.raw}] focus; $split")
        val users = usersSplits(app)
        assertEquals(true, users.isNotEmpty(), "the user's split stands: ${describe()}")
        val baseline = describe()

        suspend fun assertStands(stage: String) {
            assertEquals(users, usersSplits(app), "$stage: ${describe()}")
            assertEquals(baseline, describe(), stage)
        }

        suspend fun assertDockedInOwnSplit(dock: DockHandle, stage: String) {
            val tree = wm.tree()
            val own = assertNotNull(tree.parentOf(dock.dockId.raw), "$stage: dock's split")
            assertEquals(
                listOf(app.raw, dock.dockId.raw),
                own.nodes.map { it.id },
                "$stage: the dock stands beside the surface in a split of its own: ${describe()}",
            )
            assertEquals(users.first().id, tree.parentOf(own.id)?.id, "$stage: ${describe()}")
            assertEquals(users.first().layout, tree.find(users.first().id)?.layout, "$stage: ${describe()}")
        }

        repeat(3) { cycle ->
            val stage = "cycle ${cycle + 1}"
            val dock = wm.attach(app, dockSpec(), AGENT)
            assertDockedInOwnSplit(dock, "$stage, attached")
            dock.detach()
            assertStands("$stage, detached")

            val shown = wm.attach(app, dockSpec(), AGENT)
            shown.hide()
            assertStands("$stage, hidden")
            shown.show()
            assertDockedInOwnSplit(shown, "$stage, shown")
            shown.hide()
            shown.show()
            shown.detach()
            assertStands("$stage, hidden, shown and detached")

            val hidden = wm.attach(app, dockSpec(), AGENT)
            hidden.hide()
            hidden.detach()
            assertStands("$stage, detached while hidden")
        }
    }

    /** One of the user's split containers: what #248 says has to survive a detach. */
    private data class UserSplit(val id: Long, val layout: String?, val children: List<Long>)

    /** The split containers above [app], innermost first. */
    private suspend fun usersSplits(app: SurfaceId): List<UserSplit> {
        val tree = wm.tree()
        return generateSequence(tree.parentOf(app.raw)) { tree.parentOf(it.id) }
            .takeWhile { it.type == "con" && it.layout?.startsWith("split") == true }
            .map { UserSplit(it.id, it.layout, it.nodes.map { child -> child.id }) }
            .toList()
    }

    /** Every `_awakener_split_` mark in the tree, with the `con_id` it is on. */
    private suspend fun splitMarks(): List<Pair<Long, String>> {
        fun walk(node: Node): List<Pair<Long, String>> =
            node.marks.filter { it.startsWith("_awakener_split_") }.map { node.id to it } +
                node.children.flatMap { walk(it) }
        return walk(wm.tree())
    }

    /** Every `_awakener_anchor_` mark in the tree, with the `con_id` it is on. */
    private suspend fun anchorMarks(): List<Pair<Long, String>> {
        fun walk(node: Node): List<Pair<Long, String>> =
            node.marks.filter { it.startsWith("_awakener_anchor_") }.map { node.id to it } +
                node.children.flatMap { walk(it) }
        return walk(wm.tree())
    }

    private fun Node.holdsMark(mark: String): Boolean =
        mark in marks || children.any { it.holdsMark(mark) }

    /** The `con_id` of the node carrying [mark], or null. */
    private fun Node.holderOf(mark: String): Long? =
        if (mark in marks) id else children.firstNotNullOfOrNull { it.holderOf(mark) }

    /** Single-child splits on workspace 1 carrying no `_awakener_split_` mark, by `con_id`. */
    private suspend fun unmarkedSingleChildSplits(): List<Long> {
        fun walk(node: Node): List<Long> =
            listOfNotNull(
                node.id.takeIf {
                    node.type == "con" && node.layout?.startsWith("split") == true &&
                        node.nodes.size == 1 && node.marks.none { it.startsWith("_awakener_split_") }
                },
            ) + node.children.flatMap { walk(it) }
        return walk(workspace())
    }

    /** Takes the provenance mark off [app]'s split, as a build before #248 never put it on. */
    private suspend fun unmarkSplitOf(app: SurfaceId): Long {
        val split = assertNotNull(wm.tree().parentOf(app.raw), "the dock's split")
        val mark = split.marks.single()
        assertEquals(true, mark.startsWith("_awakener_split_"), "${split.marks}: ${describe()}")
        command("unmark $mark")
        return split.id
    }

    /** [chainCollapsedBy]'s chain under the default MARKED: every split in it still stands. */
    private fun chainSurvives(act: suspend (SurfaceId) -> Unit) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        command("[con_id=${app.raw}] focus")
        repeat(CHAIN) { command("split horizontal; focus parent") }
        val chain = usersSplits(app)
        assertEquals(CHAIN, chain.size, "the chain stands: ${describe()}")
        val baseline = describe()

        act(app)

        assertEquals(chain, usersSplits(app), describe())
        assertEquals(baseline, describe())
    }

    private fun twoDocksClosedByHand(newestFirst: Boolean) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        var depth: Int? = null
        repeat(CYCLES) { cycle ->
            val first = wm.attach(app, dockSpec(), AGENT)
            val second = wm.attach(app, dockSpec(), AGENT)
            if (newestFirst) {
                close(second.dockId)
                close(first.dockId)
            } else {
                close(first.dockId)
                close(second.dockId)
            }
            assertEquals(1, singleChildSplits(), "after cycle ${cycle + 1}: ${describe()}")
            val now = depth()
            assertEquals(depth ?: now, now, "depth after cycle ${cycle + 1}: ${describe()}")
            depth = now
        }
        wm.attach(app, dockSpec(), AGENT).detach()
        assertEquals(0, singleChildSplits(), "the next detach clears it: ${describe()}")
    }

    /** How deep the deepest window on workspace 1 sits. */
    private suspend fun depth(): Int {
        fun depth(node: Node): Int = if (node.nodes.isEmpty()) 0 else 1 + node.nodes.maxOf { depth(it) }
        return depth(workspace())
    }

    /**
     * The control for the concurrent case below: [CONCURRENT] docks on one surface, attached one
     * after another, then all detached in the order they were attached. Several docks on one
     * surface is a shape the interface permits (`invoke.when_animated=SECOND_DOCK` asks for it), so
     * a serialised attach has to leave a tree this can take back.
     */
    @Test
    fun `several docks attached one at a time on one surface tear down cleanly`() =
        rounds { app, managers ->
            managers.map { it.attach(app, dockSpec(), AGENT) }
        }

    /**
     * #199's second consequence. Separate managers have separate in-process locks, which is what
     * separate `awakener-invoke` processes are, so this releases [CONCURRENT] of them off one gate
     * against one surface. Before the cross-process attach lock the docks were cross-adopted, each
     * round nested the surface one split deeper, and detach's `split none` was refused with "Can
     * only flatten a child container with no siblings".
     */
    @Test
    fun `concurrent attaches on one surface tear down with no nesting across rounds`() =
        rounds { app, managers ->
            val gate = CompletableDeferred<Unit>()
            val attaches = managers.map { manager ->
                async(Dispatchers.Default) {
                    gate.await()
                    manager.attach(app, dockSpec(), AGENT)
                }
            }
            gate.complete(Unit)
            attaches.awaitAll()
        }

    /**
     * #226. With `workspace_layout tabbed` and the surface the only window on its workspace, the
     * tab strip is a `tabbed` container holding nothing but attach's split — so a `split none`
     * that walks the whole single-child run, which is what sway's does, takes the tab strip too.
     * The hide has to stop at the split: a window opened while the panel is hidden becomes a tab
     * of its own, and the restore splits the surface again inside its tab.
     */
    @Test
    fun `a hide on a surface alone in its tab keeps the tab, and the next window joins it`() =
        swayTest {
            val app = openSurface("aw-app")
            assertEquals("splith[tabbed[aw-app]]", describe(), "baseline")
            val dock = wm.attach(app, dockSpec(), AGENT)
            assertEquals("splith[tabbed[splith[aw-app aw-dock]]]", describe(), "docked")

            dock.hide()
            assertEquals("splith[tabbed[aw-app]]", describe(), "the hide takes attach's split only")

            openSurface("aw-new")
            assertEquals("splith[tabbed[aw-app aw-new]]", describe(), "a window opened is a tab")

            dock.show()
            assertEquals(
                "splith[tabbed[splith[aw-app aw-dock] aw-new]]",
                describe(),
                "the restore splits the surface inside its own tab",
            )
        }

    /** The same lifecycles as above with no second tab, so attach's split is the tab's only child. */
    @Test
    fun `attach, hide, show and detach cycles alone in a tab keep the tab`() =
        cycles(alone = true) { app ->
            val dock = wm.attach(app, dockSpec(), AGENT)
            dock.hide()
            dock.show()
            dock.detach()
        }

    /** A visible detach's normalisation is the same `split none`, so it has the same reach. */
    @Test
    fun `attach and detach cycles alone in a tab keep the tab`() = cycles(alone = true) { app ->
        wm.attach(app, dockSpec(), AGENT).detach()
    }

    /** And an unwound attach's, which flattens the split it made around the surface. */
    @Test
    fun `an attach whose dock never maps, alone in a tab, keeps the tab`() {
        store.put(WmFlags.mapWaitMs, NEVER_MAPS_WAIT_MS)
        cycles(alone = true) { app ->
            assertFailsWith<IllegalStateException> {
                wm.attach(app, DockSpec("aw-never", "sleep 3600"), AGENT)
            }
        }
    }

    /**
     * A chain of splits inside a lone tab still goes under SHAPE, and the tab still stays. Under
     * MARKED the chain is nobody's awakener can prove, and stands like the one above.
     */
    @Test
    fun `hide collapses a chain inside a lone tab and keeps the tab`() = swayTest {
        store.put(WmFlags.splitOwnership, SplitOwnership.SHAPE)
        val app = openSurface("aw-app")
        command("[con_id=${app.raw}] focus")
        repeat(CHAIN) { command("split horizontal; focus parent") }
        val dock = wm.attach(app, dockSpec(), AGENT)

        dock.hide()

        assertEquals("splith[tabbed[aw-app]]", describe())
    }

    /**
     * #224. A dock standing directly on the workspace — the shape #226's collapsed tab left
     * behind, and one a user flattening the tab by hand still produces. Its parent is no split
     * attach made, so a window beside it is no survivor of one: flattening it is refused, and
     * that refusal used to come back as the reap failing after the dock had already gone.
     */
    @Test
    fun `a dock standing on the workspace itself is reaped cleanly`() = swayTest {
        // The sweep is called below, rather than left to the collector, so its outcome is seen.
        store.put(WmFlags.sweepOnClose, false)
        val app = openSurface("aw-app")
        // The user's own flatten of the lone tab, so attach finds the surface on the workspace.
        command("[con_id=${app.raw}] split none")
        val dock = wm.attach(app, dockSpec(), AGENT)
        openSurface("aw-other")
        assertEquals(
            "workspace",
            wm.tree().parentOf(dock.dockId.raw)?.type,
            "the collapsed shape: ${describe()}",
        )

        close(app)
        wm.reapOrphans()

        assertEquals("splith[aw-other]", describe())
    }

    /**
     * The general half of #224, met by #256: the surface closes out of a split that two other
     * windows have joined. sway refused to flatten a split still holding two of them, and the
     * reap reported that as a cleanup that failed after the dock had gone. The split now holds
     * nothing but the user's windows, so it is theirs: the reap takes the dock and leaves the split
     * standing, with no mark of awakener's on it.
     */
    @Test
    fun `a reap out of a split two other windows joined leaves the split to them`() = swayTest {
        store.put(WmFlags.sweepOnClose, false)
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val dock = wm.attach(app, dockSpec(), AGENT)
        command("[con_id=${app.raw}] focus")
        val in1 = openSurface("aw-in1")
        val in2 = openSurface("aw-in2")
        val split = wm.tree().parentOf(dock.dockId.raw)?.id
        close(app)

        wm.reapOrphans()

        val tree = wm.tree()
        assertEquals(null, tree.find(dock.dockId.raw), "the dock is gone: ${describe()}")
        assertEquals(listOf(in1.raw, in2.raw), tree.find(assertNotNull(split))?.nodes?.map { it.id }, describe())
        assertEquals(emptyList(), splitMarks(), "no split mark is left: ${describe()}")
    }

    // -- #256: a window of the user's inside the dock's split -------------------------------

    /**
     * #256, the deliberate half: with the dock up, the user moves one of their windows into the
     * dock's split. `split none` is refused on a split that still holds two windows, so the detach
     * failed and left the split standing, marked as awakener's, around the user's window. The
     * split now holds user content and is left as theirs: the dock goes, the split stays, and it
     * carries no mark of awakener's. Two plain cycles after it must leave that shape alone.
     */
    @Test
    fun `a window moved into the dock's split stays when the dock detaches`() =
        movedIn(floating = false) { it.detach() }

    @Test
    fun `a window moved into the dock's floating split stays when the dock detaches`() =
        movedIn(floating = true) { it.detach() }

    /** Hidden first: the hide leaves the split to the user, and the detach after it is a kill. */
    @Test
    fun `a window moved into the dock's split stays through a hide and a detach`() =
        movedIn(floating = false) { dock -> hideDisowns(dock); dock.detach() }

    @Test
    fun `a window moved into the dock's floating split stays through a hide and a detach`() =
        movedIn(floating = true) { dock -> hideDisowns(dock); dock.detach() }

    /**
     * Hidden and shown again: the show treats the split as the user's, so the dock comes back
     * in a split of awakener's own around the surface inside it (tiled) or beside the surface in
     * the user's floating container (floating), and the detach takes only that back.
     */
    @Test
    fun `a window moved into the dock's split stays through a hide, a show and a detach`() =
        movedIn(floating = false) { dock -> hideDisowns(dock); showBeside(dock); dock.detach() }

    @Test
    fun `a window moved into the dock's floating split stays through a hide, a show and a detach`() =
        movedIn(floating = true) { dock -> hideDisowns(dock); showBeside(dock); dock.detach() }

    /**
     * The commonest way in on a real desktop: a window opened while the dock is focused maps
     * beside it, inside its split.
     */
    @Test
    fun `a window opened beside the focused dock stays when the dock detaches`() = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val dock = wm.attach(app, dockSpec(), AGENT)
        command("[con_id=${dock.dockId.raw}] focus")
        val mine = openSurface("aw-mine")
        val split = wm.tree().parentOf(dock.dockId.raw)?.id
        assertEquals(split, wm.tree().parentOf(mine.raw)?.id, "it mapped in the dock's split: ${describe()}")
        dock.detach()
        assertUsersSplitHolds(app, mine, "after the detach")
    }

    /** The alternative: wm.dock.evict_on_detach moves the window out, and the attach is undone. */
    @Test
    fun `under evict_on_detach a window moved into the dock's split is moved out and the split goes`() =
        swayTest {
            store.put(WmFlags.evictOnDetach, true)
            openSurface("aw-other")
            val app = openSurface("aw-app")
            val mine = openSurface("aw-mine")
            val baseline = describe()
            repeat(3) { cycle ->
                val dock = wm.attach(app, dockSpec(), AGENT)
                moveBeside(mine, app)
                assertEquals(wm.tree().parentOf(dock.dockId.raw)?.id, wm.tree().parentOf(mine.raw)?.id, describe())
                dock.detach()
                assertEquals(baseline, describe(), "cycle ${cycle + 1}")
                assertEquals(emptyList(), splitMarks(), "cycle ${cycle + 1}: no split mark is left")
            }
        }

    /**
     * #260's review: eviction kept only the surface and the dock it was for, so a second dock of
     * the same surface sharing the split (wm.dock.share_split) was evicted into a tab of its own
     * when the first one went. It has been so for wm.dock.evict_on_hide since #187.
     */
    @Test
    fun `under evict_on_detach a second dock sharing the split stays docked`() =
        secondDockStaysDocked(WmFlags.evictOnDetach) { first -> first.detach() }

    @Test
    fun `under evict_on_hide a second dock sharing the split stays docked`() =
        secondDockStaysDocked(WmFlags.evictOnHide) { first -> first.hide(); first.detach() }

    private fun secondDockStaysDocked(
        flag: Flag<Boolean>,
        goes: suspend (DockHandle) -> Unit,
    ) = swayTest {
        store.put(flag, true)
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val baseline = describe()
        val first = wm.attach(app, dockSpec(), AGENT)
        val second = wm.attach(app, dockSpec(), AGENT)
        val split = wm.tree().parentOf(app.raw)?.id
        assertEquals(split, wm.tree().parentOf(second.dockId.raw)?.id, "the docks share a split: ${describe()}")
        goes(first)
        assertEquals(split, wm.tree().parentOf(second.dockId.raw)?.id, "the second dock stays docked: ${describe()}")
        assertEquals(split, wm.tree().parentOf(app.raw)?.id, describe())
        second.detach()
        assertEquals(baseline, describe())
    }

    /**
     * #260's review: a window moved in, a second press, and the first dock hidden and shown. The
     * show brings the first dock back into the second's split, inside the one the user's window
     * is in, and the hide had cleared the mark only off the surface's own split — not off the one
     * the dock had been standing in — so that outer split kept awakener's mark for good.
     */
    @Test
    fun `a second dock and a hide and show leave no mark on the user's split, newest first`() =
        secondDockAfterMoveIn(newestFirst = true)

    @Test
    fun `a second dock and a hide and show leave no mark on the user's split, oldest first`() =
        secondDockAfterMoveIn(newestFirst = false)

    private fun secondDockAfterMoveIn(newestFirst: Boolean) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val mine = openSurface("aw-mine")
        val first = wm.attach(app, dockSpec(), AGENT)
        moveBeside(mine, app)
        val second = wm.attach(app, dockSpec(), AGENT)
        assertEquals(true, first.hide(), "the hide")
        assertEquals(true, first.show(), "the show")
        (if (newestFirst) listOf(second, first) else listOf(first, second)).forEach { it.detach() }
        assertUsersSplitHolds(app, mine, "after both docks went")
    }

    private fun movedIn(floating: Boolean, act: suspend (DockHandle) -> Unit) = swayTest {
        openSurface("aw-other")
        val app = if (floating) openFloating("aw-app") else openSurface("aw-app")
        val mine = openSurface("aw-mine")
        val dock = wm.attach(app, dockSpec(), AGENT)
        val split = assertNotNull(wm.tree().parentOf(dock.dockId.raw), "the dock's split").id
        moveBeside(mine, app)
        assertEquals(split, wm.tree().parentOf(mine.raw)?.id, "moved into the dock's split: ${describe()}")
        act(dock)
        assertUsersSplitHolds(app, mine, "after the dock went")
        val users = wm.tree().parentOf(app.raw)?.id
        val shape = describe()
        repeat(2) { cycle ->
            wm.attach(app, dockSpec(), AGENT).detach()
            assertEquals(users, wm.tree().parentOf(app.raw)?.id, "cycle ${cycle + 1}: ${describe()}")
            assertEquals(shape, describe(), "cycle ${cycle + 1}")
            assertEquals(emptyList(), splitMarks(), "cycle ${cycle + 1}: no split mark is left")
        }
    }

    private suspend fun hideDisowns(dock: DockHandle) {
        assertEquals(true, dock.hide(), "the hide")
        assertEquals(emptyList(), splitMarks(), "a split holding a user's window is not awakener's: ${describe()}")
    }

    private suspend fun showBeside(dock: DockHandle) {
        assertEquals(true, dock.show(), "the show")
        assertEquals(dock.surface.raw, wm.tree().parentOf(dock.dockId.raw)?.nodes?.firstOrNull()?.id, describe())
    }

    /** The dock is gone, [mine] still shares a container with [app], and no mark of ours is left. */
    private suspend fun assertUsersSplitHolds(app: SurfaceId, mine: SurfaceId, stage: String) {
        val tree = wm.tree()
        assertEquals(emptyList(), tree.windows.filter { it.appId == "aw-dock" }.map { it.id }, "$stage: ${describe()}")
        assertNotNull(tree.find(app.raw), "$stage: the surface stands")
        assertEquals(tree.parentOf(app.raw)?.id, tree.parentOf(mine.raw)?.id, "$stage: ${describe()}")
        assertEquals(emptyList(), splitMarks(), "$stage: no split mark is left: ${describe()}")
        assertEquals(emptyList(), anchorMarks(), "$stage: no anchor is left")
    }

    /**
     * #256, the race the #254 review found: a second connection moves a user's window beside the
     * surface 0–10 ms into each attach, so it lands in the tab, in the split as it is made, or in
     * the split once the dock is in it. Every detach must succeed, and leave the surface and the
     * window standing with no mark of awakener's on anything. Failures are counted rather than
     * thrown at the first, so a red run says how many rounds of [MOVE_IN_ROUNDS] failed.
     */
    @Test
    fun `a window another client moves in as an attach starts never fails the detach`() = swayTest {
        val other = openSurface("aw-other")
        val app = openSurface("aw-app")
        val baseline = describe()
        val failed = mutableListOf<String>()
        var inside = 0
        repeat(MOVE_IN_ROUNDS) { round ->
            val offset = (round % MOVE_IN_STEPS) * MOVE_IN_STEP_NS
            val stage = "round ${round + 1}, moved after ${offset / 1000}us"
            assertEquals(baseline, describe(), "$stage, before")
            command("[con_id=${other.raw}] focus")
            val mine = openSurface("aw-mine$round")
            command("[con_id=${app.raw}] focus")
            val attach = scope.async { wm.attach(app, dockSpec(), AGENT) }
            LockSupport.parkNanos(offset)
            moveBeside(mine, app)
            val dock = attach.await()
            wm.tree().let { if (it.parentOf(mine.raw)?.id == it.parentOf(dock.dockId.raw)?.id) inside++ }
            val problems = listOfNotNull(runCatching { dock.detach() }.exceptionOrNull()?.let { "detach: ${it.message}" })
                .toMutableList()
            val tree = wm.tree()
            if (tree.find(app.raw) == null || tree.find(mine.raw) == null) problems += "lost a window"
            splitMarks().takeIf { it.isNotEmpty() }?.let { marks ->
                problems += "marks $marks on ${describe()}"
                command(marks.joinToString("; ") { (id, mark) -> "[con_id=$id] unmark $mark" })
            }
            if (anchorMarks().isNotEmpty()) problems += "anchors ${anchorMarks()}"
            if (problems.isNotEmpty()) failed += "$stage: ${problems.joinToString("; ")}"
            close(mine)
            if (wm.tree().parentOf(app.raw)?.let { it.type == "con" && it.nodes.size == 1 } == true) {
                command("[con_id=${app.raw}] focus; split none")
            }
        }
        assert(inside > 0) { "no round had the window land in the dock's split; the sweep tests nothing" }
        assertEquals(emptyList(), failed, "${failed.size} of $MOVE_IN_ROUNDS rounds failed ($inside landed inside)")
    }

    /** Moves [window] beside [app], into whatever holds it — the dock's split, while docked. */
    private suspend fun moveBeside(window: SurfaceId, app: SurfaceId) = command(
        "[con_id=${app.raw}] mark --add _test_beside; " +
            "[con_id=${window.raw}] move window to mark _test_beside; unmark _test_beside",
    )

    // -- helpers ------------------------------------------------------------------------

    private fun swayTest(body: suspend CoroutineScope.() -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking { body() }
    }

    /**
     * [ROUNDS] rounds of: [CONCURRENT] fresh managers stand docks up beside one surface via
     * [attachAll], every handle is detached, and the workspace must be back to its baseline with
     * no single-child split and no nesting. Checked every round, because the defect this guards
     * grows by one level per round.
     */
    private fun rounds(
        attachAll: suspend CoroutineScope.(SurfaceId, List<SwayWindowManager>) -> List<DockHandle>,
    ) = swayTest {
        openSurface("aw-other")
        val app = openSurface("aw-app0")
        val baseline = describe()
        for (round in 1..ROUNDS) {
            val managers = List(CONCURRENT) {
                SwayWindowManager({ sway.connection() }, store, registry, scope)
            }
            try {
                val docks = attachAll(app, managers)
                val ids = docks.map { it.dockId }
                assertEquals(CONCURRENT, ids.toSet().size, "round $round: distinct docks $ids")
                val tree = wm.tree()
                ids.forEach { id ->
                    val node = assertNotNull(tree.find(id.raw), "round $round: dock ${id.raw}")
                    assertEquals(1, node.marks.size, "round $round: dock ${id.raw} marks ${node.marks}")
                }
                val unclaimed = tree.windows.filter { it.appId == "aw-dock" && SurfaceId(it.id) !in ids }
                assertEquals(emptyList(), unclaimed.map { it.id }, "round $round: unclaimed docks; ${describe()}")
                docks.forEach { it.detach() }
                assertEquals(0, singleChildSplits(), "after round $round: ${describe()}")
                assertEquals(baseline, describe(), "after round $round")
            } finally {
                managers.forEach { runCatching { it.close() } }
            }
        }
    }

    /**
     * Runs [cycle] [CYCLES] times beside a second tab — or, [alone], with the surface the only
     * window on the workspace — asserting after each one that no
     * single-child split is left and — where the surface outlives the cycle — that the workspace
     * is back to exactly the shape it started in. The count alone would pass a cycle that put the
     * dock somewhere wrong without leaving an only child behind.
     */
    private fun cycles(
        freshSurface: Boolean = false,
        alone: Boolean = false,
        cycle: suspend (SurfaceId) -> Unit,
    ) =
        swayTest {
            if (!alone) openSurface("aw-other")
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

    /** A window that floats from the moment it maps, by a `for_window` rule. */
    private suspend fun openFloating(appId: String): SurfaceId {
        command("for_window [app_id=\"^$appId\$\"] floating enable")
        val id = openSurface(appId)
        val floated = withTimeoutOrNull(WAIT_MS) {
            while (wm.tree().find(id.raw)?.type != "floating_con") delay(POLL_MS)
            true
        }
        assertNotNull(floated, "window ${id.raw} never floated")
        return id
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
        const val ROUNDS = 4
        const val CONCURRENT = 4

        /** Long enough for an exec to be accepted, short enough that five failures stay cheap. */
        const val NEVER_MAPS_WAIT_MS = 600L
        const val INTERRUPT_ROUNDS = 320
        const val INTERRUPT_DELAYS_MS = 16L
        const val LATE_DOCK_MS = 150L
        const val SLOW_DOCK_S = "0.4"
        const val FOCUS_MOVE_MS = 150L
        const val KILL_RACE_ROUNDS = 100
        const val KILL_RACE_STEPS = 20
        const val KILL_RACE_STEP_NS = 250_000L
        const val USER_SPLIT_ROUNDS = 400
        const val USER_SPLIT_STEPS = 400
        const val USER_SPLIT_STEP_NS = 25_000L
        const val MOVE_IN_ROUNDS = 400
        const val MOVE_IN_STEPS = 400
        const val MOVE_IN_STEP_NS = 25_000L
        val AGENT = AgentId("agent-1")
    }
}
