package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.FileLocks
import com.monkopedia.awakener.config.Xdg
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path

/**
 * Exclusion over one compositor's tree, across processes (#199).
 *
 * Every hotkey press is its own `awakener-invoke` process, and [SwayWindowManager.attach] only
 * identifies its dock because nothing else `exec`s between its snapshot of the docks standing and
 * the claim. A [kotlinx.coroutines.sync.Mutex] holds that within one JVM and nowhere else, so two
 * presses that overlapped both claimed whichever dock mapped first.
 *
 * The mechanism is [FileLocks]: an in-process mutex and then an `fcntl` lock the kernel drops when
 * the holder exits by any route, so a press that crashed mid-attach releases it and the next press
 * proceeds, instead of waiting out [WmFlags.attachLockWaitMs]. This object names the file and says
 * what a refusal means for a tree edit.
 *
 * **Reentrant per coroutine**: `awakener-invoke` holds the lock across a whole press and the tree
 * edits inside it take it again. A coroutine already holding it runs straight through. Anything
 * that is not a child of the holder — the repair collector — waits like any other process would.
 */
internal object CompositorLock {
    /** The lock file for the compositor listening on [socket]. */
    fun fileFor(socket: String, environment: Map<String, String> = System.getenv()): Path {
        // The real path, so `SWAYSOCK` and a discovered path naming the same socket share a lock.
        val canonical = runCatching { Path(socket).toRealPath().toString() }.getOrDefault(socket)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        val name = digest.take(8).joinToString("") { "%02x".format(it) }
        val dir = Xdg.base(environment["XDG_RUNTIME_DIR"]) ?: Xdg.stateHome(environment)
        return dir.resolve("awakener").resolve("attach-$name.lock")
    }

    /** Raised when another holder kept the lock past the wait. Nothing was changed. */
    class Contended(message: String) : IllegalStateException(message)

    /**
     * Runs [block] holding the lock on [file], waiting at most [waitMs] for it.
     *
     * [degraded] is told why, and [block] runs on the in-process mutex alone, when the lock file
     * cannot be created or locked — unless [required], in which case that failure is raised.
     */
    suspend fun <T> withLock(
        file: Path,
        waitMs: Long,
        required: Boolean,
        degraded: (String) -> Unit,
        block: suspend () -> T,
    ): T = FileLocks.withLock(
        file,
        waitMs,
        contended = { holder ->
            Contended(
                "$holder held the attach lock ($file); this waited up to " +
                    "${WmFlags.attachLockWaitMs.key}=${waitMs}ms; nothing was changed",
            )
        },
        unlockable = { why, cause ->
            if (required) {
                throw IOException(
                    "$file: $why (${cause::class.simpleName}: ${cause.message}), and " +
                        "${WmFlags.attachLockRequired.key}=true refuses to edit the tree without " +
                        "the cross-process attach lock",
                    cause,
                )
            }
            degraded(
                "$file: $why (${cause::class.simpleName}: ${cause.message}); editing the tree " +
                    "without cross-process exclusion, so two overlapping presses can claim the " +
                    "same dock (#199)",
            )
        },
        block = block,
    )
}
