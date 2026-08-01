package com.monkopedia.awakener.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The two pieces of dock recognition that need no compositor: what a mark means, and what the
 * table says between an attach starting and its dock being identified.
 */
class DockTableTest {
    @Test
    fun `a dock mark is the prefix, the dock's con_id and its surface's`() {
        val mark = dockMarkFor(SurfaceId(9), SurfaceId(7), PREFIX, SCHEME)
        val reading = window(9, marks = listOf(mark)).dockMark(PREFIX, SCHEME)

        assertEquals("${PREFIX}9_for_7", mark)
        assertEquals(SurfaceId(7), reading.surface)
        assertEquals(emptyList(), reading.unrecognised)
    }

    /**
     * #14 at its root. A sway mark identifier is globally unique — marking a second container
     * with one takes it off the first — so two docks of one surface can only both keep a mark if
     * the two strings differ, and only the dock's own `con_id` differs between them.
     */
    @Test
    fun `two docks of one surface are marked differently`() {
        assertNotEquals(
            dockMarkFor(SurfaceId(6), SurfaceId(5), PREFIX, SCHEME),
            dockMarkFor(SurfaceId(8), SurfaceId(5), PREFIX, SCHEME),
        )
    }

    /**
     * #15's other half, and the one the pinned predicate did not close: requiring a `con_id` in
     * the suffix stopped `awakener_dock_notes` hiding a window, but `awakener_dock_7` was still a
     * dock mark on whatever node wore it. A mark that names the node it belongs on cannot be that.
     */
    @Test
    fun `a dock mark on a node it does not name is not a dock, and is reported`() {
        val mark = dockMarkFor(SurfaceId(8), SurfaceId(7), PREFIX, SCHEME)
        val reading = window(9, marks = listOf(mark)).dockMark(PREFIX, SCHEME)

        assertNull(reading.surface, "node 9 is not the dock this mark names")
        assertEquals(listOf(mark), reading.unrecognised)
    }

    @Test
    fun `a mark under the prefix that is not a dock mark is not a dock, and is reported`() {
        val marks = listOf("${PREFIX}notes", "${PREFIX}7", "${PREFIX}9_for_soon")
        val reading = window(9, marks = marks).dockMark(PREFIX, SCHEME)

        assertNull(
            reading.surface,
            "sway's mark namespace is the user's too, so this is an ordinary window",
        )
        assertEquals(
            marks,
            reading.unrecognised,
            "and they have to be reported: hidden in one place and skipped in another is how a " +
                "window ended up reachable by no code path at all — and `${PREFIX}7` is also " +
                "what a dock marked under the other scheme wears, which is how a flip is " +
                "diagnosable rather than merely lossy",
        )
    }

    /** The previous mark, kept reachable — it is the whole of the recovery from a scheme flip. */
    @Test
    fun `the surface-only mark is switchable back on`() {
        val scheme = DockMarkScheme.SURFACE
        val mark = dockMarkFor(SurfaceId(9), SurfaceId(7), PREFIX, scheme)
        val reading = window(9, marks = listOf(mark)).dockMark(PREFIX, scheme)

        assertEquals("${PREFIX}7", mark, "it names the surface and nothing else")
        assertEquals(SurfaceId(7), reading.surface)
        assertEquals(
            SurfaceId(7),
            window(11, marks = listOf(mark)).dockMark(PREFIX, scheme).surface,
            "and it is a dock mark on any node at all, which is #15's live half kept " +
                "reproducible on purpose",
        )
        assertNull(
            window(9, marks = listOf(dockMarkFor(SurfaceId(9), SurfaceId(7), PREFIX, SCHEME)))
                .dockMark(PREFIX, scheme).surface,
            "and the other scheme's mark is not one of these — neither scheme reads the other's " +
                "mark as a dock mark, so a strand costs a leaked panel and never a kill",
        )
    }

