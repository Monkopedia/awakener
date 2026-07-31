package com.monkopedia.awakener.wm

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A dock this process stood up, and the surface it stood it up beside. */
internal data class DockEntry(val surface: SurfaceId, val appId: String)

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

/** The table as one value, so a reader sees a consistent view without taking a lock. */
internal data class DockTableSnapshot(
    val entries: Map<Long, DockEntry> = emptyMap(),
    val reservations: List<DockReservation> = emptyList(),
) {
    /** Whether an attach in flight has reserved the `app_id` [node] reports. */
    fun reserves(node: Node): Boolean = reservations.any { it.covers(node) }
}

/**
 * Which nodes are docks, and whose.
 *
 * Authoritative for exactly that one predicate. It never says a window exists — the tree keeps
 * that, and a node the tree has dropped is simply never asked about — and it is never consulted
 * by `resolve`, which answers from the durable registry: a `con_id` is meaningless after a
 * reboot and the binding it resolves is not.
 *
 * Deliberately not persisted, and deliberately no longer lived than the IPC connection it was
 * built against. sway allocates `con_id`s from a counter that restarts with the compositor, so an
 * entry that outlived a sway restart would name whatever window happens to hold that id next.
 * Nothing here enforces that bound today: no code path notices the connection dying (#18).
 *
 * Every mutation replaces the snapshot atomically, so [snapshot] blocks on nothing and enumeration
 * never queues behind an attach.
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

    fun record(dock: SurfaceId, entry: DockEntry) =
        state.update { it.copy(entries = it.entries + (dock.raw to entry)) }

    fun forget(dock: SurfaceId) =
        state.update { it.copy(entries = it.entries - dock.raw) }
}

/** What a node's marks say about the surface it is a dock for. */
internal data class DockMarkReading(
    val surface: SurfaceId?,
    /**
     * Marks under the dock prefix whose suffix is not a `con_id`.
     *
     * A user's own mark, in other words: sway's mark namespace is one global, user-facing set and
     * `mark notes` is an ordinary thing to have bound to a key. Such a node is not a dock, so it
     * is reported here and left enumerable rather than hidden.
     */
    val unparsed: List<String>,
)

/**
 * The one dock-mark predicate: the configured prefix followed by a parseable `con_id`, and
 * nothing else counts.
 *
 * Shared by enumeration and by the orphan sweep so that the two cannot disagree about what the
 * prefix identifies — when they did, a window carrying `awakener_dock_notes` was hidden from
 * enumeration by one and skipped by the other, leaving it unreachable by any code path and
 * reported by none (#15).
 *
 * A node carrying more than one parseable dock mark takes the first; sway moves a mark rather
 * than copying it, so nothing awakener does produces that.
 */
internal fun Node.dockMark(prefix: String): DockMarkReading {
    var surface: SurfaceId? = null
    val unparsed = mutableListOf<String>()
    for (mark in marks) {
        if (!mark.startsWith(prefix)) continue
        val boundTo = mark.removePrefix(prefix).toLongOrNull()
        when {
            boundTo == null -> unparsed += mark
            surface == null -> surface = SurfaceId(boundTo)
        }
    }
    return DockMarkReading(surface, unparsed)
}
