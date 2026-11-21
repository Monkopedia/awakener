package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import java.util.Collections.synchronizedList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Hiding a dock and bringing it back, against a real headless sway.
 *
 * The whole feature is a claim about what a compositor does — that a window can leave the layout
 * with its client still running, that its mark survives the trip, that it comes back where it was
 * — and every one of those is a fact about sway rather than about this code. A stub would answer
 * whatever it was written to answer.
 *
 * **What Jason asked for, 2026-08-23:** *"if we do Super A and the instance is present, we should
 * probably shut it down, or actually it's probably more likely that we should just hide the
 * window. What would it take for us to hide the window and then be able to bring it back if you
 * hit Super A again?"* The dock is a terminal running `claude`, so the difference between the two
 * halves of that sentence is a live session and everything the agent has accumulated. This suite
 * is what holds the second half: **the process must still be running while the window is gone**,
 * and that is asserted directly against the process table rather than inferred from the tree.
 *
 * Exercised against **sway 1.12** on the headless wlroots backend, at scale 1 here and at
 * scale 1.5 by [SwayHideScaledTest], which is [outputConfig]'s only use.
 */
open class SwayHideTest {
    /** Extra sway configuration for the session, which is how a subclass sets the output scale. */
    protected open val outputConfig: String = ""

    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wmScope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var registry: BindingStore
    private lateinit var stateDir: Path

    private val enabled get() = SwayHarness.available()

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start(outputConfig)
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-hide")
        registry = bindingStore()
        wmScope = managerScope()
        wm = SwayWindowManager({ sway.connection() }, store, registry, wmScope)
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    /**
     * The claim the whole feature rests on: the window goes and the agent does not.
     *
     * The process assertion is made against `ProcessHandle` rather than against anything sway
     * says, because a tree with no dock node in it looks identical whether the panel was hidden or
     * killed — which is exactly the difference between this feature and the one Jason first
     * suggested and then talked himself out of.
     *
     * **It is waited on, not sampled (#168).** A kill is asynchronous — sway asks the client to
     * close and the client exits when it gets round to it — so a check made the instant `hide`
     * returns sees a live process whether or not something is killing it. The first version
     * sampled: with `hide` mutated to `kill` the panel as it returns and a gone dock reading as
     * hidden, it went red 4 runs in 10, only when the kill won the race; a kill 300ms late it
     * never caught. So the assertion is that [awaitProcessGone] runs out its whole bound without
     * seeing the process leave, which goes red on both every time.
     *
     * It is not the only guard on this property, and does not claim to be the primary one:
     * `DockHandle.hidden` going false for a dock no longer in the tree catches a kill that has
     * landed by the time it is asked, and `:cli`'s `AwakeningSwayTest` toggle test holds the pid
     * equal across both presses, which a panel killed and re-stood cannot satisfy. What this adds
     * is the case those two can miss — a kill that lands after the hide reported success.
     */
    @Test
    fun `hiding a dock takes the window out of the layout and leaves its process running`() =
        swayTest {
            val app = openSurface("aw-app1")
            val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
            val pid = assertNotNull(pidOf(dock.dockId), "the panel has a process behind it")
            assertTrue(inTab(app, dock.dockId), "it starts docked beside its surface")

            assertTrue(dock.hide(), "the hide is what changed things")

            assertFalse(inTab(app, dock.dockId), "the window has left the surface's tab")
            assertTrue(dock.hidden(), "and it is in the scratchpad")
            assertFalse(
                awaitProcessGone(pid),
                "the agent behind the panel is still running ${WAIT_MS}ms on, which is the " +
                    "entire point: a dock is a terminal with a live claude session in it, and " +
                    "killing it would destroy the accumulated model this project exists to keep",
            )
        }

    /**
     * The other half of the round trip, asserted on geometry rather than on presence.
     *
     * `scratchpad show` alone returns the window **floating** — measured on sway 1.12 as
     * `type=floating_con`, `floating=user_on`, centred at roughly half the output — so a restore
     * that stopped at the one obvious command would leave the panel hovering over the surface. The
     * width assertion is what makes that visible from a test: 30 ppt of a 1280-wide output is 384,
     * and a floating panel is neither.
     */
    @Test
    fun `showing a hidden dock puts it back beside its surface at its configured width`() =
        swayTest {
            val app = openSurface("aw-app1")
            val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
            val docked = geometryOf(dock.dockId)
            val surfaced = geometryOf(app)

            dock.hide()
            assertTrue(dock.show(), "the show is what changed things")

            assertFalse(dock.hidden())
            assertTrue(inTab(app, dock.dockId), "it is back inside the surface's tab")
            assertEquals(
                docked,
                geometryOf(dock.dockId),
                "and at exactly the size and place it had before, which is what the " +
                    "`floating disable` and `resize set width` after `scratchpad show` are for",
            )
            assertEquals(surfaced, geometryOf(app), "the surface got its width back too")
        }

    /**
     * **#172, and the only shape of test that can see it.** Jason: *"there is a bit of a flicker in
     * the toggle when it happens."*
     *
     * A `get_tree` before and a `get_tree` after proves nothing here, because both endpoints were
     * already correct while the defect was live: the panel arrived at the right place, having been
     * *drawn* somewhere else twice on the way. So this samples the compositor continuously across
     * a run of toggles and asserts on the whole census rather than on the ends — every reading of
     * the dock has to be either hidden or finished, and anything else is a layout that reached the
     * screen.
     *
     * Measured on this sampler with the un-batched sequence put back, everything else unchanged:
     * **39 of 218 readings were an intermediate layout** — 23 caught the panel visible, *floating*
     * and centred over the surface (`w≈640 x=319`) and 16 caught it tiled 50/50 (`w=640 x=640`)
     * on the way to `w=384 x=896`. `scratchpad show` reveals the window floating, and the commands
     * that re-tile it were separate IPC round trips which sway presents one at a time; the poll for
     * "has it left the scratchpad yet" then ran for the length of the floating frame, with the user
     * looking at it.
     *
     * **The sampler is fast enough because the un-batched code fails this test**, not because a
     * rate was asserted: the frames the defect produces are the ones it catches. That is the
     * negative control, and it is the only thing that makes a count of zero mean anything.
     */
    @Test
    fun `a restore presents no intermediate layout on the way to its final one`() = swayTest {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val landed = assertNotNull(frameOf(dock.dockId), "the panel is on screen after an attach")
        dock.hide()
        assertTrue(dock.hidden(), "and off it again before the restore under test")

        val census = sampling(dock.dockId) {
            repeat(TOGGLES) {
                assertTrue(dock.show(), "the show is what changed things")
                assertTrue(dock.hide(), "and the hide is, on the way to the next one")
            }
            assertTrue(dock.show(), "ending on screen, so the census closes on the final layout")
        }

        assertEquals(
            HIDDEN,
            census.first(),
            "the sampler was already running while the panel was still hidden, so it cannot " +
                "have missed the transition by starting late. Census:\n${report(census)}",
        )
        assertEquals(
            landed,
            census.last(),
            "and it was still running once the panel had settled. Census:\n${report(census)}",
        )
        assertEquals(
            listOf(HIDDEN, landed).sorted(),
            census.distinct().sorted(),
            "every reading of the panel across the restore is either hidden or the layout it " +
                "ends at. Any third entry is a layout that was presented to the user — which is " +
                "the flicker, and it is a real frame rather than a rendering artefact. " +
                "${census.size} readings:\n${report(census)}",
        )
    }

