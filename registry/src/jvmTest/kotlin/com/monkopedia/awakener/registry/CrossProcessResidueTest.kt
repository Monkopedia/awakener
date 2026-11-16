package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.FilePermissions
import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.config.PrivateFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * One press's residue preparation in its own JVM, run by [CrossProcessResidueTest]: spins on a
 * barrier file, prepares one surface, and prints what its store found and where it prepared.
 */
object CrossProcessResidueProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val (bindings, title, barrier) = args
        val store = FileBindingStore(
            CrossProcessResidueTest.config(),
            AgentIdentities { _, _ -> error("preparing residue mints nothing") },
            environment = emptyMap(),
            path = Path.of(bindings),
        )
        while (!Path.of(barrier).exists()) Thread.sleep(BARRIER_POLL_MS)
        val prepared = runBlocking { store.prepareResidue(CrossProcessResidueTest.key(title)) }
        println("${store.residueStrandedCheck.finding}\t$prepared\t${store.residueMigrated.orEmpty()}")
    }

    private const val BARRIER_POLL_MS = 1L
}

/**
 * Separate processes preparing surfaces of one application against one residue directory, which
 * is what two presses outside the attach lock's reach are (see [ResidueConcurrencyTest] for which
 * those are). The in-process races there use one store per thread; this is the same shape with
 * nothing shared but the filesystem.
 *
 * **It is also the answer to "the stranded report is last-writer-wins".** It is, within one
 * store — but a store belongs to one press, because `awakener-invoke` is one process per press
 * and prepares once. So two presses at once are two stores, and each must still report its own
 * stranded residue. Every probe here starts with a model left behind by a layout flip and a
 * slug-named application directory to migrate, and every one of them has to say `STRANDED`.
 */
class CrossProcessResidueTest {
    private val dir = createTempDirectory("awakener-residue-cross-process")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `processes preparing one application at once each succeed and each report`() {
        repeat(ROUNDS) { round ->
            val root = dir.resolve("round-$round")
            val bindings = root.resolve("bindings.json")
            val titles = (0 until PROCESSES).map { "tab $it" }
            val cfg = config().config.value
            val slugApp = root.resolve("residue").resolve(key(titles[0]).appSlug)
            val left = titles.map { title ->
                val key = key(title)
                // Private, as any build since #102 made it: a move carries the mode it finds.
                PrivateFiles.createDirectories(slugApp.resolve(key.slug), FilePermissions.OWNER_ONLY)
                    .resolve("model.md").writeText(title)
                // What a flip from PER_KEY_DIR leaves: the old model, which nothing moves.
                RegistryPaths.residuePath(cfg, bindings, key, ResidueLayout.PER_KEY_DIR, ResidueNaming.DIGEST)
                    .createDirectories()
            }
            val barrier = root.resolve("go")

            val processes = titles.map { probe(bindings, it, barrier) }
            Thread.sleep(BARRIER_DELAY_MS)
            barrier.writeText("go")

            val reports = processes.map { process ->
                assertTrue(process.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS), "round $round: a probe hung")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), "round $round: a probe died:\n$output")
                output.trim().split('\t')
            }

            val digestApp = root.resolve("residue").resolve("app-window-${key(titles[0]).appDigest}")
            assertFalse(slugApp.exists(), "round $round: the slug application directory moved")
            titles.forEachIndexed { i, title ->
                val (finding, prepared, migrated) = reports[i] + listOf("", "", "")
                assertEquals("STRANDED", finding, "round $round: $title's report was lost")
                assertEquals(digestApp.resolve("window-${key(title).digest}").toString(), prepared)
                assertEquals(title, Path.of(prepared).resolve("model.md").readText(), "round $round")
                assertFalse("could not move" in migrated, "round $round: $migrated")
                assertEquals(
                    "rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(prepared))),
                )
            }
            assertEquals(
                (left + listOf(digestApp)).toSet(),
                root.resolve("residue").listDirectoryEntries().toSet(),
                "round $round: nothing beside the moved application and the flipped-away models",
            )
        }
    }

    private fun probe(bindings: Path, title: String, barrier: Path): Process =
        ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            CrossProcessResidueProbe::class.java.name,
            bindings.toString(),
            title,
            barrier.toString(),
        ).redirectErrorStream(true).start()

    companion object {
        fun config() = InMemoryConfigStore().apply {
            put(RegistryFlags.residueLayout, ResidueLayout.PER_APP_SESSION_DIR)
            put(RegistryFlags.windowIdentity, WindowIdentity.APP_ID_AND_TITLE)
        }

        fun key(title: String) = SurfaceKey.Window("google-chrome", title)

        private const val PROCESSES = 4
        private const val ROUNDS = 3
        private const val BARRIER_DELAY_MS = 1500L
        private const val PROBE_TIMEOUT_S = 60L
    }
}
