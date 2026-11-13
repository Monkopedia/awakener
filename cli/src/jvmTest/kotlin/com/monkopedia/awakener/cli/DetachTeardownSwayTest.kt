package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentBus
import com.monkopedia.awakener.registry.BusOutcome
import com.monkopedia.awakener.registry.FileBindingStore
import com.monkopedia.awakener.registry.LiveAgent
import com.monkopedia.awakener.registry.ProcessCommandRunner
import com.monkopedia.awakener.registry.RegistryFlags
import com.monkopedia.awakener.registry.SpanreedCli
import com.monkopedia.awakener.registry.SpanreedHarness
import com.monkopedia.awakener.wm.I3Ipc
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.SwayHarness
import com.monkopedia.awakener.wm.SwayWindowManager
import com.monkopedia.awakener.wm.WmFlags
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * #188 on the one forget nobody runs on purpose: `wm.dock.forget_binding_on_detach`.
 *
 * The panel's session registers itself under the Lifeless's id with its own pid, and its window
 * leaves the tree before that process exits. Measured on kaladin, foot 1.27.0 on headless sway
 * 1.12, 2026-11-12: the node was gone in 2-3ms and a real `claude` exited 283-529ms after it, over
 * five runs. So the forget that follows the kill finds a live entry, and without the wait in
 * `registry.agent.deregister_ending_session_wait_ms` it left it — stale moments later, with the
 * binding gone and nothing left to forget it by.
 *
 * The panel here is a stand-in honest about that: it registers under its own pid as a session
 * does, and takes 700ms to exit on SIGHUP, longer than any `claude` measured. Everything runs
 * under a scratch `SPANREED_STATE_ROOT`, and teardown proves nothing reached the shared bus.
 */
class DetachTeardownSwayTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var store: InMemoryConfigStore
    private lateinit var dir: Path
    private lateinit var busRoot: Path
    private lateinit var wm: SwayWindowManager
    private lateinit var awakening: Awakening
    private lateinit var spanreed: String
    private val minted = mutableSetOf<String>()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        spanreed = SpanreedHarness.assumeAvailable().absolutePathString()
        Flags.requireLoaded(InvokeFlags, RegistryFlags, WmFlags)
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        dir = createTempDirectory("awakener-detach-teardown")
        busRoot = dir.resolve("bus")
        store = InMemoryConfigStore()

        val panel = dir.resolve("panel.sh")
        panel.writeText(
            """
            #!/bin/sh
            "$spanreed" register --agent-id "${'$'}1" --name "${'$'}2" --working-dir /tmp \
                --pid ${'$'}${'$'} >/dev/null
            trap 'sleep 0.7; exit 0' HUP TERM
            while :; do sleep 1 & wait ${'$'}!; done
            """.trimIndent(),
        )
        panel.toFile().setExecutable(true)
        store.put(InvokeFlags.dockAppId, DOCK_APP_ID)
        store.put(
            InvokeFlags.dockCommand,
            "foot -a {app_id} -- env SPANREED_STATE_ROOT=$busRoot " +
                "${panel.absolutePathString()} {agent_id} {agent_name}",
        )
        store.put(WmFlags.sweepOnClose, false)
        store.put(WmFlags.forgetBindingOnDetach, true)
        store.put(RegistryFlags.registerOnMint, true)

        val cli = SpanreedCli(
            configStore = store,
            runner = { command, environment ->
                ProcessCommandRunner().run(
                    command,
                    environment + ("SPANREED_STATE_ROOT" to busRoot.toString()),
                )
            },
        )
        val registry = FileBindingStore(store, cli, path = dir.resolve("bindings.json"))
        wm = SwayWindowManager({ sway.connection() }, store, registry, scope)
        awakening = Awakening(
            wm = wm,
            registry = registry,
            bus = AgentBus { emptyList<LiveAgent>() },
            store = store,
            prepareResidue = { registry.prepareResidue(it) },
            reapOrphans = wm::reapOrphans,
            dockFor = wm::dockFor,
            focusedDock = wm::focusedDock,
        )
    }

    @AfterTest
    fun tearDown() {
        if (!SwayHarness.available()) return
        try {
            scope.cancel()
            sway.close()
            minted.forEach { run("deregister", it) }
            val left = roster().keys
            assertTrue(minted.none { it in left }, "scratch roster still holds $left")
            val shared = roster(root = null).keys
            assertTrue(minted.none { it in shared }, "a test id reached the shared bus")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun run(vararg args: String, root: String? = busRoot.toString()): String {
        val result = ProcessCommandRunner().run(
            listOf(spanreed) + args,
            mapOf("SPANREED_STATE_ROOT" to root, "SPANREED_AGENT_NAME" to null),
        )
        check(result.succeeded) { "spanreed ${args.joinToString(" ")} failed — ${result.problem}" }
        return result.stdout
    }

    /** Every id on the roster, stale included, with the pid it is registered under. */
    private fun roster(root: String? = busRoot.toString()): Map<String, Long?> =
        json.parseToJsonElement(run("list", "--include-stale", "--json", root = root))
            .jsonArray.associate {
                val entry = it.jsonObject
                entry.getValue("agent_id").jsonPrimitive.content to
                    entry["pid"]?.jsonPrimitive?.longOrNull
            }

    private fun swayTest(body: suspend () -> Unit) = runBlocking {
        sway.connection().use {
            it.request(I3Ipc.Request.RUN_COMMAND, "workspace 1; layout tabbed")
        }
        body()
    }

    private suspend fun openSurface(appId: String): SurfaceId {
        sway.connection().use {
            it.request(I3Ipc.Request.RUN_COMMAND, "exec ${sway.windowCommand(appId)}")
        }
        repeat(100) {
            wm.surfaces().firstOrNull { it.appId == appId }?.let { return it.id }
            delay(50)
        }
        error("window '$appId' never appeared")
    }

    /** Stands a panel up and waits until its session has registered over the mint's entry. */
    private suspend fun animated(): Awakened.Animated {
        openSurface("aw-app188")
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())
        minted += awakened.binding.agentId
        val own = ProcessHandle.current().pid()
        repeat(100) {
            val pid = roster()[awakened.binding.agentId]
            if (pid != null && pid != own) return awakened
            delay(50)
        }
        error("the panel's session never registered: ${roster()}")
    }

    @Test
    fun `a forget on detach removes the entry the panel's session left`() = swayTest {
        val awakened = animated()

        awakened.dock.detach()

        assertFalse(
            awakened.binding.agentId in roster(),
            "the ended session's entry outlived the binding: ${wm.busEntriesLeft.value}",
        )
        assertEquals(emptyList(), wm.busEntriesLeft.value, "and nothing is reported as left")
    }

    /** The flag's 0, which is the leak — pinned so the wait is what the test above measures. */
    @Test
    fun `with no wait the entry is left, and the detach says so`() = swayTest {
        store.put(RegistryFlags.endingSessionWaitMs, 0L)
        val awakened = animated()

        awakened.dock.detach()

        assertTrue(awakened.binding.agentId in roster(), "the session was still live at the read")
        val left = assertNotNull(
            wm.busEntriesLeft.value.singleOrNull(),
            "${wm.busEntriesLeft.value}",
        )
        assertEquals(BusOutcome.StillLive(awakened.binding.agent), left.outcome)
        assertEquals(awakened.surface, left.surface)
        assertEquals(awakened.dock.dockId, left.dock)
    }

    private companion object {
        const val DOCK_APP_ID = "aw-dock188"
    }
}
