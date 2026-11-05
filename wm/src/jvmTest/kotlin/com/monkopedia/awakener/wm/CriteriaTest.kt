package com.monkopedia.awakener.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The shape of [Criteria]'s output; what sway does with it is `SwayCriteriaTest`'s. */
class CriteriaTest {
    @Test
    fun `a plain app_id is anchored and otherwise untouched`() {
        assertEquals("""[app_id="^awakener-dock_5$"]""", Criteria.appId("awakener-dock_5"))
    }

    @Test
    fun `every ASCII character outside the plain set becomes a regex hex escape`() {
        assertEquals("""[app_id="^a\x2eb\x22\x5d\x3b\x5c$"]""", Criteria.appId("a.b\"];\\"))
        assertEquals("""[app_id="^é😀$"]""", Criteria.appId("é😀"))
    }

    @Test
    fun `nothing sway's parsers act on survives inside the value`() {
        val value = Criteria.exact((0 until 0x80).map { it.toChar() }.joinToString(""))
        val inner = value.removePrefix("\"").removeSuffix("\"")
        for (ch in "\"]';, \t\n") assertFalse(ch in inner, "'$ch' reached the value: $value")
        assertFalse("\\\"" in value, "a backslash sits before a quote: $value")
    }
}
