package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * That the repair mechanisms have a caller at all.
 *
 * `reapOrphans` and the `CompositorSessionEnded` that `changes` reports were both built and
 * reviewed against a driver that did not exist: on `main` the sweep's only mentions in the whole
 * tree were its declaration and one test that called it by hand, and nothing collected `changes`
 * outside tests. Every test in this file therefore refuses to touch either mechanism directly —
 * nothing here calls `reapOrphans`, and the assertions are about what happens when nobody does.
 *
 * The compositor is SIGKILLed rather than having its socket closed, because a deliberate close is
 * the shutdown path this is supposed to stay separable from.
 */
class SwayRepairTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var store: InMemoryConfigStore
    private lateinit var stateDir: Path
    private lateinit var wm: SwayWindowManager

    private val enabled get() = SwayHarness.available()

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start()
        // Belt and braces, and deliberately not the thing under test: under the default flags the
        // collector reaches this scope with nothing at all — `SwayCollectorFailureTest` is where
        // that is asserted, on a bare `Job()` where a failure would show. Here the scope stays
        // tolerant so that a *regression* in that containment surfaces as a failed assertion about
        // `repairs` rather than as a cancelled test. What the collector did is always read from
        // `repairs` and `repairing`, never from this handler.
        scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-repair")
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    /**
     * Hazard 2, with nobody driving it. The manual half of this is already covered; what was
     * missing is that a desktop nobody is calling into repairs itself, which is the only form in
     * which the repair is worth anything.
     *
     * Against `main` this hangs on the wait and fails: the dock outlives its surface for as long
     * as the process runs, because the only thing that would take it down is a method with no
     * caller.
     */
    @Test
    fun `an orphaned dock is reaped without anyone driving the sweep`() = swayTest {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockFor("aw-dock"), AgentId("agent-1")).dockId

        command("[con_id=${app.raw}] kill")

        assertNotNull(
            awaitGone(dock),
            "the dock is still standing ${WAIT_MS}ms after its surface closed, and nothing in " +
                "this test ever called the sweep — which is the point: a repair nothing drives " +
                "is not a repair",
        )
        // Waited for rather than read: the dock leaves the tree from inside the sweep, which
        // counts itself only once it returns, so reading the counter here is a coin flip.
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.repairs.value.sweeps == 0) yield()
                true
            },
            "the dock came down but the collector reports no sweep, so something else took it",
        )
    }

    /**
     * The session boundary, which is the other half of #18 and the one with teeth.
     *
     * Every key in the dock table is a `con_id`, and sway allocates those from a counter that
     * restarts with the compositor — measured across two sequential sessions under one surviving
     * client, session A's dock id was session B's browser. So a table that outlives its session
     * does not go stale, it collides, and a collision hides a genuine window from enumeration for
     * the life of the process. #20 made the boundary observable; this is the reaction.
     *
     * Asserted on the table directly because there is nothing else left to ask: after the kill,
     * every read this manager could make goes through a dead connection.
     */
    @Test
    fun `the dock table is discarded when the compositor dies`() = swayTest {
        val app = openSurface("aw-app1")
        wm.attach(app, dockFor("aw-dock"), AgentId("agent-1"))
        assertTrue(
            wm.docks.snapshot().entries.isNotEmpty(),
            "the table has to hold the dock first, or the discard below proves nothing",
        )

        sway.kill()

        assertNotNull(
            withTimeoutOrNull(DEATH_WAIT_MS) {
                while (wm.docks.snapshot().entries.isNotEmpty()) yield()
                true
            },
            "the table still names a con_id from a session that has stopped existing; the next " +
                "sway allocates from 5 again, so that entry will collide with a real window and " +
                "hide it — was ${wm.docks.snapshot().entries}",
        )
        assertEquals(
            emptyList(),
            wm.docks.snapshot().reservations,
            "reservations go with the entries: each belongs to an attach against a compositor " +
                "that is gone",
        )
        assertNotNull(
            wm.repairs.value.sessionEnded,
            "and the reason is reported rather than inferred — it is what whoever adds reconnect " +
                "has to read",
        )
    }

    /**
     * One dock that will not come down must not cost the desktop its repair.
     *
     * The sweep already isolates docks from each other (#7/#12); this is that argument one level
     * up, where the thing at stake is not the rest of one pass but every pass after it. A wedged
     * panel is permanent — nothing un-wedges it — so a collector that stopped on the first one
     * would sweep exactly once and then never again, on a desktop where docks keep being orphaned.
     *
     * `SIGSTOP` on the client is what a wedged panel looks like to sway: nothing is left to
     * service the close, so the window stays mapped while `kill` is acknowledged all the same.
     */
    @Test
    fun `a sweep that fails does not cost the collector the next orphan`() = swayTest {
        val wedged = openSurface("aw-app1")
        val wedgedDock = wm.attach(wedged, dockFor("aw-dock"), AgentId("agent-1")).dockId
        freeze(wedgedDock)

        command("[con_id=${wedged.raw}] kill")
        assertNotNull(
            awaitFailure(),
            "the wedged dock has to have failed a sweep first, or nothing below is a recovery",
        )

        val clean = openSurface("aw-app2")
        val cleanDock = wm.attach(clean, dockFor("aw-dock"), AgentId("agent-2")).dockId
        command("[con_id=${clean.raw}] kill")

        assertNotNull(
            // Generous, and not slack: every later sweep pays the wedged dock's full window wait
            // before it reaches this one, which is a cost of the wedge rather than of the fix.
            awaitGone(cleanDock, WEDGED_SWEEP_WAIT_MS),
            "the orphan after the failure was never reaped: one wedged panel took the whole " +
                "desktop's repair with it",
        )
        assertNotNull(
            wm.tree().find(wedgedDock.raw),
            "the wedge has to be real: if it came down anyway this proves nothing",
        )
    }

    /**
     * The other half of that choice, kept exercisable. Treating a failed sweep as a defect to look
     * at rather than a panel to live with is defensible — it is loud, and nothing repairs anything
     * afterwards, which is exactly what makes it visible.
     */
    @Test
    fun `stopping the collector on a failed sweep is switchable on`() = swayTest {
        store.put(WmFlags.sweepFailure, SweepFailure.STOP)
        val wedged = openSurface("aw-app1")
        val wedgedDock = wm.attach(wedged, dockFor("aw-dock"), AgentId("agent-1")).dockId
        freeze(wedgedDock)

        command("[con_id=${wedged.raw}] kill")
        assertNotNull(awaitFailure(), "the sweep has to fail before the flag can do anything")

        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.repairing.isActive) yield()
                true
            },
            "with the flag on the failure leaves the collector, so the collection ends",
        )
        assertNotNull(
            wm.repairs.value.collectorFailure,
            "and it ends reported: STOP decides that a sweep failure ends the collection, " +
                "wm.repair.collector_failure decides where the exception goes, and its default " +
                "stops it here rather than in the caller's scope",
        )

        val clean = openSurface("aw-app2")
        val cleanDock = wm.attach(clean, dockFor("aw-dock"), AgentId("agent-2")).dockId
        command("[con_id=${clean.raw}] kill")
        awaitGone(clean)

        delay(SETTLE_MS)
        assertNotNull(
            wm.tree().find(cleanDock.raw),
            "and nothing sweeps afterwards — the cost of the flag, kept reproducible on purpose",
        )
    }

    /**
     * Driving the sweep from the close event is a flag, and off is what `main` did: the sweep
     * exists and nothing runs it. The collector still runs, because the session boundary is not
     * the sweep's to give up — a stale table hides a real window whatever the orphan policy says.
     */
    @Test
    fun `sweeping on close is switchable off`() = swayTest {
        store.put(WmFlags.sweepOnClose, false)
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockFor("aw-dock"), AgentId("agent-1")).dockId

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        delay(SETTLE_MS)

        assertNotNull(
            wm.tree().find(dock.raw),
            "with the flag off the orphan stands — the previous behaviour, kept reachable",
        )
        assertEquals(0, wm.repairs.value.sweeps, "and no sweep ran at all")

        sway.kill()
        assertNotNull(
            withTimeoutOrNull(DEATH_WAIT_MS) {
                while (wm.repairs.value.sessionEnded == null) yield()
                true
            },
            "but the collector is still watching: giving up the sweep does not give up the " +
                "session boundary",
        )
        assertTrue(wm.docks.snapshot().entries.isEmpty(), "and the table went with it")
    }

    /**
     * With events off there is no stream to collect, so there is no collector — stated in
     * `wm.events.enabled`'s description and asserted here, because a degradation nothing checks is
     * a sentence rather than a behaviour.
     */
    @Test
    fun `with events off nothing is collected and nothing is repaired`() = swayTest(events = false) {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockFor("aw-dock"), AgentId("agent-1")).dockId

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        delay(SETTLE_MS)

        assertFalse(wm.repairing.isActive, "the collector has nothing to collect and returns")
        assertNotNull(wm.tree().find(dock.raw), "so the orphan stands")
        assertNull(wm.repairs.value.sessionEnded, "and no boundary is observed either")
    }

    // -- helpers ------------------------------------------------------------------------

    /**
     * Builds the manager *inside* the test body rather than in `setUp`, because its collector
     * starts with it and `wm.events.enabled` decides whether there is one at all — read once, as
     * the flow is collected, so it has to be set before the manager exists. Hence the parameter.
     *
     * The other repair flags are not like that and a `store.put` at the top of a test body is late
     * for them by construction: it lands after the constructor has already launched the collector.
     * They work anyway because `collectRepairs` re-reads config per event rather than capturing a
     * snapshot at startup — which is `:config` reloading against a live daemon, and is the property
     * those `store.put`s are quietly relying on.
     */
    private fun swayTest(events: Boolean = true, body: suspend () -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking {
            command("workspace 1; layout tabbed")
            store.put(WmFlags.eventsEnabled, events)
            wm = SwayWindowManager({ sway.connection() }, store, bindingStore(), scope)
            body()
        }
    }

    private fun bindingStore() = FileBindingStore(
        configStore = store,
        identities = { key, residuePath -> DerivedAgentIdentities(store).mint(key, residuePath) },
        path = stateDir.resolve("bindings.json"),
    )

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        val results = swayJson.decodeFromString<List<CommandResult>>(raw)
        results.firstOrNull { !it.success }?.let { error("sway rejected '$text': ${it.error}") }
    }

    private fun dockFor(appId: String) =
        DockSpec(appId, sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    private suspend fun openSurface(appId: String): SurfaceId {
        command("exec ${sway.windowCommand(appId)}")
        return SurfaceId(
            assertNotNull(
                withTimeoutOrNull(WAIT_MS) {
                    while (true) {
                        wm.tree().windows.firstOrNull { it.appId == appId }
                            ?.let { return@withTimeoutOrNull it.id }
                        yield()
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                },
                "window '$appId' never appeared",
            ),
        )
    }

    /** Null if [id] was still in the tree after [timeoutMs]. */
    private suspend fun awaitGone(id: SurfaceId, timeoutMs: Long = WAIT_MS): Boolean? =
        withTimeoutOrNull(timeoutMs) {
            while (wm.tree().find(id.raw) != null) yield()
            true
        }

    /** Null if no sweep has raised within the window wait plus the sweep's own. */
    private suspend fun awaitFailure(): Boolean? = withTimeoutOrNull(WAIT_MS + WAIT_MS) {
        while (wm.repairs.value.failures == 0) yield()
        true
    }

    /** Stops the client behind [window] so it can no longer service a close request. */
    private suspend fun freeze(window: SurfaceId) {
        val pid = assertNotNull(wm.tree().find(window.raw)?.pid, "no pid for ${window.raw}")
        assertEquals(
            0,
            ProcessBuilder("kill", "-STOP", pid.toString()).start().waitFor(),
            "kill -STOP $pid failed",
        )
    }

    private companion object {
        const val WAIT_MS = 5_000L

        /** Long enough that "nothing swept it" is a conclusion rather than a gap. */
        const val SETTLE_MS = 1_000L

        /**
         * A sweep that has to give up on a wedged dock before it reaches the next one, which
         * costs the window wait in full — so this is two of those plus room to finish.
         */
        const val WEDGED_SWEEP_WAIT_MS = 20_000L

        /** Slack for the death to reach the collector; the socket itself signals immediately. */
        const val DEATH_WAIT_MS = 5_000L
    }
}
