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
            SurfaceKey.Titled("Some X11 App"),
            SurfaceKey.Titled("vim: SurfaceKey.kt", "91"),
            SurfaceKey.Unidentified(),
            SurfaceKey.Unidentified("91"),
            SurfaceKey.Origin("https://github.com"),
        ).forEach { key ->
            assertEquals(key, SurfaceKey.parse(key.canonical), "round trip of ${key.canonical}")
        }
    }

    /**
     * A bindings file written before #95 keys every fallback surface as `window:<title>`, and
     * those entries have to keep parsing: they are read back as [SurfaceKey.Window], which is
     * exactly what they say, and a build that rejected them would report the user's own
     * bindings as unreadable and stop writing the file at all.
     *
     * They resolve for nothing, because no descriptor mints that key any more — that is the
     * migration, and it is a stale entry rather than a broken file.
     */
    @Test
    fun `a pre-existing window key still parses as the window key it says it is`() {
        assertEquals(SurfaceKey.Window("Some X11 App"), SurfaceKey.parse("window:Some X11 App"))
        assertEquals(
            SurfaceKey.Window(SurfaceKey.UNIDENTIFIED),
            SurfaceKey.parse("window:unidentified"),
        )
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
        assertNull(SurfaceKey.parse("title"))
        assertNull(SurfaceKey.parse("title:a:b:c"))
        assertNull(SurfaceKey.parse("unidentified:a:b"))
        // The colon-less kind reads as a kind, which is what `unidentified` needs — but only
        // for a kind that takes no segment. `window` alone is still a malformed window key.
        assertEquals(SurfaceKey.Unidentified(), SurfaceKey.parse("unidentified"))
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
     * #95, and the reason the two tests above did not catch it: both call
     * [assertMintsSeparately] with descriptors *of the same shape* — two `app_id`s, or two
     * titles — so neither ever asks whether one shape can reach the other's key. It can. The
     * title is substituted into the `app_id` slot, so a window reporting no `app_id` and a
     * title of `firefox` renders the identical `canonical`, and the digest is never reached.
     *
     * This is not something a wider digest can defend: the two inputs to it are the same
     * bytes, so the cost of aiming at another surface's agent is zero rather than 2^128.
     */
    @Test
    fun `a title cannot mint the agent of a window whose app_id it copies`() = runTest {
        assertMintsSeparately(
            SurfaceDescriptor(null, "firefox", 1),
            SurfaceDescriptor("firefox", "Anything at all", 2),
        )
    }

    /**
     * The same defect through the fallback rather than the title, and it needs no xwayland at
     * all: any window with neither an `app_id` nor a title lands on [SurfaceKey.UNIDENTIFIED],
     * so a Wayland client that simply declares `app_id=unidentified` — `foot -a unidentified`
     * — joins every one of them. Reachable at stock flags, not only under
     * [MissingAppId.UNIDENTIFIED].
     */
    @Test
    fun `a client naming itself unidentified cannot join the windows that have no name`() =
        runTest {
            assertMintsSeparately(
                SurfaceDescriptor(null, null, 1),
                SurfaceDescriptor(SurfaceKey.UNIDENTIFIED, "Anything at all", 2),
            )
        }

    /**
     * Neither route is an artefact of one flag setting. [RegistryFlags.windowIdentity] chooses
     * the discriminator and [RegistryFlags.missingAppId] chooses the fallback, so the pair is
     * swept: a surface's provenance has to survive every combination, or the defect is merely
     * relocated to a setting nobody tested.
     */
    @Test
    fun `provenance separates under every combination of the key flags`() = runTest {
        WindowIdentity.entries.forEach { identity ->
            MissingAppId.entries.forEach { missing ->
                val config = InMemoryConfigStore()
                    .put(RegistryFlags.windowIdentity, identity)
                    .put(RegistryFlags.missingAppId, missing)
                    .config.value
                // Same pid and same title throughout, so the discriminator is identical for
                // both members of each pair and cannot be what separates them.
                assertMintsSeparately(
                    SurfaceDescriptor(null, "firefox", 7),
                    SurfaceDescriptor("firefox", "firefox", 7),
                    config,
                    "$identity/$missing: a title keyed as an app_id",
                )
                assertMintsSeparately(
                    SurfaceDescriptor(null, null, 7),
                    SurfaceDescriptor(SurfaceKey.UNIDENTIFIED, null, 7),
                    config,
                    "$identity/$missing: a client naming itself unidentified",
                )
            }
        }
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
        config: Config = Config.EMPTY,
        case: String = "",
    ) {
        val store = InMemoryBindingStore()
        val a = SurfaceKey.of(first, config)
        val b = SurfaceKey.of(second, config)
        assertNotEquals(
            a,
            b,
            "$case: the descriptors must key differently or this proves nothing",
        )
        assertNotEquals(a.slug, b.slug, "$case: distinct keys must not share a slug")
        assertNotEquals(
            store.residueLocation(a),
            store.residueLocation(b),
            "$case: distinct keys must not share a residue file",
        )
        val bindings = listOf(store.bind(a), store.bind(b))
        assertNotEquals(
            bindings[0].agentId,
            bindings[1].agentId,
            "$case: distinct keys must not mint one agent id",
        )
        assertNotEquals(
            bindings[0].spanreedName,
            bindings[1].spanreedName,
            "$case: distinct keys must not mint one SPANREED_AGENT_NAME",
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
     *
     * It keys on the title as a [SurfaceKey.Titled], **not** as a [SurfaceKey.Window] holding a
     * title. The kind is the assertion: the fallback still happens, and it no longer lands in
     * the namespace a real `app_id` occupies.
     */
    @Test
    fun `a window with no app id falls back to its title, in its own namespace`() {
        val key = SurfaceKey.of(SurfaceDescriptor(null, "Some X11 App", 5), Config.EMPTY)
        assertEquals(SurfaceKey.Titled("Some X11 App"), key)
        assertEquals("title:Some X11 App", key.canonical)

        val nameless = SurfaceKey.of(SurfaceDescriptor(null, null, null), Config.EMPTY)
        assertEquals(SurfaceKey.Unidentified(), nameless)
        assertEquals("unidentified", nameless.canonical)
    }

    /**
     * The property the two kinds above exist for, stated directly on `canonical` rather than
     * through the minting chain: the string an `app_id` renders and the string a title renders
     * are different strings even when the `app_id` and the title are the same text.
     *
     * Worth pinning separately because it holds *by construction* — the kinds are literals this
     * file chooses and the segments are escaped — so this test is a guard against someone
     * reintroducing a shared namespace, not evidence that the namespaces are currently unshared.
     */
    @Test
    fun `an app_id and an identical title render different canonical forms`() {
        assertEquals("window:firefox", SurfaceKey.Window("firefox").canonical)
        assertEquals("title:firefox", SurfaceKey.Titled("firefox").canonical)
        assertNotEquals(
            SurfaceKey.Window("firefox").canonical,
            SurfaceKey.Titled("firefox").canonical,
        )
        // The pre-#95 pool: this string in an app_id used to render what an app_id-less
        // window rendered. It no longer can, because Unidentified has no segment to match.
        assertNotEquals(
            SurfaceKey.Window(SurfaceKey.UNIDENTIFIED).canonical,
            SurfaceKey.Unidentified().canonical,
        )
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
            SurfaceKey.Unidentified(),
            SurfaceKey.of(SurfaceDescriptor(null, "Some X11 App", 5), config),
        )
        assertEquals(
            SurfaceKey.Window("firefox"),
            SurfaceKey.of(SurfaceDescriptor("firefox", "Some X11 App", 5), config),
            "a window that does report an app_id is untouched by the flag",
        )
    }

    /**
     * The discriminator is orthogonal to the namespace, so a fallback key splits by
     * [RegistryFlags.windowIdentity] exactly as an `app_id` key does. Pinned because the fix
     * moved where the discriminator is computed — it is now decided once, before the kind is —
     * and a fallback that quietly stopped splitting would put a whole desktop's xwayland
     * windows back on one agent under `APP_ID_AND_PID`.
     */
    @Test
    fun `a fallback key splits by the same discriminator an app_id key does`() {
        val xwayland = SurfaceDescriptor(null, "Some X11 App", 5)
        assertEquals(
            SurfaceKey.Titled("Some X11 App", "5"),
            SurfaceKey.of(xwayland, identity(WindowIdentity.APP_ID_AND_PID)),
        )
        val nameless = SurfaceDescriptor(null, null, 5)
        assertEquals(
            SurfaceKey.Unidentified("5"),
            SurfaceKey.of(nameless, identity(WindowIdentity.APP_ID_AND_PID)),
        )
        assertNotEquals(
            SurfaceKey.of(nameless, identity(WindowIdentity.APP_ID_AND_PID)),
            SurfaceKey.of(SurfaceDescriptor(null, null, 6), identity(WindowIdentity.APP_ID_AND_PID)),
        )
    }

    private fun identity(value: WindowIdentity): Config =
        InMemoryConfigStore().put(RegistryFlags.windowIdentity, value).config.value
}
