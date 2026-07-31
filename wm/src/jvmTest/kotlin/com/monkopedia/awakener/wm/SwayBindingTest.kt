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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Guards the three hazards found by the 2026-07-30 probe. Each one is a way the tree degrades
 * if `attach` does not own the split container it creates, and each was observed on sway 1.12
 * before any of this code existed — see `docs/findings/2026-07-30-sway-binding-probe.md`.
 */
class SwayBindingTest {
    private lateinit var sway: SwayHarness
    private lateinit var scope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var stateDir: Path
    private var minted = 0

    /**
     * How long a mint takes. Zero everywhere but the one test that cares: the real minter is
     * `SpanreedCli`, which shells out, and a test may not.
     */
    private var mintDelayMs = 0L

    /** Clients stopped by [freeze]. They have to be killed rather than asked to leave. */
    private val frozen = mutableListOf<Int>()

    private val enabled get() = SwayHarness.available()

    @BeforeTest
    fun setUp() {
        if (!enabled) return
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-bindings")
        wm = SwayWindowManager({ sway.connection() }, store, bindingStore(), scope)
    }

    /**
     * A real file-backed registry rather than an in-memory one, because the behaviour worth
     * testing here — a binding outliving the window it was made against — is exactly what an
     * in-memory store would fake. The minter is counted so that "did this attach mint a
     * Lifeless" is observable; the real one shells out to spanreed, which a sway test must not.
     */
    private fun bindingStore() = FileBindingStore(
        configStore = store,
        identities = { key, residuePath ->
            if (mintDelayMs > 0) delay(mintDelayMs)
            minted++
            DerivedAgentIdentities(store).mint(key, residuePath)
        },
        path = stateDir.resolve("bindings.json"),
    )

    @AfterTest
    fun tearDown() {
        if (!enabled) return
        killFrozen()
        scope.cancel()
        sway.close()
        stateDir.toFile().deleteRecursively()
    }

    @Test
    fun `dock is a sibling inside the tab, not a sibling of the tab`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")

        wm.attach(app1, dockFor("aw-dock1"), AgentId("agent-1"))

