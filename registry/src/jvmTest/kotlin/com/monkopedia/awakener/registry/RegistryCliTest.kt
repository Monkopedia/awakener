package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
     */
    @Test
    fun `forget drops the binding and leaves the residue alone`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val store = store()
        store.bind(key)
        val residue = store.prepareResidue(key)
        residue.writeText("prefers dark mode")

        assertEquals(0, run(store, "forget", key.canonical))
        assertNull(store.resolve(key))
        assertEquals("prefers dark mode", residue.readText())
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
