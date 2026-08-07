package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * A manager's lifecycle: retiring one (#85), and living through a compositor restart (#33).
 *
 * They are one file because they are one question asked from two sides. A sway restart is the
 * concrete case in which a predecessor manager could still be subscribed while a successor is
 * built, and two managers over one tree do not agree about what to reap — the divergence is
 * manager-relative and whichever sweep lands first decides. So either the manager survives the
 * boundary, in which case there is no second manager, or a caller replaces it, in which case
 * retiring the first has to be something stronger than asking it to stop.
 *
 * Both are built here. `close()` is the retirement, and `wm.session.reconnect` decides whether the
 * manager acquires the successor connection itself or leaves that to the caller.
 */
class SwayLifecycleTest {
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
        // Tolerant for the same reason SwayRepairTest's is: what the collector did is read from
        // `repairs` and from `repairing`, never from a handler, so a regression in the default
        // containment shows up as a failed assertion rather than as a cancelled test.
        scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-lifecycle")
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    // -- retiring a manager (#85) -------------------------------------------------------

    /**
     * What `close()` is for, in the three parts it promises.
     *
     * The collector has *stopped* — not been asked to stop — the table is abandoned, and the
     * manager refuses to open another connection. Those three together are what makes a caller
     * replacing a manager safe: a predecessor that is still collecting is a second opinion on every
     * close event for the rest of the session, and its opinion is formed from its own dock table,
     * which the successor does not share.
     *
     * The dock is left standing on purpose and is asserted on: retiring a manager is not tearing
     * the desktop down, and a mark is what the next manager adopts a standing dock by.
     */
    @Test
    fun `a closed manager stops collecting, abandons its table and refuses further calls`() =
        swayTest {
            val app = openSurface("aw-app1")
            val dock = wm.attach(app, dockFor("aw-dock"), AgentId("agent-1")).dockId
            assertTrue(
                wm.docks.snapshot().entries.isNotEmpty(),
                "the table has to hold the dock first, or abandoning it below proves nothing",
            )

            wm.close()

            assertTrue(
                wm.repairing.isCompleted,
                "close returned with its collector still running: cancelling only asks, and a " +
                    "predecessor still inside a sweep is exactly the overlap this exists to stop",
            )
            assertTrue(
                wm.docks.snapshot().entries.isEmpty(),
                "and the table it would sweep from is abandoned: was ${wm.docks.snapshot()}",
            )

            // The event that would have driven a sweep, delivered after the retirement.
            command("[con_id=${app.raw}] kill")
            delay(SETTLE_MS)

            assertEquals(
                0,
                wm.repairs.value.sweeps,
                "a closed manager swept anyway — it is still deciding what to reap on a tree " +
                    "somebody else now owns",
            )
            assertNotNull(
                treeNow().find(dock.raw),
                "and the dock stands: closing a manager retires awakener's half of the " +
                    "arrangement, not the user's panel",
            )
            val refused = assertFailsWith<IllegalStateException> { wm.surfaces() }
            assertTrue(
                "closed" in refused.message.orEmpty(),
                "a call on a closed manager has to say that is what happened: was $refused",
            )
        }

    /**
     * The join, which is the part `cancel` cannot do.
     *
     * PR #78 established the lesson this rests on: `cancel` only asks, so returning while the old
     * collector is still inside a sweep leaves the same race, narrower. Here the sweep is held open
     * deterministically — [SwayValve] catches the `kill` the sweep sends and does not forward it,
     * so the collector is parked in a socket read no cancellation can interrupt — and `close()`
     * must still come back with it stopped. That it can is the other half of the mechanism: the
     * command connection is closed before the join, so a collector waiting on the compositor is
     * woken rather than waited for.
     *
     * `wm.manager.close_wait_ms=0` is the same call without the join, and it is what a caller had
     * before: the cancellation is issued and nothing is promised about when it lands.
     */
    @Test
    fun `close returns only once a sweep in flight has actually stopped`() = swayTest {
        SwayValve.open(sway.socket).use { valve ->
            val valved = valvedCommands(valve)
            val app = openSurface("aw-app1")
            // The field manager is retired before the valved one attaches anything, and finding
            // that out cost this test a run: leave both collecting and the close event gets two
            // sweeps, the direct one reaches sway first, and the dock is already gone by the time
            // the valved sweep looks — the divergence this whole file is about, arriving as a
            // test that could not hold what it meant to hold.
            wm.close()
            valved.attach(app, dockFor("aw-dock"), AgentId("agent-1"))

            // The sweep's own kill, which is the last thing it does before waiting on the tree.
            valve.holdNext { type, payload ->
                type == I3Ipc.Request.RUN_COMMAND && payload.endsWith("] kill")
            }
            command("[con_id=${app.raw}] kill")
            valve.awaitHeld(WAIT_MS)

            assertNotNull(
                // Twice the manager's own default join bound, so that a close which *did* wait
                // its full allowance is reported as the raise it is rather than as this timeout.
                withTimeoutOrNull(WAIT_MS * 2) { valved.close() },
                "close never returned: it has to wake a collector blocked on the compositor " +
                    "rather than wait for one",
            )
            assertTrue(
                valved.repairing.isCompleted,
                "close returned with the held sweep still running, which is the state a caller " +
                    "building a replacement must not be able to be in",
            )
        }
    }

