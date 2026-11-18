package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.FileLocks
import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.config.InMemoryConfigStore
import com.monkopedia.awakener.connector.ConnectorFlags
import com.monkopedia.awakener.connector.FileConnectorCatalog
import com.monkopedia.awakener.registry.AgentBus
import com.monkopedia.awakener.registry.AgentId
import com.monkopedia.awakener.registry.DerivedAgentIdentities
import com.monkopedia.awakener.registry.FileBindingStore
import com.monkopedia.awakener.registry.ForgetResidue
import com.monkopedia.awakener.registry.LiveAgent
import com.monkopedia.awakener.registry.RegistryFlags
import com.monkopedia.awakener.registry.ResidueLayout
import com.monkopedia.awakener.registry.SurfaceKey
import com.monkopedia.awakener.wm.DockHandle
import com.monkopedia.awakener.wm.DockSpec
import com.monkopedia.awakener.wm.Surface
import com.monkopedia.awakener.wm.SurfaceChange
import com.monkopedia.awakener.wm.SurfaceId
import com.monkopedia.awakener.wm.WindowManager
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * One long-lived worker process for [ResidueForgetRaceTest]: a press, a forget, or a dock
 * teardown's forget, run once per `go <round-root>` line on stdin, answering one line per round.
 *
 * A process rather than a thread because the residue lock's file half is the thing under test,
 * and inside one JVM the per-file mutex alone would serialise everything. Long-lived rather than
 * one JVM per round so that hundreds of rounds cost hundreds of milliseconds of start-up rather
 * than hundreds of seconds, and so the two sides of a round are released by a line each rather
 * than by two JVMs finishing start-up at whatever moment they do.
 */
object ResidueRaceWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val (role, mode, attach) = args
        val attachFile = attach.takeIf { it != "-" }?.let(Path::of)
        Flags.requireLoaded(InvokeFlags, RegistryFlags, ConnectorFlags)
        val input = System.`in`.bufferedReader()
        println("ready")
        while (true) {
            val line = input.readLine() ?: return
            val hold = line.startsWith("go-hold ")
            val root = Path.of(line.substringAfter(' '))
            val answer = try {
                runBlocking { ResidueForgetRaceTest.run(role, root, mode, attachFile, hold) }
            } catch (e: Exception) {
                "ERR ${e::class.simpleName}: ${e.message?.replace('\n', ' ')}"
            }
            println(answer)
            System.out.flush()
        }
    }
}

/**
 * A forget racing a press of the same surface, across processes (#239).
 *
 * Before the residue lock, `unbind`'s disposal ran under nothing a press also held, so an
 * `awakener-registry forget` of a surface could land between the press's `prepareResidue` and the
 * connector rendering into what it had prepared. Measured on `fd1c0c6` by these tests, per round
 * of 300: the press's rendering failed with `NoSuchFileException` when the forget archived or
 * deleted its directory, and under DELETE the forget failed part-way through, having removed the
 * old model and met the press's new file. Neither may happen now, in either mode.
 *
 * The press is the real [Awakening.invoke] — the real [FileBindingStore] and the real
 * [FileConnectorCatalog] against a stub compositor — so the section under test is the one the
 * hotkey runs, not a reconstruction of it.
 */
class ResidueForgetRaceTest {
    private val dir = createTempDirectory("awakener-residue-forget")
    private val workers = mutableListOf<Worker>()

