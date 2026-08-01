package com.monkopedia.awakener.wm

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * That a session leaves nothing of itself running.
 *
 * A stray `foot` is not untidiness here. It is a second window standing under an `app_id` that a
 * later run will go looking for — the interloper `DockIdentity.NEW_NODE` exists to describe — and
 * `:wm`'s live-compositor suite is the only verification this module has. So debris from one run
 * can make the next one pass for the wrong reason, which puts it with the false-green defects
 * this suite has already had to fix rather than with hygiene. Measured on the parent commit, ten
 * full `:wm` runs left processes behind eight times.
 *
 * Both ways a session ends are exercised, and each wedges its window with SIGSTOP first. That is
 * not a contrivance: it is the wedged panel program the suite already models elsewhere, and it is
 * what makes these tests measure the reaping instead of `foot`'s own manners — a running terminal
 * usually notices its compositor going and exits, a stopped one never runs again.
 */
class SwayHarnessReapTest {
    private lateinit var sway: SwayHarness

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start()
    }

    /**
     * Belt and braces, and deliberately not a substitute for the assertions: [SwayHarness.close]
     * is what is under test, and a second call to it costs nothing.
     */
    @AfterTest
    fun tearDown() {
        if (::sway.isInitialized) sway.close()
    }

    @Test
    fun `closing a session takes down the window it opened`() {
        val clients = wedgedWindow()

        sway.close()

        assertEquals(
            emptyList(),
            survivors(clients),
            "close destroyed the compositor and left its window standing; on a shared box that " +
                "window is what the next run's app_id lookup finds",
        )
    }

    /**
     * The path with no way back, and the one that grows with every compositor-death test.
     *
     * [SwayHarness.kill] cannot ask sway to shut its clients down, and it leaves nothing behind
     * that could be asked what the session had. This passes only because the reaping does not go
     * through the compositor at all — the windows still name their socket after it is gone.
     */
    @Test
    fun `killing the compositor does not strand the window it opened`() {
        val clients = wedgedWindow()

        sway.kill()
        sway.close()

        assertEquals(
            emptyList(),
            survivors(clients),
            "the compositor was SIGKILLed and its window outlived the session; SIGKILL is the " +
                "path that cannot ask, so this one strands a client every time rather than " +
                "sometimes",
        )
    }

    // -- helpers ------------------------------------------------------------------------

    /**
     * Opens one window and stops every process behind it, returning them.
     *
     * The `sleep` is stopped as well as the terminal, so that nothing here can tidy up after
     * anything else: what is left has to be taken down from outside or not at all.
     */
    private fun wedgedWindow(): List<ProcessHandle> {
        runBlocking {
            sway.connection().use {
                it.request(I3Ipc.Request.RUN_COMMAND, "exec ${sway.windowCommand(APP_ID)}")
            }
        }
        return awaitSpawned().onEach { client ->
            assertEquals(
                0,
                ProcessBuilder("kill", "-STOP", "${client.pid()}").start().waitFor(),
                "kill -STOP ${client.pid()} failed",
            )
        }
    }

    /**
     * Waits until sway has both processes of the window, and answers with them.
     *
     * Both, because `foot` is spawned by sway and forks its `sleep` a moment later, and a wedge
     * applied to a terminal that has not forked yet would leave the interesting half running.
     */
    private fun awaitSpawned(): List<ProcessHandle> {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            val spawned = sway.spawned()
            if (spawned.size >= WINDOW_PROCESSES) return spawned
            Thread.sleep(POLL_MS)
        }
        fail("'$APP_ID' never became $WINDOW_PROCESSES processes under sway")
    }

    /** Whatever is still running, named well enough to be chased down by hand from a failure. */
    private fun survivors(clients: List<ProcessHandle>): List<String> =
        clients.filter(ProcessHandle::isAlive)
            .map { "${it.pid()} ${it.info().commandLine().orElse("<gone>")}" }

    private companion object {
        const val APP_ID = "aw-reap"

        /** `foot`, and the `sleep 3600` it hosts. */
        const val WINDOW_PROCESSES = 2

        const val WAIT_NANOS = 10_000_000_000L
        const val POLL_MS = 25L
    }
}
