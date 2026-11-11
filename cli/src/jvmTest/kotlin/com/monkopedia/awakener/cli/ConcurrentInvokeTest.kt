package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import com.monkopedia.awakener.wm.CommandResult
import com.monkopedia.awakener.wm.I3Ipc
import com.monkopedia.awakener.wm.Node
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.SwayHarness
import com.monkopedia.awakener.wm.SwayWindowManager
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Several `awakener-invoke` processes pressed at once (#199).
 *
 * Every hotkey press is its own JVM, so the only honest reproduction is several real processes —
 * the same entry point the shipped launcher `exec`s — released off one gate against a headless
 * sway. Before the cross-process attach lock, two presses on two surfaces both reported the same
 * dock, one surface got no panel, the other got two (one sometimes titled with the other surface's
 * agent), and the second dock stood unclaimed. Four presses on one surface stood four docks
 * that nested splits.
 *
 * The docks carry their agent's name as their title (`foot -T`), which is what makes "running the
 * wrong agent" assertable without an agent.
 *
 * Nothing here touches the real bus: ids are derived, and the spanreed command either names
 * nothing or — for the REUSE cases, the one value that asks who is animated — is a stand-in
 * script in the test's own directory that lists an agent once its dock has "registered".
 *
 * Exercised against **sway 1.12**, headless wlroots backend, `workspace_layout tabbed`.
 */
class ConcurrentInvokeTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var home: Path
    private lateinit var runtime: Path

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start("workspace_layout tabbed")
        scope = CoroutineScope(SupervisorJob())
        home = createTempDirectory("awakener-concurrent-invoke")
        home.resolve("state").createDirectories()
        runtime = home.resolve("run").createDirectories()
        // A manager of the test's own, for reading the tree back and tearing docks down. Its
        // bindings file is not the presses', which is fine: it only ever detaches.
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

    /** #199 as filed: one press per surface, released together, each gets its own dock. */
    @Test
    fun `concurrent presses on two surfaces each get their own dock`() = runBlocking {
        val config = config(whenAnimated = "SECOND_DOCK")
        for (round in 1..ROUNDS) {
            val a = openSurface("aw-a$round")
            val b = openSurface("aw-b$round")
            val runs = pressTogether(config, listOf(a, b))
            runs.forEach { assertEquals(0, it.exitCode, "round $round: ${it.stdout}${it.stderr}") }

            val claimed = runs.map { run ->
                val (dock, surface) = assertNotNull(DOCK_LINE.find(run.stdout), run.stdout)
                    .destructured.let { (d, s) -> d.toLong() to s.toLong() }
                val agent = assertNotNull(AGENT_LINE.find(run.stdout), run.stdout).groupValues[1]
                Triple(dock, surface, agent)
            }
            assertEquals(
                2,
                claimed.map { it.first }.toSet().size,
                "round $round: each press its own dock, not one dock twice: $claimed",
            )
            val tree = wm.tree()
            claimed.forEach { (dock, surface, agent) ->
                val node = assertNotNull(tree.find(dock), "round $round: dock $dock stands")
                assertEquals(agent, node.name, "round $round: dock $dock runs its own agent")
                assertEquals(1, node.marks.size, "round $round: dock $dock marks ${node.marks}")
                assertTrue(
                    tree.parentOf(dock)?.children?.any { it.id == surface } == true,
                    "round $round: dock $dock is beside surface $surface: ${describe()}",
                )
            }
            val unclaimed = docks(tree).map { it.id } - claimed.map { it.first }.toSet()
            assertEquals(emptyList(), unclaimed, "round $round: unclaimed docks; ${describe()}")
            listOf(a, b).forEach { surface ->
                val beside = docks(tree).count { dock ->
                    tree.parentOf(dock.id)?.children?.any { it.id == surface.raw } == true
                }
                assertEquals(1, beside, "round $round: docks beside ${surface.raw}; ${describe()}")
            }

            listOf(a, b).forEach { assertNotNull(wm.dockFor(it), "round $round").detach() }
            assertEquals(0, singleChildSplits(), "after round $round: ${describe()}")
        }
    }

    /**
     * #199's second consequence, through the hotkey: a burst of presses on one surface under
     * TOGGLE_HIDE is one attach followed by toggles, not one attach each. Repeated so a nest that
     * deepens by a level per round shows up as a round that does not return to baseline.
     */
    @Test
    fun `a burst of presses on one surface stands one dock and nests nothing`() = runBlocking {
        val config = config(whenAnimated = "TOGGLE_HIDE")
        openSurface("aw-other")
        val app = openSurface("aw-app")
        val baseline = describe()
        for (round in 1..ROUNDS) {
            val runs = pressTogether(config, List(BURST) { app })
            runs.forEach { assertEquals(0, it.exitCode, "round $round: ${it.stdout}${it.stderr}") }
            assertEquals(
                1,
                runs.count { DOCK_LINE.containsMatchIn(it.stdout) },
                "round $round: exactly one press attached: ${runs.map { it.stdout }}",
            )
            val tree = wm.tree()
            assertEquals(1, docks(tree).size, "round $round: one dock in the tree; ${describe()}")

            assertNotNull(wm.dockFor(app), "round $round: the dock is the surface's").detach()
            assertEquals(0, singleChildSplits(), "after round $round: ${describe()}")
            assertEquals(baseline, describe(), "after round $round")
        }
    }

    /**
     * The lock is bounded and does not outlive its holder. A press whose dock never maps holds
     * the lock across its map wait; a second press with a short wait gives up having changed
     * nothing; the holder is then SIGKILLed mid-attach, and a third press gets the lock at once
     * rather than waiting out anything.
     */
    @Test
    fun `a held lock is bounded, and a killed holder releases it`() = runBlocking {
        val app = openSurface("aw-app")
        val holderConfig = config(
            whenAnimated = "SECOND_DOCK",
            dockCommand = "sleep 3600",
            extra = mapOf("wm.wait.map_ms" to "60000"),
        )
        val holder = start(holderConfig, app)
        try {
            val lock = assertNotNull(
                withTimeoutOrNull(STARTUP_MS) {
                    while (true) {
                        heldLock()?.let { return@withTimeoutOrNull it }
                        delay(POLL_MS)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                },
                "the first press never took the attach lock",
            )

            val refused = finish(
                start(config("SECOND_DOCK", extra = mapOf("wm.attach.lock_wait_ms" to "500")), app),
            )
            assertEquals(1, refused.exitCode, refused.stdout + refused.stderr)
            assertTrue("held the attach lock" in refused.stderr, refused.stderr)
            assertEquals(0, docks(wm.tree()).size, "the refused press changed nothing")

            holder.destroyForcibly()
            assertTrue(holder.waitFor(10, TimeUnit.SECONDS))
            assertTrue(lock.exists())

            val next = finish(
                start(config("SECOND_DOCK", extra = mapOf("wm.attach.lock_wait_ms" to "3000")), app),
            )
            assertEquals(0, next.exitCode, next.stdout + next.stderr)
            assertTrue(DOCK_LINE.containsMatchIn(next.stdout), next.stdout)
        } finally {
            holder.destroyForcibly()
        }
    }

    /**
     * #198 through the shipped entry point: two presses in quick succession under the default
     * REUSE, beside a dock whose agent registers [REGISTER_DELAY_S] after it starts — longer than
     * the 0.5–0.8s a real `claude` took on kaladin, so the second press lands in the gap rather
     * than near its edge.
     *
     * Each round closes the dock by hand afterwards, because that is #171's remaining leak: two
     * docks on one surface closed by hand compounded single-child splits 2 → 6 over five rounds.
     * With one dock per round the count must stay at the one a hand-closed visible dock leaves.
     */
    @Test
    fun `two quick presses under REUSE stand one panel before the agent registers`() = runBlocking {
        val config = lateRegisteringConfig()
        val app = openSurface("aw-app")
        for (round in 1..ROUNDS) {
            val first = finish(start(config, app))
            val second = finish(start(config, app))
            listOf(first, second).forEach {
                assertEquals(0, it.exitCode, "round $round: ${it.stdout}${it.stderr}")
            }
            assertTrue(DOCK_LINE.containsMatchIn(first.stdout), "round $round: ${first.stdout}")
            assertTrue(!live.exists(), "round $round: the second press beat registration")
            assertTrue(
                "already animated" in second.stdout,
                "round $round: the second press declined: ${second.stdout}",
            )
            assertEquals(1, docks(wm.tree()).size, "round $round: one panel; ${describe()}")

            closeDocksByHand()
            assertTrue(singleChildSplits() <= 1, "after round $round: ${describe()}")
        }
    }

    /**
     * The same two presses released together. The attach lock serialises them, and the one that
     * waited is past the lock with the panel already marked but its agent not yet registered.
     */
    @Test
    fun `two presses released together under REUSE stand one panel`() = runBlocking {
        val config = lateRegisteringConfig()
        val app = openSurface("aw-app")
        val runs = pressTogether(config, List(2) { app })
        runs.forEach { assertEquals(0, it.exitCode, it.stdout + it.stderr) }
        assertTrue(!live.exists(), "both presses beat registration")
        assertEquals(1, runs.count { DOCK_LINE.containsMatchIn(it.stdout) }, runs.map { it.stdout }.toString())
        assertEquals(1, runs.count { "already animated" in it.stdout }, runs.map { it.stdout }.toString())
        assertEquals(1, docks(wm.tree()).size, describe())
        closeDocksByHand()
    }

    /**
     * The control the test above needs: the same presses under `invoke.reuse_evidence=BUS` stand
     * two panels. Without it, "one panel" could be a dock that never came up a second time for
     * some other reason.
     */
    @Test
    fun `under reuse_evidence=BUS the second quick press stands a second panel`() = runBlocking {
        val config = lateRegisteringConfig(mapOf("invoke.reuse_evidence" to "BUS"))
        val app = openSurface("aw-app")
        val first = finish(start(config, app))
        val second = finish(start(config, app))
        assertTrue(!live.exists(), "the second press beat registration")
        listOf(first, second).forEach {
            assertTrue(DOCK_LINE.containsMatchIn(it.stdout), it.stdout + it.stderr)
        }
        assertEquals(2, docks(wm.tree()).size, describe())
        closeDocksByHand()
    }

    /** Once the agent has registered, the bus answers as it always did. */
    @Test
    fun `a press after registration is declined by the bus as before`() = runBlocking {
        val config = lateRegisteringConfig(mapOf("invoke.reuse_evidence" to "BUS"))
        val app = openSurface("aw-app")
        assertTrue(DOCK_LINE.containsMatchIn(finish(start(config, app)).stdout))
        assertNotNull(
            withTimeoutOrNull(STARTUP_MS) { while (!live.exists()) delay(POLL_MS) },
            "the dock never registered",
        )
        val again = finish(start(config, app))
        assertTrue("already animated" in again.stdout, again.stdout + again.stderr)
        assertEquals(1, docks(wm.tree()).size, describe())
        closeDocksByHand()
    }

    // -- helpers ------------------------------------------------------------------------

    /** Where the stand-in bus reads its listing from; the dock writes it once "registered". */
    private val live: Path get() = home.resolve("live.json")

    /**
     * A REUSE config whose bus is a stand-in: `list` prints `[]` until the dock's program has
     * written [live], [REGISTER_DELAY_S] after it starts — the shape of a `claude` whose
     * `SessionStart` hook has not run yet.
     */
    private fun lateRegisteringConfig(extra: Map<String, String> = emptyMap()): Path {
        val bus = home.resolve("spanreed-standin")
        bus.writeText(
            """
            #!/bin/sh
            [ "${'$'}1" = list ] || exit 64
            if [ -f '$live' ]; then cat '$live'; else echo '[]'; fi
            """.trimIndent() + "\n",
        )
        val dock = home.resolve("late-register")
        dock.writeText(
            """
            #!/bin/sh
            sleep $REGISTER_DELAY_S
            printf '[{"agent_id":"%s"}]\n' "${'$'}1" > '$live'
            exec sleep 3600
            """.trimIndent() + "\n",
        )
        listOf(bus, dock).forEach { it.toFile().setExecutable(true) }
        return config(
            whenAnimated = "REUSE",
            dockCommand = "foot -a {app_id} -T {agent_name} -- $dock {agent_id}",
            extra = mapOf("registry.agent.spanreed_command" to bus.absolutePathString()) + extra,
        )
    }

    /** Closes every dock the way a user does — `kill` on the window, not a detach. */
    private suspend fun closeDocksByHand() {
        command("[app_id=\"$DOCK_APP_ID\"] kill")
        withTimeoutOrNull(STARTUP_MS) { while (docks(wm.tree()).isNotEmpty()) delay(POLL_MS) }
        assertEquals(0, docks(wm.tree()).size, "docks closed by hand: ${describe()}")
        // spanreed drops an agent whose process has gone; the stand-in has to be told.
        live.toFile().delete()
    }


    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun config(
        whenAnimated: String,
        dockCommand: String = "foot -a {app_id} -T {agent_name} -- sleep 3600",
        extra: Map<String, String> = emptyMap(),
    ): Path {
        val values = mapOf(
            "invoke.when_animated" to whenAnimated,
            "invoke.dock.app_id" to DOCK_APP_ID,
            "invoke.dock.command" to dockCommand,
            "registry.agent.id_source" to "DERIVED",
            // Names nothing, so a path that reached for the bus would fail loudly here rather
            // than read Jason's real registry.
            "registry.agent.spanreed_command" to "/nonexistent/spanreed",
        ) + extra
        val file = home.resolve("config-${configs++}.json")
        file.writeText(
            values.entries.joinToString(",\n", "{\n", "\n}") { (k, v) ->
                val literal = v.toLongOrNull()?.toString() ?: JsonPrimitive(v).toString()
                "  \"$k\": $literal"
            },
        )
        return file
    }

    private var configs = 0

    private val json = Json { ignoreUnknownKeys = true }

    /** Starts one press per entry in [targets], all released off one latch. */
    private fun pressTogether(config: Path, targets: List<SurfaceId>): List<Run> {
        val gate = CountDownLatch(1)
        val results = arrayOfNulls<Run>(targets.size)
        val threads = targets.mapIndexed { index, target ->
            val builder = builder(config, target)
            Thread {
                gate.await()
                results[index] = finish(builder.start())
            }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join(PRESS_MS) }
        return results.map { assertNotNull(it, "a press did not finish") }
    }

    private fun start(config: Path, target: SurfaceId): Process = builder(config, target).start()

    private fun builder(config: Path, target: SurfaceId) = ProcessBuilder(
        listOf(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            CLASSPATH,
            MAIN,
            "invoke",
            target.raw.toString(),
        ),
    ).apply {
        environment().remove("SPANREED_AGENT_NAME")
        environment()["SWAYSOCK"] = sway.socket.absolutePathString()
        environment()["HOME"] = home.absolutePathString()
        environment()["AWAKENER_CONFIG"] = config.absolutePathString()
        environment()["XDG_CONFIG_HOME"] = home.absolutePathString()
        environment()["XDG_STATE_HOME"] = home.resolve("state").absolutePathString()
        environment()["XDG_RUNTIME_DIR"] = runtime.absolutePathString()
    }

    private fun finish(process: Process): Run {
        val stderr = StringBuilder()
        val drain = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            .apply { isDaemon = true; start() }
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        drain.join(10_000)
        return Run(code, out, stderr.toString())
    }

    /** The attach lock file, if another process currently holds it. */
    private fun heldLock(): Path? {
        val dir = runtime.resolve("awakener")
        if (!dir.exists()) return null
        return dir.listDirectoryEntries("attach-*.lock").firstOrNull { file ->
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                val probe = channel.tryLock()
                probe?.release()
                probe == null
            }
        }
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        json.decodeFromString<List<CommandResult>>(raw)
            .firstOrNull { !it.success }
            ?.let { error("sway rejected '$text': ${it.error}") }
    }

    private suspend fun openSurface(appId: String): SurfaceId {
        command("exec ${sway.windowCommand(appId)}")
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

    private fun docks(tree: Node): List<Node> = tree.windows.filter { it.appId == DOCK_APP_ID }

    private suspend fun singleChildSplits(): Int {
        fun count(node: Node): Int =
            (if (node.type == "con" && node.layout?.startsWith("split") == true && node.nodes.size == 1) 1 else 0) +
                node.children.sumOf { count(it) }
        return count(workspace())
    }

    private suspend fun workspace(): Node =
        assertNotNull(wm.tree().workspace("1"), "workspace 1 exists")

    private suspend fun describe(): String {
        fun describe(node: Node): String =
            if (node.children.isEmpty()) {
                "${node.appId ?: node.id}"
            } else {
                "${node.layout}[${node.children.joinToString(" ") { describe(it) }}]"
            }
        return describe(workspace())
    }

    private companion object {
        const val MAIN = "com.monkopedia.awakener.cli.AwakenerInvokeCliKt"
        val CLASSPATH: String = System.getProperty("java.class.path")
        const val DOCK_APP_ID = "aw-dock"
        const val ROUNDS = 3
        const val BURST = 4
        /** Longer than the 0.5–0.8s measured for a real `claude` on kaladin; see the #198 test. */
        const val REGISTER_DELAY_S = 4
        const val POLL_MS = 20L
        const val STARTUP_MS = 20_000L
        const val PRESS_MS = 60_000L
        /** The line an attach prints — indented, unlike a toggle's "hid dock …" or "restored dock …". */
        val DOCK_LINE = Regex("""^  dock (\d+) beside surface (\d+)$""", RegexOption.MULTILINE)
        val AGENT_LINE = Regex("""SPANREED_AGENT_NAME=(\S+)""")
    }
}
