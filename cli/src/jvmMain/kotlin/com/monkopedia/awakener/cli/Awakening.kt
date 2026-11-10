package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.connector.ConnectorCatalog
import com.monkopedia.awakener.connector.ConnectorFlags
import com.monkopedia.awakener.connector.ConnectorListing
import com.monkopedia.awakener.connector.ConnectorOutcome
import com.monkopedia.awakener.connector.SurfaceFacts
import com.monkopedia.awakener.registry.AgentBus
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.Binding
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.Forget
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.isAnimated
import com.monkopedia.awakener.wm.DockHandle
import com.monkopedia.awakener.wm.DockSpec
import com.monkopedia.awakener.wm.Surface
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.WindowManager
import kotlinx.coroutines.CancellationException

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
        /**
         * What the connector did, if one was selected.
         *
         * [ConnectorOutcome.None] is the unconfigured answer and is the default, so a caller
         * that never heard of connectors reads exactly what it read before they existed.
         */
        val connector: ConnectorOutcome = ConnectorOutcome.None,
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

    /**
     * The panel standing beside this surface was taken out of the layout, and its agent is still
     * running behind it.
     *
     * A distinct outcome from [AlreadyAnimated] because the two are opposite answers to the same
     * press: one declines to act and the other acts. The dock handle is carried so a caller can
     * say which node went, and because the next press's [Restored] names the same one.
     */
    data class Hidden(
        val surface: SurfaceId,
        val key: SurfaceKey,
        val dock: DockHandle,
    ) : Awakened

    /** A hidden panel was put back beside its surface, with its configured side and width. */
    data class Restored(
        val surface: SurfaceId,
        val key: SurfaceKey,
        val dock: DockHandle,
    ) : Awakened

    /**
     * A connector was asked for and could not be applied, so no panel was stood up.
     *
     * **Withholding the panel is the decision, and it is deliberate.** The tempting alternative
     * — stand it up anyway, print the warning — produces an agent that looks exactly like a
     * connected one and is not, in a window the user is about to ask it to act on. Every fact
     * awakener can hand a connector is measured to be non-unique on its own (see
     * [ConnectorCatalog]), so "connected to something" is a real possible outcome of proceeding,
     * and it is worse than not coming up.
     *
     * The binding survives. It was minted before this point for the reason [Awakening] already
     * states about a failed attach, and the retry recalls it rather than stranding its residue.
     *
     * `connector.unknown=IGNORE` is the escape hatch for the one case where the opposite answer
     * is right: a `connector.default` that is only installed on some machines.
     */
    data class ConnectorRefused(
        val surface: SurfaceId,
        val key: SurfaceKey,
        val binding: Binding,
        val refusal: ConnectorOutcome.Refused,
    ) : Awakened

    /** There was nothing to act on. The ordinary answer to a hotkey on an empty workspace. */
    data class NoSurface(val reason: String) : Awakened
}

/**
 * A binding already on disk for one of awakener's own windows, and what `list` did about it.
 *
 * @param forgotten what the forget did under [FurnitureBound.FORGET], or null under
 * [FurnitureBound.REPORT], where the binding is still there.
 */
