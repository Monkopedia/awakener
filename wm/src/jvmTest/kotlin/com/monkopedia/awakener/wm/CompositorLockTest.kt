package com.monkopedia.awakener.wm

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [CompositorLock]'s wait, against a holder in **another process** — the only kind of holder the
 * file half of the lock can see, since a second lock from this JVM throws instead of waiting.
 * Needs no compositor.
 */
class CompositorLockTest {
    private val dir: Path = createTempDirectory("awakener-compositor-lock")
    private val file: Path = dir.resolve("awakener").resolve("attach-test.lock")
    private var holder: Process? = null

    @AfterTest
    fun tearDown() {
        holder?.destroyForcibly()?.waitFor(10, TimeUnit.SECONDS)
        dir.toFile().deleteRecursively()
    }

    /**
     * `waitMs * 1_000_000` wrapped above ~9.2e12ms, so Long.MAX_VALUE put the deadline in the
     * past and the lock refused at once, claiming to have waited 9.2e18ms. The flag's range now
     * keeps that value out, but the lock is held to saturating on its own: given the largest
     * wait there is, it must still be waiting when the test gives up on it.
     */
    @Test
    fun `an enormous wait waits rather than refusing at once`() = runBlocking {
        hold()
        val outcome = withTimeoutOrNull(1_500.milliseconds) {
            runCatching { CompositorLock.withLock(file, Long.MAX_VALUE, true, {}) { "ran" } }
        }
        assertNull(outcome, "expected to still be waiting after 1.5s, got $outcome")
    }

    /** The boundary the other way: a short wait expires no sooner than it says, and refuses. */
    @Test
    fun `a bounded wait refuses no sooner than its bound`() = runBlocking {
        hold()
        val started = TimeSource.Monotonic.markNow()
        val refused = assertFailsWith<CompositorLock.Contended> {
            CompositorLock.withLock(file, 400, true, {}) { "ran" }
        }
        val waited = started.elapsedNow()
        assertTrue(waited >= 400.milliseconds, "refused after $waited")
        assertTrue(waited < 5_000.milliseconds, "refused after $waited")
        assertTrue("lock_wait_ms=400ms" in refused.message.orEmpty(), refused.message)
    }

    /** And the holder dying is what frees it, at once rather than at the bound. */
    @Test
    fun `a killed holder frees the lock for a waiter`() = runBlocking {
        val process = hold()
        val started = TimeSource.Monotonic.markNow()
        val waiter = async(Dispatchers.Default) {
            CompositorLock.withLock(file, 30_000, true, {}) { "ran" }
        }
        delay(500)
        assertTrue(waiter.isActive, "the waiter has to be waiting when the holder dies")
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        assertEquals("ran", waiter.await())
        assertTrue(started.elapsedNow() < 5_000.milliseconds, "took ${started.elapsedNow()}")
    }

    /** Starts [LockHolderMain] on [file] and waits until it reports the lock held. */
    private fun hold(): Process {
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            LockHolderMain::class.java.name,
            file.absolutePathString(),
        ).redirectErrorStream(true).start()
        holder = process
        val line = process.inputStream.bufferedReader().readLine()
        assertEquals("held", line, "the holder process did not take the lock")
        // Corroborated from outside rather than taken on the holder's word.
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            val probe = channel.tryLock()
            probe?.release()
            assertNull(probe, "the lock file is not held by the holder")
        }
        return process
    }
}

/** Holds the `fcntl` lock on `args[0]` until killed. A test fixture for [CompositorLockTest]. */
object LockHolderMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = Path.of(args[0])
        file.parent.toFile().mkdirs()
        val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        channel.lock()
        println("held")
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
