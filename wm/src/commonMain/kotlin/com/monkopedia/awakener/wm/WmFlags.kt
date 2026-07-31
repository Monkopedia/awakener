package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Flags

/** Which side of the surface the dock sits on. */
enum class DockSide { LEFT, RIGHT }

/** What a tab is left focused on once dock interaction ends. */
enum class RestingFocus { APP, DOCK }

/** How `attach` works out which tree node is the dock it just spawned. */
enum class DockIdentity {
    /**
     * The node that was not there before. `attach` records the dock-shaped windows standing at
     * the moment of the spawn and takes the one that appears afterwards. Costs the dock program
     * nothing, which is why it is the default — a panel binary that takes no `app_id` argument
     * still works.
     *
     * Its cost is that the wait is only as trustworthy as the shared `app_id`: any *other*
     * window reporting that name which maps before the real dock does gets adopted in its
     * place, marked as this surface's dock, and killed by the eventual `detach`, while the dock
     * that actually spawned is left unmanaged. awakener's own attaches are serialised so they
     * cannot do this to each other, but a panel started by hand under the same name is outside
     * that guarantee.
     */
    NEW_NODE,

    /**
     * A dock `app_id` minted per surface, so the criteria that match it can never be ambiguous.
     * Immune by construction to another window answering the wait, since nothing else carries
     * the name. Requires the dock command to accept it — see [DockSpec.APP_ID_PLACEHOLDER].
     */
    PER_SURFACE_APP_ID,
}

/** How `attach` keeps the dock off the focus when [WmFlags.dockFocusOnMap] is off. */
enum class FocusSuppression {
    /**
     * Let the dock take focus when it maps and take it back before `attach` returns, inside the
     * section that already holds the tree. Costs a transient steal — 1–2ms across three runs of
     * this code against sway 1.12 — and installs nothing that outlives the attach.
     */
    REFOCUS_AFTER_MAP,

    /**
     * A `no_focus` rule on the dock's `app_id`, issued before the spawn because sway evaluates
     * focus rules when a window maps. No steal at all, at the price of compositor state with no
     * revoke verb: the rule stands until the sway session ends, over every later dock the
     * criteria match.
     */
    NO_FOCUS_RULE,
}

/** What counts as evidence that a tree node is a dock rather than a bindable surface. */
enum class DockRecognition {
    /** The sway mark alone, so that `swaymsg -t get_tree` holds the whole truth. */
    MARK_ONLY,

    /** The mark, or awakener's own record of the docks it stood up this session. */
    MARK_OR_TABLE,
}

/** What the orphan sweep accepts as proof that a node is a dock, before it kills it. */
enum class ReapEvidence {
    /** A dock mark on the node now, or an entry written when this process stood the dock up. */
    CURRENT,

    /** Whatever enumeration recognises, an adopted node whose mark has since gone included. */
    RECOGNITION,
}

/** What happens to a dock whose bound surface has gone away. */
enum class OrphanPolicy {
    /** Tear the dock down with its surface. */
    CLOSE,

    /** Leave it standing — useful when debugging, since the tree damage stays visible. */
    LEAVE,
}

/**
 * Runtime switches for the window-management layer.
 *
 * Every one of these is a behaviour that the sway probe on 2026-07-30 showed to be a genuine
 * choice rather than a fact, so none of them are constants. See
 * `docs/findings/2026-07-30-sway-binding-probe.md`.
 */
object WmFlags {
    val dockSide = Flags.enum(
        "wm.dock.side",
        DockSide.RIGHT,
        "Which side of the surface the dock is placed on.",
    )

    val dockSizePpt = Flags.int(
        "wm.dock.size_ppt",
        30,
        "Dock width as a percentage of the tab, applied after the dock maps.",
    )

    val dockFocusOnMap = Flags.boolean(
        "wm.dock.focus_on_map",
        true,
        "Whether the dock is allowed to hold focus while it maps. Right for a hotkey " +
            "invocation (you are about to type at the agent), wrong for a dock created " +
            "proactively for a surface. It decides only that transient: where focus rests once " +
            "attach returns is wm.focus.resting's, and how the suppression is achieved is " +
            "wm.dock.focus_suppression's.",
    )

