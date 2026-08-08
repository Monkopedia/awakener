package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.InMemoryConfigStore
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one place awakener leaves its own process, exercised as a real subprocess.
 *
 * The rest of [SpanreedCli]'s tests run against a fake [CommandRunner], which is right for
 * asserting on argv but cannot see the failures that matter here: both cases below hang forever
 * against a runner that reads stdout to EOF before touching stderr, and neither is exotic —
 * `registry.agent.id_source` defaults to `SPANREED`, so every first bind of a surface goes
 * through this code with `attach` awaiting it.
 *
 * Each case runs the (blocking) call on another thread and fails on a bounded wait, so a
 * regression shows up as a failing test rather than a build that never finishes.
 */
class ProcessCommandRunnerTest {
    @Test
    fun `a child that floods stderr does not deadlock`() {
        // More than a pipe buffer (~64 KiB) of stderr while the caller is reading stdout. A
        // spanreed that logs a stack trace is enough to reach this.
        val result = bounded(seconds = 15) {
            ProcessCommandRunner().run(
                listOf("sh", "-c", "yes x | head -n 100000 >&2; echo ok"),
                emptyMap(),
            )
        }

        assertEquals(0, result.exitCode)
        assertEquals("ok", result.stdout.trim(), "stdout must survive the flood on stderr")
        assertTrue(
            result.stderr.length > 64 * 1024,
            "stderr should have been drained in full, got ${result.stderr.length} bytes",
        )
    }

    @Test
    fun `a hung child is killed when the timeout expires`() {
        val startedAt = System.nanoTime()
        val result = bounded(seconds = 15) {
            ProcessCommandRunner(timeoutMs = { 300L })
                .run(listOf("sh", "-c", "sleep 60"), emptyMap())
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("timed out"), result.stderr)
        assertTrue(
            elapsedMs < 10_000,
            "took ${elapsedMs}ms: the timeout must bound the call, not the child's lifetime",
        )
    }

    /** A child holding the pipes open past its own exit must not extend the call either. */
    @Test
    fun `output is returned as soon as the child exits`() {
        val result = bounded(seconds = 15) {
            ProcessCommandRunner().run(listOf("sh", "-c", "echo hi; sleep 30 &"), emptyMap())
        }

        assertEquals(0, result.exitCode)
        assertEquals("hi", result.stdout.trim())
    }

    /**
     * The other half of the case above, and the one that was silent (#51).
     *
     * Same shape — something the child left behind still holds the pipe — but here the output
     * has not all arrived when the drain gives up, so what comes back is a *prefix*. Against
     * unchanged `main` this returned `exitCode=0`, `stdout="agent-lifeless-fire"` and
     * `succeeded=true`: a run that read half of what the child wrote, reporting success.
     *
     * That is not a subprocess-plumbing nicety. `spanreed agent-id` prints one line, and a
     * prefix of an agent id is a valid agent id — see `SpanreedCliTest` for what it costs.
     *
     * The child sleeps a second before exiting, and that is load-bearing rather than padding.
     * The JVM drains whatever is available on a child's pipe the instant it exits and puts a
     * buffer in the pipe's place, so a reader that has not yet blocked when that happens gets a
     * clean EOF over a truncated read and this signal misses — measured at 1 run in 25 without
     * the sleep, 0 in 40 with it. The sleep puts the drain thread solidly inside a blocked read
     * first, which is the case the signal exists for. `SpanreedCliTest` covers the residual, at
     * the only level that can: the framing of the answer itself.
     */
    @Test
    fun `a stdout that was only half read is not reported as success`() {
        val result = bounded(seconds = 15) {
            ProcessCommandRunner().run(
                // The second half arrives after registry.agent.drain_grace_ms (1s by default),
                // from a background job the child leaves holding its stdout — so the child exits
                // 0 with its output in flight.
                listOf("sh", "-c", "{ printf part; sleep 5; printf rest; } &\nsleep 1\nexit 0"),
                emptyMap(),
            )
        }

        assertEquals(0, result.exitCode, "the child really did exit cleanly; that is the point")
        assertEquals("part", result.stdout, "and what arrived is a prefix of what it wrote")
        assertTrue(!result.succeeded, "a run whose stdout was cut off must not report success")
        assertTrue(
            result.shortRead?.contains("cut off") == true,
            "the reason has to name the truncation, not merely be non-null: ${result.shortRead}",
        )
    }

