package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.FilePermissions
import com.monkopedia.awakener.config.InMemoryConfigStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #238. Residue created before #102 took the umask — `0755` directories, `0644` files — and
 * nothing since has touched a mode on anything that already existed: #102 governs creation, and
 * #210's migration is a rename, which carries the mode with it. So a model written before #102,
 * and Claude's `.claude/settings.local.json` inside it, stayed readable by every local user.
 *
 * Every fixture here is built with `chmod` after creation, for the reason
 * `StorePermissionsTest.wideOpenDirectory` gives: creation modes are masked by the umask and
 * `chmod` is not, so this is the only way to build a pre-#102 tree that is actually `0755`/`0644`
 * on a host whose own umask is already `077`.
 */
class ResidueModesTest {
    private val dir = createTempDirectory("awakener-residue-modes")
    private val config = InMemoryConfigStore()
    private val identities = AgentIdentities { key, _ ->
        AgentIdentity(AgentId("agent-${key.slug}"), "lifeless-${key.slug}")
    }
    private val key = SurfaceKey.Window("firefox")

    /** Outside the residue root; a symlink inside the tree points here and must not be followed. */
    private val outside = dir.resolve("outside.txt")

    /** The same, for a symlink to a directory: a walk that followed it would recurse out. */
    private val outsideDir = dir.resolve("outside-dir")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun store() =
        FileBindingStore(config, identities, environment = emptyMap(), path = dir.resolve("bindings.json"))

    private val residueDir: Path get() = dir.resolve("residue")

    private fun pathFor(layout: ResidueLayout, naming: ResidueNaming): Path =
        RegistryPaths.residuePath(config.config.value, dir.resolve("bindings.json"), key, layout, naming)

