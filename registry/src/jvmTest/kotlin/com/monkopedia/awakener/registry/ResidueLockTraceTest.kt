package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What the residue lock (#239) leaves on disk.
 *
 * A lock file cannot be removed safely — unlinking an `fcntl` lock file reopens the race it guards
 * — so whatever it is named outlives every forget. Named per surface, it outlived a DELETE forget
 * as a file named for the surface's digest, which under the default `APP_ID` identity is a digest
 * of `window:<app_id>`: the one trace of "delete this agent" that `fd1c0c6` did not leave. These
 * pin the bucketed naming that replaced it.
 */
class ResidueLockTraceTest {
    private val dir = createTempDirectory("awakener-residue-lock-trace")
    private val bindings = dir.resolve("bindings.json")
    private val locks = dir.resolve("bindings.json.residue-locks")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun store(config: InMemoryConfigStore) = FileBindingStore(
        config,
        DerivedAgentIdentities(config),
        environment = emptyMap(),
        path = bindings,
    )

    /**
     * Under both per-key layouts — the two whose DELETE removes everything else that names the
     * key — nothing anywhere beside the bindings file names the surface once it is forgotten.
     * Read off the whole tree rather than off the lock directory alone, so a trace that moved
     * somewhere else would be caught too.
     */
    @Test
    fun `a DELETE forget leaves nothing that names the surface`() = runTest {
        listOf(ResidueLayout.PER_KEY_DIR, ResidueLayout.PER_KEY_FILE).forEach { layout ->
            val config = InMemoryConfigStore()
                .put(RegistryFlags.residueLayout, layout)
                .put(RegistryFlags.forgetResidue, ForgetResidue.DELETE)
                .put(RegistryFlags.deregisterOnForget, false)
            val key = SurfaceKey.Window("firefox-$layout")
            val store = store(config)
            store.bind(key)
            val residue = store.prepareResidue(key)
            if (layout == ResidueLayout.PER_KEY_DIR) residue.resolve("model.md").writeText("model")
            val forget = store.unbind(key)
            assertTrue(forget.residue is ResidueOutcome.Deleted, "$layout: ${forget.residue}")

            val names = dir.toFile().walkTopDown().map { it.name }.toList()
            val tracing = names.filter { key.digest in it || key.slug in it }
            assertEquals(emptyList(), tracing, "$layout: left behind among $names")
            assertTrue(locks.toFile().list()!!.isNotEmpty(), "$layout: the lock was taken")
        }
    }

    /**
     * Bounded at one file per first byte of a digest, however many surfaces come and go, and
     * every file named for that byte alone.
     */
    @Test
    fun `lock files are bounded at 256 bucket names`() = runTest {
        val config =
            InMemoryConfigStore().put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
        val store = store(config)
        repeat(SURFACES) { store.prepareResidue(SurfaceKey.Window("app-$it")) }

        val files = locks.toFile().list()!!.toList()
        assertEquals(emptyList(), files.filterNot { BUCKET.matches(it) }, "not a bucket name")
        // The keys are fixed, so this is deterministic: these 2000 digests cover all 256 first
        // bytes, which also says the bucket is the whole first byte and not something narrower.
        assertEquals(256, files.size)
    }

    private companion object {
        const val SURFACES = 2000
        val BUCKET = Regex("[0-9a-f]{2}\\.lock")
    }
}
