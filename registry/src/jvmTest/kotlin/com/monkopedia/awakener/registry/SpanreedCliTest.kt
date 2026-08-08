package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.InMemoryConfigStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isExecutable
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Blank is not a spanreed that might exist — it is an argv whose first element is the empty
     * string, which reaches `ProcessBuilder` only to come back as `could not run ''`. Declared
     * on the flag rather than coerced here, so `awakener-config list` prints the rule and a
     * hand-edited file that breaks it degrades and says so instead of failing at the hotkey.
     */
    @Test
    fun `a blank spanreed command degrades to the default and is reported`() {
        val snapshot = Config.of(
            mapOf(RegistryFlags.spanreedCommand.key to JsonPrimitive("")),
        )

        assertEquals("spanreed", snapshot[RegistryFlags.spanreedCommand])
        assertTrue(
            snapshot.problems.single { it.key == RegistryFlags.spanreedCommand.key }
                .reason.contains("blank"),
            "a value that degraded silently is the failure that costs most to diagnose: " +
                snapshot.problems,
        )
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
     * And it is refused for *being* short rather than for happening to be unparseable — which
     * is what stops the guard depending on the decoder's luck. For every document that can
     * arrive here the two coincide; a `list` that ever grew a line-oriented form would separate
     * them, and this is the assertion that survives it.
     */
    @Test
    fun `a list the runner says it read short is refused even when it parses`() = runTest {
        response = ProcessResult(0, "[]", "", shortRead = "the output was cut off")
        assertTrue(
            assertFailsWith<IllegalStateException> { cli.liveAgents() }
                .message!!.contains("cut off"),
        )
    }

    /**
     * The decoder's half of that pair, stated as narrowly as it is true.
     *
     * The comment above this method used to read "there is no prefix of a JSON array that
     * parses", and that is false: `[]` is a proper prefix of `[]\n` and both decode to the empty
     * list, as this pins. What holds — and what the guard actually rests on — is the narrow
     * form: no proper prefix of a **non-empty** array parses as an array, so a `list` cut off
     * mid-answer cannot come back as a shorter, plausible one. The wide claim was never needed,
     * and a reader extending it to a format where it fails would be extending something that
     * was never true (#110).
     */
    @Test
    fun `a truncated non-empty list cannot parse, which is the property the guard rests on`() =
        runTest {
            // The counterexample to the wide claim, and harmless: this document says the bus is
            // empty, and so does every longer one it is a prefix of.
            response = ProcessResult(0, "[]\n", "")
            assertEquals(emptyList(), cli.liveAgents())
            response = ProcessResult(0, "[]", "")
            assertEquals(emptyList(), cli.liveAgents())

            // The property the guard needs: every proper prefix of a non-empty answer is refused.
            val whole = """[{"agent_id": "agent-lifeless-a", "name": "lifeless-a"}]"""
            for (cut in whole.indices) {
                response = ProcessResult(0, whole.substring(0, cut), "")
                assertFailsWith<Exception>("a prefix of length $cut decoded into an answer") {
                    cli.liveAgents()
                }
            }
            response = ProcessResult(0, whole, "")
            assertEquals(1, cli.liveAgents().size, "and the whole answer still decodes")
        }

    // ------------------------------------------------------------ minting against a real child
    //
    // A recorded runner cannot reach the defect these cover: it is the *runner* that decides
    // whether what it read is all of it, so a fake one asserting on argv would be asserting on
    // the fake. These drive a real subprocess — a shell script standing in for spanreed, so no
    // throwaway agent lands on anybody's live bus — through the whole `mint` path.

    /**
     * **The one this whole change is about.**
     *
     * A truncated agent id is a *valid* agent id: it is a plausible string that addresses some
     * other surface's Lifeless, or nothing at all, and neither the caller nor the row it gets
     * written into can tell it from the real one afterwards. One window is then handed another
     * window's agent — and with it the accumulated model of the user that agent holds, which is
     * the failure the registry exists to prevent.
     *
     * The script here reproduces the shape exactly: a child that exits 0 leaving a background
     * job holding its stdout, so the second half of the id lands after the drain has given up.
     * Against unchanged `main` (`d0dd60a`) this minted, successfully and silently,
     * `AgentIdentity(id=AgentId(raw=agent-lifeless-fire), spanreedName=lifeless-window-firefox-…)`
     * from a spanreed that said `agent-lifeless-firefox`.
     *
     * The child exits *immediately*, unlike the one in `ProcessCommandRunnerTest`, and that is
     * the point of running it here as well: it is the timing under which the runner's own signal
     * sometimes misses, because the JVM drains and replaces a child's pipe the moment it exits.
     * What catches it every time is the framing — `agent-lifeless-fire` is not a whole line,
     * because a whole line ends in a newline and a prefix of one does not. So this refuses for
     * one of two reasons depending on which thread won, and both are correct; asserting on
     * either alone would be asserting on the race.
     */
    @Test
    fun `an id whose read came up short is refused rather than minted`() = runTest {
        val cli = againstScript("{ printf 'agent-lifeless-fire'; sleep 5; printf 'fox'; } &\nexit 0")

        val raised = assertFailsWith<IllegalStateException> { cli.mint(SurfaceKey.Window("firefox")) }
        assertTrue(
            raised.message!!.let { "cut off" in it || "prefix of an id" in it },
            "and refused for arriving incomplete, not for some other reason: ${raised.message}",
        )
    }

    /**
     * The framing check on its own, with no race to hide behind: a runner that swears the read
     * was whole, handing back an id with no line terminator. Nothing about the plumbing can
     * reject this, and nothing about the string can either — `agent-lifeless-fire` is a
     * perfectly good agent id, just not the one spanreed was in the middle of printing.
     */
    @Test
    fun `an id with no line terminator is refused even when the read looked whole`() = runTest {
        response = ProcessResult(0, "agent-lifeless-fire", "")

        assertTrue(
            assertFailsWith<IllegalStateException> { cli.mint(SurfaceKey.Window("firefox")) }
                .message!!.contains("prefix of an id"),
        )
    }

    /**
     * The control for the test above, through the same harness. Without it, an implementation
     * that simply refused to mint anything at all would pass — and that implementation is not
     * hypothetical, it is what a too-eager guard on this path looks like.
     */
    @Test
    fun `a whole read through the same path still mints exactly what spanreed said`() = runTest {
        val cli = againstScript("printf 'agent-from-a-real-child\\n'")

        assertEquals(AgentId("agent-from-a-real-child"), cli.mint(SurfaceKey.Window("firefox")).id)
    }

    // --------------------------------------------------------- #68: a spanreed that cannot run

    /**
     * The default, and the behaviour that predates the flag: an id spanreed did not issue is not
     * minted. It matters more here than for a transient failure because the binding is durable —
     * an id invented during an outage is one the surface keeps after it.
     */
    @Test
    fun `an unrunnable spanreed fails the mint by default`() = runTest {
        config.put(RegistryFlags.spanreedCommand, "awakener-no-such-spanreed")

        val raised = assertFailsWith<IllegalStateException> {
            SpanreedCli(config).mint(SurfaceKey.Window("firefox"))
        }
        assertTrue(
            RegistryFlags.spanreedCommand.key in raised.message.orEmpty(),
            "the refusal has to name the flag that chose the command: ${raised.message}",
        )
    }

    @Test
    fun `DERIVE mints spanreed's documented id locally and says that it did`() = runTest {
        config.put(RegistryFlags.spanreedCommand, "awakener-no-such-spanreed")
        config.put(RegistryFlags.idSourceUnreachable, UnreachableIdSource.DERIVE)
        val warnings = mutableListOf<String>()

        val identity = SpanreedCli(config, warn = { warnings += it })
            .mint(SurfaceKey.Window("firefox"))

        assertEquals(AgentId("agent-${identity.spanreedName}"), identity.id)
        // Silence here would be the quiet substitution #62 was about: the flag still reads
        // SPANREED, and the id did not come from spanreed.
        assertTrue(
            warnings.single().let {
                RegistryFlags.idSourceUnreachable.key in it && "awakener-no-such-spanreed" in it
            },
            "the substitution has to name itself and what could not be run: $warnings",
        )
    }

    /**
     * The scoping, which is the whole of why this is not simply "fall back on any failure".
     *
     * A spanreed that ran and exited non-zero is a bus that is *present* with something else
     * wrong, and a short read is a spanreed that answered where the answer is the thing in
     * doubt. Deriving past either would mint a plausible id over a live problem — the same
     * class of failure the truncation above is, arrived at deliberately.
     */
    @Test
    fun `DERIVE does not cover a spanreed that ran and failed, or one that read short`() =
        runTest {
            config.put(RegistryFlags.idSourceUnreachable, UnreachableIdSource.DERIVE)

            response = ProcessResult(1, "", "spanreed: registry is locked")
            assertTrue(
                assertFailsWith<IllegalStateException> { cli.mint(SurfaceKey.Window("firefox")) }
                    .message!!.contains("registry is locked"),
            )

            response = ProcessResult(0, "agent-lifeless-fire", "", shortRead = "the output was cut off")
            assertTrue(
                assertFailsWith<IllegalStateException> { cli.mint(SurfaceKey.Window("firefox")) }
                    .message!!.contains("cut off"),
            )
        }

    /** A `SpanreedCli` driving [script] as its spanreed, through the real subprocess runner. */
    private fun againstScript(script: String): SpanreedCli {
        val path = Files.createTempDirectory("awakener-fake-spanreed").resolve("spanreed")
        path.writeText("#!/bin/sh\n$script\n")
        assertTrue(path.toFile().setExecutable(true))
        return SpanreedCli(
            InMemoryConfigStore().put(RegistryFlags.spanreedCommand, path.absolutePathString()),
        )
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
