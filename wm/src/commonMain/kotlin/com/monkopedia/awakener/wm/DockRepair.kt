package com.monkopedia.awakener.wm

/** What a sweep that raised does to the collector that ran it. */
enum class SweepFailure {
    /** Record it and keep collecting, so the next close event still gets a sweep. */
    CONTINUE,

    /**
     * End the collection, and its event subscription with it.
     *
     * Where the exception goes once it leaves the sweep is [CollectorFailure]'s question, not this
     * one: under the default it is recorded on [DockRepairStatus.collectorFailure] and the
     * collector completes, and under [CollectorFailure.PROPAGATE] it also reaches the scope.
     */
    STOP,
}

/**
 * Where a collector failure that is not the session boundary goes.
 *
 * The collector is started by the manager's constructor on the scope its caller handed in,
 * so there is no `try` a caller could wrap around it and no `await` it could put the failure on. A
 * failure that simply escaped the job would therefore land in the scope — cancelling the caller's
 * other coroutines, since an ordinary `Job()` scope is not a supervisor — and reach nothing that
 * was asking about repair. That is measurable rather than hypothetical: with a `connect()` that
 * throws, the whole scope and an unrelated sibling coroutine went down while
 * [DockRepairStatus] stayed empty.
 */
enum class CollectorFailure {
    /**
     * Record it on [DockRepairStatus.collectorFailure] and complete the collector normally.
     *
     * The scope keeps every other coroutine it holds; what is lost is this manager's repair, which
     * was lost either way — the subscription behind the collector is gone and nothing rebuilds it.
     * `wm.session.reconnect` is not that thing: it restarts a collector that ended at the session
     * boundary, where a successor connection is the missing piece, and none of the three failures
     * that arrive here is one a fresh connection is known to fix.
     */
    REPORT,

    /**
     * Record it and then rethrow, so the failure reaches the scope the manager was constructed
     * with.
     *
     * The loud alternative, for a caller that would rather a manager whose collector died take the
     * process down than run on quietly repairing nothing. A caller choosing this owns the
     * consequence: the job was started by the constructor, so the scope must be one that tolerates
     * a child failing — a `SupervisorJob`, or a `CoroutineExceptionHandler`, or both.
     */
    PROPAGATE,
}

/**
 * What a manager's repair collector has done, and what stopped it.
 *
 * The collector runs where nobody is looking — it is driven by the compositor rather than by a
 * call — so everything it would otherwise swallow is reported here. Two readers are already in
 * mind: an operator asking why an orphaned panel is still on screen, and whoever adds reconnect,
 * for whom [sessionEnded] is the signal that this manager is finished and a successor is needed.
 */
data class DockRepairStatus(
    /** Sweeps run to completion, whether they repaired anything or raised. */
    val sweeps: Int = 0,
    /** How many of [sweeps] raised. */
    val failures: Int = 0,
    /**
     * The most recent sweep failure, or null if none has failed.
     *
     * The most recent only, rather than all of them: a collector lives as long as a compositor
     * session and a list here would grow with it. `reapOrphans` already aggregates the failures
     * *within* one sweep, naming the dock each came from, so what is lost is earlier sweeps and
     * not earlier docks.
     */
    val lastFailure: Throwable? = null,
    /**
     * The compositor session boundary this manager is currently stopped at, or null if it is not
     * stopped at one.
     *
     * Set when the collector sees the session end, at which point the dock table has been discarded
     * and the dead connection closed, and **cleared again when a successor connection is acquired**
     * — see [reconnects], which is where the history lives. Clearing it is not tidiness: the whole
     * meaning of this field is "this manager is finished and a successor is needed", and under
     * `wm.session.reconnect=ON_DEMAND` that stops being true the moment the next call reconnects.
     * Under `NEVER` nothing clears it, which is that flag's whole behaviour.
     */
    val sessionEnded: CompositorSessionEnded? = null,
    /**
     * How many successor connections this manager has acquired since it was built.
     *
     * Zero on a manager that has never seen a session end, and on one that has seen one and been
     * left at it. It counts *acquisitions* rather than boundaries because that is the observable
     * with consequences — a fresh connection, an empty table, a restarted collector, and every
     * [DockHandle] from before it now naming another session's `con_id`s.
     */
    val reconnects: Int = 0,
    /**
     * What ended the collection other than the session boundary, or null if nothing did.
     *
     * The three ordinary ways to get here are a `connect()` that raises — sway not up yet, or a
     * `SWAYSOCK` that names a compositor that is gone — sway refusing the event subscription, and
     * an event payload that will not deserialize. None of them is recoverable by this manager, so
     * the collector completes; what this field exists for is that it completes *visibly*. Set
     * before the collector ends, so a reader who sees the repair job stop running can read this and
     * find the reason already here.
     *
     * A [SweepFailure.STOP] failure arrives here as well as on [lastFailure] — the same throwable
     * in both, one saying which sweep raised and this one saying what ended the collection. The
     * two are distinguishable: [failures] is non-zero exactly when a sweep is what did it.
     *
     * Distinct from [sessionEnded] because the consequences differ. A session boundary means the
     * table has been discarded and a successor connection is what is needed; this means the table
     * is still whatever it was, still correct for a session that is still running, and no longer
     * maintained — including that the boundary itself will now pass unobserved.
     */
    val collectorFailure: Throwable? = null,
)
