package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.FilePermissions
import com.monkopedia.awakener.config.InMemoryConfigStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #102. Every file and directory this module creates holds the user's durable state — the
 * residue most of all, which the design brief calls the accumulated model of the user — and
 * before this they were created with whatever the process umask left. Under the usual `022`
 * that is `0644`, world-readable; what stopped it mattering was `/home/<user>` being `0700`, a
 * property of directories this store neither creates nor checks.
 *
 * **The assertions are absolute rather than comparative on purpose.** A test that only asserted
 * "tighter than a control file" would pass on a host whose umask is already `077` no matter what
 * the store did — it would be measuring the environment. `rw-------` is the claim, and it is the
 * claim on every host. The one comparative assertion here is deliberately on the *opposite* arm:
 * `UMASK` must land exactly where an unadorned `Files.createFile` in the same directory does,
 * which is what "this flag preserves the old behaviour" means and is the only way to say it
 * without hard-coding a umask into a test.
 */
class StorePermissionsTest {
    private val dir = createTempDirectory("awakener-permissions")
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

    private fun modeOf(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    /** What an unadorned create in this directory lands on, i.e. the ambient umask. */
    private fun umaskFileMode(): String {
        val control = Files.createFile(dir.resolve("control-${System.nanoTime()}"))
        return modeOf(control)
    }

    /**
     * Creates [name] with mode bits the umask would otherwise strip.
     *
     * `open(2)` and `mkdir(2)` mask the mode they are given, and the JDK's creation attributes go
     * straight to them — so `asFileAttribute("rwxrwxrwx")` lands as `0755` under the usual `022`,
     * and a fixture built that way would quietly not be world-writable at all. `chmod(2)` is not
     * masked, which is why the mode goes on afterwards here.
     *
     * The same asymmetry is why the fix does not have to think about the umask: masking can only
     * *clear* bits, and `0600`/`0700` have none to clear.
     */
    private fun wideOpenDirectory(name: String): Path {
        val path = Files.createDirectory(dir.resolve(name))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"))
        return path
    }

    @Test
    fun `the bindings file and its directory are private to their owner`() = runTest {
        val nested = dir.resolve("state").resolve("awakener")
        val store = FileBindingStore(
            config,
            identities,
            environment = emptyMap(),
            path = nested.resolve("bindings.json"),
        )
        store.bind(SurfaceKey.Window("firefox"))

        assertEquals("rw-------", modeOf(store.path), "the bindings file")
        assertEquals("rwx------", modeOf(nested), "the directory holding it")
        assertEquals(
            "rwx------",
            modeOf(nested.parent),
            "and every ancestor this call created — tightening the leaf alone would have " +
                "moved the exposure up one level rather than removed it",
        )
    }

    /**
     * The lock file sits beside the bindings and is created by the same write path. It holds no
     * model, but it is in the state directory and it is one more thing created by a rule that
     * either applies uniformly or is not a rule.
     */
    @Test
    fun `the lock file is private too`() = runTest {
        val store = store()
        store.bind(SurfaceKey.Window("firefox"))
        assertNull(store.lockError, "this filesystem locks, so the lock file was created")
        assertEquals("rw-------", modeOf(dir.resolve("${store.path.name}.lock")))
    }

    @Test
    fun `residue is private under both layouts`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val file = store().prepareResidue(key)
        assertEquals("rw-------", modeOf(file), "the per-surface residue file")
        assertEquals("rwx------", modeOf(file.parent), "the residue directory")

        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
        val asDirectory = store("other.json").prepareResidue(key)
        assertEquals("rwx------", modeOf(asDirectory), "the per-surface residue directory")
    }

    /**
     * The counterfactual, run as a test rather than asserted: with the flag at `UMASK` the store
     * does exactly what it did before #102 — which on a host with the default `022` is the
     * world-readable `0644` the issue measured.
     */
    @Test
    fun `UMASK restores the pre-102 behaviour exactly`() = runTest {
        config.put(RegistryFlags.filePermissions, FilePermissions.UMASK)
        val store = store()
        store.bind(SurfaceKey.Window("firefox"))
        val residue = store.prepareResidue(SurfaceKey.Window("firefox"))

        val ambient = umaskFileMode()
        assertEquals(ambient, modeOf(store.path), "the bindings file takes the umask")
        assertEquals(ambient, modeOf(residue), "and so does the residue")
    }

