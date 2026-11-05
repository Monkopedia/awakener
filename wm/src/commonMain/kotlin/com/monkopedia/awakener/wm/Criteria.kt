package com.monkopedia.awakener.wm

/**
 * sway criteria, and the quoting that makes a value inside one mean only itself (#184).
 *
 * A criteria value is **not** an argument, so [TabTitle.quote] is the wrong tool for it, and both
 * halves of why were measured on headless sway 1.12:
 *
 * - **The parser's escape is not a real one.** `criteria_parse` ends a quoted value at the first
 *   `"` whose preceding byte is not `\`, then turns `\"` into `"` and leaves every other backslash
 *   alone. `\\` is not an escaped backslash: `[app_id="a\\"; …]` is read as an unterminated value
 *   (`Quote mismatch in criteria`), and so is any value whose last character is `\`. Doubling the
 *   backslash, as an argument wants, is what breaks it.
 * - **The value is a PCRE2 regex, unanchored.** `no_focus [app_id="dk-5"]` suppressed focus for a
 *   window whose `app_id` was `dk-55`; `[app_id="^q-7$"]` let `q-77` through and caught `q-7`. So
 *   the old `[app_id="$appId"]` named every window whose `app_id` *contained* the dock's, and a
 *   `.` in a name matched any character.
 *
 * And the break-out is real. `no_focus [app_id="a"]; exec touch <f>; [app_id="b"]` — an `app_id`
 * of `a"]; exec touch <f>; [app_id="b` — came back as two successful results, and the file
 * existed.
 *
 * **The encoding.** Anchored with `^…$`, ASCII letters, digits, `_` and `-` as themselves, every
 * other ASCII character as the regex escape `\xHH`, and anything outside ASCII as itself. The
 * result contains no `"`, no `]`, no `;`, no `,`, no whitespace and no `\` before a quote, so no
 * layer of sway's parsing — the command splitter, the argument splitter, or the criteria scanner
 * — finds anything in it to act on, and PCRE2 gets a literal. Measured: the rule built from
 * `r.1"];\` caught a window with exactly that `app_id`, let `rx1"];\` take focus, and executed
 * nothing; `é😀` passed through as itself and matched. `SwayCriteriaTest` holds the battery
 * against a live compositor.
 */
internal object Criteria {
    /** The criteria matching windows whose `app_id` is exactly [appId], and nothing else. */
    fun appId(appId: String): String = "[app_id=${exact(appId)}]"

    /** [value] as a quoted criteria value matching exactly [value]. Includes its quotes. */
    fun exact(value: String): String {
        val out = StringBuilder(value.length + 4).append("\"^")
        for (ch in value) {
            when {
                ch.code >= 0x80 || ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                    ch == '_' || ch == '-' -> out.append(ch)
                else -> out.append("\\x").append(ch.code.toString(16).padStart(2, '0'))
            }
        }
        return out.append("$\"").toString()
    }
}
