package com.monkopedia.awakener.wm

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.writeText
import kotlin.test.fail
import org.junit.Assume.assumeTrue

/**
 * A real sway, run on the headless wlroots backend for tests.
 *
 * The window-management behaviour this module exists to get right — tree shape, focus memory,
 * what sway does and does not collapse — cannot be faked in a stub without the test becoming a
 * test of the stub. Headless sway needs no GPU, no seat, and no display, so it is usable on a
 * server and on CI.
 */
class SwayHarness private constructor(
    private var process: Process,
    val socket: Path,
    private val runtimeDir: Path,
) : AutoCloseable {
    fun connection(): SwayConnection = SwayConnection.open(socket.absolutePathString())

    /** Launches a window that simply sits there, for use as a surface or a dock. */
    fun windowCommand(appId: String): String = "$FOOT -a $appId -- sleep 3600"

    /**
     * Every process belonging to this session: the terminals sway launched, and what each runs.
     *
     * Found by the environment rather than by descent, because descent is not stable. sway 1.12
     * keeps a window it execs as its own child, while the 1.9 on the CI runner double forks and
     * the window belongs to init before it has drawn anything — both measured, and a reaper that
     * turns on which sway is installed is not one. Nor is the process group any use: sway calls
     * `setsid` before it execs, and `foot` does the same for the command it hosts, so a terminal
     * and its `sleep` sit in two sessions of their own.
     *
     * What all of them do carry is [socket], inherited from the compositor that spawned them,
     * unique to this harness, and untouched by anything that later happens to the compositor. So
     * this answers the same after sway has been killed as before, and it reaches processes no
     * tree can name — the `sleep` behind a terminal, and a window exec'd but not yet mapped.
     */
    fun spawned(): List<ProcessHandle> {
        // NUL-terminated because that is how `environ` separates its entries, and because
        // this path is a prefix of the one a longer temp directory name would give.
        val mark = "SWAYSOCK=${socket.absolutePathString()}\u0000"
        return ProcessHandle.allProcesses()
            .filter { it.pid() != process.pid() && environOf(it.pid()).contains(mark) }
            .toList()
    }

    /**
     * SIGKILLs sway and waits for it to be gone, which is a compositor crash as a client sees it.
     *
     * `destroyForcibly` is SIGKILL on Unix, and the signal matters: anything sway could catch
     * would let it shut its listeners down tidily, and a test that closes the socket politely is
     * testing the deliberate-close path it is supposed to be distinguishing itself from.
     *
     * The windows are deliberately left standing, so that what a client meets here is still a
     * compositor vanishing under it. [close] is what takes them down, and it can still find them
     * afterwards because [spawned] does not go through the compositor.
     */
    fun kill() {
        process.destroyForcibly().waitFor()
    }

    /**
     * Kills this sway and brings a fresh one up on the same socket path.
     *
     * A compositor restart with awakener's process surviving it, which is the combination the
     * design note calls the dangerous one and the only one in which reconnection means anything.
     * sway 1.12 has no in-place restart — `restart` is not a command it knows — so this is what a
     * restart *is*: a new process, a new `con_id` counter allocating from 5 again, and every window
     * of the previous session gone with the compositor that was holding them.
     *
     * **The socket path is deliberately reused**, which a real restart would not do: with
     * `SWAYSOCK` unset sway names its socket after its own pid, so a successor sits at a path
     * nothing told awakener about. That half is [SwaySocket]'s and is tested there without a
     * compositor, since it is a question about resolving a path rather than about what a client
     * observes. What this
     * harness supplies is the half only a real restart can: a client that held connections across
     * the boundary meeting a genuinely fresh tree.
     */
    fun restart() {
        process.destroyForcibly().waitFor()
        socket.deleteIfExists()
        process = launch(runtimeDir, socket)
    }

    /**
     * Ends the session, and takes everything sway launched down with it.
     *
     * The compositor goes first: it is the only thing that can start another window, so once it
     * has been waited for the session cannot grow again and one pass of [spawned] is the whole of
     * it. Asking it to leave rather than killing it also gives the windows that are still
     * listening the chance to exit on their own, which most of them take.
     *
     * SIGKILL for what is left: `close` is also how a session with a wedged client ends, and a
     * stopped process is woken for a kill and for nothing else. The `sleep` behind a terminal is
     * killed in its own right rather than left to the terminal, since a killed process reaps
     * nothing.
     */
    override fun close() {
        process.destroy()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        val clients = spawned()
        clients.forEach { it.destroyForcibly() }
        awaitGone(clients)
        socket.deleteIfExists()
        runtimeDir.toFile().deleteRecursively()
    }

    /**
     * A process's environment as the kernel lays it out, or empty when it cannot be read.
     *
     * Unreadable covers both a process belonging to somebody else and one that left between being
     * listed and being asked, and neither is this session's.
     */
    private fun environOf(pid: Long): String =
        runCatching { File("/proc/$pid/environ").readText() }.getOrDefault("")

    /**
     * Waits until nothing in [clients] is running, or until [REAP_TIMEOUT_NANOS] is up.
     *
     * SIGKILL takes a process off the scheduler at once but not out of the process table, so what
     * this waits on is whichever parent it has left getting round to reaping it. It reports
     * nothing — the bound is there so that a process no signal can shift costs a delay rather than
     * a hang, and a caller that needs the guarantee has to check for itself.
     */
    private fun awaitGone(clients: List<ProcessHandle>) {
        val deadline = System.nanoTime() + REAP_TIMEOUT_NANOS
        while (clients.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(REAP_POLL_MS)
        }
    }

    companion object {
        private const val SWAY = "sway"
        private const val FOOT = "foot"

        /**
         * Whether the tools these tests need are present.
         *
         * Absence is a skip locally and a failure in CI: a green run on a machine that silently
         * skipped every window-management test would be worse than no signal at all, since it
         * reads as coverage that does not exist.
         *
         * This is the predicate only; [assumeAvailable] is what a test gates on.
         */
        fun available(): Boolean {
            val present = which(SWAY) != null && which(FOOT) != null
            if (!present && System.getenv("AWAKENER_REQUIRE_SWAY") == "1") {
                fail("AWAKENER_REQUIRE_SWAY=1 but sway and/or foot are not installed")
            }
            return present
        }

        /**
         * Skips the calling test — for real — when sway or foot is missing.
         *
         * An early `return` is not a skip. JUnit records a test method that returns normally as
         * PASSED, so on a machine without sway this suite reported `skipped="0"`, no failures,
         * and a full count of passing tests: character for character the shape of a run that
         * drove a compositor. That is the reading `CLAUDE.md` and every review here treat as
         * proof the integration suite executed, so the one signal meant to catch a hollow green
         * run was itself producing one.
         *
         * An assumption failure throws, which the runner records as an assumption failure and
         * Gradle writes into the XML as `<skipped/>`. The count then means what it is documented
         * to mean.
         *
         * [available] runs first and is unchanged, so `AWAKENER_REQUIRE_SWAY=1` still turns a
         * missing tool into a hard failure before any of this can soften it. That half of the
         * guard was never the broken one, and it stays the one that actually protects CI.
         */
        fun assumeAvailable() {
            assumeTrue("sway and/or foot are not installed", available())
        }

        /**
         * Executable and `File.pathSeparator`, matching `toolFingerprint` in the build scripts.
         * They used to disagree: a present but non-executable `sway` was here to this and absent
         * to the cache key, which is the skew that key exists to remove (#29).
         */
        private fun which(tool: String): String? =
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { Path.of(it, tool) }
                .firstOrNull { it.isExecutable() }
                ?.absolutePathString()

        fun start(): SwayHarness {
            val runtimeDir = Files.createTempDirectory("awakener-sway")
            val socket = runtimeDir.resolve("sway-ipc.sock")
            return SwayHarness(launch(runtimeDir, socket), socket, runtimeDir)
        }

        /**
         * One sway process on [socket], waited for until it answers.
         *
         * Shared by [start] and [restart] so that a restarted compositor is the same compositor the
         * rest of the suite runs against — a second launcher would drift, and the whole point of a
         * restart test is that the successor is an ordinary session.
         */
        private fun launch(runtimeDir: Path, socket: Path): Process {
            val config = runtimeDir.resolve("sway.conf")
            // No bar, no xwayland, no autostart: the tests assert on tree shape, and anything
            // that maps a window of its own would show up as a surface.
            config.writeText(
                """
                xwayland disable
                default_border pixel 1
                focus_follows_mouse no
                """.trimIndent(),
            )

            val process = ProcessBuilder(SWAY, "-c", config.absolutePathString())
                .redirectErrorStream(true)
                .redirectOutput(runtimeDir.resolve("sway.log").toFile())
                .apply {
                    environment().apply {
                        put("XDG_RUNTIME_DIR", System.getenv("XDG_RUNTIME_DIR") ?: runtimeDir.absolutePathString())
                        put("SWAYSOCK", socket.absolutePathString())
                        put("WLR_BACKENDS", "headless")
                        put("WLR_LIBINPUT_NO_DEVICES", "1")
                        put("WLR_HEADLESS_OUTPUTS", "1")
                    }
                }
                .start()

            val deadline = System.nanoTime() + STARTUP_TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                if (socket.exists() && runCatching { SwayConnection.open(socket.absolutePathString()).close() }.isSuccess) {
                    return process
                }
                Thread.sleep(25)
            }
            process.destroyForcibly()
            fail("sway did not come up within ${STARTUP_TIMEOUT_NANOS / 1_000_000_000}s: " + runtimeDir.resolve("sway.log").toFile().readText())
        }

        private const val STARTUP_TIMEOUT_NANOS = 15_000_000_000L

        /** How long [awaitGone] will wait for the processes it killed to leave the table. */
        private const val REAP_TIMEOUT_NANOS = 5_000_000_000L

        private const val REAP_POLL_MS = 10L
    }
}
