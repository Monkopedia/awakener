package com.monkopedia.awakener.config

import java.nio.file.Files
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Flags for this suite only.
 *
 * Deliberately **not** named `*Flags`: `ConfigTest` runs `ConfigCli.bootstrap`, which performs a
 * classpath scan that loads every `com.monkopedia.awakener.**` class whose name ends in `Flags`
 * — test classes included. A holder that scan can reach could be initialised by another suite
 * before this one runs, which would make any claim here about *when* it was registered
 * unfalsifiable.
 */
private object WatchKnobs {
    val count = Flags.int("watch.count", 7, "what a watched file is read for")
}

/**
 * A watch event this suite can construct, for stating [FileConfigStore.reactionTo]'s cases one
 * at a time.
 *
 * A fake is how the *table* of cases is written, not how the mechanism is established: the two
 * tests at the end of this class drive a real `OVERFLOW` out of a real `WatchService` through
 * the real loop. An earlier version of this comment claimed no test could provoke one, on the
 * theory that an overflow is the kernel's queue giving up. That is wrong about the platform, and
 * it was a claim about untestability sitting in a PR whose other half exists to delete a claim
 * that stopped a reader doing work they could have done. The cap is the **JDK's**:
 * `AbstractWatchKey` keeps at most 512 pending events per key and replaces the lot with a single
 * `OVERFLOW` beyond that, so a flood of a couple of thousand files provokes one every time.
 */
private class FakeEvent(
    private val kind: WatchEvent.Kind<*>,
    private val context: Path?,
) : WatchEvent<Path> {
    @Suppress("UNCHECKED_CAST")
    override fun kind(): WatchEvent.Kind<Path> = kind as WatchEvent.Kind<Path>

    override fun count(): Int = 1

    override fun context(): Path? = context
}

/**
 * What `FileConfigStore.watch` does, which until #87 nothing said — the mechanism the repo's
 * whole "flip a flag against a running system" working model rests on had no test and no
 * caller (#43).
 *
 * **These are properties of the mechanism, and every one of them calls `watch` itself.** That
 * is the right shape here and it is also exactly why this file could be green while the working
 * model was false of every binary in the build: a suite that calls the thing cannot tell you
 * whether anything else does. `AwakenerConfigWatchMainTest` in `:cli` is the other half — it
 * runs `awakener-config watch` as a process and edits the file underneath it — and the two are
 * complementary rather than duplicates: delete the wiring and only that one goes red.
 *
 * Real time, real files, real inotify — so `runBlocking` rather than `runTest`, whose virtual
 * clock would skip the waits that are the thing under test.
 */
class FileConfigStoreWatchTest {
    private val dir = createTempDirectory("awakener-config-watch")
    private val scope = CoroutineScope(SupervisorJob())

    init {
        Flags.requireLoaded(WatchKnobs, ConfigFlags)
    }

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private class Watched(val store: FileConfigStore, val path: Path, val job: Job)

    /**
     * A store whose watch is *known* to be registered.
     *
     * Nothing publishes when the `WatchService` registration completes — it happens on another
     * thread — so a test that wrote the file immediately after calling `watch` would be racing
     * it, and would pass or fail on how busy the machine is. Establishing liveness by
     * observation instead costs one loop and removes the race: keep writing a sentinel until it
     * arrives, and every later assertion starts from a watcher that has demonstrably seen a
     * change.
     */
    private suspend fun watching(
        name: String,
        environment: Map<String, String> = emptyMap(),
    ): Watched {
        val path = dir.resolve(name)
        path.writeText("""{"watch.count": 0}""")
        val store = FileConfigStore(path, environment)
        assertEquals(0, store.config.value[WatchKnobs.count], "the store did not read its file")
        val job = store.watch(scope)
        withTimeout(LIVENESS_MS) {
            while (store.config.value[WatchKnobs.count] != LIVE) {
                path.writeText("""{"watch.count": $LIVE}""")
                delay(25)
            }
        }
        return Watched(store, path, job)
    }

    private suspend fun Watched.settlesAt(expected: Int, why: String) {
        withTimeoutOrNull(SETTLE_MS) { store.config.first { it[WatchKnobs.count] == expected } }
            ?: fail("$why — read ${store.config.value[WatchKnobs.count]} after ${SETTLE_MS}ms")
    }

