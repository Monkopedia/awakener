package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assume.assumeTrue

/**
 * What a woken tab actually says, read off the screen, against a real headless sway.
 *
 * ### Why the pixels
 *
 * `title_format` is write-only over IPC. `GET_TREE` reports a container's `name` as `null` before
 * and after one is set, no field anywhere carries the format, and setting one on a container emits
 * no event at all — all three checked on 1.12. So a test that asserts on the tree can establish
 * that a command was *accepted* and nothing whatever about what the user is shown, which is the
 * entire subject of #177. `grim` reads the headless output because the renderer runs under pixman.
 *
 * ### The oracle, and why it needs no OCR
 *
 * Nothing here reads text out of an image. Every assertion compares two renders **of the same
 * tree in the same state**, differing only in the `title_format` on one container:
 *
 * - the frame after awakener named the tab, against the frame after this test names it by hand
 *   with the string awakener is supposed to have used — equal, and that equality is a complete
 *   readback of the format, glyph for glyph;
 * - and against the frame with sway's own `%title` — **not** equal, which is the control. Without
 *   it an assertion of equality would pass just as happily on a comparison that could not tell any
 *   two states apart.
 *
 * The whole 1280x720 output is compared rather than a crop: five captures 400ms apart over this
 * layout were byte-identical, so there is no moving part to exclude and a crop would only be a
 * place for a difference to hide.
 *
 * Every frame is written into `build/reports/tab-title/`, named for the test and the state. They
 * are the screenshots #177 asks for, produced by the run rather than alongside it.
 *
 * ### Not every sway can do this
 *
 * `title_format` on a **container** is refused by sway 1.9 — *"Only views can have a title_format"*
 * — and accepted by 1.12. Both are current somewhere: 1.9 is what Ubuntu noble ships and what this
 * repository's CI runs, 1.12 is on kaladin and adolin. So the visual assertions below are gated on
 * an assumption, and the probe is **awakener's own attempt**: after an attach,
 * [SwayWindowManager.tabTitleUnsupported] is non-null exactly when this compositor said no. That
 * costs no extra windows and no extra commands, and it cannot drift from what the product does,
 * because it *is* what the product did.
 *
 * `attach survives a compositor that will not name a container's tab` is the one that runs
 * everywhere, and it is what holds the degradation: the assumption above is only honest if a
 * refusal is a reported no-op rather than a failed hotkey press.
 *
 * Exercised against **sway 1.12** on the headless wlroots backend, with **grim 1.5**. On CI, the
 * gated tests report as genuinely skipped against **sway 1.9**.
 */
class SwayTabTitleTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var registry: BindingStore
    private lateinit var stateDir: Path
    private lateinit var reports: File

    /**
     * Resolved once. [SwayHarness.waylandDisplay] reads `/proc/<pid>/environ` for every process on
     * the machine, and the retitle test captures dozens of frames.
     */
    private var display: String? = null

    private val enabled get() = SwayHarness.available() && SwayHarness.grimAvailable()

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        SwayHarness.assumeGrim()
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-tabtitle")
        registry = FileBindingStore(
            configStore = store,
            identities = { key, residue -> DerivedAgentIdentities(store).mint(key, residue) },
            path = stateDir.resolve("bindings.json"),
        )
        wm = SwayWindowManager({ sway.connection() }, store, registry, scope)
        reports = File("build/reports/tab-title").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    /**
     * #177's first defect and its fix, in the one form that can see either.
     *
     * The synthetic frame is what a user gets today the moment an agent is woken: sway names the
     * split out of its own layout description, so `Warbreaker - Wikipedia` becomes
     * `H[aw-app1 aw-dock]`. It is the control here *and* the bug — the same capture doing both
     * jobs, which is why the assertion that it differs is not a formality.
     */
    @Test
    fun `a woken tab is painted with the surface's own title, not sway's layout description`() =
        swayTest {
            val app = openTitled("aw-app1", PAGE)
            val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
            assertTrue(inTab(app, dock.dockId), "the dock is beside its surface")
            assumeContainerTitles()
            val container = containerOf(app)

            val managed = capture("woken-managed")

            command(TabTitle.resetCommand(container))
            val synthetic = capture("woken-synthetic")
            assertNotEquals(
                managed,
                synthetic,
                "sway's own naming has to look different, or this comparison distinguishes " +
                    "nothing and every other assertion in this suite is vacuous",
            )

            command("[con_id=${container.raw}] title_format ${TabTitle.quote(PAGE)}")
            assertEquals(
                managed,
                capture("woken-by-hand"),
                "awakener's tab has to be the same pixels as the tab this test names by hand " +
                    "with the page title — that equality is the whole readback, since sway " +
                    "will not tell anyone what a title_format expanded to",
            )
        }

    /**
     * The `window::title` half, which is what stops the fix from being right once and wrong after
     * the first click.
     *
     * The window retitles itself the way a page does — an OSC sequence from the program that owns
     * it, not a command from this test — and nothing is issued to the compositor in between. What
     * closes the loop is `changes` and the collector on it, so this also fails if
     * `wm.events.enabled` stops being wired.
     */
    @Test
    fun `a tab follows the surface when the window retitles itself`() = swayTest {
        val flag = stateDir.resolve("retitle").toFile()
        val app = openTitled("aw-app1", PAGE, then = NEXT_PAGE, on = flag)
        wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        assumeContainerTitles()
        val container = containerOf(app)
        val first = capture("retitle-before")

        flag.writeText("go")
        assertTrue(
            awaitTitle(app, NEXT_PAGE),
            "the window has to actually change its own title before this can be about awakener",
        )

        val followed = assertNotNull(
            awaitFrameOtherThan(first, "retitle-after"),
            "the tab still shows the old title ${WAIT_MS}ms after the window changed it, so " +
                "nothing re-issued the format — which is the failure mode #177 names outright",
        )
        command("[con_id=${container.raw}] title_format ${TabTitle.quote(NEXT_PAGE)}")
        assertEquals(followed, capture("retitle-by-hand"), "and it followed to the new title")
    }

    /**
     * **The break-out, attempted for real.**
     *
     * The title is chosen by the program in the window — this one sets it with an OSC sequence,
     * exactly as a web page sets `document.title` — and it carries a payload that closes
     * awakener's quote and starts two commands of its own. Two observables, because one of them
     * can be defeated by a payload that merely fails to parse: a file that must not exist, and a
     * window that must not map. `exec` is sway asking a shell to run something, so a successful
     * injection here is arbitrary code as the user.
     *
     * **The payload ends on a command that parses**, and that is the difference between this test
     * and one that passes for the wrong reason. A break-out ending in stray text makes sway reject
     * the last fragment, `run` raises, and the attach fails — *after* the injected `exec`s have
     * already run. A test that let that failure propagate would go red on a payload like this one
     * and stay green on a tidier one, which is the worse case: an injection nobody notices. So the
     * closing `title_format` makes the whole list valid, the attach is caught rather than
     * propagated, and the assertions below are what has to hold either way.
     *
     * Watched fail: with [TabTitle.quote] reduced to wrapping its input in quotes, the canary
     * assertion is the one that goes red. See the PR body for the run.
     */
    @Test
    fun `a hostile window title cannot become a compositor command`() = swayTest {
        val canary = stateDir.resolve("canary").toFile()
        val hostile = "A\"; exec touch ${canary.absolutePath}; " +
            "exec ${sway.windowCommand(INTRUDER)}; title_format \"B"
        val app = openTitled("aw-app1", hostile)
        assertEquals(hostile, titleOf(app), "the window really is presenting the payload")

        val attached = runCatching { wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1")) }

        // The injected commands would be sway's to run, so this waits the same span an ordinary
        // window map is given rather than sampling immediately.
        assertFalse(
            awaitFile(canary),
            "a window title reached sway's command parser: it ran `exec touch` and left " +
                "${canary.absolutePath} behind. That is arbitrary code execution from " +
                "`document.title`",
        )
        assertFalse(
            wm.tree().windows.any { it.appId == INTRUDER },
            "a window title started a program: '$INTRUDER' mapped, which nothing in this test " +
                "asked for. No second wait is needed here — the canary above already gave sway " +
                "the span an injected exec would have used",
        )
        val dock = attached.getOrThrow()
        assertTrue(inTab(app, dock.dockId), "and the attach itself went through")
    }

    /**
     * What happens to the tab while the dock is hidden, which is a question with a measured answer
     * rather than a design choice.
     *
     * Hiding moves the dock to the scratchpad and **sway leaves the split container standing**
     * with one child (1.12) — so the tab is still a container's tab and still synthetic without
     * this. The title therefore stays set across a hide, deliberately: clearing it would put
     * `H[aw-app1]` on screen for the whole time the panel is away, which is the bug wearing a
     * shorter name.
     */
    @Test
    fun `a hidden dock leaves its surface's title on the tab`() = swayTest {
        val app = openTitled("aw-app1", PAGE)
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        assumeContainerTitles()
        assertTrue(dock.hide(), "the hide is what changed things")
        val container = containerOf(app)
        assertNotNull(
            container.raw.let { wm.tree().find(it) },
            "the split container outlives the hide, which is why the tab still needs a name",
        )

        val hidden = capture("hidden-managed")
        command(TabTitle.resetCommand(container))
        assertNotEquals(hidden, capture("hidden-synthetic"), "the control still distinguishes")
        command("[con_id=${container.raw}] title_format ${TabTitle.quote(PAGE)}")
        assertEquals(hidden, capture("hidden-by-hand"), "so the hidden tab still reads the page")
    }

    /**
     * A detach hands the tab back, and the flag that decides whether the container survives is the
     * one that makes this observable.
     *
     * Under `normalize_container_on_detach=true` — the default — the container goes with the dock
     * and there is nothing left to be wrong. Off, it stands there holding only the surface, and a
     * title left on it would be frozen at whatever the page said when the panel closed: a tab that
     * looks right and is lying, which is worse than the synthetic name it replaced.
     */
    @Test
    fun `a detach hands the tab back to the compositor`() = swayTest {
        store.put(WmFlags.normalizeContainerOnDetach, false)
        val app = openTitled("aw-app1", PAGE)
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        assumeContainerTitles()
        val container = containerOf(app)

        dock.detach()
        assertNotNull(
            wm.tree().find(container.raw),
            "the container has to survive the detach, or this test is asserting about nothing",
        )

        val afterDetach = capture("detach-reset")
        command("[con_id=${container.raw}] title_format ${TabTitle.quote(PAGE)}")
        assertNotEquals(
            afterDetach,
            capture("detach-still-titled"),
            "the tab must not still be carrying the page title the dock was standing under",
        )
        command(TabTitle.resetCommand(container))
        assertEquals(
            afterDetach,
            capture("detach-by-hand"),
            "it reads exactly as a container sway names itself, which is the pre-#177 behaviour",
        )
    }

    /**
     * The flag off, which has to be the behaviour that existed before this change and not an
     * approximation of it.
     */
    @Test
    fun `with tab_title=COMPOSITOR the tab keeps sway's synthetic name`() = swayTest {
        store.put(WmFlags.dockTabTitle, TabTitleSource.COMPOSITOR)
        val app = openTitled("aw-app1", PAGE)
        wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        val container = containerOf(app)

        val unmanaged = capture("flag-off")
        // Awakener issues nothing under COMPOSITOR, so its own report cannot be the probe here.
        // This reset is both the probe and the comparison state the assertion below needs.
        assumeTrue(
            "this sway will not name a container's tab",
            tryCommand(TabTitle.resetCommand(container)),
        )
        assertEquals(
            unmanaged,
            capture("flag-off-by-hand"),
            "with the flag off the tab has to be pixel-identical to sway's own naming",
        )
        command("[con_id=${container.raw}] title_format ${TabTitle.quote(PAGE)}")
        assertNotEquals(unmanaged, capture("flag-off-titled"), "the control still distinguishes")
    }

    /**
     * The one test here that asserts the same thing on every sway, and the reason the rest may
     * assume.
     *
     * A tab's name is cosmetic; a dock is not. So a compositor that refuses to name a container has
     * to cost the tab's title and **nothing else** — not the panel, not the binding, not the press.
     * Before this was true, sway 1.9 turned every attach in the repository into a failed hotkey:
     * 83 tests across five classes, all of them raising out of one `run` on a `title_format` the
     * compositor would not take.
     *
     * The refusal is reported rather than swallowed, and this asserts the report against a *direct*
     * probe of the same capability rather than against itself. Both sides are measured on whatever
     * compositor is running, so this is a real assertion on 1.9 and on 1.12 and says the opposite
     * thing on each.
     */
    @Test
    fun `attach survives a compositor that will not name a container's tab`() = swayTest {
        val app = openTitled("aw-app1", PAGE)
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        assertTrue(
            inTab(app, dock.dockId),
            "the attach has to go through whatever the compositor thinks of container titles",
        )

        val accepted = tryCommand(TabTitle.resetCommand(containerOf(app)))
        assertEquals(
            !accepted,
            wm.tabTitleUnsupported.value != null,
            "awakener's account of this compositor has to match what it actually does with a " +
                "container title_format. Probe accepted=$accepted, reported " +
                "unsupported=${wm.tabTitleUnsupported.value}",
        )
    }

    /**
     * A whole dock lifecycle must not switch tab titles off, and the latch is why that needs
     * saying.
     *
     * `nameTab` remembers a compositor that refused, so it can stop asking. The hazard is that a
     * refusal and a **lost race** arrive identically: a `con_id` whose node has left the tree
     * answers `success:false, error: "No matching node."` — measured — so a latch that fired on any
     * non-success would let one unlucky moment disable the feature for the rest of the session, on
     * a compositor that supports it perfectly well. That is why the failure path asks the tree
     * whether the container is still there rather than reading the message.
     *
     * This drives every path that issues one of these commands — attach, hide, show, detach, and
     * the reap — and asserts the report is still empty at the end. It cannot manufacture the race
     * (there is no seam between the tree read and the command through the public API), so what it
     * holds is the reachable half: no **routine** operation may latch. That is not a hypothetical
     * class of bug — the detach reset raised on a collapsed container earlier in this same PR, and
     * under a latch it would have gone quiet instead of loud.
     */
    @Test
    fun `no ordinary dock lifecycle switches tab titles off`() = swayTest {
        val app = openTitled("aw-app1", PAGE)
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        assumeContainerTitles()

        assertTrue(dock.hide(), "hidden")
        assertTrue(dock.show(), "and back")
        dock.detach()

        val second = openTitled("aw-app2", NEXT_PAGE)
        val orphan = wm.attach(second, dockSpec("aw-dock2"), AgentId("agent-2"))
        command("[con_id=${second.raw}] kill")
        assertTrue(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().find(second.raw) != null) yield()
                true
            } ?: false,
            "the surface has to be gone before the sweep can be about an orphan",
        )
        wm.reapOrphans()
        assertNull(
            wm.tree().find(orphan.dockId.raw),
            "the orphaned dock is gone, so the reap really ran the detach path",
        )

        assertNull(
            wm.tabTitleUnsupported.value,
            "an ordinary attach/hide/show/detach/reap must leave the capability believed-good. " +
                "A latch here would mean one of these paths issues a title command against a " +
                "node that has already left the tree, and every later tab would go unnamed with " +
                "nothing but this flow to say why",
        )
    }

    // ------------------------------------------------------------------ harness

    /**
     * Skips when this compositor will not name a container's tab, naming its own complaint.
     *
     * Called after an attach, because the attach is the probe: awakener has by then tried exactly
     * the command the rest of the test depends on, and [SwayWindowManager.tabTitleUnsupported]
     * carries what came back. A separate probe would be a second claim about the compositor that
     * could disagree with the product's.
     */
    private fun assumeContainerTitles() {
        val refusal = wm.tabTitleUnsupported.value
        assumeTrue("this sway will not name a container's tab: $refusal", refusal == null)
    }

    /** Whether sway accepted [text]; for asking about a capability rather than relying on one. */
    private suspend fun tryCommand(text: String): Boolean {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        return swayJson.decodeFromString<List<CommandResult>>(raw).all { it.success }
    }

    private fun swayTest(body: suspend CoroutineScope.() -> Unit) {
        if (!enabled) return
        runBlocking {
            command("layout tabbed")
            body()
        }
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        val results = swayJson.decodeFromString<List<CommandResult>>(raw)
        results.firstOrNull { !it.success }?.let { error("sway rejected '$text': ${it.error}") }
    }

    /**
     * A window whose title is [title], and optionally [then] once [on] appears.
     *
     * **The title is set by the program in the window**, with the OSC sequence a terminal
     * application uses and a browser's equivalent of `document.title`, rather than by a command
     * from this test. That matters for the injection case: a title handed to `foot -T` would have
     * to survive this test's own shell quoting on the way through `exec`, and a payload that
     * arrived mangled would make the suite pass for the wrong reason. Read from a file instead, so
     * no layer between here and the window ever parses it.
     */
    private suspend fun openTitled(
        appId: String,
        title: String,
        then: String? = null,
        on: File? = null,
    ): SurfaceId {
        val first = stateDir.resolve("$appId-title").also { it.writeText(title) }
        val script = buildString {
            append(OSC_TITLE.format(first.toAbsolutePath()))
            if (then != null && on != null) {
                val second = stateDir.resolve("$appId-title-2").also { it.writeText(then) }
                append("while [ ! -e ${on.absolutePath} ]; do sleep 0.05; done; ")
                append(OSC_TITLE.format(second.toAbsolutePath()))
            }
            append("exec sleep 3600")
        }
        command("exec foot -a $appId -- sh -c '$script'")
        val id = SurfaceId(assertNotNull(awaitWindow(appId), "window '$appId' never appeared"))
        assertTrue(awaitTitle(id, title), "window '$appId' never took the title it was given")
        return id
    }

    private fun dockSpec(appId: String) =
        DockSpec(appId, sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    /** The container the surface sits in, which is the node whose tab #177 is about. */
    private suspend fun containerOf(surface: SurfaceId): SurfaceId {
        val parent = assertNotNull(
            wm.tree().parentOf(surface.raw),
            "surface ${surface.raw} has no parent, so there is no tab to be about",
        )
        assertEquals(
            "con",
            parent.type,
            "the surface has to be inside a container rather than a workspace, or there is no tab",
        )
        return SurfaceId(parent.id)
    }

    private suspend fun tabHolding(surface: SurfaceId): Node? =
        wm.tree().workspace("1")?.children?.firstOrNull { it.find(surface.raw) != null }

    private suspend fun inTab(surface: SurfaceId, node: SurfaceId): Boolean =
        tabHolding(surface)?.find(node.raw) != null

    private suspend fun titleOf(id: SurfaceId): String? = wm.tree().find(id.raw)?.name

    /**
     * One frame of the compositor's output, as pixels, also written to `build/reports/tab-title`.
     *
     * Compared as an `IntArray` rather than as file bytes so a failure can say how many pixels
     * differ; kept as a [Frame] so `assertEquals` does not compare array identities.
     */
    private suspend fun capture(name: String): Frame = withContext(Dispatchers.IO) {
        val display = display ?: assertNotNull(
            sway.waylandDisplay(),
            "no client of this compositor has published a WAYLAND_DISPLAY, so grim has nothing " +
                "to connect to",
        ).also { display = it }
        val out = File(reports, "$name.png")
        val process = ProcessBuilder(SwayHarness.grimCommand(), out.absolutePath)
            .apply {
                environment()["WAYLAND_DISPLAY"] = display
                environment()["XDG_RUNTIME_DIR"] = System.getenv("XDG_RUNTIME_DIR")
                    ?: error("XDG_RUNTIME_DIR is unset, so the Wayland socket cannot be found")
            }
            .redirectErrorStream(true)
            .start()
        val log = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "grim failed capturing '$name': $log" }
        val image = assertNotNull(ImageIO.read(out), "grim wrote something that is not an image")
        Frame(
            name,
            image.width,
            image.height,
            image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
        )
    }

    /**
     * Captures until the frame differs from [from], which is how a change the compositor makes on
     * its own — a re-render after an event awakener reacted to — is waited for rather than raced.
     */
    private suspend fun awaitFrameOtherThan(from: Frame, name: String): Frame? =
        withTimeoutOrNull(WAIT_MS) {
            while (true) {
                val frame = capture(name)
                if (frame != from) return@withTimeoutOrNull frame
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            null
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

    private suspend fun awaitTitle(id: SurfaceId, title: String): Boolean =
        withTimeoutOrNull(WAIT_MS) {
            while (titleOf(id) != title) yield()
            true
        } ?: false

    private suspend fun awaitFile(file: File): Boolean = withTimeoutOrNull(WAIT_MS) {
        while (!file.exists()) yield()
        true
    } ?: false

    /**
     * One capture: the pixels, and the name of the file they were written to.
     *
     * [name] is deliberately outside the comparison — two frames are the same frame when they look
     * the same, whatever they were called — and is carried so a failure can point at the pair of
     * PNGs a reader should open.
     */
    private class Frame(
        val name: String,
        private val width: Int,
        private val height: Int,
        private val pixels: IntArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Frame &&
            width == other.width &&
            height == other.height &&
            pixels.contentEquals(other.pixels)

        override fun hashCode(): Int = pixels.contentHashCode()

        /** How many pixels differ, so a failure says whether it is a glyph or a whole repaint. */
        fun differences(other: Frame): Int =
            pixels.indices.count { it < other.pixels.size && pixels[it] != other.pixels[it] }

        override fun toString(): String =
            "$name (${width}x$height, build/reports/tab-title/$name.png)"
    }

    private companion object {
        const val WAIT_MS = 5_000L

        /** A page title with a space and a dash in it, so the render has something to be about. */
        const val PAGE = "Warbreaker - Wikipedia"

        /** What the same window retitles itself to, chosen to be a different width on screen. */
        const val NEXT_PAGE = "Nightblood"

        /** The `app_id` an injected `exec` would map a window under, and nothing else does. */
        const val INTRUDER = "aw-intruder"

        /**
         * How the window sets its own title: OSC 0, read out of a file whose path is `%s`.
         *
         * Double quotes inside, because the whole script is single-quoted for sway's argument
         * splitting and a nested `'` would close that wrapper — after which sway reads the rest of
         * the shell script as its own command language, which is a smaller cousin of the very bug
         * this suite is about.
         */
        const val OSC_TITLE = "printf \"\\033]0;%%s\\a\" \"\$(cat %s)\"; "
    }
}
