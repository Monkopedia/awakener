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
}
