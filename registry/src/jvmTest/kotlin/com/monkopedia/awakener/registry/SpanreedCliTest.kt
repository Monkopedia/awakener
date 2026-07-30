package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Asserts on the exact argv and environment awakener hands to spanreed.
 *
 * The CLI is spanreed's public contract and its registry file is not, so what matters is that
 * every interaction goes through the command line and that `SPANREED_AGENT_NAME` — the only
 * mechanism by which a surface, which has no working directory, can have a stable identity —
 * is actually set. A recording runner keeps that checkable without registering throwaway agents
 * on the developer's live bus.
 */
class SpanreedCliTest {
    private val config = InMemoryConfigStore()
    private val calls = mutableListOf<Pair<List<String>, Map<String, String>>>()
    private var response = ProcessResult(0, "agent-from-spanreed\n", "")

    private val cli = SpanreedCli(
        configStore = config,
        runner = { command, environment ->
            calls += command to environment
            response
        },
        ownPid = { 4321L },
        residuePathFor = { _, key -> "/state/residue/${key.slug}.md" },
    )

    @Test
    fun `minting asks spanreed for the id under the surface's agent name`() = runTest {
        val identity = cli.mint(SurfaceKey.Window("firefox"))

        val (command, environment) = calls.single()
        assertEquals(listOf("spanreed", "agent-id"), command)
        assertEquals(
            identity.spanreedName,
            environment["SPANREED_AGENT_NAME"],
            "the name is the whole mechanism — without it spanreed derives from cwd",
        )
        assertEquals(AgentId("agent-from-spanreed"), identity.id)
    }

    @Test
    fun `the minted name carries the configured prefix and the surface slug`() = runTest {
        config.put(RegistryFlags.agentNamePrefix, "surface-")
        val key = SurfaceKey.Origin("https://github.com")

        assertEquals("surface-${key.slug}", cli.mint(key).spanreedName)
    }

    /** A name that is stable is the point; one that changes per launch is not an identity. */
    @Test
    fun `the same surface mints the same name every time`() = runTest {
        val key = SurfaceKey.Window("foot", "kaladin")
        assertEquals(cli.mint(key).spanreedName, cli.mint(key).spanreedName)
    }

    @Test
    fun `the derived source applies spanreed's rule without a subprocess`() = runTest {
        config.put(RegistryFlags.agentIdSource, AgentIdSource.DERIVED)
        val identity = cli.mint(SurfaceKey.Window("firefox"))

        assertTrue(calls.isEmpty(), "DERIVED must not shell out")
        assertEquals(AgentId("agent-${identity.spanreedName}"), identity.id)
    }

    /**
     * A wrong id is worse than no id: it would silently address a different agent, so a failed
     * or empty derivation must not be turned into a plausible-looking identity.
     */
    @Test
    fun `a failing spanreed is surfaced rather than papered over`() = runTest {
        response = ProcessResult(1, "", "spanreed: not configured")
        assertTrue(
            assertFailsWith<IllegalStateException> { cli.mint(SurfaceKey.Window("firefox")) }
                .message!!.contains("not configured"),
        )

        response = ProcessResult(0, "  \n", "")
        assertFailsWith<IllegalStateException> { cli.mint(SurfaceKey.Window("firefox")) }
    }

    @Test
    fun `registering on mint is off by default and switchable on`() = runTest {
        cli.mint(SurfaceKey.Window("firefox"))
        assertEquals(1, calls.size, "only the id derivation, no registration")

        calls.clear()
        config.put(RegistryFlags.registerOnMint, true)
        val identity = cli.mint(SurfaceKey.Window("firefox"))

        assertEquals(
            listOf(
                "spanreed", "register",
                "--agent-id", identity.id.raw,
                "--name", identity.spanreedName,
                "--working-dir", "/state/residue/${SurfaceKey.Window("firefox").slug}.md",
                "--pid", "4321",
            ),
            calls.last().first,
            "a surface has no cwd, so its residue location stands in for one",
        )
    }

    @Test
    fun `the spanreed executable is configurable`() = runTest {
        config.put(RegistryFlags.spanreedCommand, "/opt/spanreed/bin/spanreed")
        cli.mint(SurfaceKey.Window("firefox"))
        assertEquals("/opt/spanreed/bin/spanreed", calls.single().first.first())
    }

    /**
     * The one test that touches the real thing. `agent-id` only prints a derivation — it does
     * not write to the registry — so this is safe to run against an installed spanreed, and it
     * is the only way to catch the derivation rule changing underneath us.
     */
    @Test
    fun `the real spanreed derives agent-name from SPANREED_AGENT_NAME`() = runTest {
        val spanreed = onPath("spanreed")
        if (spanreed == null) {
            check(System.getenv("AWAKENER_REQUIRE_SPANREED") != "1") {
                "AWAKENER_REQUIRE_SPANREED=1 but spanreed is not installed"
            }
            println("skipping: spanreed is not installed")
            return@runTest
        }
        val real = SpanreedCli(
            InMemoryConfigStore().put(
                RegistryFlags.spanreedCommand,
                spanreed.absolutePathString(),
            ),
        )
        val key = SurfaceKey.Window("awakener-test-surface")
        val identity = real.mint(key)

        assertEquals(
            AgentId("agent-${identity.spanreedName}"),
            identity.id,
            "spanreed still documents the override as agent-<name>; if this fails, " +
                "RegistryFlags.agentIdSource=DERIVED has silently become wrong",
        )
        assertEquals(identity, real.mint(key), "and it is deterministic")
    }

    private fun onPath(tool: String): Path? = System.getenv("PATH").orEmpty().split(':')
        .map { Path.of(it, tool) }
        .firstOrNull { it.exists() }
}
