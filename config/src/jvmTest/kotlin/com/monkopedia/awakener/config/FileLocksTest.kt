package com.monkopedia.awakener.config

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [FileLocks]'s reentrancy across two files, which is the shape a press has: the attach lock for
 * the whole press, the residue lock inside it, and both taken again further in (#239).
 *
 * The cross-process wait itself is `CompositorLockTest`'s, against a holder in another JVM.
 */
class FileLocksTest {
    private val dir = createTempDirectory("awakener-file-locks")
    private val outer = dir.resolve("attach.lock")
    private val inner = dir.resolve("residue.lock")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    /**
     * Each file is held by name, and holding the second does not forget the first. With a zero
     * wait, a re-entry that was not recognised refuses at once rather than running through, so
     * either defect fails here instead of hanging. The first version recorded the held set as
     * `held + file`, which — a [java.nio.file.Path] being an `Iterable<Path>` — added the file's
     * name components rather than the file, and every re-entry waited on itself.
     */
    @Test
    fun `a coroutine holding two files re-enters either`() = runBlocking {
        val ran = lock(outer) {
            assertTrue(FileLocks.holds(outer))
            assertFalse(FileLocks.holds(inner))
            lock(inner) {
                assertTrue(FileLocks.holds(inner))
                assertTrue(FileLocks.holds(outer), "taking the inner lock dropped the outer")
                lock(outer) { lock(inner) { "ran" } }
            }
        }
        assertEquals("ran", ran)
        assertFalse(FileLocks.holds(outer), "released on the way out")
    }

    private suspend fun <T> lock(file: Path, block: suspend () -> T): T =
        FileLocks.withLock(
            file,
            waitMs = 0,
            contended = { holder -> IllegalStateException("$holder held $file") },
            unlockable = { why, cause -> throw IOException(why, cause) },
            block = block,
        )
}
