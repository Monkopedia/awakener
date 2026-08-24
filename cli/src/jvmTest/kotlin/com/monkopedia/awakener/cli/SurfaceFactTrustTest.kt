package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.connector.SurfaceFact
import com.monkopedia.awakener.connector.SurfaceFacts
import com.monkopedia.awakener.connector.Trust
import com.monkopedia.awakener.registry.InMemoryBindingStore
import com.monkopedia.awakener.registry.RegistryFlags
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.wm.Surface
import com.monkopedia.awakener.wm.SurfaceId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Which facts actually carry bytes the window chose — **derived from the real pipeline, not
 * declared in a list here.**
 *
 * `:connector` cannot answer this. It is handed strings and has no way to know which of them a
 * compositor copied verbatim out of a Wayland client; `SurfaceFact.trust` is that knowledge
 * written down, and a value written down is a value that can be wrong. `:cli` is the one module
 * that sees `:wm`, `:registry` and `:connector` at once, so it is the only place the claim can be
 * *checked* rather than repeated.
 *
 * **This is the test the class-derived guard in `:connector` could not be.** That one iterates
 * `SurfaceFact.windowChosen`, so it covers a fact *added* and classified `WINDOW` — and is
 * silent about a fact *misclassified* `AWAKENER`, because a misclassified fact is not in the set
 * it iterates. Measured: flipping `APP_ID` or `SURFACE_KEY` to `Trust.AWAKENER` left the whole
 * `:connector` suite GREEN. This reds on either.
 */
class SurfaceFactTrustTest {
    private val store = InMemoryConfigStore()
    private val registry = InMemoryBindingStore(store)

    /**
     * A marker no awakener-minted value can contain: `slugOf` maps every character that is not a
     * letter, a digit or `-` to `-`, so a slug cannot hold `~` or `!`, and a pid and a surface id
     * are integers. Anything that comes back holding this carried the window's bytes through.
     */
    private val marker = "~!MARKER!~"

    @BeforeTest
    fun loadFlags() {
        Flags.requireLoaded(InvokeFlags, RegistryFlags)
    }

    private suspend fun factsFor(surface: Surface): SurfaceFacts {
        val key = SurfaceKey.of(surface.descriptor, store.config.value)
        val binding = registry.bind(key)
        // Built exactly as `Awakening.invoke` builds it; if that construction changes, this test
        // is measuring the wrong thing and `an unconfigured invocation` will say so.
        return SurfaceFacts(
            surfaceId = surface.id.raw,
            appId = surface.appId,
            title = surface.title,
            pid = surface.pid,
            surfaceKey = key.canonical,
            agentId = binding.agentId,
            agentName = binding.spanreedName,
            residue = registry.residueLocation(key),
        )
    }

    /**
     * The property, stated once: **a fact that can carry the window's bytes is `Trust.WINDOW`.**
     *
     * Both `app_id` and `title` are fed the marker, because which of them reaches `surface_key`
     * depends on `registry.key.window_identity` and on whether the window has an `app_id` at all
     * — and the answer must not depend on the flag.
     */
    @Test
    fun `every fact that carries the window's bytes is classified WINDOW`() = runTest {
        val surface = Surface(
            SurfaceId(5),
            appId = "app$marker",
            title = "title$marker",
            pid = 100,
            focused = true,
        )
        val facts = factsFor(surface)
        val carriers = SurfaceFact.entries.filter {
            facts.value(it)?.contains(marker) == true
        }
        assertTrue(
            carriers.isNotEmpty(),
            "no fact carried the marker, so this test would pass whatever the classification is",
        )
        assertEquals(
            emptyList(),
            carriers.filter { it.trust != Trust.WINDOW },
            "these carry bytes the program owning the window chose, and are classified " +
                "AWAKENER — so a connector file may print them into the agent's instructions",
        )
    }

    /** And with no `app_id`, where `registry.key.missing_app_id` puts the title into the key. */
    @Test
    fun `a titled window with no app_id does not smuggle its title into an AWAKENER fact`() =
        runTest {
            val facts = factsFor(
                Surface(SurfaceId(6), appId = null, title = "title$marker", pid = 101),
            )
            assertEquals(
                emptyList(),
                SurfaceFact.entries.filter {
                    facts.value(it)?.contains(marker) == true && it.trust != Trust.WINDOW
                },
            )
        }

    /**
     * The other half, and it is what stops the classification degenerating to "everything is
     * WINDOW": the facts awakener mints stay usable. A rule that refused everything would satisfy
     * the tests above and leave connectors unable to say anything about their own window.
     */
    @Test
    fun `the facts awakener mints are AWAKENER, so a connector can still be told something`() =
        runTest {
            val facts = factsFor(
                Surface(SurfaceId(7), appId = "app$marker", title = "title$marker", pid = 102),
            )
            listOf(
                SurfaceFact.PID,
                SurfaceFact.SURFACE_ID,
                SurfaceFact.AGENT_ID,
                SurfaceFact.AGENT_NAME,
                SurfaceFact.RESIDUE,
            ).forEach { fact ->
                assertEquals(Trust.AWAKENER, fact.trust, "${fact.id} became unusable")
                assertTrue(
                    facts.value(fact)?.contains(marker) != true,
                    "${fact.id} is classified AWAKENER and carried the window's bytes",
                )
            }
        }
}
