package com.monkopedia.awakener.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [refusedResize] against trees shaped like the ones measured on headless sway 1.12 for #261.
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
        assertEquals(36, tree.refusedResize(DOCK, 30))
    }

    @Test
    fun `the same short dock where every sibling could give its share is a fault`() {
        assertNull(split(721, APP to 236, DOCK to 180, 21L to 153, 22L to 152).refusedResize(DOCK, 30))
    }

    /** The intermediate layout the readback was written for: `floating disable` without the resize. */
    @Test
    fun `a dock left at half of a split of two is a fault`() {
        assertNull(split(1280, APP to 640, DOCK to 640).refusedResize(DOCK, 30))
    }

    @Test
    fun `a dock narrowed in a split of two is a fault`() {
        assertNull(split(1280, APP to 1088, DOCK to 192).refusedResize(DOCK, 30))
    }

    /**
     * sway sizes a horizontal split's children only, so the excuse is for those alone — even
     * where a sibling elsewhere is narrow enough that the arithmetic would otherwise excuse it.
     */
    @Test
    fun `a dock outside any horizontal split is a fault`() {
        assertNull(split(1280, APP to 150, DOCK to 192, layout = "tabbed").refusedResize(DOCK, 30))
    }

    @Test
    fun `the sibling share is rounded up as sway rounds it`() {
        // 30% of 1000 is 300; 300 - 297 = 3 px over two siblings is 2 each, which takes 101 to 99.
        assertEquals(3, split(1000, APP to 602, DOCK to 297, 21L to 101).refusedResize(DOCK, 30))
        assertNull(split(1000, APP to 601, DOCK to 297, 21L to 102).refusedResize(DOCK, 30))
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
        const val APP = 10L
        const val DOCK = 11L
    }
}
