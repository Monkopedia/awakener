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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

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
     * `forget` is the module's advertised repair path, and it has to hold against the process
     * that is *using* the binding — which is the shape this actually runs in: a live holder,
     * plus an `awakener-registry forget` beside it. A store that wrote its whole in-memory map
     * back would resurrect the entry on the holder's next bind, and the user would have no
     * reason to check.
     */
    @Test
    fun `a forget by another process is not undone by the holder's next bind`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val holder = store()
        val original = holder.bind(key)

        assertTrue(store().unbind(key), "awakener-registry forget, in its own process")

        val rebound = holder.bind(key)
        assertNotEquals(original.agent, rebound.agent, "the forgotten agent must not come back")
        assertEquals(2, minted, "the surface gets a fresh Lifeless, which is what forget promises")
        assertEquals(rebound.agent, store().resolve(key)?.agent, "and the file agrees")
    }

    /** The clobber is not even about the forgotten key: a whole-map write-back takes everything. */
    @Test
    fun `a forget of one surface survives a bind of another`() = runTest {
        val forgotten = SurfaceKey.Window("firefox")
        val other = SurfaceKey.Window("foot")
        val holder = store()
        holder.bind(forgotten)

        assertTrue(store().unbind(forgotten))
        holder.bind(other)

        assertNull(store().resolve(forgotten), "an unrelated bind must not resurrect it")
    }

    /** Two live stores are two writers, and neither may drop what the other put on disk. */
    @Test
    fun `two stores binding different surfaces both survive`() = runTest {
        val first = store()
        val second = store()
        first.bind(SurfaceKey.Window("firefox"))
        second.bind(SurfaceKey.Window("foot"))

        assertEquals(
            setOf(SurfaceKey.Window("firefox"), SurfaceKey.Window("foot")),
            store().bindings.value.keys,
        )
    }

    /**
     * The locking is the point of the read-modify-write, not decoration: a store that read the
     * file outside the lock would lose whichever of two overlapping updates landed second.
     */
    @Test
    fun `concurrent binds through two stores all reach the file`() = runTest {
        val first = store()
        val second = store()
        val keys = (0 until 20).map { SurfaceKey.Window("app-$it") }

        withContext(Dispatchers.Default) {
            keys.mapIndexed { index, key ->
                async { (if (index % 2 == 0) first else second).bind(key) }
            }.awaitAll()
        }

        assertEquals(keys.toSet(), store().bindings.value.keys)
    }

    /** A holder that keeps answering with a forgotten binding is the same failure, read side. */
    @Test
    fun `resolve stops answering with a binding another process forgot`() = runTest {
        val key = SurfaceKey.Origin("https://github.com")
        val holder = store()
        holder.bind(key)

        assertTrue(store().unbind(key))
        assertNull(holder.resolve(key))
    }

    /**
     * The other half of the flag: a Lifeless is live and mid-session, so re-establishing the
     * identity the holder is carrying is a defensible answer to a forget that was a mistake.
     * Not the default — `forget` is an explicit instruction and honouring it is the point.
     */
    @Test
    fun `the holder can be told to win over a forget instead`() = runTest {
        val key = SurfaceKey.Window("firefox")
        val holder = store()
        val original = holder.bind(key)
        config.put(RegistryFlags.forgetConflict, ForgetConflict.HOLDER_WINS)

        assertTrue(store().unbind(key))
        val rebound = holder.bind(key)

        assertEquals(original.agent, rebound.agent, "the live agent keeps its surface")
        assertEquals(1, minted, "and no fresh Lifeless is minted")
    }

    /**
     * The single-writer store is still reachable, for a run that wants no per-operation file
     * read at all — and pinning it here keeps what the default protects against visible.
     */
    @Test
    fun `the single-writer store is still available by flag`() = runTest {
        val key = SurfaceKey.Window("firefox")
        config.put(RegistryFlags.storeReload, StoreReload.NEVER)
        val holder = store()
        val original = holder.bind(key)

        assertTrue(store().unbind(key))
        assertEquals(original.agent, holder.bind(key).agent, "the stale map wins, as documented")
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

    /**
     * The same refusal, but arriving *after* the store came up clean — which is the shape it
     * takes in practice, because the file is what someone edits when they want to inspect what
     * an agent knows. The store must not empty out, must not rewrite what it cannot read, and
     * must say so.
     */
    @Test
    fun `a file corrupted mid-session makes the store read-only without going blank`() = runTest {
        val store = store()
        store.bind(SurfaceKey.Window("firefox"))
        store.path.writeText("{ no longer json")

        store.bind(SurfaceKey.Window("foot"))

        assertNotNull(store.loadError, "the store noticed on its next operation")
        assertEquals("{ no longer json", store.path.readText(), "and never wrote over it")
        assertEquals(
            setOf(SurfaceKey.Window("firefox"), SurfaceKey.Window("foot")),
            store.bindings.value.keys,
            "the live map keeps serving rather than collapsing to empty",
        )
    }

    @Test
    fun `a file from a newer awakener is refused rather than reinterpreted`() = runTest {
        val path = dir.resolve("future.json")
        path.writeText("""{"version": 99, "bindings": {}}""")
        val store = store("future.json")

        // Read once: `loadError` now tracks the latest read, so it is a getter rather than a val.
        val error = assertNotNull(store.loadError)
        assertTrue(error.contains("99"), error)
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
