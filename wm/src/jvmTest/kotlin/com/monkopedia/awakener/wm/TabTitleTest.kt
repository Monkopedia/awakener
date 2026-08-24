package com.monkopedia.awakener.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The escaping, asserted as exact bytes.
 *
 * **This suite is the specification and `SwayTabTitleTest` is the confrontation.** Every expected
 * string below was measured against a live sway before it was written down — the rules are in
 * [TabTitle]'s docs with what each measurement was — but an expectation and the compositor
 * agreeing is a claim this file cannot make, because nothing here has a compositor in it. What it
 * can do, and what a live test cannot do cheaply, is cover the whole character table and pin the
 * output byte for byte, so a change to the routine is a diff here rather than a surprise on
 * somebody's desktop.
 *
 * The one thing it deliberately does **not** do is re-implement sway's parser as an oracle. A
 * model that agreed with a bug in the escaping would pass, and the failure mode of this whole
 * area is a guard nobody watched fail — so the break-out lives next door, against the real thing,
 * with the injected command's effect as the observable.
 */
class TabTitleTest {

    @Test
    fun `an ordinary title is quoted and otherwise untouched`() {
        assertEquals("\"Warbreaker - Wikipedia\"", TabTitle.quote("Warbreaker - Wikipedia"))
    }

    /**
     * The rule the security of this whole feature rests on.
     *
     * sway's `argsep` splits a command list on `;` and `,` only *outside* quotes and honours `\`
     * as an escape, so a title's own quote has to be neutered or it closes ours and everything
     * after it is command language.
     */
    @Test
    fun `a quote and a backslash are escaped rather than dropped`() {
        assertEquals("\"a\\\"b\"", TabTitle.quote("a\"b"))
        assertEquals("\"a\\\\b\"", TabTitle.quote("a\\b"))
        assertEquals("\"\\\\\\\"\"", TabTitle.quote("\\\""))
    }

    /**
     * The measured cosmetic cost of the rule above, asserted so that it is a decision rather than
     * a surprise.
     *
     * sway's `strip_quotes` removes the quote it was told to honour and **leaves the backslash**,
     * so a page whose title contains `"` gets a visible `\` in its tab. Captured with `grim` on
     * 1.12: `A\"; exec touch …`. The alternative is deleting a character the page really typed,
     * which is a worse lie than an extra glyph.
     */
    @Test
    fun `the escape sway leaves behind is a known artefact, not an accident`() {
        assertTrue(
            TabTitle.quote("say \"hi\"").contains("\\\""),
            "the backslash is what sway keeps; a test that expected it gone would be asserting " +
                "a renderer behaviour that was measured not to happen",
        )
    }

    /**
     * Not a security rule — a fidelity one, and the reason it is a substitution rather than an
     * escape is that sway offers no escape: `%%` renders as `%%`, measured.
     *
     * Left alone, a page titling itself `%title` would put sway's synthetic layout description
     * back into the tab, which is the precise defect #177 is about. The zero-width space breaks
     * sway's substring scan and draws nothing.
     */
    @Test
    fun `a percent cannot start one of sway's own expansions`() {
        assertEquals("\"%\u200btitle\"", TabTitle.quote("%title"))
        assertEquals("\"50%\u200b off\"", TabTitle.quote("50% off"))
        assertFalse(
            TabTitle.quote("%title").contains("%title"),
            "the literal token must not survive, or sway expands it",
        )
    }

    /**
     * Applied to every `%`, not to the tokens sway happens to expand today.
     *
     * `%title` is the only one that expands on a container in 1.12 — `%app_id`, `%class`,
     * `%instance` and `%shell` all rendered literally, measured — and writing that list down is
     * how a denylist starts. `:connector`'s `Trust` exists because the same shortcut was taken
     * there and grew a second and third member on the first review.
     */
    @Test
    fun `the percent rule is about the character, not a list of token names`() {
        assertEquals(
            "\"%\u200bapp_id %\u200bshell %\u200bnot_a_token\"",
            TabTitle.quote("%app_id %shell %not_a_token"),
        )
    }

    /**
     * Control characters parse safely inside the quotes; they do not *render* safely.
     *
     * A newline came out of the renderer as a stray glyph in the middle of the strip, captured on
     * 1.12. A space is what a one-line title bar can honestly do with a line break.
     */
    @Test
    fun `control characters are folded to a space`() {
        assertEquals("\"a b c d\"", TabTitle.quote("a\nb\rc\td"))
        assertEquals("\" \"", TabTitle.quote("\u0000"), "NUL is a control character too")
        assertEquals("\" \"", TabTitle.quote("\u007f"), "and DEL sits above the range")
    }

    @Test
    fun `an empty title still produces a well-formed argument`() {
        assertEquals("\"\"", TabTitle.quote(""))
    }

    /** Text outside the BMP goes through as it arrived: nothing here is byte-oriented. */
    @Test
    fun `non-ascii and astral text is passed through`() {
        assertEquals("\"ü — 中文 🙂\"", TabTitle.quote("ü — 中文 🙂"))
    }

    @Test
    fun `the format substitutes the title where the placeholder is`() {
        assertEquals("* Warbreaker", TabTitle.render("* {title}", "Warbreaker"))
        assertEquals("Warbreaker [agent]", TabTitle.render("{title} [agent]", "Warbreaker"))
    }

    /**
     * One pass, so a substituted value is never rescanned.
     *
     * A page can title itself `{title}`, and the difference between one pass and a loop is
     * whether that expands forever or reads back as what the page typed. `:connector`'s `render`
     * carries the same rule for the same reason.
     */
    @Test
    fun `a title that is itself the placeholder is not expanded twice`() {
        assertEquals("{title}", TabTitle.render("{title}", "{title}"))
    }

    /**
     * The whole rendered string is one argument, operator's half included.
     *
     * The template comes out of the config file, so it is not hostile — but it is still text
     * somebody typed, and a template with a stray quote in it would break the command exactly the
     * way a hostile title would. Quoting the result rather than the substituted half is what makes
     * that impossible instead of unlikely.
     */
    @Test
    fun `the operator's template is escaped along with the title`() {
        val command = TabTitle.command(SurfaceId(7), "[\"{title}\"]", "x\"y")
        assertEquals("[con_id=7] title_format \"[\\\"x\\\"y\\\"]\"", command)
    }

    @Test
    fun `a reset asks for sway's own default`() {
        assertEquals("[con_id=7] title_format %title", TabTitle.resetCommand(SurfaceId(7)))
    }

    /**
     * The shape of the injection, in the one place it can be stated cheaply.
     *
     * The observable that matters is next door — a file that must not appear and a window that
     * must not map — but the *reason* it does not appear is this: after escaping, the command
     * string contains no quote that sway will read as closing the argument, so `;` never gets to
     * be a separator.
     */
    @Test
    fun `an injection payload stays inside the argument`() {
        val hostile = "A\"; exec touch /tmp/pwned; \"B"
        val command = TabTitle.command(SurfaceId(3), TabTitle.TITLE_PLACEHOLDER, hostile)
        assertEquals(
            "[con_id=3] title_format \"A\\\"; exec touch /tmp/pwned; \\\"B\"",
            command,
        )
    }
}