    val dockFocusSuppression = Flags.enum(
        "wm.dock.focus_suppression",
        FocusSuppression.REFOCUS_AFTER_MAP,
        "How wm.dock.focus_on_map=false is achieved; read only when that flag is off. " +
            "REFOCUS_AFTER_MAP lets the dock take focus and takes it back before attach " +
            "returns, still holding the tree, so the correction is scoped to the one attach " +
            "that asked for it and nothing outlives it — at the cost of a transient steal, " +
            "measured at 1-2ms across three runs against sway 1.12, with focus resting on the " +
            "application afterwards rather than going back. That correction is part of " +
            "the suppression rather than of resting focus, so it runs whatever " +
            "wm.focus.restore_after_attach says; that flag decides whether the resting-focus " +
            "rule is applied at the end of an attach, not whether a steal is corrected. " +
            "NO_FOCUS_RULE is the previous behaviour and has no steal, but sway has no verb " +
            "that revokes a no_focus rule: it stands for the rest of the compositor session " +
            "over every window the criteria match, and an attach that fails does not take it " +
            "back either — it is named as an exception to that unwind rather than covered by " +
            "it. Under the default " +
            "wm.dock.identity=NEW_NODE those criteria are the app_id every dock shares, so one " +
            "attach under it suppresses focus for every dock afterwards, including docks " +
            "attached while this flag says to focus on map. That half is not fixable here and " +
            "is why REFOCUS_AFTER_MAP is the default; what is fixed is the accumulation — at " +
            "most one rule is issued per app_id per compositor session rather than one per " +
            "attach. Combining it with wm.dock.identity=PER_SURFACE_APP_ID is the one " +
            "arrangement whose rule list still grows without bound, since every attach mints a " +
            "fresh name for the memory to miss.",
    )

    val dockIdentity = Flags.enum(
        "wm.dock.identity",
        DockIdentity.NEW_NODE,
        "How attach tells the dock it just spawned from the docks already standing. Every dock " +
            "is the same panel program and so reports the same app_id, which is no identifier " +
            "at all. NEW_NODE snapshots the windows under that app_id and takes the one that " +
            "appears next: it asks nothing of the dock program, but it trusts the shared name " +
            "for the length of the wait, so any other window reporting it that maps first — a " +
            "panel launched by hand, a second copy started outside awakener — is adopted as " +
            "the dock, marked, and killed by the eventual detach while the real dock goes " +
            "unmanaged. A failed attach reaches the same window by a second route: its unwind " +
            "reads the surface's container back against this name to find a dock it never " +
            "identified, so a window under it that maps in there during the attach is killed " +
            "by the unwind instead. PER_SURFACE_APP_ID makes the name itself unique, so " +
            "nothing else can answer the wait, and where " +
            "wm.dock.focus_suppression=NO_FOCUS_RULE is chosen it " +
            "additionally scopes that rule to one dock instead of to every dock ever spawned — " +
            "at the cost of requiring the dock command to accept the name, and, in that " +
            "combination only, of one permanent no_focus rule per attach, since sway cannot " +
            "revoke one and a fresh name defeats the memory that would suppress the second.",
    )

    val dockMarkPrefix = Flags.string(
        "wm.dock.mark_prefix",
        "awakener_dock_",
        "Prefix for the sway mark identifying a dock. Docks are real tree nodes, and a dock " +
            "mark — this prefix followed by the bound surface's con_id, nothing else — is what " +
            "keeps them out of surface enumeration and focus scripting across an awakener " +
            "restart, while sway keeps running. Changing it against a running desktop orphans " +
            "less than it looks and hides more. It orphans only a dock nothing has enumerated " +
            "since this process started: everything else is already in the record — the docks " +
            "this process stood up, and every dock recognised from its old-prefix mark by any " +
            "read, since recognising a dock records it — and a recorded node is never matched " +
            "against the prefix again, so the flip carries it. That last clause is also the " +
            "sharp edge: a genuine window hidden by a prefix-shaped user mark under the old " +
            "value stays hidden under the new one, and removing the mark does not release it " +
            "either. Moving the prefix is a restart with no docks standing, not a flip.",
    )

