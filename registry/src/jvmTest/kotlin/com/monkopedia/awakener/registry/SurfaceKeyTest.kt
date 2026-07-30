package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
