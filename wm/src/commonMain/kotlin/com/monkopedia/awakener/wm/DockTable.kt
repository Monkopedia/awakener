package com.monkopedia.awakener.wm

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * An attach that has spawned a dock it cannot name yet.
 *
 * A `con_id` does not exist until the window maps, so between the `exec` and the map there is
 * nothing to key an entry on. The `app_id` is the only predicate that exists earlier — the same
 * one the `no_focus` rule matches on — so that is what an attach in flight reserves.
 */
internal data class DockReservation(
    val appId: String,
    /**
     * Windows already reporting [appId] when the attach began.
     *
     * Part of the record rather than something a reader recomputes: it is the snapshot `attach`
     * takes inside the tree-edit lock, and a reader outside that lock has no way to reconstruct
     * which windows were already there.
     */
    val standing: Set<Long>,
    /**
     * When this reservation stops suppressing anything.
     *
     * `attach` evicts its own reservation in a `finally`, which is what normally ends one; this
     * bounds a reservation whose attach died without running that.
     */
    val deadline: TimeSource.Monotonic.ValueTimeMark,
) {
    fun covers(node: Node): Boolean =
        node.appId == appId && node.id !in standing && !deadline.hasPassedNow()
}

/** Where an entry's claim that a node is a dock came from. */
internal enum class DockOrigin {
    /** This process spawned the window and marked it, inside the tree-edit lock. */
    STOOD_UP,

    /** A read found the mark on a node the table did not know, and recorded what it read. */
    ADOPTED,
}

/**
 * What the table knows about one dock.
 *
 * [origin] is here because the orphan sweep asks a different question than enumeration does.
 * Enumeration only needs to know the node is a dock; a sweep is about to *kill* it, and an
 * adopted entry is a recognition this process latched at some past read rather than evidence
 * that exists now. See [WmFlags.reapEvidence].
 */
internal data class DockEntry(val surface: SurfaceId, val origin: DockOrigin)

/** The table as one value, so a reader sees a consistent view without taking a lock. */
internal data class DockTableSnapshot(
    /**
     * Dock `con_id` → what is known about that dock.
     *
     * The surface it belongs to and where the claim came from, and nothing else, because nothing
     * reads more. The `app_id` an earlier draft carried had no reader, and an adopted entry could
     * not supply one anyway: a dock recognised from its mark is whatever node wears the mark, and
     * sway's `app_id` is absent on an xwayland window.
     */
    val entries: Map<Long, DockEntry> = emptyMap(),
    val reservations: List<DockReservation> = emptyList(),
    /**
     * `app_id`s a `no_focus` rule has already been issued for.
     *
     * The one thing here that is a record of *compositor* state rather than of awakener's own,
     * and it is here because sway offers no way to read the rule list back. It is bounded by the
     * same session boundary as everything else in this table, and correctly so: a rule cannot
     * outlive the compositor that holds it, so a new sway session has none.
     */
    val focusRules: Set<String> = emptySet(),
) {
    /** Whether an attach in flight has reserved the `app_id` [node] reports. */
    fun reserves(node: Node): Boolean = reservations.any { it.covers(node) }
}

