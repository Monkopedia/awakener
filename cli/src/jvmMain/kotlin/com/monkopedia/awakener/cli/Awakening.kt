package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.registry.AgentBus
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.Binding
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.isAnimated
import com.monkopedia.awakener.wm.DockHandle
import com.monkopedia.awakener.wm.DockSpec
import com.monkopedia.awakener.wm.Surface
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.WindowManager

/** One window, and what awakener currently knows about it. */
data class Standing(
    val surface: Surface,
    val key: SurfaceKey,
    /** Null means a Drab: a window with nothing bound to it. */
    val binding: Binding?,
    /** Whether this surface's Lifeless has a live session on the bus right now. */
    val animated: Boolean,
) {
    val agent: AgentId? get() = binding?.agent
}

/** What one invocation did. */
sealed interface Awakened {
    /** A panel was stood up, and this surface's Lifeless is animating in it. */
    data class Animated(
        val surface: SurfaceId,
        val key: SurfaceKey,
        val binding: Binding,
        val dock: DockHandle,
        /** Whether this invocation is what first gave the surface an agent. */
        val minted: Boolean,
    ) : Awakened

    /**
     * The surface's Lifeless was already animated, so no second panel was stood up.
     *
     * Not a failure and not a no-op: the binding was still recalled, which is what makes the
     * answer "the agent you want is `<id>`, and it is already up". What a one-shot process
     * cannot do is raise the panel it is already in — see [WhenAnimated.REUSE].
     */
    data class AlreadyAnimated(
        val surface: SurfaceId,
        val key: SurfaceKey,
        val binding: Binding,
    ) : Awakened

    /** There was nothing to act on. The ordinary answer to a hotkey on an empty workspace. */
    data class NoSurface(val reason: String) : Awakened
}

/**
 * The loop the whole project exists for: a window becomes a surface, a surface recalls or mints
 * its Lifeless, and the Lifeless comes up in a panel docked inside that window's tab.
 *
 * **Compositor-agnostic on purpose.** It holds a [WindowManager] and never a `SwayWindowManager`,
 * so nothing here knows what a `con_id`, a mark or a split container is. The two things it needs
 * that the interface does not carry — creating a residue location, and sweeping orphaned docks —
 * arrive as lambdas from the composition root, which is the one place that already knows both the
 * concrete store and the concrete compositor. That is deliberately not a fourth and fifth call on
 * [WindowManager]: the working agreement holds that interface at three, and a sweep is a repair
 * an implementation offers rather than a thing every compositor must have.
 *
 * ### Why the binding happens before the attach
 *
 * `attach` binds too — it is the last thing it does, once the dock is standing — and that order
 * is right for `attach`, because a failed attach must not leave a durable binding to an agent
 * with no panel. But the dock *command* has to carry `SPANREED_AGENT_NAME` into the process sway
 * `exec`s, and that command is built before the exec, which is long before `attach` binds. So the
 * identity must exist first, and this layer is where it is made to.
 *
 * The cost is real and is stated rather than designed around: **a failed attach leaves a durable
 * binding to an agent that never came up.** The compensating fact is that the retry recalls that
 * binding rather than minting a second one, which is the behaviour wanted anyway — an agent
 * identity is cheap and stable, and re-minting on every failed attempt is what would actually
 * hurt, since it strands the residue path the previous identity was going to write to.
 *
 * ### Why "already animated" is asked of the bus and not of the tree
 *
 * The tree could answer "is there a panel beside this window", and that is a worse question. It
 * is compositor-specific, it needs a fourth call, and it stops being the same question the
 * moment a panel and an agent are not one-to-one — which is exactly what the design brief's
 * Chrome manager does, mirroring several origins behind one window. "Is this Lifeless animated"
 * is the question that survives that, and `spanreed list` already answers it.
 */
