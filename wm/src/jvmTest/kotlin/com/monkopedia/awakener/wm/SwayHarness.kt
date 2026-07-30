package com.monkopedia.awakener.wm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.fail

/**
 * A real sway, run on the headless wlroots backend for tests.
 *
 * The window-management behaviour this module exists to get right — tree shape, focus memory,
 * what sway does and does not collapse — cannot be faked in a stub without the test becoming a
 * test of the stub. Headless sway needs no GPU, no seat, and no display, so it is usable on a
 * server and on CI.
 */
class SwayHarness private constructor(
    private val process: Process,
    val socket: Path,
    private val runtimeDir: Path,
) : AutoCloseable {
    fun connection(): SwayConnection = SwayConnection.open(socket.absolutePathString())

    /** Launches a window that simply sits there, for use as a surface or a dock. */
    fun windowCommand(appId: String): String = "$FOOT -a $appId -- sleep 3600"

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        socket.deleteIfExists()
        runtimeDir.toFile().deleteRecursively()
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
         */
        fun available(): Boolean {
            val present = which(SWAY) != null && which(FOOT) != null
            if (!present && System.getenv("AWAKENER_REQUIRE_SWAY") == "1") {
                fail("AWAKENER_REQUIRE_SWAY=1 but sway and/or foot are not installed")
            }
            return present
        }

        private fun which(tool: String): String? =
            System.getenv("PATH").orEmpty().split(':')
                .map { Path.of(it, tool) }
                .firstOrNull { it.exists() }
                ?.absolutePathString()

        fun start(): SwayHarness {
            val runtimeDir = Files.createTempDirectory("awakener-sway")
            val socket = runtimeDir.resolve("sway-ipc.sock")
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
                    return SwayHarness(process, socket, runtimeDir)
                }
                Thread.sleep(25)
            }
            process.destroyForcibly()
            fail("sway did not come up within ${STARTUP_TIMEOUT_NANOS / 1_000_000_000}s: " + runtimeDir.resolve("sway.log").toFile().readText())
        }

        private const val STARTUP_TIMEOUT_NANOS = 15_000_000_000L
    }
}
