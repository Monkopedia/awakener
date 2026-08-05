package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Flags

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
            "whatever {app_id} expands to in the dock command.",
    )

    val whenAnimated = Flags.enum(
        "invoke.when_animated",
        WhenAnimated.REUSE,
        "What a hotkey does when this surface's Lifeless already has a live session on the bus. " +
            "Reusing declines to stand a second panel, since two sessions under one identity " +
            "accumulate two divergent residues; SECOND_DOCK stands another anyway, which is the " +
            "deliberate second view onto one agent.",
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
