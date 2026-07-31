package com.monkopedia.awakener.wm

/** What a sweep that raised does to the collector that ran it. */
enum class SweepFailure {
    /** Record it and keep collecting, so the next close event still gets a sweep. */
    CONTINUE,

    /** Let it out of the collector, which ends the collection and its subscription with it. */
    STOP,
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
     * Non-null once the compositor session ended, at which point the dock table has been discarded
     * and this manager can do nothing further: its commands still ride the dead connection.
     */
    val sessionEnded: CompositorSessionEnded? = null,
)
