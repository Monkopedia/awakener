package com.monkopedia.awakener.wm

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That a session's directory does not outlive the run that made it (#243).
 *
 * Each one is a sway config, a log and a socket on `/tmp`, which on kaladin is RAM, and 168 of
 * them built up in one day. [SwayHarness.close] was already removing its own on every path a test
 * takes — measured, a passing suite, a failing test and the deliberate kills in
 * [SwayHarnessReapTest] each left none. What leaked was the worker dying under its sessions, on a
 * cancelled build or a SIGKILL, and these cover the two backstops for that.
 */
class SwayHarnessTmpTest {
    /**
     * The half the build owns: it names a per-run directory, sweeps it when the root suite ends,
     * and does that from the Gradle daemon, which is still there when the worker is not. That only
     * reaches a session that was created inside it.
     */
    @Test
    fun `a session's directory sits under the run's sweep root`() {
        SwayHarness.assumeAvailable()
        val root = assertNotNull(
            System.getenv(SwayHarness.TMPDIR_VAR),
            "${SwayHarness.TMPDIR_VAR} is unset, so nothing sweeps what a killed worker leaves",
        )
        SwayHarness.start().use { sway ->
            assertEquals(Path.of(root), sway.socket.parent.parent)
        }
    }

    /**
     * The half the JVM owns: a worker told to stop, which is how Gradle ends one on a cancelled
     * build, runs its shutdown hooks and nothing else. SIGTERM, not SIGKILL, because a hook is
     * exactly what SIGKILL cannot run — that case belongs to the sweep above.
     */
    @Test
    fun `a JVM that exits with a session open takes its directory with it`() {
        SwayHarness.assumeAvailable()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").absolutePathString()
        val child = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            OpenSessionMain::class.java.name,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val socket = assertNotNull(
                child.inputReader().readLine(),
                "the child exited before it had a session",
            ).let(Path::of)
            assertTrue(socket.parent.exists(), "the child's session directory was never there")

            child.destroy()
            assertTrue(child.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "the child ignored SIGTERM")

            assertFalse(
                socket.parent.exists(),
                "the JVM exited with a session open and left ${socket.parent} behind",
            )
        } finally {
            child.destroyForcibly()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 20L
    }
}

/** Starts a session, says where, and waits to be stopped without ever closing it. */
object OpenSessionMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sway = SwayHarness.start()
        println(sway.socket.absolutePathString())
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