/**
 * Which nodes are docks, and whose.
 *
 * Holds the docks this process stood up *and* the ones it adopted — a marked node the table did
 * not know is recorded the first time anything reads the tree, not merely answered about. That
 * distinction is the whole of an adopted dock's durability: a mark lives in a namespace shared
 * with the user, so it can be moved or removed by a hand that is not awakener's, and a recognition
 * that leaves no record behind hands the agent panel back as a bindable surface the moment it is.
 * Under the previous [DockMarkScheme.SURFACE] mark awakener did that to itself — a second attach
 * on one surface took the mark off the first dock (#14) — which is what the default scheme fixes.
 *
 * **Recording is one-way.** Nothing here un-records a node whose mark went away, so recognition
 * outlives the evidence that produced it: a genuine application window that carried a mark shaped
 * exactly like its own dock mark — somebody else's mark, #15's residual and then #35's — at any
 * single enumeration stays out of `surfaces()` for the life of this process, and `swaymsg unmark`
 * does not bring it back. `wm.dock.recognition=MARK_ONLY` does, live, and so does restarting
 * awakener; those are the whole of the recovery. What the latch is *not* allowed to do is destroy
 * that window, which is why the orphan sweep asks [DockEntry.origin] rather than trusting the
 * entry — see [WmFlags.reapEvidence].
 *
 * That bounds the **latch** and nothing wider. While a mark is still on the window it is evidence
 * the sweep accepts, whoever wrote it, and the sweep destroys that window when the surface the mark
 * names closes. Recording is what makes the *hiding* outlast the mark; it is not what makes the
 * kill possible. What bounds the kill is the shape the mark has to have — see
 * [DockMarkScheme.DOCK_SURFACE_AND_NONCE], which is why one cannot be written by accident — and,
 * for a desktop that wants no tree evidence to be destructive at all, [ReapEvidence.STOOD_UP].
 *
 * Authoritative for exactly that one predicate. It never says a window exists — the tree keeps
 * that, and a node the tree has dropped is simply never asked about — and **nothing here is
 * reachable from `resolve`**, which derives its key from the tree and answers from the durable
 * registry: a `con_id` is meaningless after a reboot and the binding it resolves is not.
 *
 * That last clause is the note's tripwire, and it is worth stating in the form it can be
 * *checked* in, because for a while it could not be. It read "the table is never consulted by
 * `resolve`", and `resolve` reached its key through `surfaces()`, which enumerates — so the
 * check fired on code that was never wrong about durability, since no agent is held here and the
 * answer was always `:registry`'s (#52). What was true is narrower and is what the wording now
 * says: the *set of windows* `resolve` would answer for depended on this session's table, so a
 * surface the table was hiding read as unbound however durably it was bound. `resolve` now takes
 * the tree route, so the property is structural — grep `resolve` and no path from it arrives
 * here. `wm.resolve.key_source=ENUMERATION` is the one thing that puts the table back in that
 * path, and it says so.
 *
 * Deliberately not persisted, and deliberately no longer lived than the IPC connection it was
 * built against. sway allocates `con_id`s from a counter that restarts with the compositor, so an
 * entry that outlived a sway restart would name whatever window happens to hold that id next.
 * [discard] is that bound, and `SwayWindowManager`'s repair collector is what calls it: #20 made
 * the boundary observable by failing `changes` with `CompositorSessionEnded`, and the collector is
 * what reacts. Nothing here bounds the table by *time* — a manager whose events are switched off
 * has no promptly-detecting connection and so no trigger at all, which is stated in
 * `WmFlags.eventsEnabled` rather than worked around.
 *
 * Every mutation replaces the snapshot atomically, so [snapshot] blocks on nothing and enumeration
 * never queues behind an attach.
 *
 * Nothing evicts the entry of a dock the user closed outside `detach`. The note's rule for that
 * case is "evict it, silently"; this never asks, which is observationally the same thing inside a
 * session — sway does not recycle a `con_id` — but it does mean entries accumulate for the life
 * of the process, one per dock ever stood up or adopted.
 */
internal class DockTable {
    private val state = MutableStateFlow(DockTableSnapshot())

    fun snapshot(): DockTableSnapshot = state.value

    fun reserve(appId: String, standing: Set<Long>, grace: Duration): DockReservation {
        val reservation =
            DockReservation(appId, standing, TimeSource.Monotonic.markNow() + grace)
        state.update { it.copy(reservations = it.reservations + reservation) }
        return reservation
    }

    fun release(reservation: DockReservation) =
        state.update { it.copy(reservations = it.reservations - reservation) }

    fun record(dock: SurfaceId, surface: SurfaceId, origin: DockOrigin) =
        state.update { it.copy(entries = it.entries + (dock.raw to DockEntry(surface, origin))) }

    fun forget(dock: SurfaceId) =
        state.update { it.copy(entries = it.entries - dock.raw) }

    /**
     * Records that a `no_focus` rule now stands for [appId].
     *
     * A set rather than a count because the point is that the second rule is never issued: sway
     * cannot revoke one, so every rule after the first changes nothing and outlives everything.
     */
    fun recordFocusRule(appId: String) =
        state.update { it.copy(focusRules = it.focusRules + appId) }

    /**
     * Throws the whole table away — every field of it — because the session it described has
     * ended.
     *
     * Discarded rather than repaired, and that is the whole of the rule. Every entry key is a
     * `con_id`, and sway allocates those from a counter that restarts with the compositor: a fresh
     * session hands out 5, 6, 7 again, densely and from the moment it starts. So a surviving entry
     * does not merely go stale, it *collides* — measured across two sequential sway sessions under
     * one client, session A's dock id was session B's browser — and a collision here hides a
     * genuine window from enumeration for the life of the process. Neither of the table's own
     * rules catches it: the tree veto is "table says dock, tree has no such node", and after a
     * restart the tree does have a node at that id; the recognition union then makes it a false
     * *positive*, which is the direction the union deliberately biases toward.
     *
     * The other two go for reasons of their own rather than by association. A reservation is keyed
     * on `app_id` and so cannot collide, but it belongs to an attach that was talking to the dead
     * compositor and can no longer complete. [DockTableSnapshot.focusRules] is the one field that
     * records *sway's* state rather than awakener's, and it is exactly wrong across the boundary:
     * a `no_focus` rule cannot outlive the compositor holding it, so keeping the record would
     * suppress the next session's first attach from issuing one it genuinely needs.
     *
     * What this does not do is make the manager usable again: the connection its commands ride on
     * is still the dead one. Emptying the table is the half of the boundary that is ours; acquiring
     * a successor connection is reconnection, which is not designed (#33).
     */
    fun discard() {
        state.update { DockTableSnapshot() }
    }
}

