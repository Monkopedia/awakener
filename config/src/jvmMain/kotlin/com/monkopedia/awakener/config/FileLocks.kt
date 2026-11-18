package com.monkopedia.awakener.config

import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.FileLockInterruptionException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
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
 * A bounded, reentrant section held across processes on one lock file: the attach lock (#199)
 * and the residue lock (#239) are both this.
 *
 * Two layers, in this order, because a second [FileChannel.lock] on a file this process already
 * holds throws rather than waits: a mutex per lock file, shared by every caller in the process,
 * and then the file lock itself. The file lock is POSIX `fcntl`, which the kernel drops when the
 * holder exits by any route, `SIGKILL` included — so a holder that crashed mid-section releases it
 * and the next one proceeds, instead of waiting out the bound.
 *
 * **Reentrant per coroutine**, through a context element naming *every* file the coroutine holds.
 * Every file and not the innermost: a press holds the attach lock and then, inside it, the residue
 * lock, and a tree read made inside the second still has to run straight through the first. An
 * element that named only the innermost file would make that read wait on its own press. Anything
 * that is not a child of the holder waits like any other process would.
 */
object FileLocks {
    private val mutexes = ConcurrentHashMap<Path, Mutex>()

    /**
     * Runs [block] holding the lock on [file], waiting at most [waitMs] for it.
     *
     * @param contended builds what is raised when another holder kept the lock past the wait,
     * told which kind of holder that was; nothing has run.
     * @param unlockable is told why, when the lock file cannot be created or locked. It raises to
     * refuse; returning runs [block] on the in-process mutex alone.
     */
    suspend fun <T> withLock(
        file: Path,
        waitMs: Long,
        contended: (holder: String) -> Exception,
        unlockable: (why: String, cause: IOException) -> Unit,
        block: suspend () -> T,
    ): T {
        if (holds(file)) return block()
        val held = coroutineContext[Held]?.files.orEmpty()
        val mutex = mutexes.computeIfAbsent(file) { Mutex() }
        // A Duration and a monotonic mark rather than `nanoTime() + waitMs * 1_000_000`: that
        // product wraps above ~9.2e12ms, and Long.MAX_VALUE then refused after 233ms. Duration
        // arithmetic saturates to INFINITE, whatever the flag's declared range lets through.
        val wait = waitMs.coerceAtLeast(0L).milliseconds
        val deadline = TimeSource.Monotonic.markNow() + wait
        if (!mutex.tryLock()) {
            val got = waitMs > 0 && withTimeoutOrNull(wait) { mutex.lock() } != null
            if (!got) throw contended("another awakener in this process")
        }
        // `plusElement`, not `+`: a Path is an Iterable<Path>, so `held + file` adds the file's
        // name components one by one and the reentrancy check above never matches.
        val holding = Held(held.plusElement(file))
        try {
            val channel = try {
                PrivateFiles.createParentDirectories(file, FilePermissions.OWNER_ONLY)
                PrivateFiles.open(
                    file,
                    FilePermissions.OWNER_ONLY,
                    setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                )
            } catch (e: IOException) {
                unlockable("cannot create it", e)
                return withContext(holding) { block() }
            }
            return channel.use { open ->
                val lock = try {
                    open.tryLock()
                } catch (e: IOException) {
                    unlockable("the filesystem will not lock it", e)
                    return@use withContext(holding) { block() }
                } ?: try {
                    await(open, deadline)
                } catch (e: IOException) {
                    // Not a filesystem that will not lock: it has just answered a lock request.
                    // On Linux this is fcntl's EDEADLK, the kernel refusing a wait that would
                    // close a cycle with another process — and a cycle read as "cannot lock" would
                    // run the section unlocked, which is the one thing a lock may not do.
                    throw contended(
                        "another awakener process, in a cycle the kernel refused to wait on " +
                            "(${e.message}),",
                    )
                } ?: throw contended("another awakener process")
                try {
                    withContext(holding) { block() }
                } finally {
                    lock.release()
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Whether the calling coroutine already holds [file], so that [withLock] would run through. */
    suspend fun holds(file: Path): Boolean = coroutineContext[Held]?.files?.contains(file) == true

    /**
     * The file lock, once another holder lets go of it, or null if [deadline] passed first.
     *
     * A blocking [FileChannel.lock] under [runInterruptible], so the wait costs no polling and
     * the timeout's cancellation interrupts it; an interrupted lock closes the channel, which is
     * why the caller's `use` is the only owner of [channel].
     *
     * The interrupt surfaces as [FileLockInterruptionException] (or [ClosedByInterruptException],
     * depending on where in the call it lands), and both **are [IOException]s** — so they are
     * answered here, as the timeout it is, before the caller's `IOException` handler can
     * read it as a refusal. The first version of the attach lock read it as a filesystem that will
     * not lock and ran the section unlocked, and the bounded-wait test is what caught it.
     */
    private suspend fun await(channel: FileChannel, deadline: TimeMark): FileLock? {
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

    private class Held(val files: Set<Path>) : AbstractCoroutineContextElement(Held) {
        companion object Key : CoroutineContext.Key<Held>
    }
}
