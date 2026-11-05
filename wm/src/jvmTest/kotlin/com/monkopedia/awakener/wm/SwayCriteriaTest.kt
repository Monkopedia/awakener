package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * The `no_focus` rule `attach` installs names the dock's `app_id` and nothing else, whatever that
 * `app_id` contains (#184). See [Criteria] for what sway 1.12 does with a value.
 *
 * **The hostile `app_id` reaches sway through one door only.** The dock command reads its name out
 * of a file rather than having `{app_id}` substituted into it, so the `exec` that starts the dock
 * carries no hostile byte, and a canary that appears can only have come from the criteria.
 *
 * The focus assertions are what make this more than "sway accepted a command": the dock *not*
 * taking focus is the rule matching that exact name, and a near-miss window *taking* it is the
 * rule matching nothing else.
 *
 * Exercised against **sway 1.12** on the headless wlroots backend.
 */
class SwayCriteriaTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var stateDir: Path

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-criteria")
        val registry = FileBindingStore(
            configStore = store,
            identities = { key, residuePath -> DerivedAgentIdentities(store).mint(key, residuePath) },
            path = stateDir.resolve("bindings.json"),
        )
        wm = SwayWindowManager({ sway.connection() }, store, registry, scope)
        store.put(WmFlags.dockFocusSuppression, FocusSuppression.NO_FOCUS_RULE)
        store.put(WmFlags.dockFocusOnMap, false)
        store.put(WmFlags.restoreFocusAfterAttach, false)
    }

    @AfterTest
    fun tearDown() {
        if (!SwayHarness.available()) return
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    /**
     * The red test. Before [Criteria], the first id here ran its `exec` — measured, two successful
     * results and the canary on disk — the trailing backslash and the unbalanced paren each made
     * sway reject the rule and fail the attach, and the rest suppressed focus only because an
     * unanchored regex happened to match.
     */
    @Test
    fun `a hostile dock app_id cannot break out of the no_focus criteria`() = swayTest {
        val canary = stateDir.resolve("pwned")
        val hostile = listOf(
            "x\"]; exec touch $canary; [app_id=\"y",
            "x\"], exec touch $canary, [app_id=\"y",
            "q\\\"]; exec touch $canary; [app_id=\\\"r",
            "trailing\\",
            "paren(",
            "sp ace'\$x.*",
        )

        hostile.forEachIndexed { index, appId ->
            val app = openSurface("aw-app$index")
            val dock = wm.attach(app, fileNamedDock(appId, index), AgentId("agent-$index"))

            assertEquals(appId, wm.tree().find(dock.dockId.raw)?.appId, "the dock reports the hostile name")
            assertEquals(app.raw, focusedId(), "the rule caught the dock named '$appId' and it did not take focus")
            delay(SETTLE_MS)
            assertFalse(canary.exists(), "the dock app_id '$appId' ran a compositor command")
        }
    }

    /**
     * The other half of the red: an unanchored regex reached windows that merely contain the name
     * or match its `.` as a wildcard, and none of these could take focus before the fix.
     */
    @Test
    fun `the no_focus rule matches the dock app_id exactly`() = swayTest {
        val app = openSurface("aw-app")
        wm.attach(app, fileNamedDock("aw.dock", 0), AgentId("agent-0"))
        assertEquals(app.raw, focusedId(), "the rule caught the dock itself")

        for (nearMiss in listOf("awXdock", "aw.dock2", "xaw.dock")) {
            val window = openSurface(nearMiss)
            assertEquals(window.raw, focusedId(), "'$nearMiss' is not 'aw.dock' and took focus on map")
        }
    }

    // -- helpers ------------------------------------------------------------------------

    /**
     * A dock that reads its `app_id` from a file, so the hostile name never passes through the
     * `exec` that starts it. `NEW_NODE` identity, the default, needs no `{app_id}` in the command.
     */
    private fun fileNamedDock(appId: String, index: Int): DockSpec {
        val name = stateDir.resolve("dock-name-$index").also { it.writeText(appId) }
        return DockSpec(appId, "foot -a \"\$(cat $name)\" -- sleep 3600")
    }

    private fun swayTest(body: suspend () -> Unit) {
        SwayHarness.assumeAvailable()
        runBlocking { body() }
    }

    private suspend fun openSurface(appId: String): SurfaceId {
        val raw = sway.connection().use {
            it.request(I3Ipc.Request.RUN_COMMAND, "exec ${sway.windowCommand(appId)}")
        }
        swayJson.decodeFromString<List<CommandResult>>(raw)
            .firstOrNull { !it.success }
            ?.let { error("sway rejected the exec of '$appId': ${it.error}") }
        val id = withTimeoutOrNull(WAIT_MS) {
            while (true) {
                wm.tree().windows.firstOrNull { it.appId == appId }
                    ?.let { return@withTimeoutOrNull it.id }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        return SurfaceId(assertNotNull(id, "window '$appId' never appeared"))
    }

    private suspend fun focusedId(): Long? = wm.tree().windows.firstOrNull { it.focused }?.id

    private companion object {
        const val WAIT_MS = 5_000L

        /** Time for an `exec` that did get through to have run, before the canary is read. */
        const val SETTLE_MS = 500L
    }
}