    /** A whole read of the same shape is not accused of truncation. */
    @Test
    fun `a child that closes its pipes reports no short read`() {
        val result = bounded(seconds = 15) {
            ProcessCommandRunner().run(listOf("sh", "-c", "printf whole"), emptyMap())
        }

        assertEquals("whole", result.stdout)
        assertNull(result.shortRead)
        assertTrue(result.succeeded)
    }

    // -------------------------------------------------------- #110: one observation, not two
    //
    // `Drain.collect` snapshotted the buffer and *then* read the flag saying whether the read
    // had finished, and the flag was written outside the buffer's monitor. A drain that appended
    // its last chunk in the gap between those two therefore published a prefix with `shortRead`
    // null — the one outcome the whole signal exists to prevent.
    //
    // **The real window is tens of nanoseconds and nothing can hit it on purpose**: it is the
    // stretch between a `monitorexit` and the volatile read on the next line, and reaching it
    // needs the collector descheduled there while the drain runs append, EOF and a write. So
    // these two do not try. They pin the invariant — *a body reported whole is the whole body* —
    // over an interleave that is scheduled rather than raced, and they are calibrated to catch
    // the defect when its window is widened enough to be reachable. Measured, on this tree:
    // reinstating the pre-fix ordering with a 500ms delay wedged into the gap reds the first of
    // these and leaves every pre-existing `:registry` test green, including the one directly
    // above — its own margin is 3s, so it does not notice until the widening is 6× larger.

    /**
     * The drain's grace expires with the answer still in flight, and what comes back has to say
     * so — with the body and the verdict on it taken as one observation, not two.
     *
     * `registry.agent.drain_grace_ms` is 200 here and the rest of the output lands ~300ms after
     * that expires, which is what makes the interleave scheduled rather than raced: the snapshot
     * happens first, and the only way to report this body as complete is to read the completion
     * flag *later* than the body. 300ms is the sensitivity — it is how far the gap has to be
     * stretched before the defect is visible at all — and it is deliberately much tighter than
     * the 3s margin the test above happens to have.
     *
     * The invariant is asserted rather than the exact string, because the assertion has to stay
     * true in both directions: on a stalled machine the drain may legitimately have finished
     * before the snapshot, and `partrest` with no short read is then the correct answer. What is
     * never correct is `part` with no short read — so a stall makes this test weaker, never
     * flaky, which is the trade to want in a timing test that gates a build.
     */
    @Test
    fun `a body reported whole is the whole body, even across the grace expiring`() {
        val result = bounded(seconds = 15) {
            ProcessCommandRunner(drainGraceMs = { 200L }).run(
                listOf("sh", "-c", "{ printf part; sleep 1.5; printf rest; } &\nsleep 1\nexit 0"),
                emptyMap(),
            )
        }

        assertEquals(0, result.exitCode, "the child exits cleanly; that is what makes this hard")
        assertTrue(
            result.stdout == "part" || result.stdout == "partrest",
            "the child writes 'partrest' in two pieces, so anything else is a third failure: " +
                "'${result.stdout}'",
        )
        assertTrue(
            result.shortRead != null || result.stdout == "partrest",
            "a prefix reported as whole is the defect (#110): stdout='${result.stdout}' " +
                "shortRead=${result.shortRead}",
        )
    }

    /**
     * The control, and it is not optional: an implementation that reported every read as short
     * would satisfy the invariant above completely, and it is the implementation a too-eager
     * guard produces.
     *
     * Same shape — a background job holding the pipe past the child's exit — with the grace set
     * wide enough that the drain finishes inside it. Both halves must come back, and the read
     * must not be accused of truncation.
     */
    @Test
    fun `a read that finished inside the grace is whole and is not accused of being short`() {
        val result = bounded(seconds = 15) {
            ProcessCommandRunner(drainGraceMs = { 3_000L }).run(
                listOf("sh", "-c", "{ printf part; sleep 0.2; printf rest; } &\nsleep 1\nexit 0"),
                emptyMap(),
            )
        }

        assertEquals("partrest", result.stdout)
        assertNull(result.shortRead)
        assertTrue(result.succeeded)
    }

