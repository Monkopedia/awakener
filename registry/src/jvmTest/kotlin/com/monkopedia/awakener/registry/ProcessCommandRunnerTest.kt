package com.monkopedia.awakener.registry

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun bounded(seconds: Long, body: () -> ProcessResult): ProcessResult {
        val call = CompletableFuture.supplyAsync(body)
        return try {
            call.get(seconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            fail("run() did not return within ${seconds}s: $e")
        }
    }
}
