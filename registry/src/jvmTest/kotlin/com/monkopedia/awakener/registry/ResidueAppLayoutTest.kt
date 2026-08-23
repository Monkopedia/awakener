package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * [ResidueLayout.PER_APP_SESSION_DIR] — `<residue>/<app>/<session>/`.
 *
 * Two flat layouts existed before this one and neither had anywhere to put something two windows
 * of one application share; this gives the application a directory and each surface a folder
 * inside it.
 *
 * **The app component is the part that needs guarding and the part these tests are mostly
 * about.** It is derived from `app_id`, or from a window title, which is to say from a string the
 * program owning the window chooses: `foot -a '../../..'` is a command anyone can type. Every
 * escape below is attempted against the real path-building call rather than against a sanitiser
 * in isolation, because the claim is about where residue lands and not about what a helper
 * returns.
 */
class ResidueAppLayoutTest {
    private val dir = createTempDirectory("awakener-residue-app")
    private val config = InMemoryConfigStore()
    private val identities = AgentIdentities { key, _ ->
        AgentIdentity(AgentId("agent-${key.slug}"), "lifeless-${key.slug}")
    }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun store(name: String = "bindings.json") =
        FileBindingStore(config, identities, environment = emptyMap(), path = dir.resolve(name))

    private val residueDir: Path get() = dir.resolve("residue")

    private fun nested() = config.put(RegistryFlags.residueLayout, ResidueLayout.PER_APP_SESSION_DIR)

    // --- what the layout is for -------------------------------------------------------------

    /**
     * Jason's request in one assertion: two windows of one application land in one directory,
     * which is the shared workspace neither flat layout has.
     */
    @Test
    fun `two windows of one application share a parent directory`() = runTest {
        nested()
        val store = store()
        val editor = SurfaceKey.Window("org.gnome.TextEditor", "notes.md")
        val other = SurfaceKey.Window("org.gnome.TextEditor", "budget.md")

        val a = store.prepareResidue(editor)
        val b = store.prepareResidue(other)

        assertNotEquals(a, b, "each window still gets its own session folder")
        assertEquals(a.parent, b.parent, "and both sit under one application directory")
        assertEquals(residueDir, a.parent.parent, "which is a direct child of the residue dir")
        assertTrue(a.isDirectory() && b.isDirectory())
        // The shared parent is writable and readable by both, which is the whole point.
        a.parent.resolve("shared.md").writeText("this app's conventions")
        assertEquals("this app's conventions", b.parent.resolve("shared.md").readText())
    }

    /** A different application is a different directory, not a sibling session. */
    @Test
    fun `a different application gets a different directory`() = runTest {
        nested()
        val store = store()
        val one = store.prepareResidue(SurfaceKey.Window("firefox", "a"))
        val two = store.prepareResidue(SurfaceKey.Window("chromium", "a"))
        assertNotEquals(one.parent, two.parent)
    }

    /** The session component is the existing per-key slug, unchanged by the extra nesting. */
    @Test
    fun `the session folder is named by the same key slug the flat layouts use`() = runTest {
        val key = SurfaceKey.Window("firefox", "a")
        val flat = store().residueLocation(key)
        assertEquals("${key.slug}.md", Path(flat).name)

        nested()
        val location = Path(store().residueLocation(key))
        assertEquals(key.slug, location.name)
        assertEquals(key.appSlug, location.parent.name)
    }