    /**
     * The third arm of the same observation, and the one no public path can reach on demand.
     *
     * `Drain` resolves a read three ways — EOF, the grace expiring, and the read *failing* —
     * and all three now happen under one monitor. The first two are driven above through a real
     * child; the third needs a pipe that breaks mid-read, which is not something to fake against
     * a live one, so it landed with #110 disclosed as untested.
     *
     * It is reachable without widening anything production sees: `Drain` is `internal`, it reads
     * whatever [InputStream] it is handed, and a stream that throws after *n* bytes is a few
     * lines here. The property is the one the whole change is about — a read that ended early
     * yields the prefix **and** a reason, never the prefix alone — and the clean stream at the
     * end is the control, because "always report a failure" would satisfy the first half by
     * itself.
     */
    @Test
    fun `a stream that fails mid-read yields the prefix and a reason`() {
        val head = "agent-lifeless-".toByteArray()
        val breaks = object : InputStream() {
            private var at = 0

            override fun read(): Int {
                if (at >= head.size) throw IOException("the pipe went away")
                return head[at++].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= head.size) throw IOException("the pipe went away")
                val n = minOf(len, head.size - at)
                head.copyInto(b, off, at, at + n)
                at += n
                return n
            }
        }

        val cut = ProcessCommandRunner.Drain(breaks, 5_000L).collect()

        assertEquals("agent-lifeless-", cut.text, "what did arrive is kept, not discarded")
        val reason = assertNotNull(cut.shortBy, "a prefix with no reason is the defect (#51/#110)")
        assertTrue(reason.contains("failed mid-read"), "the reason must name the cause: $reason")

        val intact = "agent-lifeless-firefox\n".byteInputStream()
        val whole = ProcessCommandRunner.Drain(intact, 5_000L).collect()

