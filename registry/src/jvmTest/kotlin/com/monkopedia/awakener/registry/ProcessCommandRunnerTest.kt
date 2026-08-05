package com.monkopedia.awakener.registry

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
            ProcessCommandRunner(timeoutMs = 300).run(listOf("sh", "-c", "sleep 60"), emptyMap())
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
                // The second half arrives after DRAIN_GRACE_MS, from a background job the child
                // leaves holding its stdout — so the child exits 0 with its output in flight.
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

    /**
     * A slow spanreed's own stderr is the one thing that says why it was slow, and the timeout
     * branch used to replace it with the timeout note. The two failures compound: an operator
     * saw a wrong-looking answer and had nothing to diagnose it from.
     */
    @Test
    fun `a killed child keeps the stderr it managed to write`() {
        val result = bounded(seconds = 15) {
            ProcessCommandRunner(timeoutMs = 500).run(
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
                ProcessCommandRunner(timeoutMs = 60_000).run(
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