data class BoundFurniture(val key: SurfaceKey, val binding: Binding, val forgotten: Forget?)

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
     * Where connector definitions come from, and what applies one.
     *
     * A collaborator rather than a lambda — unlike [prepareResidue] and friends, which are
     * lambdas because only a *concrete* store or compositor can answer them. A catalog is
     * already compositor-agnostic and store-agnostic by construction: it is handed
     * [com.monkopedia.awakener.connector.SurfaceFacts], which is strings.
     *
     * Defaults to one that finds nothing and applies nothing, which is what makes every
     * existing construction site — and every test written before connectors existed — mean
     * exactly what it meant.
     */
    private val connectors: ConnectorCatalog = NoConnectors,
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
    /**
     * Finds the panel already standing beside a surface, if there is one.
     *
     * A lambda for exactly the reasons [reapOrphans] is one, and it is the piece that makes
     * `invoke.when_animated=TOGGLE_HIDE` possible from a one-shot process at all: `attach` is the
     * only thing that mints a [DockHandle], and this binary exits between presses. Recovering the
     * handle is `SwayWindowManager.dockFor`'s, which reads it back out of the tree by mark — so it
     * works across an awakener restart, and it is a concrete method rather than a fourth call on
     * [WindowManager], whose three-call agreement is #94's to settle rather than this change's.
     *
     * Defaults to "no dock", which is what an implementation with no notion of one honestly
     * reports; under that default `TOGGLE_HIDE` degrades to standing a panel up every press.
     */
    private val dockFor: suspend (SurfaceId) -> DockHandle? = { null },
    /**
     * The focused panel, if focus is in one that names its surface — [DockHandle.surface].
     *
     * [dockFor]'s question the other way round, a lambda for the same reasons, and the piece
     * that lets a press made from inside a panel act on its surface (#196) and on that panel
     * rather than a sibling (#220). Defaults to "not in a panel", which leaves every such press
     * refused, as it was before.
     */
    private val focusedDock: suspend () -> DockHandle? = { null },
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
        val surfaces = wm.surfaces().filterNot { cfg.isFurniture(it.appId) }
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
     * The flag snapshot is read once, so one invocation cannot act on two of them: a command
     * built under one dock name and attached under another would leave a panel nothing can
     * find. **That reason is about this method and not about the binary it happens to be in.**
     * It used to be stated the other way round — that reading once was merely the right shape,
     * because nothing anywhere called `FileConfigStore.watch` and no snapshot was ever replaced
     * (#43). Something does now, so the qualifier has gone; what has not changed is that the
     * one read is deliberate. A hotkey press is one operation and must act on one set of
     * values, and it would still have to whether or not this process were following the file.
     */
    suspend fun invoke(
        target: SurfaceId? = null,
        connector: ConnectorChoice = ConnectorChoice.FromConfig,
    ): Awakened {
        val cfg = config
        val surfaces = wm.surfaces()
        // The panel the press came from, when it came from one. Kept, not just mapped to its
        // surface: with two panels beside one surface, re-deriving "the" dock from the surface
        // answers whichever the tree lists first, which need not be this one (#220).
        val pressedFrom =
            if (target == null && surfaces.none { it.focused }) pressedPanel(cfg) else null
        val aimed = if (target == null) {
            surfaces.firstOrNull { it.focused }
                ?: pressedFrom?.let { panel -> surfaces.firstOrNull { it.id == panel.surface } }
        } else {
            surfaces.firstOrNull { it.id == target }
        }
        // After the press is aimed — `pressedFrom` included — and before anything acts on it
        // (#197). Before, because this
        // process is the only thing that runs, so the press is the only moment a panel whose
        // window has closed can be noticed at all. After the aim, because the orphan is the
        // likeliest thing to have focus — it fills the tab — and sweeping it moves focus to
        // whatever sway picks next: a press aimed after the sweep would act on a window the user
        // never pressed in. The sweep only takes down panels whose surface is gone, so it cannot
        // take down the one this press is aimed at.
        if (cfg[InvokeFlags.sweepFirst]) sweep()
        val surface = aimed ?: return Awakened.NoSurface(
            when {
                target != null -> "no such surface: ${target.raw}"
                surfaces.isEmpty() -> "no bindable surfaces"
                // Focus is on something surfaces() excludes, and the likeliest such thing is a
                // dock — pressing the hotkey inside an agent panel. Saying so beats "nothing is
                // focused", which is not true and sends the reader to sway.
                else -> "the focused window is not a bindable surface (an agent panel, perhaps)"
            },
        )

        // Ahead of everything durable, because a binding for a panel is a Lifeless whose surface
        // is another Lifeless's window and it outlives the window that caused it (#170). Asked of
        // the `app_id` rather than of `:wm`'s dock recognition, which cannot cover this: a
        // one-shot process starts with an empty dock table and knows a panel only by its mark, so
        // a panel another press is still standing up, or one #199's race left unclaimed, is an
        // ordinary window to it. The `app_id` is the one fact such a window has from the moment
        // it maps, and it is the configured one.
        if (cfg.isFurniture(surface.appId)) {
            return Awakened.NoSurface(
                "the window is awakener's own (${surface.appId}), not a surface to bind an " +
                    "agent to",
            )
        }

        val key = SurfaceKey.of(surface.descriptor, cfg)
        // Read before binding, because binding is what makes it stop being true.
        val wasADrab = registry.resolve(key) == null
        // Ahead of everything durable, and that order is the whole of what makes a toggle
        // cheap: a press that only hides or restores a panel mints nothing, prepares nothing,
        // and writes nothing. It is also correct rather than merely tidy — a surface with a
        // panel standing beside it was bound by the attach that stood it up, so there is no
        // identity here left to recall.
        if (cfg[InvokeFlags.whenAnimated] == WhenAnimated.TOGGLE_HIDE) {
            val dock = pressedFrom ?: dockFor(surface.id)
            if (dock != null) {
                // Asked of the compositor rather than remembered, so a dock the user pulled out
                // of the scratchpad by hand toggles from where it actually is. Nothing here holds
                // a "hidden" bit; there is nowhere for one to be wrong.
                return if (dock.hidden()) {
                    dock.show()
                    Awakened.Restored(surface.id, key, dock)
                } else {
                    dock.hide()
                    Awakened.Hidden(surface.id, key, dock)
                }
            }
            // No panel: fall through and stand one up. That is the first press of the toggle,
            // and it is also what a press does after the panel has been closed by hand.
        }

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

        val residue = registry.residueLocation(key)

        // Before the attach, so the dock command's process finds the connector's files already
        // in its working directory. That ordering is the whole of "starts up claude code with
        // initial data": there is no message to deliver and no protocol to speak, because the
        // agent's cwd is the residue directory and the files are simply there when it opens it.
        //
        // After the bind, because a connector is told which agent it belongs to, and after
        // `prepareResidue`, because it writes into the directory that call creates.
        val applied = connectors.apply(
            when (connector) {
                is ConnectorChoice.FromConfig -> cfg[ConnectorFlags.default].ifEmpty { null }
                is ConnectorChoice.None -> null
                is ConnectorChoice.Named -> connector.name
            },
            SurfaceFacts(
                surfaceId = surface.id.raw,
                appId = surface.appId,
                title = surface.title,
                pid = surface.pid,
                surfaceKey = key.canonical,
                agentId = binding.agentId,
                agentName = binding.spanreedName,
                residue = residue,
            ),
        )
        if (applied is ConnectorOutcome.Refused) {
            return Awakened.ConnectorRefused(surface.id, key, binding, applied)
        }

        val command = cfg[InvokeFlags.dockCommand]
            .replace(AGENT_NAME, binding.spanreedName)
            .replace(AGENT_ID, binding.agentId)
            .replace(RESIDUE, residue)

        // The agent is handed to `attach` rather than left for it to work out. Its own bind is
        // then a recall of the row this method just wrote, so the identity baked into the command
        // above and the identity the handle reports cannot disagree — which they could if each
        // side resolved independently across a `registry.binding.rebind_policy` flip.
        val dock = wm.attach(
            surface.id,
            DockSpec(cfg[InvokeFlags.dockAppId], command),
            binding.agent,
        )
        return Awakened.Animated(surface.id, key, binding, dock, wasADrab, applied)
    }

    /**
     * The panel a press was made from, under `invoke.from_panel=OWNING_SURFACE` (#196).
     *
     * Only ever reached with focus on something [WindowManager.surfaces] left out, so this does
     * not undo #170: a panel nothing has claimed is still listed, still focused, and refused by
     * the `app_id` check below. What reaches here is a panel `:wm` recognised; the caller looks
     * its surface up in this press's own enumeration, so one whose surface has gone is refused
     * rather than acted on.
     */
    private suspend fun pressedPanel(cfg: Config): DockHandle? =
        if (cfg[InvokeFlags.fromPanel] == FromPanel.OWNING_SURFACE) focusedDock() else null

    /**
     * Bindings already written for awakener's own windows — before #170 refused them — reported,
     * or forgotten under `invoke.furniture.bound=FORGET`.
     *
     * Nothing recalls such a binding any more, since [list] and [invoke] no longer offer the
     * window it names, so leaving one in place costs nothing but the row and its residue. That is
     * why the default only says so: what to do with the residue is the user's decision.
     */
    suspend fun boundFurniture(): List<BoundFurniture> {
        val cfg = config
        val forget = cfg[InvokeFlags.furnitureBound] == FurnitureBound.FORGET
        return registry.bindings.value
            .filterKeys { it is SurfaceKey.Window && cfg.isFurniture(it.appId) }
            .map { (key, binding) ->
                BoundFurniture(key, binding, if (forget) registry.unbind(key) else null)
            }
    }

    /**
     * Whether a window reporting [appId] is one awakener put on screen itself.
     *
     * The panel's name is read from [InvokeFlags.dockAppId] rather than listed, so the rule
     * follows a rename instead of guarding the old name. It is matched both bare and as
     * `<app_id>-<con_id>`, the name `:wm` gives it under `wm.dock.identity=PER_SURFACE_APP_ID` —
     * under either identity, because flipping it leaves the panels named the other way standing.
     */
    private fun Config.isFurniture(appId: String?): Boolean {
        if (appId.isNullOrBlank()) return false
        val dock = this[InvokeFlags.dockAppId]
        if (dock.isNotEmpty()) {
            if (appId == dock) return true
            val suffix = appId.removePrefix("$dock-")
            if (suffix != appId && suffix.isNotEmpty() && suffix.all(Char::isDigit)) return true
        }
        return this[InvokeFlags.furnitureAppIds].split(',').any { it.trim() == appId }
    }

    /** Every connector definition on the search path, and the directories that nearly are one. */
    fun connectors(): ConnectorListing = connectors.list()

    /** Takes down docks whose surface has gone. See [reapOrphans]. */
    suspend fun reap() = reapOrphans()

    /**
     * Why the sweep [invoke] ran under [InvokeFlags.sweepFirst] failed, if it did.
     *
     * Kept rather than raised: a panel that will not come down is a reason to say so, not to
     * refuse every press until it does — the hotkey would then be broken by the one thing the
     * user cannot fix by pressing it. The composition root reports it on stderr. `reap` raises
     * instead, because there the sweep is the whole of what was asked for.
     */
    var sweepFailure: Exception? = null
        private set

    private suspend fun sweep() {
        try {
            reapOrphans()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            sweepFailure = failure
        }
    }

    companion object {
        /** `:wm` substitutes `{app_id}`; see [InvokeFlags.dockCommand] for why it is not here. */
        const val AGENT_NAME = "{agent_name}"
        const val AGENT_ID = "{agent_id}"
        const val RESIDUE = "{residue}"
    }
}

