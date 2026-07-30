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
        "Focus the dock as soon as it appears. Right for a hotkey invocation (you are about " +
            "to type at the agent), wrong for a dock created proactively for a surface.",
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
            "unmanaged. PER_SURFACE_APP_ID makes the name itself unique, so nothing else can " +
            "answer the wait, and it additionally scopes the no_focus rule to one dock instead " +
            "of to every dock ever spawned — at the cost of requiring the dock command to " +
            "accept the name, and of one permanent no_focus rule per attach, since sway " +
            "cannot revoke one.",
    )

    val dockMarkPrefix = Flags.string(
        "wm.dock.mark_prefix",
        "awakener_dock_",
        "Prefix for the sway mark identifying a dock. Docks are real tree nodes, so this " +
            "mark is what keeps them out of surface enumeration and focus scripting.",
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
