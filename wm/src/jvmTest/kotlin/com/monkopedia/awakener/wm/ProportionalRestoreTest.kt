package com.monkopedia.awakener.wm

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [proportionalRestore]'s plan, applied by a model of the two things sway 1.12 does with it:
 * `floating disable` tiles the dock in at the mean of the existing fractions, and `resize
 * grow|shrink right` moves one window's right edge against its neighbour alone, refusing to
 * take either under [MIN_SANE_WIDTH]. The model is what makes this a test of the walk rather
 * than of the arithmetic restated; whether sway really behaves so is `SwayCrowdedDockTest`'s.
 */
class ProportionalRestoreTest {
    /** #264's shape: the surface beside three docks, the fourth coming back at 21%. */
    @Test
    fun `every window gives the dock back its width in proportion to its own`() {
        val split = split(721, APP to 200, 21L to 149, 22L to 147, 23L to 225)
        val after = apply(split, assertNotNull(split.proportionalRestore(APP, DOCK, 721 * 21 / 100, dockFirst = false)), at = 1)
        val dock = 721 * 21 / 100
        assertEquals(dock, after.getValue(DOCK), "$after")
        listOf(APP to 200, 21L to 149, 22L to 147, 23L to 225).forEach { (id, width) ->
            val expected = width * (721.0 - dock) / 721
            assertTrue(kotlin.math.abs(after.getValue(id) - expected) <= 1, "$id: $after, expected $expected")
        }
    }

    @Test
    fun `a dock that comes back before the surface is walked from there`() {
        val split = split(1280, 21L to 300, APP to 600, 22L to 380)
        val after = apply(split, assertNotNull(split.proportionalRestore(APP, DOCK, 320, dockFirst = true)), at = 1)
        assertEquals(320, after.getValue(DOCK), "$after")
        assertTrue(kotlin.math.abs(after.getValue(APP) - 450) <= 1, "$after")
    }

    /**
     * The surface would come back at 72 px, which sway refuses, so it comes back at the floor and
     * the docks beside it give up the difference (#267).
     */
    @Test
    fun `a window that would come back under the floor comes back at it`() {
        val split = split(721, APP to 120, 21L to 300, 22L to 301)
        val after = apply(split, assertNotNull(split.proportionalRestore(APP, DOCK, 288, dockFirst = false)), at = 1)
        assertEquals(288, after.getValue(DOCK), "$after")
        assertEquals(MIN_SANE_WIDTH + 2, after.getValue(APP), "$after")
        assertEquals(721, after.values.sum(), "$after")
    }

    /** sway would refuse the boundary, and a refused command fails the whole restore batch. */
    @Test
    fun `a plan the other windows cannot spare the floor for is not made`() {
        assertNull(split(400, APP to 130, 21L to 135, 22L to 135).proportionalRestore(APP, DOCK, 150, dockFirst = false))
    }

    @Test
    fun `a split that is not horizontal gets no plan`() {
        assertNull(split(721, APP to 360, 21L to 361, layout = "splitv").proportionalRestore(APP, DOCK, 216, dockFirst = false))
    }

    /**
     * #267's shape: the surface's side of the user's split, beside the user's window and a
     * second dock, widened as every other window walks back to its target.
     */
    @Test
    fun `every other window is walked to its target and the side takes the rest`() {
        val split = split(1235, 21L to 300, APP to 535, 22L to 400)
        val growth = assertNotNull(split.walkedTo(APP, mapOf(21L to 240, 22L to 320)))
        val after = walked(split, growth.commands)
        assertEquals(mapOf(21L to 240, APP to 675, 22L to 320), after)
        assertEquals(675, growth.width)
    }

    @Test
    fun `a walk that would take a window under the floor is not made`() {
        assertNull(split(721, APP to 400, 21L to 150, 22L to 171).walkedTo(APP, mapOf(21L to 88, 22L to 100)))
    }

    @Test
    fun `a walk missing a target is not made`() {
        assertNull(split(721, APP to 400, 21L to 150, 22L to 171).walkedTo(APP, mapOf(21L to 140)))
    }

    /** The widths sway arrives at from [split] once [plan] runs, nothing tiled in. */
    private fun walked(split: Node, plan: List<String>): Map<Long, Int> {
        val ids = split.nodes.map { it.id }
        val widths = split.nodes.map { it.rect!!.width }.toMutableList()
        val step = Regex("""\[con_id=(\d+)] resize (grow|shrink) right (\d+) px""")
        plan.forEach { command ->
            val (id, verb, px) = assertNotNull(step.matchEntire(command), command).destructured
            val i = ids.indexOf(id.toLong())
            val amount = if (verb == "grow") px.toInt() else -px.toInt()
            widths[i] += amount
            widths[i + 1] -= amount
            assertTrue(widths[i] >= MIN_SANE_WIDTH && widths[i + 1] >= MIN_SANE_WIDTH, "sway refuses '$command': $widths")
        }
        return ids.zip(widths).toMap()
    }

    /** The widths sway arrives at from [split] once the dock is tiled in at [at] and [plan] runs. */
    private fun apply(split: Node, plan: List<String>, at: Int): Map<Long, Int> {
        val before = split.nodes.map { it.id to it.rect!!.width }
        val total = before.sumOf { it.second }
        val ids = before.map { it.first }.toMutableList().apply { add(at, DOCK) }
        val widths = ids.map { id ->
            if (id == DOCK) total.toDouble() / ids.size else before.toMap().getValue(id).toDouble() * before.size / ids.size
        }.map { it.roundToInt() }.toMutableList()
        widths[widths.lastIndex] = total - widths.dropLast(1).sum()
        val step = Regex("""\[con_id=(\d+)] resize (grow|shrink) right (\d+) px""")
        plan.forEach { command ->
            val (id, verb, px) = assertNotNull(step.matchEntire(command), command).destructured
            val i = ids.indexOf(id.toLong())
            val amount = if (verb == "grow") px.toInt() else -px.toInt()
            widths[i] += amount
            widths[i + 1] -= amount
            assertTrue(widths[i] >= MIN_SANE_WIDTH && widths[i + 1] >= MIN_SANE_WIDTH, "sway refuses '$command': $widths")
        }
        return ids.zip(widths).toMap()
    }

    private fun split(width: Int, vararg children: Pair<Long, Int>, layout: String = "splith") = Node(
        id = 2,
        type = "con",
        layout = layout,
        rect = Rect(width = width),
        nodes = children.map { (id, w) -> Node(id = id, type = "con", rect = Rect(width = w)) },
    )

    private companion object {
        const val APP = 10L
        const val DOCK = 11L
    }
}