    @AfterTest
    fun cleanUp() {
        workers.forEach { it.process.destroyForcibly().waitFor(10, TimeUnit.SECONDS) }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a forget racing a press under ARCHIVE fails neither`() = race(ForgetResidue.ARCHIVE)

    @Test
    fun `a forget racing a press under DELETE fails neither`() = race(ForgetResidue.DELETE)

    /**
     * The order the two locks are taken in, where a deadlock would live (#239).
     *
     * A press holds the attach lock across its whole run and the residue lock inside it, and reads
     * the tree — which takes the attach lock again — from inside the residue section. A dock
     * teardown's forget holds the attach lock and then takes the residue lock to dispose. A user's
     * forget takes the residue lock alone. Every wait is bounded at [LOCK_WAIT_MS], so a cycle
     * surfaces as a refusal naming the lock it waited for — or sooner, as the kernel's `EDEADLK`
     * on the second `fcntl` — and a tree read that waited on its own press as one naming the
     * attach lock. Every round has to finish with nothing refused.
     *
     * **Odd rounds release all three together; even rounds make the order the question.** Released
     * together, a press that took the two locks the wrong way round deadlocked in one round of 100
     * and passed the next 100 outright, because the gap between its two acquisitions is a few
     * microseconds. So on even rounds the teardown goes first and holds the attach lock for
     * [TEARDOWN_HOLD_MS] before disposing: a press released into that window has to queue on the
     * attach lock *without* the residue lock, or the teardown can never take it, and the user's
     * forget sits those rounds out so that the teardown is the one with a binding to dispose. With
     * the order reversed, that fails on the first even round.
     */
    @Test
    fun `a press, a teardown forget and a user forget released together never deadlock`() {
        val attach = dir.resolve("run").resolve("attach-test.lock")
        val mode = ForgetResidue.ARCHIVE
        val press = start("press", mode, attach)
        val teardown = start("teardown", mode, attach)
        val forget = start("forget", mode, attach)
        val pool = Executors.newFixedThreadPool(3)
        try {
            repeat(DEADLOCK_ROUNDS) { round ->
                val root = prepareRound(round, mode)
                val staggered = round % 2 == 0
                teardown.go(root, hold = staggered)
                if (staggered) Thread.sleep(TEARDOWN_HOLD_MS / 4)
                press.go(root)
                // Not on a staggered round: a user's forget landing first would leave the
                // teardown nothing bound to dispose, and so no reason to want the residue lock.
                val released = listOfNotNull(press, teardown, forget.takeUnless { staggered })
                if (!staggered) forget.go(root)
                val answers = released
                    .map { worker -> pool.submit<String> { worker.answer() } }
                    .map { it.get(ROUND_TIMEOUT_S, TimeUnit.SECONDS) }
                val pressed = answers.first()
                assertTrue(pressed.startsWith("Animated Applied"), "round $round: press $pressed")
                if (staggered) {
                    val tornDown = answers[1]
                    assertTrue(tornDown.startsWith("Archived"), "round $round: teardown $tornDown")
                }
                answers.drop(1).forEach {
                    val refused = it.startsWith("ERR") || it.startsWith("Failed")
                    assertFalse(refused, "round $round: $it")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun race(mode: ForgetResidue) {
        val press = start("press", mode, null)
        val forget = start("forget", mode, null)
        val pressFailures = mutableListOf<String>()
        val forgetFailures = mutableListOf<String>()
        val survivors = mutableListOf<String>()
        repeat(ROUNDS) { round ->
            val root = prepareRound(round, mode)
            // Released by one line each, back to back: the two sides start within microseconds of
            // each other, which is the whole of what makes the overlap an every-round event
            // rather than a lucky one.
            press.go(root)
            forget.go(root)
            val pressed = press.answer()
            val forgot = forget.answer()
            if (!pressed.startsWith("Animated Applied")) pressFailures += "round $round: $pressed"
            if (!forgot.startsWith("Archived") && !forgot.startsWith("Deleted")) {
                forgetFailures += "round $round: $forgot"
            }
            // And the half-delete the issue names, read off the disk: whichever order the two
            // ran in, the old model is never left at the live location.
            val live = location(root)
            if (live.resolve(MODEL).exists()) survivors += "round $round: $live"
        }
        // One assertion over all three, so a red run reports every count rather than the first.
        assertEquals(
            "0 presses failed, 0 forgets failed, 0 old models survived",
            "${pressFailures.size} presses failed, ${forgetFailures.size} forgets failed, " +
                "${survivors.size} old models survived",
            "under $mode over $ROUNDS rounds: " +
                (pressFailures.take(3) + forgetFailures.take(3) + survivors.take(3)),
        )
    }

    /** A fresh store with [KEY] bound and a model written down, which is what a forget forgets. */
    private fun prepareRound(round: Int, mode: ForgetResidue): Path {
        val root = dir.resolve("round-$round").createDirectories()
        runBlocking {
            val store = store(config(root, mode), root)
            store.bind(KEY)
            store.prepareResidue(KEY).resolve(MODEL).writeText("the old model")
        }
        return root
    }

    private fun location(root: Path): Path =
        Path.of(store(config(root, ForgetResidue.KEEP), root).residueLocation(KEY))

    private fun start(role: String, mode: ForgetResidue, attach: Path?): Worker {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            ResidueRaceWorker::class.java.name,
            role,
            mode.name,
            attach?.toString() ?: "-",
        ).redirectErrorStream(true).start()
        val worker = Worker(process, process.inputStream.bufferedReader())
        workers += worker
        assertEquals("ready", worker.answer(), "the $role worker did not start")
        return worker
    }

    class Worker(val process: Process, private val output: BufferedReader) {
        fun go(root: Path, hold: Boolean = false) {
            process.outputStream.write("go${if (hold) "-hold" else ""} $root\n".toByteArray())
            process.outputStream.flush()
        }

        fun answer(): String = output.readLine() ?: throw IOException("worker exited")
    }

    companion object {
        val KEY = SurfaceKey.Window("editor")
        private const val MODEL = "model.md"
        private const val CONNECTOR = "notes"
        private const val ROUNDS = 300
        private const val DEADLOCK_ROUNDS = 100
        private const val LOCK_WAIT_MS = 20_000L
        private const val ROUND_TIMEOUT_S = 60L
        private const val TEARDOWN_HOLD_MS = 200L

        fun config(root: Path, mode: ForgetResidue): InMemoryConfigStore {
            val definitions = root.resolve("definitions")
            definitions.resolve(CONNECTOR).createDirectories().resolve("NOTES.md")
                .writeText("agent {agent_id}")
            return InMemoryConfigStore()
                .put(RegistryFlags.residueLayout, ResidueLayout.PER_KEY_DIR)
                .put(RegistryFlags.forgetResidue, mode)
                .put(RegistryFlags.deregisterOnForget, false)
                .put(RegistryFlags.residueLockWaitMs, LOCK_WAIT_MS)
                .put(ConnectorFlags.dir, definitions.toString())
                .put(ConnectorFlags.default, CONNECTOR)
                .put(InvokeFlags.sweepFirst, false)
        }

        fun store(config: InMemoryConfigStore, root: Path) = FileBindingStore(
            config,
            DerivedAgentIdentities(config),
            environment = emptyMap(),
            path = root.resolve("bindings.json"),
        )

        /** One worker's part in one round, as the line it answers with. */
        suspend fun run(
            role: String,
            root: Path,
            mode: String,
            attach: Path?,
            hold: Boolean,
        ): String {
            val config = config(root, ForgetResidue.valueOf(mode))
            val store = store(config, root)
            return when (role) {
                "press" -> holding(attach) { press(config, store, attach) }
                "teardown" -> holding(attach) {
                    if (hold) delay(TEARDOWN_HOLD_MS)
                    store.unbind(KEY, sessionEnding = true)
                }
                    .residue.let { it::class.simpleName + " " + it }
                "forget" -> store.unbind(KEY).residue.let { it::class.simpleName + " " + it }
                else -> error("no role $role")
            }
        }

        private suspend fun press(
            config: InMemoryConfigStore,
            store: FileBindingStore,
            attach: Path?,
        ): String {
            val awakening = Awakening(
                wm = StubWindowManager,
                registry = store,
                bus = AgentBus { emptyList<LiveAgent>() },
                store = config,
                connectors = FileConnectorCatalog(config.config.value),
                prepareResidue = { store.prepareResidue(it) },
                // A tree read, which in production takes the attach lock again from inside the
                // residue section; it must run straight through rather than wait on its own press.
                dockFor = { holding(attach) { null } },
            )
            return when (val awakened = awakening.invoke()) {
                is Awakened.Animated ->
                    "Animated ${awakened.connector::class.simpleName} ${awakened.connector}"
                else -> "${awakened::class.simpleName} $awakened"
            }
        }

        /** What `awakener-invoke` and a dock teardown hold around their work: the attach lock. */
        private suspend fun <T> holding(attach: Path?, block: suspend () -> T): T =
            if (attach == null) {
                block()
            } else {
                FileLocks.withLock(
                    attach,
                    LOCK_WAIT_MS,
                    contended = { holder -> IllegalStateException("$holder held the attach lock") },
                    unlockable = { why, cause -> throw IOException(why, cause) },
                    block = block,
                )
            }
    }
}

/** One focused window and an attach that stands nothing: the press under test ends at attach. */
private object StubWindowManager : WindowManager {
    private val surface = Surface(SurfaceId(5), "editor", "notes.md", pid = 100, focused = true)

    override suspend fun surfaces(): List<Surface> = listOf(surface)

    override suspend fun resolve(surface: SurfaceId): AgentId? = null

    override suspend fun attach(surface: SurfaceId, dock: DockSpec, agent: AgentId?): DockHandle =
        StubDock(surface, checkNotNull(agent) { "Awakening always hands attach its agent" })

    private class StubDock(override val surface: SurfaceId, override val agent: AgentId) :
        DockHandle {
        override val dockId = SurfaceId(surface.raw + 1000)

        override suspend fun focus() = Unit
        override suspend fun settleFocus() = Unit
        override suspend fun hidden() = false
        override suspend fun hide() = false
        override suspend fun show() = false
        override suspend fun detach() = Unit
    }

    override val changes: Flow<SurfaceChange> = emptyFlow()
}
