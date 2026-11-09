package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.FilePermissions
import com.monkopedia.awakener.config.PrivateFiles
import com.monkopedia.awakener.config.Xdg
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.channels.FileLock
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Exclusion over one compositor's tree, across processes (#199).
 *
 * Every hotkey press is its own `awakener-invoke` process, and [SwayWindowManager.attach] only
 * identifies its dock because nothing else `exec`s between its snapshot of the docks standing and
 * the claim. A [Mutex] holds that within one JVM and nowhere else, so two presses that overlapped
 * both claimed whichever dock mapped first.
 *
 * Two layers, in this order, because a second [FileChannel.lock] on a file this process already
 * holds throws rather than waits: a mutex per lock file, shared by every manager in the process,
 * and then the file lock itself. The file lock is POSIX `fcntl`, which the kernel drops when the
 * holder exits by any route, `SIGKILL` included — so a press that crashed mid-attach releases it
 * and the next press proceeds, instead of waiting out [WmFlags.attachLockWaitMs].
 *
 * **Reentrant per coroutine**, through a context element: `awakener-invoke` holds the lock across
 * a whole press and the tree edits inside it take it again. A coroutine already holding it runs
 * straight through. Anything that is not a child of the holder — the repair collector — waits
 * like any other process would.
 */
internal object CompositorLock {
    private val mutexes = ConcurrentHashMap<Path, Mutex>()

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
    ): T {
        if (coroutineContext[Held]?.file == file) return block()
        val mutex = mutexes.computeIfAbsent(file) { Mutex() }
        // A Duration and a monotonic mark rather than `nanoTime() + waitMs * 1_000_000`: that
        // product wraps above ~9.2e12ms, and Long.MAX_VALUE then refused after 233ms. Duration
        // arithmetic saturates to INFINITE, whatever the flag's declared range lets through.
        val wait = waitMs.coerceAtLeast(0L).milliseconds
        val deadline = TimeSource.Monotonic.markNow() + wait
        if (!mutex.tryLock()) {
            val got = waitMs > 0 && withTimeoutOrNull(wait) { mutex.lock() } != null
            if (!got) throw contended(file, waitMs, "another awakener in this process")
        }
        try {
            val channel = try {
                PrivateFiles.createParentDirectories(file, FilePermissions.OWNER_ONLY)
                PrivateFiles.open(
                    file,
                    FilePermissions.OWNER_ONLY,
                    setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                )
            } catch (e: IOException) {
                return degrade(file, "cannot create it", e, required, degraded, block)
            }
            return channel.use { open ->
                val held = try {
                    acquire(open, deadline)
                } catch (e: IOException) {
                    return@use degrade(file, "the filesystem will not lock it", e, required, degraded, block)
                } ?: throw contended(file, waitMs, "another awakener process")
                try {
                    withContext(Held(file)) { block() }
                } finally {
                    held.release()
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The file lock, or null if [deadline] passed first.
     *
     * A blocking [FileChannel.lock] under [runInterruptible], so the wait costs no polling and
     * the timeout's cancellation interrupts it; an interrupted lock closes the channel, which is
     * why the caller's `use` is the only owner of [channel].
     *
     * The interrupt surfaces as [FileLockInterruptionException] (or [ClosedByInterruptException],
     * depending on where in the call it lands), and both **are [IOException]s** — so they are
     * answered here, as the timeout it is, before the caller's `IOException` handler can
     * read it as a filesystem that will not lock and run the edit unlocked. That is exactly what
     * the first version of this did, and the bounded-wait test is what caught it.
     */
    private suspend fun acquire(channel: FileChannel, deadline: TimeMark): FileLock? {
        channel.tryLock()?.let { return it }
        val remaining = -deadline.elapsedNow()
        if (!remaining.isPositive()) return null
        return try {
            withTimeoutOrNull(remaining) {
                runInterruptible(Dispatchers.IO) { channel.lock() }
            }
        } catch (interrupted: FileLockInterruptionException) {
            timedOut()
        } catch (interrupted: ClosedByInterruptException) {
            timedOut()
        }
    }

    /** A cancellation from outside interrupts the wait the same way; that is not a timeout. */
    private suspend fun timedOut(): FileLock? {
        currentCoroutineContext().ensureActive()
        return null
    }

    private suspend fun <T> degrade(
        file: Path,
        why: String,
        cause: IOException,
        required: Boolean,
        degraded: (String) -> Unit,
        block: suspend () -> T,
    ): T {
        if (required) {
            throw IOException(
                "$file: $why (${cause::class.simpleName}: ${cause.message}), and " +
                    "${WmFlags.attachLockRequired.key}=true refuses to edit the tree without the " +
                    "cross-process attach lock",
                cause,
            )
        }
        degraded(
            "$file: $why (${cause::class.simpleName}: ${cause.message}); editing the tree " +
                "without cross-process exclusion, so two overlapping presses can claim the " +
                "same dock (#199)",
        )
        return withContext(Held(file)) { block() }
    }

    private fun contended(file: Path, waitMs: Long, holder: String) = Contended(
        "$holder held the attach lock ($file) for longer than " +
            "${WmFlags.attachLockWaitMs.key}=${waitMs}ms; nothing was changed",
    )

    private class Held(val file: Path) : AbstractCoroutineContextElement(Held) {
        companion object Key : CoroutineContext.Key<Held>
    }
}
