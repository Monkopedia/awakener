package com.monkopedia.awakener.config

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A second `awakener-config set` loop, run in its own JVM by [CrossProcessConfigTest].
 *
 * A real second process rather than a second store here, because the mechanism under test is the
 * one thing a single JVM cannot exercise: the per-path `Mutex` inside [FileConfigStore] already
 * serialises everything in-process, so a store with no cross-process exclusion at all passes
 * every other test in this module. This is the same shape, and the same reason, as
 * `CrossProcessBindingTest` in `:registry` (#59).
 *
 * The flags are declared here rather than in a shared holder because they only have to exist in
 * the process that writes them: `set` refuses a key no flag declares, and the verifying side
 * reads the file as JSON rather than as a snapshot.
 */
object CrossProcessSetProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val (path, prefix, count, barrier) = args
        val keys = (0 until count.toInt()).map { "cross.$prefix.$it" }
        keys.forEach { Flags.int(it, -1, "written by the $prefix probe") }
        val store = FileConfigStore(Path.of(path), environment = emptyMap())
        // Both processes are released together, so their writes actually overlap. Started
        // sequentially they would mostly interleave anyway, but "mostly" is not a test.
        while (!Path.of(barrier).exists()) Thread.sleep(BARRIER_POLL_MS)
        runBlocking {
            keys.forEachIndexed { index, key -> store.set(key, index.toString()) }
        }
    }

    private const val BARRIER_POLL_MS = 1L
}

/**
 * The half of #88's fix a single-JVM test cannot reach.
 *
 * #88 is *two `awakener-config set` runs*, or a `set` beside an editor saving the file — two
 * processes, each performing a read-modify-write over one file. Everything else in this module
 * runs in one JVM, where the per-path `Mutex` alone is enough: delete the `FileChannel.lock` and
 * the rest of the suite stays green. This test is the guard on the file lock itself.
 *
 * Without the lock it fails exactly one way, measured: see the note on [SETS_PER_PROCESS]. Only
 * lost — the per-process staging file means an unlocked write cannot tear the config or take a
 * probe down with it, so `exitValue() == 0` and a parseable file both hold on the way to the
 * failure. Those two assertions stay because they guard other defects: for a hand-authored file,
 * "unparseable" would be a worse outcome than "one value missing", and the tmp name is what
 * rules it out.
 */
class CrossProcessConfigTest {
    private val dir = createTempDirectory("awakener-config-cross-process")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `two processes setting at once lose nothing`() {
        val config = dir.resolve("config.json")
        config.writeText("{}")
        val barrier = dir.resolve("go")

        val processes = listOf("a", "b").map { prefix ->
            prefix to probe(config, prefix, SETS_PER_PROCESS, barrier)
        }
        // Long enough for both JVMs to be up and spinning on the barrier before either writes.
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

        // Read as JSON rather than through a store: what is being checked is which keys survived
        // the two writers, and a snapshot would answer for flags this JVM has never declared by
        // reporting them as unknown and returning defaults.
        val written = runCatching { Json.parseToJsonElement(config.readText()).jsonObject.keys }
            .getOrElse { cause ->
                throw AssertionError("the file both processes wrote no longer parses", cause)
            }
        val expected = listOf("a", "b")
            .flatMap { prefix -> (0 until SETS_PER_PROCESS).map { "cross.$prefix.$it" } }
            .toSet()
        val missing = expected - written
        assertTrue(missing.isEmpty(), "${missing.size} of ${expected.size} values were lost")
    }

    /** Runs [CrossProcessSetProbe] in its own JVM, on this suite's own classpath. */
    private fun probe(config: Path, prefix: String, count: Int, barrier: Path): Process =
        ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            CrossProcessSetProbe::class.java.name,
            config.toString(),
            prefix,
            count.toString(),
            barrier.toString(),
        ).redirectErrorStream(true).start()

    private companion object {
        /**
         * Enough overlap that an unlocked run loses values every round rather than on a lucky
         * interleaving. With `FileChannel.lock` removed, four rounds on kaladin lost 77, 69, 87
         * and 72 of the 200 — 34% to 44%, never near the zero-loss boundary, so the constant is
         * not sitting on a threshold. Locked it costs about 0.3s on top of the barrier delay,
         * which is what stops it going higher.
         *
         * Lower than `:registry`'s 150 because a config write costs more than a binding write —
         * it parses the whole file twice per `set`, once to build the new map and once to
         * rebuild the snapshot — and the file grows to every key both probes have written.
         */
        const val SETS_PER_PROCESS = 100
        const val BARRIER_DELAY_MS = 2000L
        const val PROBE_TIMEOUT_S = 120L
    }
}