    /**
     * A staging file left behind by a run that died between the write and the rename already
     * exists, so the creation attribute is never consulted for it — the mode has to be set
     * explicitly, or the rename carries a `0644` onto the bindings file.
     *
     * This is also the migration path for a bindings file an earlier build created `0644`: there
     * is no migration step anywhere, because the rename replaces the old file's mode along with
     * its contents.
     */
    @Test
    fun `a staging file left behind by a crash does not carry its mode onto the bindings`() =
        runTest {
            val store = store()
            val stale = dir.resolve("bindings.json.${ProcessHandle.current().pid()}.tmp")
            Files.createFile(stale)
            // chmod rather than a creation attribute: see [wideOpenDirectory] for why the
            // umask would otherwise make this fixture not wide open at all.
            Files.setPosixFilePermissions(stale, WIDE_OPEN_FILE)
            assertEquals("rw-rw-rw-", modeOf(stale), "the crashed run's leftovers")

            store.bind(SurfaceKey.Window("firefox"))
            assertEquals("rw-------", modeOf(store.path))
        }

    @Test
    fun `a residue directory nobody else can write to is not reported`() = runTest {
        val store = store()
        store.prepareResidue(SurfaceKey.Window("firefox"))
        assertNull(store.residueExposure)
    }

    /**
     * `registry.residue.dir` pointed at a world-writable directory is the case #102 names, and
     * `/tmp` is the real one. The check is on the nearest *existing* directory, because the
     * residue directory itself does not exist yet — that is precisely the window in which
     * another user can create it first, as a symlink onto something of theirs.
     */
    @Test
    fun `a world-writable ancestor is reported by default and refused when told to`() = runTest {
        val shared = wideOpenDirectory("shared")
        val key = SurfaceKey.Window("firefox")
        // A fresh leaf per arm. The first `prepareResidue` creates the residue directory at
        // 0700, which makes it the deepest existing directory and therefore not exposed — so
        // reusing one leaf would have the second and third arms measuring a directory awakener
        // had already secured rather than the one the flag is about.
        config.put(RegistryFlags.residueDir, shared.resolve("reported").toString())

        val reporting = store()
        reporting.prepareResidue(key)
        val warning = assertNotNull(reporting.residueExposure, "REPORT is the default")
        assertTrue(warning.contains(shared.toString()), "and it names the directory: $warning")
        assertTrue(
            warning.contains(RegistryFlags.residueExposure.key),
            "and the flag that changes what happens: $warning",
        )

        config.put(RegistryFlags.residueExposure, ResidueExposure.REFUSE)
        config.put(RegistryFlags.residueDir, shared.resolve("refused").toString())
        // `runCatching` rather than `assertFailsWith`, whose block is not a suspend lambda.
        val refusal = runCatching { store("refusing.json").prepareResidue(key) }.exceptionOrNull()
        assertIs<IOException>(refusal, "REFUSE raises rather than writing")
        assertTrue(
            !Files.exists(shared.resolve("refused")),
            "and raises *before* creating anything under it",
        )

        config.put(RegistryFlags.residueExposure, ResidueExposure.ALLOW)
        config.put(RegistryFlags.residueDir, shared.resolve("allowed").toString())
        val quiet = store("quiet.json")
        quiet.prepareResidue(key)
        assertNull(quiet.residueExposure, "ALLOW says nothing")
    }

    /**
     * Reported, and then *stopped* being reported once the residue is somewhere private. A
     * warning that latches is a warning that stops meaning anything the moment somebody acts on
     * it — the same property [FileBindingStore.lockError] holds.
     */
    @Test
    fun `the exposure report clears once the residue moves somewhere private`() = runTest {
        val shared = wideOpenDirectory("shared")
        config.put(RegistryFlags.residueDir, shared.resolve("residue").toString())
        val store = store()
        store.prepareResidue(SurfaceKey.Window("firefox"))
        assertNotNull(store.residueExposure)

        config.put(RegistryFlags.residueDir, dir.resolve("private").toString())
        store.prepareResidue(SurfaceKey.Window("firefox"))
        assertNull(store.residueExposure)
    }

    private companion object {
        /** `rw-rw-rw-`, which no umask on either host produces — so the tightening is visible. */
        private val WIDE_OPEN_FILE: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rw-rw-rw-")
    }
}
