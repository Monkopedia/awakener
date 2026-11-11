package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.wm.PLAIN_NAME

/** What an invocation does when the surface's Lifeless is already animated. */
enum class WhenAnimated {
    /**
     * Stand no second panel. The agent is already up and holding this surface's context, so a
     * second one would be a second session accumulating a second, divergent residue under the
     * same identity.
     *
     * What it cannot do from a one-shot process is *raise* the panel that is already there: a
     * fresh process holds no [com.monkopedia.awakener.wm.DockHandle], and the handle is what
     * knows which node the dock is. Raising is what a daemon adds. Declining to duplicate is the
     * whole of what this value promises.
     *
     * "Already up" is a panel standing for this Lifeless or the bus listing it, by default — see
     * [ReuseEvidence] for why the bus alone answers too late to keep that promise.
     */
    REUSE,

    /**
     * Stand another panel anyway, bound to the same agent.
     *
     * Deliberately reachable rather than forbidden: a second view onto one Lifeless — the same
     * agent docked beside two windows of one application — is a real thing to want, and the
     * design note explicitly left #14's "refuse a second attach" to whoever owns the hotkey
     * path rather than taking it in `:wm`. This is that decision, made here, and made a flag
     * because both answers are defensible.
     */
    SECOND_DOCK,

    /**
     * Hide the panel that is standing, and bring it back on the press after that.
     *
     * Jason's own framing, 2026-08-23: *"if we do Super A and the instance is present, we should
     * probably shut it down, or actually it's probably more likely that we should just hide the
     * window. What would it take for us to hide the window and then be able to bring it back if
     * you hit Super A again?"* — and the answer to the alternative he raised first is why this
     * hides rather than shutting down. The dock is a terminal running `claude`; killing it
     * destroys a live session and everything the agent has accumulated about that surface, which
     * is the one thing this project exists to keep.
     *
     * **It asks only the tree, where [REUSE] also asks the bus, and that is a real difference
     * rather than an inconsistency.** `Awakening`'s note gives the reason [REUSE] is right to ask the bus: "is
     * this Lifeless animated" survives a Chrome manager mirroring several origins behind one
     * window, where "is there a panel beside this window" does not. But the question *this* value
     * has to answer is literally about a panel — there is nothing to hide or restore otherwise —
     * so the tree is the only thing that can answer it. The consequence is worth stating: with a
     * live agent on the bus whose panel has been closed by hand, this stands a new one, where
     * [REUSE] would decline.
     *
     * Not the default. An unconfigured system behaves exactly as it did before this value
     * existed.
     */
    TOGGLE_HIDE,
}

/**
 * What [WhenAnimated.REUSE] takes as proof that a Lifeless is already animated (#198).
 *
 * The bus is the question that survives a panel and an agent stopping being one-to-one — see
 * `Awakening`'s note — but it is not an immediate one. A dock's `claude` registers on spanreed
 * only once its `SessionStart` hook has run: measured on kaladin at 0.5–0.8s after the panel
 * maps in a trusted folder, and **never** in a folder `claude` has not been told to trust, where
 * it sits at the trust prompt until somebody answers it. A press in that window is told "not
 * animated" and stands a second panel under the same identity, which is the outcome [REUSE]
 * exists to prevent and the shape that leaves #171's two docks to be closed by hand.
 */
enum class ReuseEvidence {
    /**
     * Declined if a panel for this Lifeless already stands beside any surface — visible or hidden
     * — **or** the bus says it is animated.
     *
     * The tree knows the instant `attach` returns, because the panel's mark is what `attach`
     * finishes by writing, so a second press cannot overtake it. The bus is still asked when no
     * panel stands, so an agent animated with no panel here (the Chrome manager's mirrored
     * origins, a panel the user closed whose `claude` lives on) is still declined exactly as
     * before. What changes is only the case the bus cannot see yet: a panel standing whose agent
     * has not registered — or never will.
     */
    BUS_OR_PANEL,

    /**
     * The bus alone — the behaviour before #198. A press before the panel's agent registers stands
     * a second panel under the same identity, and so does every press beside a panel whose
     * program never registers at all.
     */
    BUS,
}

/** What a press does when focus is in an agent panel rather than in a surface (#196). */
enum class FromPanel {
    /**
     * Act on the surface the panel is docked beside, exactly as a press from that surface would.
     *
     * The default because it is what makes `invoke.when_animated=TOGGLE_HIDE` a toggle at all
     * under `wm.focus.resting=DOCK`: the press that stands a panel up leaves focus in it, so the
     * next press always comes from inside the panel. Only a panel whose mark names its surface is
     * mapped; one that carries no mark is still refused, since its surface would be a guess.
     */
    OWNING_SURFACE,

    /**
     * Refuse, saying the focused window is not a bindable surface — the behaviour before #196.
     *
     * Kept reachable because under `REUSE` and `SECOND_DOCK` it is less clear that a press from
     * the panel means its surface: under `SECOND_DOCK` it stands a second panel beside the same
     * window, which a press typed into the agent may not have meant.
     */
    REFUSE,
}

