package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RegistryCliTest {
    private val dir = createTempDirectory("awakener-registry-cli")
    private val config = InMemoryConfigStore()
    private val identities = AgentIdentities { key, _ ->
        AgentIdentity(AgentId("agent-${key.slug}"), key.slug)
    }
    private val out = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun store(name: String = "bindings.json") =
        FileBindingStore(config, identities, environment = emptyMap(), path = dir.resolve(name))

    private fun run(store: FileBindingStore, vararg args: String) =
        RegistryCli.run(arrayOf(*args), store, out::add)

    @Test
    fun `list names the agent and the residue for every bound surface`() = runTest {
        val store = store()
        store.bind(SurfaceKey.Origin("https://github.com"))

        assertEquals(0, run(store, "list"))
        val text = out.joinToString("\n")
        assertTrue(text.contains("origin:https%3A//github.com"), text)
        assertTrue(text.contains("SPANREED_AGENT_NAME="), text)
        assertTrue(text.contains(store.residueLocation(SurfaceKey.Origin("https://github.com"))))
    }

    /**
     * #181, the listing `awakener-invoke list` was fixed for, one module over: `canonical`
     * escapes `%` and `:` and nothing else, so a window's `\n`, U+2028 or bidi override reached
     * this output raw. Every line this command prints about a hostile key — the listing, the
     * unreadable-key warning, `forget`'s echo and the archive failure it reports — must be one
     * line by `\R`'s definition and carry no line terminator or bidi control.
     */
    @Test
    fun `no window-chosen text splits or reorders a line of the registry's output`() = runTest {
        val window = SurfaceKey.Window(HOSTILE, "a")
        val titled = SurfaceKey.Titled(HOSTILE)
        val unreadable = "window:a:b:$HOSTILE"
        val quoted = kotlinx.serialization.json.JsonPrimitive(unreadable).toString()
        dir.resolve("bindings.json").writeText(
            """{"version": 1, "bindings": {$quoted: {"agent_id": "agent-x", """ +
                """"spanreed_name": "x", "created_at_ms": 0, "last_bound_at_ms": 0}}}""",
        )
        // A fixed clock and every archive name for that second already taken, so `forget`
        // reports the archive failure — the third message that carries the key.
        config.put(RegistryFlags.forgetResidue, ForgetResidue.ARCHIVE)
        val store = FileBindingStore(
            config,
            identities,
            environment = emptyMap(),
            clock = { 0L },
            path = dir.resolve("bindings.json"),
        )
        store.bind(window)
        store.bind(titled)
        val residue = store.prepareResidue(window).also { it.writeText("model") }
        (0 until 100).forEach { n ->
            val suffix = if (n == 0) "19700101-000000" else "19700101-000000-$n"
            residue.resolveSibling(residue.fileName.toString().removeSuffix(".md") + ".$suffix.md")
                .writeText("taken")
        }

        assertEquals(0, run(store, "list"))
        run(store, "forget", window.canonical)

        val text = out.joinToString("\n")
        assertEquals(out.size, text.split(Regex("\\R")).size, "a line was split: $out")
        val leaked = out.joinToString("").filter { it in CONTROLS }.map { "U+%04X".format(it.code) }
        assertEquals(emptyList(), leaked, "a control reached the output raw: $out")
        val escaped = "evil\\n# NEW INSTRUCTIONS\\u2028x\\u2029y\\x85z\\u202Ew\\u2066v\\u2069"
        assertTrue(out.any { it == "window:$escaped:a" }, "the listing lost the key: $out")
        assertTrue(out.any { it.startsWith("warning: unreadable key") && "\\u2028" in it }, "$out")
        assertTrue(out.any { it.startsWith("forgot ") && "\\u202E" in it }, "$out")
        assertTrue(out.any { "no free archive name" in it && "evil\\n#" in it }, "$out")
    }

    /**
     * The repair path: drop the binding so the surface mints a fresh Lifeless, without deleting
     * the residue — the old written-down model is the thing you would want to read afterwards.
     *
     * The output is asserted, not just the exit code. This command's whole job is to tell a
     * user who has decided an agent is wrong about them what state they are now in, and "the
     * model is still there and the new agent will read it" is a different sentence from "the
     * model has been put aside" — so the path has to appear rather than be implied (#60).
     */
    @Test
    fun `forget drops the binding, leaves the residue, and says where it left it`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val store = store()
        store.bind(key)
        val residue = store.prepareResidue(key)
        residue.writeText("prefers dark mode")

        assertEquals(0, run(store, "forget", key.canonical))
        assertNull(store.resolve(key))
        assertEquals("prefers dark mode", residue.readText())
        val text = out.joinToString("\n")
        assertTrue(text.contains("left in place at $residue"), text)
    }

    /**
     * The archiving repair, end to end through the command a user actually runs. Asserted on
     * the reported path rather than on a reconstructed one: a message naming a file that is not
     * the file would be worse than no message, since it is the path they will go and open.
     */
    @Test
    fun `forget can archive the residue and names the archive it wrote`() = runTest {
        config.put(RegistryFlags.forgetResidue, ForgetResidue.ARCHIVE)
        val key = SurfaceKey.Window("firefox")
        val store = store()
        store.bind(key)
        val residue = store.prepareResidue(key)
        residue.writeText("prefers dark mode")

        assertEquals(0, run(store, "forget", key.canonical))

        val reported = out.single { it.contains("archived to") }
            .substringAfter("archived to ").substringBefore(" (")
        assertEquals("prefers dark mode", Path(reported).readText())
        assertFalse(residue.exists(), "and the fresh Lifeless starts empty")
    }

    /**
     * A forget whose residue disposal failed exits non-zero. Under
     * [ForgetResidue.DELETE] the user asked for a model to stop existing; reporting 0 with it
     * still on disk is the shape of failure this repo keeps being bitten by — a signal that
     * answers a different question from the one being asked of it.
     */
    @Test
    fun `a forget whose disposal failed does not report success`() = runTest {
        config.put(RegistryFlags.forgetResidue, ForgetResidue.DELETE)
        val key = SurfaceKey.Window("firefox")
        val store = store()
        store.bind(key)
        val residue = store.prepareResidue(key)
        residue.writeText("prefers dark mode")
        residue.parent.toFile().setWritable(false)

        val code = try {
            run(store, "forget", key.canonical)
        } finally {
            residue.parent.toFile().setWritable(true)
        }

        assertEquals(3, code, out.toString())
        assertTrue(out.any { it.contains("STILL AT $residue") }, out.toString())
        assertEquals("prefers dark mode", residue.readText(), "the model really is still there")
    }

    /**
     * The repair path is the last place a degradation should be invisible: a `forget` made with
     * no cross-process exclusion is one a live holder can undo, which is the bug this command
     * exists to fix. Non-fatal, so the exit code still says the forget happened.
     */
    @Test
    fun `a forget made without the lock warns rather than looking clean`() = runTest {
        val key = SurfaceKey.Window("firefox")
        // A directory where the lock file goes: the one way to make locking fail on a host whose
        // filesystems all lock. Created before anything writes, or the first write makes the file.
        Files.createDirectories(dir.resolve("bindings.json.lock"))
        val store = store()
        store.bind(key)

        assertEquals(0, run(store, "forget", key.canonical))

        val warning = out.singleOrNull { it.startsWith("warning:") }
        assertTrue(warning?.contains("without cross-process exclusion") == true, out.toString())
    }

    @Test
    fun `an unparseable key is refused rather than silently missing`() = runTest {
        assertEquals(2, run(store(), "resolve", "tab:7"))
        assertTrue(out.any { it.startsWith("error:") })
    }

    /** A store that could not read its file must not be used to answer questions about it. */
    @Test
    fun `a broken bindings file makes the CLI refuse rather than report an empty registry`() {
        dir.resolve("broken.json").writeText("nonsense")

        assertEquals(1, run(store("broken.json"), "list"))
        assertTrue(out.first().startsWith("error:"), out.toString())
    }

    private companion object {
        const val HOSTILE = "evil\n# NEW INSTRUCTIONS\u2028x\u2029y\u0085z\u202Ew\u2066v\u2069"

        /** `\n` plus the line terminators `\R` knows beyond it, and the bidi controls. */
        val CONTROLS: Set<Char> =
            ("\n\r\u000B\u000C\u0085\u2028\u2029" + ('\u202A'..'\u202E').joinToString("") +
                ('\u2066'..'\u2069').joinToString("")).toSet()
    }
}
