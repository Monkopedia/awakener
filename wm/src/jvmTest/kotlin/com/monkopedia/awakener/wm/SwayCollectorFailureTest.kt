package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Where a repair collector's failure lands, and what it takes with it.
 *
 * The collector is started by [SwayWindowManager]'s constructor, so a caller has no call to wrap in
 * a `try` and no result to await: a failure that merely escaped the job would go to the scope, and
 * an ordinary `CoroutineScope(Job())` is not a supervisor, so it would cancel every unrelated
 * coroutine the caller had put there — while `repairs`, the thing built to be watched, said
 * nothing. That is what this file pins down, in both directions.
 *
 * **No compositor is needed and none is started.** The trigger used here is a `connect()` that
 * raises, which is the ordinary shape of "sway is not up yet" or "`SWAYSOCK` names a compositor
 * that is gone" — and it reaches [SwayWindowManager.collectRepairs] by exactly the path the other
 * two triggers do (sway refusing the subscription; an event payload that will not deserialize), so
 * one of the three is enough to fix the behaviour of all three. Being compositor-free is also the
 * point of running it as its own class: `SwayRepairTest` hands its manager a `SupervisorJob` plus a
 * swallowing `CoroutineExceptionHandler`, which is precisely the containment being asserted here,
 * so these assertions cannot live there.
 */
class SwayCollectorFailureTest {
    private lateinit var store: InMemoryConfigStore
    private lateinit var stateDir: Path
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-collector")
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        stateDir.toFile().deleteRecursively()
    }

    /**
     * The defect, stated as the property that was missing: a manager built on a caller's scope must
     * not be able to take that scope down.
     *
     * Against the code this test was written for, the sibling below was cancelled, `scope.isActive`
     * was false, `repairing` reported `isCancelled`, and `repairs` held a default
     * `DockRepairStatus` — the diagnosis went to the default uncaught-exception handler, which is
     * the one place the PR that added the collector said it must never go.
     */
    @Test
    fun `a collector failure does not cancel the caller's scope`() = runBlocking<Unit> {
        val scope = scope(Job())
        val sibling = scope.launch { awaitCancellation() }
        val wm = managerOn(scope)

        withTimeout(WAIT_MS) { wm.repairing.join() }

        assertTrue(
            sibling.isActive,
            "the collector's failure cancelled an unrelated coroutine of the caller's: a " +
                "constructor-started job must not be able to reach the scope it was handed",
        )
        assertTrue(scope.isActive, "and it cancelled the caller's scope itself")
        assertFalse(
            wm.repairing.isCancelled,
            "the collector ended on a failure it contained, so it completes; being cancelled " +
                "would mean the failure went to the scope after all",
        )
        val reported = assertNotNull(
            wm.repairs.value.collectorFailure,
            "and the failure is reported rather than swallowed — a collector nobody is watching " +
                "is the last place a diagnosis should be able to die quietly",
        )
        assertTrue(
            generateSequence(reported) { it.cause }.any { it.message == BOOM },
            "the reported failure is the one that happened, not a substitute: was $reported",
        )
        assertNull(
            wm.repairs.value.sessionEnded,
            "and it is not reported as a session boundary: nothing here says sway went away, and " +
                "the two have different consequences for whoever reads this",
        )
    }

    /**
     * The other half, kept exercisable. A caller that would rather a manager which has stopped
     * repairing take the process down than run on quietly can have that — it owns the consequence,
     * which is this test's swallowing handler standing in for it.
     */
    @Test
    fun `letting a collector failure out to the scope is switchable on`() = runBlocking<Unit> {
        store.put(WmFlags.collectorFailure, CollectorFailure.PROPAGATE)
        val reachedScope = CompletableDeferred<Throwable>()
        val scope = scope(Job() + CoroutineExceptionHandler { _, t -> reachedScope.complete(t) })
        val sibling = scope.launch { awaitCancellation() }
        val wm = managerOn(scope)

        withTimeout(WAIT_MS) { wm.repairing.join() }

        val escaped = withTimeout(WAIT_MS) { reachedScope.await() }
        assertTrue(
            generateSequence(escaped) { it.cause }.any { it.message == BOOM },
            "with the flag on the failure reaches the scope: was $escaped",
        )
        assertFalse(
            sibling.isActive,
            "and it takes the caller's other coroutines with it — the cost of the flag, kept " +
                "reproducible rather than described",
        )
        assertNotNull(
            wm.repairs.value.collectorFailure,
            "recorded either way: propagating must not also cost the diagnosis",
        )
    }

    // -- helpers ------------------------------------------------------------------------

    private fun scope(context: CoroutineContext) = CoroutineScope(context).also { scopes += it }

    /** A manager whose IPC connect raises, which is all it takes to fail the collector. */
    private fun managerOn(scope: CoroutineScope) = SwayWindowManager(
        connect = { error(BOOM) },
        store = store,
        registry = FileBindingStore(
            configStore = store,
            identities = { key, residue -> DerivedAgentIdentities(store).mint(key, residue) },
            path = stateDir.resolve("bindings.json"),
        ),
        scope = scope,
    )

    private companion object {
        const val BOOM = "sway is not reachable"
        const val WAIT_MS = 5_000L
    }
}