    /**
     * The default scheme, and the property that makes it usable at all: the nonce is verified by
     * **shape**, so a reader that never saw the mark written still recognises it. A mark exists to
     * outlive the awakener process that wrote it — that is the whole of what adoption reads — so a
     * check against a remembered value would strand every standing dock on every restart.
     */
    @Test
    fun `the default mark ends in a nonce a reader verifies by shape, not by value`() {
        val mark = dockMarkFor(SurfaceId(9), SurfaceId(7), PREFIX, NONCE_SCHEME)

        assertTrue(
            Regex("${PREFIX}9_for_7_[0-9a-f]{16}").matches(mark),
            "the shape is the contract: $mark",
        )
        assertEquals(
            SurfaceId(7),
            window(9, marks = listOf(mark)).dockMark(PREFIX, NONCE_SCHEME).surface,
            "nothing here knows which nonce was drawn, and it does not need to",
        )
    }

    /** Two docks marked in one process must not collide either, so the draw has to be a draw. */
    @Test
    fun `each dock's nonce is drawn afresh`() {
        val marks = (1..32).map { dockMarkFor(SurfaceId(9), SurfaceId(7), PREFIX, NONCE_SCHEME) }

        assertEquals(marks.size, marks.toSet().size, "a constant would be no barrier at all")
    }

    /**
     * What the nonce field costs a mark that has not got one — which is every mark a hand writes
     * without meaning to write a dock mark. The shapes here are the near misses: the previous
     * scheme's mark, a trailing word, the wrong length, and hex in the wrong case.
     */
    @Test
    fun `a mark whose trailing field is not nonce-shaped is not a dock mark, and is reported`() {
        val marks = listOf(
            "${PREFIX}9_for_7",
            "${PREFIX}9_for_7_notes",
            "${PREFIX}9_for_7_0f1e2d3c4b5a697",
            "${PREFIX}9_for_7_0F1E2D3C4B5A6978",
        )
        val reading = window(9, marks = marks).dockMark(PREFIX, NONCE_SCHEME)

        assertNull(reading.surface, "each of these is an ordinary window's mark")
        assertEquals(marks, reading.unrecognised, "and each is named rather than passed over")
    }

    /**
     * #35's shape, at the level where it is decided. A mark carrying the marked window's own
     * `con_id` passes the self-check the previous scheme was the whole of, and the sweep kills on
     * a mark it accepts — so what stops that here is the nonce field being absent.
     */
    @Test
    fun `the shape that cost a window under the previous scheme is not a dock mark now`() {
        val forged = dockMarkFor(SurfaceId(9), SurfaceId(7), PREFIX, DockMarkScheme.DOCK_AND_SURFACE)

        assertEquals(
            SurfaceId(7),
            window(9, marks = listOf(forged)).dockMark(PREFIX, DockMarkScheme.DOCK_AND_SURFACE)
                .surface,
            "under the previous scheme it is a dock mark, which is #35",
        )
        assertNull(
            window(9, marks = listOf(forged)).dockMark(PREFIX, NONCE_SCHEME).surface,
            "and under the default it is not, so the sweep has nothing to act on",
        )
    }

    /**
     * The migration rule, in all three directions at once: a scheme decides reading and writing
     * together, so an upgrade or a flip strands the marks written under another value. Stranding
     * has to cost a leak and never a kill, and that holds only if no scheme ever reads another's
     * mark as one of its own.
     */
    @Test
    fun `no scheme reads another scheme's mark as a dock mark`() {
        val schemes = DockMarkScheme.entries
        for (written in schemes) {
            val mark = dockMarkFor(SurfaceId(9), SurfaceId(7), PREFIX, written)
            for (read in schemes.filter { it != written }) {
                assertNull(
                    window(9, marks = listOf(mark)).dockMark(PREFIX, read).surface,
                    "$read read $written's mark '$mark' as a dock mark: a stranded dock would " +
                        "then be reaped on a mark this build did not write",
                )
            }
        }
    }

    @Test
    fun `marks outside the prefix say nothing either way`() {
        val reading = window(9, marks = listOf("notes", "urgent")).dockMark(PREFIX, SCHEME)

        assertNull(reading.surface)
        assertEquals(emptyList(), reading.unrecognised)
    }

