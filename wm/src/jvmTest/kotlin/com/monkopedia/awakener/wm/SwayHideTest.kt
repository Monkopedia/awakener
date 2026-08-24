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
 * Exercised against **sway 1.12** on the headless wlroots backend.
 */
class SwayHideTest {
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
        sway = SwayHarness.start()
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
     * The process assertion is the one that matters and is deliberately made against
     * `ProcessHandle` rather than against anything sway says. A tree with no dock node in it looks
     * identical whether the panel was hidden or killed, which is exactly the difference between
     * this feature and the one Jason first suggested and then talked himself out of.
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
            assertTrue(
                ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false),
                "the agent behind the panel is still running, which is the entire point: a " +
                    "dock is a terminal with a live claude session in it, and killing it would " +
                    "destroy the accumulated model this project exists to keep",
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
     */
    @Test
    fun `a hidden dock is still reaped when its surface closes`() = swayTest {
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

    // -- helpers ------------------------------------------------------------------------

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
     * Read straight out of `GET_TREE`'s payload rather than through [Node], which deliberately
     * models only the fields awakener reasons about. Geometry is not one of them — nothing in the
     * product reads a rect — so it is parsed here rather than added to a type every decode in the
     * build pays for.
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

    /** The `rect` of the node with [id], found by walking `nodes` and `floating_nodes`. */
    private fun rectOf(node: JsonObject, id: Long): JsonObject? {
        if (node["id"]?.jsonPrimitive?.longOrNull == id) return node["rect"]?.jsonObject
        return listOf("nodes", "floating_nodes")
            .mapNotNull { node[it]?.jsonArray }
            .flatten()
            .firstNotNullOfOrNull { rectOf(it.jsonObject, id) }
    }

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
     */
    private suspend fun awaitProcessGone(pid: Int): Boolean = withTimeoutOrNull(WAIT_MS) {
        while (ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)) yield()
        true
    } ?: false

    private companion object {
        const val WAIT_MS = 5_000L

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