    val dockRecognition = Flags.enum(
        "wm.dock.recognition",
        DockRecognition.MARK_OR_TABLE,
        "What identifies a node as a dock. The mark lands one IPC round trip after the window " +
            "maps, so under MARK_ONLY there is a moment in every attach when enumeration " +
            "reports the agent panel as an ordinary bindable surface — and a hotkey acting on " +
            "that answer mints an agent for the panel and writes it to the durable registry. " +
            "MARK_OR_TABLE also counts awakener's own record of the docks it stood up, which " +
            "covers the panel from the moment it maps; the two sources are each reliable in one " +
            "direction only, the record being ahead of the mark during an attach and the mark " +
            "outliving an awakener restart the record cannot. Recognising a dock by its mark " +
            "adds it to the record, so a dock adopted after a restart stays recognised even " +
            "once a second attach on that surface moves the mark off it — and nothing " +
            "withdraws that record, so under MARK_OR_TABLE a node that carried a dock-shaped " +
            "mark at any single read is a dock for the life of the process whatever its marks " +
            "say afterwards. MARK_ONLY is the previous behaviour and the debuggable one — the " +
            "whole truth is then in `swaymsg -t get_tree` with nothing held in process memory " +
            "— and it is the lever to reach for if the record is ever suspected of hiding a " +
            "real window, being the only thing that releases one live. It also stops a pending " +
            "attach's reservation from suppressing anything, for the same reason. The record is " +
            "never written to disk and is never consulted by resolve, which answers from the " +
            "durable registry.",
    )

    val dockPendingSuppression = Flags.boolean(
        "wm.dock.pending_suppression",
        true,
        "Keep windows reporting the dock's app_id out of surface enumeration while an attach " +
            "for that app_id is in flight. A con_id does not exist until the dock maps, so the " +
            "record of a dock cannot be made before that and, without this, enumeration can " +
            "still read the tree in the gap between the map and the record. The app_id is the " +
            "only predicate that exists before the window does. Off is the previous behaviour, " +
            "with no over-suppression at all. On costs that under wm.dock.identity=NEW_NODE, " +
            "where every dock reports the same name, a further window under it is hidden for " +
            "the rest of the attach — narrower than it sounds, since the first such window is " +
            "the one attach adopts as its dock either way, but real. The asymmetry is " +
            "deliberate: a surface briefly missing costs a hotkey that says 'no such surface' " +
            "for a moment, while a dock briefly present costs a minted agent and a durable " +
            "registry write for the panel. Under PER_SURFACE_APP_ID the suppression is exact. " +
            "This decides only whether a reservation is filed; nothing decides whether one is " +
            "cleared, which is unconditional.",
    )

    val reapEvidence = Flags.enum(
        "wm.dock.reap_evidence",
        ReapEvidence.CURRENT,
        "What the orphan sweep must see before it kills a node it believes is a dock. " +
            "Recognising a dock by its mark records it, which is what keeps an adopted dock " +
            "recognised after a second attach moves that mark (#14) — and that record is never " +
            "withdrawn, so recognition outlives the mark that produced it. A genuine window " +
            "carrying a user's own mark that happens to be the dock prefix plus a live con_id " +
            "(#15) is therefore hidden from enumeration for the life of the process even after " +
            "the mark is removed. Being hidden is recoverable — wm.dock.recognition=MARK_ONLY, " +
            "or an awakener restart — and being killed is not, so CURRENT will not reap on that " +
            "latched recognition alone: it kills only a node carrying a dock mark at the moment " +
            "of the sweep, or one this process recorded when it stood the dock up itself. What " +
            "that costs is the single case with neither, a dock adopted after a restart whose " +
            "mark a later attach then took: it stays out of enumeration but its panel is left " +
            "standing when its surface closes, to be closed by hand. RECOGNITION reaps " +
            "everything enumeration calls a dock, which closes that gap at the price of the " +
            "user's window.",
    )

