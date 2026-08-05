package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentBus
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import com.monkopedia.awakener.registry.LiveAgent
import com.monkopedia.awakener.registry.RegistryFlags
import com.monkopedia.awakener.wm.CommandResult
import com.monkopedia.awakener.wm.I3Ipc
import com.monkopedia.awakener.wm.Node
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

        registry = FileBindingStore(
            configStore = store,
            // Counted, so "did this invocation mint" is observable rather than inferred; and
            // derived rather than real, because a mint through the spanreed CLI is a subprocess
            // and this suite already spends a compositor.
            identities = { key, residuePath ->
                minted++
                DerivedAgentIdentities(store).mint(key, residuePath)
            },
            path = stateDir.resolve("bindings.json"),
        )
        wm = SwayWindowManager({ sway.connection() }, store, registry, scope)
        awakening = Awakening(
            wm = wm,
            registry = registry,
            bus = bus,
            store = store,
            prepareResidue = { registry.prepareResidue(it) },
            reapOrphans = wm::reapOrphans,
        )
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

    // -- helpers ------------------------------------------------------------------------

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

    private companion object {
        const val WAIT_MS = 10_000L
        const val DOCK_APP_ID = "aw-dock"
    }
}