/** What a node's marks say about the surface it is a dock for. */
internal data class DockMarkReading(
    val surface: SurfaceId?,
    /**
     * Marks under the dock prefix that are not this node's dock mark.
     *
     * A user's own mark, in other words: sway's mark namespace is one global, user-facing set and
     * `mark notes` is an ordinary thing to have bound to a key. Such a node is not a dock, so it
     * is reported here and left enumerable rather than hidden.
     *
     * Named for the question rather than for the parse: this holds a mark that does not parse at
     * all *and* one that parses perfectly while naming a different node — see [dockMark], where
     * refusing the second is what stops a user's mark from hiding their window. It is what
     * `SwayWindowManager.unrecognisedDockMarks` reports, and the two names say the same thing on
     * purpose.
     */
    val unrecognised: List<String>,
)

/** What a dock's mark names. */
enum class DockMarkScheme {
    /**
     * The dock's own `con_id`, the surface's, and a nonce this attach drew:
     * `<prefix><dockId>_for_<surfaceId>_<16 hex digits>`.
     *
     * Everything [DOCK_AND_SURFACE] does, plus the one thing it does not: a shape nobody writes by
     * accident. It is verified by **shape and not by value** — sixteen lowercase hex digits, that
     * is the whole check — which is what lets a process that did not draw the nonce still read the
     * mark. That property is not a detail: a mark exists to survive an awakener restart while sway
     * keeps running, so a successor that had to recognise the *value* would strand every standing
     * dock on every restart.
     *
     * **It is not unforgeable, and nothing can be.** sway offers exactly one way to set a mark —
     * `RUN_COMMAND` — on the socket `swaymsg` uses, with the same parser, so every mark awakener
     * can write a hand can write too. Measured on sway 1.12: a hand-run
     * `swaymsg '[con_id=5] mark --add awakener_dock_5_for_5_9f3a1c7e0b2d8465'` returns
     * `success:true` and the mark reads back verbatim. Nor is there a structural substitute — the
     * tree's layout is `swaymsg`'s to write as well. So what this buys is the *accident*: a nonce
     * copied out of `swaymsg -t get_tree` and re-marked onto another window still reaches the
     * sweep, and [ReapEvidence.STOOD_UP] is the flag that closes that, at its own price.
     */
    DOCK_SURFACE_AND_NONCE,

    /**
     * The dock's own `con_id` and the surface's: `<prefix><dockId>_for_<surfaceId>`.
     *
     * Unique per dock by construction, which is the whole point — sway's mark identifiers are one
     * global namespace and marking a second container with an existing identifier *removes it from
     * the first*, so a mark naming only the surface is a string two docks of one surface both want
     * (#14).
     *
     * It also makes the mark self-validating: it says which node it belongs on, so a mark on any
     * other node is not a dock mark at all. That is what narrows #15 from "any `<prefix><live
     * con_id>`, on any window" to a mark whose user wrote their own window's `con_id` into it.
     *
     * **The narrowing is of the trigger, not of the consequence**, and that residual is why this is
     * no longer the default: a mark that passes the self-check is on the node when the sweep looks,
     * so `wm.dock.reap_evidence=CURRENT` is satisfied, and the sweep kills that window when the
     * `con_id` after `_for_` closes. Measured on sway 1.12 (#35) — `SwayBindingTest.a user mark
     * naming its own window and a dead con_id no longer costs that window` drives exactly that
     * shape and is red against `0e2446b7`.
     */
    DOCK_AND_SURFACE,

    /**
     * The surface's `con_id` alone: `<prefix><surfaceId>`. The original behaviour, with every hole
     * the two values above close still open — so under it any `<prefix><live con_id>` the user has
     * written on any window of their own is a dock mark, and the sweep destroys that window when
     * the `con_id` in it closes. Measured on sway 1.12, which is what #15 is.
     */
    SURFACE,
}

/** Separates the two ids in a mark that names the dock as well as the surface. */
private const val DOCK_MARK_INFIX = "_for_"

/** How many hex digits a [DockMarkScheme.DOCK_SURFACE_AND_NONCE] mark ends in. */
private const val NONCE_LENGTH = 16