        val workspace = assertNotNull(wm.tree().workspace("1"))
        assertEquals(
            2,
            workspace.children.size,
            "the tabbed workspace must still hold exactly two tabs; a dock that became a " +
                "sibling of the tab would show up as a third",
        )
        val tab = assertNotNull(workspace.children.firstOrNull { it.find(app1.raw) != null })
        assertEquals("splith", tab.layout, "tab 1's contents should be a split container")
        assertEquals(
            setOf(app1.raw, dockId("aw-dock1")),
            tab.children.map { it.id }.toSet(),
            "app and dock share the tab",
        )
        assertNotNull(workspace.children.firstOrNull { it.id == app2.raw }, "tab 2 is untouched")
    }

    @Test
    fun `surfaces excludes docks`() = swayTest {
        val app = openSurface("aw-app1")
        wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1"))

        val appIds = wm.surfaces().map { it.appId }
        assertEquals(listOf("aw-app1"), appIds, "the dock is a real tree node but not a surface")
    }

    /**
     * The dock is enumerable as an ordinary surface for the length of one round trip if the mark
     * is the only thing that says otherwise. `attach` spawns the dock, waits for it to map, and
     * marks it afterwards — so an enumeration landing in between hands the agent panel back as
     * bindable, `resolve` calls it a Drab, and anything acting on that mints a Lifeless for the
     * panel and writes it to the durable registry.
     *
     * Enumeration shares one IPC connection with the attach that is running and that connection
     * serialises requests, so a poll waiting on its mutex is handed the tree in exactly the gap
     * between `awaitWindow`'s successful read and the `mark` command.
     */
    @Test
    fun `a dock is not enumerable before its mark lands`() = swayTest {
        repeat(PREMAP_ROUNDS) { round ->
            val app = openSurface("aw-app$round")
            val leaked = mutableListOf<Surface>()

            coroutineScope {
                val attaching =
                    async { wm.attach(app, dockFor("aw-dock"), AgentId("agent-$round")) }
                while (attaching.isActive) {
                    leaked += wm.surfaces().filter { it.appId == "aw-dock" }
                    yield()
                }
                attaching.await()
            }

            assertEquals(
                emptyList(),
                leaked.map { it.id.raw }.distinct(),
                "a dock was enumerable as an ordinary surface while its own attach was still " +
                    "running, in round $round",
            )
        }
    }

    /**
     * The mark is a hint that outlives an awakener restart, not the truth: sway's mark namespace
     * is global, so a second attach on one surface takes the mark off the first dock (#14). While
     * this process remembers standing a dock up, losing the mark does not make it a surface.
     */
    @Test
    fun `a dock whose mark is taken off it is still not a surface`() = swayTest {
        val app = openSurface("aw-app1")
        val handle = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1"))

        command("[con_id=${handle.dockId.raw}] unmark ${markFor(app)}")

        assertEquals(emptyList(), marksOf(handle.dockId), "the mark really is gone")
        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "the dock is still a dock: this process stood it up and has not forgotten it",
        )
    }

    /**
     * The other half of the choice. Recognising a dock only by what awakener wrote into the tree
     * puts the whole truth in `swaymsg -t get_tree`, which is the lever to reach for when the
     * in-memory record is suspected of hiding a real window — at the cost this issue is about.
     */
    @Test
    fun `dock recognition is switchable to the mark alone`() = swayTest {
        store.put(WmFlags.dockRecognition, DockRecognition.MARK_ONLY)
        val app = openSurface("aw-app1")
        val handle = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1"))

        command("[con_id=${handle.dockId.raw}] unmark ${markFor(app)}")

        assertEquals(
            setOf(app.raw, handle.dockId.raw),
            wm.surfaces().map { it.id.raw }.toSet(),
            "with the flag on the mark is the only evidence, so an unmarked dock is a surface " +
                "again — the hazard, kept reproducible on purpose",
        )
    }

    /**
     * A mark under the dock prefix whose suffix is not a `con_id` is a user's mark on a real
     * window — `mark notes` is an ordinary thing to have bound to a key, and the namespace is
     * shared. Treating it as a dock made that window invisible to enumeration and unresolvable,
     * while the orphan sweep, which did validate the suffix, skipped it: unreachable by every
     * path at once and reported by none (#15).
     */
    @Test
    fun `a user's mark under the dock prefix hides nothing and is reported`() = swayTest {
        val app = openSurface("aw-app1")
        val userMark = "${WmFlags.dockMarkPrefix.default}notes"

        command("[con_id=${app.raw}] mark --add $userMark")

        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "a dock mark is the prefix plus a con_id; this is neither, so the window stands",
        )
        assertEquals(
            setOf(userMark),
            wm.unrecognisedDockMarks.value,
            "and it is named rather than passed over, which is what made this cost a probe",
        )
    }

    /**
     * The reservation covers the dock from before it exists, so it has to be given back whether
     * the attach worked or not — and a leaked one is invisible in the tree while hiding every
     * window under the dock's `app_id` for the life of the process. Cancelled rather than left to
     * time out, because a reservation that has merely expired would prove nothing about eviction.
     */
    @Test
    fun `a cancelled attach gives its reservation back`() = swayTest {
        val app = openSurface("aw-app1")

        coroutineScope {
            // A dock command that maps its window under a different name: the attach then never
            // identifies a dock and sits in its map wait with the reservation outstanding, and
            // that window appearing is proof the exec — and so the reservation filed just before
            // it — has happened.
            val attaching = async {
                wm.attach(app, DockSpec("aw-dock", sway.windowCommand("aw-decoy")), AgentId("a-1"))
            }
            val decoy = SurfaceId(assertNotNull(awaitWindow("aw-decoy"), "no decoy window"))

            assertTrue(
                wm.surfaces().any { it.id == decoy },
                "a reservation covers one app_id, not every window that maps during an attach",
            )
            attaching.cancelAndJoin()
        }

        val other = openSurface("aw-dock")
        assertTrue(
            wm.surfaces().any { it.id == other },
            "a window under the dock's app_id must be enumerable once no attach is in flight",
        )
    }

    /**
     * Hazard 1. sway remembers the last-focused child per container, so a tab left resting on
     * the dock sends the next tab switch — and the user's next keystrokes — into the agent
     * panel rather than the application.
     */
    @Test
    fun `tab switch lands on the app, not the dock`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        wm.attach(app1, dockFor("aw-dock1"), AgentId("agent-1"))

        command("[con_id=${app2.raw}] focus")
        command("focus left")

        assertEquals(app1.raw, focusedId(), "switching into tab 1 must land on the application")
    }

    @Test
    fun `resting focus is switchable to the dock`() = swayTest {
        store.put(WmFlags.restingFocus, RestingFocus.DOCK)
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        val handle = wm.attach(app1, dockFor("aw-dock1"), AgentId("agent-1"))

        command("[con_id=${app2.raw}] focus")
        command("focus left")

        assertEquals(handle.dockId.raw, focusedId(), "the flag must actually change behaviour")
    }

    /** Hazard 2: sway leaves the dock standing when its surface dies. */
    @Test
    fun `orphaned dock is reaped when its surface closes`() = swayTest {
        val app = openSurface("aw-app1")
        val handle = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1"))

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        wm.reapOrphans()

        // Deliberately not waiting for the dock here. sway acknowledges `kill` when it has asked
        // the client to close, not when the window unmaps, and detach used to return on that
        // acknowledgement — so a sweep reported a repair it had not yet made, and the next sweep
        // (there is one per `close` event) found the same dock still in the tree and killed it
        // again. Waiting here hid that: the dock was still standing on 70 runs out of 70 against
        // the unfixed code, so this assertion is where the race stops being a matter of luck.
        assertNull(wm.tree().find(handle.dockId.raw), "the dock must not outlive its surface")
    }

    /**
     * The same repair driven by several sweeps at once, which is the ordinary case rather than a
     * contrived one: `reapOrphans` is driven off the `close` event, and closing a workspace's
     * worth of windows emits a burst of them.
     *
     * Unfixed, each sweep killed a dock and returned while it was still standing, so the next
     * sweep saw it in the tree and killed it a second time — and sway rejects criteria that match
     * nothing, so whichever loser's command happened to land in the millisecond the window
     * unmapped threw out of the middle of `reapOrphans`, leaving every orphan after it in that
     * pass standing. That is a transient race turning into permanent tree damage, since the
     * `close` that would have triggered the next sweep has already been and gone.
     *
     * Intermittent by nature, so it is repeated: one round of this shape failed on 44 of 70 runs
     * against `main`, and the test as written on 20 of 20.
     */
    @Test
    fun `concurrent reaps do not fight over the same dock`() = swayTest {
        repeat(REAP_ROUNDS) {
            val docks = orphans(ORPHANS_PER_ROUND)

            coroutineScope {
                List(ORPHANS_PER_ROUND) { async { wm.reapOrphans() } }.forEach { it.await() }
            }

            val root = wm.tree()
            assertEquals(
                emptyList(),
                docks.filter { root.find(it.raw) != null },
                "every orphan must be down by the time the sweeps return",
            )
        }
    }

    /**
     * The consequence that actually matters. `reapOrphans` is the whole mechanism for Hazard 2,
     * and it gets one shot per `close` event — so a teardown that throws partway through does not
     * merely lose that dock, it strands every orphan the sweep had not reached yet, with nothing
     * scheduled to come back for them.
     *
     * The failure here is a real sway rejection rather than an injected one: `split none` is
     * refused on a child that still has siblings, so a tab holding two other windows besides the
     * dock cannot be normalised. A user splitting a second window into a tab produces exactly
     * that shape.
     */
    @Test
    fun `a failing teardown does not abandon the rest of the sweep`() = swayTest {
        val stuck = openSurface("aw-app1")
        val clean = openSurface("aw-app2")
        val stuckDock = wm.attach(stuck, dockFor("aw-dock"), AgentId("agent-1"))

        // Two extra windows in the stuck dock's tab, so the container it leaves behind has more
        // than one survivor and sway will refuse to flatten it.
        command("[con_id=${stuck.raw}] focus")
        val extra = openSurface("aw-app3")
        command("[con_id=${extra.raw}] focus")
        openSurface("aw-app4")

        val cleanDock = wm.attach(clean, dockFor("aw-dock"), AgentId("agent-2"))
        for (surface in listOf(stuck, clean)) {
            command("[con_id=${surface.raw}] kill")
            awaitGone(surface)
        }

        assertFailsWith<IllegalStateException>("a teardown that fails must still be reported") {
            wm.reapOrphans()
        }

        val root = wm.tree()
        assertNull(
            root.find(cleanDock.dockId.raw),
            "the orphan after the failing one must still have been reaped — abandoning the rest " +
                "of the sweep is what turns one lost race into tree damage nothing repairs",
        )
        assertNull(root.find(stuckDock.dockId.raw), "and the failing dock itself is still down")
    }

    /**
     * The one teardown failure the collection machinery could not see. `awaitGone` returning
     * false left `detach` by an ordinary `return`, so the dock that *genuinely refuses to die* —
     * the case the sweep's failure handling exists for — was the single failure nothing reported.
     * The sweep gets one pass per `close` event and that event has been and gone, so nothing is
     * scheduled to come back for it either: the orphan survives, the sweep claims success, and
     * every later sweep pays the full window wait on it in silence.
     *
     * A `SIGSTOP`ped client is what a wedged panel program looks like to sway: nothing is left to
     * service the `xdg_toplevel` close, so the window stays mapped while `kill` is acknowledged
     * all the same. Real, rather than an injected failure — and deterministic, since a stopped
     * process cannot come back on its own.
     */
    @Test
    fun `a dock that will not die is reported and does not stop the sweep`() = swayTest {
        val wedged = openSurface("aw-app1")
        val clean = openSurface("aw-app2")
        val wedgedDock = wm.attach(wedged, dockFor("aw-dock"), AgentId("agent-1"))
        val cleanDock = wm.attach(clean, dockFor("aw-dock"), AgentId("agent-2"))

        freeze(wedgedDock.dockId)
        for (surface in listOf(wedged, clean)) {
            command("[con_id=${surface.raw}] kill")
            awaitGone(surface)
        }

        assertEquals(
            listOf(wedgedDock.dockId.raw, cleanDock.dockId.raw),
            wm.tree().windows.map { it.id },
            "the sweep walks the tree in order, so the wedged dock has to be the one it reaches " +
                "first, or 'the rest of the sweep still ran' is not what this proves",
        )

        val failure = assertFailsWith<IllegalStateException>(
            "a dock still standing when its wait runs out is a failed teardown, and the whole " +
                "point of the sweep is that it does not report a repair it has not made",
        ) { wm.reapOrphans() }

        assertTrue(
            wedgedDock.dockId.raw.toString() in failure.message.orEmpty(),
            "an aggregate over a sweep of N docks has to name the dock each failure came from " +
                "or it is not diagnosable, and reapOrphans has no production caller yet to say " +
                "it on its behalf; the message was: ${failure.message}",
        )
        assertNotNull(
            wm.tree().find(wedgedDock.dockId.raw),
            "the wedge has to be real: if the dock came down anyway this proves nothing",
        )
        assertNull(
            wm.tree().find(cleanDock.dockId.raw),
            "and the sweep must still have reaped the orphan after it — a dock that will not " +
                "come down costs that dock, not the rest of the pass",
        )
    }

    /**
     * The other half of the choice. Raising is right when the timeout means the dock is wedged,
     * and wrong when it only means a real panel program is slower to exit than the window wait —
     * where the dock does come down a moment later and the failure is pure noise. Which of those
     * a given panel is, is a fact about the desktop rather than about this code.
     */
    @Test
    fun `treating a wedged dock as a failed detach is switchable off`() = swayTest {
        store.put(WmFlags.wedgedDockFailsDetach, false)
        val app = openSurface("aw-app1")
        val handle = wm.attach(app, dockFor("aw-dock"), AgentId("agent-1"))

        freeze(handle.dockId)
        command("[con_id=${app.raw}] kill")
        awaitGone(app)

        wm.reapOrphans()

        assertNotNull(
            wm.tree().find(handle.dockId.raw),
            "with the flag off the dock is left standing and the sweep says nothing about it — " +
                "the blindness the default exists to avoid, kept reproducible on purpose",
        )
    }

    /**
     * Hazard 3, the sharpest of the three: sway does not collapse a single-child split
     * container, and the leftover container silently adopts the next window opened in that tab.
     */
    @Test
    fun `detaching normalises the container so later windows are not swallowed`() = swayTest {
        val app1 = openSurface("aw-app1")
        val handle = wm.attach(app1, dockFor("aw-dock1"), AgentId("agent-1"))
        handle.detach()

        val newSurface = openSurface("aw-app3")

        val workspace = assertNotNull(wm.tree().workspace("1"))
        assertTrue(
            workspace.children.any { it.id == newSurface.raw },
            "a window opened after detach must become its own tab, not get swallowed into the " +
                "container the dock left behind",
        )
        assertEquals(2, workspace.children.size, "two surfaces means two tabs")
    }

    @Test
    fun `container normalisation is switchable off`() = swayTest {
        store.put(WmFlags.normalizeContainerOnDetach, false)
        val app1 = openSurface("aw-app1")
        val handle = wm.attach(app1, dockFor("aw-dock1"), AgentId("agent-1"))
        handle.detach()

        val tab = assertNotNull(wm.tree().workspace("1")).children.single()
        assertEquals(
            "splith",
            tab.layout,
            "with normalisation off the leftover container should still be there — this is the " +
                "hazard, kept reproducible on purpose",
        )
    }

    /**
     * The point of the durable layer: the same application gets the same agent after a restart,
     * even though sway hands out a brand-new `con_id` for the new window. Simulated by throwing
     * away the window *and* the manager, and rebuilding both over the same bindings file.
     */
    @Test
    fun `a binding survives the window and the process that made it`() = swayTest {
        val app = openSurface("aw-app1")
        wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1"))

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        wm = SwayWindowManager({ sway.connection() }, store, bindingStore(), scope)
        val reopened = openSurface("aw-app1")

        assertTrue(reopened != app, "sway must have minted a new con_id for the new window")
        assertEquals(
            AgentId("agent-1"),
            wm.resolve(reopened),
            "the same surface must resolve to the same agent across a restart",
        )
    }

    /** Closing a panel is not the user asking for a different agent. */
    @Test
    fun `detaching the dock leaves the binding standing`() = swayTest {
        val app = openSurface("aw-app1")
        wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).detach()

        assertEquals(AgentId("agent-1"), wm.resolve(app), "the durable binding outlives the dock")
    }

    @Test
    fun `forgetting on detach is switchable on`() = swayTest {
        store.put(WmFlags.forgetBindingOnDetach, true)
        val app = openSurface("aw-app1")
        wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).detach()

        assertNull(wm.resolve(app), "with the flag on, the dock's lifetime is the agent's")
    }

    /** A window nobody has invoked a hotkey on is a Drab: enumerable, but bound to nothing. */
    @Test
    fun `an unbound surface resolves to nothing`() = swayTest {
        assertNull(wm.resolve(openSurface("aw-app1")))
    }

    /**
     * The hotkey path: nobody upstream holds an agent for a Drab, so attaching without one is
     * what mints a Lifeless — and it is the only moment anything is minted, since a trigger on
     * window creation would spawn an agent for every window glanced at and closed.
     */
    @Test
    fun `attaching without an agent mints one and keeps it`() = swayTest {
        val app = openSurface("aw-app1")
        assertNull(wm.resolve(app), "a Drab to start with")

        val handle = wm.attach(app, dockFor("aw-dock1"))

        assertEquals(1, minted, "the surface had no agent, so one had to be minted")
        assertEquals(handle.agent, wm.resolve(app), "and the mint is what the surface resolves to")

        handle.detach()
        wm.attach(app, dockFor("aw-dock2"))
        assertEquals(1, minted, "a surface that is already bound must not cost a second mint")
    }

    /**
     * In production every dock is the same panel program, so every dock reports the same
     * `app_id`. Matching the spawned dock on `app_id` alone therefore resolved every attach
     * after the first to the *first* dock's node: both marks landed on one window, the dock
     * that had actually just spawned was left unmarked and unmanaged, and `detach()` tore down
     * the other surface's panel.
     */
    @Test
    fun `two surfaces docked by the same program get their own dock`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")

        val dock1 = wm.attach(app1, dockFor("aw-dock"), AgentId("agent-1"))
        val dock2 = wm.attach(app2, dockFor("aw-dock"), AgentId("agent-2"))

        assertTrue(
            dock1.dockId != dock2.dockId,
            "the second attach must resolve to the dock it just spawned (${dock2.dockId.raw}) " +
                "and not to the first one (${dock1.dockId.raw})",
        )
        assertEquals(listOf(markFor(app1)), marksOf(dock1.dockId), "one mark per dock")
        assertEquals(listOf(markFor(app2)), marksOf(dock2.dockId), "one mark per dock")
        assertEquals(
            setOf(app1.raw, dock1.dockId.raw),
            assertNotNull(tabHolding(app1)).children.map { it.id }.toSet(),
            "each dock shares a tab with the surface it was attached to",
        )
        assertEquals(
            setOf(app2.raw, dock2.dockId.raw),
            assertNotNull(tabHolding(app2)).children.map { it.id }.toSet(),
            "each dock shares a tab with the surface it was attached to",
        )

        dock1.detach()
        awaitGone(dock1.dockId)

        assertNotNull(
            wm.tree().find(dock2.dockId.raw),
            "detaching one surface's dock must leave the other surface's dock standing",
        )
        assertEquals(listOf(markFor(app2)), marksOf(dock2.dockId), "and still bound to its own")
    }

    /**
     * The same failure again, this time from two attaches overlapping rather than following one
     * another. Nothing about this class was written under a single-threaded assumption —
     * `attach` is a public `suspend fun` with no stated contract, `DockHandle.close()` already
     * launches `detach()` on a scope, and orphan reaping is driven off the `changes` flow — so
     * two hotkeys pressed together are an ordinary case. Unserialised, both attaches snapshot
     * the standing docks before either `exec` lands and both then accept the first new node:
     * one window carrying both marks, and the dock that really belongs to the second surface
     * left unmarked and unmanaged. That is issue #2's symptom exactly, so identifying a dock by
     * node is only true if `attach` owns the tree for the length of the snapshot.
     */
    @Test
    fun `concurrent attaches by the same program get their own dock`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")

        val (dock1, dock2) = coroutineScope {
            val first = async { wm.attach(app1, dockFor("aw-dock"), AgentId("agent-1")) }
            val second = async { wm.attach(app2, dockFor("aw-dock"), AgentId("agent-2")) }
            first.await() to second.await()
        }

        assertTrue(
            dock1.dockId != dock2.dockId,
            "concurrent attaches must resolve to the docks they each spawned, not both to " +
                "${dock1.dockId.raw}",
        )
        assertEquals(
            mapOf(
                dock1.dockId.raw to listOf(markFor(app1)),
                dock2.dockId.raw to listOf(markFor(app2)),
            ),
            docksOf("aw-dock"),
            "every window the dock program produced must be exactly one surface's dock: a node " +
                "carrying both marks, or an unmarked orphan panel beside it, is #2 back again",
        )
        assertEquals(
            setOf(app1.raw, dock1.dockId.raw),
            assertNotNull(tabHolding(app1)).children.map { it.id }.toSet(),
            "interleaved focus/split must not land a dock in the other surface's tab",
        )
        assertEquals(
            setOf(app2.raw, dock2.dockId.raw),
            assertNotNull(tabHolding(app2)).children.map { it.id }.toSet(),
            "interleaved focus/split must not land a dock in the other surface's tab",
        )
    }

    /**
     * The third door into the same failure. `attach` spawns its dock with `exec`, and sway maps
     * the new window into whatever container is focused *when it maps* — so a bare `focus` landing
     * anywhere between `attach`'s own focus and the dock appearing hands the dock to a different
     * surface's tab. `DockHandle.focus` is exactly that bare focus, and it is what a hotkey on an
     * already-bound surface calls, so "two hotkeys at once" is one Drab and one bound surface just
     * as readily as two Drabs.
     */
    @Test
    fun `a focus during an attach does not land the dock in another surface's tab`() = swayTest {
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")
        val dock2 = wm.attach(app2, dockFor("aw-dock"), AgentId("agent-2"))

        val dock1 = coroutineScope {
            val attaching = async { wm.attach(app1, dockFor("aw-dock"), AgentId("agent-1")) }
            // Once app1 is focused the attach is past its own focus and has not yet claimed a
            // dock, which is the window a hotkey has to be safe in.
            awaitFocused(app1)
            dock2.focus()
            attaching.await()
        }

        assertEquals(
            setOf(app1.raw, dock1.dockId.raw),
            assertNotNull(tabHolding(app1)).children.map { it.id }.toSet(),
            "the dock attach just spawned belongs to app1's tab, not to wherever the focus went",
        )
        assertEquals(
            setOf(app2.raw, dock2.dockId.raw),
            assertNotNull(tabHolding(app2)).children.map { it.id }.toSet(),
            "and app2's tab must not have adopted another surface's agent panel",
        )
    }

    /**
     * The hotkey path mints, and the production minter is `SpanreedCli` — a subprocess bounded
     * only by a 10s timeout, twice over with `registry.register_on_mint` on. Serialising the
     * compositor is worth one dock's map time; it is not worth a process spawn, which would park
     * every other hotkey on the desktop behind one surface's agent being named. `:registry` made
     * the same call one layer down — see the comment on `FileBindingStore.bind` — so the binding
     * has to stay outside the tree section here too.
     */
    @Test
    fun `an attach waiting on a mint does not hold up another surface's attach`() = swayTest {
        mintDelayMs = MINT_DELAY_MS
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")

        coroutineScope {
            // No agent: the hotkey case, and the only thing that ever mints.
            val minting = async { wm.attach(app1, dockFor("aw-dock")) }
            // Its dock is marked, so its tree work is done and it is now inside the mint.
            awaitMarked(app1)

            wm.attach(app2, dockFor("aw-dock"), AgentId("agent-2"))

            assertEquals(
                0,
                minted,
                "the second surface's attach must not have to wait for the first one's mint: " +
                    "a mint completed before it returned, so the subprocess is inside the lock",
            )
            minting.await()
        }
    }

    /** The alternative identity scheme: unique by construction, at the cost of a dock argument. */
    @Test
    fun `per-surface app_id is switchable on`() = swayTest {
        store.put(WmFlags.dockIdentity, DockIdentity.PER_SURFACE_APP_ID)
        val app1 = openSurface("aw-app1")
        val app2 = openSurface("aw-app2")

        val dock1 = wm.attach(app1, dockFor("aw-dock"), AgentId("agent-1"))
        val dock2 = wm.attach(app2, dockFor("aw-dock"), AgentId("agent-2"))

        assertEquals("aw-dock-${app1.raw}", appIdOf(dock1.dockId))
        assertEquals("aw-dock-${app2.raw}", appIdOf(dock2.dockId))
        assertEquals(listOf(markFor(app1)), marksOf(dock1.dockId), "one mark per dock")
        assertEquals(listOf(markFor(app2)), marksOf(dock2.dockId), "one mark per dock")
    }

    // -- helpers ------------------------------------------------------------------------

    private fun swayTest(body: suspend () -> Unit) {
        if (!enabled) {
            println("skipping: sway/foot not installed")
            return
        }
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
        return SurfaceId(assertNotNull(awaitWindow(appId), "window '$appId' never appeared"))
    }

    private fun dockFor(appId: String) =
        DockSpec(appId, sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    /**
     * [count] docks whose surfaces have gone — the tree shape Hazard 2 leaves behind. Every
     * surface is opened before any dock is attached so that each becomes its own tab, which keeps
     * the orphans in sibling containers rather than nested inside one another.
     */
    private suspend fun orphans(count: Int): List<SurfaceId> {
        val apps = (1..count).map { openSurface("aw-app$it") }
        val docks = apps.map {
            wm.attach(it, dockFor("aw-dock"), AgentId("agent-${it.raw}")).dockId
        }
        apps.forEach {
            command("[con_id=${it.raw}] kill")
            awaitGone(it)
        }
        return docks
    }

    /**
     * Stops the client behind [window], so that it can no longer service a close request.
     *
     * The compositor's view of a wedged panel program: the window stays mapped, sway acknowledges
     * `kill` regardless, and no amount of waiting makes the node leave the tree.
     */
    private suspend fun freeze(window: SurfaceId) {
        val pid = assertNotNull(wm.tree().find(window.raw)?.pid, "no pid for ${window.raw}")
        frozen += pid
        signal("STOP", pid)
    }

    /**
     * Kills whatever [freeze] stopped, children first, before sway goes.
     *
     * Before, because a stopped client cannot notice the compositor leaving and would sit there
     * for the hour its `sleep` has left to run. Children first, because the client is running
     * that `sleep` as a child of itself and a killed process cannot take its children with it —
     * the child has to go while its parent is still there to enumerate it from. SIGKILL rather
     * than SIGTERM throughout: a stopped process is woken for a kill and for nothing else.
     */
    private fun killFrozen() {
        frozen.forEach { pid ->
            runCatching { ProcessBuilder("pkill", "-KILL", "-P", "$pid").start().waitFor() }
            runCatching { signal("KILL", pid) }
        }
        frozen.clear()
    }

    private fun signal(name: String, pid: Int) {
        val exit = ProcessBuilder("kill", "-$name", pid.toString()).start().waitFor()
        assertEquals(0, exit, "kill -$name $pid failed")
    }

    private fun markFor(surface: SurfaceId) =
        "${WmFlags.dockMarkPrefix.default}${surface.raw}"

    private suspend fun marksOf(dock: SurfaceId): List<String>? = wm.tree().find(dock.raw)?.marks

    /** Every window the dock program produced, against the marks it carries. */
    private suspend fun docksOf(appId: String): Map<Long, List<String>> =
        wm.tree().windows.filter { it.appId == appId }.associate { it.id to it.marks }

    private suspend fun appIdOf(dock: SurfaceId): String? = wm.tree().find(dock.raw)?.appId

    /** The tab a surface lives in — the workspace child that contains it. */
    private suspend fun tabHolding(surface: SurfaceId): Node? =
        wm.tree().workspace("1")?.children?.firstOrNull { it.find(surface.raw) != null }

    private suspend fun dockId(appId: String): Long =
        assertNotNull(wm.tree().windows.firstOrNull { it.appId == appId }).id

    private suspend fun focusedId(): Long? = wm.tree().windows.firstOrNull { it.focused }?.id

    /** Waits until [surface]'s dock has been marked, which is the last of `attach`'s tree work. */
    private suspend fun awaitMarked(surface: SurfaceId) {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().windows.none { markFor(surface) in it.marks }) yield()
                true
            },
            "no dock was ever marked for ${surface.raw}",
        )
    }

    /**
     * Waits until [surface]'s tab has become the split container `attach` creates, which is the
     * first of its tree work and happens before the reservation is filed.
     */
    private suspend fun awaitSplit(surface: SurfaceId) {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (tabHolding(surface)?.layout != "splith") yield()
                true
            },
            "${surface.raw} was never split for a dock",
        )
    }

    private suspend fun awaitFocused(surface: SurfaceId) {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (focusedId() != surface.raw) yield()
                true
            },
            "focus never reached ${surface.raw}",
        )
    }

    private suspend fun awaitWindow(appId: String): Long? = withTimeoutOrNull(WAIT_MS) {
        while (true) {
            wm.tree().windows.firstOrNull { it.appId == appId }?.let { return@withTimeoutOrNull it.id }
            yield()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private suspend fun awaitGone(id: SurfaceId) {
        withTimeoutOrNull(WAIT_MS) {
            while (wm.tree().find(id.raw) != null) yield()
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L

        /** Long enough that a second attach finishing inside it cannot be luck. */
        const val MINT_DELAY_MS = 2_000L

        /**
         * Shape of the concurrent-reap round. Six orphans swept by six sweeps is where the
         * unfixed race became likely rather than rare — measured 11/20, 16/25 and 17/25 — and
         * five rounds is what turns that into a test that fails every time it is run.
         */
        const val ORPHANS_PER_ROUND = 6
        const val REAP_ROUNDS = 5

        /**
         * Attaches raced against enumeration. The gap between a dock mapping and its mark
         * landing is one round trip on a connection the poller is already queued on, so one
         * round is nearly always enough; three is what made it every run.
         */
        const val PREMAP_ROUNDS = 3
    }
}
