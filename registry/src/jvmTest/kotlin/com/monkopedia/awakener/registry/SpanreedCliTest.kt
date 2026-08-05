package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isExecutable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.AssumptionViolatedException

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
    private val calls = mutableListOf<Pair<List<String>, Map<String, String?>>>()
    private var response = ProcessResult(0, "agent-from-spanreed\n", "")

    private val cli = SpanreedCli(
        configStore = config,
        runner = { command, environment ->
            calls += command to environment
            response
        },
        ownPid = { 4321L },
    )

    /** The store is what knows where residue lives, so the caller supplies it. */
    private suspend fun SpanreedCli.mint(key: SurfaceKey) =
        mint(key, "/state/residue/${key.slug}.md")

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

    @Test
    fun `listing the bus asks under no agent name at all`() = runTest {
        response = ProcessResult(0, "[]", "")
        assertEquals(emptyList(), cli.liveAgents())

        val (command, environment) = calls.single()
        assertEquals(listOf("spanreed", "list"), command)
        assertTrue(
            "SPANREED_AGENT_NAME" in environment,
            "the variable has to be named to be cleared; leaving it out inherits it instead",
        )
        assertEquals(
            null,
            environment["SPANREED_AGENT_NAME"],
            "null is removal — awakener's own process usually has one set, and asking the bus " +
                "who is live while wearing a name asks as somebody",
        )
    }

    @Test
    fun `an agent is animated when the bus lists its id`() = runTest {
        response = ProcessResult(
            0,
            """
            [
              {"agent_id": "agent-lifeless-a", "name": "lifeless-a", "pid": 7, "focus": "x"},
              {"agent_id": "agent-jason", "name": "jason", "working_dir": "/home/jmonk"}
            ]
            """.trimIndent(),
            "",
        )

        assertEquals(
            listOf("agent-lifeless-a", "agent-jason"),
            cli.liveAgents().map { it.agentId },
        )
        assertTrue(cli.isAnimated(AgentId("agent-lifeless-a")))
        assertTrue(!cli.isAnimated(AgentId("agent-lifeless-b")))
    }

    /**
     * A bus that cannot be read must not read as an empty bus: the next act on "nobody is
     * animated" is standing a second panel up beside one that is already there.
     */
    @Test
    fun `a failing list is raised rather than read as an empty bus`() = runTest {
        response = ProcessResult(2, "", "spanreed: registry is locked")
        assertTrue(
            assertFailsWith<IllegalStateException> { cli.liveAgents() }
                .message!!.contains("registry is locked"),
        )
    }

    /**
     * The two shapes a short read can take, and the reason they are worth their own tests: a
     * non-zero exit is not how truncation presents. `ProcessCommandRunner` gives up on the
     * drain after a grace period, so the child can exit 0 with its output still in flight — and
     * then `check(result.succeeded)` passes. The list this method returns decides whether a
     * hotkey press stands a second panel beside one already there, so anything short of the
     * whole document has to raise. See #51 for the runner-level defect underneath.
     */
    @Test
    fun `a silent list is raised rather than read as an empty bus`() = runTest {
        response = ProcessResult(0, "", "")
        assertTrue(
            assertFailsWith<IllegalStateException> { cli.liveAgents() }
                .message!!.contains("printed nothing"),
        )
    }

    @Test
    fun `a truncated list is raised rather than read as a shorter one`() = runTest {
        response = ProcessResult(
            0,
            """[{"agent_id": "agent-lifeless-a", "name": "lifeless-a"}, {"agent_id": "age""",
            "",
        )
        assertFailsWith<Exception> { cli.liveAgents() }
    }

    /**
     * The one test that touches the real thing. `agent-id` only prints a derivation — it does
     * not write to the registry — so this is safe to run against an installed spanreed, and it
     * is the only way to catch the derivation rule changing underneath us.
     */
    @Test
    fun `the real spanreed derives agent-name from SPANREED_AGENT_NAME`() = runTest {
        val spanreed = spanreedOrSkip()
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

    /**
     * Also against the real thing, and also read-only: `list` prints the registry and writes
     * nothing. What it catches is the half a recorded runner cannot — that spanreed's actual
     * output still decodes into [LiveAgent]. The fields asserted on are only the ones awakener
     * depends on, so spanreed adding to the shape stays a non-event, which is the point of
     * `ignoreUnknownKeys`.
     */
    @Test
    fun `the real spanreed list decodes into live agents`() = runTest {
        val spanreed = spanreedOrSkip()
        val real = SpanreedCli(
            InMemoryConfigStore().put(
                RegistryFlags.spanreedCommand,
                spanreed.absolutePathString(),
            ),
        )

        // Every entry `list` returns is one spanreed considers live, so the id must be there to
        // compare a binding against. Asserting on a count would assert on Jason's live bus.
        real.liveAgents().forEach {
            assertTrue(it.agentId.isNotBlank(), "an entry with no id cannot be matched to a binding")
        }
    }

    /**
     * The installed spanreed, or a skip the test report actually shows.
     *
     * The skip has to throw. A test method that returns normally is recorded as PASSED, so the
     * early `return` this replaced made a machine without spanreed report `skipped="0"` and a
     * full green suite — indistinguishable from a run that really did shell out. An assumption
     * failure is what Gradle writes into the XML as `<skipped/>`, which is what `CLAUDE.md`
     * claims the count means.
     *
     * `AWAKENER_REQUIRE_SPANREED=1` still comes first and still makes absence a failure; that
     * half of the guard was never the broken one.
     */
    private fun spanreedOrSkip(): Path {
        val spanreed = onPath("spanreed")
        if (spanreed == null) {
            check(System.getenv("AWAKENER_REQUIRE_SPANREED") != "1") {
                "AWAKENER_REQUIRE_SPANREED=1 but spanreed is not installed"
            }
            throw AssumptionViolatedException("spanreed is not installed")
        }
        return spanreed
    }

    /** Same predicate as `toolFingerprint` in the build scripts — see the note in `:wm` (#29). */
    private fun onPath(tool: String): Path? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { Path.of(it, tool) }
            .firstOrNull { it.isExecutable() }
}
