package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentBus
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.InMemoryBindingStore
import com.monkopedia.awakener.registry.LiveAgent
import com.monkopedia.awakener.registry.RegistryFlags
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.WindowIdentity
import com.monkopedia.awakener.registry.asIdentity
import com.monkopedia.awakener.wm.DockHandle
import com.monkopedia.awakener.wm.DockSpec
import com.monkopedia.awakener.wm.Surface
import com.monkopedia.awakener.wm.SurfaceChange
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.WindowManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * The invoke loop, without a compositor.
 *
 * What is faked here is deliberately only the compositor and the bus — the registry is the real
 * [InMemoryBindingStore], because the questions this suite exists to answer are all about which
 * identity ends up where, and a fake registry would be a test of the fake. `AwakeningSwayTest`
 * covers the half that only a live sway can say anything about.
 */
class AwakeningTest {
    private val config = InMemoryConfigStore()
    private val registry = InMemoryBindingStore(config)
    private val bus = FakeBus()
    private val prepared = mutableListOf<SurfaceKey>()
    private var sweeps = 0

    private val app = Surface(SurfaceId(5), "editor", "notes.md", pid = 100, focused = true)
    private val other = Surface(SurfaceId(6), "browser", "a page", pid = 101, focused = false)

    private val wm = FakeWindowManager(listOf(app, other))

    private val awakening = Awakening(
        wm = wm,
        registry = registry,
        bus = bus,
        store = config,
        prepareResidue = { prepared += it },
        reapOrphans = { sweeps++ },
    )

    @BeforeTest
    fun loadFlags() {
        // Flag lookup is by key against a global registry, so the declaring objects have to have
        // initialised before a snapshot can read anything but defaults.
        Flags.requireLoaded(InvokeFlags, RegistryFlags)
    }

    private fun key(surface: Surface) = SurfaceKey.of(surface.descriptor, config.config.value)

    @Test
    fun `a hotkey with no target acts on the focused surface`() = runTest {
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())

