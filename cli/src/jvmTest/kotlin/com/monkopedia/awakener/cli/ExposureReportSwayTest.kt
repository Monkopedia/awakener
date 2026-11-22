package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import com.monkopedia.awakener.wm.I3Ipc
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.SwayHarness
import com.monkopedia.awakener.wm.SwayWindowManager
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a press says about the residue exposure check under `invoke.residue.exposure_report` (#153).
 *
 * The check's answer lives in the pressing process's store and dies with it, so the only honest
 * reader is the real `awakener-invoke` process, pressed against a real tree: anything less would
 * prove a formatter works rather than that `main` prints it. Each test asserts on the stderr of
 * that process.
 *
 * Exercised against **sway 1.12**, headless wlroots backend.
 */
class ExposureReportSwayTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var home: Path
    private lateinit var runtime: Path

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        home = createTempDirectory("awakener-exposure-report")
        home.resolve("state").createDirectories()
        runtime = home.resolve("run").createDirectories()
        // Only ever reads the tree; its bindings file is not the presses'.
        val store = InMemoryConfigStore()
        val registry = FileBindingStore(
            configStore = store,
            identities = { key, residue -> DerivedAgentIdentities(store).mint(key, residue) },
            path = home.resolve("observer-bindings.json"),
        )
        wm = SwayWindowManager({ sway.connection() }, store, registry, scope)
    }

    @AfterTest
    fun tearDown() {
        if (!SwayHarness.available()) return
        scope.cancel()
        sway.close()
        home.toFile().deleteRecursively()
    }

    /** Before #153 none of these presses printed anything about the check. */
    @Test
    fun `each answer reads differently, and names what it examined`() = runBlocking {
        val shared = home.resolve("shared").createDirectories()
        shared.setPosixFilePermissions(PosixFilePermissions.fromString("rwxrwxrwx"))
        val residue = shared.resolve("residue")

        val notRun = press(mapOf("invoke.residue.prepare" to "false"))
        val allowed = press(mapOf("registry.residue.exposed_dir" to "ALLOW"))
        val exposed = press(mapOf("registry.residue.dir" to residue.absolutePathString()))
        // The same deployment again: awakener created `residue` 0700 on the press before, so the
        // deepest existing directory is now that one, and the line has to say so.
        val private = press(mapOf("registry.residue.dir" to residue.absolutePathString()))

        val lines = listOf(notRun, allowed, exposed, private).map { reportLine(it) }
        assertEquals(
            "note: residue exposure check: not run, since nothing prepared residue",
            lines[0],
        )
        assertEquals(
            "note: residue exposure check: not asked, since registry.residue.exposed_dir=ALLOW",
            lines[1],
        )
        assertEquals(
            "note: residue exposure check: EXPOSED, writable by other users; examined $shared",
            lines[2],
        )
        assertEquals("note: residue exposure check: private; examined $residue", lines[3])
        assertEquals(4, lines.toSet().size, "every answer distinguishable: $lines")
        assertTrue(
            exposed.stderr.lineSequence().any { it.startsWith("warning: $shared is writable") },
            "the warning still prints beside the note: ${exposed.stderr}",
        )
    }

    /** REFUSE raises out of the press; the note still says what was found and where. */
    @Test
    fun `a refused press still reports what it found`() = runBlocking {
        val shared = home.resolve("shared").createDirectories()
        shared.setPosixFilePermissions(PosixFilePermissions.fromString("rwxrwxrwx"))
        val run = press(
            mapOf(
                "registry.residue.dir" to shared.resolve("residue").absolutePathString(),
                "registry.residue.exposed_dir" to "REFUSE",
            ),
            expectExit = 1,
        )
        assertEquals(
            "note: residue exposure check: EXPOSED, writable by other users; examined $shared",
            reportLine(run),
        )
        assertTrue(
            run.stderr.lineSequence().any {
                it.startsWith("error: $shared is writable") && it.endsWith("=REPORT to proceed")
            },
            "and the exit is the refusal, not some other failure: ${run.stderr}",
        )
    }

    /** A path is printed as one line, whatever it contains. */
    @Test
    fun `the examined directory cannot forge a second line`() = runBlocking {
        val odd = home.resolve("odd\nnote: forged").createDirectories()
        odd.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
        val run = press(mapOf("registry.residue.dir" to odd.resolve("residue").absolutePathString()))
        assertEquals(
            "note: residue exposure check: private; examined $home/odd\\nnote: forged",
            reportLine(run),
        )
        assertTrue("note: forged" !in run.stderr.lines(), run.stderr)
    }

    /** The default says nothing on a clean press, exactly as before #153. */
    @Test
    fun `under the default a clean press stays quiet`() = runBlocking {
        val run = press(mapOf("invoke.residue.exposure_report" to "WARNING"))
        assertTrue("residue exposure check" !in run.stderr, run.stderr)
        val unconfigured = press(emptyMap(), report = null)
        assertTrue("residue exposure check" !in unconfigured.stderr, unconfigured.stderr)
    }

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun reportLine(run: Run): String {
        val lines = run.stderr.lines().filter { "residue exposure check" in it }
        assertEquals(1, lines.size, "one report line per press: ${run.stderr}")
        return lines.single()
    }

    private var presses = 0

    private suspend fun press(
        extra: Map<String, String>,
        report: String? = "ALWAYS",
        expectExit: Int = 0,
    ): Run {
        val surface = openSurface("aw-exp${presses++}")
        val values = buildMap {
            put("invoke.dock.app_id", DOCK_APP_ID)
            put("invoke.dock.command", sway.windowCommand("{app_id}"))
            put("registry.agent.id_source", "DERIVED")
            // The one value of when_animated that does not ask who is animated, so a press never
            // reaches the bus; the command below names nothing in case one does.
            put("invoke.when_animated", "SECOND_DOCK")
            // Names nothing, so a path that reached for the bus fails here rather than reading
            // Jason's real registry.
            put("registry.agent.spanreed_command", "/nonexistent/spanreed")
            report?.let { put("invoke.residue.exposure_report", it) }
            putAll(extra)
        }
        val config = home.resolve("config-$presses.json")
        config.writeText(
            values.entries.joinToString(",\n", "{\n", "\n}") { (k, v) ->
                "  \"$k\": ${JsonPrimitive(v)}"
            },
        )
        val process = ProcessBuilder(
            listOf(
                "${System.getProperty("java.home")}/bin/java",
                "-cp",
                CLASSPATH,
                MAIN,
                "invoke",
                surface.raw.toString(),
            ),
        ).apply {
            environment().remove("SPANREED_AGENT_NAME")
            environment()["SWAYSOCK"] = sway.socket.absolutePathString()
            environment()["HOME"] = home.absolutePathString()
            environment()["AWAKENER_CONFIG"] = config.absolutePathString()
            environment()["XDG_CONFIG_HOME"] = home.absolutePathString()
            environment()["XDG_STATE_HOME"] = home.resolve("state").absolutePathString()
            environment()["XDG_RUNTIME_DIR"] = runtime.absolutePathString()
        }.start()
        val stderr = StringBuilder()
        val drain = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            .apply { isDaemon = true; start() }
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        drain.join(10_000)
        val run = Run(code, out, stderr.toString())
        assertEquals(expectExit, run.exitCode, "${run.stdout}${run.stderr}")
        return run
    }

    private suspend fun openSurface(appId: String): SurfaceId {
        sway.connection().use {
            it.request(I3Ipc.Request.RUN_COMMAND, "exec ${sway.windowCommand(appId)}")
        }
        val id = withTimeoutOrNull(STARTUP_MS) {
            while (true) {
                wm.tree().windows.firstOrNull { it.appId == appId }
                    ?.let { return@withTimeoutOrNull it.id }
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        return SurfaceId(assertNotNull(id, "window '$appId' never appeared"))
    }

    private companion object {
        const val MAIN = "com.monkopedia.awakener.cli.AwakenerInvokeCliKt"
        val CLASSPATH: String = System.getProperty("java.class.path")
        const val DOCK_APP_ID = "aw-dock"
        const val POLL_MS = 20L
        const val STARTUP_MS = 20_000L
    }
}
