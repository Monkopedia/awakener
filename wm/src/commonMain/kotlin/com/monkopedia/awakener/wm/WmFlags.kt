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

/**
 * What the orphan sweep accepts as proof that a node is a dock, before it kills it.
 *
 * Declared narrowest first, and the narrowest is the only one that does not rest on something the
 * desktop can write: every mark in sway's namespace is reachable from `swaymsg`, measured on 1.12.
 */
enum class ReapEvidence {
    /**
     * An entry this process wrote when it stood the dock up itself, and nothing else. The only
     * evidence for a kill that no mark can produce — at the price of never reaping a dock this
     * process merely adopted, which is every dock that outlived an awakener restart.
     */
    STOOD_UP,

    /** A dock mark on the node now, or an entry written when this process stood the dock up. */
    CURRENT,

    /** Whatever enumeration recognises, an adopted node whose mark has since gone included. */
    RECOGNITION,
}

/**
 * Where `resolve` gets the durable key it looks a binding up by.
 *
 * Both values answer from `:registry`, and neither ever reads an *agent* out of the dock table —
 * the table holds no agent and never has. What they differ on is the set of windows `resolve`
 * will answer for at all, and so whether that set depends on this session's state.
 */
enum class ResolveKeySource {
    /**
     * The tree node carrying that `con_id`, straight from `get_tree`.
     *
     * `resolve`'s whole path is then one tree read and one registry lookup, and [DockTable] is
     * not reachable from any of it — which is what lets the note's tripwire be *checked* rather
     * than promised.
     */
    TREE,

    /**
     * The window `surfaces()` reports under that `con_id`, or nothing at all.
     *
     * The previous behaviour. Enumeration filters out docks and the windows an in-flight attach
     * has reserved, so under this a window the table is currently hiding resolves as a Drab
     * whatever the durable registry says — a session-scoped answer to the one question the
     * registry exists to answer durably.
     */
    ENUMERATION,
}

/**
 * Who acquires the successor connection when a compositor session ends under a running manager.
 *
 * The two values are the two answers to #33's "reconnection has no owner": the manager, or the
 * caller. They are not points on a scale — one of them makes a manager span sessions, and the other
 * makes a manager the *name* of one session — so the choice reaches [DockHandle] lifetimes and the
 * dock table alike, and both consequences are stated at the flag.
 */
enum class SessionReconnect {
    /**
     * The manager stays broken after the boundary and the caller builds a successor.
     *
     * The previous behaviour, kept reachable because it is a coherent design rather than a defect:
     * a manager is then one session's, every `con_id` it ever handed out belongs to that session,
     * and a caller that retires it with [SwayWindowManager.close] cannot end up with two managers
     * over one tree (#85).
     */
    NEVER,

    /**
     * The next call that needs the compositor acquires a fresh connection, and the repair collector
     * is restarted on it.
     *
     * On demand rather than eagerly, and that is the design agreement rather than laziness: the
     * product does not act unattended, so a reconnection is one act in reaction to a caller — a
     * hotkey press — and never a retry loop or a schedule. Nothing reconnects on a desktop nobody
     * is using, which is the correct amount of work to do on one.
     */
    ON_DEMAND,
}

/** What a [DockHandle] does when the session its `con_id`s belong to has ended. */
enum class StaleHandle {
    /**
     * Refuse, with [CompositorSessionEnded].
     *
     * `con_id`s are minted from a counter that restarts with the compositor, so after a reconnect
     * the id a handle holds is not merely dead — it names whatever window the new session gave it
     * to, which is why refusing is the default rather than the tidy option.
     */
    REFUSE,

    /**
     * Issue the command anyway, against whatever session is live now.
     *
     * The previous behaviour: before reconnection existed, a stale handle could only reach a dead
     * socket and fail, so nothing had to decide. With a successor connection in place the same code
     * path reaches a live compositor with a dead session's ids, and `detach` on one is a `kill`.
     */
    ACT,
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
            "mark — this prefix followed by whatever wm.dock.mark_scheme says, and nothing " +
            "else — is what " +
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

