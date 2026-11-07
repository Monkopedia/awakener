package com.monkopedia.awakener.config

import kotlin.test.Test
import kotlin.test.assertEquals

class TextControlsTest {
    @Test
    fun `oneLine escapes control characters reversibly and leaves ordinary text alone`() {
        assertEquals("plain app_id", oneLine("plain app_id"))
        assertEquals("a\\nb", oneLine("a\nb"))
        assertEquals("a\\rb", oneLine("a\rb"))
        assertEquals("a\\tb", oneLine("a\tb"))
        assertEquals("a\\x1B[31m", oneLine("a\u001B[31m"))
        assertEquals("a\\x7F", oneLine("a\u007F"))
        // The backslash itself, or the escaping would not be reversible and two different
        // app_ids could print the same line — this is a listing people match windows against.
        assertEquals("a\\\\nb", oneLine("a\\nb"))
        // Everything else that is invisible or acts on layout, in the escape that fits its width.
        assertEquals("a\\x85b", oneLine("a\u0085b"))
        assertEquals("a\\x9B31m", oneLine("a\u009B31m"))
        assertEquals("a\\u2028b\\u2029c", oneLine("a\u2028b\u2029c"))
        assertEquals("\\u202Eevil\\u2069", oneLine("\u202Eevil\u2069"))
        assertEquals("a\\u200Bb", oneLine("a\u200Bb"))
        assertEquals("a\\U000E0041b", oneLine("a\uDB40\uDC41b"))
        // And text that merely is not ASCII is left alone: this is not an ASCII filter.
        assertEquals("café — 日本語 עברית", oneLine("café — 日本語 עברית"))
    }
}