    /**
     * `wm.dock.side=LEFT` is the one command in the restore that is not always issued, and
     * batching the sequence is exactly the change that could drop it.
     *
     * There was no coverage of the flag on this path at all before #172 — a restore that silently
     * put a left-hand panel back on the right would have been green — so this is the guard rather
     * than a re-check. Asserted on the geometry the user sees: at 30 ppt of a 1280-wide output the
     * panel is 384 wide, and the whole question is whether it starts at x=0 or at x=896.
     */
    @Test
    fun `a restore puts a left-hand dock back on the left`() = swayTest {
        store.put(WmFlags.dockSide, DockSide.LEFT)
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val docked = geometryOf(dock.dockId)

        dock.hide()
        assertTrue(dock.show())

        assertEquals(
            docked,
            geometryOf(dock.dockId),
            "the `move left` the batch only carries under wm.dock.side=LEFT survived it",
        )
        assertTrue(
            geometryOf(dock.dockId).startsWith("x=0 "),
            "and it is genuinely on the left rather than merely back where it started: " +
                geometryOf(dock.dockId),
        )
    }

    /**
     * Both verbs are idempotent, which is what stops two presses landing at once from cancelling
     * each other out. The return value is the observable: it says whether *this* call is what
     * changed the world.
     */
    @Test
    fun `hide and show report whether they were the call that changed anything`() = swayTest {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))

        assertFalse(dock.show(), "showing a dock that is already standing does nothing")
        assertTrue(dock.hide())
        assertFalse(dock.hide(), "and hiding one that is already hidden does nothing")
        assertTrue(dock.show())
    }

    /**
     * `floating disable` re-tiles beside whatever is **focused**, not beside where the window came
     * from — measured on sway 1.12, and the reason `showDock` focuses the surface first.
     *
     * Without that step a restore issued while the user is looking at another tab drops the panel
     * into *that* tab, beside a stranger's window. This is the guard for it: hide, switch tabs,
     * restore, and the panel must be back with its own surface.
     */
    @Test
    fun `a restore lands beside its own surface and not beside whatever is focused`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        val dock = wm.attach(app1, dockSpec("aw-dock"), AgentId("agent-1"))

        dock.hide()
        command("[con_id=${app2.raw}] focus")
        awaitFocused(app2)

        dock.show()

        assertTrue(inTab(app1, dock.dockId), "the panel is back with the surface it belongs to")
        assertFalse(
            inTab(app2, dock.dockId),
            "and not in the tab that happened to be focused when the key was pressed",
        )
    }

    /**
     * **The hazard this feature could have introduced, and the first of the two things the brief
     * said to prove rather than assume.**
     *
     * A dock in the scratchpad is an invisible window with a live `claude` in it. If the sweep
     * cannot see it, a surface closing leaves that agent running forever with no way to reach it —
     * strictly worse than the orphaned panel `reapOrphans` already exists to prevent, because at
     * least an orphaned panel is *visible*.
     *
     * It does not leak, and the reason is structural rather than lucky: sway's scratchpad is an
     * ordinary workspace on the `__i3` output, `Node.windows` walks `floating_nodes` as well as
     * `nodes`, and the mark rides along with the window. So the sweep enumerates a hidden dock
     * exactly as it does a standing one.
     *
     * The sweep here is the one this test calls, so the collector's sweep on close is switched
     * off: with it on, the close event can reap the panel before the precondition reads the tree,
     * and under load it did (#230). That is the product working early, not a leak — and the
     * collector's own reach into the scratchpad is held by the test after this one.
     */
    @Test
    fun `a hidden dock is still reaped when its surface closes`() = swayTest {
        store.put(WmFlags.sweepOnClose, false)
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val pid = assertNotNull(pidOf(dock.dockId))
        dock.hide()
        assertTrue(dock.hidden(), "it really is out of the layout before the surface dies")

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        assertNotNull(
            wm.tree().find(dock.dockId.raw),
            "the orphan is still there — sway does not take a scratchpad window down with the " +
                "surface it was docked beside, which is the whole hazard",
        )

        wm.reapOrphans()

        assertNull(
            wm.tree().find(dock.dockId.raw),
            "the sweep reached into the scratchpad and took the hidden panel down",
        )
        assertTrue(
            awaitProcessGone(pid),
            "and the agent behind it exited, rather than being left running invisibly",
        )
    }

    /**
     * The same hazard with nobody driving the sweep: the close event alone takes a hidden panel
     * down. The test before this one proves the sweep can see into the scratchpad; this one proves
     * the collector, which is what a desktop nobody is calling into actually relies on, does.
     */
    @Test
    fun `a hidden dock is reaped on close without anyone driving the sweep`() = swayTest {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val pid = assertNotNull(pidOf(dock.dockId))
        dock.hide()
        assertTrue(dock.hidden(), "it really is out of the layout before the surface dies")

        command("[con_id=${app.raw}] kill")

        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().find(dock.dockId.raw) != null) yield()
                true
            },
            "the hidden panel is still standing ${WAIT_MS}ms after its surface closed, and nothing " +
                "in this test called the sweep — the collector did not reach into the scratchpad",
        )
        assertTrue(
            awaitProcessGone(pid),
            "and the agent behind it exited, rather than being left running invisibly",
        )
    }

    /**
     * **The second thing the brief said to prove.** A hidden dock has to survive awakener
     * restarting, or a crash between two presses of the hotkey strands a running agent in a window
     * nothing can name.
     *
     * Recognition is `wm.dock.recognition=MARK_OR_TABLE`, and a restart empties the table — so the
     * mark is the whole of what is left. Marks do survive a scratchpad move: measured here, and
     * separately on the compositor directly. What this asserts is the consequence, which is that
     * the successor can find the panel *and act on it*: `dockFor` returns a handle and `show()`
     * puts the window back beside a surface the successor never attached to.
     */
    @Test
    fun `a hidden dock is re-adopted after an awakener restart and can be brought back`() =
        swayTest {
            val app = openSurface("aw-app1")
            val original = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
            original.hide()

            restartAwakener()

            val recovered = assertNotNull(
                wm.dockFor(app),
                "the successor found the panel by its mark; without that a restart between two " +
                    "presses strands a running agent in a window nothing can name",
            )
            assertEquals(original.dockId, recovered.dockId, "and it is the same window")
            assertTrue(recovered.hidden(), "still hidden, which the successor can also see")

            assertTrue(recovered.show())
            assertTrue(inTab(app, recovered.dockId), "and the successor brought it back")
        }

    /**
     * A tidiness guarantee that the scratchpad quietly breaks unless `detach` asks the right node.
     *
     * `attach` wraps the surface in a `splith` container, and `wm.dock.normalize_container_on_detach`
     * flattens it afterwards — "a leftover would silently adopt the next window opened in this
     * tab". The survivor is found from the *dock's* parent, and a hidden dock's parent is the
     * `__i3_scratch` workspace, whose other children are unrelated windows. So asked from the dock's
     * side, a detach of a hidden panel normalises nothing at all here and something arbitrary in
     * the general case, and the leftover container stands. Measured before the fix: sway leaves
     * `splith` with one child.
     *
     * Asked from the surface's side it is correct in both cases, and null for an orphan whose
     * surface is already gone — by which point sway has collapsed the empty container itself.
     */
    @Test
    fun `detaching a hidden dock flattens the container it came out of`() = swayTest {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        assertEquals("splith", assertNotNull(tabHolding(app)).layout, "attach made a container")

        dock.hide()
        dock.detach()

        assertNull(wm.tree().find(dock.dockId.raw), "the panel is gone")
        assertEquals(
            app.raw,
            assertNotNull(tabHolding(app)).id,
            "and so is the split container: a leftover would silently adopt the next window " +
                "opened in this tab, which is exactly what a detach is supposed to prevent",
        )
    }

    /**
     * Enumeration must not start handing the agent panel back as a bindable window merely because
     * it has been hidden. A hidden dock is still in the tree, so nothing about being in the
     * scratchpad exempts it from the predicate `surfaces` filters on — and if it did, a hotkey
     * would mint a Lifeless for a Lifeless.
     */
    @Test
    fun `a hidden dock is still not enumerated as a surface`() = swayTest {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        dock.hide()

        assertEquals(
            listOf("aw-app1"),
            wm.surfaces().map { it.appId },
            "the hidden panel is a real tree node and must not be one of them",
        )
    }

    /**
     * `dockFor` targets by `con_id`, and this is why it has to.
     *
     * Under the default `wm.dock.identity=NEW_NODE` every panel reports the same `app_id`, so once
     * two of them can be hidden at once an `app_id` criterion names an arbitrary one. Two surfaces,
     * two panels, one hidden: the lookup must answer with each surface's own.
     */
    @Test
    fun `dockFor answers with each surface's own panel, not whichever shares the app_id`() =
        swayTest {
            val app1 = openSurface("aw-app1")
            val app2 = openSurface("aw-app2")
            val dock1 = wm.attach(app1, dockSpec("aw-dock"), AgentId("agent-1"))
            val dock2 = wm.attach(app2, dockSpec("aw-dock"), AgentId("agent-2"))
            dock1.hide()

            assertEquals(dock1.dockId, assertNotNull(wm.dockFor(app1)).dockId)
            assertEquals(dock2.dockId, assertNotNull(wm.dockFor(app2)).dockId)
            assertTrue(assertNotNull(wm.dockFor(app1)).hidden(), "and only the hidden one is")
            assertFalse(assertNotNull(wm.dockFor(app2)).hidden())
        }

    /** A surface with no panel beside it has none to hide, and the lookup says so rather than
     * guessing at the nearest one. */
    @Test
    fun `dockFor answers nothing for a surface with no panel`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        wm.attach(app1, dockSpec("aw-dock"), AgentId("agent-1"))

        assertNull(wm.dockFor(app2))
    }

    /**
     * **#229.** A floating surface's dock shares a *floating* split with it, and sway's
     * `move scratchpad` on any child of a floating container moves the whole container — measured
     * on headless sway 1.12 — so the hide used to take the user's window into the scratchpad with
     * the panel.
     *
     * Asserted on what the user sees, once the client has settled: the surface is still on the
     * workspace, still floating, and occupies exactly the rectangle it held before the panel was
     * ever attached — the panel's share of the floating window goes back to the surface, as it
     * does for a tiled one.
     *
     * The hide follows straight on from the attach on purpose. That is the case where the
     * surface's client has not yet committed the narrower size the split gave it, and the first
     * fix lost the panel's share of the window to that late commit in most runs at scale 1.5.
     */
    @Test
    fun `hiding the panel of a floating surface leaves the surface where it was`() = swayTest {
        val app = openFloatingSurface("aw-float1")
        val before = settledGeometryOf(app)
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val split = assertNotNull(floatingAncestorOf(app)).id

        assertTrue(dock.hide(), "the hide is what changed things")

        assertTrue(dock.hidden(), "the panel is in the scratchpad")
        assertFalse(inScratchpad(app), "and the surface it was hidden from is not")
        assertEquals(split, floatingAncestorOf(app)?.id, "it is still in its floating split")
        assertEquals(before, settledGeometryOf(app), "on the rectangle it held before the attach")
        assertEquals(listOf("1"), workspaceNames(), "and the hide left no workspace behind")
    }

    /**
     * The hide passes the dock through the workspace as a tiled window on its way out of the
     * floating split, and that must not disturb anything tiled there: the same containers, in the
     * same layouts, at the same sizes, before the hide, after it and after the restore.
     */
    @Test
    fun `a floating hide leaves the tiled windows on its workspace as they were`() = swayTest {
        val left = openSurface("aw-tiled1")
        openSurface("aw-tiled2")
        command("[con_id=${left.raw}] focus; split vertical")
        openSurface("aw-tiled3")
        val app = openFloatingSurface("aw-float1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        settledGeometryOf(dock.dockId)
        val shape = tiledShape()

        assertTrue(dock.hide())
        assertEquals(shape, tiledShape(), "after the hide")
        assertTrue(dock.show())
        assertEquals(shape, tiledShape(), "after the restore")
    }

    /**
     * The other half of #229: the panel comes back *into* the floating window, beside the surface,
     * rather than tiled into the workspace behind it or floating over it.
     */
    @Test
    fun `showing the panel of a floating surface puts it back beside the surface`() = swayTest {
        // Opened first: one opened while the floating surface is focused maps inside its split.
        val other = openSurface("aw-other")
        val app = openFloatingSurface("aw-float1")
        val before = settledGeometryOf(app)
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val docked = settledGeometryOf(dock.dockId)
        val attached = settledGeometryOf(app)
        dock.hide()
        // Elsewhere, so the restore cannot be relying on focus to find the surface.
        command("[con_id=${other.raw}] focus")
        awaitFocused(other)

        assertTrue(dock.show(), "the show is what changed things")

        val container = assertNotNull(floatingAncestorOf(app), "the surface is still floating")
        assertEquals(
            container.id,
            floatingAncestorOf(dock.dockId)?.id,
            "and the panel is back in the same floating window as it",
        )
        assertEquals(
            before,
            settledGeometryOf(SurfaceId(container.id)),
            "which has not moved or resized",
        )
        assertEquals(docked, settledGeometryOf(dock.dockId), "the panel is where the attach put it")
        assertEquals(attached, settledGeometryOf(app), "and the surface has given its share back")
    }

    /**
     * **The review of #232's first fix.** That hide tiled the floating window for the length of
     * one message and floated the surface again, and the client then committed the size it had
     * held *inside* the split after sway had already laid the window out — which sway keeps for a
     * floating window — so every hide cost the window the panel's share of its width, compounding
     * across cycles. At `scale 1.5` it was nearly every run: `1275 → 893 → 622`.
     *
     * Read only after the client has settled, which is what a one-read assertion straight after
     * the call cannot see, and over several cycles, which is what makes a loss compound.
     */
    @Test
    fun `a floating surface keeps its size across repeated hides and restores`() = swayTest {
        cycleFloating("aw-float1")
    }

    /**
     * The same cycle on a second output, where the reviewer saw the loss at scale 1 too, and where
     * the workspace the hide passes the dock back through is not workspace `1` — named with a
     * space and a double quote, which is the spelling the hide has to get right to name it.
     */
    @Test
    fun `a floating surface on a second output keeps its size across hides`() = swayTest {
        command("create_output")
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (outputs() < 2) yield()
                true
            },
            "the second output never appeared",
        )
        command("output HEADLESS-2 position 1280 0 scale 1; focus output HEADLESS-2")
        command("workspace 'second \"one\"'; layout tabbed")
        cycleFloating("aw-float2")
    }

    /**
     * `wm.dock.flatten_floating_on_hide` on: the floating split is collapsed, the surface floats
     * alone on its rectangle, and the restore splits it again.
     *
     * The surface is let settle inside the split before the hide, because the flag's documented
     * cost is exactly the case where it has not: a collapse makes the surface a floating window,
     * which sway resizes to whatever its client commits next.
     */
    @Test
    fun `with flatten_floating_on_hide the surface floats alone and keeps its place`() =
        swayTest {
            store.put(WmFlags.flattenFloatingOnHide, true)
            val app = openFloatingSurface("aw-float1")
            val before = settledGeometryOf(app)
            val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
            val docked = settledGeometryOf(dock.dockId)
            val attached = settledGeometryOf(app)

            assertTrue(dock.hide())
            assertFalse(inScratchpad(app), "the surface was not hidden with the panel")
            assertEquals(app.raw, floatingAncestorOf(app)?.id, "it floats alone")
            assertEquals(before, settledGeometryOf(app), "where it was")

            assertTrue(dock.show())
            val container = assertNotNull(floatingAncestorOf(app))
            assertEquals(container.id, floatingAncestorOf(dock.dockId)?.id, "split again")
            assertEquals(before, settledGeometryOf(SurfaceId(container.id)))
            assertEquals(docked, settledGeometryOf(dock.dockId))
            assertEquals(attached, settledGeometryOf(app))
        }

    /** `wm.dock.side=LEFT` survives the floating restore, which places the panel by a different
     * route from the tiled one. */
    @Test
    fun `a floating surface's left-hand panel comes back on the left`() = swayTest {
        store.put(WmFlags.dockSide, DockSide.LEFT)
        val app = openFloatingSurface("aw-float1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val docked = settledGeometryOf(dock.dockId)

        dock.hide()
        assertTrue(dock.show())

        assertEquals(docked, settledGeometryOf(dock.dockId))
        assertEquals(
            settledGeometryOf(SurfaceId(assertNotNull(floatingAncestorOf(app)).id))
                .substringBefore(" "),
            settledGeometryOf(dock.dockId).substringBefore(" "),
            "it starts at the floating window's left edge",
        )
    }

    /**
     * The guard that #229's branch is chosen by the dock's container and not by the workspace: a
     * tiled surface with a floating window beside it hides and restores exactly as before, and the
     * floating window is not touched.
     */
    @Test
    fun `a tiled surface's hide is unchanged by a floating window on its workspace`() = swayTest {
        val floater = openFloatingSurface("aw-float-other")
        val floating = settledGeometryOf(floater)
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val docked = settledGeometryOf(dock.dockId)
        val surfaced = settledGeometryOf(app)

        assertTrue(dock.hide())
        assertFalse(inTab(app, dock.dockId))
        assertTrue(dock.show())

        assertTrue(inTab(app, dock.dockId), "the panel is back in the surface's tab")
        assertEquals(docked, settledGeometryOf(dock.dockId))
        assertEquals(surfaced, settledGeometryOf(app))
        assertEquals(FLOATING, wm.tree().find(floater.raw)?.type, "the floater is still floating")
        assertEquals(floating, settledGeometryOf(floater), "and has not moved")
    }

    /**
     * #253. A floating surface's dock stands inside a floating split with it, and a detach
     * collapses that split again: the surface floats alone on the rectangle it had before the
     * attach, and nothing else on the workspace has changed. Five cycles, because a leftover that
     * the next attach reuses or nests inside only shows on the second.
     */
    @Test
    fun `attach and detach on a floating surface leave the tree as it was`() = swayTest {
        openSurface("aw-tiled1")
        val app = openFloatingSurface("aw-float1")
        cycleAttach(app)
    }

    /**
     * #253's first half, found by #250's review: the dock maps beside whatever is focused when it
     * maps, and a user who presses the key and then clicks a tiled window has moved focus there
     * before the panel is up. The dock has to end up inside the floating surface's split all the
     * same, and the tiled window the user clicked must not have gained or lost anything.
     */
    @Test
    fun `a floating surface's dock lands beside it when focus moves during start-up`() =
        swayTest {
            val other = openSurface("aw-tiled1")
            val app = openFloatingSurface("aw-float1")
            cycleAttach(app, dockSpec = focusStealingDock(other))
        }

    /**
     * A floating split that is not awakener's — the user's, or another client's — holding the
     * surface alone. It counts as a split now, so the attach takes #248's route: the dock maps
     * beside the surface and the surface is wrapped in a split of awakener's *inside* it, rather
     * than the unmarked re-lay `split` would make of it. The user's container keeps its id and its
     * `splitv` through every cycle, and the detach collapses only awakener's split.
     */
    @Test
    fun `a floating split that is not awakener's survives attach and detach`() = swayTest {
        openSurface("aw-tiled1")
        val app = openFloatingSurface("aw-float1")
        command("[con_id=${app.raw}] split vertical")
        val user = assertNotNull(floatingAncestorOf(app))
        assertEquals("splitv", user.layout, "the fixture is a floating splitv around the surface")
        cycleAttach(app)
        assertEquals(user.id, floatingAncestorOf(app)?.id, "the user's floating split stands")
    }

    /** The same move for a tiled surface, whose dock used to stand as a tab beside the window. */
    @Test
    fun `a tiled surface's dock lands beside it when focus moves during start-up`() = swayTest {
        val other = openSurface("aw-tiled1")
        val app = openSurface("aw-app1")
        cycleAttach(app, dockSpec = focusStealingDock(other))
    }

    /** The same with the surface not focused at the press: the attach is aimed at it by id. */
    @Test
    fun `attaching to an unfocused floating surface leaves the focused window alone`() =
        swayTest {
            val other = openSurface("aw-tiled1")
            val app = openFloatingSurface("aw-float1")
            cycleAttach(app, before = { command("[con_id=${other.raw}] focus") })
        }

    /**
     * #253's fullscreen half, tiled. sway moves the fullscreen state to the split attach makes
     * around a fullscreen window, so the surface and its panel fill the output together; the
     * detach has to hand it back to the surface, or the surface is left windowed.
     */
    @Test
    fun `attach and detach on a fullscreen tiled surface leave the tree as it was`() = swayTest {
        openSurface("aw-tiled1")
        val app = openSurface("aw-full1")
        command("[con_id=${app.raw}] fullscreen enable")
        cycleAttach(app)
    }

    /**
     * A surface that is floating and fullscreen is refused by default, because its round trip is
     * not clean: the pre-fullscreen floating rectangle sway saved goes to the split. Under
     * `wm.dock.fullscreen_surface=DOCK` it docks, and the detach still hands the fullscreen back.
     */
    @Test
    fun `a fullscreen floating surface is refused by default and docks under DOCK`() = swayTest {
        openSurface("aw-tiled1")
        val app = openFloatingSurface("aw-float1")
        command("[con_id=${app.raw}] fullscreen enable")
        val shape = workspaceShape()
        val failure = runCatching { wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1")) }
            .exceptionOrNull()
        assertTrue(
            failure?.message?.contains("fullscreen and floating") == true,
            "the refusal names the reason: $failure",
        )
        assertEquals(shape, workspaceShape(), "and the tree is untouched")

        store.put(WmFlags.fullscreenSurface, FullscreenSurface.DOCK)
        cycleAttach(app, geometry = false)
    }

    /**
     * A hide and a show between the attach and the detach, on a floating surface: #232's path,
     * which must keep working, followed by the detach #253 fixes — still back to the baseline.
     */
    @Test
    fun `a floating surface hidden and shown before its detach is left as it was`() = swayTest {
        openSurface("aw-tiled1")
        val app = openFloatingSurface("aw-float1")
        cycleAttach(app) { dock ->
            assertTrue(dock.hide())
            assertTrue(dock.show())
            assertEquals(
                floatingAncestorOf(app)?.id,
                floatingAncestorOf(dock.dockId)?.id,
                "the panel came back into the surface's floating window",
            )
        }
    }

    /** And a detach straight from hidden. */
    @Test
    fun `a floating surface detached while its panel is hidden is left as it was`() = swayTest {
        openSurface("aw-tiled1")
        val app = openFloatingSurface("aw-float1")
        cycleAttach(app) { dock -> assertTrue(dock.hide()) }
    }

    /**
     * The review of #255: the detach's settle watch is for a late *client* commit, and a client
     * can change only its size. A user who closes the panel and moves the window straight away —
     * 30 ms after the collapse — keeps the window where they put it, in every cycle.
     */
    @Test
    fun `a window moved during the detach's settle stays where the user put it`() = swayTest {
        openSurface("aw-tiled1")
        val app = openFloatingSurface("aw-float1")
        repeat(ATTACH_CYCLES) { cycle ->
            val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
            coroutineScope {
                val detach = launch { dock.detach() }
                awaitFloatingAlone(app)
                delay(USER_MOVE_DELAY_MS)
                command("[con_id=${app.raw}] move absolute position $MOVED_X px $MOVED_Y px")
                detach.join()
            }
            val (x, y) = settledGeometryOf(app).split(" ").take(2).map { it.substringAfter("=").toInt() }
            assertTrue(
                kotlin.math.abs(x - MOVED_X) <= GEOMETRY_SLACK && kotlin.math.abs(y - MOVED_Y) <= GEOMETRY_SLACK,
                "cycle $cycle: the window stayed where the user moved it, at $x,$y",
            )
            command("[con_id=${app.raw}] move absolute position $FLOAT_X px $FLOAT_Y px")
            settledGeometryOf(app)
        }
    }

    /**
     * A drag that starts as the panel closes — a move every 25 ms for 1.5 s — is never snapped
     * back, and the detach does not wait for the drag to finish.
     */
    @Test
    fun `a drag during the detach's settle is never snapped back`() = swayTest {
        openSurface("aw-tiled1")
        val app = openFloatingSurface("aw-float1")
        repeat(ATTACH_CYCLES) { cycle ->
            val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
            var detachMs = 0L
            var snapBacks = 0
            coroutineScope {
                val detach = launch {
                    val started = kotlin.time.TimeSource.Monotonic.markNow()
                    dock.detach()
                    detachMs = started.elapsedNow().inWholeMilliseconds
                }
                awaitFloatingAlone(app)
                repeat(DRAG_STEPS) { step ->
                    val x = MOVED_X + step * 2
                    command("[con_id=${app.raw}] move absolute position $x px $MOVED_Y px")
                    delay(DRAG_STEP_MS)
                    val now = geometryOf(app).split(" ").take(2).map { it.substringAfter("=").toInt() }
                    if (kotlin.math.abs(now[0] - FLOAT_X) <= GEOMETRY_SLACK &&
                        kotlin.math.abs(now[1] - FLOAT_Y) <= GEOMETRY_SLACK
                    ) {
                        snapBacks++
                    }
                }
                detach.join()
            }
            assertEquals(0, snapBacks, "cycle $cycle: snap-backs during the drag")
            assertTrue(
                detachMs < DRAG_STEPS * DRAG_STEP_MS,
                "cycle $cycle: the detach returned before the drag ended, in ${detachMs}ms",
            )
            command("[con_id=${app.raw}] move absolute position $FLOAT_X px $FLOAT_Y px")
            settledGeometryOf(app)
        }
    }

    /** `wm.dock.floating_surface=REFUSE`: the attach fails, saying why, and touches nothing. */
    @Test
    fun `under floating_surface REFUSE a floating surface is refused untouched`() = swayTest {
        store.put(WmFlags.floatingSurface, FloatingSurface.REFUSE)
        openSurface("aw-tiled1")
        val app = openFloatingSurface("aw-float1")
        val shape = workspaceShape()
        val failure = runCatching { wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1")) }
            .exceptionOrNull()
        assertTrue(
            failure?.message?.contains("floating") == true,
            "the refusal names the reason: $failure",
        )
        assertEquals(shape, workspaceShape(), "and the tree is untouched")
        assertTrue(wm.tree().windows.none { it.appId == "aw-dock" }, "with no dock started")
    }

    /** `wm.dock.fullscreen_surface=REFUSE`: the same for a fullscreen surface. */
    @Test
    fun `under fullscreen_surface REFUSE a fullscreen surface is refused untouched`() = swayTest {
        store.put(WmFlags.fullscreenSurface, FullscreenSurface.REFUSE)
        val app = openSurface("aw-full1")
        command("[con_id=${app.raw}] fullscreen enable")
        val shape = workspaceShape()
        val failure = runCatching { wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1")) }
            .exceptionOrNull()
        assertTrue(
            failure?.message?.contains("fullscreen") == true,
            "the refusal names the reason: $failure",
        )
        assertEquals(shape, workspaceShape(), "and the tree is untouched")
    }

    // -- helpers ------------------------------------------------------------------------

    /**
     * [ATTACH_CYCLES] attaches and detaches of [app], each held to the baseline taken before the
     * first: the workspace's whole shape — every container's id, type, layout, fullscreen state
     * and marks, and every tiled rectangle — and, unless [geometry] is false, [app]'s own settled
     * rectangle to within [GEOMETRY_SLACK]. [before] runs ahead of each attach and [between]
     * while the dock stands.
     */
    private suspend fun cycleAttach(
        app: SurfaceId,
        dockSpec: DockSpec = dockSpec("aw-dock"),
        before: suspend () -> Unit = {},
        geometry: Boolean = true,
        between: suspend (DockHandle) -> Unit = {},
    ) {
        val rect = settledGeometryOf(app)
        val shape = workspaceShape()
        repeat(ATTACH_CYCLES) { cycle ->
            before()
            val dock = wm.attach(app, dockSpec, AgentId("agent-1"))
            assertEquals(
                wm.tree().parentOf(app.raw)?.id,
                wm.tree().parentOf(dock.dockId.raw)?.id,
                "cycle $cycle: the panel stands beside the surface: ${workspaceShape()}",
            )
            between(dock)
            dock.detach()
            awaitGone(dock.dockId)
            assertEquals(shape, workspaceShape(), "cycle $cycle: the workspace, after the detach")
            if (geometry) assertNear(rect, settledGeometryOf(app), "cycle $cycle: the surface")
        }
    }

    /** Waits until [app] floats alone again, which is the detach's collapse having run. */
    private suspend fun awaitFloatingAlone(app: SurfaceId) {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (floatingAncestorOf(app)?.id != app.raw) delay(2)
                true
            },
            "the detach never collapsed the floating split",
        )
    }

    private fun assertNear(expected: String, actual: String, what: String) {
        fun parse(g: String) = g.split(" ").map { it.substringAfter("=").toInt() }
        val off = parse(expected).zip(parse(actual)).maxOf { (a, b) -> kotlin.math.abs(a - b) }
        assertTrue(off <= GEOMETRY_SLACK, "$what: expected $expected, was $actual")
    }

    /**
     * A dock that moves focus to [elsewhere] before its window maps — a user clicking another
     * window while the panel starts, made deterministic. `swaymsg` reaches this session through
     * the `SWAYSOCK` sway hands every process it execs.
     */
    private fun focusStealingDock(elsewhere: SurfaceId) = DockSpec(
        "aw-dock",
        "sh -c 'swaymsg [con_id=${elsewhere.raw}] focus >/dev/null; exec " +
            sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER) + "'",
    )

    /**
     * Workspace `1` as one string: every node's id, type, layout, fullscreen state and marks,
     * nested, with the rectangle of every tiled node. Floating rectangles are left out, because
     * a client settles those on its own schedule; [cycleAttach] compares the surface's settled.
     */
    private suspend fun workspaceShape(): String {
        val raw = sway.connection().use { it.request(I3Ipc.Request.GET_TREE, "") }
        val root = Json.parseToJsonElement(raw).jsonObject
        fun walk(node: JsonObject, floating: Boolean): String {
            val type = node["type"]?.jsonPrimitive?.contentOrNull
            val inFloat = floating || type == FLOATING
            val rect = node.getValue("rect").jsonObject
            val geometry = if (inFloat) "" else listOf("x", "y", "width", "height")
                .joinToString(",", "@") { rect.getValue(it).jsonPrimitive.int.toString() }
            val tiled = node["nodes"]?.jsonArray.orEmpty().map { walk(it.jsonObject, inFloat) }
            val floats = node["floating_nodes"]?.jsonArray.orEmpty()
                .map { walk(it.jsonObject, inFloat) }
            return "${node["id"]}:$type:${node["layout"]?.jsonPrimitive?.contentOrNull}" +
                ":fs=${node["fullscreen_mode"]}:${node["marks"]}$geometry" +
                tiled.joinToString(",", "[", "]") + floats.joinToString(",", "~{", "}")
        }
        fun workspace(node: JsonObject): JsonObject? =
            if (node["type"]?.jsonPrimitive?.contentOrNull == "workspace" &&
                node["name"]?.jsonPrimitive?.contentOrNull == "1"
            ) {
                node
            } else {
                node["nodes"]?.jsonArray.orEmpty().firstNotNullOfOrNull { workspace(it.jsonObject) }
            }
        return walk(assertNotNull(workspace(root), "no workspace 1"), false)
    }


    /** [FLOATING_CYCLES] hides and restores of a floating surface's panel, each read settled. */
    private suspend fun cycleFloating(appId: String) {
        val app = openFloatingSurface(appId)
        val before = settledGeometryOf(app)
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val docked = settledGeometryOf(dock.dockId)
        val attached = settledGeometryOf(app)
        val workspaces = workspaceNames()
        repeat(FLOATING_CYCLES) { cycle ->
            assertTrue(dock.hide(), "cycle $cycle: the hide is what changed things")
            assertEquals(workspaces, workspaceNames(), "cycle $cycle: no workspace left behind")
            assertEquals(before, settledGeometryOf(app), "cycle $cycle: the surface, hidden")
            assertTrue(dock.show(), "cycle $cycle: the show is what changed things")
            val container = assertNotNull(floatingAncestorOf(app), "cycle $cycle: floating")
            assertEquals(before, settledGeometryOf(SurfaceId(container.id)), "cycle $cycle: window")
            assertEquals(attached, settledGeometryOf(app), "cycle $cycle: the surface, docked")
            assertEquals(docked, settledGeometryOf(dock.dockId), "cycle $cycle: the panel")
        }
    }

    /**
     * A surface that floats from the moment it maps, at a size of the test's choosing, read back
     * once the client has settled on it.
     *
     * **Floated by a `for_window` rule rather than after mapping**, because the other way is not a
     * fixture of the test's choosing. A foot that maps tiled is configured at the tiled size, and
     * commits that size whenever it gets round to it — after a `floating enable` and a `resize
     * set` have already gone out, and sway keeps whatever a floating client commits. Measured on
     * this suite: a window asked for at 640x400 settled at 1280x691, hanging off the output. A
     * window that has only ever been floating has no stale size to commit.
     *
     * The size is then asserted rather than assumed, to within the terminal's cell, since foot
     * rounds to whole cells: a fixture that silently landed somewhere else is what let the
     * off-screen window above go unnoticed.
     */
    private suspend fun openFloatingSurface(appId: String): SurfaceId {
        command("for_window [app_id=\"^$appId\$\"] floating enable")
        val id = openSurface(appId)
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().find(id.raw)?.type != FLOATING) yield()
                true
            },
            "window ${id.raw} never floated",
        )
        settledGeometryOf(id)
        val output = assertNotNull(rectOfOutputHolding(id), "window ${id.raw} is on no output")
        val x = output.first + FLOAT_X
        command(
            "[con_id=${id.raw}] resize set $FLOAT_W px $FLOAT_H px; " +
                "[con_id=${id.raw}] move absolute position $x px $FLOAT_Y px",
        )
        val settled = settledGeometryOf(id)
        val (w, h) = settled.split(" ").drop(2).map { it.substringAfter("=").toInt() }
        assertTrue(
            w in (FLOAT_W - CELL_SLACK)..FLOAT_W && h in (FLOAT_H - CELL_SLACK)..FLOAT_H,
            "the floating fixture landed at the size it asked for, within one cell: $settled",
        )
        return id
    }

    /**
     * [id]'s geometry once it has held still for [SETTLE_READS] reads running, [SETTLE_MS] apart.
     *
     * A client commits on its own schedule, and sway keeps whatever a floating one commits — so a
     * single read straight after a call answers about the layout sway asked for, not the one the
     * user ends up with. The window here is long enough to span the late commit the review of
     * #232 caught at 700ms only because each settle restarts on any change.
     */
    private suspend fun settledGeometryOf(id: SurfaceId): String = assertNotNull(
        withTimeoutOrNull(WAIT_MS) {
            var last = geometryOf(id)
            var settled = 0
            while (settled < SETTLE_READS) {
                delay(SETTLE_MS)
                val now = geometryOf(id)
                settled = if (now == last) settled + 1 else 0
                last = now
            }
            last
        },
        "window ${id.raw} never held still for ${SETTLE_READS * SETTLE_MS}ms",
    )

    /** The x of the output [id] is on, and its width, in logical pixels. */
    private suspend fun rectOfOutputHolding(id: SurfaceId): Pair<Int, Int>? {
        val raw = sway.connection().use { it.request(I3Ipc.Request.GET_TREE, "") }
        val outputs = Json.parseToJsonElement(raw).jsonObject.getValue("nodes").jsonArray
        return outputs.map { it.jsonObject }
            .firstOrNull { nodeOf(it, id.raw) != null }
            ?.getValue("rect")?.jsonObject
            ?.let { it.getValue("x").jsonPrimitive.int to it.getValue("width").jsonPrimitive.int }
    }

    /** The workspaces on real outputs, by name. */
    private suspend fun workspaceNames(): List<String> =
        wm.tree().nodes.filter { it.name != "__i3" }.flatMap { output -> output.nodes.mapNotNull { it.name } }

    /**
     * The tiled part of workspace `1` as one string — every container's id, layout and geometry,
     * nested — so that two readings are equal exactly when nothing tiled has changed.
     */
    private suspend fun tiledShape(): String {
        val workspace = assertNotNull(wm.tree().workspace("1"))
        suspend fun shape(node: Node): String =
            "${node.id}:${node.layout}:${geometryOf(SurfaceId(node.id))}" +
                node.nodes.map { shape(it) }.joinToString(",", "[", "]")
        return workspace.nodes.map { shape(it) }.joinToString(",")
    }

    /** Real outputs, which is every child of the root but sway's `__i3` scratchpad output. */
    private suspend fun outputs(): Int = wm.tree().nodes.count { it.name != "__i3" }

    /** The node with [id] in a raw `GET_TREE` payload, found by walking `nodes` and `floating_nodes`. */
    private fun nodeOf(node: JsonObject, id: Long): JsonObject? {
        if (node["id"]?.jsonPrimitive?.longOrNull == id) return node
        return listOf("nodes", "floating_nodes")
            .mapNotNull { node[it]?.jsonArray }
            .flatten()
            .firstNotNullOfOrNull { nodeOf(it.jsonObject, id) }
    }

    private suspend fun inScratchpad(id: SurfaceId): Boolean =
        wm.tree().workspace(SCRATCH)?.find(id.raw) != null

    /** The floating container [id] is in — itself, when it floats alone — or null when tiled. */
    private suspend fun floatingAncestorOf(id: SurfaceId): Node? {
        val tree = wm.tree()
        return generateSequence(tree.find(id.raw)) { tree.parentOf(it.id) }
            .firstOrNull { it.type == FLOATING }
    }

    private fun swayTest(body: suspend () -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking {
            command("workspace 1; layout tabbed")
            body()
        }
    }

    private fun managerScope() = CoroutineScope(SupervisorJob(scope.coroutineContext.job))

    /**
     * An awakener restart over the same sway session: the predecessor is retired and joined, and a
     * fresh manager comes up with an empty dock table over the same marks and the same windows.
     *
     * Joining rather than merely cancelling is #56's lesson: `cancel` only asks, and a predecessor
     * still inside a sweep races the successor for the same dock.
     */
    private suspend fun restartAwakener() {
        wmScope.coroutineContext.job.cancelAndJoin()
        wmScope = managerScope()
        wm = SwayWindowManager({ sway.connection() }, store, bindingStore(), wmScope)
    }

    private fun bindingStore() = FileBindingStore(
        configStore = store,
        identities = { key, residuePath -> DerivedAgentIdentities(store).mint(key, residuePath) },
        path = stateDir.resolve("bindings.json"),
    )

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        swayJson.decodeFromString<List<CommandResult>>(raw)
            .firstOrNull { !it.success }
            ?.let { error("sway rejected '$text': ${it.error}") }
    }

    private suspend fun openSurface(appId: String): SurfaceId {
        command("exec ${sway.windowCommand(appId)}")
        return SurfaceId(assertNotNull(awaitWindow(appId), "window '$appId' never appeared"))
    }

    private fun dockSpec(appId: String) =
        DockSpec(appId, sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    /** The tab a surface lives in — the workspace child that contains it. */
    private suspend fun tabHolding(surface: SurfaceId): Node? =
        wm.tree().workspace("1")?.children?.firstOrNull { it.find(surface.raw) != null }

    /** Whether [node] is inside the tab [surface] lives in. */
    private suspend fun inTab(surface: SurfaceId, node: SurfaceId): Boolean =
        tabHolding(surface)?.find(node.raw) != null

    private suspend fun pidOf(id: SurfaceId): Int? = wm.tree().find(id.raw)?.pid

    /**
     * Size and position, which is what a restore has to give back.
     *
     * Read straight out of `GET_TREE`'s payload rather than through [Node], so that the test's
     * reading of a rectangle does not go through the decoding the code under test uses for its
     * own (#253's detach reads `Node.rect`).
     */
    private suspend fun geometryOf(id: SurfaceId): String {
        val raw = sway.connection().use { it.request(I3Ipc.Request.GET_TREE, "") }
        val rect = assertNotNull(
            rectOf(Json.parseToJsonElement(raw).jsonObject, id.raw),
            "node ${id.raw} is not in the tree, so it has no geometry to compare",
        )
        return listOf("x", "y", "width", "height")
            .joinToString(" ") { "$it=${rect.getValue(it).jsonPrimitive.int}" }
    }

    /**
     * Reads the dock continuously on a connection of its own while [body] runs, and hands back
     * every reading in order.
     *
     * A separate connection because the manager's carries the restore itself, and the i3 protocol
     * has no request ids — one connection serialises, so sampling down the same socket would
     * interleave with the commands being measured and pace them.
     *
     * The loop is deliberately unpaced. What it is looking for is a layout that exists for a
     * handful of milliseconds, and [SwayWindowManager]'s own polling discipline — which exists to
     * stop a *wait* costing thousands of tree reads — would be the wrong instrument here: this one
     * runs for the length of one test body and the reads are the measurement.
     */
    private suspend fun sampling(dock: SurfaceId, body: suspend () -> Unit): List<String> =
        coroutineScope {
            val census = synchronizedList(mutableListOf<String>())
            val connection = sway.connection()
            val sampler = launch(Dispatchers.IO) {
                connection.use {
                    while (isActive) {
                        val raw = it.request(I3Ipc.Request.GET_TREE, "")
                        census += frameOf(Json.parseToJsonElement(raw).jsonObject, dock.raw, false)
                    }
                }
            }
            // So the census opens on the state the restore starts from. Without it a sampler that
            // took its first reading after the batch had already run would report a clean census
            // for having watched nothing at all, which is the reading this test exists to refuse.
            while (census.isEmpty()) yield()
            body()
            sampler.cancelAndJoin()
            census.toList()
        }

    /** One reading of the dock, taken through the tree the way the compositor presents it. */
    private suspend fun frameOf(dock: SurfaceId): String? {
        val raw = sway.connection().use { it.request(I3Ipc.Request.GET_TREE, "") }
        return frameOf(Json.parseToJsonElement(raw).jsonObject, dock.raw, false)
            .takeIf { it != HIDDEN }
    }

    /**
     * How the dock looks in one `get_tree`: [HIDDEN] while it is in the scratchpad or absent, and
     * otherwise the geometry a user would see, floating included.
     *
     * [inScratch] carries down the walk rather than being asked separately, so that a single pass
     * answers both "is it on screen" and "what does it look like" — the two have to come from the
     * same reading or the pair describes no moment in particular.
     */
    private fun frameOf(node: JsonObject, dock: Long, inScratch: Boolean): String {
        val type = node["type"]?.jsonPrimitive?.contentOrNull
        val scratch = inScratch ||
            (type == "workspace" && node["name"]?.jsonPrimitive?.contentOrNull == SCRATCH)
        if (node["id"]?.jsonPrimitive?.longOrNull == dock) {
            if (scratch) return HIDDEN
            val rect = node.getValue("rect").jsonObject
            val floating = type == FLOATING
            return "floating=$floating " +
                "w=${rect.getValue("width").jsonPrimitive.int} " +
                "x=${rect.getValue("x").jsonPrimitive.int}"
        }
        return listOf("nodes", "floating_nodes")
            .mapNotNull { node[it]?.jsonArray }
            .flatten()
            .map { frameOf(it.jsonObject, dock, scratch) }
            .firstOrNull { it != HIDDEN }
            ?: HIDDEN
    }

    /** The census as the issue reports it: each distinct reading, with how many samples saw it. */
    private fun report(census: List<String>): String =
        census.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "  ${it.key}  ${it.value} samples" }

    /** The `rect` of the node with [id]. */
    private fun rectOf(node: JsonObject, id: Long): JsonObject? = nodeOf(node, id)?.get("rect")?.jsonObject

    private suspend fun awaitWindow(appId: String): Long? = withTimeoutOrNull(WAIT_MS) {
        while (true) {
            wm.tree().windows.firstOrNull { it.appId == appId }
                ?.let { return@withTimeoutOrNull it.id }
            yield()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private suspend fun awaitGone(id: SurfaceId) {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().find(id.raw) != null) yield()
                true
            },
            "window ${id.raw} never left the tree",
        )
    }

    private suspend fun awaitFocused(id: SurfaceId) {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().windows.firstOrNull { it.focused }?.id != id.raw) yield()
                true
            },
            "focus never reached ${id.raw}",
        )
    }

    /**
     * Whether [pid] has left, waited for rather than sampled.
     *
     * A kill is asynchronous twice over: sway asks the client to close, and the client's own exit
     * is then whatever the kernel gets round to. Sampling straight after the sweep would be a race
     * that passes on a quiet machine.
     *
     * Read the other way it is the bound on "still running": false means the process outlived the
     * whole wait, which a sample taken at once cannot say (#168).
     */
    private suspend fun awaitProcessGone(pid: Int): Boolean = withTimeoutOrNull(WAIT_MS) {
        while (ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)) yield()
        true
    } ?: false

    private companion object {
        const val WAIT_MS = 5_000L

        /** How often a window is re-read while waiting for its client to settle. */
        const val SETTLE_MS = 50L

        /** How many reads in a row a window has to hold still for to count as settled. */
        const val SETTLE_READS = 10

        /** Attach/detach cycles [cycleAttach] runs: a leftover shows on the second (#253). */
        const val ATTACH_CYCLES = 5

        /** When the user moves the window after the detach's collapse, and where to. */
        const val USER_MOVE_DELAY_MS = 30L
        const val MOVED_X = 400
        const val MOVED_Y = 250

        /** A drag: one move every [DRAG_STEP_MS] for [DRAG_STEPS] steps, about 1.5 s. */
        const val DRAG_STEPS = 60
        const val DRAG_STEP_MS = 25L

        /** How far a surface's settled rectangle may move across a cycle, in logical pixels. */
        const val GEOMETRY_SLACK = 2

        /** Hide/restore cycles one floating census covers; the first fix's loss compounded. */
        const val FLOATING_CYCLES = 5

        /** The floating fixture's rectangle, on-screen on a 1280x720 logical output. */
        const val FLOAT_X = 100
        const val FLOAT_Y = 60
        const val FLOAT_W = 1000
        const val FLOAT_H = 600

        /** How far foot may round the fixture's size down to a whole cell. */
        const val CELL_SLACK = 30

        /**
         * The reading for a dock that is not on screen — in the scratchpad, or not in the tree.
         *
         * One value for both because the census is about what the user is shown, and a window that
         * is not there shows nothing either way. Sorting ahead of every geometry reading is why it
         * starts with a space: the assertions compare sorted lists.
         */
        const val HIDDEN = " hidden"

        /**
         * How many hide/show cycles one census covers.
         *
         * More than one because a single restore at the fixed code takes about 5ms end to end, and
         * a census of five readings is a weak thing to conclude "no intermediate layout" from. The
         * hides are in the census too and are held to the same rule: a toggle is what the user
         * presses, and a frame presented on the way down would be the same defect facing the other
         * way.
         */
        const val TOGGLES = 20

        /**
         * sway's own names, spelled out here rather than borrowed from [SwayWindowManager].
         *
         * Deliberate duplication: these are facts about the compositor, and a test that read them
         * off the code under test would agree with it about a typo. Both measured on 1.12 — a
         * scratchpad window is parented to the `__i3_scratch` workspace, and a window that has not
         * been re-tiled reports `type=floating_con`.
         */
        const val SCRATCH = "__i3_scratch"
        const val FLOATING = "floating_con"
    }
}
