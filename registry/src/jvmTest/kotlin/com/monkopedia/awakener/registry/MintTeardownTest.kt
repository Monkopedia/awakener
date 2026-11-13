package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `registry.agent.register_on_mint` has a teardown (#188): the entry a mint put on the bus goes
 * when the binding it describes is forgotten.
 *
 * Against the real spanreed, because the claim is about what the roster holds afterwards and an
 * exit code is not a readback — `spanreed deregister` exits 0 and prints nothing whether or not
 * the id was there (measured against spanreed-bus 0.3.2 on 2026-11-12). Every call runs under a
 * scratch `SPANREED_STATE_ROOT`, so nothing here registers on the shared fleet bus; teardown
 * proves that as well as cleaning the scratch roster.
 */
class MintTeardownTest {
    private val dir = createTempDirectory("awakener-mint-teardown")
    private val busRoot = dir.resolve("bus")
    private val config = InMemoryConfigStore().apply {
        put(RegistryFlags.registerOnMint, true)
    }

    /** Every id a test in this class minted, so teardown can prove each one is gone. */
    private val minted = mutableSetOf<String>()

    private fun spanreed(vararg args: String, root: String? = busRoot.toString()): String {
        val command = listOf(SpanreedHarness.assumeAvailable().absolutePathString()) + args
        val result = ProcessCommandRunner().run(
            command,
            mapOf("SPANREED_STATE_ROOT" to root, "SPANREED_AGENT_NAME" to null),
        )
        check(result.succeeded) { "${command.joinToString(" ")} failed — ${result.problem}" }
        return result.stdout
    }

    /** Every id `list --include-stale` reports: the roster, stale entries included. */
    private fun roster(root: String? = busRoot.toString()): Set<String> =
        Json.parseToJsonElement(spanreed("list", "--include-stale", "--json", root = root))
            .jsonArray.map { it.jsonObject.getValue("agent_id").jsonPrimitive.content }.toSet()

    /**
     * A process standing in for the one-shot `awakener-invoke` that mints: alive while it
     * registers, so spanreed records its `pid_start`, and ended with [exited] before the forget.
     * Ending it after the register rather than registering a pid that is already dead is what
     * keeps a reused pid from reading live — spanreed matches the start time, and a dead pid has
     * none recorded.
     */
    private val minter = mutableListOf<Process>()

    private fun minterPid(): Long =
        ProcessBuilder("sleep", "60").start().also { minter += it }.pid()

    private fun exited() = minter.forEach { it.destroy(); it.waitFor() }

    private fun cli(pid: Long) = SpanreedCli(
        configStore = config,
        runner = { command, environment ->
            ProcessCommandRunner().run(
                command,
                environment + ("SPANREED_STATE_ROOT" to busRoot.toString()),
            )
        },
        ownPid = { pid },
    )

    private fun store(cli: SpanreedCli) =
        FileBindingStore(config, cli, environment = emptyMap(), path = dir.resolve("bindings.json"))

    @AfterTest
    fun cleanUp() {
        exited()
        try {
            if (SpanreedHarness.available()) {
                minted.forEach { spanreed("deregister", it) }
                val left = roster()
                assertTrue(minted.none { it in left }, "scratch roster still holds $left")
                // The isolation itself: none of these ids may have reached the shared bus.
                val shared = roster(root = null)
                assertTrue(minted.none { it in shared }, "a test id reached the shared bus")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `forgetting a binding removes the entry its mint registered`() = runTest {
        SpanreedHarness.assumeAvailable()
        val store = store(cli(minterPid()))
        val key = SurfaceKey.Window("awakener-test-188")
        val binding = store.bind(key)
        minted += binding.agentId
        assertTrue(binding.agentId in roster(), "the mint registered nothing to tear down")
        exited()

        val forget = store.unbind(key)

        assertTrue(forget.wasBound)
        assertFalse(binding.agentId in roster(), "the forgotten Lifeless is still on the roster")
        assertEquals(BusOutcome.Deregistered(binding.agent), forget.bus)
    }

    /**
     * A forget made by a dock teardown: the panel's session registered over the mint's entry
     * with its own pid, and is still exiting when the forget reads the roster — its window left
     * the tree first. Measured: a real `claude` exits 283-529ms after its foot window is gone.
     */
    @Test
    fun `an ending session is waited out, then deregistered`() = runTest {
        SpanreedHarness.assumeAvailable()
        val store = store(cli(minterPid()))
        val key = SurfaceKey.Window("awakener-test-188", "ending")
        val binding = store.bind(key)
        minted += binding.agentId
        exited()
        val session = ProcessBuilder("sleep", "60").start()
        try {
            spanreed(
                "register", "--agent-id", binding.agentId, "--name", binding.spanreedName,
                "--working-dir", "/tmp", "--pid", session.pid().toString(),
            )
            Thread {
                Thread.sleep(700)
                session.destroy()
            }.start()

            val forget = store.unbind(key, sessionEnding = true)

            assertEquals(BusOutcome.Deregistered(binding.agent), forget.bus)
            assertFalse(binding.agentId in roster(), "the ended session's entry outlived it")
        } finally {
            session.destroy()
        }
    }

    /** The same moment with no wait: the leak the flag exists to close, pinned at its 0. */
    @Test
    fun `with no wait an ending session's entry is left`() = runTest {
        SpanreedHarness.assumeAvailable()
        config.put(RegistryFlags.endingSessionWaitMs, 0L)
        val store = store(cli(minterPid()))
        val key = SurfaceKey.Window("awakener-test-188", "nowait")
        val binding = store.bind(key)
        minted += binding.agentId
        exited()
        val session = ProcessBuilder("sleep", "60").start()
        try {
            spanreed(
                "register", "--agent-id", binding.agentId, "--name", binding.spanreedName,
                "--working-dir", "/tmp", "--pid", session.pid().toString(),
            )

            assertEquals(
                BusOutcome.StillLive(binding.agent),
                store.unbind(key, sessionEnding = true).bus,
            )
            assertTrue(binding.agentId in roster())
        } finally {
            session.destroy()
        }
    }

    /** The command a user actually runs, and what it tells them about the roster. */
    @Test
    fun `awakener-registry forget deregisters and says so`() = runTest {
        SpanreedHarness.assumeAvailable()
        val store = store(cli(minterPid()))
        val key = SurfaceKey.Window("awakener-test-188", "cli")
        val binding = store.bind(key)
        minted += binding.agentId
        val out = mutableListOf<String>()
        exited()

        assertEquals(0, RegistryCli.run(arrayOf("forget", key.canonical), store, out::add))

        assertFalse(binding.agentId in roster())
        assertTrue(out.any { it == "  spanreed deregistered ${binding.agentId}" }, out.toString())
    }

    /**
     * A panel's session registers itself under the same id, with its own live pid. Forgetting the
     * binding underneath a running panel must not take that agent off the roster its peers
     * address it through.
     */
    @Test
    fun `a live session's entry survives the forget`() = runTest {
        SpanreedHarness.assumeAvailable()
        val store = store(cli(ProcessHandle.current().pid()))
        val key = SurfaceKey.Window("awakener-test-188", "live")
        val binding = store.bind(key)
        minted += binding.agentId

        assertEquals(BusOutcome.StillLive(binding.agent), store.unbind(key).bus)
        assertTrue(binding.agentId in roster())
    }

    @Test
    fun `with the teardown off a forget leaves the bus alone`() = runTest {
        SpanreedHarness.assumeAvailable()
        config.put(RegistryFlags.deregisterOnForget, false)
        val store = store(cli(minterPid()))
        val key = SurfaceKey.Window("awakener-test-188", "off")
        val binding = store.bind(key)
        minted += binding.agentId
        exited()

        assertEquals(BusOutcome.NotAsked, store.unbind(key).bus)
        assertTrue(binding.agentId in roster(), "the pre-#188 behaviour: the entry outlives it")
    }

    /** The default install: nothing was registered, so one read finds nothing to take down. */
    @Test
    fun `forgetting an agent that was never registered reports it absent`() = runTest {
        SpanreedHarness.assumeAvailable()
        config.put(RegistryFlags.registerOnMint, false)
        val store = store(cli(minterPid()))
        val key = SurfaceKey.Window("awakener-test-188", "never")
        val binding = store.bind(key)
        minted += binding.agentId

        assertEquals(BusOutcome.Absent(binding.agent), store.unbind(key).bus)
    }

    /**
     * A host with no spanreed: nothing can have been registered there, so the forget says
     * nothing about the bus rather than warning on every run — and the binding still goes.
     */
    @Test
    fun `a spanreed that cannot run is not asked, and the forget still happens`() = runTest {
        val identity = AgentIdentity(AgentId("agent-awakener-test-188-unrunnable"), "x")
        val store = FileBindingStore(
            config,
            object : AgentIdentities {
                override suspend fun mint(key: SurfaceKey, residuePath: String) = identity
                override suspend fun retire(identity: AgentIdentity, sessionEnding: Boolean) =
                    SpanreedCli(
                        InMemoryConfigStore()
                            .put(RegistryFlags.spanreedCommand, "awakener-no-such-spanreed"),
                    ).retire(identity, sessionEnding)
            },
            environment = emptyMap(),
            path = dir.resolve("bindings.json"),
        )
        val key = SurfaceKey.Window("awakener-test-188", "unrunnable")
        store.bind(key)

        val forget = store.unbind(key)

        assertTrue(forget.wasBound)
        assertEquals(BusOutcome.NotAsked, forget.bus)
        assertEquals(null, store.resolve(key))
    }
}
