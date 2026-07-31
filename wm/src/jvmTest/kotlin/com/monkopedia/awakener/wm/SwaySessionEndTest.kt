package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * What a collector observes when the compositor goes away.
 *
 * The failure this guards is not a crash, it is a silence: `subscribe` used to catch the EOF from
 * a dead sway and return as though the caller had closed the connection, and `changes` never
 * closed its channel, so after a `SIGKILL` a collector still read
 * `collectorActive=true flowCompleted=false failed=null` — character for character what an idle
 * desktop reads. Those want opposite responses, so both states are exercised here: a fix that
 * reports death by closing the stream whenever nothing is happening would be no better than the
 * silence it replaced.
 *
 * sway is killed for real rather than having its socket closed from underneath, because closing
 * the socket is the deliberate shutdown these tests exist to keep separable.
 */
class SwaySessionEndTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var stateDir: Path

    /** Clients this test opened, so none of them outlives the compositor it was talking to. */
    private val clients = mutableListOf<Int>()

    private val enabled get() = SwayHarness.available()

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        val store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-session")
        wm = SwayWindowManager(
            connect = { sway.connection() },
            store = store,
            registry = FileBindingStore(
                configStore = store,
                identities = { key, residuePath -> DerivedAgentIdentities(store).mint(key, residuePath) },
                path = stateDir.resolve("bindings.json"),
            ),
            scope = scope,
        )
    }

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        // Before sway goes, and children first: a terminal whose compositor was SIGKILLed can be
        // left holding a `sleep` that nothing else will ever reap.
        clients.forEach { pid ->
            runCatching { ProcessBuilder("pkill", "-KILL", "-P", "$pid").start().waitFor() }
            runCatching { ProcessBuilder("kill", "-KILL", "$pid").start().waitFor() }
        }
        clients.clear()
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    /**
     * The two states, read through the same observation, in the order a desktop meets them.
     *
     * The assertion that matters is that the two strings differ. Pre-fix they are identical, and
     * the second one is the one that is wrong: at that point every binding, every dock id and the
     * whole in-memory view of the tree belongs to a session that has stopped existing, and the
     * collector is still sat there waiting for the next window to open.
     */
    @Test
    fun `an idle desktop and a dead compositor do not look the same`() = swayTest {
        val stream = collectChanges()
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        assertNotNull(
            awaitDelivery(stream, listOf(app1, app2)),
            "the subscription never delivered anything, so nothing below would mean much",
        )
        closeSurface(app1)
        closeSurface(app2)

        delay(IDLE_MS)
        val idle = stream.state()

        sway.kill()
        assertNotNull(
            stream.await { !job.isActive },
            "the stream still had nothing to say ${DEATH_WAIT_MS}ms after sway was killed: $idle",
        )
        val dead = stream.state()

        assertEquals(
            "collectorActive=true flowCompleted=false failed=null",
            idle,
            "an idle desktop is a live stream with nothing on it; that must not change",
        )
        assertEquals(
            "collectorActive=false flowCompleted=false failed=CompositorSessionEnded",
            dead,
            "a dead compositor has to reach the collector as a failure rather than as more " +
                "quiet: unfixed, this reads '$idle' — character for character what idle reads",
        )
        assertNotNull(stream.failed?.cause, "and it must carry what the socket actually reported")
    }

    /**
     * The same distinction at the layer it is drawn on, where both cases used to return from one
     * `catch (e: IOException) { return }`.
     *
     * Closing the connection is how a caller ends a subscription — `changes` does exactly this
     * from `awaitClose` — so it must stay an ordinary return, or every collector that stops
     * collecting reports a compositor crash.
     */
    @Test
    fun `closing a subscription is not the compositor dying`() = swayTest {
        val closed = sway.connection()
        val deliberate = subscribing(closed)
        closed.close()
        assertNull(
            deliberate.await().exceptionOrNull(),
            "a subscription whose connection the caller closed has nothing to report",
        )

        val doomed = sway.connection()
        val crash = subscribing(doomed)
        sway.kill()
        assertIs<CompositorSessionEnded>(
            crash.await().exceptionOrNull(),
            "and one whose compositor was killed under it has to say so",
        )
        doomed.close()
    }

    // -- helpers ------------------------------------------------------------------------

    /**
     * A live collection of [SwayWindowManager.changes], recording the three facts that were
     * indistinguishable between the two states: is the collector still running, did the flow
     * complete, and did it fail.
     */
    private inner class Stream {
        val events = CopyOnWriteArrayList<SurfaceChange>()

        @Volatile
        var completed = false

        @Volatile
        var failed: Throwable? = null

        val job = scope.launch {
            try {
                wm.changes.collect { events += it }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failed = failure
            }
        }

        fun state(): String =
            "collectorActive=${job.isActive} flowCompleted=$completed " +
                "failed=${failed?.let { it::class.simpleName }}"

        /** Waits for [reached], or null if it never was. */
        suspend fun await(reached: Stream.() -> Boolean): Boolean? =
            withTimeoutOrNull(DEATH_WAIT_MS) {
                while (!reached()) yield()
                true
            }
    }

    private fun collectChanges() = Stream()

    /**
     * Waits until [stream] has actually been given something, keeping the events coming until it
     * has.
     *
     * The collection starts on another dispatcher, so a window opened straight afterwards can map
     * before sway has the subscription at all — measured, once in three runs. Triggering once
     * would make the liveness check a coin flip, and a liveness check that can fail by chance is
     * worse than none: it is the assertion that stops the two states below from both being read
     * off a stream that was never listening.
     */
    private suspend fun awaitDelivery(stream: Stream, surfaces: List<SurfaceId>): Boolean? =
        withTimeoutOrNull(WAIT_MS) {
            var next = 0
            while (stream.events.isEmpty()) {
                command("[con_id=${surfaces[next++ % surfaces.size].raw}] focus")
                delay(TRIGGER_INTERVAL_MS)
            }
            true
        }

    /** [SwayConnection.subscribe] running on its own coroutine, for a test that ends it. */
    private fun subscribing(connection: SwayConnection): Deferred<Result<Unit>> =
        scope.async { runCatching { connection.subscribe(listOf("window")) { _, _ -> } } }

    private fun swayTest(body: suspend () -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking {
            command("workspace 1; layout tabbed")
            body()
        }
    }

    private suspend fun command(text: String) {
        val raw = sway.connection().use { it.request(I3Ipc.Request.RUN_COMMAND, text) }
        val results = swayJson.decodeFromString<List<CommandResult>>(raw)
        results.firstOrNull { !it.success }?.let { error("sway rejected '$text': ${it.error}") }
    }

    private suspend fun openSurface(appId: String): SurfaceId {
        command("exec ${sway.windowCommand(appId)}")
        val node = assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (true) {
                    wm.tree().windows.firstOrNull { it.appId == appId }
                        ?.let { return@withTimeoutOrNull it }
                    yield()
                }
                @Suppress("UNREACHABLE_CODE")
                null
            },
            "window '$appId' never appeared",
        )
        node.pid?.let { clients += it }
        return SurfaceId(node.id)
    }

    /** Leaves the desktop genuinely idle — and takes the terminal down while sway can still ask. */
    private suspend fun closeSurface(surface: SurfaceId) {
        command("[con_id=${surface.raw}] kill")
        withTimeoutOrNull(WAIT_MS) {
            while (wm.tree().find(surface.raw) != null) yield()
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L

        /** Long enough that "nothing is happening" is the desktop's state and not a gap. */
        const val IDLE_MS = 1_000L

        /** Between two focus changes, which is how the subscription is given something to say. */
        const val TRIGGER_INTERVAL_MS = 50L

        /**
         * How long the death may take to reach the collector. The socket signals immediately, so
         * this is slack for the report to get there, not an expected wait.
         */
        const val DEATH_WAIT_MS = 5_000L
    }
}