    /** The default is untouched: an unconfigured system is laid out exactly as it was. */
    @Test
    fun `the default layout is still one flat file per surface`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val prepared = store().prepareResidue(key)
        assertEquals(residueDir, prepared.parent, "no new nesting level appears by default")
        assertEquals("${key.slug}.md", prepared.name)
        assertFalse(prepared.isDirectory())
    }

    // --- path safety ------------------------------------------------------------------------

    /**
     * The security question of this change, attempted rather than asserted about.
     *
     * Each of these is a string a window can hand the compositor as its `app_id`. None of them
     * may name anything outside the residue directory, and none may invent a nesting level: the
     * residue of a surface is always `<residue>/<one name>/<one name>`.
     *
     * **This is the test the mutation evidence in the PR body is taken against.** Building the
     * app component out of `appId` rather than `appSlug` — the obvious implementation, and the
     * one a reviewer would have to notice — makes the first case here write into the temporary
     * directory's grandparent and the NUL case throw out of `Path.resolve`.
     */
    @Test
    fun `no app_id a window can choose escapes the residue directory`() = runTest {
        nested()
        val store = store()
        val hostile = listOf(
            "../../../etc",
            "..",
            ".",
            "../".repeat(40) + "tmp/awakener-escape",
            "/etc/passwd",
            "/",
            "a\u0000b",
            "a/b",
            "..\\..\\windows",
            "~",
            "‮" + "gnp.txt",
            "",
            " ",
            "-",
            "....",
            "%2e%2e%2f%2e%2e",
        )

        hostile.forEach { appId ->
            val key = SurfaceKey.Window(appId, "session")
            val location = store.prepareResidue(key)
            val real = location.toRealPath()
            val root = residueDir.toRealPath()

            assertTrue(
                real.startsWith(root) && real != root,
                "app_id ${appId.escaped()} put residue at $real, outside $root",
            )
            assertEquals(
                2,
                root.relativize(real).nameCount,
                "app_id ${appId.escaped()} produced $real, which is not <app>/<session>",
            )
            val app = root.relativize(real).getName(0).toString()
            assertTrue(
                app.matches(SAFE_NAME) && app != "." && app != "..",
                "app_id ${appId.escaped()} became directory '$app'",
            )
            assertTrue(location.isDirectory(), "and it is a real directory: $location")
        }
        // Nothing was created outside the residue directory by any of the above. Asserted as
        // "nothing unexpected is here" rather than "the escape target is absent", because the
        // list above aims at several different places and only one of them is nameable here.
        assertEquals(
            setOf("residue"),
            dir.toFile().list()!!.toSet(),
            "an escape would have left something beside the residue directory",
        )
    }

    /** The same strings in the *title* slot, which is where a key with no `app_id` reads from. */
    @Test
    fun `no window title escapes it either`() = runTest {
        nested()
        val store = store()
        listOf("../../../etc", "/etc/passwd", "a\u0000b", "..").forEach { title ->
            val real = store.prepareResidue(SurfaceKey.Titled(title)).toRealPath()
            val root = residueDir.toRealPath()
            assertTrue(real.startsWith(root), "title ${title.escaped()} landed at $real")
            assertEquals(2, root.relativize(real).nameCount, "title ${title.escaped()}: $real")
        }
    }

    /**
     * The second guard, exercised directly.
     *
     * [SurfaceKey.appSlug] makes an escaping component unconstructible, and a guard that cannot
     * be handed a hostile input is a guard nothing can show firing. [RegistryPaths.containedResolve]
     * is the property itself — no residue path leaves the residue directory, and no component
     * invents a nesting level — so it takes the hostile component directly and refuses it.
     */
    @Test
    fun `a residue component that is not a single name under the residue dir is refused`() {
        val base = dir.resolve("residue")
        listOf(
            listOf(".."),
            listOf("app", ".."),
            listOf("app", "..", "..", "etc"),
            listOf("/etc"),
            listOf(""),
            listOf("a/b"),
            listOf("."),
            listOf("a\u0000b"),
        ).forEach { segments ->
            assertFailsWith<IllegalArgumentException>("$segments was resolved rather than refused") {
                RegistryPaths.containedResolve(base, segments)
            }
        }
        // And it is not refusing everything: a single name is a single name, spaces included,
        // and the two-component shape the layout actually produces goes through.
        assertEquals(
            base.resolve("app").resolve("session"),
            RegistryPaths.containedResolve(base, listOf("app", "session")),
        )
        assertEquals(base.resolve("a b"), RegistryPaths.containedResolve(base, listOf("a b")))
    }

    // --- collisions -------------------------------------------------------------------------

    /**
     * Two applications whose names flatten to the same readable text must not share a residue
     * directory: that would be two agents' models of Jason accumulating in one place, which is
     * the failure the per-key digest exists to prevent, one level up.
     */
    @Test
    fun `two applications that flatten to the same readable name stay separate`() {
        val pairs = listOf(
            "Foo/Bar" to "Foo:Bar",
            "my.app" to "my-app",
            "a b" to "a_b",
            "café" to "cafe",
        )
        pairs.forEach { (left, right) ->
            val a = SurfaceKey.Window(left).appSlug
            val b = SurfaceKey.Window(right).appSlug
            assertNotEquals(a, b, "'$left' and '$right' both became $a")
        }
    }

    /**
     * #95 at the application level. `app_id` and title are both chosen by the program that owns
     * the window, so a grouping that folded them into one slot would let `xterm -T firefox`
     * share Firefox's application directory — and share it *above* the session folders, which
     * is worse than sharing a leaf.
     */
    @Test
    fun `a title cannot reach an app_id's application directory`() {
        assertNotEquals(
            SurfaceKey.Window("firefox").appSlug,
            SurfaceKey.Titled("firefox").appSlug,
        )
        assertNotEquals(
            SurfaceKey.Window(SurfaceKey.UNIDENTIFIED).appSlug,
            SurfaceKey.Unidentified().appSlug,
        )
        assertNotEquals(
            SurfaceKey.Window("https://github.com").appSlug,
            SurfaceKey.Origin("https://github.com").appSlug,
        )
    }

    /** The discriminator splits sessions and must not split applications. */
    @Test
    fun `the per-window discriminator does not reach the application directory`() {
        val key = SurfaceKey.Window("firefox", "some-title")
        assertEquals(SurfaceKey.Window("firefox").appSlug, key.appSlug)
        assertNotEquals(SurfaceKey.Window("firefox").slug, key.slug)
    }

    /**
     * An app id that is absent or empty still names a directory. `SurfaceKey.of` will not mint
     * these, but `parse` reads them out of a hand-edited bindings file, and a layout that threw
     * there would take the hotkey down over a stray colon.
     */
    @Test
    fun `an empty or absent application still names one directory`() = runTest {
        nested()
        val store = store()
        val keys = listOfNotNull(
            SurfaceKey.parse("window:"),
            SurfaceKey.parse("title:"),
            SurfaceKey.parse("unidentified"),
            SurfaceKey.parse("origin:"),
        )
        assertEquals(4, keys.size, "all four kinds parse")
        keys.forEach { key ->
            val real = store.prepareResidue(key).toRealPath()
            val relative = residueDir.toRealPath().relativize(real)
            assertEquals(2, relative.nameCount, "$key landed at $real")
            assertTrue(relative.getName(0).toString().matches(SAFE_NAME), "$key: $relative")
        }
        assertEquals(
            keys.size,
            keys.map { it.appSlug }.toSet().size,
            "and the four empty heads are still four different applications",
        )
    }

    // --- what the nesting does not change ---------------------------------------------------

    /**
     * The exposure check's subject is the residue directory and the directories *above* it, so a
     * nesting level *below* it cannot move it. Asserted rather than argued: the check is the one
     * thing in this file whose answer a new component could plausibly have changed.
     */
    @Test
    fun `the exposure check still examines the residue directory, not the app directory`() =
        runTest {
            nested()
            val store = store()
            val key = SurfaceKey.Window("firefox", "a")
            val location = store.prepareResidue(key)
            // Second press: by now awakener owns the residue directory, so DEEPEST_EXISTING is
            // looking at it rather than at the temporary directory above.
            store.prepareResidue(key)

            val examined = store.residueExposureCheck.examined
            assertEquals(residueDir.toString(), examined)
            assertNotEquals(location.parent.toString(), examined, "not the application directory")
            assertEquals(ResidueExposureFinding.PRIVATE, store.residueExposureCheck.finding)
        }

    /** Both created levels get `registry.store.permissions`, not just the leaf. */
    @Test
    fun `the application directory is as private as the session directory`() = runTest {
        nested()
        val location = store().prepareResidue(SurfaceKey.Window("firefox", "a"))
        assertEquals("rwx------", modeOf(location), "the session directory")
        assertEquals("rwx------", modeOf(location.parent), "the application directory")
        assertEquals("rwx------", modeOf(residueDir), "and the residue directory itself")
    }

    /** An archive stays inside the application's directory rather than flattening back up. */
    @Test
    fun `archiving a nested residue keeps it under its application`() = runTest {
        nested()
        config.put(RegistryFlags.forgetResidue, ForgetResidue.ARCHIVE)
        val key = SurfaceKey.Window("firefox", "a")
        val store = store()
        store.bind(key)
        val residue = store.prepareResidue(key)
        residue.resolve("model.md").writeText("prefers dark mode")

        val archived = assertIs<ResidueOutcome.Archived>(store.unbind(key).residue)
        val archive = Path(archived.archive)
        assertFalse(residue.exists(), "the session directory itself moved")
        assertEquals(residue.parent, archive.parent, "and it stayed under its application")
        assertEquals("prefers dark mode", archive.resolve("model.md").readText())

        config.put(RegistryFlags.forgetResidue, ForgetResidue.DELETE)
        store.bind(key)
        store.prepareResidue(key).resolve("model.md").writeText("second")
        assertEquals(ResidueOutcome.Deleted(residue.toString()), store.unbind(key).residue)
        assertFalse(residue.exists())
    }

    // --- migration --------------------------------------------------------------------------

    /**
     * Nothing migrates, and that is reported rather than left to be discovered.
     *
     * A deployment running the default layout has `<slug>.md` files on disk. Flipping the flag
     * changes where residue is written and moves nothing: the old model is neither read nor
     * deleted. On its own that is indistinguishable from a surface that never wrote anything
     * down, which is exactly the silence this repository keeps finding — so the prepare that
     * was about to write somewhere else says so, naming both paths.
     */
    @Test
    fun `residue left behind by a layout flip is reported, not silently orphaned`() = runTest {
        val key = SurfaceKey.Window("firefox", "a")
        val flat = store().prepareResidue(key)
        flat.writeText("six weeks of accumulated model")

        nested()
        val store = store()
        val nestedLocation = store.prepareResidue(key)

        val check = store.residueStrandedCheck
        assertEquals(ResidueStrandedFinding.STRANDED, check.finding)
        assertEquals(listOf(flat.toString()), check.stranded)
        assertEquals(nestedLocation.toString(), check.active)
        val warning = check.warning
        assertTrue(warning != null && flat.toString() in warning, "warning: $warning")
        assertTrue(warning.contains(RegistryFlags.residueLayout.key), "warning: $warning")
        assertEquals(store.residueStranded, warning, "and it is what a caller prints")

        assertEquals(
            "six weeks of accumulated model",
            flat.readText(),
            "the old model is left exactly where it was — reported, never moved or removed",
        )
    }

    /** And it says so when there is nothing left behind, rather than going quiet. */
    @Test
    fun `a surface with no residue under another layout reads NONE`() = runTest {
        nested()
        val store = store()
        assertEquals(ResidueStrandedFinding.NOT_RUN, store.residueStrandedCheck.finding)
        store.prepareResidue(SurfaceKey.Window("firefox", "a"))
        assertEquals(ResidueStrandedFinding.NONE, store.residueStrandedCheck.finding)
        assertTrue(store.residueStrandedCheck.stranded.isEmpty())
        assertEquals(null, store.residueStranded)
    }

    /** It reports a flip in either direction, including between the two layouts that predate it. */
    @Test
    fun `the report is not specific to the new layout`() = runTest {
        val key = SurfaceKey.Window("firefox", "a")
        nested()
        store().prepareResidue(key).resolve("model.md").writeText("model")

        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_FILE)
        val store = store()
        store.prepareResidue(key)
        assertEquals(ResidueStrandedFinding.STRANDED, store.residueStrandedCheck.finding)
        assertEquals(1, store.residueStrandedCheck.stranded.size)
    }

    private fun modeOf(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    private companion object {
        /**
         * What a directory name under the residue directory may consist of. Deliberately not the
         * negation of a hazard list: the guard allows three character classes, so the test asserts
         * those and not "no `..` in it".
         */
        private val SAFE_NAME = Regex("[\\p{IsAlphabetic}\\p{IsDigit}-]+")

        /** Renders a hostile input so a failure message shows what was actually passed. */
        private fun String.escaped(): String =
            "'" + map { if (it.code in 0x20..0x7e) it.toString() else "\\u%04x".format(it.code) }
                .joinToString("") + "'"
    }
}