        assertEquals("agent-lifeless-firefox\n", whole.text)
        assertNull(whole.shortBy, "a stream that reached EOF must not be accused of failing")
    }

    // ------------------------------------------------------------------ the budgets are flags
    //
    // Both numbers govern how long a key press can stall, which is Jason's time rather than a
    // fact about the system, so both are flags and neither is captured when the runner is built.
    // These drive the config-store constructor — the one production uses — so a flag that stops
    // reaching the runner fails here rather than in a hotkey.

    @Test
    fun `the kill deadline comes from registry_agent_command_timeout_ms`() {
        val config = InMemoryConfigStore().put(RegistryFlags.commandTimeoutMs, 300L)
        val startedAt = System.nanoTime()
        val result = bounded(seconds = 15) {
            ProcessCommandRunner(config).run(listOf("sh", "-c", "sleep 60"), emptyMap())
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(ProcessResult.TIMED_OUT, result.exitCode)
        assertTrue(
            result.stderr.contains("timed out after 300ms"),
            "the note has to quote the budget that was actually applied: ${result.stderr}",
        )
        assertTrue(elapsedMs < 10_000, "took ${elapsedMs}ms against a 300ms flag")
    }

    @Test
    fun `the drain grace comes from registry_agent_drain_grace_ms`() {
        val config = InMemoryConfigStore().put(RegistryFlags.drainGraceMs, 200L)
        val startedAt = System.nanoTime()
        val result = bounded(seconds = 15) {
            ProcessCommandRunner(config).run(
                listOf("sh", "-c", "{ printf part; sleep 5; printf rest; } &\nsleep 1\nexit 0"),
                emptyMap(),
            )
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(0, result.exitCode)
        assertTrue(
            result.shortRead?.contains("200ms") == true,
            "the reason has to quote the grace that was actually spent: ${result.shortRead}",
        )
        assertTrue(
            elapsedMs < 3_000,
            "took ${elapsedMs}ms: a 200ms grace must not wait out a 5s grandchild",
        )
    }

    /**
     * A negative grace is a value that decodes and is nonsense, which is the half of "bad value"
     * that used to change behaviour in silence. Declared on the flag, so the file can be
     * hand-edited against a running desktop and a typo degrades and says so.
     */
    @Test
    fun `an out-of-range budget degrades to its default and is reported`() {
        val snapshot = Config.of(
            mapOf(
                RegistryFlags.drainGraceMs.key to JsonPrimitive(-1),
                RegistryFlags.commandTimeoutMs.key to JsonPrimitive(0),
            ),
        )

        assertEquals(1_000L, snapshot[RegistryFlags.drainGraceMs])
        assertEquals(10_000L, snapshot[RegistryFlags.commandTimeoutMs])
        assertEquals(
            setOf(RegistryFlags.drainGraceMs.key, RegistryFlags.commandTimeoutMs.key),
            snapshot.problems.map { it.key }.toSet(),
            "a budget that degraded silently is a hotkey whose stall nobody can explain",
        )
    }

    /**
     * A slow spanreed's own stderr is the one thing that says why it was slow, and the timeout
     * branch used to replace it with the timeout note. The two failures compound: an operator
     * saw a wrong-looking answer and had nothing to diagnose it from.
     */
    @Test
    fun `a killed child keeps the stderr it managed to write`() {
        val result = bounded(seconds = 15) {
            ProcessCommandRunner(timeoutMs = { 500L }).run(
                listOf("sh", "-c", "echo 'registry is locked, retrying' >&2; sleep 60"),
                emptyMap(),
            )
        }

        assertEquals(ProcessResult.TIMED_OUT, result.exitCode)
        assertTrue(
            result.stderr.contains("registry is locked"),
            "the child's own account of the failure is the only diagnosis there is: " +
                result.stderr,
        )
        assertTrue(result.stderr.contains("timed out"), result.stderr)
    }

    /**
     * The reaping arm, which had no test until the review of #81 pointed out that the comment
     * justifying it named a mechanism that does not exist.
     *
     * The original claim was that coroutine cancellation makes an interrupt out of `waitFor`
     * reachable. It does not — a cancelled job does not interrupt a thread already blocked in a
     * JDK call on `Dispatchers.IO`, which is what `runInterruptible` is for, and probing both
     * shapes gave `interrupted=false, returnedEarly=false` each time. A *genuine* interrupt is
     * still reachable, from `ExecutorService.shutdownNow`, from `runInterruptible`, or from any
     * caller that interrupts directly — and `Dispatchers.IO` pools its threads, so one raised
     * for another task can surface inside this call. This provokes that arm the honest way, by
     * interrupting the thread, and asserts the thing that actually matters: no child is left
     * running behind the exception.
     *
     * `exec` in the script is load-bearing — it makes the sleeping process *be* the direct
     * child rather than a grandchild of it, so the pid recorded is the one `destroyForcibly`
     * is responsible for.
     */
    @Test
    fun `a child is reaped when the call is interrupted`() {
        val pidFile = Files.createTempFile("awakener-reap", ".pid")
        val raised = CompletableFuture<Throwable?>()

        val caller = Thread {
            try {
                ProcessCommandRunner(timeoutMs = { 60_000L }).run(
                    listOf("sh", "-c", "echo $$ > '$pidFile'; exec sleep 30"),
                    emptyMap(),
                )
                raised.complete(null)
            } catch (e: Throwable) {
                raised.complete(e)
            }
        }.apply { isDaemon = true; start() }

        val pid = await(seconds = 15) { pidFile.readText().trim().toLongOrNull() }
        assertTrue(await(seconds = 5) { ProcessHandle.of(pid).orElse(null)?.isAlive })
        // Give the caller time to be inside waitFor rather than on its way there.
        Thread.sleep(250)
        caller.interrupt()

        val thrown = raised.get(15, TimeUnit.SECONDS)
        assertTrue(thrown is InterruptedException, "expected the interrupt to escape, got $thrown")
        assertTrue(
            await(seconds = 10) { ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) },
            "the child outlived the call that started it: pid $pid is still running",
        )
    }

    /** Polls [probe] until it answers with something other than null or false. */
    private fun <T : Any> await(seconds: Long, probe: () -> T?): T {
        val deadline = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < deadline) {
            runCatching(probe).getOrNull()?.let { if (it != false) return it }
            Thread.sleep(50)
        }
        fail("condition never held within ${seconds}s")
    }

    private fun bounded(seconds: Long, body: () -> ProcessResult): ProcessResult {
        val call = CompletableFuture.supplyAsync(body)
        return try {
            call.get(seconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            fail("run() did not return within ${seconds}s: $e")
        }
    }
}
