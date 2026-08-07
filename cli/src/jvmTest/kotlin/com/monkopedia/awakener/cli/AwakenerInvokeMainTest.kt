package com.monkopedia.awakener.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `awakener-invoke`'s `main`, run as the process it actually is.
 *
 * The thing under test is what a person sees after pressing a key, and that is a property of the
 * *process*: the handler, the exit code, and which stream each line lands on. `main` ends in
 * `exitProcess`, so there is no in-process way to ask — and a test of an extracted helper would
 * prove only that the helper works, not that `main` is wired to it. Two PRs were blocked in this
 * repo for exactly that shape, so this launches the real entry point on the test's own classpath
 * and reads what comes back.
 *
 * The failure it provokes is a deliberate one: with no `SWAYSOCK`, no `wm.ipc.socket_path` and an
 * empty runtime directory, `SwaySocket` raises `no reachable sway IPC socket`, which is `:wm`
 * refusing to guess. That is the same class of raise as `liveAgents` refusing to read an
 * unreadable bus as an empty one — it just needs no compositor and no bus to reach, so it is
 * deterministic on any host.
 *
 * **`XDG_RUNTIME_DIR` is scratch too, and that is load-bearing rather than hygiene.**
 * `wm.ipc.socket_discovery` looks there when `SWAYSOCK` names nothing reachable, so on a host with
 * a live sway session of its own this would otherwise *connect* — the trigger would evaporate on
 * exactly the machine awakener is meant to run on, and the test would report on the developer's
 * desktop rather than on `main`.
 *
 * Everything else runs against a scratch `HOME`, `AWAKENER_CONFIG` and `XDG_STATE_HOME`: nothing
 * here may read Jason's config or write his bindings file.
 */
class AwakenerInvokeMainTest {
    /**
     * Against unchanged `main` (`d0dd60a`) this printed, on stderr, exit 1:
     *
     * ```
     * Exception in thread "main" java.lang.IllegalStateException: no sway socket: pass one …
     *     at com.monkopedia.awakener.wm.SwayConnection$Companion.open(SwayConnection.kt:120)
     *     … 18 more frames
     * ```
     *
     * The wording moved when reconnection landed: the composition root now resolves the socket
     * through `SwaySocket`, which looks for a successor when the named path answers nothing, so
     * the refusal says what it tried rather than only that it was not told. What is asserted is
     * still the shape — a `error: ` line a hotkey can act on, and no trace — plus enough of the
     * text to know it is that refusal and not another.
     */
    @Test
    fun `a raised failure is reported rather than thrown at the user`() {
        val run = invoke(config = "{}")

        assertEquals(1, run.exitCode)
        assertTrue(
            run.stderr.lineSequence().any {
                it.startsWith("error: no reachable sway IPC socket: SWAYSOCK=<unset>;")
            },
            "the refusal's own message is what a hotkey press can act on: ${run.stderr}",
        )
        assertTrue(
            "Exception in thread \"main\"" !in run.stderr,
            "an uncaught throw is what this is about; a trace is not a report: ${run.stderr}",
        )
        assertTrue(run.stdout.isEmpty(), "stdout is the answer, and there was none: ${run.stdout}")
    }

    /**
     * The other half of the flag, and the reason it is a flag: the message is what makes a
     * hotkey say something useful, and the trace is what makes it debuggable. Asserting both
     * directions is also what stops this passing against an implementation that simply always
     * printed the trace — which is the pre-change behaviour.
     */
    @Test
    fun `TRACE adds the stack trace under the message`() {
        val run = invoke(config = """{"invoke.failure.detail": "TRACE"}""")

        assertEquals(1, run.exitCode)
        assertTrue("error: no reachable sway IPC socket" in run.stderr, run.stderr)
        assertTrue(
            "at com.monkopedia.awakener.wm.SwaySocket" in run.stderr,
            "TRACE has to produce the frames, not merely a longer message: ${run.stderr}",
        )
    }

    /** An exit code the caller can branch on, and a usage error is still its own code. */
    @Test
    fun `a usage error is still a usage error and not a failure`() {
        val run = invoke(config = "{}", args = arrayOf("nonsense"))

        assertEquals(2, run.exitCode)
        assertTrue("unknown command 'nonsense'" in run.stdout, run.stdout)
    }

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun invoke(config: String, args: Array<String> = arrayOf("list")): Run {
        val home: Path = Files.createTempDirectory("awakener-invoke-main")
        val configFile = home.resolve("config.json")
        configFile.writeText(config)
        home.resolve("state").createDirectories()
        // Empty, and its own: discovery searches it, so a runtime directory holding the
        // developer's live sway would connect and this test would have no failure to report on.
        val runtime = home.resolve("run").createDirectories()

        val process = ProcessBuilder(
            listOf("${System.getProperty("java.home")}/bin/java", "-cp", CLASSPATH, MAIN) + args,
        ).apply {
            // A shell that launched the test may have any of these; the point of the scratch
            // directory is defeated if one survives.
            environment().remove("SWAYSOCK")
            environment().remove("SPANREED_AGENT_NAME")
            environment()["HOME"] = home.absolutePathString()
            environment()["AWAKENER_CONFIG"] = configFile.absolutePathString()
            environment()["XDG_CONFIG_HOME"] = home.absolutePathString()
            environment()["XDG_STATE_HOME"] = home.resolve("state").absolutePathString()
            environment()["XDG_RUNTIME_DIR"] = runtime.absolutePathString()
        }.start()

        // Both pipes before waitFor, for the reason `ProcessCommandRunner` spells out: reading
        // one to EOF first deadlocks on a child that fills the other.
        val stdout = process.inputStream.bufferedReader()
        val stderr = StringBuilder()
        val drain = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            .apply { isDaemon = true; start() }
        val out = stdout.readText()
        val code = process.waitFor()
        drain.join(10_000)
        return Run(code, out, stderr.toString())
    }

    private companion object {
        const val MAIN = "com.monkopedia.awakener.cli.AwakenerInvokeCliKt"

        /**
         * The test JVM's own classpath, which under Gradle is `:cli`'s test runtime classpath —
         * every module in the build, which is what the launcher lays down too.
         */
        val CLASSPATH: String = System.getProperty("java.class.path")
    }
}