    /**
     * The other choice, kept exercisable: a shutdown with nothing coming after it, where a standing
     * panel nothing holds a handle to is a leak rather than an inheritance.
     */
    @Test
    fun `taking the docks down with the manager is switchable on`() = swayTest {
        store.put(WmFlags.closeReapsDocks, true)
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockFor("aw-dock"), AgentId("agent-1")).dockId

        wm.close()

        assertNull(
            treeNow().find(dock.raw),
            "with the flag on a closed manager takes its docks with it",
        )
        assertNotNull(
            treeNow().find(app.raw),
            "and only its docks: the surface is the user's window and is not awakener's to close",
        )
    }

    // -- living through a compositor restart (#33) --------------------------------------

    /**
     * Reconnection, end to end and through the thing that proves it: the collector.
     *
     * A successor *connection* is easy to observe and easy to fake — any call that returns is one.
     * What #33 actually asks for is a manager that works after the boundary, and the piece that
     * would silently stay missing is the repair collector: nothing restarted it, so a manager could
     * reconnect its commands and never sweep another orphan for the rest of the process. So this
     * drives the new session as a desktop rather than as a socket — attach a dock, close its
     * surface, and require the orphan to be reaped by a collector that only exists if the reconnect
     * built one.
     *
     * The table is asserted empty across the boundary because the note's rule is that it is
     * discarded and rebuilt by adoption rather than carried: `con_id`s restart with the compositor,
     * so a carried entry does not go stale, it collides with a real window.
     */
    @Test
    fun `a compositor restart is survived, and the successor session is collected on`() = swayTest {
        val app = openSurface("aw-app1")
        wm.attach(app, dockFor("aw-dock"), AgentId("agent-1"))

        sway.restart()
        assertNotNull(awaitBoundary(), "the boundary has to be observed before anything below")
        command("workspace 1; layout tabbed")

        // The first call that needs the compositor is what reconnects; nothing does it in advance,
        // which is the design's rule about acting only when asked.
        val app2 = openSurface("aw-app2")

        assertEquals(
            1,
            wm.repairs.value.reconnects,
            "the manager never acquired a successor connection",
        )
        assertNull(
            wm.repairs.value.sessionEnded,
            "and it is no longer stopped at a boundary it has reconnected past",
        )
        assertTrue(
            wm.docks.snapshot().entries.isEmpty(),
            "the successor session starts with an empty table: a carried con_id collides with a " +
                "real window rather than merely going stale — was ${wm.docks.snapshot().entries}",
        )

        val dock2 = wm.attach(app2, dockFor("aw-dock"), AgentId("agent-2")).dockId
        command("[con_id=${app2.raw}] kill")
        assertNotNull(
            awaitGone(dock2),
            "the orphan in the new session was never reaped, so the reconnect brought back a " +
                "command connection and not a manager: nothing restarted the repair collector",
        )
    }

    /**
     * Leaving reconnection to the caller, which is a coherent design and not a defect: under it a
     * manager is one session's, and a restart cannot produce two managers over one tree because the
     * old one refuses to be part of the new session at all.
     */
    @Test
    fun `refusing to reconnect is switchable on`() = swayTest {
        store.put(WmFlags.sessionReconnect, SessionReconnect.NEVER)
        val app = openSurface("aw-app1")
        wm.attach(app, dockFor("aw-dock"), AgentId("agent-1"))

        sway.restart()
        assertNotNull(awaitBoundary(), "the boundary has to be observed first")

        val refused = assertFailsWith<IllegalStateException> { wm.surfaces() }
        assertTrue(
            "wm.session.reconnect" in refused.message.orEmpty(),
            "a refusal has to name the flag that decided it: was $refused",
        )
        assertEquals(0, wm.repairs.value.reconnects, "and nothing was acquired")
        assertNotNull(
            wm.repairs.value.sessionEnded,
            "and it stays stopped at the boundary, which is what the caller reads to know a " +
                "successor manager is needed",
        )
    }

    /**
     * The hazard reconnection creates, and the reason it is not enough to acquire a connection.
     *
     * A handle names `con_id`s, and sway allocates those from a counter that restarts with the
     * compositor — session A's dock id was session B's browser, measured across two sequential
     * sessions under one client. So a handle that survives the boundary does not name a dead
     * window; it names whatever the successor gave that id to, and `detach`'s first act is a kill.
     * Refusing it is the default for that reason and not for tidiness.
     */
    @Test
    fun `a dock handle from the ended session is refused`() = swayTest {
        val app = openSurface("aw-app1")
        val handle = wm.attach(app, dockFor("aw-dock"), AgentId("agent-1"))

        sway.restart()
        assertNotNull(awaitBoundary(), "the boundary has to be observed first")
        command("workspace 1; layout tabbed")
        wm.surfaces()
        assertEquals(1, wm.repairs.value.reconnects, "the manager has to be on the new session")

        val refused = assertFailsWith<CompositorSessionEnded> { handle.detach() }
        assertTrue(
            "${handle.dockId.raw}" in refused.message.orEmpty(),
            "the refusal has to name the id it would have acted on: was $refused",
        )

        store.put(WmFlags.staleHandles, StaleHandle.ACT)
        assertFalse(
            runCatching { handle.focus() }.exceptionOrNull() is CompositorSessionEnded,
            "with the flag on the handle is not refused: the command reaches the live session, " +
                "whatever that session has since done with the id — which is the previous " +
                "behaviour and the reason it is not the default",
        )
    }

    // -- helpers ------------------------------------------------------------------------

    private fun swayTest(body: suspend () -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking {
            command("workspace 1; layout tabbed")
            wm = SwayWindowManager({ sway.connection() }, store, bindingStore(), scope)
            body()
        }
    }

    /**
     * A manager whose **commands** reach sway through [valve] while its event subscription does
     * not, so that a test can hold a command a sweep sends.
     *
     * The split is forced rather than chosen. [SwayValve] relays strictly one reply per request,
     * which is the whole of the i3 protocol on a connection that has not subscribed — put a
     * subscription through it and the ack comes back and then nothing, because the relay is parked
     * on the client's next request while sway is writing events at it. A manager valved on both
     * connections therefore never sees a close event, never sweeps, and holds nothing: measured, as
     * this test failing to catch its own predicate.
     *
     * Which connection is which is decided by order and not by guesswork: the collector's
     * subscription is the first thing a manager opens, and this waits for it before returning, so
     * everything afterwards — the command session included — is valved.
     */
    private suspend fun valvedCommands(valve: SwayValve): SwayWindowManager {
        val connects = AtomicInteger(0)
        val manager = SwayWindowManager(
            {
                if (connects.getAndIncrement() == 0) sway.connection()
                else SwayConnection.open(valve.socket.absolutePathString())
            },
            store,
            bindingStore(),
            scope,
        )
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (connects.get() == 0) delay(POLL_MS)
                true
            },
            "the collector never opened its subscription, so nothing below is valved the way " +
                "this test believes it is",
        )
        return manager
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

    /**
     * The tree read on a connection of the test's own.
     *
     * Every assertion about what is standing after a manager has been closed has to come from
     * somewhere other than that manager — a closed one will not open a connection, which is
     * precisely one of the things being asserted.
     */
    private suspend fun treeNow(): Node =
        sway.connection().use { swayJson.decodeFromString(it.request(I3Ipc.Request.GET_TREE)) }

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

    /** Null if [id] was still in the tree after [WAIT_MS]. */
    private suspend fun awaitGone(id: SurfaceId): Boolean? = withTimeoutOrNull(WAIT_MS) {
        while (treeNow().find(id.raw) != null) delay(POLL_MS)
        true
    }

    /** Null if the collector never reported the session ending. */
    private suspend fun awaitBoundary(): Boolean? = withTimeoutOrNull(DEATH_WAIT_MS) {
        while (wm.repairs.value.sessionEnded == null) delay(POLL_MS)
        true
    }

    private companion object {
        const val WAIT_MS = 5_000L

        /** Slack for the death to reach the collector; the socket itself signals immediately. */
        const val DEATH_WAIT_MS = 5_000L

        /** Long enough that "nothing swept it" is a conclusion rather than a gap. */
        const val SETTLE_MS = 1_000L

        /** Paced rather than spun: these polls open a connection each (#49's argument, smaller). */
        const val POLL_MS = 25L
    }
}