        assertEquals(app.id, awakened.surface)
        assertEquals(key(app), awakened.key)
        assertTrue(awakened.minted, "the surface was a Drab, so this is the mint")
    }

    /**
     * The whole reason `Surface.focused` exists. A key press carries no window, so a loop that
     * silently took the first surface would bind whichever window sway happened to list first.
     */
    @Test
    fun `it does not fall back to any surface when nothing is focused`() = runTest {
        wm.surfaces = listOf(app.copy(focused = false), other)

        val nothing = assertIs<Awakened.NoSurface>(awakening.invoke())
        assertTrue(nothing.reason.contains("not a bindable surface"), nothing.reason)
        assertTrue(wm.attaches.isEmpty(), "and nothing was stood up")
    }

    @Test
    fun `an empty desktop is reported as having nothing to awaken`() = runTest {
        wm.surfaces = emptyList()
        assertEquals(
            "no bindable surfaces",
            assertIs<Awakened.NoSurface>(awakening.invoke()).reason,
        )
    }

    @Test
    fun `an id naming no surface is refused rather than guessed at`() = runTest {
        assertEquals(
            "no such surface: 99",
            assertIs<Awakened.NoSurface>(awakening.invoke(SurfaceId(99))).reason,
        )
    }

    @Test
    fun `an explicit id awakens that surface and not the focused one`() = runTest {
        val awakened = assertIs<Awakened.Animated>(awakening.invoke(other.id))
        assertEquals(other.id, awakened.surface)
        assertEquals(key(other), awakened.key)
    }

    /**
     * The binding is the product. A second press must find the agent that already carries this
     * surface's accumulated model, not mint a stranger.
     */
    @Test
    fun `a second invocation recalls the same agent rather than minting`() = runTest {
        val first = assertIs<Awakened.Animated>(awakening.invoke())
        bus.live = emptyList() // the panel died; the surface is a Drab to the bus, not to us

        val second = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(first.binding.agentId, second.binding.agentId)
        assertEquals(first.binding.spanreedName, second.binding.spanreedName)
        assertTrue(first.minted, "the first press is the mint")
        assertTrue(!second.minted, "and the second is a recall")
    }

    /**
     * Under the default `registry.key.window_identity=APP_ID` the durable unit is the
     * application, so a second window of it is the same surface and gets the same Lifeless. This
     * is the property the live suite asserts against a real second window.
     */
    @Test
    fun `a new window of the same app recalls that app's agent`() = runTest {
        val first = assertIs<Awakened.Animated>(awakening.invoke())

        // Same app_id, different con_id and pid: a relaunch, or a second window.
        val reopened = Surface(SurfaceId(11), "editor", "other.md", pid = 200, focused = true)
        wm.surfaces = listOf(reopened)

        val second = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(first.binding.agentId, second.binding.agentId)
        assertTrue(!second.minted)
    }

    @Test
    fun `per-window identity gives the second window its own Lifeless`() = runTest {
        config.put(RegistryFlags.windowIdentity, WindowIdentity.APP_ID_AND_TITLE)
        val first = assertIs<Awakened.Animated>(awakening.invoke())

        wm.surfaces = listOf(Surface(SurfaceId(11), "editor", "other.md", 200, focused = true))
        val second = assertIs<Awakened.Animated>(awakening.invoke())

        assertTrue(first.binding.agentId != second.binding.agentId)
        assertTrue(second.minted)
    }

    /**
     * The single most load-bearing substitution in the project: spanreed derives an id from the
     * session's working directory, and a surface has no working directory, so a command that
     * loses this variable animates a stranger with none of the surface's residue.
     */
    @Test
    fun `the dock command carries the minted SPANREED_AGENT_NAME`() = runTest {
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())
        val command = wm.attaches.single().dock.command

        assertTrue(
            command.contains("SPANREED_AGENT_NAME=${awakened.binding.spanreedName}"),
            command,
        )
        assertTrue(Awakening.AGENT_NAME !in command, "the placeholder must not survive: $command")
    }

    /**
     * `{app_id}` is `:wm`'s, and leaving it is what keeps `wm.dock.identity` working from this
     * path — under `PER_SURFACE_APP_ID` the name is derived from a `con_id` nothing up here may
     * see.
     */
    @Test
    fun `the app_id placeholder is left for the wm layer to substitute`() = runTest {
        awakening.invoke()
        val spec = wm.attaches.single().dock

        assertEquals("awakener-dock", spec.appId)
        assertTrue(
            spec.command.contains(DockSpec.APP_ID_PLACEHOLDER),
            "the wm layer needs it intact: ${spec.command}",
        )
    }

    @Test
    fun `agent id and residue are substituted too`() = runTest {
        config.put(
            InvokeFlags.dockCommand,
            "panel --id {agent_id} --name {agent_name} --residue {residue}",
        )
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())

        assertEquals(
            "panel --id ${awakened.binding.agentId} --name ${awakened.binding.spanreedName} " +
                "--residue ${registry.residueLocation(key(app))}",
            wm.attaches.single().dock.command,
        )
    }

    /**
     * Standing a second session up under one identity would give one surface two agents writing
     * two divergent residues to one path.
     */
    @Test
    fun `an already animated Lifeless gets no second panel`() = runTest {
        val first = assertIs<Awakened.Animated>(awakening.invoke())
        bus.live = listOf(LiveAgent(first.binding.agentId, first.binding.spanreedName))
        wm.attaches.clear()

        val second = assertIs<Awakened.AlreadyAnimated>(awakening.invoke())
        assertEquals(first.binding.agentId, second.binding.agentId)
        assertTrue(wm.attaches.isEmpty(), "no second attach")
    }

    /**
     * The design note left #14's "refuse a second attach" to whoever owns the hotkey path rather
     * than taking it in `:wm`. This is that decision, and it is a flag because a second view onto
     * one Lifeless is a real thing to want.
     */
    @Test
    fun `SECOND_DOCK stands another panel for the same agent`() = runTest {
        val first = assertIs<Awakened.Animated>(awakening.invoke())
        bus.live = listOf(LiveAgent(first.binding.agentId, first.binding.spanreedName))
        config.put(InvokeFlags.whenAnimated, WhenAnimated.SECOND_DOCK)

        val second = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(first.binding.agentId, second.binding.agentId, "same Lifeless, second panel")
        assertEquals(2, wm.attaches.size)
    }

    /**
     * The stated cost of binding before attaching, pinned so it cannot drift into a surprise.
     * A failed attach leaves a durable binding to an agent with no panel — and the retry recalls
     * it, which is the behaviour wanted anyway: re-minting would strand the residue path the
     * previous identity was going to write to.
     */
    @Test
    fun `a failed attach leaves the binding, and the retry recalls it`() = runTest {
        wm.attachFailure = IllegalStateException("dock never appeared")
        assertFailsWith<IllegalStateException> { awakening.invoke() }

        val bound = registry.resolve(key(app))
        assertTrue(bound != null, "the binding outlives the failed attach, deliberately")

        wm.attachFailure = null
        val retry = assertIs<Awakened.Animated>(awakening.invoke())
        assertEquals(bound.agentId, retry.binding.agentId)
        assertTrue(!retry.minted, "the retry recalls rather than minting a second identity")
    }

    /**
     * Before the bind, so that a cold Lifeless launched with `{residue}` finds an empty model
     * rather than a path that is not there.
     */
    @Test
    fun `residue is prepared for the surface, and the flag turns it off`() = runTest {
        awakening.invoke()
        assertEquals(listOf(key(app)), prepared)

        config.put(InvokeFlags.residuePrepare, false)
        prepared.clear()
        awakening.invoke(other.id)
        assertTrue(prepared.isEmpty())
    }

    /** The agent handed to `attach` is the one the command was built from; see `Awakening`. */
    @Test
    fun `the agent baked into the command is the one attach is told to bind`() = runTest {
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())
        val attach = wm.attaches.single()

        assertEquals(awakened.binding.agent, attach.agent)
        assertEquals(awakened.binding.agent, awakened.dock.agent)
    }

    @Test
    fun `list reports drabs, bound agents, and which are animated`() = runTest {
        val awakened = assertIs<Awakened.Animated>(awakening.invoke())
        bus.live = listOf(LiveAgent(awakened.binding.agentId, awakened.binding.spanreedName))

        val standing = awakening.list().associateBy { it.surface.id }
        assertEquals(awakened.binding.agent, standing.getValue(app.id).agent)
        assertTrue(standing.getValue(app.id).animated)
        assertNull(standing.getValue(other.id).agent, "never invoked, so still a Drab")
        assertTrue(!standing.getValue(other.id).animated)
    }

    /** One subprocess for the whole listing, not one per window. */
    @Test
    fun `listing asks the bus once however many surfaces there are`() = runTest {
        awakening.list()
        assertEquals(1, bus.calls)
    }

    @Test
    fun `reap delegates to the sweep the composition root supplied`() = runTest {
        awakening.reap()
        assertEquals(1, sweeps)
    }

    private class FakeBus : AgentBus {
        var live: List<LiveAgent> = emptyList()
        var calls = 0

        override suspend fun liveAgents(): List<LiveAgent> {
            calls++
            return live
        }
    }

    private class Attach(val surface: SurfaceId, val dock: DockSpec, val agent: AgentId?)

    /**
     * A compositor that records rather than one that pretends.
     *
     * It does one thing the real `attach` does — it binds — because that is what makes "the
     * identity in the command and the identity on the handle agree" a real assertion rather than
     * an echo of what the test just handed in.
     */
    private inner class FakeWindowManager(var surfaces: List<Surface>) : WindowManager {
        val attaches = mutableListOf<Attach>()
        var attachFailure: Throwable? = null

        override suspend fun surfaces(): List<Surface> = surfaces

        override suspend fun resolve(surface: SurfaceId): AgentId? =
            surfaces.firstOrNull { it.id == surface }
                ?.let { registry.resolve(SurfaceKey.of(it.descriptor, config.config.value))?.agent }

        override suspend fun attach(
            surface: SurfaceId,
            dock: DockSpec,
            agent: AgentId?,
        ): DockHandle {
            attachFailure?.let { throw it }
            attaches += Attach(surface, dock, agent)
            val window = surfaces.first { it.id == surface }
            val bound = registry.bind(
                SurfaceKey.of(window.descriptor, config.config.value),
                agent?.asIdentity(),
            )
            return FakeDock(surface, bound.agent, SurfaceId(surface.raw + 1000))
        }

        override val changes: Flow<SurfaceChange> = emptyFlow()
    }

    private class FakeDock(
        override val surface: SurfaceId,
        override val agent: AgentId,
        override val dockId: SurfaceId,
    ) : DockHandle {
        override suspend fun focus() = Unit
        override suspend fun settleFocus() = Unit
        override suspend fun detach() = Unit
    }
}
