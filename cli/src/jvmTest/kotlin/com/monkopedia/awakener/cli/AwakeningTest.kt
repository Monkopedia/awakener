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
        dockFor = { wm.standing[it] },
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
     * Jason's ask, 2026-08-23, as a unit: *"if we do Super A and the instance is present … we
     * should just hide the window … and then be able to bring it back if you hit Super A again"*.
     *
     * Three presses, three different things, and the panel is the same one throughout — which is
     * what makes it a toggle rather than a stand-up followed by two no-ops.
     */
    @Test
    fun `TOGGLE_HIDE hides the standing panel and brings it back on the next press`() = runTest {
        config.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)

        val first = assertIs<Awakened.Animated>(awakening.invoke())
        val panel = wm.standing.getValue(app.id)

        val second = assertIs<Awakened.Hidden>(awakening.invoke())
        assertEquals(panel.dockId, second.dock.dockId, "the panel it stood up is the one it hid")
        assertTrue(panel.isHidden)

        val third = assertIs<Awakened.Restored>(awakening.invoke())
        assertEquals(panel.dockId, third.dock.dockId)
        assertTrue(!panel.isHidden)

        assertEquals(1, wm.attaches.size, "and no second panel was ever stood up")
        assertEquals(1, panel.hides)
        assertEquals(1, panel.shows)
        assertEquals(first.binding.agent, third.dock.agent, "still the same Lifeless")
    }

    /**
     * A toggle press must be free of everything durable. There is nothing to mint — a surface with
     * a panel beside it was bound by the attach that stood the panel up — and preparing residue or
     * re-binding on every keystroke would make a hide cost a registry write.
     */
    @Test
    fun `a toggle press mints nothing, prepares nothing, and asks the bus nothing`() = runTest {
        config.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        awakening.invoke()
        prepared.clear()
        val callsAfterAttach = bus.calls

        assertIs<Awakened.Hidden>(awakening.invoke())

        assertTrue(prepared.isEmpty(), "no residue was prepared for a press that only hid a panel")
        assertEquals(callsAfterAttach, bus.calls, "and the bus was not consulted")
    }

    /**
     * The other half of the same flag: with no panel beside the surface there is nothing to
     * toggle, so the press stands one up. That is the first press, and it is also what happens
     * after the panel has been closed by hand.
     */
    @Test
    fun `TOGGLE_HIDE stands a panel up when the surface has none`() = runTest {
        config.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        bus.live = emptyList()

        val awakened = assertIs<Awakened.Animated>(awakening.invoke())
        assertTrue(awakened.minted)
        assertEquals(1, wm.attaches.size)
    }

    /**
     * `TOGGLE_HIDE` asks the tree and not the bus, which is a deliberate difference from
     * [WhenAnimated.REUSE] and the one consequence worth pinning: an animated Lifeless whose panel
     * is standing gets hidden rather than declined.
     */
    @Test
    fun `TOGGLE_HIDE hides an animated Lifeless where REUSE would decline`() = runTest {
        val first = assertIs<Awakened.Animated>(awakening.invoke())
        bus.live = listOf(LiveAgent(first.binding.agentId, first.binding.spanreedName))

        assertIs<Awakened.AlreadyAnimated>(awakening.invoke())

        config.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        assertIs<Awakened.Hidden>(awakening.invoke())
    }

    /**
     * The default is what an unconfigured desktop gets, and this change must not have moved it.
     * Asserted against the declared default rather than against behaviour, because behaviour that
     * happens to match is what a wrong default looks like until the day it does not.
     */
    @Test
    fun `REUSE is still the default, so an unconfigured system is unchanged`() = runTest {
        assertEquals(WhenAnimated.REUSE, InvokeFlags.whenAnimated.default)
        assertEquals(WhenAnimated.REUSE, config.config.value[InvokeFlags.whenAnimated])
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

    /**
     * What the hotkey actually *prints*, which nothing else in this build looks at.
     *
     * It is here because the first version of these two lines interpolated `dock.agent` directly
     * and printed `AgentId(raw=agent-…)` — [AgentId] is a value class over a `String`, so its
     * `toString` is the wrapper's. Every other reader of an agent on this path goes through
     * `Binding.agentId`, which is already a `String`, so nothing existing would have caught it and
     * nothing did: it was found by running the shipped launcher against a live sway. This is the
     * guard that makes the second occurrence cheaper than the first.
     */
    @Test
    fun `a toggle press prints the agent id and not the wrapper around it`() = runTest {
        config.put(InvokeFlags.whenAnimated, WhenAnimated.TOGGLE_HIDE)
        val stood = assertIs<Awakened.Animated>(awakening.invoke())

        val printed = mutableListOf<String>()
        assertEquals(0, AwakenerInvokeCli.run(arrayOf("invoke"), awakening, printed::add))
        assertEquals(0, AwakenerInvokeCli.run(arrayOf("invoke"), awakening, printed::add))

        val id = stood.binding.agentId
        assertTrue(printed.any { it.startsWith("hid dock ") }, printed.toString())
        assertTrue(printed.any { it.startsWith("restored dock ") }, printed.toString())
        assertTrue(printed.any { it.trim().startsWith("$id is still running") }, printed.toString())
        assertTrue(printed.any { it.trim().startsWith("$id is back on screen") }, printed.toString())
        assertTrue(
            printed.none { "AgentId(" in it },
            "the value class wrapper reached the user: $printed",
        )
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
        val standing = mutableMapOf<SurfaceId, FakeDock>()
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
            // Kept, because a toggle's second press has to find the panel the first stood up —
            // which is the fact `SwayWindowManager.dockFor` supplies in production.
            return FakeDock(surface, bound.agent, SurfaceId(surface.raw + 1000))
                .also { standing[surface] = it }
        }

        override val changes: Flow<SurfaceChange> = emptyFlow()
    }

    /**
     * A panel that remembers whether it is hidden, which is the whole of what the toggle needs
     * from a compositor it is not testing.
     *
     * The bit is here and not in `Awakening` on purpose: the loop under test must ask, and a fake
     * that answered from something the loop handed it would prove nothing about that.
     */
    private class FakeDock(
        override val surface: SurfaceId,
        override val agent: AgentId,
        override val dockId: SurfaceId,
    ) : DockHandle {
        var isHidden = false
        var hides = 0
        var shows = 0

        override suspend fun focus() = Unit
        override suspend fun settleFocus() = Unit
        override suspend fun detach() = Unit
        override suspend fun hidden(): Boolean = isHidden

        override suspend fun hide(): Boolean {
            if (isHidden) return false
            isHidden = true
            hides++
            return true
        }

        override suspend fun show(): Boolean {
            if (!isHidden) return false
            isHidden = false
            shows++
            return true
        }
    }
}
