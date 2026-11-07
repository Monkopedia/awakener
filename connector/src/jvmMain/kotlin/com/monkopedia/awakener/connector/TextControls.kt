package com.monkopedia.awakener.connector

/**
 * Whether [codePoint] is one a reader may act on rather than show — so one that must never
 * reach text an agent reads unescaped, when the bytes around it are the window's.
 *
 * **By general category, not by list**, which is the lesson #181 recorded: the first escaper
 * named `\n`, `\r`, `\t` and C0, and LINE SEPARATOR went straight through it. The four
 * categories, and what each is here for:
 *
 *  - **Cc**, control — C0 and DEL as before, and now C1, which holds NEL (U+0085, a line
 *    terminator to `\R` and to XML 1.1) and CSI (U+009B, an escape-sequence introducer to a
 *    terminal in 8-bit mode).
 *  - **Zl** and **Zp** — U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR, the only members
 *    of either, and line terminators to `\R`, to JavaScript and to Unicode's own line-breaking
 *    rules even where a terminal does not break on them.
 *  - **Cf**, format — every bidi control (the embeddings and overrides U+202A–U+202E, the
 *    isolates U+2066–U+2069, and LRM/RLM/ALM), which reorder what a reader sees without adding
 *    a line; the zero-width characters and U+FEFF, which make two different strings print the
 *    same; and the tag characters (U+E0001, U+E0020–U+E007F), which are invisible on screen
 *    yet read as text by a language model. That last is why this takes a code point, not a
 *    `Char`: a supplementary character is two surrogates, and a surrogate is neither.
 *
 * The cost is that a ZERO WIDTH JOINER inside an emoji sequence prints as an escape. That is
 * the right side to err on for text people and agents match windows against: an invisible
 * difference is exactly what such a listing must not hide. Ordinary non-ASCII text — letters,
 * marks, symbols, CJK, right-to-left *letters* — is in none of these categories and passes.
 */
fun isTextControl(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.CONTROL,
    Character.FORMAT,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
    -> true

    else -> false
}
