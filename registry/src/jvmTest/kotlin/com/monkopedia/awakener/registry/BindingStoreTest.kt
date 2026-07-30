package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BindingStoreTest {
    private val dir = createTempDirectory("awakener-registry")
    private val config = InMemoryConfigStore()
    /** Counts mints, so "did this surface get a *new* agent" is directly observable. */
    private var minted = 0

    /** The residue path the store handed the minter on the last mint. */
    private var mintedAgainst: String? = null

    private val identities = AgentIdentities { key, residuePath ->
        minted++
        mintedAgainst = residuePath
        AgentIdentity(AgentId("agent-minted-$minted"), "minted-${key.slug}")
    }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun store(name: String = "bindings.json") =
        FileBindingStore(config, identities, environment = emptyMap(), path = dir.resolve(name))

    /**
     * The whole reason this module exists: a surface that has been bound once keeps that agent,
     * across a rebind and across the process that made it. A second mint would mean a fresh
     * Lifeless with none of the accumulated model.
     */
    @Test
    fun `a binding is minted once and then found again after a restart`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val first = store().bind(key)
        assertEquals(1, minted)

        val reopened = store()
        assertEquals(first.agent, reopened.resolve(key)?.agent)
        assertEquals(first.spanreedName, reopened.bind(key).spanreedName)
        assertEquals(1, minted, "an already-bound surface must not cost a mint")
    }

    @Test
    fun `an unbound surface resolves to nothing and costs no mint`() = runTest {
        assertNull(store().resolve(SurfaceKey.Window("firefox")))
        assertEquals(0, minted)
    }

    /**
     * A caller that happens to be holding an agent id must not be able to strand the residue
     * accumulated under the existing one — that is the registry's call, via the flag.
     */
    @Test
    fun `rebinding keeps the existing agent by default and replaces when told to`() = runTest {
        val key = SurfaceKey.Origin("https://github.com")
        val store = store()
        val original = store.bind(key)

        val kept = store.bind(key, AgentIdentity(AgentId("agent-other"), "other"))
        assertEquals(original.agent, kept.agent)
        assertEquals(original.createdAtMs, kept.createdAtMs)

        config.put(RegistryFlags.rebindPolicy, RebindPolicy.REPLACE)
        val replaced = store.bind(key, AgentIdentity(AgentId("agent-other"), "other"))
        assertEquals(AgentId("agent-other"), replaced.agent)
        assertEquals("other", replaced.spanreedName)
        assertEquals(AgentId("agent-other"), store().resolve(key)?.agent, "and it is on disk")
    }

    @Test
    fun `unbinding is durable and reported`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val store = store()
        store.bind(key)

        assertTrue(store.unbind(key))
        assertFalse(store.unbind(key), "the second unbind has nothing to do")
        assertNull(store().resolve(key), "and the removal reached the file")
    }

    /**
     * A binding is on disk before `bind` returns. There is no batching mode: with no daemon to
     * own a flush point, a policy that deferred the write would mean bindings that are never
     * written at all — the exact failure this module exists to prevent.
     */
    @Test
    fun `a binding is on disk as soon as it is made`() = runTest {
        val store = store()
        assertFalse(store.path.exists(), "nothing bound, nothing written")

        store.bind(SurfaceKey.Window("firefox"))
        assertTrue(store.path.readText().contains("window:firefox"))
    }

    /**
     * The store is the authority on where residue lives, not the flags: one opened over an
     * explicit path must not hand a minter a location that nothing will ever write to.
     */
    @Test
    fun `the minter is told the residue path this store will actually use`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val store = store()
        store.bind(key)

        assertEquals(store.residueLocation(key), mintedAgainst)
    }

    /**
     * Refusing to write is the important half. Starting empty and overwriting would re-mint
     * every agent on the desktop and abandon every residue file already on disk.
     */
    @Test
    fun `a file that cannot be read is reported and never overwritten`() = runTest {
        val path = dir.resolve("broken.json")
        path.writeText("{ this is not json")
        val store = store("broken.json")

        assertNotNull(store.loadError)
        store.bind(SurfaceKey.Window("firefox"))
        assertEquals("{ this is not json", path.readText(), "the unreadable file is left alone")
    }

    @Test
    fun `a file from a newer awakener is refused rather than reinterpreted`() = runTest {
        val path = dir.resolve("future.json")
        path.writeText("""{"version": 99, "bindings": {}}""")
        val store = store("future.json")

        assertNotNull(store.loadError)
        assertTrue(store.loadError.contains("99"))
    }

    /** Downgrading must not silently delete the bindings the newer build was using. */
    @Test
    fun `a key this build cannot interpret is reported and written back untouched`() = runTest {
        val path = dir.resolve("unknown-kind.json")
        path.writeText(
            """
            {"version": 1, "bindings": {"tab:7": {"agent_id": "agent-x",
             "spanreed_name": "x", "created_at_ms": 1, "last_bound_at_ms": 1}}}
            """.trimIndent(),
        )
        val store = store("unknown-kind.json")

        assertEquals(listOf("tab:7"), store.unreadableKeys)
        assertTrue(store.bindings.value.isEmpty(), "it cannot be resolved against")
        store.bind(SurfaceKey.Window("firefox"))
        assertTrue(path.readText().contains("tab:7"), "but it survives the rewrite")
    }

    @Test
    fun `residue lives beside the bindings file and is stable per surface`() = runTest {
        val key = SurfaceKey.Origin("https://github.com")
        val store = store()

        val location = store.residueLocation(key)
        assertTrue(location.startsWith(dir.resolve("residue").toString()), location)
        assertTrue(location.endsWith(".md"), location)
        assertEquals(location, store().residueLocation(key), "stable across restarts")
        assertNotEquals(location, store.residueLocation(SurfaceKey.Origin("https://gitlab.com")))
    }

    @Test
    fun `residue layout switches between a file and a directory`() = runTest {
        val key = SurfaceKey.Window("firefox")
        assertTrue(store().prepareResidue(key).let { !it.isDirectory() && it.exists() })

        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
        assertTrue(store().prepareResidue(key).isDirectory())
    }

    @Test
    fun `the residue directory can be redirected on its own`() = runTest {
        val elsewhere = dir.resolve("elsewhere")
        config.put(RegistryFlags.residueDir, elsewhere.toString())
        assertTrue(
            store().residueLocation(SurfaceKey.Window("firefox")).startsWith(elsewhere.toString()),
        )
    }
}
