package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The in-memory store exists so a test or a deliberately traceless run can bind without writing
 * to disk. It has to agree with [FileBindingStore] on the rebind rule, or a flag would mean two
 * different things depending on which store happened to be wired in.
 */
class InMemoryBindingStoreTest {
    private val config = InMemoryConfigStore()

    @Test
    fun `it mints, resolves, and forgets`() = runTest {
        val store = InMemoryBindingStore(config)
        val key = SurfaceKey.Window("firefox")

        assertNull(store.resolve(key))
        val bound = store.bind(key)
        assertEquals("agent-lifeless-${key.slug}", bound.agentId)
        assertEquals(bound, store.resolve(key))

        assertTrue(store.unbind(key).wasBound)
        assertNull(store.resolve(key))
    }

    @Test
    fun `it applies the same rebind rule as the durable store`() = runTest {
        val store = InMemoryBindingStore(config)
        val key = SurfaceKey.Window("firefox")
        val original = store.bind(key)
        val other = AgentIdentity(AgentId("agent-other"), "other")

        assertEquals(original.agent, store.bind(key, other).agent)

        config.put(RegistryFlags.rebindPolicy, RebindPolicy.REPLACE)
        assertEquals(other.id, store.bind(key, other).agent)
    }

    @Test
    fun `it reports residue relative to wherever the durable layer would put it`() = runTest {
        val store = InMemoryBindingStore(config)
        val key = SurfaceKey.Window("firefox")
        assertEquals("${key.slug}.md", store.residueLocation(key))

        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
        assertEquals(key.slug, store.residueLocation(key))
    }
}
