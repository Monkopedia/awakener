package com.monkopedia.awakener.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * #102's other half. The issue is titled for the bindings and the residue, and it opens by
 * naming this store too: `FileConfigStore` created its file, its lock file and its staging file
 * with no permission either, so all three took the umask.
 *
 * The config file holds no model of the user, which is why it is the quieter half — but the two
 * stores share a directory convention, a lock-file convention and now a creation helper, and a
 * rule that held for one of them would be read as holding for both. Fixing one and leaving the
 * other is how the next reader learns the wrong thing from the nearer example.
 *
 * Absolute assertions, for the reason `StorePermissionsTest` states at length: "tighter than a
 * control file" passes on a host whose umask is already `077` whatever the code does.
 */
class ConfigPermissionsTest {
    private val dir = createTempDirectory("awakener-config-permissions")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun modeOf(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `a set creates the config file, its directory and its lock private`() = runTest {
        val nested = dir.resolve("config").resolve("awakener")
        val path = nested.resolve("awakener.json")
        val store = FileConfigStore(path, environment = emptyMap())
        store.set(ConfigFlags.watchDebounceMs.key, "50")

        assertNull(store.lockError.value, "this filesystem locks, so the lock file was created")
        assertEquals("rw-------", modeOf(path), "the config file")
        assertEquals("rw-------", modeOf(nested.resolve("${path.name}.lock")), "its lock file")
        assertEquals("rwx------", modeOf(nested), "the directory holding both")
        assertEquals("rwx------", modeOf(nested.parent), "and the ancestor this created")
    }

    /**
     * The counterfactual: with the flag at `UMASK` the store lands exactly where an unadorned
     * `Files.createFile` in the same directory does, which is what it did before #102 — a
     * world-readable `0644` on a host with the usual `022`.
     */
    @Test
    fun `UMASK restores the pre-102 behaviour exactly`() = runTest {
        val path = dir.resolve("awakener.json")
        val store = FileConfigStore(path, environment = emptyMap())
        // Through the store rather than an InMemoryConfigStore, because it is this store's own
        // snapshot that the write path reads the flag out of.
        store.set(ConfigFlags.filePermissions.key, FilePermissions.UMASK.name)
        store.set(ConfigFlags.watchDebounceMs.key, "50")

        val control = Files.createFile(dir.resolve("control"))
        assertEquals(modeOf(control), modeOf(path))
    }
}
