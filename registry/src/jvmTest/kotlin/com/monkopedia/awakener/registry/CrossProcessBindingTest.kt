package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A second process that binds into a shared bindings file, run by [CrossProcessBindingTest].
 *
 * A real second JVM rather than a second store in this one, because the mechanism under test is
 * the one thing a single JVM cannot exercise: the coroutine mutex inside [FileBindingStore]
 * already serialises everything in-process, so an implementation with no cross-process exclusion
 * at all passes every other test in this module. `SpanreedCliTest` and `RegistryCliTest` drive
 * subprocesses for the same reason — the thing being checked lives outside this JVM.
 */
object CrossProcessBindProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val (path, prefix, count, barrier) = args
        val store = FileBindingStore(
            InMemoryConfigStore(),
            AgentIdentities { key, _ ->
                AgentIdentity(AgentId("agent-$prefix-${key.slug}"), "$prefix-${key.slug}")
            },
            environment = emptyMap(),
            path = Path.of(path),
        )
        // Both processes are released together, so their writes actually overlap. Started
        // sequentially they would mostly interleave anyway, but "mostly" is not a test.
        while (!Path.of(barrier).exists()) Thread.sleep(BARRIER_POLL_MS)
        runBlocking {
            repeat(count.toInt()) { store.bind(SurfaceKey.Window("$prefix-$it")) }
        }
    }

    private const val BARRIER_POLL_MS = 1L
}

/**
 * The half of the fix a single-JVM test cannot reach.
 *
 * #50 is *a live holder plus `awakener-registry forget` in its own process*. Everything else in
 * this module runs in one JVM, where the per-path `Mutex` alone is enough — delete the
 * `FileChannel.lock` and the rest of the suite stays green. This test is the guard on the file
 * lock itself: two JVMs, one bindings file, every write a read-modify-write, and nothing may be
 * lost. Without the lock it fails two ways at once, both observed: about half the bindings go
 * missing, and a process dies on the staging file another one renamed out from under it.
 */
class CrossProcessBindingTest {
    private val dir = createTempDirectory("awakener-cross-process")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `two processes binding at once lose nothing`() {
        val bindings = dir.resolve("bindings.json")
        val barrier = dir.resolve("go")

        val processes = listOf("a", "b").map { prefix ->
            prefix to probe(bindings, prefix, BINDS_PER_PROCESS, barrier)
        }
        // Long enough for both JVMs to be up and spinning on the barrier before either binds.
        Thread.sleep(BARRIER_DELAY_MS)
        barrier.writeText("go")

        processes.forEach { (prefix, process) ->
            assertTrue(
                process.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS),
                "probe '$prefix' never finished",
            )
            assertEquals(
                0,
                process.exitValue(),
                "probe '$prefix' died:\n${process.inputStream.bufferedReader().readText()}",
            )
        }

        val store = FileBindingStore(
            InMemoryConfigStore(),
            AgentIdentities { _, _ -> error("nothing should need minting here") },
            environment = emptyMap(),
            path = bindings,
        )
        assertEquals(null, store.loadError, "the file both processes wrote must still parse")
        val expected = listOf("a", "b")
            .flatMap { prefix -> (0 until BINDS_PER_PROCESS).map { SurfaceKey.Window("$prefix-$it") } }
            .toSet()
        val missing = expected - store.bindings.value.keys
        assertTrue(missing.isEmpty(), "${missing.size} of ${expected.size} bindings were lost")
    }

    /** Runs [CrossProcessBindProbe] in its own JVM, on this suite's own classpath. */
    private fun probe(bindings: Path, prefix: String, count: Int, barrier: Path): Process =
        ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            CrossProcessBindProbe::class.java.name,
            bindings.toString(),
            prefix,
            count.toString(),
            barrier.toString(),
        ).redirectErrorStream(true).start()

    private companion object {
        /**
         * Enough overlap that an unlocked run loses bindings every time rather than sometimes:
         * at 150 each it was 5 for 5, losing about half and taking a process down with it.
         */
        const val BINDS_PER_PROCESS = 150
        const val BARRIER_DELAY_MS = 2000L
        const val PROBE_TIMEOUT_S = 120L
    }
}
