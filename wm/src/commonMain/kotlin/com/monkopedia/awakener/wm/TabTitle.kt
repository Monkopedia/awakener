package com.monkopedia.awakener.wm

/**
 * The tab title awakener puts on the container it wrapped a surface in, and the escaping that
 * makes putting one there safe.
 *
 * ### Why a woken tab needs one at all
 *
 * Attaching a dock replaces the surface leaf in its tab with a **split container** holding the
 * surface and the dock. sway names a container it did not get a name from *synthetically*, out of
 * its own layout description — measured on headless sway 1.12, a tab that read `Warbreaker -
 * Wikipedia` a moment earlier reads `H[chromium awakener-dock]` the moment an agent is woken. So
 * the price of the dock, today, is the tab's title. `title_format` on the container replaces that
 * synthetic name, and after it the tab is indistinguishable from a plain one (#177).
 *
 * **`%title` cannot do this, which is why there is interpolation here at all.** On a container
 * that token expands to the synthetic name — measured: `title_format "pre %title"` on the split
 * rendered `pre H[probeA awakener-dock]`. It is the *surface's* title that has to be substituted,
 * by awakener, into the format string. Which is exactly the problem below.
 *
 * ### Why the escaping is the load-bearing part
 *
 * **A window title is chosen by whatever is running in the window.** Any web page sets
 * `document.title`. `:connector` classifies that fact as `Trust.WINDOW` and refuses by
 * default to render it into files an agent reads as instructions (#179); this is the same class of byte arriving in a fourth
 * context, and the context is a **compositor command**. That context is worse than the ones #179
 * closed, because sway's command language contains `exec`.
 *
 * Measured on headless sway 1.12, against the naive `title_format "<title>"`, with the title
 * `A"; exec touch /tmp/pwned; "B`:
 *
 * ```
 * [con_id=8] title_format "A"; exec touch /tmp/pwned; "B"
 *   → three results, two successes and a parse error — and /tmp/pwned existed.
 * ```
 *
 * That is arbitrary command execution as the user, reachable from a page title. `SwayTabTitleTest`
 * attempts that break-out for real, against a live compositor, and asserts the file is not there.
 *
 * ### The rules, each one measured rather than assumed
 *
 * sway splits a command list on `;` and `,` **outside quotes**, tracking `\` as an escape
 * (`argsep`), then splits arguments on whitespace with the same quoting (`split_args`). So one
 * pair of double quotes with `\` and `"` escaped inside it is sufficient, and it was tested
 * against `;`, `,`, `]`, `'`, `` ` ``, `$(…)`, newline, CR, tab, C0 controls, an unpaired quote,
 * a lone backslash, the empty string and non-BMP text: every case produced exactly **one** result
 * from `RUN_COMMAND` and executed nothing. The count of results is the oracle, because an
 * injection *is* the command list gaining an element.
 *
 * - **`\` → `\\` and `"` → `\"`.** This is the whole of the security argument. sway's
 *   `strip_quotes` does not consume the backslash it honoured, so a title containing a double
 *   quote renders with a visible `\` before it — measured, and asserted by
 *   `TabTitleTest`. Cosmetic, and the honest
 *   trade: the alternative is deleting a character the page really put in its title.
 * - **`<` and `&` → the character + [ZERO_WIDTH_SPACE].** Pango markup, which a page would
 *   otherwise get to write into the tab strip. See below — this rule and its shape are the part of
 *   this file a reader is most likely to think is over-clever, and the part with the most
 *   measurement behind it.
 * - **`%` → `%` + [ZERO_WIDTH_SPACE].** Not a security rule, a fidelity one, and it has no
 *   escape to use instead: `%%` renders as `%%` — measured — so sway offers no literal. What it
 *   does is a plain substring scan, so a page titling itself `%title` would otherwise put the
 *   synthetic layout name back in the tab, which is the exact defect this file exists to remove.
 *   The zero-width space breaks the scan and renders as nothing: `50%<ZWSP> off` and
 *   `%<ZWSP>title` were captured with `grim` and read `50% off` and `%title`. Applied to **every**
 *   `%` rather than to the token names sway happens to expand today — `%title` is the only one
 *   that expands on a container in 1.12, and a denylist over a set that grows is the defect
 *   `:connector`'s `Trust` was written to stop repeating.
 * - **C0 controls and DEL → a space.** Also fidelity: they parse safely inside the quotes, but a
 *   newline came out of the renderer as a stray glyph in the middle of the strip (captured).
 *
 * ### Not every sway will accept one
 *
 * `title_format` on a **container** is refused by sway 1.9 — *"Only views can have a
 * title_format"* — and accepted by 1.12. Both are current: 1.9 is Ubuntu noble's and this
 * repository's CI, 1.12 is on kaladin and adolin. Nothing here can ask in advance; there is no IPC
 * request for it, so the only way to find out is to issue the command and read the answer. That is
 * `SwayWindowManager.tabTitleUnsupported`, and the rule it exists to hold is that a **cosmetic tab
 * name must never cost an attach** — the first version of this raised on the refusal and took every
 * dock attach on sway 1.9 down with it.
 *
 * ### Pango markup, and a measurement that lied
 *
 * **A title reaching a title bar is markup, not text, on any config whose `font` line starts
 * `pango:`.** sway markup-escapes the value it substitutes for `%title`; it does **not** escape the
 * format string's own literal text. Interpolating a page's title into that format string is
 * precisely what moves those bytes across that boundary, so this is a hazard this file created
 * rather than one it inherited.
 *
 * Not `exec` — markup cannot run anything — but the tab strip becomes chrome the page controls,
 * and the first casualty is [WmFlags.dockTabTitleFormat]'s own marker: a user who sets
 * `* {title}` so they can spot a woken tab has that asterisk restyled or hidden by any page that
 * wants to.
 *
 * **The first version of this file said markup rendered literally here, and cited a measurement
 * for it. The measurement was real and the conclusion was wrong**, in a way worth keeping on the
 * page: the probe used a *double*-quoted attribute, `<span foreground="#ff0000">`, and the `"` in
 * it is escaped to `\"` by the rule above — which breaks pango's parse, so the whole string fell
 * back to literal. The one syntax that self-defeats was the one the probe used. Re-measured on
 * sway 1.12 with `font pango:monospace 10` **in the config file** (a runtime `font` command does
 * not turn markup on — also measured), against one node with only `title_format` differing:
 *
 * | title | rendered |
 * | --- | --- |
 * | `<span foreground='#ff0000'>RED</span>` — single-quoted | **red**, markup honoured |
 * | `<span foreground="#ff0000">RED</span>` — double-quoted | literal, self-defeated by `\"` |
 * | `<b>BOLD</b>` — no attribute at all | **bold**, markup honoured |
 * | `Tom &amp; Jerry` | `Tom & Jerry`, entity resolved |
 *
 * ### Why the rule is a zero-width space rather than `&amp;`-escaping
 *
 * Entity-escaping is what markup wants and it is **wrong half the time here**, because whether
 * markup is on is the *user's* choice and awakener cannot see it: markup is enabled only by a
 * `font pango:…` line, and under sway's default font the same `&amp;` renders as the literal
 * five characters `&amp;` (measured). An escaping that depends on a setting this code cannot read
 * is a coin flip.
 *
 * So `<` and `&` are neutralised the same way `%` is, and for a reason that holds in **both**
 * modes: pango falls back to rendering the raw string when its parse fails, so a tag whose name
 * cannot start (`<` + [ZERO_WIDTH_SPACE]) and an entity that cannot resolve (`&` +
 * [ZERO_WIDTH_SPACE]) both come out as the text the page actually typed. Measured on 1.12 across
 * `<span …>`, `<b>`, `&amp;` and a bare `&`: the neutralised title renders **pixel-identical**
 * with markup on and markup off — 0 differing pixels in all four cases. `>` needs nothing, since
 * no tag can begin without a `<`.
 *
 * The cost is that awakener's tab titles are text in both directions: an operator cannot put
 * markup in [WmFlags.dockTabTitleFormat] either. That is the same call the `%` rule already
 * makes, and it is the one that does not depend on a config file this code cannot see.
 */
