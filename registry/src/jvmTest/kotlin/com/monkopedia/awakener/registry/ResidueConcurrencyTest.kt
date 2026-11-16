package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.InMemoryConfigStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
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
 * Residue preparation for several presses at once against one residue directory (#167).
 *
 * **Why this is reachable at all.** `awakener-invoke` prepares residue inside the compositor's
 * attach lock (#217), so two presses on one sway queue rather than overlap. But that lock is keyed
 * on the compositor socket and lives under `$XDG_RUNTIME_DIR`, and the residue directory is keyed
 * on neither: two compositors, a press whose environment names a different runtime directory,
 * `wm.attach.lock_scope=PROCESS`, and a lock that could not be taken under
 * `wm.attach.lock_required=false` all reach one residue directory with nothing serialising them.
 * `prepareResidue` itself takes no lock, so its correctness under overlap is its own.
 *
 * **Why it is the ordinary case once it is reachable.** Under the default
 * `registry.key.window_identity=APP_ID` two windows of one application *are* one surface, so the
 * first press on a second Chrome window prepares the same residue as the first; under
 * [ResidueLayout.PER_APP_SESSION_DIR] two surfaces of one application share their parent.
 *
 * Every race here is released off one barrier, with one store per thread — what separate
 * processes have — and every thread's failure is collected rather than left to an uncaught-
 * exception handler that would let the test pass. [CrossProcessResidueTest] runs the same shape
 * across real JVMs.
 */
class ResidueConcurrencyTest {
    private val dir = createTempDirectory("awakener-residue-race")
    private val config = InMemoryConfigStore()
    private val identities = AgentIdentities { key, _ ->
        AgentIdentity(AgentId("agent-${key.digest}"), "lifeless-${key.digest}")
    }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun store(root: Path) =
        FileBindingStore(config, identities, environment = emptyMap(), path = root.resolve("bindings.json"))

    /**
     * The default layout, and two windows of one application, which the default identity makes
     * one surface. Before #167 the file was created check-then-act, so the press that lost the
     * race between `exists()` and `createFile` raised `FileAlreadyExistsException` and lost the
     * key press over a file the other press had just made.
     */
    @Test
    fun `presses preparing one residue file at once all succeed`() {
        repeat(ROUNDS) { round ->
            val root = dir.resolve("round-$round")
            val key = SurfaceKey.Window("google-chrome")
            val prepared = race(PRESSES) { runBlocking { store(root).prepareResidue(key) } }

            assertEquals(1, prepared.toSet().size, "round $round: one surface, one path")
            val file = prepared.first()
            assertTrue(file.isRegularFile(), "round $round: $file")
            assertEquals("rw-------", mode(file), "round $round")
            assertEquals("rwx------", mode(file.parent), "round $round")
        }
    }

    /**
     * The layout the feature is for: several windows of one application, each its own surface,
     * first-pressed together. The shared application directory is created by whichever press
     * gets there first and must be success for every other one — and must be one directory, not
     * one per press.
     */
    @Test
    fun `surfaces of one application preparing at once share one private directory`() {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_APP_SESSION_DIR)
        config.put(RegistryFlags.windowIdentity, WindowIdentity.APP_ID_AND_TITLE)
        repeat(ROUNDS) { round ->
            val root = dir.resolve("round-$round")
            val keys = (0 until PRESSES).map { SurfaceKey.Window("google-chrome", "tab $it") }
            val prepared = race(PRESSES) { i -> runBlocking { store(root).prepareResidue(keys[i]) } }

            assertEquals(PRESSES, prepared.toSet().size, "round $round: a session per surface")
            val app = prepared.map { it.parent }.toSet().single()
            assertEquals(listOf(app), root.resolve("residue").listDirectoryEntries(), "round $round")
            assertEquals(prepared.toSet(), app.listDirectoryEntries().toSet(), "round $round")
            (prepared + listOf(app)).forEach {
                assertTrue(it.isDirectory(), "round $round: $it")
                assertEquals("rwx------", mode(it), "round $round: $it")
            }
        }
    }

    /** The same race with the default identity: every press names the same session directory. */
    @Test
    fun `presses preparing one session directory at once all succeed`() {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_APP_SESSION_DIR)
        repeat(ROUNDS) { round ->
            val root = dir.resolve("round-$round")
            val key = SurfaceKey.Window("google-chrome")
            val session = race(PRESSES) { runBlocking { store(root).prepareResidue(key) } }.toSet().single()
            assertTrue(session.isDirectory(), "round $round")
            assertEquals("rwx------", mode(session), "round $round")
            assertEquals("rwx------", mode(session.parent), "round $round")
        }
    }

    /**
     * Migration under overlap: an application directory still slug-named from before #210, with a
     * session per surface and a file they share, and every surface pressed at once. Exactly one
     * press moves the application directory; every other one must find it moved rather than
     * report a failure, and must then move its own session inside it. Nothing may be lost, split
     * across two application directories, or reported as stranded.
     */
    @Test
    fun `surfaces migrating one slug-named application at once lose nothing`() {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_APP_SESSION_DIR)
        config.put(RegistryFlags.windowIdentity, WindowIdentity.APP_ID_AND_TITLE)
        repeat(ROUNDS) { round ->
            val root = dir.resolve("round-$round")
            val keys = (0 until PRESSES).map { SurfaceKey.Window("google-chrome", "tab $it") }
            val slugApp = root.resolve("residue").resolve(keys[0].appSlug)
            keys.forEach { key ->
                slugApp.resolve(key.slug).createDirectories().resolve("model.md").writeText("model ${key.slug}")
            }
            slugApp.resolve("shared.md").writeText("shared")

            val stores = keys.map { store(root) }
            val prepared = race(PRESSES) { i -> runBlocking { stores[i].prepareResidue(keys[i]) } }

            val digestApp = root.resolve("residue").resolve("app-window-${keys[0].appDigest}")
            assertEquals(listOf(digestApp), root.resolve("residue").listDirectoryEntries(), "round $round")
            assertEquals("shared", digestApp.resolve("shared.md").readText(), "round $round")
            keys.forEachIndexed { i, key ->
                assertEquals(digestApp.resolve("window-${key.digest}"), prepared[i], "round $round")
                assertEquals("model ${key.slug}", prepared[i].resolve("model.md").readText(), "round $round")
                val note = stores[i].residueMigrated.orEmpty()
                assertFalse("could not move" in note, "round $round: $note")
                assertEquals(
                    ResidueStrandedFinding.NONE,
                    stores[i].residueStrandedCheck.finding,
                    "round $round: ${stores[i].residueStranded}",
                )
            }
            assertFalse(slugApp.exists(), "round $round: moved, not copied")
        }
    }

    /**
     * The issue's own example during a migration: the first press on a second Chrome window, while
     * the first window's press is moving the slug-named application directory. The new surface
     * must join the moved directory, not stand up a second slug-named one beside it.
     */
    @Test
    fun `a new window pressed during its application's migration joins the moved directory`() {
        config.put(RegistryFlags.residueLayout, ResidueLayout.PER_APP_SESSION_DIR)
        config.put(RegistryFlags.windowIdentity, WindowIdentity.APP_ID_AND_TITLE)
        repeat(ROUNDS) { round ->
            val root = dir.resolve("round-$round")
            val old = SurfaceKey.Window("google-chrome", "first")
            val fresh = (1 until PRESSES).map { SurfaceKey.Window("google-chrome", "new $it") }
            root.resolve("residue").resolve(old.appSlug).resolve(old.slug).createDirectories()
                .resolve("model.md").writeText("model")

            val keys = listOf(old) + fresh
            val prepared = race(PRESSES) { i -> runBlocking { store(root).prepareResidue(keys[i]) } }

            val digestApp = root.resolve("residue").resolve("app-window-${old.appDigest}")
            assertEquals(listOf(digestApp), root.resolve("residue").listDirectoryEntries(), "round $round")
            assertTrue(prepared.all { it.parent == digestApp }, "round $round: $prepared")
            assertEquals("model", prepared[0].resolve("model.md").readText(), "round $round")
        }
    }

    /**
     * Runs [press] on [count] threads released together and returns each one's result in order,
     * or fails with every exception any of them raised — the point of the exercise, and the part
     * a bare `Thread { }` would hand to the default handler and pass.
     */
    private fun <T> race(count: Int, press: (Int) -> T): List<T> {
        val barrier = CyclicBarrier(count)
        val results = arrayOfNulls<Result<T>>(count)
        val threads = (0 until count).map { i ->
            Thread {
                barrier.await(BARRIER_TIMEOUT_S, TimeUnit.SECONDS)
                results[i] = runCatching { press(i) }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val failures = results.mapNotNull { it?.exceptionOrNull() }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} of $count presses failed: " +
                    failures.joinToString("; ") { "${it::class.simpleName}: ${it.message}" },
                failures.first(),
            )
        }
        return results.map { result ->
            (result ?: throw AssertionError("a press died before the barrier")).getOrThrow()
        }
    }

    private fun mode(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))

    private companion object {
        /** Presses per race: the konstructor#102 shape, eight at once. */
        const val PRESSES = 8

        /** Fresh directories per test, because one interleaving per run is a coin toss. */
        const val ROUNDS = 40

        const val BARRIER_TIMEOUT_S = 30L
    }
}