/** What `list` does with a binding already written for one of awakener's own windows. */
enum class FurnitureBound {
    /**
     * Say so, and leave it. The default because the binding is inert — nothing enumerates the
     * window it names, so nothing recalls it — and what is under it is a residue directory, which
     * `forget` may archive or delete. That is a decision about durable state, and the person who
     * reads the warning is the one to take it.
     */
    REPORT,

    /**
     * Forget it, exactly as `awakener-registry forget` would, with the residue disposed of per
     * `registry.binding.forget_residue`. For a desktop that has already decided furniture
     * bindings are debris and would rather `list` cleaned them up than reported them each time.
     */
    FORGET,
}

/** How much a hotkey press prints when something on the bind path raises. */
enum class FailureDetail {
    /**
     * The message alone, on stderr, as `error: <message>`.
     *
     * The default because the raises this catches are largely *deliberate* — `liveAgents`
     * refuses to answer an unreadable bus as an empty one, and says why in its message — and a
     * sentence saying what went wrong is the whole of what a person who just pressed a key can
     * use. A stack trace on a hotkey press is a report nobody reads.
     */
    MESSAGE,

    /**
     * The message, and the stack trace under it.
     *
     * For the other half of the same problem: a message is what makes a hotkey say something
     * useful, and a trace is what makes it debuggable. Both are wanted, at different times, by
     * the same person — which is exactly the shape a flag is for.
     */
    TRACE,
}

/**
 * How a hotkey turns a Drab into a Lifeless.
 *
 * These are `:cli`'s because the loop is: the dock *command* is the one place awakener decides
 * what a Lifeless actually is, and that is a product decision rather than a window-management
 * one. `:wm` is told a command and an `app_id` and is deliberately incurious about both.
 */
object InvokeFlags {
    /**
     * The command sway runs to bring a panel up.
     *
     * Four placeholders, and which layer substitutes each is the interesting part:
     *
     * - `{app_id}` is **left alone here**. It is `:wm`'s, substituted inside `attach`, because
     *   under `wm.dock.identity=PER_SURFACE_APP_ID` the name is derived from a `con_id` that
     *   nothing above `:wm` may see. Passing it through untouched is what keeps that flag
     *   working from this path.
     * - `{agent_name}` is the `SPANREED_AGENT_NAME` the registry minted or recalled. This is the
     *   entire binding mechanism: spanreed derives an id from the session's working directory,
     *   and a surface has no working directory, so this variable is the only thing that makes
     *   the session that comes up *this surface's* agent rather than a stranger.
     * - `{agent_id}` is the address that name resolves to, for a dock program that wants to
     *   print or log it.
     * - `{residue}` is where this surface's durable model lives.
     *
     * The default runs a real `claude` under the minted name, which is the product: a terminal
     * with no agent in it would stand up and look identical while proving nothing.
     */
    val dockCommand = Flags.string(
        "invoke.dock.command",
        "foot -a {app_id} -- env SPANREED_AGENT_NAME={agent_name} claude",
        "Command sway runs to stand a Lifeless up. {app_id} is left for :wm to substitute; " +
            "{agent_name}, {agent_id} and {residue} are filled in here. SPANREED_AGENT_NAME is " +
            "the whole binding mechanism — a surface has no working directory for spanreed to " +
            "derive an id from, so a command that drops it animates a stranger.",
    )

    /**
     * The `app_id` the panel reports, or the prefix it is derived from under
     * `wm.dock.identity=PER_SURFACE_APP_ID`.
     *
     * It has to be predictable before the window exists, because sway matches focus rules at map
     * time — which is why it is configured rather than discovered.
     */
    val dockAppId = Flags.string(
        "invoke.dock.app_id",
        "awakener-dock",
        "The app_id a Lifeless's panel reports. It must be predictable before the window maps, " +
            "since that is when sway evaluates the focus rules :wm sets, and it must match " +
            "whatever {app_id} expands to in the dock command — which it is written into as it " +
            "stands, hence the restriction on what it may contain.",
        PLAIN_NAME,
    )

    /**
     * Windows awakener puts on screen itself that are not surfaces, besides its panels.
     *
     * The panel's own `app_id` is deliberately **not** in this list. It is [dockAppId], read from
     * there on every press, so changing what a panel is called cannot leave the refusal guarding
     * the old name. This set is for everything else awakener spawns that a press could land on —
     * the shipped `Super+o` binding runs the listing in `foot -a awakener-list`, which is the
     * default here — and for anything a desktop adds of its own.
     *
     * See [Awakening.invoke] for why this is asked of the `app_id` rather than of the tree.
     */
    val furnitureAppIds = Flags.string(
        "invoke.furniture.app_ids",
        "awakener-list",
        "Comma-separated app_ids of awakener's own windows, which a press refuses to bind and " +
            "`list` does not offer as surfaces. invoke.dock.app_id is always refused besides " +
            "these, both as itself and with a surface's con_id after it (wm.dock.identity=" +
            "PER_SURFACE_APP_ID), so it does not belong here.",
    )