/** Which connector an invocation applies. */
sealed interface ConnectorChoice {
    /**
     * Whatever `connector.default` says, which unconfigured is none.
     *
     * The default value of the parameter, so a caller that has never heard of connectors gets
     * the configured behaviour rather than a hard-coded one.
     */
    data object FromConfig : ConnectorChoice

    /**
     * None, whatever the flag says — `awakener-invoke invoke --no-connector`.
     *
     * Its own value rather than `Named("")`, because "the user asked for no connector" and "the
     * user did not say" are different requests that happen to agree on an unconfigured machine.
     */
    data object None : ConnectorChoice

    /** This one — `--connector chromium`. */
    data class Named(val name: String) : ConnectorChoice
}

/**
 * The catalog an [Awakening] has when nobody gave it one: nothing is installed, nothing applies.
 *
 * Not merely a test double. It is what makes the no-connector path *structurally* identical to
 * the path that existed before this feature — [ConnectorOutcome.None] is returned without any
 * filesystem access at all, so an unconfigured invocation cannot be slowed down, cannot warn,
 * and cannot fail for a reason connectors introduced.
 */
private object NoConnectors : ConnectorCatalog {
    override fun list() = ConnectorListing(emptyList(), emptyList(), emptyList())
    override fun apply(name: String?, facts: SurfaceFacts) = ConnectorOutcome.None
}
