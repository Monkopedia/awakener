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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
 * What a page can and cannot paint into the tab strip, against a **markup-enabled** compositor.
 *
 * ### Why this is a class of its own
 *
 * Pango markup in title bars is switched on by a `font pango:…` line **in the config file** — a
 * runtime `font` command does not do it, measured on 1.12 — so this needs a compositor launched
 * differently from every other suite here. That is the whole reason for the separate class and for
 * [SwayHarness.start]'s parameter.
 *
 * ### The hazard, which this PR created rather than inherited
 *
 * sway markup-escapes the value it substitutes for `%title`. It does **not** escape the format
 * string's own literal text. Interpolating a page's title into that format string is exactly what
 * carries those bytes across that boundary, so a title of
 * `<span foreground='#ff0000'>…</span>` becomes red text in the tab rather than the characters the
 * page typed. It cannot execute anything — but it makes the tab strip page-controlled chrome, and
 * the first thing it defeats is the marker `wm.dock.tab_title.format = "* {title}"` exists to
 * provide: a user who sets it to tell woken tabs apart has that asterisk restyled or hidden by any
 * page that would rather they could not.
 *
 * ### The control is what makes this suite mean anything
 *
 * `a page cannot write pango markup into the tab strip` asserts two things, and the **second is
 * load-bearing**: that the raw, un-neutralised title renders *differently*. Without it, the test
 * would pass just as happily on a compositor where markup was never honoured — which is every
 * other compositor in this build, and is exactly how the first version of this measurement fooled
 * its author. See [TabTitle] for that story; the short version is that the probe used the one
 * attribute syntax the `"`-escaping self-defeats.
 *
 * Exercised against **sway 1.12** with `font pango:monospace 10`, headless wlroots, **grim 1.5**.
 */
class SwayTabTitleMarkupTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var registry: BindingStore
    private lateinit var stateDir: Path
    private lateinit var reports: File
    private var display: String? = null

    private val enabled get() = SwayHarness.available() && SwayHarness.grimAvailable()

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        SwayHarness.assumeGrim()
        // The line that turns markup on, and the only difference from every other session here.
        sway = SwayHarness.start(PANGO_FONT)
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-markup")
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
     * A page sets a title that is markup, under the flag whose marker it would hijack.
     *
     * The format is `* {title}` — the exact thing `wm.dock.tab_title.format`'s documentation
     * suggests for telling a woken tab apart — and the title is a `<span>` that would recolour and
     * resize everything after it, the asterisk included.
     */
    @Test
    fun `a page cannot write pango markup into the tab strip`() = swayTest {
        store.put(WmFlags.dockTabTitleFormat, "* ${TabTitle.TITLE_PLACEHOLDER}")
        val app = openTitled("aw-app1", MARKUP_TITLE)
        val dock = wm.attach(app, dockSpec("aw-dock"), AgentId("agent-1"))
        assertTrue(inTab(app, dock.dockId), "the dock is beside its surface")
        assumeContainerTitles()
        val container = containerOf(app)

        val managed = capture("markup-managed")

        // THE CONTROL, and the reason this test is not vacuous: the same tab, named with the same
        // text but *without* the neutralisation. If markup were not live on this compositor this
        // would render identically and the assertion below would be asserting nothing.
        command("[con_id=${container.raw}] title_format \"* $MARKUP_TITLE\"")
        val raw = capture("markup-raw")
        assertNotEquals(
            managed,
            raw,
            "awakener's tab renders identically to the raw markup one, which has exactly two " +
                "causes and both matter: either the neutralisation is gone and a page is writing " +
                "markup into the tab strip, or markup is not honoured on this compositor and " +
                "this test proves nothing. Open the two PNGs — styled text means the first",
        )

        command("[con_id=${container.raw}] title_format ${TabTitle.quote("* $MARKUP_TITLE")}")
        assertEquals(
            managed,
            capture("markup-by-hand"),
            "awakener's tab has to be the neutralised rendering — the characters the page typed, " +
                "shown as text — rather than the page's markup applied to the tab strip",
        )
    }

    /**
     * The same claim from the other side: what the page wrote is *readable*, not merely inert.
     *
     * A rule that defeated markup by mangling the title into something else would pass the test
     * above and still be wrong, and entity-escaping — the obvious fix, and the one the review
     * proposed — is wrong in exactly that way *half the time*: `&amp;` renders as `&` on a
     * markup compositor and as five literal characters without one, and which of those a user has
     * is a `font pango:` line awakener cannot see.
     *
     * So this holds the property that makes a zero-width space the right tool instead: the
     * neutralised string renders **identically whether or not the compositor is parsing markup**.
     * Two sessions, one leaf window each, the same `title_format` — the config line is the only
     * difference between them, which is what makes the comparison about that line.
     */
    @Test
    fun `a neutralised title renders the same with markup on and off`() = swayTest {
        val quoted = TabTitle.quote(MARKUP_TITLE)
        val onMarkup = leafRender(sway, quoted, "markup-on")

        // THE CONTROL: the same tab on the same compositor with the neutralisation removed. If
        // this matched, markup would not be live here and the equality below would be vacuous —
        // which is the trap the first version of this measurement fell into.
        val rawOnMarkup = leafRender(sway, "\"$MARKUP_TITLE\"", "markup-on-raw")
        assertNotEquals(
            onMarkup,
            rawOnMarkup,
            "the neutralised and raw renders are identical, so either the rule is gone or this " +
                "compositor is not parsing markup; in both cases the comparison below is empty",
        )

        SwayHarness.start().use { plain ->
            assertEquals(
                onMarkup,
                leafRender(plain, quoted, "markup-off"),
                "the neutralised title has to look the same on a compositor that parses markup " +
                    "and one that does not. That is the whole reason the rule is a zero-width " +
                    "space and not entity-escaping",
            )
        }
    }

    // ------------------------------------------------------------------ harness

    /**
     * One leaf window on [harness], named with [quoted], captured.
     *
     * A **leaf** rather than the split container the rest of this file is about, and deliberately:
     * what this frame is evidence for is how a *string* renders, which does not depend on the kind
     * of node carrying it — and a leaf is the one node kind every sway in this build will accept a
     * `title_format` on, so the comparison does not quietly become a second capability test.
     *
     * Idempotent per harness: the window is opened once and re-titled afterwards, so two renders
     * on one compositor differ in the title and in nothing else.
     */
    private suspend fun leafRender(harness: SwayHarness, quoted: String, name: String): Frame {
        plainCommand(harness, "layout tabbed")
        val existing = plainTree(harness).windows.firstOrNull { it.appId == LEAF_APP_ID }
        val id = existing?.id ?: run {
            plainCommand(harness, "exec ${harness.windowCommand(LEAF_APP_ID)}")
            assertNotNull(
                withTimeoutOrNull(WAIT_MS) {
                    while (true) {
                        plainTree(harness).windows.firstOrNull { it.appId == LEAF_APP_ID }
                            ?.let { return@withTimeoutOrNull it.id }
                        yield()
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                },
                "compositor never produced a window to name",
            )
        }
        plainCommand(harness, "[con_id=$id] title_format $quoted")
        return captureOn(harness, name)
    }

    private suspend fun plainCommand(harness: SwayHarness, text: String) {
        val raw = harness.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        swayJson.decodeFromString<List<CommandResult>>(raw)
            .firstOrNull { !it.success }
            ?.let { error("sway rejected '$text': ${it.error}") }
    }

    private suspend fun plainTree(harness: SwayHarness): Node {
        val raw = harness.connection().use { it.request(I3Ipc.Request.GET_TREE, "") }
        return swayJson.decodeFromString(raw)
    }

    private fun swayTest(body: suspend CoroutineScope.() -> Unit) {
        if (!enabled) return
        runBlocking {
            command("layout tabbed")
            body()
        }
    }

    private fun assumeContainerTitles() {
        val refusal = wm.tabTitleUnsupported.value
        assumeTrue("this sway will not name a container's tab: $refusal", refusal == null)
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        swayJson.decodeFromString<List<CommandResult>>(raw)
            .firstOrNull { !it.success }
            ?.let { error("sway rejected '$text': ${it.error}") }
    }

    /** See `SwayTabTitleTest.openTitled`: the window sets its own title, from a file. */
    private suspend fun openTitled(appId: String, title: String): SurfaceId {
        val file = stateDir.resolve("$appId-title").also { it.writeText(title) }
        val script = "printf \"\\033]0;%s\\a\" \"\$(cat ${file.toAbsolutePath()})\"; exec sleep 3600"
        command("exec foot -a $appId -- sh -c '$script'")
        val id = SurfaceId(
            assertNotNull(
                withTimeoutOrNull(WAIT_MS) {
                    while (true) {
                        wm.tree().windows.firstOrNull { it.appId == appId }
                            ?.let { return@withTimeoutOrNull it.id }
                        yield()
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                },
                "window '$appId' never appeared",
            ),
        )
        assertTrue(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().find(id.raw)?.name != title) yield()
                true
            } ?: false,
            "window '$appId' never took the title it was given",
        )
        return id
    }

    private fun dockSpec(appId: String) =
        DockSpec(appId, sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    private suspend fun containerOf(surface: SurfaceId): SurfaceId {
        val parent = assertNotNull(
            wm.tree().parentOf(surface.raw),
            "surface ${surface.raw} has no parent, so there is no tab to be about",
        )
        assertEquals("con", parent.type, "the surface has to be inside a container")
        return SurfaceId(parent.id)
    }

    private suspend fun inTab(surface: SurfaceId, node: SurfaceId): Boolean =
        wm.tree().workspace("1")?.children?.firstOrNull { it.find(surface.raw) != null }
            ?.find(node.raw) != null

    private suspend fun capture(name: String): Frame = captureOn(sway, name, ::display::get)

    private suspend fun captureOn(
        harness: SwayHarness,
        name: String,
        cached: (() -> String?)? = null,
    ): Frame = withContext(Dispatchers.IO) {
        val resolved = cached?.invoke() ?: assertNotNull(
            harness.waylandDisplay(),
            "no client of this compositor has published a WAYLAND_DISPLAY for grim to reach",
        )
        if (harness === sway) display = resolved
        val out = File(reports, "$name.png")
        val process = ProcessBuilder(SwayHarness.grimCommand(), out.absolutePath)
            .apply {
                environment()["WAYLAND_DISPLAY"] = resolved
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

    private class Frame(
        val name: String,
        private val width: Int,
        private val height: Int,
        val pixels: IntArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Frame &&
            width == other.width &&
            height == other.height &&
            pixels.contentEquals(other.pixels)

        override fun hashCode(): Int = pixels.contentHashCode()

        override fun toString(): String =
            "$name (${width}x$height, build/reports/tab-title/$name.png)"
    }

    private companion object {
        const val WAIT_MS = 5_000L

        /** The line that enables markup. A runtime `font` command does not; measured on 1.12. */
        const val PANGO_FONT = "font pango:monospace 10"

        /**
         * A title that recolours and enlarges everything after it, including a leading marker.
         *
         * Single-quoted attribute deliberately: the double-quoted form is neutralised by accident,
         * because the `"`-escaping puts a backslash inside the attribute and pango then fails to
         * parse. Testing only that form is how the first measurement of this concluded, wrongly,
         * that markup was inert here.
         */
        const val MARKUP_TITLE =
            "<span foreground='#ff0000' size='xx-large'>HIJACKED</span>"

        /** The one window the mode comparison renders, on whichever compositor it is asking. */
        const val LEAF_APP_ID = "aw-leaf"
    }
}
