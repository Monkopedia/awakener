package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentBus
import com.monkopedia.awakener.registry.AgentIdSource
import com.monkopedia.awakener.registry.AgentIdentities
import com.monkopedia.awakener.registry.CommandRunner
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import com.monkopedia.awakener.registry.LiveAgent
import com.monkopedia.awakener.registry.ProcessCommandRunner
import com.monkopedia.awakener.registry.ProcessResult
import com.monkopedia.awakener.registry.RegistryFlags
import com.monkopedia.awakener.registry.SpanreedCli
import com.monkopedia.awakener.registry.SpanreedHarness
import com.monkopedia.awakener.registry.UnreachableIdSource
import com.monkopedia.awakener.wm.CommandResult
import com.monkopedia.awakener.wm.I3Ipc
import com.monkopedia.awakener.wm.Node
import com.monkopedia.awakener.wm.RestingFocus
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.SwayHarness
import com.monkopedia.awakener.wm.SwayWindowManager
import com.monkopedia.awakener.wm.WmFlags
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json

/**
 * The invoke loop against a real headless sway.
 *
 * This is the half `AwakeningTest` cannot reach. The tree shape a dock lands in, the fact that a
 * panel really comes up, and — the one that makes this a product loop rather than a terminal in a
 * box — that the process sway `exec`s receives the exact `SPANREED_AGENT_NAME` the registry
 * minted, are all facts about a compositor and a process, and a fake of either would be a test of
 * the fake.
 *
 * The dock program here is not `claude`. It is a shell script that records the identity it was
 * launched under and then sits there like a panel, which is what makes the binding *assertable*:
 * a real agent proves the same thing only by being asked, and costs a session per run. The live
 * `claude` demonstration belongs in the PR, once, not in a suite that runs on every build.
 *
 * The bus is faked here for one reason only: `spanreed list` reads Jason's real registry, and a
 * test suite must not stand throwaway agents up on it. What awakener does with the *answer* is
 * what this asserts; that the answer decodes is `SpanreedCliTest`'s, against the real thing.
 *
 * The **mint** is a separate question from the bus, and one test below does not fake it:
 * `the panel comes up under an identity the real spanreed issued`. That one is what
 * `AWAKENER_REQUIRE_SPANREED` protects in this module, and before it existed the flag was
 * forwarded here with nothing on the test path consulting spanreed at all (#100). It asserts on
 * the subprocess itself rather than on the id that came back, because the id is by design one a
 * local rule reproduces — which is how that test spent its first life gating a spanreed
 * *installation* while a mint that never forked one would have passed it (#139).
 */
class AwakeningSwayTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var registry: FileBindingStore
    private lateinit var stateDir: Path
    private lateinit var reportFile: Path
    private lateinit var awakening: Awakening
    private val bus = FakeBus()
    private var minted = 0

    /**
     * Who mints, indirected through a `var` so one test can swap the real [SpanreedCli] in without
     * every other test paying for a subprocess. The store reads this on every bind, so the swap
     * has to happen before the invocation and not before the store is built.
     */
    private lateinit var identities: AgentIdentities

    private val enabled get() = SwayHarness.available()

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        Flags.requireLoaded(InvokeFlags, RegistryFlags, WmFlags)
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-invoke")
        reportFile = stateDir.resolve("reported-names")

        // A panel that says who it is. `env SPANREED_AGENT_NAME=…` is exactly the shape of the
        // production default, so what is being exercised is the real mechanism; only what runs
        // after `env` differs, and it differs so the identity can be read back rather than
        // conversed with.
        val script = stateDir.resolve("dock.sh")
        script.writeText(
            """
            #!/bin/sh
            printf '%s\n' "${'$'}SPANREED_AGENT_NAME" >> "${'$'}1"
            exec sleep 3600
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)

        store.put(InvokeFlags.dockAppId, DOCK_APP_ID)
        store.put(
            InvokeFlags.dockCommand,
            "foot -a {app_id} -- env SPANREED_AGENT_NAME={agent_name} " +
                "${script.absolutePathString()} ${reportFile.absolutePathString()}",
        )
        // Only this suite's own `reap` may sweep. The collector sweeps on every window close by
        // default, which would take the orphan down before the test that is about `reap` gets to
        // ask for it — and a test whose subject has already happened proves nothing.
        store.put(WmFlags.sweepOnClose, false)

        // Counted, so "did this invocation mint" is observable rather than inferred; and derived
        // rather than real, because a mint through the spanreed CLI is a subprocess and this suite
        // already spends a compositor. The one test that wants the subprocess replaces this.
        identities = AgentIdentities { key, residuePath ->
            minted++
            DerivedAgentIdentities(store).mint(key, residuePath)
        }
        registry = FileBindingStore(
            configStore = store,
            identities = { key, residuePath -> identities.mint(key, residuePath) },
            path = stateDir.resolve("bindings.json"),
        )
        wm = SwayWindowManager({ sway.connection() }, store, registry, scope)
        awakening = awakeningOver(wm)
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    /**
     * Proof 1: a window is enumerated, resolved as a Drab, and the invocation stands the panel as
     * a tree sibling *inside* its tab — the shape the whole dock design rests on, and the one
     * layer-shell cannot produce.
     */
    @Test
    fun `invoking a Drab stands its panel inside the same tab`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        command("[con_id=${app1.raw}] focus")

        assertNull(
            awakening.list().single { it.surface.id == app1 }.agent,
            "it has to be a Drab before the hotkey, or the invocation proves nothing",
        )

        val awakened = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(app1, awakened.surface)
        assertTrue(awakened.minted)

        val workspace = assertNotNull(wm.tree().workspace("1"))
        assertEquals(
            2,
            workspace.children.size,
            "the tabbed workspace still holds exactly two tabs; a panel that had become a " +
                "sibling of the tab would be a third",
        )
        val tab = assertNotNull(workspace.children.firstOrNull { it.find(app1.raw) != null })
        assertEquals("splith", tab.layout, "the tab's contents are a split container")
        assertEquals(
            setOf(app1.raw, awakened.dock.dockId.raw),
            tab.children.map { it.id }.toSet(),
            "surface and panel share the one tab",
        )
        assertNotNull(
            workspace.children.firstOrNull { it.id == app2.raw },
            "and the other tab is untouched",
        )
    }

    /**
     * The single fact that makes this a binding rather than a terminal in a box: the process sway
     * `exec`s comes up **as this surface's agent**.
     *
     * Read back from the panel's own environment rather than from awakener's intent, because the
     * intent is what a unit test can already check. spanreed derives an identity from the
     * session's working directory and a surface has no working directory, so this variable is the
     * entire mechanism — a command that drops it animates a stranger, and everything downstream
     * still looks correct.
     */
    @Test
    fun `the panel comes up under the exact name the registry minted`() = swayTest {
        openSurface("aw-app1")
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())

        assertEquals(
            listOf(awakened.binding.spanreedName),
            awaitReportedNames(1),
            "the panel's own SPANREED_AGENT_NAME, read out of the process sway started",
        )
        assertEquals(
            "agent-${awakened.binding.spanreedName}",
            awakened.binding.agentId,
            "and it is the name that address is derived from, so the bus can route to it",
        )
    }

    /**
     * The same fact as the test above, with the mint no longer faked: the panel comes up under an
     * identity **spanreed itself issued**, reached through the flag defaults a machine with no
     * config file has.
     *
     * ### Why it needs the real spanreed, stated so it cannot quietly stop needing it
     *
     * `registry.agent.id_source` defaults to `SPANREED` and `registry.agent.id_source_unreachable`
     * to `FAIL`, so a mint that returns at all has run `spanreed agent-id` as a subprocess:
     * refusing rather than deriving past an unreachable bus is what those two defaults mean
     * together. The assertions on the snapshot below are not decoration — flip either default and
     * this test would keep passing while touching no spanreed at all, which is exactly how the
     * gate it belongs to came to protect nothing (#100).
     *
     * ### And why it is the only test of `registry.agent.spanreed_command`'s default
     *
     * Every other spanreed test in the build injects an absolute path or a scratch script, so the
     * shipped default — the bare name `spanreed`, left for `ProcessBuilder` to resolve on PATH —
     * is exercised nowhere else. That default is what an unconfigured `awakener-invoke` runs with,
     * so a typo in it would reach a user's first hotkey press before it reached a build.
     *
     * The **bus** is still faked, and the split is deliberate: liveness is `spanreed list`, a read
     * of Jason's live registry that this suite's own note keeps out, while the mint is `agent-id`,
     * which prints a derivation and writes nothing.
     *
     * ### Why none of that can be asserted through the id (#139)
     *
     * The obvious proof — assert the id — cannot work here, and the reason is a property worth
     * keeping rather than a defect: `SpanreedCli.derivedId` and [DerivedAgentIdentities] apply
     * spanreed's `SPANREED_AGENT_NAME` override rule from one shared definition **so the two
     * cannot drift apart**, and spanreed applies the same rule literally — measured on kaladin,
     * `SPANREED_AGENT_NAME=Aw_Test-UPPER.dot spanreed agent-id` prints exactly
     * `agent-Aw_Test-UPPER.dot`, with no normalisation to distinguish it by. So **every id a real
     * spanreed can return is reconstructible locally**, and an assertion on the value is satisfied
     * by the fallback it exists to rule out. This test's three "spanreed ran" assertions were each
     * that shape: `"agent-" + binding.spanreedName` is `derivedId`'s rule verbatim,
     * `binding.spanreedName` is `spanreedNameFor` computed in-process, and `minted == 0` was true
     * by construction — [identities] is reassigned below, so the counting lambda installed in
     * `setUp` is no longer on the mint path at all. Rewriting `AgentIdSource.SPANREED ->
     * spanreedId(cfg, name)` to `-> derivedId(name)` left the whole test green with no subprocess
     * forked, which is #100's gate holding *installation* and still not *consultation*.
     *
     * What replaces them is an assertion on the **act**, since the answer cannot carry it: the
     * production [ProcessCommandRunner] is wrapped in a [RecordingRunner], so the argv, the
     * environment override and the child's own bytes are all readable afterwards. A record of a
     * run is the one artefact the local rule produces none of, and the id is then pinned to
     * `stdout` **including its terminator** — the byte `derivedId` has no way to invent.
     *
     * Wrapping rather than pointing [RegistryFlags.spanreedCommand] at a recording script is the
     * whole point: a script would move that flag off its shipped default, and being the only test
     * that runs the mint *under* the default is the reason this test is worth gating. The
     * delegate is `ProcessCommandRunner(store)`, which is exactly what [SpanreedCli]'s own default
     * argument builds — so what runs is unchanged and only the record of it is new.
     */
    @Test
    fun `the panel comes up under an identity the real spanreed issued`() = swayTest {
        val spanreed = SpanreedHarness.assumeAvailable()
        val cfg = store.config.value
        assertEquals(
            AgentIdSource.SPANREED,
            cfg[RegistryFlags.agentIdSource],
            "only under this default does the mint shell out; under DERIVED it needs no spanreed",
        )
        assertEquals(
            UnreachableIdSource.FAIL,
            cfg[RegistryFlags.idSourceUnreachable],
            "and only under this one does an unreachable spanreed refuse instead of deriving past",
        )
        assertEquals(
            SpanreedHarness.COMMAND,
            cfg[RegistryFlags.spanreedCommand],
            "the shipped default, resolved on PATH — the gate found it at $spanreed",
        )

        val runs = RecordingRunner(ProcessCommandRunner(store))
        identities = SpanreedCli(store, runner = runs)
        openSurface("aw-app1")
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())

        assertEquals(
            listOf(awakened.binding.spanreedName),
            awaitReportedNames(1),
            "the panel's own SPANREED_AGENT_NAME, read out of the process sway started",
        )

        // Everything above here is satisfied by a mint that never left the JVM. Everything below
        // is about a subprocess that ran.
        val call = assertNotNull(
            runs.calls.singleOrNull(),
            "the mint has to have forked exactly one child; it ran ${runs.calls.size} — " +
                "${runs.calls.map { it.command }}",
        )
        assertEquals(
            listOf(SpanreedHarness.COMMAND, "agent-id"),
            call.command,
            "the bare name the shipped default holds, handed to ProcessBuilder unresolved — " +
                "this test injected no path, which is what makes it the one that says an " +
                "unconfigured awakener-invoke can reach the bus",
        )
        assertEquals(
            awakened.binding.spanreedName,
            call.environment["SPANREED_AGENT_NAME"],
            "asked as this surface's agent; a surface has no cwd, so without the override " +
                "spanreed answers about wherever awakener was started from",
        )
        assertEquals(0, call.result.exitCode, "a zero exit from the spanreed at $spanreed")
        assertNull(
            call.result.shortRead,
            "and the whole of what it wrote — a prefix of an agent id is itself a valid agent id",
        )
        assertEquals(
            "${awakened.binding.agentId}\n",
            call.result.stdout,
            "the Lifeless is bound under exactly the bytes that child printed. The terminator is " +
                "the part that cannot be reconstructed: derivedId returns 'agent-<name>' and " +
                "nothing else in the process ever appends a newline to it",
        )
    }

    /**
     * Proof 4. Under the default `registry.key.window_identity=APP_ID` the durable unit is the
     * application, so a second window of it is the same surface — which is what makes the agent
     * survive the window it was first bound against.
     */
    @Test
    fun `a second window of one app recalls its agent without minting`() = swayTest {
        openSurface("aw-app1")
        val first = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(1, minted)

        // A genuinely different window — its own con_id, its own pid — reporting the same app_id.
        val second = openSurface("aw-app1")
        command("[con_id=${second.raw}] focus")
        val again = assertIs<Awakened.Animated>(awakening.invoke())

        assertEquals(second, again.surface, "a different window")
        assertEquals(first.binding.agentId, again.binding.agentId, "and the same Lifeless")
        assertTrue(!again.minted)
        assertEquals(1, minted, "nothing was minted a second time")
        assertEquals(
            listOf(first.binding.spanreedName, first.binding.spanreedName),
            awaitReportedNames(2),
            "both panels came up as the same agent",
        )
    }

    /** Proof: an animated Lifeless gets no second panel, asserted against the tree. */
    @Test
    fun `an animated Lifeless is not given a second panel`() = swayTest {
        openSurface("aw-app1")
        val first = assertIs<Awakened.Animated>(awakening.invoke())
        bus.live = listOf(LiveAgent(first.binding.agentId, first.binding.spanreedName))

        val second = assertIs<Awakened.AlreadyAnimated>(awakening.invoke())
        assertEquals(first.binding.agentId, second.binding.agentId, "still recalled, though")
        assertEquals(
            listOf(first.dock.dockId.raw),
            docks(),
            "exactly the one panel; the tree is what says so, not the return value",
        )

        // And the lever that says otherwise really does say otherwise.
        store.put(InvokeFlags.whenAnimated, WhenAnimated.SECOND_DOCK)
        val third = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(first.binding.agentId, third.binding.agentId, "one Lifeless, two views")
        assertEquals(2, docks().size)
    }

    /** Proof 5, the tidy half: teardown leaves no `splith` behind to adopt the next window. */
    @Test
    fun `detaching takes the panel down and flattens the container`() = swayTest {
        val app1 = openSurface("aw-app1")
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals("splith", assertNotNull(tabHolding(app1)).layout)

        awakened.dock.detach()

        assertEquals(emptyList(), docks(), "the panel is gone")
        val tab = assertNotNull(tabHolding(app1))
        assertEquals(
            app1.raw,
            tab.id,
            "and so is the split container: a leftover would silently adopt the next window " +
                "opened in this tab",
        )
    }

    /** Proof 5, the hazard half: a panel whose surface died is taken down by the sweep. */
    @Test
    fun `reap takes down a panel whose surface has gone`() = swayTest {
        val app1 = openSurface("aw-app1")
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())

        command("[con_id=${app1.raw}] kill")
        awaitGone(app1)
        assertEquals(
            listOf(awakened.dock.dockId.raw),
            docks(),
            "the orphan really is standing — sway leaves it, which is the whole hazard",
        )

        awakening.reap()

        assertEquals(emptyList(), docks(), "and the sweep took it down")
    }

    /**
     * #197: the next press anywhere takes down a panel whose surface has gone.
     *
     * The collector that sweeps on a close only lives as long as one press, so on the shipped
     * desktop nothing is listening when the user closes a window and its panel stays standing.
     * The press is the one moment awakener runs; [freshAwakening] is what makes it the shipped
     * shape — a new process, with no dock table and no collector that saw the close.
     */
    @Test
    fun `a press takes down a panel whose surface has gone`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        command("[con_id=${app1.raw}] focus")
        val first = assertIs<Awakened.Animated>(awakening.invoke())

        command("[con_id=${app1.raw}] kill")
        awaitGone(app1)
        assertEquals(listOf(first.dock.dockId.raw), docks(), "the orphan really is standing")

        command("[con_id=${app2.raw}] focus")
        val second = assertIs<Awakened.Animated>(freshAwakening().invoke())

        assertEquals(app2, second.surface, "the press still acts on the window it was made in")
        assertEquals(
            listOf(second.dock.dockId.raw),
            docks(),
            "and the orphan went first, so the only panel left is the one this press stood up",
        )
    }

    /** Off is the previous behaviour: a press leaves the orphan for `reap`. */
    @Test
    fun `sweeping on every press is switchable off`() = swayTest {
        store.put(InvokeFlags.sweepFirst, false)
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        command("[con_id=${app1.raw}] focus")
        val first = assertIs<Awakened.Animated>(awakening.invoke())
        command("[con_id=${app1.raw}] kill")
        awaitGone(app1)

        command("[con_id=${app2.raw}] focus")
        val second = assertIs<Awakened.Animated>(freshAwakening().invoke())

        assertEquals(
            listOf(first.dock.dockId.raw, second.dock.dockId.raw).sorted(),
            docks(),
            "with the flag off the orphan stands beside the new panel",
        )
    }

    /**
     * **The product loop for Jason's toggle, end to end**: press, press, press.
     *
     * The assertion that carries it is that the *same process* is behind the panel all three
     * times. `awaitReportedNames` counts one line per panel start, so a second line would mean
     * the hotkey had re-launched the agent — the outcome the whole feature exists to avoid, and
     * one that a tree assertion alone cannot tell from a genuine restore: a fresh panel is in the
     * tab in exactly the same place a restored one is.
     *
     * The pid is checked too, and it is the sharper of the two: a dock that had been killed and
     * re-`exec`'d would be a different process even if it somehow wrote no second line.
     */
    @Test
    fun `the hotkey toggles the panel, and the agent behind it survives both presses`() = swayTest {
        store.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        val app1 = openSurface("aw-app1")

        val stood = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(listOf(stood.binding.spanreedName), awaitReportedNames(1))
        val pid = assertNotNull(wm.tree().find(stood.dock.dockId.raw)?.pid)

        val hidden = assertIs<Awakened.Hidden>(awakening.invoke())
        assertEquals(stood.dock.dockId, hidden.dock.dockId)
        assertEquals(
            emptyList(),
            assertNotNull(tabHolding(app1)).windows.map { it.id }.filter { it != app1.raw },
            "the panel is out of the tab entirely; the surface is alone in it",
        )
        assertTrue(
            ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false),
            "and the agent is still running behind it, which is the entire point of hiding " +
                "rather than shutting the panel down",
        )

        val restored = assertIs<Awakened.Restored>(awakening.invoke())
        assertEquals(stood.dock.dockId, restored.dock.dockId, "the same node came back")
        assertEquals(
            listOf(stood.dock.dockId.raw),
            assertNotNull(tabHolding(app1)).windows.map { it.id }.filter { it != app1.raw },
            "back in the surface's own tab",
        )
        assertEquals(
            pid,
            wm.tree().find(stood.dock.dockId.raw)?.pid,
            "and behind the same process as before — a panel that had been re-launched would " +
                "be a different one",
        )
        assertEquals(
            listOf(stood.binding.spanreedName),
            awaitReportedNames(1),
            "the panel reported its identity exactly once across three presses; a second line " +
                "would mean the hotkey had stood a fresh agent up",
        )
        assertEquals(1, minted, "and nothing was minted for the two toggle presses")
    }

    /**
     * A hidden panel whose surface dies must not be left running where nobody can see it.
     *
     * The invisible case is strictly worse than the orphan `reap` already exists for: an orphaned
     * panel is at least on screen. sway's scratchpad is an ordinary workspace, so the sweep
     * reaches it — proved here through the same `reap` a user would run.
     */
    @Test
    fun `reap takes down a hidden panel whose surface has gone`() = swayTest {
        store.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        val app1 = openSurface("aw-app1")
        val stood = assertIs<Awakened.Animated>(awakening.invoke())
        assertIs<Awakened.Hidden>(awakening.invoke())

        command("[con_id=${app1.raw}] kill")
        awaitGone(app1)
        assertEquals(
            listOf(stood.dock.dockId.raw),
            docks(),
            "the hidden orphan really is standing — sway does not take a scratchpad window " +
                "down with the surface it was docked beside",
        )

        awakening.reap()

        assertEquals(emptyList(), docks(), "and the sweep reached into the scratchpad for it")
    }

    /**
     * Enumeration must never hand the agent panel back as a bindable window: a hotkey on it would
     * mint a Lifeless for a Lifeless, and write that to the durable registry.
     */
    @Test
    fun `the panel is never enumerated as a surface of its own`() = swayTest {
        val app1 = openSurface("aw-app1")
        val before = awakening.list()
        assertEquals(listOf(app1), before.map { it.surface.id })
        assertNull(before.single().agent, "a Drab")

        val awakened = assertIs<Awakened.Animated>(awakening.invoke())

        val after = awakening.list()
        assertEquals(
            listOf(app1),
            after.map { it.surface.id },
            "still one surface; the panel is a real tree node and must not be one of them",
        )
        assertEquals(awakened.binding.agent, after.single().agent, "now a Lifeless")
        assertTrue(!after.single().animated, "the fake bus has nothing on it")

        bus.live = listOf(LiveAgent(awakened.binding.agentId, awakened.binding.spanreedName))
        assertTrue(awakening.list().single().animated, "and the bus is what decides that")
    }

    /**
     * #170, the dock case: a panel this process does not recognise is still awakener's own, and
     * a press on it must write nothing durable.
     *
     * The window is opened by hand under the dock's `app_id`, with no mark and no table entry,
     * because that is exactly what *every* other process's panel is to a one-shot
     * `awakener-invoke`: its table starts empty, so it knows a dock only by the mark, and a dock
     * whose mark has not landed yet, or one #199's race left unclaimed, has none. Recognition by
     * mark and table cannot cover that window. Its `app_id` is the one fact present from the
     * moment it maps, and the configured `invoke.dock.app_id` is what predicts it.
     */
    @Test
    fun `a press on a panel nothing has claimed mints nothing`() = swayTest {
        openSurface("aw-app1")
        val stray = openSurface(DOCK_APP_ID)
        command("[con_id=${stray.raw}] focus")

        assertIs<Awakened.NoSurface>(awakening.invoke())
        assertIs<Awakened.NoSurface>(awakening.invoke(stray), "nor when it is named by id")

        assertNothingDurableFor(DOCK_APP_ID)
        assertEquals(listOf(stray.raw), docks(), "and no panel was stood up beside the panel")
    }

    /**
     * The same refusal under `wm.dock.identity=PER_SURFACE_APP_ID`, where each panel reports the
     * configured `app_id` with its surface's `con_id` after it. Refused under either identity
     * rather than only the current one, since a flip leaves panels standing that were named the
     * other way.
     */
    @Test
    fun `a per-surface panel name is refused too`() = swayTest {
        val app1 = openSurface("aw-app1")
        val stray = openSurface("$DOCK_APP_ID-${app1.raw}")
        command("[con_id=${stray.raw}] focus")

        assertIs<Awakened.NoSurface>(awakening.invoke())
        assertNothingDurableFor(DOCK_APP_ID)
    }

    /**
     * #170, the list case, which shares no mechanism with the dock case: the `Super+o` window is a
     * plain `foot -a awakener-list`, never a dock, with no mark and no entry in any table. Only
     * `invoke.furniture.app_ids` makes it something other than an ordinary window.
     */
    @Test
    fun `a press on the list window mints nothing`() = swayTest {
        openSurface("aw-app1")
        val list = openSurface("awakener-list")
        command("[con_id=${list.raw}] focus")

        assertIs<Awakened.NoSurface>(awakening.invoke())
        assertNothingDurableFor("awakener-list")
        assertEquals(
            listOf("aw-app1"),
            awakening.list().map { it.surface.appId },
            "and the listing offers neither piece of furniture as a Drab",
        )

        // The set is a flag, so a window awakener does not ship can be added to it, and taking
        // the default out of it makes the list window an ordinary surface again.
        store.put(InvokeFlags.furnitureAppIds, "")
        assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(1, minted)
    }

    /**
     * #196: with focus resting in the panel, the toggle hides the panel it is pressed from.
     *
     * `wm.focus.resting=DOCK` is the combination that made this unreachable: the press that
     * stands a panel up leaves focus in it, so every later press came from a window enumeration
     * leaves out, and was refused. Each press after the first is made by a **fresh**
     * `SwayWindowManager`, because that is what a hotkey is — a new process with an empty dock
     * table, which knows the panel only by the mark on it.
     */
    @Test
    fun `a press from inside the panel hides it`() = swayTest {
        store.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        store.put(WmFlags.restingFocus, RestingFocus.DOCK)
        val app1 = openSurface("aw-app1")

        val stood = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(stood.dock.dockId.raw, focusedWindow(), "focus rests in the panel")

        val hidden = assertIs<Awakened.Hidden>(freshAwakening().invoke())
        assertEquals(app1, hidden.surface, "the press was taken as the panel's own surface's")
        assertEquals(stood.dock.dockId, hidden.dock.dockId)
        assertEquals(
            emptyList(),
            assertNotNull(tabHolding(app1)).windows.map { it.id }.filter { it != app1.raw },
            "and the panel is out of the tab",
        )

        assertIs<Awakened.Restored>(freshAwakening().invoke())
        assertEquals(stood.dock.dockId.raw, focusedWindow(), "the restore rests in the panel too")
        assertIs<Awakened.Hidden>(freshAwakening().invoke(), "so the toggle keeps toggling")
        assertEquals(1, minted, "and no press after the first minted anything")
    }

    /**
     * #220: with two panels beside one surface, a press from a panel acts on **that** panel.
     *
     * The shape `SECOND_DOCK` leaves, and `invoke.when_animated` is a flag flipped on a live
     * system. Panel 1 is hidden first, so the scratchpad — which the tree lists ahead of every
     * workspace — holds it, and re-deriving "the" dock from the surface finds panel 1. The press
     * comes from panel 2 and must hide panel 2; restoring panel 1 instead is the defect.
     */
    @Test
    fun `a press from the second of two panels acts on that panel`() = swayTest {
        store.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        val app1 = openSurface("aw-app1")
        val first = assertIs<Awakened.Animated>(awakening.invoke(app1))
        assertIs<Awakened.Hidden>(freshAwakening().invoke(app1))

        store.put(InvokeFlags.whenAnimated, WhenAnimated.SECOND_DOCK)
        val second = assertIs<Awakened.Animated>(freshAwakening().invoke(app1))
        command("[con_id=${second.dock.dockId.raw}] focus")

        store.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        val hidden = assertIs<Awakened.Hidden>(freshAwakening().invoke())
        assertEquals(second.dock.dockId, hidden.dock.dockId, "the pressed panel is the one hidden")
        assertEquals(app1, hidden.surface)
        assertEquals(
            listOf(app1.raw),
            assertNotNull(tabHolding(app1)).windows.map { it.id },
            "and neither panel is in the tab: ${first.dock.dockId.raw} stayed hidden",
        )
    }

    /**
     * #197 against #220: a press from a live panel still acts on that panel when the same press
     * sweeps an orphan in another tab.
     *
     * Composition, not ordering. Sweeping this orphan does not move focus off the pressed panel,
     * so this passes with the sweep moved above the aim as well — measured. The ordering is held
     * by `a press from a panel whose surface has gone is refused`, where the swept panel *is* the
     * focused one and a press aimed after the sweep acts on the wrong window.
     */
    @Test
    fun `a press from a panel acts on that panel while its sweep takes an orphan down`() =
        swayTest {
            store.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
            // Off while the orphan is made, so the press that stands the live panel up does not
            // sweep it before the press under test can.
            store.put(InvokeFlags.sweepFirst, false)
            val app1 = openSurface("aw-app1")
            val app2 = openSurface("aw-app2")
            val orphan = assertIs<Awakened.Animated>(awakening.invoke(app2))
            val live = assertIs<Awakened.Animated>(freshAwakening().invoke(app1))
            command("[con_id=${app2.raw}] kill")
            awaitGone(app2)
            command("[con_id=${live.dock.dockId.raw}] focus")
            assertEquals(
                listOf(orphan.dock.dockId.raw, live.dock.dockId.raw).sorted(),
                docks(),
                "both panels stand before the press",
            )

            store.put(InvokeFlags.sweepFirst, true)
            val hidden = assertIs<Awakened.Hidden>(freshAwakening().invoke())

            assertEquals(live.dock.dockId, hidden.dock.dockId, "the pressed panel is hidden")
            assertEquals(app1, hidden.surface)
            assertEquals(listOf(live.dock.dockId.raw), docks(), "and the orphan was swept")
        }

    /**
     * `invoke.from_panel=REFUSE` is the behaviour before #196, kept reachable.
     */
    @Test
    fun `a press from inside the panel is refused under REFUSE`() = swayTest {
        store.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        store.put(WmFlags.restingFocus, RestingFocus.DOCK)
        store.put(InvokeFlags.fromPanel, FromPanel.REFUSE)
        openSurface("aw-app1")
        val stood = assertIs<Awakened.Animated>(awakening.invoke())

        assertIs<Awakened.NoSurface>(freshAwakening().invoke())
        assertEquals(stood.dock.dockId.raw, focusedWindow(), "and the panel is where it was")
    }

    /**
     * A panel with no mark — the shape #199's race leaves, and what any panel is in the moment
     * before its mark lands — names no surface. With a real, marked panel standing beside a
     * surface too, a press from the unmarked one must neither guess its way to that surface nor
     * bind the panel itself.
     */
    @Test
    fun `a press from a panel nothing has claimed is still refused`() = swayTest {
        store.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        val app1 = openSurface("aw-app1")
        val stood = assertIs<Awakened.Animated>(awakening.invoke())
        minted = 0
        val stray = openSurface("$DOCK_APP_ID-${app1.raw}")
        command("[con_id=${stray.raw}] focus")

        assertIs<Awakened.NoSurface>(freshAwakening().invoke())
        assertNothingDurableFor("$DOCK_APP_ID-${app1.raw}")
        assertNotNull(
            assertNotNull(tabHolding(app1)).find(stood.dock.dockId.raw),
            "and the marked panel was not hidden on the stray's behalf",
        )
    }

    /**
     * A marked panel whose surface has gone names a surface this press cannot act on.
     *
     * It is also the likeliest press of all to be made from an orphan, since the panel fills the
     * tab. Under `invoke.sweep_first` the press takes it down (#197) — and must still be refused:
     * the sweep moves focus to whatever sway picks next, and a press aimed after it would stand a
     * panel up on a window the user never pressed in.
     */
    @Test
    fun `a press from a panel whose surface has gone is refused`() = swayTest {
        val app1 = openSurface("aw-app1")
        openSurface("aw-app2")
        val stood = assertIs<Awakened.Animated>(awakening.invoke(app1))
        command("[con_id=${app1.raw}] kill")
        awaitGone(app1)
        command("[con_id=${stood.dock.dockId.raw}] focus")

        assertIs<Awakened.NoSurface>(freshAwakening().invoke())
        assertEquals(1, minted, "nothing beyond the first panel's own mint")
        assertEquals(emptyList(), docks(), "the orphan is gone, and no panel replaced it")
    }

    // -- helpers ------------------------------------------------------------------------

    /**
     * No binding, no mint and no residue for [appId], read back from the files themselves rather
     * than from the store's view of them: the defect was a durable write, so the disk is what has
     * to say it did not happen.
     */
    private fun assertNothingDurableFor(appId: String) {
        assertEquals(0, minted, "nothing was minted")
        val file = stateDir.resolve("bindings.json").toFile()
        assertTrue(
            !file.exists() || appId !in file.readText(),
            "bindings.json names $appId: ${file.takeIf { it.exists() }?.readText()}",
        )
        val residue = stateDir.resolve("residue").toFile().walk().map { it.name }.toList()
        assertTrue(residue.none { appId in it }, "residue was prepared for $appId: $residue")
    }

    private fun awakeningOver(wm: SwayWindowManager) = Awakening(
        wm = wm,
        registry = registry,
        bus = bus,
        store = store,
        prepareResidue = { registry.prepareResidue(it) },
        reapOrphans = wm::reapOrphans,
        dockFor = wm::dockFor,
        focusedDock = wm::focusedDock,
    )

    /** What the next hotkey press is: a new process, holding no dock table. */
    private fun freshAwakening() =
        awakeningOver(SwayWindowManager({ sway.connection() }, store, registry, scope))

    private suspend fun focusedWindow(): Long? = wm.tree().windows.firstOrNull { it.focused }?.id

    private fun swayTest(body: suspend () -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking {
            command("workspace 1; layout tabbed")
            body()
        }
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        json.decodeFromString<List<CommandResult>>(raw).firstOrNull { !it.success }
            ?.let { error("sway rejected '$text': ${it.error}") }
    }

    private suspend fun openSurface(appId: String): SurfaceId {
        command("exec ${sway.windowCommand(appId)}")
        return SurfaceId(assertNotNull(awaitWindow(appId), "window '$appId' never appeared"))
    }

    /** Every panel standing right now, by node id. */
    private suspend fun docks(): List<Long> =
        wm.tree().windows.filter { it.appId == DOCK_APP_ID }.map { it.id }.sorted()

    private suspend fun tabHolding(surface: SurfaceId): Node? =
        wm.tree().workspace("1")?.children?.firstOrNull { it.find(surface.raw) != null }

    /**
     * The names the panels reported, once [count] of them have.
     *
     * Polled rather than assumed present: `attach` returns when the *window* has mapped, and the
     * process behind it writes this line whenever the kernel gets round to it. Asserting straight
     * after the attach would be a race that passes on a quiet machine.
     */
    private suspend fun awaitReportedNames(count: Int): List<String> {
        val names = withTimeoutOrNull(WAIT_MS) {
            while (true) {
                val lines = runCatching { reportFile.readText() }.getOrDefault("")
                    .lines().filter { it.isNotBlank() }
                if (lines.size >= count) return@withTimeoutOrNull lines
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList<String>()
        }
        return assertNotNull(names, "only ${count - 1} or fewer panels ever reported a name")
    }

    private suspend fun awaitWindow(appId: String): Long? = withTimeoutOrNull(WAIT_MS) {
        while (true) {
            wm.tree().windows.firstOrNull { it.appId == appId }
                ?.let { return@withTimeoutOrNull it.id }
            yield()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private suspend fun awaitGone(id: SurfaceId) {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().find(id.raw) != null) yield()
                true
            },
            "window ${id.raw} never left the tree",
        )
    }

    private class FakeBus : AgentBus {
        var live: List<LiveAgent> = emptyList()
        override suspend fun liveAgents(): List<LiveAgent> = live
    }

    /**
     * Every subprocess a mint ran, recorded **around** the production runner rather than in place
     * of it.
     *
     * Not a fake: [delegate] is the real [ProcessCommandRunner], so the child is forked, the pipes
     * are drained and the short-read discipline applies exactly as they do on the hotkey path.
     * All that is added is a record — which is the only evidence available here, because the id a
     * real spanreed returns is by design reconstructible without one (#139; see
     * `the panel comes up under an identity the real spanreed issued`).
     *
     * [calls] is a plain list on purpose. It is written on `Dispatchers.IO` inside
     * `SpanreedCli.exec`'s `withContext` and read after `invoke()` has returned, and that
     * resumption is the happens-before — a concurrent collection here would suggest a
     * concurrency this test does not have.
     */
    private class RecordingRunner(private val delegate: CommandRunner) : CommandRunner {
        val calls = mutableListOf<Call>()

        override fun run(
            command: List<String>,
            environment: Map<String, String?>,
        ): ProcessResult = delegate.run(command, environment).also {
            calls += Call(command, environment, it)
        }

        class Call(
            val command: List<String>,
            val environment: Map<String, String?>,
            val result: ProcessResult,
        )
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val DOCK_APP_ID = "aw-dock"
    }
}
