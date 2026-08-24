package com.monkopedia.awakener.connector

import kotlin.test.Test
import kotlin.test.assertEquals

/** Placeholder expansion, which is the whole of what awakener does to a connector's files. */
class RenderTest {
    private val values = mapOf(
        SurfaceFact.PID to "4242",
        SurfaceFact.RESIDUE to "/home/jmonk/.local/state/awakener/residue/editor",
        SurfaceFact.TITLE to "notes.md",
    )

    @Test
    fun `a known placeholder expands`() {
        assertEquals("pid=4242", render("pid={pid}", values).text)
    }

    @Test
    fun `an unknown brace is copied through untouched`() {
        // A connector ships JSON and shell, both of which are full of braces that mean something
        // else. Escaping them would make every definition harder to write than the thing it
        // configures.
        val json = """{"mcpServers": {"x": {"env": {"PID": "{pid}"}}}}"""
        assertEquals(
            """{"mcpServers": {"x": {"env": {"PID": "4242"}}}}""",
            render(json, values).text,
        )
        assertEquals("\${HOME}/bin", render("\${HOME}/bin", values).text)
    }

    /**
     * **The reason this is one pass and not a chain of `replace` calls.** A window title is
     * chosen by the program that owns the window, so it is attacker-controlled in the ordinary
     * sense: a page can set `document.title` to anything. A chain of replacements rescans its own
     * output, so a window titled `{residue}` would have the residue path substituted into the
     * file — a value the connector's author never wrote appearing where they wrote a title.
     */
    @Test
    fun `an expansion is never rescanned`() {
        val hostile = values + (SurfaceFact.TITLE to "{residue}")
        assertEquals("title={residue}", render("title={title}", hostile).text)
    }

    @Test
    fun `a placeholder with no value expands to nothing and is reported`() {
        val rendered = render("title=[{title}] pid={pid}", values - SurfaceFact.TITLE)
        assertEquals("title=[] pid=4242", rendered.text)
        assertEquals(listOf(SurfaceFact.TITLE), rendered.absent)
    }

    @Test
    fun `one absent fact is reported once however often it appears`() {
        val rendered = render("{title} {title} {title}", emptyMap())
        assertEquals("  ", rendered.text)
        assertEquals(listOf(SurfaceFact.TITLE), rendered.absent)
    }

    @Test
    fun `an unterminated brace at the end of the file is not lost`() {
        assertEquals("a {pid", render("a {pid", values).text)
        assertEquals("{", render("{", values).text)
    }

    @Test
    fun `every fact has a distinct placeholder`() {
        // The ids are the manifest's vocabulary and the file format's at once, so a duplicate
        // would make one of two facts unreachable with nothing to say so.
        assertEquals(
            SurfaceFact.entries.size,
            SurfaceFact.entries.map { it.placeholder }.toSet().size,
        )
    }

    @Test
    fun `a fact id round-trips through byId`() {
        SurfaceFact.entries.forEach { assertEquals(it, SurfaceFact.byId(it.id)) }
        assertEquals(null, SurfaceFact.byId("geometry"))
    }
}