    private suspend fun Watched.staysAt(expected: Int, forMs: Long, why: String) {
        withTimeoutOrNull(forMs) { store.config.first { it[WatchKnobs.count] != expected } }
            ?.let { fail("$why — became ${it[WatchKnobs.count]} within ${forMs}ms") }
    }

    @Test
    fun `an edit to the file reaches the live snapshot`() = runBlocking {
        val watched = watching("edit.json")
        watched.path.writeText("""{"watch.count": 2}""")
        watched.settlesAt(2, "the edit never reached the snapshot")
    }

    /**
     * The save pattern that matters most, because it is the one this store performs on itself:
     * `set` writes `<name>.tmp` and renames it over the target. The target's inode does not
     * survive that, so a watch bound to the file rather than to its name would miss the store's
     * own writes first and an editor's second.
     */
    @Test
    fun `a rename over the file reaches the live snapshot`() = runBlocking {
        val watched = watching("rename.json")
        val tmp = dir.resolve("rename.json.tmp")
        tmp.writeText("""{"watch.count": 5}""")
        Files.move(
            tmp,
            watched.path,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        watched.settlesAt(5, "a rename over the target was invisible to the watch")
    }

    /**
     * Why the *directory* is registered, stated as an assertion rather than as a sentence: a
     * `WatchService` watches directory entries, and will not take a regular file at all. The
     * KDoc used to give a different reason — that a rename destroys a file-level watch — which
     * is true of the inode but describes a registration this API never permits in the first
     * place.
     *
     * `NotDirectoryException` is an optional specific exception in `Path.register`'s contract,
     * so this pins the behaviour of the JDK we build on rather than a universal law. That is
     * the useful shape: if some platform ever did allow it, this goes red and the reason above
     * gets re-examined instead of being inherited.
     */
    @Test
    fun `a watch service refuses to register a regular file`() {
        val path = dir.resolve("not-a-directory.json")
        path.writeText("{}")
        path.fileSystem.newWatchService().use { watcher ->
            val failure = assertFailsWith<NotDirectoryException> {
                path.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            }
            assertEquals(path.toString(), failure.file)
        }
    }

    /**
     * The keep-last-good branch of `load`, which had no coverage on the path that reaches it.
     * A hand-edited file is half-written for as long as the editor takes, and a JSON parse of
     * the half is a snapshot of nothing — collapsing to defaults over that would be the most
     * expensive thing a reload could do against a live desktop.
     */
    @Test
    fun `a malformed edit keeps the last good snapshot and is reported`() = runBlocking {
        val watched = watching("malformed.json")
        watched.path.writeText("""{"watch.count": 3}""")
        watched.settlesAt(3, "the good edit never arrived")

        watched.path.writeText("""{"watch.count": """)
        watched.staysAt(3, QUIET_MS, "a half-written file replaced the live snapshot")
        assertTrue(
            watched.store.loadError.value?.contains(watched.path.toString()) == true,
            "the unreadable file went unmentioned: ${watched.store.loadError.value}",
        )

        watched.path.writeText("""{"watch.count": 4}""")
        watched.settlesAt(4, "the watch did not recover once the file parsed again")
        assertNull(watched.store.loadError.value, "the repaired file is still reported broken")
    }

    /**
     * A file that has gone is not evidence that defaults were wanted — and under a
     * delete-then-write save it is not even evidence that the file has gone. Verified against
     * the code as it stood: deleting the watched file took every flag back to its declared
     * default, with `loadError` left null, so nothing anywhere said it had happened.
     */
    @Test
    fun `a file that disappears keeps the values last read from it`() = runBlocking {
        val watched = watching("vanishes.json")
        watched.path.writeText("""{"watch.count": 2}""")
        watched.settlesAt(2, "the edit never arrived")

        watched.path.deleteExisting()
        watched.staysAt(2, QUIET_MS, "a deleted file collapsed every flag to its default")
        assertTrue(
            watched.store.loadError.value?.contains(watched.path.toString()) == true,
            "the file went and nothing said so: ${watched.store.loadError.value}",
        )
    }

    /** The other arm, for a deployment where removing the file is how a reset is performed. */
    @Test
    fun `missing_file DEFAULTS lets a removal reset a running snapshot`() = runBlocking {
        val watched = watching(
            "vanishes-defaults.json",
            environment = mapOf("AWAKENER_CONFIG_WATCH_MISSING_FILE" to "DEFAULTS"),
        )
        watched.path.writeText("""{"watch.count": 2}""")
        watched.settlesAt(2, "the edit never arrived")

        watched.path.deleteExisting()
        watched.settlesAt(7, "removing the file did not take the flag back to its default")
    }

    /**
     * The settling window is a flag, and — the part that matters more — it is read out of the
     * live snapshot rather than captured when the watcher started. This raises it *through the
     * watch itself*, so the value in force when the second edit lands was never available to
     * the coroutine at launch: if it were cached, the second edit would arrive at the old 40ms.
     *
     * This is `:config` holding itself to the rule it states for everybody else — never cache a
     * flag across an operation that could span a reload.
     */
    @Test
    fun `the debounce is a flag and is read from the live snapshot`() = runBlocking {
        val watched = watching("debounce.json")
        watched.path.writeText(
            """{"config.watch.debounce_ms": $SLOW_DEBOUNCE_MS, "watch.count": 2}""",
        )
        watched.settlesAt(2, "the edit raising the debounce never arrived")
        assertEquals(SLOW_DEBOUNCE_MS, watched.store.config.value[ConfigFlags.watchDebounceMs])

        watched.path.writeText(
            """{"config.watch.debounce_ms": $SLOW_DEBOUNCE_MS, "watch.count": 3}""",
        )
        watched.staysAt(2, QUIET_MS, "the raised debounce did not reach the running watcher")
        watched.settlesAt(3, "the edit never arrived once the debounce had elapsed")
    }

    /** A debounce out of range degrades and reports, like every other value; it never throws. */
    @Test
    fun `a negative debounce degrades to the default and is reported`() {
        val config = Config.of(
            mapOf(ConfigFlags.watchDebounceMs.key to JsonPrimitive(-1)),
        )
        assertEquals(40L, config[ConfigFlags.watchDebounceMs])
        assertEquals(
            listOf(ConfigFlags.watchDebounceMs.key),
            config.problems.map { it.key },
        )
    }

    /**
     * The config file shares a directory with whatever else lives under `~/.config/awakener`,
     * and — unavoidably — with the `.tmp` file `set` writes beside it. Re-reading on any of
     * those would make every write of the store's own two events instead of one.
     *
     * Counted rather than compared: an unrelated file's contents never reach the snapshot, so a
     * spurious re-read produces the *same* values and is invisible to a value assertion.
     */
    @Test
    fun `a change to another file in the same directory does not re-read`() = runBlocking {
        val watched = watching("only-mine.json")
        val seen = mutableListOf<Int>()
        val collector = scope.launch {
            watched.store.config.collect { seen += it[WatchKnobs.count] }
        }
        // Let any events still queued from establishing liveness drain before counting.
        delay(QUIET_MS)
        seen.clear()

        dir.resolve("someone-elses.json").writeText("""{"watch.count": 99}""")
        dir.resolve("only-mine.json.tmp").writeText("""{"watch.count": 98}""")
        delay(QUIET_MS)
        collector.cancel()

        assertEquals(
            emptyList(),
            seen,
            "a file that is not the config file provoked a re-read",
        )
    }

    /**
     * A write under a running watch, which is the only pair in this class that contends: the
     * store's own `set` takes the per-path mutex, and the watch's reload now takes the same one
     * so a reload cannot publish a snapshot the write is on its way to replacing.
     *
     * The value has to still be there a moment later. A write that landed and was then undone by
     * the watch's own re-read would look identical at the instant `set` returns.
     */
    @Test
    fun `a set under a running watch survives the watch's own reload`() = runBlocking {
        val watched = watching("set-under-watch.json")
        watched.store.set(WatchKnobs.count.key, "8")

        assertEquals(8, watched.store.config.value[WatchKnobs.count], "the write never landed")
        watched.staysAt(8, QUIET_MS, "the watch's reload undid the store's own write")
    }

    // ---- What an OVERFLOW means (#43's last comment) ----

    /**
     * The decision, stated one case at a time.
     *
     * These four name what [FileConfigStore.reactionTo] answers; the two tests after them drive a
     * real overflow through the loop, so the wiring from the answer back into the snapshot is
     * covered too rather than left as the gap this comment used to admit to.
     */
    @Test
    fun `an overflow re-reads by default`() {
        assertEquals(
            FileConfigStore.Reaction.REREAD,
            FileConfigStore.reactionTo(
                listOf(FakeEvent(OVERFLOW, null)),
                "config.json",
                WatchOverflow.REREAD,
            ),
            "an overflow is the one event that says a change may have been lost",
        )
    }

    @Test
    fun `an overflow under REPORT keeps the snapshot`() {
        assertEquals(
            FileConfigStore.Reaction.REPORT_LOSS,
            FileConfigStore.reactionTo(
                listOf(FakeEvent(OVERFLOW, null)),
                "config.json",
                WatchOverflow.REPORT,
            ),
        )
    }

    /**
     * The regression this is really about: before it was handled, an overflow failed the
     * `as? Path` name filter — it carries no context path — and so read as "nothing here
     * concerns me", which is the *opposite* of what it means.
     */
    @Test
    fun `an event for another file is still ignored`() {
        assertEquals(
            FileConfigStore.Reaction.IGNORE,
            FileConfigStore.reactionTo(
                listOf(FakeEvent(ENTRY_MODIFY, Path.of("config.json.tmp"))),
                "config.json",
                WatchOverflow.REREAD,
            ),
        )
    }

    /**
     * A batch holding both is a re-read whatever the flag says, and then has nothing left to
     * report: the read the named event earned is the same read the overflow would have asked
     * for, and it takes in the whole file either way.
     */
    @Test
    fun `an overflow beside a real change re-reads even under REPORT`() {
        assertEquals(
            FileConfigStore.Reaction.REREAD,
            FileConfigStore.reactionTo(
                listOf(FakeEvent(OVERFLOW, null), FakeEvent(ENTRY_MODIFY, Path.of("config.json"))),
                "config.json",
                WatchOverflow.REPORT,
            ),
        )
    }

    // ---- A real OVERFLOW, driven through the real loop ----

    /**
     * Puts the watcher into a state where a genuine `OVERFLOW` reaches it, and returns the store.
     *
     * Two things have to be true at once, and neither is a timing gamble:
     *
     * - **The watcher must not be draining while the flood runs.** It drains every 250ms, so a
     *   flood into a watcher that is polling normally arrives as a string of small batches and
     *   never reaches the cap. Parking it in its own debounce is the window: a named edit sends
     *   it into `delay(config.watch.debounce_ms)`, and [PARK_MS] is how long that lasts. The
     *   debounce is raised through the *file* first and waited for, so the value is in the live
     *   snapshot before the parking edit is made — the loop reads it per event, so an edit that
     *   carried the new debounce with it would still be timed by the old one.
     * - **The flood must exceed the JDK's per-key cap**, which is 512 and is a constant rather
     *   than a function of machine speed. [FLOOD_FILES] is 2000, and the whole flood measured
     *   28–58ms on this host across three rounds, against a park of [PARK_MS]. The drained batch
     *   was `[OVERFLOW]` — size 1, no context path — every time.
     */
    private suspend fun parkedAndFlooded(name: String, environment: Map<String, String>): Watched {
        val watched = watching(name, environment)
        val slow = """"config.watch.debounce_ms": $PARK_MS"""
        watched.path.writeText("""{$slow, "watch.count": 2}""")
        watched.settlesAt(2, "the edit raising the debounce never arrived")

        // Parks the watcher for PARK_MS. Everything below happens inside that window.
        watched.path.writeText("""{$slow, "watch.count": 3}""")
        delay(PARKED_MS)
        repeat(FLOOD_FILES) { dir.resolve("junk$it").writeText("x") }

        // The park ends by re-reading the parking edit. The overflow is a *separate* batch,
        // drained on the next turn of the loop — which is the turn under test.
        watched.settlesAt(3, "the edit that parked the watcher never arrived")
        return watched
    }

    /**
     * The arm with nothing else holding it: `REPORT` must actually report.
     *
     * `Reaction.REPORT_LOSS -> Unit` passes every other test in this file, which is what makes
     * this the one that earns its place. The waiting period is longer than [PARK_MS] on purpose —
     * under `REREAD` the same batch costs one more debounce and *then* re-reads, so a shorter
     * window would go green for both values of the flag and distinguish nothing.
     */
    @Test
    fun `a real overflow under REPORT is reported and provokes no re-read`() = runBlocking {
        val watched = parkedAndFlooded(
            "overflow-report.json",
            mapOf("AWAKENER_CONFIG_WATCH_OVERFLOW" to "REPORT"),
        )
        // Identity, not equality: `load` builds a fresh `Config` every time and the class does
        // not define equals, so "is this the same snapshot" is exactly "has nothing re-read".
        // It is also what makes the StateFlow publish a re-read that changed no value.
        val snapshot = watched.store.config.value

        val reported = withTimeoutOrNull(SETTLE_MS) {
            watched.store.loadError.first { it != null }
        } ?: fail("a real overflow was dropped in silence")
        assertTrue(
            ConfigFlags.watchOverflow.key in reported,
            "the report does not name the flag that would re-read instead: $reported",
        )

        delay(PARK_MS + QUIET_MS)
        assertSame(snapshot, watched.store.config.value, "REPORT replaced the snapshot anyway")
        assertEquals(3, watched.store.config.value[WatchKnobs.count], "the values moved")
    }

    /**
     * And the default arm does re-read, on an overflow that carries no path to match on.
     *
     * This is the regression in its original form. Before the overflow was handled, this batch
     * failed the `as? Path` name filter and read as "nothing here concerns me" — so the watch
     * went silent at the one moment the operating system had told it a change may have been
     * lost, and nothing would have re-read until some later event happened to arrive.
     */
    @Test
    fun `a real overflow under the default re-reads rather than going quiet`() = runBlocking {
        val watched = parkedAndFlooded("overflow-reread.json", emptyMap())
        val snapshot = watched.store.config.value

        withTimeoutOrNull(PARK_MS + SETTLE_MS) {
            watched.store.config.first { it !== snapshot }
        } ?: fail("a real overflow left the watch quiet")

        assertNull(
            watched.store.loadError.value,
            "a re-read that found the file intact still reported it broken",
        )
        assertEquals(3, watched.store.config.value[WatchKnobs.count], "the values moved")
    }

    // ---- How the watch waits between events ----

    /**
     * The lifetime is the caller's: a cancelled scope leaves nothing reading the file.
     *
     * **This one does not exercise the close-to-wake arrangement, and saying so is the point.**
     * [watching] establishes liveness by writing the file in a loop, so at the moment of
     * cancellation the `WatchService` still holds events that loop queued: `take` returns a
     * pending key immediately instead of parking, the loop reaches its `delay`, and *that* is
     * what observes the cancellation. Delete the sibling and this test stays green. It was
     * claimed here as the test that held the arrangement; it is not, and the next test is —
     * which is why that one drains the queue first. What this covers is the ordinary case, and
     * the ordinary case is worth covering.
     */
    @Test
    fun `cancelling the scope retires the watch`() = runBlocking {
        val watched = watching("cancelled.json")
        watched.job.cancelAndJoinWithin()

        watched.path.writeText("""{"watch.count": 6}""")
        watched.staysAt(LIVE, QUIET_MS, "the watcher outlived the scope that owned it")
    }

    /**
     * The same retirement from the state the arrangement exists for: a watcher genuinely parked
     * in `WatchService.take`, with nothing queued to return early on.
     *
     * `take` parks a thread in a native wait that cancellation cannot reach — cancelling sets a
     * flag on a coroutine that is not going to look at it — so `watch` runs a sibling coroutine
     * whose only job is to close the service, and closing it is what returns from the wait.
     * Nothing else in this class puts the loop there, which is why deleting that sibling left
     * the whole suite green: measured, `:config` was 63 tests and 0 failures with the sibling
     * replaced by nothing at all. This test is what makes it 63 passes and one failure, and it
     * fails on the **join** — the watcher never stops — rather than on an assertion about
     * values.
     *
     * [quiesces] is the part that does the work. A fixed sleep would be a guess about how long
     * the queue takes to drain; waiting until the snapshot stops being replaced is the
     * observation that it *has* drained, on the same principle as [watching] establishing
     * registration by observation rather than by delay.
     */
    @Test
    fun `cancelling a quiescent watch retires it`() = runBlocking {
        val watched = watching("quiescent.json")
        watched.quiesces()
        watched.job.cancelAndJoinWithin()

        watched.path.writeText("""{"watch.count": 6}""")
        watched.staysAt(LIVE, QUIET_MS, "the watcher outlived the scope that owned it")
    }

    /**
     * Waits until nothing has replaced the snapshot for [QUIET_MS], which is what "the watch has
     * drained its queue and is parked in its wait" looks like from outside.
     *
     * Identity rather than value: a re-read that changes no flag still publishes a fresh [Config]
     * — `load` builds a new one every time and the class defines no `equals` — so `!==` sees the
     * re-reads that a value comparison cannot. That is the same distinction
     * `a real overflow under REPORT is reported and provokes no re-read` turns on.
     */
    private suspend fun Watched.quiesces() {
        val drained = withTimeoutOrNull(LIVENESS_MS) {
            while (true) {
                val snapshot = store.config.value
                withTimeoutOrNull(QUIET_MS) { store.config.first { it !== snapshot } } ?: break
            }
            true
        }
        if (drained == null) {
            fail("the watch never went quiet within ${LIVENESS_MS}ms, so it was never parked")
        }
    }

    /**
     * The other wakeup, which reaches the snapshot by the same route and gets there by waking
     * on a timer instead of waiting on the file.
     *
     * It exists as a fallback, so what has to be true of it is that it is a *fallback* — a
     * position of the switch that works — rather than a position that compiles.
     */
    @Test
    fun `the POLL wakeup reaches the live snapshot too`() = runBlocking {
        val watched = watching("poll.json", mapOf("AWAKENER_CONFIG_WATCH_WAKEUP" to "POLL"))
        assertEquals(WatchWakeup.POLL, watched.store.config.value[ConfigFlags.watchWakeup])

        watched.path.writeText("""{"watch.count": 2}""")
        watched.settlesAt(2, "a watch under POLL never saw the edit")
    }

    /**
     * And it stops when its scope does, by the mechanism it has instead of the sibling — a
     * poll returns on its own and the loop then sees an inactive scope.
     */
    @Test
    fun `cancelling the scope retires a POLL watch`() = runBlocking {
        val watched =
            watching("poll-cancelled.json", mapOf("AWAKENER_CONFIG_WATCH_WAKEUP" to "POLL"))
        watched.job.cancelAndJoinWithin()

        watched.path.writeText("""{"watch.count": 6}""")
        watched.staysAt(LIVE, QUIET_MS, "the watcher outlived the scope that owned it")
    }

    /**
     * The interval is a flag rather than the constant it was, and like every other value out of
     * range it degrades and reports instead of being coerced quietly or taking the watch down.
     *
     * Zero is the value worth pinning: a poll of 0ms is a legal argument to `WatchService.poll`
     * and turns the loop into a spin over a whole core, which is the failure a range exists to
     * stop and the one that would otherwise be discovered by a fan.
     */
    @Test
    fun `a poll interval out of range degrades to the default and is reported`() {
        val config = Config.of(mapOf(ConfigFlags.watchPollMs.key to JsonPrimitive(0)))
        assertEquals(250L, config[ConfigFlags.watchPollMs])
        assertEquals(listOf(ConfigFlags.watchPollMs.key), config.problems.map { it.key })
    }

    private suspend fun Job.cancelAndJoinWithin() {
        cancel()
        withTimeoutOrNull(SETTLE_MS) { join() }
            ?: fail("the watch did not stop within ${SETTLE_MS}ms of being cancelled")
    }

    private companion object {
        /** The sentinel [watching] uses to establish that the watch is registered. */
        const val LIVE = 1

        const val LIVENESS_MS = 10_000L

        /** How long a change is given to arrive. Generous: the machine also builds. */
        const val SETTLE_MS = 10_000L

        /** How long "nothing happened" is watched for before it counts as nothing. */
        const val QUIET_MS = 750L

        /**
         * Long enough that a snapshot taken [QUIET_MS] after an edit cannot have seen it, and
         * short enough that the test still finishes.
         */
        const val SLOW_DEBOUNCE_MS = 2_500L

        /**
         * How long the watcher sits in its debounce while the flood runs — the window in which
         * it is guaranteed not to be draining. Comfortably more than [PARKED_MS] plus the
         * measured 28–58ms the flood itself takes.
         */
        const val PARK_MS = 1_500L

        /** How long the parking edit is given to reach the watcher and send it into its wait. */
        const val PARKED_MS = 700L

        /**
         * Enough to pass the JDK's per-key cap of 512 pending events with room to spare. Not a
         * threshold that machine speed moves: `AbstractWatchKey` counts events, not milliseconds,
         * and 400 already overflows on JDK 21.
         */
        const val FLOOD_FILES = 2_000

    }
}