    val dockMarkScheme = Flags.enum(
        "wm.dock.mark_scheme",
        DockMarkScheme.DOCK_SURFACE_AND_NONCE,
        "What a dock's mark says after the prefix. DOCK_SURFACE_AND_NONCE writes " +
            "<dockCon_id>_for_<surfaceCon_id>_<16 hex digits>, recognised only on the node whose " +
            "con_id it names and only when the trailing field is nonce-shaped. The nonce is " +
            "checked by shape and never by value: the process that reads a mark is routinely a " +
            "later awakener that never saw it written — that is what a mark is for — so a check " +
            "against a remembered value would strand every standing dock on every restart. What " +
            "the shape buys is that nobody arrives at one by accident. What it does not buy is " +
            "forgery resistance, and nothing can: sway sets a mark through RUN_COMMAND on the " +
            "socket swaymsg speaks, with the same parser, so every mark awakener can write a " +
            "hand can write too — measured on sway 1.12, where a hand-run swaymsg wrote a full " +
            "nonce-shaped mark and read it back verbatim, and where marks round-trip unchanged " +
            "to at least 16384 characters. A nonce copied out of `swaymsg -t get_tree` onto " +
            "another window is therefore still a dock mark, and that window is still destroyed " +
            "by the sweep when the con_id after _for_ closes; wm.dock.reap_evidence=STOOD_UP is " +
            "what closes that, at its own stated price. DOCK_AND_SURFACE is the previous " +
            "<dockCon_id>_for_<surfaceCon_id> without the nonce. It keeps the two properties " +
            "that make a mark per-dock and self-checking — a sway mark identifier is globally " +
            "unique, so a mark naming only the surface is a name two docks of one surface both " +
            "want and the second attach silently unmarked the first (#14); and a mark naming the " +
            "node it sits on cannot be a dock mark anywhere else (#15) — and its price is the " +
            "shape a user reaches without meaning to: this prefix, the marked window's own " +
            "con_id, _for_, and any con_id at all is a dock mark, and the sweep destroys that " +
            "window when the con_id after _for_ closes (#35, measured on sway 1.12). SURFACE is " +
            "the original <surfaceCon_id> and is worse again: under it this prefix plus any live " +
            "con_id is a dock mark on whatever window wears it, so a user's own " +
            "'awakener_dock_7' hides that window and the sweep destroys it when node 7 closes, " +
            "which is what #15 is. Both older values are here because a downgrade is the whole " +
            "of the recovery if an upgrade lands while docks are standing, and each is worth its " +
            "price only in a session with no marks under this prefix that awakener did not " +
            "write. This decides reading and writing together, so an upgrade over standing docks " +
            "— or a flip against a live desktop — strands every dock marked under another value: " +
            "those marks stop being recognised, the docks become bindable surfaces a hotkey will " +
            "mint an agent for, and their names are reported through the manager's unrecognised " +
            "dock marks rather than passed over. A stranded dock is never reaped and no scheme " +
            "reads another scheme's mark as a dock mark, so that costs a leak and never a kill: " +
            "close the stranded panels by hand, or flip to the value they were marked under, " +
            "close them, and flip back. Move it with no docks standing.",
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
            "once something takes that mark off it — a hand-run unmark, or, under " +
            "wm.dock.mark_scheme=SURFACE, a second attach on the same surface (#14). Nothing " +
            "withdraws that record, so under MARK_OR_TABLE a node that carried a dock mark of " +
            "its own at any single read is a dock for the life of the process whatever its " +
            "marks say afterwards. MARK_ONLY is the previous behaviour and the debuggable one — the " +
            "whole truth is then in `swaymsg -t get_tree` with nothing held in process memory " +
            "— and it is the lever to reach for if the record is ever suspected of hiding a " +
            "real window, being the only thing that releases one live. It also stops a pending " +
            "attach's reservation from suppressing anything, for the same reason. The record is " +
            "never written to disk and holds no agent, so nothing this flag decides can change " +
            "which agent a surface is bound to: that is the registry's, keyed on what outlives " +
            "the window. What it does decide is which windows are enumerable, and under " +
            "wm.resolve.key_source=ENUMERATION that reaches resolve too — see that flag, which " +
            "defaults to keeping the record out of resolve's path entirely.",
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
            "Recognising a dock by its mark records it, and that record is never withdrawn, so " +
            "recognition outlives the mark that produced it. A genuine window carrying a user's " +
            "own mark that happens to be shaped exactly like that window's dock mark (#15's " +
            "residual) is therefore hidden from enumeration for the life of the process even " +
            "after the mark is removed. Being hidden is recoverable — " +
            "wm.dock.recognition=MARK_ONLY, " +
            "or an awakener restart — and being killed is not, so CURRENT will not reap on that " +
            "latched recognition alone: it kills only a node carrying a dock mark at the moment " +
            "of the sweep, or one this process recorded when it stood the dock up itself. That " +
            "bounds the latch and not the mark, and it is worth being exact about which: while " +
            "that user's mark is still on the window it is precisely the evidence CURRENT asks " +
            "for, so CURRENT reaps on it and the window is destroyed. Removing the mark is what " +
            "this flag makes survivable. See wm.dock.mark_scheme, which is where that residual " +
            "and its cost are stated. What " +
            "that costs is the case with neither — a dock adopted after a restart whose mark " +
            "something has since taken off it, which stays out of enumeration but is left " +
            "standing when its surface closes, to be closed by hand. Under the default " +
            "wm.dock.mark_scheme that needs a hand-run unmark; under SURFACE a second attach on " +
            "the same surface does it (#14). RECOGNITION reaps " +
            "everything enumeration calls a dock, which closes that gap at the price of the " +
            "user's window. STOOD_UP goes the other way and is the only value under which no " +
            "mark can cost a window at all: it reaps a node only on an entry this process wrote " +
            "when it stood that dock up, which is evidence held in awakener's own memory rather " +
            "than in a namespace the desktop writes into. Every mark sway holds is writable from " +
            "swaymsg, measured on 1.12, so this is the only thing that makes a forged mark " +
            "harmless rather than merely unlikely. Its price is what the mark is for: a dock " +
            "that outlived an awakener restart is adopted from its mark and was never stood up " +
            "by this process, so it is never reaped — its panel stands when its surface closes " +
            "and has to be closed by hand. That is a leaked panel against a destroyed window, " +
            "and CURRENT stays the default because the default wm.dock.mark_scheme already makes " +
            "the destroyed window need a mark nobody writes by accident.",
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
            "the dock's app_id until its deadline — wm.wait.reservation_grace_ms, 5s by " +
            "default. Two things it does not reach " +
            "either: a no_focus rule, which sway cannot revoke, and the dock program itself, " +
            "which is already exec'd by the time anything can fail — a dock that maps after " +
            "the unwind has finished stands as an unowned panel. A repair collector exists now " +
            "and does not reach that panel: it carries no mark and is in no table, so the sweep " +
            "does not see it as a dock. What is missing is the claim the design note specifies, " +
            "which attach does not file and nothing reads (#32).",
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

    val sweepOnClose = Flags.boolean(
        "wm.repair.sweep_on_close",
        true,
        "Sweep for orphaned docks when the compositor reports a window closing. sway leaves a " +
            "dock standing when the surface it shares a tab with dies, and the close event is " +
            "the exact moment that happens, so this is what turns wm.dock.orphan_policy from a " +
            "setting into a behaviour. Off is the previous behaviour, in which the sweep existed " +
            "and nothing ran it: orphaned panels then accumulate until something calls the sweep " +
            "itself, and nothing in awakener does. Turn it off to drive repair by hand, or to " +
            "leave the tree damage visible while diagnosing without also giving up the session " +
            "boundary — the collector still watches for the compositor going away either way. " +
            "Nothing is swept on a timer: this reacts to an event or it does not run.",
    )

    val sweepFailure = Flags.enum(
        "wm.repair.sweep_failure",
        SweepFailure.CONTINUE,
        "What a sweep that raises does to the collector driving it. A sweep already isolates " +
            "each dock and raises an aggregate naming the ones that would not come down, so by " +
            "the time this decides anything the repair has been attempted on every orphan. " +
            "CONTINUE records the failure and keeps collecting, because the collector is the only " +
            "thing that will sweep the *next* orphan and one wedged panel is a poor reason to " +
            "stop repairing the desktop — the same argument that made the sweep isolate docks " +
            "from each other, one level up. STOP lets the failure out of the collector, which " +
            "ends the collection and its event subscription: louder, and reasonable if a failing " +
            "sweep is treated as a defect to be looked at rather than a wedged panel to be lived " +
            "with. Under STOP nothing repairs anything afterwards, and nothing restarts the " +
            "collector. Either way the failure is reported through the manager's repair status, " +
            "and under STOP wm.repair.collector_failure decides where the exception itself goes.",
    )

    val collectorFailure = Flags.enum(
        "wm.repair.collector_failure",
        CollectorFailure.REPORT,
        "Where a repair collector's failure goes when it is not the compositor session ending. " +
            "The collector is started by the manager's constructor on the scope the caller " +
            "handed it, so a failure that simply escaped would land in that scope — cancelling " +
            "the caller's unrelated coroutines, since an ordinary Job() scope is not a " +
            "supervisor — and reach nothing that was asking about repair. The three ordinary " +
            "ways to get here are the IPC connect raising because sway is not up or SWAYSOCK is " +
            "stale, sway refusing the event subscription, and an event payload that will not " +
            "parse. REPORT records it on the manager's repair status and ends the collection " +
            "there: the caller keeps its scope, and this manager stops repairing — which it did " +
            "either way, since the subscription behind the collector is gone and nothing " +
            "reconnects it. PROPAGATE records it and rethrows, so the failure reaches the scope: " +
            "louder, and reasonable if a manager that has stopped repairing should take the " +
            "process with it, but a scope that gets this must tolerate a child failing. Neither " +
            "restarts the collector, and under both the session boundary now passes unobserved, " +
            "so the dock table is no longer discarded when the compositor goes away. " +
            "wm.session.reconnect does not rescue this either, and deliberately: it restarts a " +
            "collector that ended at the *boundary*, where a successor connection is exactly the " +
            "missing thing, and none of these three failures — a connect that raised, a " +
            "subscription sway refused, a payload that will not parse — is one a fresh " +
            "connection is known to fix. Reconnecting on them would be a retry loop wearing " +
            "another name.",
    )

    val resolveKeySource = Flags.enum(
        "wm.resolve.key_source",
        ResolveKeySource.TREE,
        "Where resolve gets the durable key it looks a binding up by. Either way the answer " +
            "comes from :registry — the dock table holds no agent — so this cannot change which " +
            "agent a bound surface has. What it changes is which windows resolve will answer " +
            "for. TREE reads the node out of get_tree and derives the key from it, so resolve's " +
            "whole path is one tree read and one registry lookup and the session-scoped dock " +
            "table is not reachable from it at all: a window is looked up by what outlives it, " +
            "which is the property the registry exists for, and the design note's tripwire — " +
            "'if the table ever appears in resolve's path, the durability story has rotted' — " +
            "becomes a check somebody can run rather than a promise. ENUMERATION is the previous " +
            "behaviour: the key comes from the enumerating form, which filters out docks and the " +
            "windows " +
            "an in-flight attach has reserved, so anything the table is hiding resolves as a " +
            "Drab however durably it is bound — a surface hidden by a latched recognition (see " +
            "wm.dock.reap_evidence) or by a reservation under a shared dock app_id then reads as " +
            "unbound, and a caller acting on that mints a second agent for a surface that " +
            "already has one. Its one property TREE does not have is that resolve answers " +
            "nothing for a dock: under TREE a dock is an ordinary node and resolves to whatever " +
            "the registry holds under the key its app_id gives, which is nothing unless " +
            "something bound that key. Callers get their surface ids from resolve's null-argument " +
            "form, which excludes docks under both values.",
    )

    val mapWaitMs = Flags.long(
        "wm.wait.map_ms",
        5_000,
        "How long attach waits for the dock window to appear before failing and unwinding. " +
            "sway acknowledges an exec with nothing that says whether the program will ever " +
            "map, so this deadline is the whole of what bounds an attach. Previously one " +
            "hard-coded 5s shared with two unrelated waits, which is why it is here: a real " +
            "panel program slower to start than 5s could not be accommodated without a rebuild. " +
            "Raising it lengthens the tree lock an attach holds, since the wait happens inside " +
            "it, so every other attach and detach queues behind the slowest dock. Lowering it " +
            "risks the case the unwind exists for — a dock that maps just after the deadline, " +
            "which is then a window the attach spawned and did not identify. Zero fails every " +
            "attach immediately.",
        requires = Flags.atLeast(0L),
    )

    val unmapWaitMs = Flags.long(
        "wm.wait.unmap_ms",
        5_000,
        "How long a kill waits for the window to leave the tree before treating it as wedged. " +
            "sway acknowledges a kill once it has asked the client to close, not when the " +
            "window unmaps, so this is what turns the acknowledgement into a fact. It bounds " +
            "detach, the orphan sweep and an unwind's compensating kill alike, and a detach " +
            "holds the tree lock across it. Its consequence is wm.dock.wedged_dock_fails_detach's " +
            "— expiring is what makes a dock 'still standing' — so shortening it turns a panel " +
            "that is merely slow to exit into a reported failure sooner.",
        requires = Flags.atLeast(0L),
    )

    val reservationGraceMs = Flags.long(
        "wm.wait.reservation_grace_ms",
        5_000,
        "How long an attach's app_id reservation keeps suppressing windows if nothing evicts " +
            "it. attach evicts its own in a finally, which is what normally ends one, so this " +
            "only bounds a reservation whose attach died without running that — a process " +
            "killed mid-attach leaves no other way to clear it, and until this expires a stale " +
            "one hides every window under the dock's app_id. This value is that bound and " +
            "nothing else is, which is why the eviction in attach's finally is unconditional " +
            "rather than left to it. It has to be at or " +
            "above wm.wait.map_ms, and that is a declared constraint rather than advice: a " +
            "grace shorter than the map deadline expires while the attach it belongs to is " +
            "still waiting, which reopens the gap wm.dock.pending_suppression closes — " +
            "enumeration reporting the agent panel as bindable. One constant held that by " +
            "construction until the two were separated, so it is stated here because splitting " +
            "them is what made it violable. Read once, when the reservation is filed.",
        requires = Flags.atLeast(0L),
    )

    /**
     * The pair rule the split created.
     *
     * Neither value is wrong alone — a short grace and a long deadline are each defensible — so
     * this reports rather than degrading either, which is exactly what [Flags.constraint] is
     * for. Keyed on the grace because that is the one a reader should move: lowering the map
     * deadline to match would shorten every attach, which is not what anybody setting a grace
     * meant to do.
     */
    val reservationOutlastsMap = Flags.constraint(
        reservationGraceMs.key,
        "at least wm.wait.map_ms",
    ) { config ->
        val grace = config[reservationGraceMs]
        val map = config[mapWaitMs]
        if (grace >= map) {
            null
        } else {
            "wm.wait.reservation_grace_ms ($grace) is below wm.wait.map_ms ($map), so an " +
                "attach's reservation expires while that attach is still waiting for its dock " +
                "— which hands the agent panel back to surfaces() as a bindable window for the " +
                "rest of the map wait"
        }
    }

    val pollSpinMs = Flags.long(
        "wm.wait.poll_spin_ms",
        250,
        "How long a wait on the tree re-reads it as fast as the socket will answer before " +
            "falling back to wm.wait.poll_interval_ms. attach polls get_tree rather than " +
            "listening for the new event, deliberately, so that it keeps working with " +
            "wm.events.enabled off — but polling with nothing between the reads cost 6,637 to " +
            "11,085 round trips per second against headless sway 1.12, 59% of a compositor core " +
            "and 41% of a client core, so a 5s deadline that expired spent roughly 33,000 round " +
            "trips and 2.9s of compositor CPU to learn that a dock did not appear (#49). " +
            "Spinning is not free of value: the same measurement found it detects a dock about " +
            "10ms sooner than a 25ms poll does — median 16.5ms against 26.1ms over 8 alternated " +
            "trials — and that 10ms is hotkey responsiveness. So the default spins for the " +
            "quarter second in which a dock almost always maps and paces itself afterwards, " +
            "which is where the round trips stop being worth anything. Counted at the socket, a " +
            "5s deadline that expires costs 1,719 tree reads at the stock defaults against about " +
            "27,500 spun. Set it to zero to pace from the first read, or above the wait itself " +
            "to get the old pure spin back exactly — that is the value to use for the previous " +
            "behaviour, rather than a zero interval.",
        requires = Flags.atLeast(0L),
    )

    val pollIntervalMs = Flags.long(
        "wm.wait.poll_interval_ms",
        25,
        "How long a wait on the tree sleeps between reads once wm.wait.poll_spin_ms is up. " +
            "This is what bounds the cost of a wait that is going to expire: at the default it " +
            "is about 40 reads a second instead of the ten thousand a bare spin manages. It is " +
            "also the worst-case latency added to detecting a dock that maps after the spin " +
            "window, which is why the spin exists rather than this being the whole policy. Zero " +
            "does not sleep and is not a yield — kotlinx's delay returns without suspending at " +
            "or below zero — so it does not restore the old busy-poll, it removes the pacing " +
            "and leaves the tree read as the only suspension point in the loop. That is " +
            "marginally more aggressive than the yield the old loop had. Use " +
            "wm.wait.poll_spin_ms above the wait for the previous behaviour exactly.",
        requires = Flags.atLeast(0L),
    )

    val socketPath = Flags.string(
        "wm.ipc.socket_path",
        "",
        "Path to sway's IPC socket. Empty means use SWAYSOCK from the environment. Set, it is " +
            "authoritative: wm.ipc.socket_discovery is not consulted for it, because a path an " +
            "operator typed names the compositor they meant and falling back from it would talk " +
            "to a different one.",
    )

    val socketDiscovery = Flags.boolean(
        "wm.ipc.socket_discovery",
        true,
        "Find sway's socket in XDG_RUNTIME_DIR when SWAYSOCK does not name a reachable one. " +
            "This is what makes wm.session.reconnect mean anything on an ordinary desktop, and " +
            "the reason is that SWAYSOCK cannot be re-read: a process's environment is fixed at " +
            "exec, and with SWAYSOCK unset sway names its socket " +
            "\$XDG_RUNTIME_DIR/sway-ipc.<uid>.<pid>.sock — so the successor session's path " +
            "differs from the dead one's by a pid awakener was never told. Without this a " +
            "long-lived awakener can only ever reconnect where an operator pinned " +
            "wm.ipc.socket_path or where a supervisor re-execs it. Consulted only after the " +
            "named socket fails to connect, so a live desktop's behaviour is unchanged, and only " +
            "over sockets in this user's own runtime directory — it widens what the manager will " +
            "talk to from one path to whichever sway of yours is up, which is the point and is " +
            "worth knowing. Newest first, since that is the successor. Off restores the previous " +
            "behaviour exactly: SWAYSOCK or nothing.",
    )

    val sessionReconnect = Flags.enum(
        "wm.session.reconnect",
        SessionReconnect.ON_DEMAND,
        "Who acquires the successor connection when the compositor session ends under a running " +
            "manager. ON_DEMAND makes it the manager's: the boundary closes the dead connection " +
            "and discards the dock table as it always did, and then the next call that needs the " +
            "compositor — an enumeration, a resolve, an attach — opens a fresh one and restarts " +
            "the repair collector on it. Nothing happens on a schedule and nothing retries in a " +
            "loop: a reconnection is one act in reaction to a caller, which is the design's rule " +
            "about unattended action and is why there is no backoff to configure. So a desktop " +
            "whose sway has restarted repairs itself at the next hotkey press and not before, " +
            "and a manager whose next call arrives before sway is back raises from that call " +
            "rather than reconnecting behind it. What it costs is that a manager now spans " +
            "sessions, which makes every con_id a caller is holding a fact about a session that " +
            "may be over — see wm.session.stale_handles, which is what stops that costing a " +
            "window. NEVER is the previous behaviour: the manager stays broken after the " +
            "boundary, every call against it raises, and the caller retires it with close() and " +
            "builds a successor. That is a coherent design and not a defect — under it a manager " +
            "is one session's, and the overlap two managers over one tree produce cannot arise " +
            "from a restart at all. Neither value restarts a collector that ended for any other " +
            "reason: see wm.repair.collector_failure, none of whose three failures a successor " +
            "connection fixes. With wm.events.enabled off nothing observes the boundary, so " +
            "nothing reconnects under either value.",
    )

    val staleHandles = Flags.enum(
        "wm.session.stale_handles",
        StaleHandle.REFUSE,
        "What a dock handle does once the compositor session its con_ids came from has ended. " +
            "sway allocates con_ids from a counter that restarts with the compositor — measured " +
            "across two sequential sessions under one client, session A's dock id was session " +
            "B's browser — so a handle that outlives its session does not name a dead window, it " +
            "names somebody else's live one. REFUSE fails focus and detach on such " +
            "a handle with the same compositor-agnostic CompositorSessionEnded the change stream " +
            "reports, which tells a caller holding it exactly what it is holding. ACT is the " +
            "previous behaviour and was harmless only because it was unreachable: before " +
            "wm.session.reconnect existed a stale handle could reach nothing but a dead socket, " +
            "so it failed on its own. With a successor connection in place the same call reaches " +
            "a live compositor carrying a dead session's ids, and detach's first act is a kill. " +
            "Turn it on only where the ids are known to still mean what they meant.",
    )

    val closeWaitMs = Flags.long(
        "wm.manager.close_wait_ms",
        5_000,
        "How long close() waits for a retired manager's collector to actually stop. Cancelling " +
            "only *asks*: a collector inside a sweep keeps sweeping until the cancellation " +
            "reaches a suspension point, and a close that returned before then would leave the " +
            "caller building a replacement while the predecessor was still deciding what to " +
            "reap — which is the overlap close() exists to remove, narrowed rather than closed. " +
            "So the default waits, having first closed the command connection so that a " +
            "collector blocked in a socket read is woken rather than waited on. A wait that " +
            "expires raises, because a manager that has been asked to stop and has not is " +
            "precisely the state that must not pass silently. Zero does not wait at all and is " +
            "the previous behaviour — the caller gets the cancellation and no promise about when " +
            "it lands — and is the value to use where a close must not be able to block a hotkey.",
        requires = Flags.atLeast(0L),
    )

    val closeReapsDocks = Flags.boolean(
        "wm.manager.close_reaps_docks",
        false,
        "Tear this manager's docks down when it is closed. Off by default because a mark is " +
            "what a successor adopts a standing dock by: an awakener restart over a live sway is " +
            "the case the marks exist for, and a close that killed every panel would make each " +
            "restart cost the user their agent panels and the residue on screen with them. Off, " +
            "a closed manager leaves the tree exactly as it found it and the docks are adopted " +
            "by whatever comes next, or closed by hand if nothing does. On is for a shutdown " +
            "with nothing coming after it, where a standing panel nothing holds a handle to is a " +
            "leak rather than an inheritance. It tears down every dock in this manager's table, " +
            "adopted ones included, and a teardown that fails is raised once the rest have been " +
            "attempted — the retirement itself still completes, since a wedged panel must not " +
            "leave a collector running.",
    )

    val eventsEnabled = Flags.boolean(
        "wm.events.enabled",
        true,
        "Subscribe to sway window events. This is the only connection that notices anything " +
            "promptly, so turning it off costs three things rather than one. Orphan handling " +
            "stops, since the sweep is driven by the close event and nothing else drives it. " +
            "Nothing reads a compositor session ending either, so the dock table — whose keys are " +
            "con_ids from a counter that restarts with the compositor — is never discarded, and " +
            "an entry that outlives a sway restart names whatever window holds that id next: " +
            "surfaces() takes no lock and would answer from that stale table against a fresh " +
            "tree, hiding a genuine window. And the command connection learns of the boundary " +
            "only at its next use, which cannot make a command wrong — a reply that arrives " +
            "describes the session that produced it — but is late. Attaching a dock is " +
            "unaffected: it polls the tree rather than listening, deliberately, so it keeps " +
            "working with events off. Nothing reconnects either, for the same reason and not as " +
            "a fourth cost: wm.session.reconnect acquires a successor connection when the " +
            "boundary is *observed*, and with events off it never is.",
    )
}
