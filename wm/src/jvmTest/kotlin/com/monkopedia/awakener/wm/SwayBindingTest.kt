package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.BindingStore
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.registry.asIdentity
import java.nio.file.Path
import kotlin.io.path.absolutePathString
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    /**
     * The lifetime of the manager [wm] currently holds, and of nothing else.
     *
     * A child of [scope], so `tearDown` still takes everything down in one call — but cancellable
     * on its own, which is what lets [restartAwakener] retire the manager it replaces. That is #56:
     * `SwayWindowManager` starts its repair collector in its constructor, so rebinding [wm] over a
     * manager built on the shared scope leaves the old collector subscribed to the same sway.
     * (This used to add "and offers no `close()`", which stopped being true at #116 —
     * [SwayWindowManager.close] exists and is what the product retires by.) Two collectors do not
     * agree: a sweep answers partly from the asking manager's table, so a manager that stood a dock
     * up reaps it while a manager that merely adopted it — or that cannot recognise it at all —
     * refuses (#72). That holds at stock flags and is only *widest* under
     * [ReapEvidence.STOOD_UP]. Which answer the tree ends up with was decided by timing.
     */
    private lateinit var wmScope: CoroutineScope
    private lateinit var wm: SwayWindowManager
    private lateinit var store: InMemoryConfigStore
    private lateinit var stateDir: Path

    /**
     * The same store the manager was built on, held so a test can write a durable binding
     * directly — which is how "what does `resolve` answer for a window enumeration is hiding"
     * becomes askable at all, since a hidden window is one no `attach` will take.
     */
    private lateinit var registry: BindingStore

    private var minted = 0

    /**
     * How long a mint takes. Zero everywhere but the one test that cares: the real minter is
     * `SpanreedCli`, which shells out, and a test may not.
     */
    private var mintDelayMs = 0L

    /**
     * Whether minting fails. The production minter shells out to spanreed, so this is the
     * ordinary way `attach` fails *after* its dock is standing — and the only such failure a test
     * can produce on demand, since every command `attach` sends between the map and the bind is
     * one sway accepts.
     */
    private var mintFails = false

    private val enabled get() = SwayHarness.available()

    @BeforeTest
    fun setUp() {
        SwayHarness.assumeAvailable()
        sway = SwayHarness.start()
        scope = CoroutineScope(SupervisorJob())
        store = InMemoryConfigStore()
        stateDir = createTempDirectory("awakener-wm-bindings")
        registry = bindingStore()
        wmScope = managerScope()
        wm = SwayWindowManager({ sway.connection() }, store, registry, wmScope)
    }

    /** One manager's lifetime, granted from [scope] so that cancelling [scope] still ends it. */
    private fun managerScope() = CoroutineScope(SupervisorJob(scope.coroutineContext.job))

    /**
     * An awakener restart: the running manager is retired and a fresh one comes up over the same
     * sway session, the same marks and the same standing docks — which is all a restarted process
     * would have.
     *
     * **Retiring the predecessor is the point, not tidiness.** A restart that leaves the old
     * manager's collector subscribed is not a restart; it is two awakeners, and #56 is what that
     * costs — the predecessor's sweep and the assertion race for the same dock, and under
     * [ReapEvidence.STOOD_UP] they want opposite things. Cancelling the outgoing manager's scope is
     * the lever this harness uses, which is why each manager gets its own — it is no longer the
     * *only* one, since #116 added [SwayWindowManager.close], and `SwayLifecycleTest` is where that
     * is exercised. This keeps the scope join because it covers the manager's other children too,
     * for the reason the body gives.
     *
     * Returns the retired manager, because what it does *after* being retired — nothing — is worth
     * asserting on.
     */
    private suspend fun restartAwakener(): SwayWindowManager {
        val outgoing = wm
        // The scope's job rather than `repairing` alone, and joined rather than merely cancelled.
        // `repairing` is the root the #56 race was measured through, but a manager launches two
        // others on the scope it was given — the subscription behind `changes`, and a `detach` a
        // closed handle starts — and joining the scope covers all three for one line. The join is
        // the part that is load-bearing either way: `cancel` only asks, and returning while the
        // old collector is still inside a sweep leaves the same race, just narrower.
        wmScope.coroutineContext.job.cancelAndJoin()
        wmScope = managerScope()
        wm = SwayWindowManager({ sway.connection() }, store, bindingStore(), wmScope)
        return outgoing
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
            check(!mintFails) { "the minter is unavailable" }
            minted++
            DerivedAgentIdentities(store).mint(key, residuePath)
        },
        path = stateDir.resolve("bindings.json"),
    )

    @AfterTest
    fun tearDown() {
        if (!enabled) return
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
     * The mark is a hint that outlives an awakener restart, not the truth: it lives in a namespace
     * the user writes into as well, so it can go without awakener doing anything. While this
     * process remembers standing a dock up, losing the mark does not make it a surface.
     */
    @Test
    fun `a dock whose mark is taken off it is still not a surface`() = swayTest {
        val app = openSurface("aw-app1")
        val handle = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1"))

        command("[con_id=${handle.dockId.raw}] unmark ${soleMarkOf(handle.dockId)}")

        assertEquals(emptyList(), marksOf(handle.dockId), "the mark really is gone")
        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "the dock is still a dock: this process stood it up and has not forgotten it",
        )
    }

    /**
     * The same loss, one restart later, which is the case the previous test does *not* cover:
     * there the table was still holding the dock this process stood up, so nothing rested on
     * adoption. After a restart the mark is the only evidence a fresh manager ever had, and if
     * adoption is only a read-time union it leaves nothing behind — so the moment the mark goes,
     * the panel is neither marked nor tabled and enumeration hands it back as bindable. That is
     * the expensive false negative: a hotkey acting on it mints a Lifeless for the agent panel
     * and writes it to the durable registry, while `reapOrphans`, answering from the same
     * predicate, cannot see the panel to clean it up.
     *
     * The enumeration between the restart and the unmark is the adoption: it must record the
     * dock, not merely answer about it.
     *
     * The mark is taken off by hand because that is now the only thing that takes one off. This
     * test used to drive it with a second attach on the same surface, which is #14: while the
     * mark named only the surface, sway moved it to the second dock. It no longer does, and what
     * this test is about — that an adoption is a write — is unchanged either way.
     */
    @Test
    fun `an adopted dock stays a dock once its mark is taken off it`() = swayTest {
        val app = openSurface("aw-app1")
        val first = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).dockId

        // awakener restarts: a fresh manager with an empty table, over a tree and marks sway
        // has kept untouched.
        restartAwakener()
        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "the mark is the whole of what the new process knows, and here it is enough",
        )

        command("[con_id=${first.raw}] unmark ${soleMarkOf(first)}")

        assertEquals(emptyList(), marksOf(first), "the mark really is gone")
        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "the dock was adopted at the enumeration above and stays a dock: an adoption that " +
                "leaves no record hands the agent panel back the moment the mark goes",
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

        command("[con_id=${handle.dockId.raw}] unmark ${soleMarkOf(handle.dockId)}")

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
     * The cost side of materialising adoption, and the one place it must not be paid.
     *
     * A mark somebody else wrote, shaped exactly like the marked window's *own* dock mark — which
     * since #35 means a well-formed nonce and not merely two `con_id`s, but the shape is the whole
     * of what the predicate asks either way — hides a genuine application window. That used to be
     * self-healing — `swaymsg unmark` handed the window straight back — and recording what a read
     * recognises makes it permanent, because nothing withdraws a record. Enumeration keeps hiding
     * the window here on purpose; `wm.dock.recognition=MARK_ONLY` is the lever that releases it.
     *
     * What must not follow is the sweep destroying it. The window carries no dock mark at all by
     * then, and this process never stood it up, so the only thing saying "dock" is a recognition
     * latched at an earlier read — and the note's bar for acting destructively on a coarse
     * predicate is explicit: a user's window is not recoverable at all.
     */
    @Test
    fun `a window whose dock-shaped mark is gone stays hidden but is not killed`() = swayTest {
        val app = openSurface("aw-app1")
        val victim = openSurface("aw-app2")

        command("[con_id=${victim.raw}] mark --add ${markFor(victim, app)}")
        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "while the mark is on, the pinned predicate says dock and the window is hidden",
        )

        command("[con_id=${victim.raw}] unmark ${markFor(victim, app)}")
        assertEquals(emptyList(), marksOf(victim), "the user's mark really is gone")
        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "documented rather than desired: the enumeration above recorded the node, and a " +
                "record is never withdrawn, so the window stays hidden until MARK_ONLY or a " +
                "restart releases it",
        )

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        wm.reapOrphans()

        assertNotNull(
            wm.tree().find(victim.raw),
            "and this is the line: a latched recognition may hide a window, which the user can " +
                "get back, but it may not kill one, which they cannot",
        )
    }

    /**
     * The hazard kept reproducible, the same way `MARK_ONLY` keeps its own. Reaping on whatever
     * enumeration recognises closes one real gap — an adopted dock whose mark has since been
     * taken off it is then still swept — and the price is this window.
     */
    @Test
    fun `reaping on a latched recognition is switchable on`() = swayTest {
        store.put(WmFlags.reapEvidence, ReapEvidence.RECOGNITION)
        val app = openSurface("aw-app1")
        val victim = openSurface("aw-app2")

        command("[con_id=${victim.raw}] mark --add ${markFor(victim, app)}")
        wm.surfaces()
        command("[con_id=${victim.raw}] unmark ${markFor(victim, app)}")

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        wm.reapOrphans()

        awaitGone(victim)
        assertNull(
            wm.tree().find(victim.raw),
            "with the flag flipped the sweep acts on the record alone, which is what costs the " +
                "user's window — the flag must actually change behaviour",
        )
    }

    /**
     * The other direction, so that requiring current evidence has not quietly stopped the sweep
     * from doing its job. A restarted awakener knows a standing dock only by its mark — and the
     * mark is evidence that exists now, so the orphan still comes down.
     */
    @Test
    fun `a dock adopted after a restart is still reaped when its surface closes`() = swayTest {
        // What is under test is the *restarted* manager's sweep, working from the mark alone. That
        // used to need `sweep_on_close=false`, because the manager that stood the dock up was still
        // collecting and would have reaped it from its own table first — proving nothing about
        // adoption. `restartAwakener` retires it instead, so the event-driven path stays live and
        // every sweep here is the adopting manager's.
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).dockId

        restartAwakener()
        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "the fresh manager adopts the dock from its mark",
        )

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        wm.reapOrphans()

        assertNull(wm.tree().find(dock.raw), "the dock must not outlive its surface")
    }

    /**
     * #14, at the level where it happens: sway's mark namespace is one global set of unique
     * identifiers, so a mark derived from the *surface* is a name that two docks of one surface
     * both want, and marking the second takes it off the first. Nothing forbids a second attach —
     * `WindowManager` says the hotkey path on a bound surface should call `DockHandle.focus`, but
     * that is a caller convention rather than a guard, and `attach` is documented as safe to call
     * concurrently.
     *
     * Asserted twice over, because the table hides the first half from behaviour. While this
     * process is the one that stood both docks up it remembers them whatever their marks say, so
     * the loss shows only in the tree; the fresh manager below is the awakener restart that has
     * nothing but the marks to read, and it is where a dock with no mark left comes back as a
     * bindable surface and stops being reapable.
     *
     * Deliberately says nothing about the mark's *format* — that is `dockMarkFor`'s, and this test
     * has to keep meaning the same thing if it changes again.
     */
    @Test
    fun `a second attach on one surface leaves the first dock its own mark`() = swayTest {
        // What the marks carry is the whole point here, so the manager that stood these docks up
        // must not be the one that reaps them from its own table. That used to be bought with
        // `sweep_on_close=false`; `restartAwakener` retires the manager instead, which buys it
        // without also turning off the event-driven path this test then runs through.
        val app = openSurface("aw-app1")
        val first = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).dockId
        val second = wm.attach(app, dockFor("aw-dock2"), AgentId("agent-1")).dockId

        val firstMarks = marksOf(first).orEmpty()
        val secondMarks = marksOf(second).orEmpty()
        assertTrue(
            firstMarks.isNotEmpty(),
            "the second attach took the first dock's mark off it: sway moves a mark rather than " +
                "copying it, so a mark naming the surface is one two docks of that surface cannot " +
                "both hold",
        )
        assertTrue(secondMarks.isNotEmpty(), "the second dock is marked too")
        assertEquals(
            emptySet(),
            firstMarks.toSet() intersect secondMarks.toSet(),
            "and each mark names one dock: a string both wear is a string neither identifies",
        )

        // awakener restarts. The marks are the whole of what a fresh process knows, which is what
        // makes them worth being per-dock.
        restartAwakener()
        assertEquals(
            listOf(app.raw),
            wm.surfaces().map { it.id.raw },
            "a dock the restart cannot recognise is handed back as a bindable surface, and a " +
                "hotkey on it mints a Lifeless for the agent panel",
        )

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        wm.reapOrphans()

        assertNull(wm.tree().find(first.raw), "the first dock must not outlive its surface")
        assertNull(wm.tree().find(second.raw), "nor the second")
    }

    /**
     * The previous mark kept reachable, and the hazard with it — the recovery if an upgrade lands
     * while docks are standing has to be a flag rather than a downgrade. Under `SURFACE` the two
     * docks of one surface want one identifier and sway gives it to whichever was marked last,
     * which is #14 as the compositor performs it.
     */
    @Test
    fun `the surface-only dock mark is switchable back on`() = swayTest {
        store.put(WmFlags.dockMarkScheme, DockMarkScheme.SURFACE)
        val app = openSurface("aw-app1")
        val first = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).dockId
        val second = wm.attach(app, dockFor("aw-dock2"), AgentId("agent-1")).dockId

        assertEquals(emptyList(), marksOf(first), "#14, kept reproducible on purpose")
        assertEquals(
            listOf(dockMarkFor(second, app, WmFlags.dockMarkPrefix.default, DockMarkScheme.SURFACE)),
            marksOf(second),
            "the flag has to actually change what attach writes",
        )
    }

    /**
     * #15's live half. `mark` is a user-facing verb in a single global namespace, so a mark under
     * awakener's prefix is an ordinary thing for a personal sway config to produce — and while a
     * dock mark meant "the prefix and *the bound surface's* con_id", `awakener_dock_<n>` for any
     * live `n` was one, on whatever window wore it.
     *
     * That the suffix is a number is the whole of the difference from the `notes` case above, and
     * it is the half the pinned predicate did not close: enumeration and the sweep agree about it,
     * and what they agree on is wrong.
     */
    @Test
    fun `a user's mark of the prefix and another window's con_id hides nothing`() = swayTest {
        val app = openSurface("aw-app1")
        val victim = openSurface("aw-app2")
        val userMark = "${WmFlags.dockMarkPrefix.default}${app.raw}"

        command("[con_id=${victim.raw}] mark --add $userMark")

        assertEquals(
            setOf(app.raw, victim.raw),
            wm.surfaces().map { it.id.raw }.toSet(),
            "a mark the user wrote on their own window must not remove it from enumeration: " +
                "every surface gets an agent, and one that cannot be enumerated never gets one",
        )
        assertNotNull(
            wm.keyFor(victim),
            "and it stays bindable — keyFor answers from surfaces(), so attach would say 'no " +
                "such surface' for a window that is plainly there",
        )
        assertEquals(
            setOf(userMark),
            wm.unrecognisedDockMarks.value,
            "reported rather than passed over: a mark under the prefix that is not a dock mark " +
                "is the one thing that would have made this take minutes rather than a probe",
        )
    }

    /**
     * The same mark, and the consequence #18 added. A sweep runs on every window close now, so a
     * window mis-recognised as a dock is reachable by a *destructive* path and not merely by an
     * enumeration one — and `wm.dock.reap_evidence=CURRENT` is no defence here, because the mark
     * the sweep is asked about is on the node at the moment it looks. It is the user's mark.
     *
     * The bar is the design note's own, set for `RECLAIM` and applying unchanged: a window hidden
     * is recoverable and a window destroyed is not.
     */
    @Test
    fun `the sweep does not destroy a window carrying a user's prefix-shaped mark`() = swayTest {
        val app = openSurface("aw-app1")
        val victim = openSurface("aw-app2")

        command("[con_id=${victim.raw}] mark --add ${WmFlags.dockMarkPrefix.default}${app.raw}")

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        awaitSweep()
        // By hand as well, so that the assertion does not rest on the collector having got there.
        wm.reapOrphans()

        assertNotNull(
            wm.tree().find(victim.raw),
            "the sweep killed a window on the strength of a mark the user wrote themselves — " +
                "the con_id in it named a different window, which has now closed",
        )
    }

    /**
     * #35, in the shape the issue names: a genuine window carrying `<prefix><that window's own
     * con_id>_for_<a con_id that then closes>`.
     *
     * That mark passed the self-check `DOCK_AND_SURFACE` was the whole of — it does name the node
     * it sits on — so the one predicate called the window a dock, `wm.dock.reap_evidence=CURRENT`
     * was satisfied because the mark was on the node at the moment the sweep looked, and the sweep
     * destroyed it. Measured against `0e2446b7`, where this assertion is red.
     *
     * What closes it is the default scheme's nonce field: the string above is no longer a dock
     * mark at all, so the window is enumerated, is named in `unrecognisedDockMarks`, and nothing
     * has evidence to kill it with. The bar is the design note's own — a window hidden is
     * recoverable and a window destroyed is not — and this shape now clears both halves of it.
     */
    @Test
    fun `a user mark naming its own window and a dead con_id no longer costs that window`() =
        swayTest {
            val app = openSurface("aw-app1")
            val victim = openSurface("aw-app2")
            val userMark = dockMarkFor(
                victim,
                app,
                WmFlags.dockMarkPrefix.default,
                DockMarkScheme.DOCK_AND_SURFACE,
            )

            command("[con_id=${victim.raw}] mark --add $userMark")

            assertEquals(
                setOf(app.raw, victim.raw),
                wm.surfaces().map { it.id.raw }.toSet(),
                "it is not a dock mark, so the window is not hidden either",
            )
            assertEquals(
                setOf(userMark),
                wm.unrecognisedDockMarks.value,
                "and it is named rather than passed over, which is the whole of the diagnosis",
            )

            command("[con_id=${app.raw}] kill")
            awaitGone(app)
            awaitSweep()
            // By hand as well, so that what is asserted does not rest on collector timing.
            wm.reapOrphans()

            assertNotNull(
                wm.tree().find(victim.raw),
                "the sweep destroyed a window on the strength of a mark whose only claim was " +
                    "naming its own node — the shape #35 is about",
            )
        }

    /**
     * **This test documents what awakener does today; it does not prove a fix, and the behaviour
     * it pins is a defect.** It is the successor to `the residual the self-check leaves is a
     * destroyed window, not a hidden one`, which pinned the shape the test above now closes, and
     * it is here for the same reason: so that the cost stated at `WmFlags.dockMarkScheme` and in
     * `docs/design-notes/wm-dock-ownership.md` is measured rather than reasoned.
     *
     * What the nonce does not buy. It is verified by shape, and it has to be — the process that
     * reads a mark is routinely a later awakener that never saw it written — so a well-formed
     * nonce written by any other hand is a dock mark, and the sweep destroys that window when the
     * `con_id` after `_for_` closes. There is no privileged channel to close this with: sway sets
     * a mark through `RUN_COMMAND` on the socket `swaymsg` speaks, so every mark awakener can
     * write a hand can write too, measured on sway 1.12.
     *
     * What is left is therefore a *deliberate* forgery — the shape is not one anybody reaches by
     * accident — and `wm.dock.reap_evidence=STOOD_UP`, in the test below, is what makes even that
     * harmless.
     */
    @Test
    fun `a nonce-shaped user mark is still destroyed, and only the reap evidence closes that`() =
        swayTest {
            val app = openSurface("aw-app1")
            val victim = openSurface("aw-app2")

            command("[con_id=${victim.raw}] mark --add ${markFor(victim, app)}")

            command("[con_id=${app.raw}] kill")
            awaitGone(app)
            awaitSweep()
            // By hand as well, so that what is asserted does not rest on collector timing.
            wm.reapOrphans()

            assertNull(
                wm.tree().find(victim.raw),
                "a well-formed dock mark is current evidence whoever wrote it, and the sweep " +
                    "kills on it",
            )
        }

    /**
     * The consequence narrowed rather than the trigger, which is what every fix on this defect so
     * far has done instead. Nothing in sway's tree is evidence a desktop cannot write — marks are
     * `swaymsg`'s to set, and so is the layout — so the only kind of proof a forged mark cannot
     * supply is awakener's own memory of having stood the dock up.
     *
     * The same forgery as the test above, and under this flag it costs nothing at all. The price
     * is in the test after it.
     */
    @Test
    fun `requiring a stood-up entry stops the sweep killing on any mark at all`() = swayTest {
        store.put(WmFlags.reapEvidence, ReapEvidence.STOOD_UP)
        val app = openSurface("aw-app1")
        val victim = openSurface("aw-app2")

        command("[con_id=${victim.raw}] mark --add ${markFor(victim, app)}")

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        awaitSweep()
        wm.reapOrphans()

        assertNotNull(
            wm.tree().find(victim.raw),
            "with the flag on, no mark is evidence for a kill: the only thing that is, is an " +
                "entry this process wrote when it stood the dock up",
        )
    }

    /**
     * What `STOOD_UP` costs, stated rather than designed around: it is the mark's own purpose. A
     * dock that outlived an awakener restart is recognised from its mark and adopted — never stood
     * up by *this* process — so nothing reaps it and its panel stands when its surface closes.
     *
     * Kept reproducible because it is the reason this is not the default: a leaked panel is
     * recoverable by hand, and under the default mark scheme the window it would protect needs a
     * mark nobody writes by accident.
     */
    @Test
    fun `a dock adopted after a restart is left standing under a stood-up requirement`() =
        swayTest {
            store.put(WmFlags.reapEvidence, ReapEvidence.STOOD_UP)
            val app = openSurface("aw-app1")
            val dock = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).dockId

            // awakener restarts: the mark is all a fresh manager has, and adoption is all it can do.
            restartAwakener()
            assertEquals(
                listOf(app.raw),
                wm.surfaces().map { it.id.raw },
                "the dock is still recognised — a nonce is read by shape, so a process that " +
                    "never saw it written reads the mark perfectly well",
            )

            command("[con_id=${app.raw}] kill")
            awaitGone(app)
            wm.reapOrphans()

            assertNotNull(
                wm.tree().find(dock.raw),
                "and it is left standing, which is the price of refusing to kill on a mark",
            )
        }

    /**
     * #56 as its mechanism rather than as its symptom, and the reason [restartAwakener] retires the
     * manager it replaces.
     *
     * The test above is the one that flaked — about 1 run in 20, in CI and on kaladin — because
     * under [ReapEvidence.STOOD_UP] *every* adopted dock is a disagreement: the manager that stood
     * the dock up holds `DockOrigin.STOOD_UP` for it and reaps, while the manager that adopted it
     * from its mark holds `ADOPTED` and refuses. Leave both collecting and one close event gets
     * both answers, with the assertion racing the predecessor's sweep for the same window.
     *
     * `STOOD_UP` is where that is *total*, not where it is possible. The default
     * [ReapEvidence.CURRENT] reads `stoodUp || <a mark readable now>`, so two managers diverge
     * there too wherever the successor cannot read the mark — measured in `a dock marked under the
     * other scheme is reported and left standing`, which sets no `reap_evidence` at all and fails
     * 3 runs out of 3 with the leak reintroduced and the race forced. Anything here that reads as
     * "only under an opt-in" is wrong; this test is pinned at `STOOD_UP` because that is the
     * cheapest place to observe the race, not the only one.
     *
     * So this one does not race it. The window is held open with [LEAKED_SWEEP_GRACE_MS] — the
     * instrument from #56, where a delay in exactly this position turned a 1-in-20 flake into every
     * run failing — and the retired manager is then asked directly what it swept. Reintroduce the
     * leak by rebinding `wm` without retiring, and this fails every run instead of occasionally,
     * which is the whole difference between a guard and a second lottery ticket.
     *
     * It says nothing about whether the *product* should stop two managers overlapping. That was
     * #72's second half; #72 closed with the half it did settle, and the open half moved to
     * **#85**, which #116 settled by adding [SwayWindowManager.close]. Follow #85, not #72 — a
     * pointer at a closed issue reads as "already answered", which was true of #72's first half
     * and not of this. What this test asserts is only that a caller which retires one manager
     * gets what retirement is for.
     */
    @Test
    fun `a retired manager sweeps nothing, so a restart cannot race it`() = swayTest {
        store.put(WmFlags.reapEvidence, ReapEvidence.STOOD_UP)
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).dockId

        val retired = restartAwakener()
        assertTrue(
            retired.repairing.isCompleted,
            "retiring a manager has to stop its collector: a subscription that outlives the " +
                "manager is a second opinion on every close event for the rest of the session",
        )

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        // The live manager's own sweep, which proves the close event reached a collector rather
        // than that this test outran it — and then time enough for a leaked one to have acted.
        awaitSweep()
        delay(LEAKED_SWEEP_GRACE_MS)

        assertEquals(
            0,
            retired.repairs.value.sweeps,
            "a retired manager must sweep nothing: its table says it stood this dock up, so its " +
                "sweep would kill the dock the live manager has just refused to",
        )
        assertNotNull(
            wm.tree().find(dock.raw),
            "and the dock stands, which is the decision the manager under test made and the " +
                "only decision there is anybody left to make",
        )
    }

    /**
     * **Also a record of current behaviour rather than a fix**: what an upgrade over standing
     * docks costs. The scheme decides reading and writing together, so a dock marked by an older
     * awakener is marked under the value this build no longer reads, and a live sway session
     * outlives the process that marked into it.
     *
     * The choice made, and the reason it is this one: a stranded mark is **not** adopted. Reading
     * the old shape as well would mean recognising `<prefix><any live con_id>` on any node again,
     * which is exactly the destructive defect #15 filed and this change closes — a migration read
     * would re-open the kill path on every window in the session. So the old mark falls where any
     * unrecognised mark under the prefix falls: it is *named* in `unrecognisedDockMarks`, its dock
     * is enumerated as an ordinary bindable surface, and no sweep will touch it.
     *
     * Every direction of the strand is safe in the direction that matters — no scheme reads
     * another's mark as a dock mark of its own, which `DockTableTest.no scheme reads another
     * scheme's mark as a dock mark` pins across all three — so the cost is a leak and never a
     * kill. The recovery is `wm.dock.mark_scheme` set back to the value the marks were written
     * under, with its own price, both stated at the flag.
     */
    @Test
    fun `a dock marked under the other scheme is reported and left standing`() = swayTest {
        // The manager that stands this dock up holds a STOOD_UP entry for it and would reap from
        // that, saying nothing about what a fresh process reads from the mark. `restartAwakener`
        // retires it below, which is what leaves the assertion about the mark alone.
        store.put(WmFlags.dockMarkScheme, DockMarkScheme.DOCK_AND_SURFACE)
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockFor("aw-dock1"), AgentId("agent-1")).dockId
        val oldMark =
            dockMarkFor(dock, app, WmFlags.dockMarkPrefix.default, DockMarkScheme.DOCK_AND_SURFACE)
        assertEquals(listOf(oldMark), marksOf(dock), "the dock is marked as the older build did")

        // The upgrade this change is: same sway session, same standing dock, a build whose default
        // scheme wants a nonce in the mark that the mark standing there has not got.
        store.put(WmFlags.dockMarkScheme, DockMarkScheme.DOCK_SURFACE_AND_NONCE)
        restartAwakener()

        assertEquals(
            setOf(app.raw, dock.raw),
            wm.surfaces().map { it.id.raw }.toSet(),
            "a stranded dock is handed back as a bindable surface: that is the cost of the " +
                "upgrade, and it is the recoverable kind — a Breath and a registry row",
        )
        assertEquals(
            setOf(oldMark),
            wm.unrecognisedDockMarks.value,
            "and it is named rather than passed over, which is the whole of the diagnosis",
        )

        command("[con_id=${app.raw}] kill")
        awaitGone(app)
        wm.reapOrphans()

        assertNotNull(
            wm.tree().find(dock.raw),
            "a stranded dock is never killed on the strength of a mark this build does not " +
                "recognise — the leak is the cost, and it must not become a kill",
        )
    }

    /**
     * The reservation covers the dock from before it exists, so it has to be given back whether
     * the attach worked or not — and a leaked one is invisible in the tree while hiding every
     * window under the dock's `app_id` until its deadline (`wm.wait.reservation_grace_ms`, 5s by
     * default). Cancelled rather than left to time out, because a reservation that has merely
     * expired would prove nothing about eviction — which is also why the deadline is a backstop
     * for an attach that died rather than a reason not to evict (#108).
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

    /**
     * #4. Suppressing focus with a `no_focus` rule spends compositor state that sway has no verb
     * to take back, and under the default identity scheme every dock reports one `app_id` — so
     * one attach with `wm.dock.focus_on_map` off suppressed focus for every dock afterwards, for
     * the life of the sway session. The flag stopped meaning what it says after its first use.
     *
     * `wm.focus.restore_after_attach` is off throughout so that what is read at the end is where
     * the map left focus, rather than where the resting-focus rule put it back.
     */
    @Test
    fun `suppressing focus for one dock does not suppress it for the next`() = swayTest {
        store.put(WmFlags.restoreFocusAfterAttach, false)
        store.put(WmFlags.dockFocusOnMap, false)
        val app1 = openSurface("aw-app1")

        wm.attach(app1, dockFor("aw-dock"), AgentId("agent-1"))
        assertEquals(
            app1.raw,
            focusedId(),
            "the attach that asked for suppression still gets it — however it is achieved, the " +
                "dock must not be left holding focus",
        )

        store.put(WmFlags.dockFocusOnMap, true)
        val app2 = openSurface("aw-app2")
        val second = wm.attach(app2, dockFor("aw-dock"), AgentId("agent-2"))

        assertEquals(
            second.dockId.raw,
            focusedId(),
            "and the next attach, made while the flag says to focus on map, has to actually " +
                "get focus: a suppression the first attach cannot revoke poisons every dock " +
                "after it for the rest of the session",
        )
    }

    /**
     * The combination that would otherwise leave the suppression flag suppressing nothing.
     * `settleFocus` runs only under `wm.focus.restore_after_attach`, so with that off and the
     * correction treated as part of resting focus, the dock would keep the focus it took on map —
     * the one outcome `focus_on_map = false` was asked for. The correction belongs to the
     * suppression instead, and runs regardless.
     */
    @Test
    fun `the focus correction runs whatever restore_after_attach says`() = swayTest {
        store.put(WmFlags.restoreFocusAfterAttach, false)
        store.put(WmFlags.dockFocusOnMap, false)
        val app = openSurface("aw-app1")

        wm.attach(app, dockFor("aw-dock"), AgentId("agent-1"))

        assertEquals(
            app.raw,
            focusedId(),
            "restore_after_attach decides whether the resting-focus rule is applied at the end " +
                "of an attach, not whether a transient steal is corrected",
        )
    }

    /**
     * The previous mechanism, kept reachable for anyone who would rather have no flicker than a
     * revocable rule — with the cost that made it the wrong default kept reproducible in the same
     * test, since a rule sway cannot revoke reaches every dock the criteria match.
     */
    @Test
    fun `no_focus focus suppression is switchable on`() = swayTest {
        store.put(WmFlags.dockFocusSuppression, FocusSuppression.NO_FOCUS_RULE)
        store.put(WmFlags.restoreFocusAfterAttach, false)
        store.put(WmFlags.dockFocusOnMap, false)
        val app1 = openSurface("aw-app1")

        wm.attach(app1, dockFor("aw-dock"), AgentId("agent-1"))
        assertEquals(app1.raw, focusedId(), "the rule must actually suppress the map-time focus")

        store.put(WmFlags.dockFocusOnMap, true)
        val app2 = openSurface("aw-app2")
        val second = wm.attach(app2, dockFor("aw-dock"), AgentId("agent-2"))

        assertEquals(
            app2.raw,
            focusedId(),
            "and this is what it costs: sway has no verb that takes the rule back, so under the " +
                "shared app_id it goes on suppressing focus for a dock attached while the flag " +
                "says to focus on map — dock ${second.dockId.raw} never gets it",
        )
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
        // This drives the sweep by hand and asserts on what one pass of it did, so the collector
        // that now runs one per close event has to be out of the way — it would reap these orphans
        // before the call below and leave it nothing to fail on. What the collector does with a
        // failing sweep is SwayRepairTest's.
        store.put(WmFlags.sweepOnClose, false)
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
        // As above: one hand-driven pass is what is asserted on, down to the order the sweep walks
        // the tree in, so the event-driven collector must not have run one first.
        store.put(WmFlags.sweepOnClose, false)
        store.put(WmFlags.unmapWaitMs, WEDGE_UNMAP_WAIT_MS)
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
                "or it is not diagnosable, and the sweep's usual caller is a collector nobody " +
                "is watching; the message was: ${failure.message}",
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
        store.put(WmFlags.unmapWaitMs, WEDGE_UNMAP_WAIT_MS)
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

    /**
     * The same hazard reached through the failure path, which is #6. `attach` splits the tab
     * before it spawns anything, so a dock that never maps leaves the surface wrapped in a
     * single-child split container that sway will not collapse — and the next window opened in
     * that tab is swallowed into it.
     *
     * The failure is the dock program exiting without ever mapping a window, which is what a
     * mistyped command or a panel binary that dies on startup looks like, and it is the failure
     * mode `attach` was already reproduced on.
     *
     * It costs this test one map wait, which is why it sets a short one: nothing here races the
     * deadline — the dock program is `exit 1` and no window is ever coming — so the length of the
     * wait is pure latency and used to be five seconds of it. That the deadline is now a flag is
     * #49's, and this is the first thing it buys.
     */
    @Test
    fun `a failed attach leaves the tab as it found it`() = swayTest {
        store.put(WmFlags.mapWaitMs, NO_DOCK_MAP_WAIT_MS)
        val app1 = openSurface("aw-app1")
        openSurface("aw-app2")
        val before = assertNotNull(wm.tree().workspace("1")).children.map { it.id }

        assertFailsWith<IllegalStateException>("a dock that never maps has to fail the attach") {
            wm.attach(app1, DockSpec("aw-dock", "sh -c 'exit 1'"), AgentId("agent-1"))
        }

        assertEquals(
            before,
            assertNotNull(wm.tree().workspace("1")).children.map { it.id },
            "the tab has to be the surface again: a leftover container is a new node, so its " +
                "id shows up here in place of the one the surface had",
        )

        val later = openSurface("aw-app3")
        val workspace = assertNotNull(wm.tree().workspace("1"))
        assertTrue(
            workspace.children.any { it.id == later.raw },
            "and this is what the leftover costs — a window opened afterwards is swallowed " +
                "into the container the failed attach left behind instead of becoming its own tab",
        )
        assertEquals(3, workspace.children.size, "three surfaces means three tabs")
    }

    /**
     * The same leftover container, reached through the race rather than through a dock program
     * that never maps at all — and this is the case the unwind exists for, not a corner of it.
     *
     * `attach` learns which node its dock is only when `awaitWindow` returns, so across the whole
     * of the map deadline it holds no record of a window it may already have spawned. A dock
     * that maps as that deadline expires is therefore a window the unwind does not know exists:
     * it is not killed, and the flatten that follows is refused on a container that has acquired
     * a second child — #6 reinstated, on the failure path #6 was reproduced on.
     *
     * Deterministic rather than swept for. [SwayValve] holds the attach's first tree read after
     * the exec until the dock has mapped *and* the deadline has passed, so the attach gives up at
     * exactly the moment its dock is standing. The same state is reachable by sweeping `sleep`
     * offsets across the deadline, which is how it was found, but it costs several runs an
     * observation and the gap it aims at is under a millisecond wide.
     */
    @Test
    fun `a dock that maps as the map deadline expires is still taken back down`() = swayTest {
        // The deadline is held shut by the valve rather than waited out, so its length buys this
        // test nothing at all: what has to be true is that it expires while the read is held, and
        // a short one expires the same way a long one does.
        store.put(WmFlags.mapWaitMs, HELD_MAP_WAIT_MS)
        SwayValve.open(sway.socket).use { valve ->
            val manager = valved(valve)
            val app = openSurface("aw-app1")
            openSurface("aw-app2")
            val before = assertNotNull(wm.tree().workspace("1")).children.map { it.id }
            // The first tree read after the exec is `awaitWindow`'s first poll, so holding it
            // holds the whole map wait: nothing the attach reads afterwards can tell it the dock
            // arrived.
            var execd = false
            valve.holdNext { type, payload ->
                if (type == I3Ipc.Request.RUN_COMMAND && payload.startsWith("exec ")) execd = true
                execd && type == I3Ipc.Request.GET_TREE
            }

            coroutineScope {
                val attaching = async {
                    assertFailsWith<IllegalStateException>("the map deadline has to fail it") {
                        manager.attach(app, dockFor("aw-dock"), AgentId("agent-1"))
                    }
                }
                // Off the runBlocking thread: the attach's own continuations need it, and a
                // blocking wait taken here would stop it ever reaching the exec.
                withContext(Dispatchers.IO) { valve.awaitHeld(VALVE_WAIT_MS) }
                assertNotNull(awaitWindow("aw-dock"), "the dock never mapped")
                delay(PAST_MAP_DEADLINE_MS)
                valve.release()
                attaching.await()
            }

            assertEquals(
                before,
                assertNotNull(wm.tree().workspace("1")).children.map { it.id },
                "the tab has to be the surface again: a leftover container is a new node, so " +
                    "its id shows up here in place of the one the surface had",
            )
            assertEquals(
                emptyList(),
                wm.tree().windows.filter { it.appId == "aw-dock" }.map { it.id },
                "and the dock goes with it — the attach spawned this window and merely failed " +
                    "to identify it in time, which does not make it someone else's to leave",
            )
        }
    }

    /**
     * The second half of that race, one round trip later. The unwind reads the tree, finds
     * nothing, and the dock maps before the `split none` it sends next — so sway refuses the
     * flatten, "Can only flatten a child container with no siblings", and the container stands.
     *
     * This is what a single re-read of the tree does not reach, and why the unwind makes two
     * passes at the flatten rather than one: a refusal is itself the news that a window arrived,
     * and the next pass is what takes it down. The valve holds the `split none` itself, so the
     * dock lands between the read and the command on every run.
     */
    @Test
    fun `a dock that maps as the unwind flattens the container is taken back down`() = swayTest {
        // Short, and [slowDock]'s sleep is set against it: what this test needs is a dock that
        // maps *after* the deadline and *while* the valve holds the flatten, which is a gap the
        // valve holds open for as long as it likes. Five seconds of it was five seconds of
        // nothing happening.
        store.put(WmFlags.mapWaitMs, HELD_MAP_WAIT_MS)
        SwayValve.open(sway.socket).use { valve ->
            val manager = valved(valve)
            val app = openSurface("aw-app1")
            openSurface("aw-app2")
            val before = assertNotNull(wm.tree().workspace("1")).children.map { it.id }
            valve.holdNext { type, payload ->
                type == I3Ipc.Request.RUN_COMMAND && payload == "split none"
            }

            coroutineScope {
                val attaching = async {
                    assertFailsWith<IllegalStateException>("the map deadline has to fail it") {
                        manager.attach(app, slowDock("aw-dock"), AgentId("agent-1"))
                    }
                }
                withContext(Dispatchers.IO) { valve.awaitHeld(VALVE_WAIT_MS) }
                assertNotNull(awaitWindow("aw-dock"), "the dock never mapped")
                valve.release()
                attaching.await()
            }

            assertEquals(
                before,
                assertNotNull(wm.tree().workspace("1")).children.map { it.id },
                "the flatten the arrival made sway refuse has to be tried again once the dock " +
                    "is gone, or the leftover container stands",
            )
            assertEquals(
                emptyList(),
                wm.tree().windows.filter { it.appId == "aw-dock" }.map { it.id },
                "and a dock that arrives while the unwind is working in the container is still " +
                    "the unwind's to take down",
            )
        }
    }

    /**
     * The other half of #6: a failure *after* the dock has mapped owes the window as well as the
     * container. The binding is what fails here, and it is the realistic case rather than a
     * contrived one — it is recorded outside the tree section because the minter shells out to
     * spanreed, so it is the step of `attach` most likely to fail and the only one that can fail
     * once sway has accepted everything else.
     *
     * It is also the one compensation that cannot run inside the section that built the dock,
     * since that section ended before the bind began.
     */
    @Test
    fun `a dock whose binding cannot be recorded is taken back down`() = swayTest {
        val app = openSurface("aw-app1")
        val before = assertNotNull(wm.tree().workspace("1")).children.map { it.id }
        mintFails = true

        assertFailsWith<IllegalStateException>("a bind that fails has to fail the attach") {
            // No agent: the hotkey case, and the only path that mints.
            wm.attach(app, dockFor("aw-dock"))
        }

        assertEquals(
            emptyList(),
            wm.tree().windows.filter { it.appId == "aw-dock" }.map { it.id },
            "the dock is a window nothing holds a handle to and nothing is bound to, so an " +
                "attach that could not finish owes it",
        )
        assertEquals(
            before,
            assertNotNull(wm.tree().workspace("1")).children.map { it.id },
            "and the container it was spawned into goes with it",
        )
    }

    /**
     * The other half of the choice, for the same reason `OrphanPolicy.LEAVE` exists: when
     * diagnosing, tree damage you can see beats tree damage that was tidied away. Driven from the
     * bind failure rather than the map timeout because that is the shape where both compensations
     * have something to leave standing.
     */
    @Test
    fun `leaving the wreckage of a failed attach standing is switchable on`() = swayTest {
        store.put(WmFlags.unwindFailedAttach, false)
        val app = openSurface("aw-app1")
        mintFails = true

        assertFailsWith<IllegalStateException> { wm.attach(app, dockFor("aw-dock")) }

        assertEquals(
            1,
            wm.tree().windows.count { it.appId == "aw-dock" },
            "with the flag off the dock the attach spawned is left where it is",
        )
        assertEquals(
            "splith",
            assertNotNull(wm.tree().workspace("1")).children.single().layout,
            "and so is the container it was spawned into — the hazard, kept reproducible on " +
                "purpose",
        )
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
        restartAwakener()
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
     * #52: `resolve` answers from the durable registry, and the dock table cannot make it say
     * otherwise.
     *
     * The window here is a genuine application window that a hand marked with a mark shaped
     * exactly like that window's own dock mark — #15's residual, the case the table is documented
     * to latch and never let go of. One enumeration hides it for the life of the process, and
     * `swaymsg unmark` does not bring it back; that latch is deliberate and is asserted here as
     * unchanged. What must not follow from it is `resolve` calling a durably bound surface a
     * **Drab**, because a caller acting on that mints a second agent for a surface that already
     * has one — and until this fix that is exactly what happened, since `resolve` reached its key
     * through `surfaces()`.
     *
     * The binding is written straight into the registry rather than through `attach`, because a
     * window the table is hiding is one no `attach` will take: the point is that the binding
     * outlives everything the table knows, so the table must not be able to hide it.
     */
    @Test
    fun `a surface the table is hiding still resolves to its agent`() = swayTest {
        val app = openSurface("aw-app1")
        registry.bind(SurfaceKey.Window("aw-app1"), AgentId("agent-1").asIdentity())
        command("[con_id=${app.raw}] mark --add ${markFor(app, app)}")

        assertEquals(
            emptyList(),
            wm.surfaces().filter { it.id == app }.map { it.id },
            "the forged mark has to hide it from enumeration, or this test proves nothing",
        )
        command("[con_id=${app.raw}] unmark ${markFor(app, app)}")
        assertEquals(
            emptyList(),
            wm.surfaces().filter { it.id == app }.map { it.id },
            "and the recognition is latched: unmarking does not hand it back, which is the " +
                "documented cost of adoption recording",
        )

        assertEquals(
            AgentId("agent-1"),
            wm.resolve(app),
            "resolve answers from the registry, keyed on what outlives the window — a session's " +
                "dock table has no way to turn a bound surface into a Drab",
        )
    }

    /**
     * The other end of that switch, and the reason it is a flag rather than a rewrite: reaching
     * the key through enumeration is a real behaviour somebody may want, since it makes `resolve`
     * refuse a dock outright. It also brings the session dependence back, which is what the
     * second half asserts.
     */
    @Test
    fun `resolve reaching its key through enumeration is switchable back on`() = swayTest {
        store.put(WmFlags.resolveKeySource, ResolveKeySource.ENUMERATION)
        val app = openSurface("aw-app1")
        registry.bind(SurfaceKey.Window("aw-app1"), AgentId("agent-1").asIdentity())
        assertEquals(AgentId("agent-1"), wm.resolve(app), "an enumerable surface resolves either way")

        command("[con_id=${app.raw}] mark --add ${markFor(app, app)}")
        assertNull(
            wm.resolve(app),
            "and under ENUMERATION the table decides what resolve will answer for at all, so a " +
                "hidden surface reads as a Drab however durably it is bound",
        )
    }

    /**
     * The same surface, seen by a manager that never reads the table at all — so that the
     * previous test's null is attributable to the table and not to anything about the mark.
     */
    @Test
    fun `a dock resolves to whatever the registry holds for its app_id`() = swayTest {
        val app = openSurface("aw-app1")
        val dock = wm.attach(app, dockFor("aw-dock"), AgentId("agent-1")).dockId

        assertNull(wm.resolve(dock), "nothing has bound the dock's key, so there is nothing to say")

        registry.bind(SurfaceKey.Window("aw-dock"), AgentId("panel-agent").asIdentity())
        assertEquals(
            AgentId("panel-agent"),
            wm.resolve(dock),
            "a dock is an ordinary node to resolve, which is the disclosed cost of taking the " +
                "table out of its path — callers get surface ids from surfaces(), which still " +
                "excludes docks",
        )
        assertEquals(
            emptyList(),
            wm.surfaces().filter { it.id == dock }.map { it.id },
            "enumeration is unchanged and is what a caller actually asks",
        )
    }

    /**
     * #49: a wait that expires costs a paced poll, not a spin.
     *
     * The cost of a poll is round trips, and round trips are invisible from both ends — sway
     * reports nothing, and a manager issuing thirty thousand of them returns the same answer, at
     * the same moment, as one issuing thirty. [SwayValve] is the only place they can be seen, so
     * it counts them.
     *
     * Both halves run here rather than one, because an absolute bound would be a number this
     * machine happened to produce. The spin is the control: same wait, same failing attach, same
     * unwind, with `wm.wait.poll_spin_ms` raised past the deadline so no read is ever paced.
     * Measured on headless sway 1.12 the two differ by more than two orders of magnitude — a
     * paced wait of this length is about `wait / interval` reads plus the unwind's handful, and
     * the spin is several thousand.
     */
    @Test
    fun `a dock that never maps costs a paced poll, not a spin`() = swayTest {
        store.put(WmFlags.mapWaitMs, POLL_PROOF_WAIT_MS)
        store.put(WmFlags.pollIntervalMs, POLL_PROOF_INTERVAL_MS)
        val app = openSurface("aw-app1")

        SwayValve.open(sway.socket).use { valve ->
            val manager = valved(valve)
            store.put(WmFlags.pollSpinMs, 0)
            assertFailsWith<IllegalStateException>("a dock that never maps has to fail the attach") {
                manager.attach(app, DockSpec("aw-nodock", "sh -c 'exit 1'"), AgentId("agent-1"))
            }
            val paced = valve.requestCount(I3Ipc.Request.GET_TREE)

            valve.resetCounts()
            // Past the deadline, so every read of the wait is an unpaced one: the old behaviour,
            // reached through the flag rather than through a second build.
            store.put(WmFlags.pollSpinMs, POLL_PROOF_WAIT_MS * 2)
            assertFailsWith<IllegalStateException> {
                manager.attach(app, DockSpec("aw-nodock", "sh -c 'exit 1'"), AgentId("agent-1"))
            }
            val spun = valve.requestCount(I3Ipc.Request.GET_TREE)

            assertTrue(
                paced <= POLL_PROOF_WAIT_MS / POLL_PROOF_INTERVAL_MS + POLL_PROOF_SLACK,
                "a ${POLL_PROOF_WAIT_MS}ms wait paced at ${POLL_PROOF_INTERVAL_MS}ms cannot cost " +
                    "more than about ${POLL_PROOF_WAIT_MS / POLL_PROOF_INTERVAL_MS} tree reads " +
                    "plus the unwind's; it cost $paced",
            )
            assertTrue(
                spun > paced * POLL_PROOF_RATIO,
                "and the control has to show the reads were real: spinning the same wait cost " +
                    "$spun tree reads against the paced $paced, which is not the order of " +
                    "magnitude #49 measured — if these are close, the pacing is not what is " +
                    "being exercised",
            )
        }
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
        assertMarkedFor(app1, dock1.dockId, "one per dock")
        assertMarkedFor(app2, dock2.dockId, "one per dock")
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
        assertMarkedFor(app2, dock2.dockId, "and still bound to its own")
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
            setOf(dock1.dockId.raw, dock2.dockId.raw),
            docksOf("aw-dock").keys,
            "every window the dock program produced must be one of the two docks: an unmarked " +
                "orphan panel beside them is #2 back again",
        )
        assertMarkedFor(app1, dock1.dockId, "and each is exactly one surface's dock")
        assertMarkedFor(app2, dock2.dockId, "and each is exactly one surface's dock")
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
        assertMarkedFor(app1, dock1.dockId, "one per dock")
        assertMarkedFor(app2, dock2.dockId, "one per dock")
    }

    // -- helpers ------------------------------------------------------------------------

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
        return SurfaceId(assertNotNull(awaitWindow(appId), "window '$appId' never appeared"))
    }

    private fun dockFor(appId: String) =
        DockSpec(appId, sway.windowCommand(DockSpec.APP_ID_PLACEHOLDER))

    /**
     * A dock program whose window maps well after `attach` has given up waiting for it.
     *
     * The sleep is stated against [HELD_MAP_WAIT_MS] rather than against the shipped default:
     * what makes this a *slow* dock is that it maps on the far side of whatever deadline the test
     * set, and the only test using it sets a short one.
     */
    private fun slowDock(appId: String) =
        DockSpec(appId, "sh -c 'sleep $SLOW_DOCK_SLEEP_S; exec ${sway.windowCommand(appId)}'")

    /**
     * A manager whose commands reach sway through [valve], so that a test can hold one of them.
     * Its own manager rather than the shared one: the valve is a one-shot and the field manager
     * is what the assertions read the tree with.
     */
    private fun valved(valve: SwayValve) = SwayWindowManager(
        { SwayConnection.open(valve.socket.absolutePathString()) },
        store,
        bindingStore(),
        scope,
    )

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
        signal("STOP", pid)
    }

    private fun signal(name: String, pid: Int) {
        val exit = ProcessBuilder("kill", "-$name", pid.toString()).start().waitFor()
        assertEquals(0, exit, "kill -$name $pid failed")
    }

    /**
     * A dock mark for [dock] naming [surface], at stock defaults and with a fixed nonce.
     *
     * For **forging** one by hand, which is what a test that plays the user does: the nonce is a
     * constant so that the same string can be marked and then unmarked. Nothing that asks what a
     * real dock carries may use this — production draws its own nonce, so a real dock's mark is not
     * a string a test can predict. See [soleMarkOf] and [assertMarkedFor] for that question.
     */
    private fun markFor(dock: SurfaceId, surface: SurfaceId) = dockMarkFor(
        dock,
        surface,
        WmFlags.dockMarkPrefix.default,
        WmFlags.dockMarkScheme.default,
        FORGED_NONCE,
    )

    private suspend fun marksOf(dock: SurfaceId): List<String>? = wm.tree().find(dock.raw)?.marks

    /** The one mark sway holds on [dock] — what a hand would have to name to take it off. */
    private suspend fun soleMarkOf(dock: SurfaceId): String {
        val marks = assertNotNull(wm.tree().find(dock.raw), "no node ${dock.raw}").marks
        assertEquals(1, marks.size, "expected exactly one mark on ${dock.raw}, got $marks")
        return marks.single()
    }

    /**
     * Asserts [dock] carries exactly one mark and that it is this dock's mark for [surface].
     *
     * Read back and put through the production predicate rather than compared against a predicted
     * string: the default scheme's mark carries a nonce the test did not choose, and the question
     * worth asking was never "is the string this" but "does awakener read this node as that
     * surface's dock, and does it wear nothing else".
     */
    private suspend fun assertMarkedFor(surface: SurfaceId, dock: SurfaceId, message: String) {
        val node = assertNotNull(wm.tree().find(dock.raw), "$message: no node ${dock.raw}")
        assertEquals(1, node.marks.size, "$message: one mark per dock, got ${node.marks}")
        assertEquals(surface, dockMarkOf(node), "$message: ${node.marks}")
    }

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

    /**
     * Waits until [surface]'s dock has been marked, which is the last of `attach`'s tree work.
     *
     * Asks the production predicate rather than matching a string, because the mark names the
     * dock as well as the surface and the caller does not know which node the dock is yet.
     */
    private suspend fun awaitMarked(surface: SurfaceId) {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.tree().windows.none { dockMarkOf(it) == surface }) yield()
                true
            },
            "no dock was ever marked for ${surface.raw}",
        )
    }

    /** The surface [node]'s marks say it is the dock for, at stock defaults. */
    private fun dockMarkOf(node: Node): SurfaceId? = node.dockMark(
        WmFlags.dockMarkPrefix.default,
        WmFlags.dockMarkScheme.default,
    ).surface

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

    /** Waits for the repair collector to have completed a sweep of its own. */
    private suspend fun awaitSweep() {
        assertNotNull(
            withTimeoutOrNull(WAIT_MS) {
                while (wm.repairs.value.sweeps == 0) yield()
                true
            },
            "the collector never swept, so a test asserting what a sweep did not do proves nothing",
        )
    }

    private companion object {
        const val WAIT_MS = 5_000L

        /**
         * How long a retired manager is given to misbehave before the one test that watches for it
         * concludes it did not.
         *
         * A wait rather than a poll because what is being asserted is an *absence*, and the only
         * honest instrument for an absence is time. 1.5s is the figure #56 was diagnosed with: a
         * delay of it in that position took the flake from roughly 1 run in 20 to every run, on two
         * trees and by two people, so it is comfortably wider than the window the race lives in.
         */
        const val LEAKED_SWEEP_GRACE_MS = 1_500L

        /**
         * The nonce a test writes when it is playing the user rather than awakener.
         *
         * Nonce-shaped on purpose: the point of these tests is that a *well-formed* dock mark is
         * still one whoever wrote it, since sway sets marks through the same `RUN_COMMAND` a hand
         * sends and there is no privileged channel. Fixed rather than drawn so that the same
         * string can be marked and then unmarked.
         */
        const val FORGED_NONCE = "0f1e2d3c4b5a6978"

        /**
         * How long a valved test will wait for the request it means to hold. Longer than the map
         * deadline itself, since the request it is after is often the one the attach sends after
         * giving up.
         */
        const val VALVE_WAIT_MS = 15_000L

        /**
         * The map deadline a valved test runs against.
         *
         * Short because the valve, not the clock, is what decides when the held read is answered:
         * every one of these tests holds a request across the deadline, so the deadline's length
         * is dead time and nothing else. It was five seconds each because the deadline was a
         * `private const` (#49) — the tests were the first thing paying for that, and 46s of a
         * 62s suite went on waits like these.
         */
        const val HELD_MAP_WAIT_MS = 1_000L

        /**
         * Held past [HELD_MAP_WAIT_MS], so that the deadline has certainly expired before the read
         * it expired on is answered. The margin is slack, not a race: the answer cannot arrive
         * until the valve lets it.
         */
        const val PAST_MAP_DEADLINE_MS = 1_200L

        /**
         * How long `slowDock` sleeps before mapping: comfortably past [HELD_MAP_WAIT_MS], so the
         * dock is certainly still absent when the deadline expires.
         */
        const val SLOW_DOCK_SLEEP_S = 2

        /**
         * The map deadline for a test whose dock program never maps anything.
         *
         * There is nothing to race — the command is `exit 1` — so this is the shortest wait that
         * still exercises an expiry rather than a scheduling accident.
         */
        const val NO_DOCK_MAP_WAIT_MS = 500L

        /**
         * The unmap wait for a test whose panel is `SIGSTOP`ped.
         *
         * A stopped process cannot service a close however long it is given, so waiting the full
         * default on it is waiting for something that has already been decided. Kept at a second
         * rather than driven to nothing so that the *other* docks in the same test — real windows
         * that do exit — are not racing it.
         */
        const val WEDGE_UNMAP_WAIT_MS = 1_000L

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

        /**
         * The map deadline the round-trip measurement runs against, and the interval it is paced
         * at. A second is long enough that the paced count is dominated by the poll rather than
         * by the constant handful the unwind costs, and short enough that the *spinning* control
         * — which is a real busy-poll against a real compositor — is a second of heat and not
         * five.
         */
        const val POLL_PROOF_WAIT_MS = 1_000L
        const val POLL_PROOF_INTERVAL_MS = 25L

        /**
         * Room above `wait / interval` for the reads that are not the poll's: the `keyFor` at the
         * top of `attach`, the standing-docks snapshot, and the unwind's two passes. Seven on the
         * runs measured; twenty so that a scheduler hiccup is not a failure.
         */
        const val POLL_PROOF_SLACK = 20L

        /**
         * How much dearer the spinning control has to be before the paced count is evidence
         * rather than a coincidence. Measured against headless sway 1.12 the true ratio is two
         * orders of magnitude; twenty is the floor below which this test would be asserting
         * nothing.
         */
        const val POLL_PROOF_RATIO = 20L
    }
}