/**
 * A fresh nonce for one dock's mark.
 *
 * Per dock rather than per process, because a per-process nonce would be a field with no reader:
 * the only question it could answer — "did *this* awakener stand that dock up" — is the one
 * [DockOrigin.STOOD_UP] already answers, from memory, without depending on a string the desktop
 * can write.
 *
 * Sixty-four bits, from the ordinary random source rather than a cryptographic one, because what
 * it has to survive is a coincidence and not an adversary — see [DockMarkScheme] for why an
 * adversary is not on the table at all.
 */
private fun newDockMarkNonce(): String =
    Random.nextLong().toULong().toString(16).padStart(NONCE_LENGTH, '0')

/** Whether [this] is the sixteen lowercase hex digits a dock mark's nonce field is. */
private fun String.isDockMarkNonce(): Boolean =
    length == NONCE_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

/**
 * The mark [dock] carries to say it is [surface]'s dock.
 *
 * [nonce] is a parameter only so that a test can write a stable forgery; production draws one per
 * call, and the schemes that carry no nonce ignore it.
 */
internal fun dockMarkFor(
    dock: SurfaceId,
    surface: SurfaceId,
    prefix: String,
    scheme: DockMarkScheme,
    nonce: String = newDockMarkNonce(),
): String = when (scheme) {
    DockMarkScheme.DOCK_SURFACE_AND_NONCE ->
        "$prefix${dock.raw}$DOCK_MARK_INFIX${surface.raw}_$nonce"
    DockMarkScheme.DOCK_AND_SURFACE -> "$prefix${dock.raw}$DOCK_MARK_INFIX${surface.raw}"
    DockMarkScheme.SURFACE -> "$prefix${surface.raw}"
}

/**
 * The one dock-mark predicate: whether this node carries the mark [dockMarkFor] would write for
 * it, and if so which surface that mark names.
 *
 * Shared by enumeration and by the orphan sweep so that the two cannot disagree about what the
 * prefix identifies — when they did, a window carrying `awakener_dock_notes` was hidden from
 * enumeration by one and skipped by the other, leaving it unreachable by any code path and
 * reported by none (#15).
 *
 * Under both schemes that name the dock, the node has to be the one the mark names. That is the
 * rest of #15: a mark is a string in a namespace the user writes into too, so the question worth
 * asking is not "does this look like a dock mark" but "does this look like *this node's* dock
 * mark", and only awakener knows a node's `con_id` at the moment it marks it. Under the default
 * scheme the mark must also end in a nonce-shaped field, which is what makes the shape one nobody
 * arrives at by accident (#35); the *value* is never checked, because the reader is routinely a
 * later awakener that never saw it written.
 *
 * Anything else under the prefix is reported through [DockMarkReading.unrecognised] rather than
 * hidden. That covers a mark whose suffix is not ids at all, and — after an awakener upgrade over
 * standing docks, or a [DockMarkScheme] flip — a mark written under the other scheme, which is
 * how such a dock is diagnosable rather than merely lost.
 *
 * A node carrying more than one dock mark of its own takes the first; nothing awakener does
 * produces that, since it writes one mark per dock and each names the dock it is on.
 */
internal fun Node.dockMark(prefix: String, scheme: DockMarkScheme): DockMarkReading {
    var surface: SurfaceId? = null
    val unrecognised = mutableListOf<String>()
    for (mark in marks) {
        if (!mark.startsWith(prefix)) continue
        val boundTo = boundSurface(mark.removePrefix(prefix), scheme)
        when {
            boundTo == null -> unrecognised += mark
            surface == null -> surface = boundTo
        }
    }
    return DockMarkReading(surface, unrecognised)
}

/** The surface a dock mark's [suffix] names, or null if it is not this node's dock mark. */
private fun Node.boundSurface(suffix: String, scheme: DockMarkScheme): SurfaceId? {
    if (scheme == DockMarkScheme.SURFACE) return suffix.toLongOrNull()?.let(::SurfaceId)
    // Both remaining schemes name this node before the infix, and a mark naming another node is
    // not a dock mark at all — that is the self-check, and it is the same one either way.
    val parts = suffix.split(DOCK_MARK_INFIX)
    val tail = parts.takeIf { it.size == 2 && it[0].toLongOrNull() == id }?.get(1) ?: return null
    if (scheme == DockMarkScheme.DOCK_AND_SURFACE) return tail.toLongOrNull()?.let(::SurfaceId)
    // Shape, not value: the process that reads this is routinely not the one that wrote it, so
    // "sixteen hex digits are there" is the whole of what can be checked and the whole of what is.
    if (!tail.substringAfterLast('_', "").isDockMarkNonce()) return null
    return tail.substringBeforeLast('_', "").toLongOrNull()?.let(::SurfaceId)
}
