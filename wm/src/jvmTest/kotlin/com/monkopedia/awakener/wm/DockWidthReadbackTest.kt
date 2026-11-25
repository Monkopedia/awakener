package com.monkopedia.awakener.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [refusedResize] against trees shaped like the ones measured on headless sway 1.12 for #261,
 * under 1.12's rule, and against the trees where 1.11's differs from it (#269).
 *
 * It is the one thing standing between the show's width readback and a dock left at the wrong
 * width, so both directions are held here: the crowded split sway refused, and the same short
 * dock in splits sway would have resized.
 */
class DockWidthReadbackTest {
    /** #261 itself: a split of four 721 wide, the oldest dock at 100 px, the restored one at 1/4. */
    @Test
    fun `a dock refused its width because a sibling would go under the floor is excused`() {
        val tree = split(721, APP to 236, DOCK to 180, 21L to 205, 22L to 100)
        // 30% of 721 is 216, so the resize asks for 36 px, 12 from each sibling: 100 - 12 < 100.
        assertEquals(36, tree.refusedResize(DOCK, 30, ALL))
    }

    @Test
    fun `the same short dock where every sibling could give its share is a fault`() {
        assertNull(split(721, APP to 236, DOCK to 180, 21L to 153, 22L to 152).refusedResize(DOCK, 30, ALL))
    }

    /** The intermediate layout the readback was written for: `floating disable` without the resize. */
    @Test
    fun `a dock left at half of a split of two is a fault`() {
        assertNull(split(1280, APP to 640, DOCK to 640).refusedResize(DOCK, 30, ALL))
    }

    @Test
    fun `a dock narrowed in a split of two is a fault`() {
        assertNull(split(1280, APP to 1088, DOCK to 192).refusedResize(DOCK, 30, ALL))
    }

    /**
     * sway sizes a horizontal split's children only, so the excuse is for those alone — even
     * where a sibling elsewhere is narrow enough that the arithmetic would otherwise excuse it.
     */
    @Test
    fun `a dock outside any horizontal split is a fault`() {
        assertNull(split(1280, APP to 150, DOCK to 192, layout = "tabbed").refusedResize(DOCK, 30, ALL))
    }

    @Test
    fun `the sibling share is rounded up as sway rounds it`() {
        // 30% of 1000 is 300; 300 - 297 = 3 px over two siblings is 2 each, which takes 101 to 99.
        assertEquals(3, split(1000, APP to 602, DOCK to 297, 21L to 101).refusedResize(DOCK, 30, ALL))
        assertNull(split(1000, APP to 601, DOCK to 297, 21L to 102).refusedResize(DOCK, 30, ALL))
    }

    /**
     * #264's map, four docks and a user's window: a split too narrow for the restored dock to be
     * *narrowed* to its width without going under the floor itself, which sway refuses as well.
     */
    @Test
    fun `a dock that would be narrowed under the floor is excused`() {
        assertEquals(-11, split(330, APP to 110, 21L to 110, DOCK to 110).refusedResize(DOCK, 30, ALL))
    }

    /**
     * #269: before 1.12 sway takes the growth from the dock's neighbours alone, half each, so
     * #261's own tree is one 1.11 would have resized — 18 px from each of 236 and 205 — and a
     * dock short of its width there is a fault.
     */
    @Test
    fun `under 1_11 a sibling beyond the dock's neighbours is not asked`() {
        assertNull(split(721, APP to 236, DOCK to 180, 21L to 205, 22L to 100).refusedResize(DOCK, 30, NEIGHBOURS))
    }

    /** And a neighbour that can spare 1.12's third of the growth but not 1.11's half of it. */
    @Test
    fun `under 1_11 a neighbour giving half the growth is refused where a third would not be`() {
        val tree = split(721, APP to 236, DOCK to 180, 21L to 115, 22L to 190)
        assertEquals(36, tree.refusedResize(DOCK, 30, NEIGHBOURS))
        assertNull(tree.refusedResize(DOCK, 30, ALL))
    }

    /** A dock at the end of the split has one neighbour, which gives all of the growth on 1.11. */
    @Test
    fun `under 1_11 a dock at the end of the split takes the whole growth from its one neighbour`() {
        val tree = split(1000, 21L to 101, APP to 602, DOCK to 297)
        assertNull(tree.refusedResize(DOCK, 30, NEIGHBOURS))
        assertEquals(3, tree.refusedResize(DOCK, 30, ALL))
        assertEquals(3, split(1000, APP to 602, 21L to 101, DOCK to 297).refusedResize(DOCK, 30, NEIGHBOURS))
    }

    @Test
    fun `the dock's own floor is the same under 1_11`() {
        assertEquals(-11, split(330, APP to 110, 21L to 110, DOCK to 110).refusedResize(DOCK, 30, NEIGHBOURS))
    }

    /** swaywm/sway@340505bb is first in 1.12, so the version sway reports picks the rule. */
    @Test
    fun `the rule is chosen by the version sway reports`() {
        assertEquals(NEIGHBOURS, ResizeShare.of(SwayVersion(1, 9)))
        assertEquals(NEIGHBOURS, ResizeShare.of(SwayVersion(1, 11)))
        assertEquals(ALL, ResizeShare.of(SwayVersion(1, 12)))
        assertEquals(ALL, ResizeShare.of(SwayVersion(2, 0)))
        assertEquals(NEIGHBOURS, ResizeShare.of("""{"human_readable":"1.11","major":1,"minor":11,"patch":0}"""))
        assertEquals(ALL, ResizeShare.of("""{"human_readable":"1.12","major":1,"minor":12,"patch":0}"""))
    }

    /**
     * A version that cannot be read is the newest rule, stated as [ResizeShare.UNKNOWN_VERSION],
     * and never an exception out of the show that asked.
     */
    @Test
    fun `a version that cannot be read is the stated default`() {
        assertEquals(ALL, ResizeShare.UNKNOWN_VERSION)
        assertEquals(ALL, ResizeShare.of(SwayVersion(0, 0)), "sway's own unparseable version string")
        assertEquals(ALL, ResizeShare.of("""{"human_readable":"1.11"}"""), "no major or minor")
        assertEquals(ALL, ResizeShare.of("""{"major":1}"""), "no minor")
        assertEquals(ALL, ResizeShare.of("""{"major":"one","minor":11}"""), "not a number")
        assertEquals(ALL, ResizeShare.of("""[]"""), "not an object")
        assertEquals(ALL, ResizeShare.of(""), "empty")
    }

    private fun split(width: Int, vararg children: Pair<Long, Int>, layout: String = "splith"): Node {
        val split = Node(
            id = 2,
            type = "con",
            layout = layout,
            rect = Rect(width = width),
            nodes = children.map { (id, w) -> Node(id = id, type = "con", rect = Rect(width = w)) },
        )
        return Node(id = 1, type = "root", nodes = listOf(split))
    }

    private companion object {
        val ALL = ResizeShare.EVERY_SIBLING
        val NEIGHBOURS = ResizeShare.NEIGHBOURS
        const val APP = 10L
        const val DOCK = 11L
    }
}