class Awakening(
    private val wm: WindowManager,
    private val registry: BindingStore,
    private val bus: AgentBus,
    private val store: ConfigStore,
    /**
     * Creates this surface's residue location. A lambda because only the concrete store can —
     * `prepareResidue` is `FileBindingStore`'s, and an in-memory store has nothing to create.
     */
    private val prepareResidue: suspend (SurfaceKey) -> Unit = {},
    /**
     * Takes down docks whose surface has gone. A lambda because the sweep belongs to a
     * compositor implementation rather than to the interface, and this class must not learn
     * which one it has.
     */
    private val reapOrphans: suspend () -> Unit = {},
) {
    private val config: Config get() = store.config.value

    /**
     * Every bindable window, with the agent it resolves to and whether that agent is up.
     *
     * The bus is asked once for the whole listing rather than once per surface: `spanreed list`
     * is a subprocess, and a desktop with a dozen windows would otherwise pay a dozen spawns to
     * answer one question about a registry it reads whole each time.
     */
    suspend fun list(): List<Standing> {
        val cfg = config
        val surfaces = wm.surfaces()
        val live = bus.liveAgents().map { it.agentId }.toSet()
        return surfaces.map { surface ->
            val key = SurfaceKey.of(surface.descriptor, cfg)
            val binding = registry.resolve(key)
            Standing(surface, key, binding, binding != null && binding.agentId in live)
        }
    }

    /**
     * Awakens [target], or — the hotkey case — whichever surface the user is working in.
     *
     * A null [target] is not a convenience: a key press carries no window, and the user's answer
     * to "which one" is where their focus already is. That is what makes the whole binding
     * reachable as `bindsym $mod+a exec awakener-invoke invoke`, with no wrapper script deriving
     * a window id that this could derive better.
     *
     * The flag snapshot is read once. Minting reaches a spanreed subprocess and attaching waits
     * for a window to map, so an invocation spans plenty of time for `:config` to reload
     * underneath it — and an invocation that built its command under one dock name while
     * attaching under another would produce a panel nothing can find.
     */
    suspend fun invoke(target: SurfaceId? = null): Awakened {
        val cfg = config
        val surfaces = wm.surfaces()
        val surface = if (target == null) {
            surfaces.firstOrNull { it.focused }
                ?: return Awakened.NoSurface(
                    if (surfaces.isEmpty()) {
                        "no bindable surfaces"
                    } else {
                        // Focus is on something surfaces() excludes, and the likeliest such thing
                        // is a dock — pressing the hotkey inside an agent panel. Saying so beats
                        // "nothing is focused", which is not true and sends the reader to sway.
                        "the focused window is not a bindable surface (an agent panel, perhaps)"
                    },
                )
        } else {
            surfaces.firstOrNull { it.id == target }
                ?: return Awakened.NoSurface("no such surface: ${target.raw}")
        }

        val key = SurfaceKey.of(surface.descriptor, cfg)
        // Read before binding, because binding is what makes it stop being true.
        val wasADrab = registry.resolve(key) == null
        // Before the bind rather than after, so that a cold Lifeless launched by the command
        // below finds an empty model at {residue} instead of a path that is not there yet.
        if (cfg[InvokeFlags.residuePrepare]) prepareResidue(key)

        // Recall or mint. This is the only moment an identity is ever created — the design brief
        // settles that spawning is on first *invocation*, not on first sight of a window, because
        // window creation as a trigger would mint hundreds a day for tabs glanced at and closed.
        val binding = registry.bind(key)

        if (cfg[InvokeFlags.whenAnimated] == WhenAnimated.REUSE && bus.isAnimated(binding.agent)) {
            return Awakened.AlreadyAnimated(surface.id, key, binding)
        }

        val command = cfg[InvokeFlags.dockCommand]
            .replace(AGENT_NAME, binding.spanreedName)
            .replace(AGENT_ID, binding.agentId)
            .replace(RESIDUE, registry.residueLocation(key))

        // The agent is handed to `attach` rather than left for it to work out. Its own bind is
        // then a recall of the row this method just wrote, so the identity baked into the command
        // above and the identity the handle reports cannot disagree — which they could if each
        // side resolved independently across a `registry.binding.rebind_policy` flip.
        val dock = wm.attach(
            surface.id,
            DockSpec(cfg[InvokeFlags.dockAppId], command),
            binding.agent,
        )
        return Awakened.Animated(surface.id, key, binding, dock, minted = wasADrab)
    }

    /** Takes down docks whose surface has gone. See [reapOrphans]. */
    suspend fun reap() = reapOrphans()

    companion object {
        /** `:wm` substitutes `{app_id}`; see [InvokeFlags.dockCommand] for why it is not here. */
        const val AGENT_NAME = "{agent_name}"
        const val AGENT_ID = "{agent_id}"
        const val RESIDUE = "{residue}"
    }
}