    private fun modeOf(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))

    private fun chmod(path: Path, mode: String) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    }

    /**
     * What a pre-#102 surface directory looked like by the time Claude had worked in it: nested
     * directories, a settings file, a connector script that has to stay executable, and a symlink
     * out of the tree to a file whose mode is nobody's business here.
     */
    private fun preHundredTwoTree(at: Path) {
        at.resolve(".claude").createDirectories()
        at.resolve(".claude/settings.local.json").writeText("{}")
        at.resolve("notes.md").writeText("model")
        at.resolve("bin").createDirectories()
        at.resolve("bin/cdp.sh").writeText("#!/bin/sh\n")
        outside.writeText("not residue")
        chmod(outside, "rw-r--r--")
        Files.createSymbolicLink(at.resolve("escape"), outside)
        outsideDir.resolve("inner").createDirectories()
        chmod(outsideDir, "rwxr-xr-x")
        Files.createSymbolicLink(at.resolve("escape-dir"), outsideDir)
        listOf(residueDir, at, at.resolve(".claude"), at.resolve("bin")).forEach {
            chmod(it, "rwxr-xr-x")
        }
        var up = at.parent
        while (up != residueDir) {
            chmod(up, "rwxr-xr-x")
            up = up.parent
        }
        chmod(at.resolve(".claude/settings.local.json"), "rw-r--r--")
        chmod(at.resolve("notes.md"), "rw-r--r--")
        chmod(at.resolve("bin/cdp.sh"), "rwxr-xr-x")
    }

    private fun assertTightened(at: Path) {
        assertEquals("rwx------", modeOf(residueDir), "the residue root")
        var up = at.parent
        while (up != residueDir) {
            assertEquals("rwx------", modeOf(up), "the application directory $up")
            up = up.parent
        }
        assertEquals("rwx------", modeOf(at), "the surface's directory")
        assertEquals("rwx------", modeOf(at.resolve(".claude")), "a directory inside it")
        assertEquals("rw-------", modeOf(at.resolve(".claude/settings.local.json")), "Claude's settings")
        assertEquals("rw-------", modeOf(at.resolve("notes.md")), "the model")
        assertEquals("rwx------", modeOf(at.resolve("bin/cdp.sh")), "a connector script stays executable")
        assertEquals("rw-r--r--", modeOf(outside), "the symlink's target outside the root is untouched")
        assertTrue(Files.isSymbolicLink(at.resolve("escape")), "and the link itself is still a link")
        assertEquals("rwxr-xr-x", modeOf(outsideDir), "a directory symlink is not followed either")
    }

    @Test
    fun `an existing slug-named residue tree is tightened on prepare`() = runTest {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
        config.put(RegistryFlags.residueNaming, ResidueNaming.SLUG)
        val at = pathFor(ResidueLayout.PER_KEY_DIR, ResidueNaming.SLUG)
        preHundredTwoTree(at)

        val store = store()
        assertEquals(at, store.prepareResidue(key))

        assertTightened(at)
        val note = assertNotNull(store.residueTightened, "said once")
        assertTrue(note.contains(at.toString()), note)
    }

    @Test
    fun `an existing digest-named residue tree is tightened on prepare`() = runTest {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_APP_SESSION_DIR)
        val at = pathFor(ResidueLayout.PER_APP_SESSION_DIR, ResidueNaming.DIGEST)
        preHundredTwoTree(at)

        val store = store()
        assertEquals(at, store.prepareResidue(key))

        assertTightened(at)
        assertNotNull(store.residueTightened)
        store.prepareResidue(key)
        assertNull(store.residueTightened, "nothing left to tighten, so nothing to say")
    }

    /** The rename carries the mode with it; that is the half of the issue #210 made. */
    @Test
    fun `residue migrated from its slug name is tightened at its new one`() = runTest {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_APP_SESSION_DIR)
        val slug = pathFor(ResidueLayout.PER_APP_SESSION_DIR, ResidueNaming.SLUG)
        preHundredTwoTree(slug)

        val store = store()
        val at = store.prepareResidue(key)

        assertEquals(pathFor(ResidueLayout.PER_APP_SESSION_DIR, ResidueNaming.DIGEST), at)
        assertFalse(slug.exists(), "it moved")
        assertNotNull(store.residueMigrated)
        assertTightened(at)
    }

    /** The #236 reviewer's case: the per-key file left at `0644` by preparation. */
    @Test
    fun `an existing per-key residue file is tightened`() = runTest {
        for (naming in ResidueNaming.entries) {
            config.put(RegistryFlags.residueNaming, naming)
            val at = pathFor(ResidueLayout.PER_KEY_FILE, naming)
            residueDir.createDirectories()
            at.writeText("model")
            chmod(at, "rw-r--r--")
            chmod(residueDir, "rwxr-xr-x")

            assertEquals(at, store().prepareResidue(key), "$naming")

            assertEquals("rw-------", modeOf(at), "$naming: the residue file")
            assertEquals("rwx------", modeOf(residueDir), "$naming: the residue root")
            Files.delete(at)
        }
    }

    /** Bits are only ever cleared: an owner-read-only file is not made writable. */
    @Test
    fun `tightening never widens an owner's own bits`() = runTest {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
        val at = pathFor(ResidueLayout.PER_KEY_DIR, ResidueNaming.DIGEST)
        at.createDirectories()
        at.resolve("frozen.md").writeText("x")
        chmod(at.resolve("frozen.md"), "r--r--r--")

        store().prepareResidue(key)

        assertEquals("r--------", modeOf(at.resolve("frozen.md")))
    }

    @Test
    fun `LOCATION tightens the path itself and nothing inside it`() = runTest {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
        config.put(RegistryFlags.residueTighten, ResidueTighten.LOCATION)
        val at = pathFor(ResidueLayout.PER_KEY_DIR, ResidueNaming.DIGEST)
        preHundredTwoTree(at)

        store().prepareResidue(key)

        assertEquals("rwx------", modeOf(residueDir))
        assertEquals("rwx------", modeOf(at))
        assertEquals("rw-r--r--", modeOf(at.resolve("notes.md")), "the contents are left")
    }

    @Test
    fun `OFF and UMASK each leave an existing tree exactly as it was`() = runTest {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
        val at = pathFor(ResidueLayout.PER_KEY_DIR, ResidueNaming.DIGEST)
        preHundredTwoTree(at)

        config.put(RegistryFlags.residueTighten, ResidueTighten.OFF)
        val off = store().apply { prepareResidue(key) }
        assertEquals("rwxr-xr-x", modeOf(at), "OFF")
        assertNull(off.residueTightened)

        config.put(RegistryFlags.residueTighten, ResidueTighten.TREE)
        config.put(RegistryFlags.filePermissions, FilePermissions.UMASK)
        store().prepareResidue(key)
        assertEquals("rwxr-xr-x", modeOf(at), "UMASK is a deployment that wants the umask's modes")
        assertEquals("rw-r--r--", modeOf(at.resolve("notes.md")))
    }

    /**
     * An entry that cannot be tightened is reported and the press goes on. Driven through the
     * `setMode` seam because the honest way to make `chmod` fail — a file owned by another user —
     * needs root to build, and a test gated on the runner's identity would be one more gate.
     */
    @Test
    fun `an entry that cannot be tightened is reported and does not stop the rest`() {
        val root = dir.resolve("residue").createDirectories()
        val at = root.resolve("surface").createDirectories()
        listOf("a.md", "b.md", "c.md").forEach {
            at.resolve(it).writeText("x")
            chmod(at.resolve(it), "rw-r--r--")
        }
        chmod(at, "rwxr-xr-x")
        chmod(root, "rwxr-xr-x")

        val result = ResidueModes.tighten(root, at, recursive = true) { path ->
            if (path.fileName.toString() == "b.md") throw IOException("Operation not permitted")
        }

        assertEquals(4, result.tightened, "the root, the surface, a.md and c.md")
        assertEquals(1, result.failed.size)
        assertTrue(result.failed.single().contains("b.md"), result.failed.single())
        assertEquals("rw-------", modeOf(at.resolve("c.md")), "the walk went on past it")
        assertEquals("rw-r--r--", modeOf(at.resolve("b.md")))
    }

    /**
     * A directory inside the residue, swapped for a symlink to somewhere outside it after the walk
     * has classified it as a directory. A walk by path string follows the link for every child
     * (review of #241, at `bcf3e66`); a walk by descriptor is still holding the real directory.
     *
     * The swap is made from the seam, just before `sub`'s own mode is set, which is after the walk
     * decided `sub` was a directory and before it reads what is inside — the window a real race
     * has to hit, held open deterministically.
     */
    @Test
    fun `a directory swapped for a symlink mid-walk does not carry the walk out of the root`() {
        val root = dir.resolve("residue").createDirectories()
        val at = root.resolve("surface").createDirectories()
        val sub = at.resolve("sub").createDirectories()
        sub.resolve("id").writeText("inside")
        chmod(sub.resolve("id"), "rw-r--r--")
        chmod(sub, "rwxr-xr-x")
        outsideDir.createDirectories()
        outsideDir.resolve("id").writeText("outside")
        chmod(outsideDir.resolve("id"), "rw-r--r--")
        val moved = at.resolve("sub-real")

        val result = ResidueModes.tighten(root, at, recursive = true) { path ->
            if (path == sub) {
                Files.move(sub, moved)
                Files.createSymbolicLink(sub, outsideDir)
            }
        }

        assertEquals("rw-r--r--", modeOf(outsideDir.resolve("id")), "outside the root is untouched")
        assertEquals("rw-------", modeOf(moved.resolve("id")), "the real directory's child was reached")
        assertTrue(result.failed.isEmpty(), "${result.failed}")
    }

    /**
     * The same escape without a seam: one thread flips `sub` between a real directory and a
     * symlink out of the root with atomic renames while the walk runs. A path walk escaped within
     * tens of milliseconds in review; this runs for a fixed number of rounds and checks, after
     * every one, that nothing outside the root changed mode.
     */
    @Test
    fun `a directory flipping to a symlink under a real race never leads out of the root`() {
        val root = dir.resolve("residue").createDirectories()
        val at = root.resolve("surface").createDirectories()
        val real = at.resolve("real").createDirectories()
        val sub = at.resolve("sub")
        val parked = at.resolve("parked")
        outsideDir.createDirectories()
        val outsideFiles = (0 until 50).map { i ->
            outsideDir.resolve("f$i").also {
                it.writeText("outside")
                chmod(it, "rw-r--r--")
            }
        }
        (0 until 50).forEach { i -> real.resolve("f$i").writeText("inside") }
        Files.createSymbolicLink(parked, outsideDir)
        val stop = AtomicBoolean(false)
        val flipper = Thread {
            // Each move on its own, so one that loses to the walk does not stall the cycle.
            val cycle = listOf(real to sub, sub to real, parked to sub, sub to parked)
            while (!stop.get()) {
                cycle.forEach { (from, to) ->
                    runCatching { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) }
                }
            }
        }
        flipper.isDaemon = true
        flipper.start()
        try {
            repeat(RACE_ROUNDS) { round ->
                runCatching { chmod(sub, "rwxr-xr-x") }
                runCatching { chmod(real, "rwxr-xr-x") }
                ResidueModes.tighten(root, at, recursive = true)
                val escaped = outsideFiles.filter { modeOf(it) != "rw-r--r--" }
                assertTrue(escaped.isEmpty(), "round $round reached outside the root: $escaped")
            }
        } finally {
            stop.set(true)
            flipper.join()
        }
    }

    private companion object {
        const val RACE_ROUNDS = 400
    }
}