    @Test
    fun `a reservation covers a window that appeared under the app_id`() {
        val table = DockTable()
        table.reserve("aw-dock", standing = setOf(4L), grace = 5.seconds)

        assertTrue(table.snapshot().reserves(window(9, appId = "aw-dock")))
    }

    @Test
    fun `a reservation does not cover what was standing when it was filed`() {
        val table = DockTable()
        table.reserve("aw-dock", standing = setOf(4L), grace = 5.seconds)

        assertFalse(
            table.snapshot().reserves(window(4, appId = "aw-dock")),
            "the set attach snapshots inside the lock is part of the record precisely so that a " +
                "window it already knew keeps being enumerated",
        )
        assertFalse(table.snapshot().reserves(window(9, appId = "an-app")))
    }

    @Test
    fun `a reservation past its deadline covers nothing`() {
        val table = DockTable()
        table.reserve("aw-dock", standing = emptySet(), grace = (-1).milliseconds)

        assertFalse(table.snapshot().reserves(window(9, appId = "aw-dock")))
    }

    @Test
    fun `releasing a reservation leaves any other one standing`() {
        val table = DockTable()
        val first = table.reserve("aw-dock", standing = emptySet(), grace = 5.seconds)
        table.reserve("other-dock", standing = emptySet(), grace = 5.seconds)

        table.release(first)

        assertFalse(table.snapshot().reserves(window(9, appId = "aw-dock")))
        assertTrue(table.snapshot().reserves(window(9, appId = "other-dock")))
    }

    @Test
    fun `an entry names the surface its dock was stood up for`() {
        val table = DockTable()
        table.record(SurfaceId(9), SurfaceId(4), DockOrigin.STOOD_UP)

        assertEquals(DockEntry(SurfaceId(4), DockOrigin.STOOD_UP), table.snapshot().entries[9])

        table.forget(SurfaceId(9))

        assertNull(table.snapshot().entries[9], "a torn-down dock is not a dock")
    }

    /**
     * The one thing the origin is for: an adopted entry is a recognition latched at some past
     * read, and the orphan sweep is about to kill what it acts on. Keeping the two apart is what
     * lets `surfaces()` go on hiding a window while nothing destroys it.
     */
    @Test
    fun `an entry says whether it was stood up or adopted from a mark`() {
        val table = DockTable()
        table.record(SurfaceId(9), SurfaceId(4), DockOrigin.STOOD_UP)
        table.record(SurfaceId(11), SurfaceId(4), DockOrigin.ADOPTED)

        assertEquals(DockOrigin.STOOD_UP, table.snapshot().entries[9]?.origin)
        assertEquals(DockOrigin.ADOPTED, table.snapshot().entries[11]?.origin)
    }

    /**
     * The non-accumulating half of #4, which is the only half a test can see: two `no_focus`
     * rules for one `app_id` are indistinguishable from one through anything sway offers — it has
     * no verb that lists them and none that revokes them — so what the table remembers is what
     * decides whether the second is ever issued.
     */
    @Test
    fun `a no_focus rule is remembered once per app_id`() {
        val table = DockTable()
        table.recordFocusRule("aw-dock")
        table.recordFocusRule("aw-dock")
        table.recordFocusRule("other-dock")

        assertEquals(setOf("aw-dock", "other-dock"), table.snapshot().focusRules)
    }

    private fun window(id: Long, appId: String? = null, marks: List<String> = emptyList()) =
        Node(id = id, type = "con", appId = appId, pid = 1, marks = marks)

    private companion object {
        const val PREFIX = "awakener_dock_"

        /**
         * The scheme the tests above pin, which is deliberately **not** the default: what they are
         * about is the dock-naming self-check, and it reads more plainly without a nonce in every
         * expected string. The default's own additions have their own tests.
         */
        val SCHEME = DockMarkScheme.DOCK_AND_SURFACE

        val NONCE_SCHEME = DockMarkScheme.DOCK_SURFACE_AND_NONCE
    }
}