    val orphanPolicy = Flags.enum(
        "wm.dock.orphan_policy",
        OrphanPolicy.CLOSE,
        "What to do with a dock whose surface has closed. sway leaves the dock standing in " +
            "the split container, so without action it becomes an orphaned panel.",
    )

    val normalizeContainerOnDetach = Flags.boolean(
        "wm.dock.normalize_container_on_detach",
        true,
        "Collapse the split container once the dock leaves. sway does not remove single-child " +
            "split containers, and a leftover one silently swallows the next window opened.",
    )

    val unwindFailedAttach = Flags.boolean(
        "wm.dock.unwind_failed_attach",
        true,
        "Take back what an attach put into the tree when it cannot finish. attach splits the " +
            "tab before it spawns anything, so without this a failure leaves the surface " +
            "wrapped in a single-child split container — which sway does not collapse and " +
            "which silently swallows the next window opened in that tab — plus, if the failure " +
            "came after the dock mapped, a panel window nothing holds a handle to. Off leaves " +
            "both standing, for the same reason wm.dock.orphan_policy=LEAVE exists: when " +
            "diagnosing, tree damage you can see beats tree damage that was tidied away. It " +
            "covers the tree and only the tree — awakener's own record of the dock and of the " +
            "attach's reservation is cleared on both paths whatever this says, since a leaked " +
            "reservation is invisible in `swaymsg -t get_tree` while hiding every window under " +
            "the dock's app_id for the life of the process. Two things it does not reach " +
            "either: a no_focus rule, which sway cannot revoke, and the dock program itself, " +
            "which is already exec'd by the time anything can fail — a dock that maps after " +
            "the unwind has finished stands as an unowned panel, and nothing collects it yet " +
            "(#18).",
    )

    val wedgedDockFailsDetach = Flags.boolean(
        "wm.dock.wedged_dock_fails_detach",
        true,
        "Treat a dock still in the tree when the window wait runs out as a failed detach rather " +
            "than a completed one. sway acknowledges a kill once it has asked the client to " +
            "close, so a panel whose process is wedged never unmaps and the wait simply expires " +
            "— and a detach that returns anyway reports a repair it has not made, which is what " +
            "lets the next orphan sweep find the same dock and kill it again. On by default so " +
            "the failure reaches the sweep's aggregate instead of being the one teardown " +
            "failure nothing can see; on also keeps the surface's durable binding, since the " +
            "raise happens before the unbind and forgetting a binding whose panel is still on " +
            "screen strands it. Turn it off where a real panel program is legitimately slower " +
            "to exit than the wait, so the dock comes down a moment later and the failure is " +
            "noise — at the cost of that dock being invisible to the sweep that left it.",
    )

    val forgetBindingOnDetach = Flags.boolean(
        "wm.dock.forget_binding_on_detach",
        false,
        "Drop the surface's durable binding when its dock is torn down. Off by default because " +
            "the design's memory model says the written-down residue outlives the window — " +
            "closing a panel is not the user saying they want a different agent. Turn it on to " +
            "make a dock's lifetime the agent's lifetime.",
    )

    val restingFocus = Flags.enum(
        "wm.focus.resting",
        RestingFocus.APP,
        "Which child of the tab is left focused after dock interaction. sway remembers this " +
            "per container and it decides where a later tab switch lands.",
    )

    val restoreFocusAfterAttach = Flags.boolean(
        "wm.focus.restore_after_attach",
        true,
        "Apply the resting-focus rule at the end of attach, so the very first tab switch " +
            "after attaching already behaves.",
    )

    val socketPath = Flags.string(
        "wm.ipc.socket_path",
        "",
        "Path to sway's IPC socket. Empty means use SWAYSOCK from the environment.",
    )

    val eventsEnabled = Flags.boolean(
        "wm.events.enabled",
        true,
        "Subscribe to sway window events. Turning this off stops orphan handling too, since " +
            "that is driven by the close event.",
    )
}