internal object TabTitle {

    /** Where [render] substitutes the surface's own title into the operator's template. */
    const val TITLE_PLACEHOLDER = "{title}"

    /**
     * What a reset restores, which is sway's own default and so the pre-#177 behaviour exactly.
     *
     * A container's `title_format` outlives the dock that caused it to be set: measured, hiding a
     * dock leaves the split container standing with one child, and so does a detach under
     * `wm.dock.normalize_container_on_detach=false`. A container left holding a frozen title from
     * whenever the dock came down would be a worse bug than the one being fixed, so detach issues
     * this.
     */
    const val COMPOSITOR_DEFAULT = "%title"

    /**
     * U+200B: breaks a scan, occupies no width when drawn.
     *
     * Three rules lean on it and each wants a different scan broken — sway's substring search for
     * `%title`, pango's tag-name start after `<`, and pango's entity name after `&`. Both halves
     * are measurements and both are necessary: U+200C behaves identically and either would do,
     * while any character with a glyph would not, since a title bar that grew a dot every time a
     * page mentioned a percentage would be a fidelity bug of its own.
     */
    private const val ZERO_WIDTH_SPACE = '\u200b'

    /**
     * [template] with every [TITLE_PLACEHOLDER] replaced by [title].
     *
     * One pass, and no rescan of what was substituted: a page titling itself `{title}` gets that
     * text in its tab and not a second expansion. The template is the operator's, out of the
     * config file; [title] is the window's. Only the result of putting them together is quoted,
     * so the operator does not get `%`-expansion in their template either — `{title}` is the
     * substitution awakener offers and sway's tokens expand to the synthetic name that this whole
     * mechanism exists to hide.
     */
    fun render(template: String, title: String): String =
        template.replace(TITLE_PLACEHOLDER, title)

    /**
     * [text] as one sway command argument that cannot be anything else.
     *
     * See the class docs for why each rule is here and what was measured for it. The return value
     * **includes** its quotes: a caller that wanted to add its own would be re-opening the hole.
     */
    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (ch in text) {
            when {
                ch == '\\' || ch == '"' -> out.append('\\').append(ch)
                ch == '%' || ch == '<' || ch == '&' ->
                    out.append(ch).append(ZERO_WIDTH_SPACE)
                ch < ' ' || ch == '\u007f' -> out.append(' ')
                else -> out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    /** The command that puts [title] — rendered through [template] — on [container]'s tab. */
    fun command(container: SurfaceId, template: String, title: String): String =
        "[con_id=${container.raw}] title_format ${quote(render(template, title))}"

    /** The command that hands [container]'s tab back to the compositor's own naming. */
    fun resetCommand(container: SurfaceId): String =
        "[con_id=${container.raw}] title_format $COMPOSITOR_DEFAULT"
}