    /** What `list` does about bindings already written for awakener's own windows (#170). */
    val furnitureBound = Flags.enum(
        "invoke.furniture.bound",
        FurnitureBound.REPORT,
        "What `awakener-invoke list` does with a binding whose key is one of awakener's own " +
            "windows — written before such windows were refused, and never recalled since. " +
            "REPORT names it and the `awakener-registry forget` that removes it; FORGET runs " +
            "that forget itself, disposing of the residue per registry.binding.forget_residue.",
    )

    val whenAnimated = Flags.enum(
        "invoke.when_animated",
        WhenAnimated.REUSE,
        "What a hotkey does when this surface's Lifeless is already up — a panel standing for " +
            "it, or a live session on the bus (see invoke.reuse_evidence). " +
            "Reusing declines to stand a second panel, since two sessions under one identity " +
            "accumulate two divergent residues; SECOND_DOCK stands another anyway, which is the " +
            "deliberate second view onto one agent; TOGGLE_HIDE makes the key a toggle — a press " +
            "with the panel standing hides it, leaving the agent running, and the next press " +
            "brings it back where it was. TOGGLE_HIDE asks only the tree, because hiding is a " +
            "question about a panel and only the tree knows whether there is one.",
    )

    val reuseEvidence = Flags.enum(
        "invoke.reuse_evidence",
        ReuseEvidence.BUS_OR_PANEL,
        "What invoke.when_animated=REUSE takes as proof the Lifeless is already up. " +
            "BUS_OR_PANEL declines if a panel for this agent already stands beside any surface, " +
            "visible or hidden, or else if the bus lists it — so a press before the panel's " +
            "claude has registered on spanreed (0.5-0.8s measured in a trusted folder, never in " +
            "an untrusted one until its trust prompt is answered) cannot stand a second panel " +
            "under the same identity. BUS asks spanreed alone, the behaviour before #198. " +
            "Read only under REUSE.",
    )

    val fromPanel = Flags.enum(
        "invoke.from_panel",
        FromPanel.OWNING_SURFACE,
        "What a press with no window id does when focus is in an agent panel. OWNING_SURFACE " +
            "acts on the surface the panel's mark names, as a press from that surface would — so " +
            "under invoke.when_animated=TOGGLE_HIDE the press hides the panel it was made from. " +
            "A panel with no mark names no surface and is refused either way. REFUSE declines " +
            "every press made from a panel.",
    )

    /**
     * Whether a press sweeps for orphaned panels before it does anything else (#197).
     *
     * The press is the only moment `awakener-invoke` runs, so it is the only moment a panel
     * whose window has closed can be noticed. That is what keeps this inside "no unattended
     * autonomous action": the sweep is part of something the user just did.
     */
    val sweepFirst = Flags.boolean(
        "invoke.sweep_first",
        true,
        "Sweep for panels whose window has closed at the start of every `awakener-invoke " +
            "invoke`, before the press resolves its surface. awakener-invoke is one-shot, so " +
            "nothing is running when a window closes and its panel stays standing until " +
            "something sweeps; with this on, that something is the next press anywhere, at the " +
            "cost of one tree read when nothing is orphaned. A press made from inside such a " +
            "panel takes it down and stops, rather than acting on whatever window focus falls " +
            "to. A sweep that fails is reported on stderr and the press carries on — so an " +
            "orphan whose client will not close (a frozen panel) costs every press the detach " +
            "wait, about 5.5s measured, until it is killed by hand; turn this off to stop " +
            "paying it. What is " +
            "swept, and whether anything is, follows wm.dock.orphan_policy and " +
            "wm.dock.reap_evidence exactly as `awakener-invoke reap` does. Off leaves orphans " +
            "for `reap` alone.",
    )

    /**
     * How much a press that raised prints, and where.
     *
     * See [FailureDetail] for why both answers are wanted by the same person at different
     * times. What makes this a `:cli` flag rather than a `:registry` one is that the raises it
     * governs come from every layer at once — `:wm`, `:registry` and `:config` — and the
     * composition root is the only place that sees all of them.
     */
    val failureDetail = Flags.enum(
        "invoke.failure.detail",
        FailureDetail.MESSAGE,
        "How much a failed hotkey press prints to stderr. MESSAGE prints `error: <message>`, " +
            "which is what the deliberate refusals on this path — an unreadable bus, an id " +
            "spanreed did not issue — already say in as many words. TRACE adds the stack trace " +
            "under it, which is the same failure made debuggable rather than merely legible.",
    )

    /**
     * Whether to create the residue file or directory before the panel comes up.
     *
     * On by default because the agent is launched with `{residue}` pointing at it, and an agent
     * told to read a path that does not exist has to guess whether that means "new surface" or
     * "wrong path". Off is for a read-only state directory, where failing to create it would
     * otherwise fail the whole invocation.
     */
    val residuePrepare = Flags.boolean(
        "invoke.residue.prepare",
        true,
        "Create the surface's residue file or directory before standing the panel up, so a " +
            "cold Lifeless reading {residue} finds an empty model rather than a missing path.",
    )
}
