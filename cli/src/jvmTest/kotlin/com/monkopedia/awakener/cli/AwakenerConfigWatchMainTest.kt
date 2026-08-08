package com.monkopedia.awakener.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `awakener-config watch`, run as the process it actually is — which is the only way this
 * claim can be made.
 *
 * **What is under test is that the wiring exists, not that the mechanism works.**
 * `FileConfigStore.watch` has been complete and covered by `FileConfigStoreWatchTest` since
 * #87, and every one of those tests calls `watch` itself. That is precisely why they could all
 * be green while the repo's whole "flip a flag against a running system" working model was
 * false of every binary in the build: nothing called it (#43). A test here that called `watch`,
 * or that called some extracted helper, would reproduce that gap exactly — it would prove the
 * mechanism a ninth time and the wiring not at all. `AwakenerInvokeMainTest` says the same
 * thing about `main` and records that two PRs were blocked in this repo for that shape.
 *
 * So this launches the real entry point on the test's own classpath, edits the file underneath
 * it, and reads what the process says. Delete the `store.watch(this)` call from `ConfigCli` and
 * every test in `FileConfigStoreWatchTest` stays green while these go red, which is the whole
 * difference between the two files.
 *
 * Everything runs against a scratch `HOME`, `AWAKENER_CONFIG` and `XDG_CONFIG_HOME`: nothing
 * here may read Jason's config or write over it.
 */
class AwakenerConfigWatchMainTest {
    private val running = mutableListOf<Watched>()

    @AfterTest
    fun stopEverything() {
        // A `watch` that is working never exits on its own — that is its contract — so every
        // one of these has to be taken down by the test that started it, including the ones
        // whose assertions failed on the way.
        running.forEach { it.process.destroyForcibly() }
    }

    /**
     * The property this file exists for: an edit to the file reaches a process that is already
     * running, and the process says so.
     *
     * The flag flipped is deliberately not one of the `config.watch.*` family — flipping the
     * watch's own settling window or wakeup mode from underneath it would leave a pass
     * ambiguous between "the edit was seen" and "the watch changed how it waits".
     */
    @Test
    fun `an edit reaches a process that is already running`() {
        val watched = watch(config = "{}")
        watched.awaitFollowing()

        val line = watched.awaitWrite(
            """{"$FLAG": true}""",
            "the edit never reached the running process",
            change(FLAG),
        )
        assertTrue(
            line.startsWith("true "),
            "the report names the flag but not the value now in effect: $line",
        )
        assertTrue("was false" in line, "the report does not say what it was: $line")
    }

    /**
     * The realistic failure, not a contrived one: an editor writing the file is not atomic, so
     * a watch fires on a file holding half a JSON document. The rule `:config` states is that a
     * snapshot is total and a typo must not take the process down — this is that rule on the
     * one path where the process is still there afterwards to be taken down.
     *
     * Both halves matter. A reload that collapsed every flag to its default on a half-written
     * file would satisfy "did not crash" and would be the most expensive thing a reload can do
     * against a live desktop; and one that stayed quiet would leave the operator reading values
     * that are no longer what the file says.
     */
    @Test
    fun `a half-written file is reported and does not take the process down`() {
        val watched = watch(config = """{"$FLAG": true}""")
        watched.awaitFollowing()

        watched.awaitWrite("""{"$FLAG": """, "a half-written file went unmentioned") {
            it.startsWith("warning:") && watched.file.absolutePathString() in it
        }
        assertTrue(watched.process.isAlive, "a typo in the config file killed the process")

        // The values it kept are the last good ones, and the proof is that finishing the write
        // with a *different* value produces a change report from `true` rather than from the
        // flag's `false` default — which is what a collapse to defaults would have left behind.
        val line = watched.awaitWrite(
            """{"$FLAG": false}""",
            "the watch never recovered once the file parsed again",
            change(FLAG),
        )
        assertTrue(
            "was true" in line,
            "the half-written file replaced the values it was supposed to keep: $line",
        )
    }

    /**
     * The other arm of the flag that decides whether anything is wired at all. Off, the command
     * is one-shot like every other: it says what is in effect, says why it will not be
     * following, and exits 0.
     *
     * Asserting the exit is what makes this more than a message test — a process that printed
     * the notice and then parked anyway would look identical on stdout.
     */
    @Test
    fun `live reload can be switched off, and then the command exits`() {
        val watched = watch(config = """{"config.watch.enabled": false}""")

        assertEquals(
            0,
            watched.awaitExit(),
            "with the watch off there is nothing to wait for, so it must not park",
        )
        assertTrue(
            watched.lines().any { it.startsWith("not following") && "config.watch.enabled" in it },
            "it stopped following without saying why: ${watched.lines()}",
        )
    }

    /**
     * `POLL` is the fallback wakeup, and it is the one arm nothing else here would exercise:
     * every other test in this file runs under the `BLOCK` default. Running the same wiring
     * under both is what keeps the switch from being a flag with one working position.
     */
    @Test
    fun `the POLL wakeup follows the file too`() {
        val watched = watch(config = """{"config.watch.wakeup": "POLL"}""")
        watched.awaitFollowing()

        val line = watched.awaitWrite(
            """{"config.watch.wakeup": "POLL", "$FLAG": true}""",
            "a watch under POLL never saw the edit",
            change(FLAG),
        )
        assertTrue(line.startsWith("true "), line)
    }

    /**
     * A change report and nothing else.
     *
     * The obvious predicate — the flag's key appears in the line — matches a *parse failure*
     * too, because kotlinx.serialization quotes the offending input back and the input is the
     * half-written file the key is in. That made the malformed-file test above pass on the
     * warning it had already read and never wait for the recovery it is about.
     */
    private fun change(key: String): (String) -> Boolean = { "(was " in it && key in it }

    /** One running `awakener-config watch`, and the file it is watching. */
    private inner class Watched(val process: Process, val file: Path) {
        private val seen = mutableListOf<String>()
        private val queue = LinkedBlockingQueue<String>()

        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine { queue.put(it) }
            }.apply { isDaemon = true; start() }
        }

        fun lines(): List<String> {
            drain()
            return seen.toList()
        }

        private fun drain() {
            while (true) seen += queue.poll() ?: return
        }

        /**
         * Waits for the line the command prints once its watch is launched.
         *
         * Not decoration: the registration completes on another thread, so a test that wrote
         * the file the instant the process started would be racing it and would pass or fail on
         * how loaded the machine is. Every assertion below therefore starts from a process that
         * has demonstrably got as far as launching its watch — and the writes each test makes
         * are retried, because "launched" still is not "registered".
         */
        fun awaitFollowing() {
            awaitLine("the process never got as far as following the file") {
                it.startsWith("following ")
            }
        }

        /**
         * Writes [content] and waits for a line matching [predicate], **writing it again every
         * [REWRITE_MS] until one arrives.**
         *
         * The rewrite is what removes the one race in this file. [awaitFollowing] establishes
         * that the process reached the point of launching its watch, and that is genuinely all
         * it establishes: registering with the `WatchService` finishes on another thread and
         * nothing publishes when it has, so a single write can land in the gap and be the one
         * change nothing was watching for. Rewriting costs nothing once the watch is up — the
         * store re-reads a file it has already read — and turns "did the write beat the
         * registration" from a property of how loaded the machine is into one that resolves on
         * the next attempt.
         *
         * It is also why identical content is written each time rather than something varying:
         * an in-place write produces `ENTRY_MODIFY` whether or not the bytes differ, so the
         * event arrives on every attempt, while the *reported* change stays the single change
         * the test is asserting on.
         */
        fun awaitWrite(
            content: String,
            why: String,
            predicate: (String) -> Boolean,
        ): String = await(why, predicate) { file.writeText(content) }

        /** Waits for a line matching [predicate], touching nothing. */
        fun awaitLine(why: String, predicate: (String) -> Boolean): String =
            await(why, predicate) {}

        /**
         * The wait is generous because the machine this runs on is also building, and the
         * failure it guards is a hang rather than a wrong answer: with the wiring removed there
         * is no line to arrive, ever, so the only thing the timeout trades is how long a red
         * test takes to go red.
         */
        private fun await(
            why: String,
            predicate: (String) -> Boolean,
            retry: () -> Unit,
        ): String {
            val deadline = System.nanoTime() + WAIT_MS * 1_000_000
            drain()
            // Only lines that arrived since the last wait: a predicate loose enough to match
            // something already read would otherwise return an old line and assert nothing.
            val from = seen.size
            var nextRetry = 0L
            while (System.nanoTime() < deadline) {
                if (System.nanoTime() >= nextRetry) {
                    retry()
                    nextRetry = System.nanoTime() + REWRITE_MS * 1_000_000
                }
                val line = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                seen += line
                if (predicate(line)) return line
            }
            fail("$why — after ${WAIT_MS}ms the process had said: ${seen.drop(from)}")
        }

        fun awaitExit(): Int {
            assertTrue(
                process.waitFor(WAIT_MS, TimeUnit.MILLISECONDS),
                "the command did not exit within ${WAIT_MS}ms: ${lines()}",
            )
            return process.exitValue()
        }
    }

    private fun watch(config: String): Watched {
        val home: Path = Files.createTempDirectory("awakener-config-watch-main")
        val file = home.resolve("config.json")
        file.writeText(config)
        home.resolve("state").createDirectories()

        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            CLASSPATH,
            MAIN,
            "watch",
        ).apply {
            environment()["HOME"] = home.absolutePathString()
            environment()["AWAKENER_CONFIG"] = file.absolutePathString()
            environment()["XDG_CONFIG_HOME"] = home.absolutePathString()
            environment()["XDG_STATE_HOME"] = home.resolve("state").absolutePathString()
            // Warnings and discovery notes go to stdout here — `awakener-config`'s `out` is
            // `::println` — so merging keeps the two streams in the order they were written
            // instead of interleaving them by whichever drain thread ran first.
            redirectErrorStream(true)
        }.start()

        return Watched(process, file).also { running += it }
    }

    private companion object {
        const val MAIN = "com.monkopedia.awakener.cli.AwakenerConfigCliKt"

        /**
         * The flag these tests flip.
         *
         * Deliberately not one of the `config.watch.*` family: changing the watch's own
         * settling window or wakeup mode from underneath it would leave a pass ambiguous
         * between "the edit was seen" and "the watch started waiting differently". This one is
         * read only by `set`, which none of these run.
         */
        const val FLAG = "config.store.lock_required"

        /** How long any one line is waited for. See [Watched.awaitLine]. */
        const val WAIT_MS = 30_000L

        /** How long a wait for the next line parks before looking at the deadline again. */
        const val POLL_MS = 50L

        /** How often a wait that owns the file writes it again. See [Watched.awaitWrite]. */
        const val REWRITE_MS = 400L

        /**
         * The test JVM's own classpath, which under Gradle is `:cli`'s test runtime classpath —
         * every module in the build, which is what the launcher lays down too. Flag discovery
         * scans it, so a `watch` started with anything narrower would report every other
         * module's keys as unknown.
         */
        val CLASSPATH: String = System.getProperty("java.class.path")
    }
}
