package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SurfaceKeyTest {
    @Test
    fun `keys round-trip through their canonical form`() {
        listOf(
            SurfaceKey.Window("firefox"),
            SurfaceKey.Window("org.gnome.Nautilus", "~/git/awakener"),
            SurfaceKey.Origin("https://github.com"),
        ).forEach { key ->
            assertEquals(key, SurfaceKey.parse(key.canonical), "round trip of ${key.canonical}")
        }
    }

    /**
     * The canonical form is the on-disk key, so it has to be injective. A window title is
     * arbitrary user text and will contain the separator sooner or later; escaping is what
     * stops two different surfaces from colliding onto one binding.
     */
    @Test
    fun `separators and escapes inside a segment survive`() {
        val key = SurfaceKey.Window("app", "10:30 — 100% done : maybe")
        assertEquals(key, SurfaceKey.parse(key.canonical))

        val colliding = SurfaceKey.Window("app:x", "y")
        assertNotEquals(colliding.canonical, SurfaceKey.Window("app", "x:y").canonical)
        assertEquals(colliding, SurfaceKey.parse(colliding.canonical))
    }

    @Test
    fun `an unrecognised key form is rejected rather than guessed at`() {
        assertNull(SurfaceKey.parse("tab:12"))
        assertNull(SurfaceKey.parse("window"))
        assertNull(SurfaceKey.parse("window:a:b:c"))
    }

    /**
     * The slug becomes both a filename and a SPANREED_AGENT_NAME, so it must be safe in both
     * and must not collapse two surfaces onto one name.
     */
    @Test
    fun `slugs are safe, stable, and distinct`() {
        val origin = SurfaceKey.Origin("https://github.com/Monkopedia/awakener/pulls?q=is%3Aopen")
        assertTrue(
            origin.slug.all { it.isLetterOrDigit() || it == '-' },
            "slug '${origin.slug}' must be filesystem- and shell-safe",
        )
        assertEquals(origin.slug, SurfaceKey.Origin(origin.origin).slug, "slugs are deterministic")

        val truncatedAway = SurfaceKey.Origin("https://github.com/Monkopedia/awakener/issues")
        assertNotEquals(
            origin.slug,
            truncatedAway.slug,
            "two origins that differ only past the truncation point must still differ",
        )
    }

    /**
     * The `app_id` half of #13. Both strings are things an ordinary desktop program can ask the
     * compositor for — `foot -a <anything>`, or a Chrome `--app=URL` toplevel — and they are a
     * real collision under the 32-bit FNV-1a this replaced: sanitised prefixes equal past the
     * 48-character truncation, digests equal, so the second surface was handed the first one's
     * agent id and residue file.
     */
    @Test
    fun `a program cannot choose an app_id that mints another surface's agent`() = runTest {
        val appId = "chrome-github.com__Monkopedia__awakener__pull__11-Default"
        val impostor = "chrome-github.com__Monkopedia__awakener__pull__hOtRa4"
        assertMintsSeparately(
            SurfaceDescriptor(appId, "Pull request 11", 1),
            SurfaceDescriptor(impostor, "Anything at all", 2),
        )
    }

    /**
     * The title half of #13, which is the wider hole: [RegistryFlags.missingAppId] defaults to
     * [MissingAppId.TITLE], so a window reporting no `app_id` is keyed on its title — and a
     * title is set by whatever runs in the window, including remote output in a terminal.
     */
    @Test
    fun `a program cannot choose a title that mints another surface's agent`() = runTest {
        val title = "IntelliJ IDEA - awakener - registry/src/commonMain/SurfaceKey.kt"
        val impostor = "IntelliJ IDEA - awakener - registry/src/commonMain/fz4kCM"
        assertMintsSeparately(
            SurfaceDescriptor(null, title, 1),
            SurfaceDescriptor(null, impostor, 2),
        )
    }

    /**
     * Asserts the whole downstream chain separates, not just the keys. The slug becomes the
     * `SPANREED_AGENT_NAME`, which becomes the `agent_id` spanreed routes on, and separately the
     * residue path — so any one of these agreeing is one surface addressable as, or reading the
     * accumulated model of, another.
     */
    private suspend fun assertMintsSeparately(
        first: SurfaceDescriptor,
        second: SurfaceDescriptor,
    ) {
        val store = InMemoryBindingStore()
        val a = SurfaceKey.of(first, Config.EMPTY)
        val b = SurfaceKey.of(second, Config.EMPTY)
        assertNotEquals(a, b, "the descriptors must key differently or this proves nothing")

        assertNotEquals(a.slug, b.slug, "distinct keys must not share a slug")
        assertNotEquals(
            store.residueLocation(a),
            store.residueLocation(b),
            "distinct keys must not share a residue file",
        )
        val bindings = listOf(store.bind(a), store.bind(b))
        assertNotEquals(
            bindings[0].agentId,
            bindings[1].agentId,
            "distinct keys must not mint one agent id",
        )
        assertNotEquals(
            bindings[0].spanreedName,
            bindings[1].spanreedName,
            "distinct keys must not mint one SPANREED_AGENT_NAME",
        )
    }

    @Test
    fun `an origin surface is origin-keyed whatever the window identity flag says`() {
        val config = InMemoryConfigStore()
            .put(RegistryFlags.windowIdentity, WindowIdentity.APP_ID_AND_TITLE)
            .config.value
        val descriptor = SurfaceDescriptor(
            appId = "google-chrome",
            title = "Pull requests",
            pid = 42,
            origin = "https://github.com",
        )
        assertEquals(SurfaceKey.Origin("https://github.com"), SurfaceKey.of(descriptor, config))
    }

    @Test
    fun `window identity decides how finely windows are split`() {
        val descriptor = SurfaceDescriptor(appId = "foot", title = "vim: Flag.kt", pid = 91)

        assertEquals(SurfaceKey.Window("foot"), SurfaceKey.of(descriptor, Config.EMPTY))
        assertEquals(
            SurfaceKey.Window("foot", "vim: Flag.kt"),
            SurfaceKey.of(descriptor, identity(WindowIdentity.APP_ID_AND_TITLE)),
        )
        assertEquals(
            SurfaceKey.Window("foot", "91"),
            SurfaceKey.of(descriptor, identity(WindowIdentity.APP_ID_AND_PID)),
        )
    }

    /**
     * "Every surface gets an agent" is an invariant of the design, so a window with no app_id —
     * which xwayland routinely produces — has to key on something rather than be dropped.
     */
    @Test
    fun `a window with no app id falls back to its title`() {
        val key = SurfaceKey.of(SurfaceDescriptor(null, "Some X11 App", 5), Config.EMPTY)
        assertEquals(SurfaceKey.Window("Some X11 App"), key)

        val nameless = SurfaceKey.of(SurfaceDescriptor(null, null, null), Config.EMPTY)
        assertEquals(SurfaceKey.Window(SurfaceKey.UNIDENTIFIED), nameless)
    }

    /**
     * A title churns — it carries the open document, a dirty marker, a line number — so keying
     * on it orphans the residue accumulated under the old title. The alternative collapses every
     * app_id-less window onto one agent, which is why it is a flag rather than a fix.
     */
    @Test
    fun `the title fallback can be switched off for something that does not churn`() {
        val config = InMemoryConfigStore()
            .put(RegistryFlags.missingAppId, MissingAppId.UNIDENTIFIED)
            .config.value

        assertEquals(
            SurfaceKey.Window(SurfaceKey.UNIDENTIFIED),
            SurfaceKey.of(SurfaceDescriptor(null, "Some X11 App", 5), config),
        )
        assertEquals(
            SurfaceKey.Window("firefox"),
            SurfaceKey.of(SurfaceDescriptor("firefox", "Some X11 App", 5), config),
            "a window that does report an app_id is untouched by the flag",
        )
    }

    private fun identity(value: WindowIdentity): Config =
        InMemoryConfigStore().put(RegistryFlags.windowIdentity, value).config.value
}
