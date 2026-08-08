package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** spanreed's registry is its shape to grow, so an unknown field must never fail a hotkey. */
private val busJson = Json { ignoreUnknownKeys = true }

/**
 * The outcome of running a subprocess.
 *
 * ### Why [shortRead] exists
 *
 * A child's exit does not close a pipe a grandchild inherited, so the collecting side has to be
 * able to stop waiting and keep what arrived — see the drain inside [ProcessCommandRunner]. That
 * leaves a result whose [stdout] is a *prefix* of what the child meant to say, on a run that
 * exited 0.
 * Until this field existed there was no way to tell the two apart, and the consequence was not
 * cosmetic: `spanreed agent-id` prints one line, and a prefix of an agent id is itself a
 * perfectly valid agent id. A surface bound under one addresses somebody else's Lifeless — and
 * with it the accumulated model of the user that agent holds. Truncation of a JSON array is
 * caught by the decoder; truncation of an identifier is caught by nothing at all (#51).
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /**
     * Why [stdout] may be only part of what the child wrote, or null when it is all of it.
     *
     * About stdout alone, because stdout is the *answer* and stderr is the explanation: a
     * diagnostic that arrived half-written is still a diagnostic, and failing an otherwise good
     * run over one would take the hotkey down for a reason that has nothing to do with the
     * result. A short read on stderr is noted inside [stderr] instead.
     *
     * **A positive signal, not a total one, and callers must not treat null as proof.** The JVM
     * drains whatever is available on a child's pipe the moment it exits and substitutes a
     * buffer for the pipe itself, so a reader that had not yet blocked when that happened gets
     * a *clean EOF over a truncated read* — indistinguishable from a whole one at this level.
     * Measured at roughly one run in twenty-five on kaladin against a child leaving a background
     * job holding its stdout; the other twenty-four are caught. Anything whose correctness turns
     * on completeness therefore needs its own framing check as well, which is why
     * [SpanreedCli] requires an `agent-id` answer to carry the line terminator a whole line has.
     *
     * That quantification covers **one** path to the residual and there used to be a second: the
     * drain read its completion flag *after* it had snapshotted the buffer, so a drain that
     * appended its last chunk in the gap between the two reported a prefix with nothing saying
     * so. That one is closed — the buffer and the flags are now one observation under one
     * monitor (#110) — so the JVM's pipe substitution is again the whole of what this field
     * cannot see.
     */
    val shortRead: String? = null,
) {
    /** Ran to completion, exited zero, and [stdout] is the whole of what it wrote. */
    val succeeded: Boolean get() = exitCode == 0 && shortRead == null

    /**
     * Nothing ran at all — the command named by `registry.agent.spanreed_command` is not there.
     *
     * Worth distinguishing from every other failure because it is the one that says nothing
     * about the bus: a spanreed that ran and exited non-zero is a bus that is present with
     * something else wrong. See [RegistryFlags.idSourceUnreachable], which is scoped to this.
     */
    val notRunnable: Boolean get() = exitCode == NOT_RUNNABLE

    /**
     * Why this run cannot be believed, phrased to follow the name of what was run, or null when
     * it can. Kept here rather than at each call site so a caller cannot report a short read as
     * a clean exit simply by writing the obvious message.
     */
    val problem: String? get() = when {
        shortRead != null -> "$shortRead (exit $exitCode): ${detail()}"
        exitCode != 0 -> "exited $exitCode: ${detail()}"
        else -> null
    }

    private fun detail(): String =
        stderr.ifBlank { stdout }.trim().ifBlank { "and said nothing about why" }

    companion object {
        /** Distinguishable from any real exit code, which is 0..255. */
        const val TIMED_OUT = -1

        /** Likewise, and distinct from [TIMED_OUT]: nothing ran, so nothing timed out. */
        const val NOT_RUNNABLE = -2
    }
}

/**
 * Runs a command. A seam, so tests can assert on the exact argv awakener sends to spanreed
 * without registering throwaway agents on the developer's live bus.
 *
 * [environment] is applied *over* the inherited environment, and **a null value removes the
 * variable** rather than setting it empty. Removal is not a convenience: awakener's own process
 * routinely runs with `SPANREED_AGENT_NAME` set — the hotkey path is reached from a session that
 * has one, and `awakener-invoke` is a child of whatever launched it — so a read of the whole bus
 * issued without clearing it would be issued *as* some other agent.
 *
 * Setting it empty would reach spanreed the same way — `identity.py` tests the override with
 * `if override:`, so an empty value and an absent variable take the same branch and spanreed
 * cannot tell them apart. (Measured, because this doc claimed the opposite until #109: with
 * `SPANREED_AGENT_NAME=""` and with it unset, `spanreed agent-id` printed the same cwd-derived
 * `agent-0474e11f`.) The reason to remove rather than blank it is about everything *else* in the
 * child's environment: an empty name is a lie any other reader of that variable would believe,
 * and removal is what "as nobody in particular" actually means. Weaker than the claim it
 * replaces, and enough to keep the `String?`.
 */
fun interface CommandRunner {
    fun run(command: List<String>, environment: Map<String, String?>): ProcessResult
}

/**
 * Runs commands as real subprocesses.
 *
 * Every call here is on the hotkey path — the default [AgentIdSource] shells out to mint an
 * identity, and `attach` awaits it — so the contract is that [run] returns within [timeoutMs]
 * (plus at most one [drainGraceMs] per pipe) whatever the child does, and returns at all whether
 * or not there is a child to run.
 *
 * Both budgets are suppliers rather than numbers, and both are read once per [run]. That is what
 * lets the flags behind them be read from the live snapshot instead of captured when the runner
 * was built: an operation gets one consistent pair, and the next operation gets whatever the
 * config file says by then.
 *
 * @param timeoutMs how long the child gets before it is killed.
 * @param drainGraceMs how long a finished child's pipes are still read before the runner keeps
 * what arrived and calls the read short.
 */
class ProcessCommandRunner(
    private val timeoutMs: () -> Long = { RegistryFlags.commandTimeoutMs.default },
    private val drainGraceMs: () -> Long = { RegistryFlags.drainGraceMs.default },
) : CommandRunner {
    /**
     * The production wiring: both budgets come off the config snapshot at the moment of the call.
     *
     * Not read at construction, because a value captured then is a value a live edit cannot
     * reach — and these two are exactly the numbers somebody tunes against a running desktop
     * when a hotkey feels slow.
     */
    constructor(configStore: ConfigStore) : this(
        timeoutMs = { configStore.config.value[RegistryFlags.commandTimeoutMs] },
        drainGraceMs = { configStore.config.value[RegistryFlags.drainGraceMs] },
    )

    override fun run(command: List<String>, environment: Map<String, String?>): ProcessResult {
        val builder = ProcessBuilder(command)
            .apply {
                val inherited = environment()
                environment.forEach { (name, value) ->
                    if (value == null) inherited.remove(name) else inherited[name] = value
                }
            }
        // A command that does not exist is a config value, not a defect: the executable comes
        // from registry.agent.spanreed_command, which is hand-edited against a running desktop,
        // and a typo in it used to leave `start` throwing an IOException that nothing on the
        // hotkey path caught. Answering it as a failed run puts it where every other way this
        // can go wrong already goes, and where the caller's own message can name the flag —
        // rather than a stack trace from a line that mentions neither.
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return ProcessResult(
                ProcessResult.NOT_RUNNABLE,
                "",
                "could not run '${command.firstOrNull().orEmpty()}' " +
                    "(${RegistryFlags.spanreedCommand.key}): ${e.message}",
            )
        }
        // Every exit from here on has to go past the child: leaving without reaping abandons a
        // running process holding the pipes it inherited.
        //
        // **Not because of coroutine cancellation** — that was this comment's first claim and it
        // is false. Cancelling a job does *not* interrupt a thread already blocked in a JDK call
        // on `Dispatchers.IO`; `runInterruptible` exists precisely because it does not. Probed
        // both shapes rather than reasoning about it: a cancelled `Thread.sleep` and a cancelled
        // `Process.waitFor` each reported `interrupted=false, returnedEarly=false`. The claim was
        // inherited from #51's text, where it is also wrong, and a note there says so.
        //
        // What can actually leave by this path, and is why the guard stays: `outputStream.close()`
        // throwing `IOException`; either `Thread.start()` in a `Drain` failing under resource
        // pressure, which strands an already-started child and possibly a first drain; and
        // `waitFor` throwing `InterruptedException` when the thread is *genuinely* interrupted —
        // by `ExecutorService.shutdownNow`, by `runInterruptible`, or by any caller that
        // interrupts directly. `Dispatchers.IO` threads are pooled and outlive a single call, so
        // an interrupt raised for one task can surface inside another's. `a child is reaped when
        // the call is interrupted` covers that arm.
        return try {
            drainAndWait(process)
        } catch (e: Throwable) {
            process.destroyForcibly()
            throw e
        }
    }

    private fun drainAndWait(process: Process): ProcessResult {
        // Both budgets read once, here, so the whole of one run is measured against one pair.
        // Re-reading per drain would let a config reload land between the two pipes of a single
        // command and give stdout and stderr different grace periods.
        val timeout = timeoutMs()
        val grace = drainGraceMs()
        // Nothing awakener runs is fed on stdin, and a child that reads it would otherwise wait
        // forever for input that is never coming.
        process.outputStream.close()
        // Both pipes are drained concurrently, and the clock runs while they drain. Reading
        // stdout to EOF first deadlocks the moment the child writes more than a pipe buffer
        // (~64 KiB) to stderr: it blocks on that write, so it never closes stdout, so the read
        // never returns — and a timeout applied *after* the reads can then never fire. A
        // spanreed that logs a stack trace is enough to hit it.
        val stdout = Drain(process.inputStream, grace)
        val stderr = Drain(process.errorStream, grace)
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            // Killing the child closes its pipes, which is what lets the drains finish.
            process.waitFor()
            val out = stdout.collect()
            // The child's own stderr is kept rather than replaced by the timeout note. It is the
            // one piece of evidence that says *why* a spanreed was slow, and discarding it left
            // an operator with a wrong-looking answer and nothing to diagnose it from.
            return ProcessResult(
                ProcessResult.TIMED_OUT,
                out.text,
                listOf(stderr.collect().noted(), "timed out after ${timeout}ms")
                    .filter { it.isNotEmpty() }
                    .joinToString("\n"),
                shortRead = out.shortBy,
            )
        }
        val out = stdout.collect()
        return ProcessResult(
            process.exitValue(),
            out.text,
            stderr.collect().noted(),
            shortRead = out.shortBy,
        )
    }

    /**
     * What one pipe yielded, and — [shortBy] — why that may not be the whole of it.
     *
     * `internal` for the same reason as [Drain]: it is that class's answer.
     */
    internal class Read(val text: String, val shortBy: String?) {
        /**
         * The text with the reason folded in, for a pipe whose completeness is not modelled.
         * Only stderr is read this way; see [ProcessResult.shortRead].
         */
        fun noted(): String = when {
            shortBy == null -> text
            text.isEmpty() -> "[$shortBy]"
            else -> "$text\n[$shortBy]"
        }
    }

    /**
     * Reads one pipe on its own thread into a buffer the caller can take at any point.
     *
     * Partial rather than all-or-nothing on purpose: a child's exit does not close a pipe a
     * grandchild inherited, so the collecting side has to be able to stop waiting and still keep
     * what arrived — an unbounded wait here would put back exactly the unbounded call above.
     *
     * What it must not do is stay quiet about having stopped early, which is what it did until
     * #51: the join returned whatever had arrived, and a `runCatching {}` around the read loop
     * made a mid-read `IOException` look identical to a clean EOF. Both now come back as a
     * reason on [Read.shortBy], and it is the caller's to decide about.
     *
     * **How much arrived and whether that was all of it are one fact, so they are read as one**
     * (#110). They were two: the buffer was snapshotted under [text]'s monitor and [atEnd] was
     * read afterwards, having been written outside it — so a drain that appended its final chunk
     * in the gap between the two published a *prefix* alongside a flag saying the read was
     * whole, which is precisely the outcome the signal exists to prevent. The window was tens of
     * nanoseconds wide and no test could hit it; widening it artificially is how it was
     * demonstrated, and the fix is structural rather than a re-ordering: every write of a flag
     * happens under the same monitor as the appends, and [collect] takes all three in one block.
     *
     * **`internal` rather than `private`, as a test seam and nothing else.** The [failure] arm
     * cannot be reached on demand through [run]: making a real pipe fail mid-read is not
     * something to fake, so that branch landed with #110 untested and disclosed as such. This
     * class reads whatever [InputStream] it is handed, so a stream that throws after *n* bytes
     * reaches it in one line of test code — `a stream that fails mid-read yields the prefix and
     * a reason`. `internal` does not cross a module boundary, so no caller of this package gains
     * anything; only `:registry`'s own tests do.
     */
    internal class Drain(stream: InputStream, private val graceMs: Long) {
        private val text = StringBuilder()

        /**
         * Set once the read loop has seen EOF, **under [text]'s monitor**, in the same
         * critical section discipline as the appends.
         *
         * Deliberately not `@Volatile` any more. Volatile would make an unsynchronised read
         * *visible* and still leave it a different observation from the buffer's, which is the
         * bug — a sound read of a second, later fact is still not the fact that was read first.
         *
         * **Nothing enforces the monitor.** This is an ordinary `private var`: an `if (atEnd)`
         * outside a `synchronized(text)` block compiles, and without `@Volatile` such a read is
         * now a data race rather than a merely stale one, so dropping volatile makes an
         * accidental unsynchronised read *worse* rather than impossible. Holding the two under
         * one monitor is a convention this class keeps and the compiler does not check — which
         * is why the reason is written here rather than left to be inferred from the code.
         */
        private var atEnd = false

        /** Why the read stopped before EOF on its own, when it did. Guarded by [text] too. */
        private var failure: String? = null

        private val thread = Thread {
            val reader = stream.bufferedReader()
            val chunk = CharArray(DEFAULT_BUFFER_SIZE)
            try {
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) {
                        synchronized(text) { atEnd = true }
                        break
                    }
                    synchronized(text) { text.appendRange(chunk, 0, read) }
                }
            } catch (e: Throwable) {
                // Throwable rather than IOException: whatever ends this loop early, the buffer
                // below is a prefix, and a prefix reported as whole is the defect.
                synchronized(text) { failure = "the pipe failed mid-read ($e)" }
            }
        }.apply {
            name = "awakener-subprocess-drain"
            isDaemon = true
            start()
        }

        fun collect(): Read {
            thread.join(graceMs)
            // One observation. The drain thread cannot append between the buffer and the verdict
            // on it, because it needs this monitor to do either — which is the whole of #110.
            return synchronized(text) {
                Read(
                    text.toString(),
                    when {
                        atEnd -> null
                        failure != null -> failure
                        else -> "the output was cut off: still unread ${graceMs}ms after " +
                            "the child was done, so something it left behind still holds the pipe"
                    },
                )
            }
        }
    }
}

/**
 * awakener's only route to spanreed.
 *
 * spanreed's CLI is its versioned contract and `~/.claude/spanreed/registry.json` and its
 * lockfile are internal. Driving the files directly would mean reimplementing spanreed's locking
 * discipline in a second language — duplicating invariants that are not ours to hold — so this
 * shells out even where a file read would be shorter.
 *
 * The published set is whatever `spanreed --help` prints; as of 2026-08-07 that is `agent-id`,
 * `inbox-path`, `register`, `deregister`, `list`, `send`, `recv`, `inbox-watch`,
 * `session-start`, `name`, `focus`, `status`, `status-tracking`, `activity-log`, `log`,
 * `conjoin`. **awakener calls two of them**: `agent-id`, from [spanreedId], which is the primary
 * mint path, and `list`, from [liveAgents]; [register] adds a third under
 * [RegistryFlags.registerOnMint], which is off by default. Naming what awakener uses rather than
 * an abridged copy of the contract is the point — the abridged copy this replaces omitted
 * `agent-id`, so an audit of "does awakener stay inside the published contract?" read the list
 * beside the code and found this class's main call unaccounted for (#109).
 */
class SpanreedCli(
    private val configStore: ConfigStore,
    private val runner: CommandRunner = ProcessCommandRunner(configStore),
    private val ownPid: () -> Long = { ProcessHandle.current().pid() },
    /**
     * Where a substitution this class made on its own is reported.
     *
     * There is exactly one — [RegistryFlags.idSourceUnreachable] set to
     * [UnreachableIdSource.DERIVE] — and it needs a channel because a quiet substitution of one
     * id source for another is the failure #62 was about: the flag says `SPANREED` and the id
     * did not come from spanreed. stderr by default because every entry point's *stdout* is its
     * answer, and a warning printed into it is a line something downstream will try to parse.
     */
    private val warn: (String) -> Unit = System.err::println,
) : AgentIdentities, AgentBus {
    private val config: Config get() = configStore.config.value

    /**
     * Every agent spanreed currently considers live.
     *
     * Run under **no** `SPANREED_AGENT_NAME` at all, which is the one interaction here that is
     * not about an identity: `list` is a read of the whole registry, and awakener asking it while
     * wearing a name would be asking as somebody. Every other call in this class sets the name
     * because the name is the whole point of the call; this one clears it for the same reason.
     * The clearing is real removal rather than an empty value — see [CommandRunner] — because
     * awakener's own process very often has one set, inherited from the session that launched it.
     *
     * A failure is raised rather than answered as "nobody is animated": the caller's next act on
     * a false empty list is to stand a second panel up beside one that is already there. That is
     * why silence is refused too. A zero exit is not enough on its own — [ProcessCommandRunner]
     * stops waiting on the drain after a grace period and returns what arrived, so a child that
     * exits 0 while its output is still in flight yields a short read. A short read of a JSON
     * array is blank, or unparseable, or short by nothing but trailing whitespace — the first
     * two raise and the third decodes to the same answer the whole document carries, per the
     * identity property below. What must never happen is the remaining shape, one that parses
     * into a *plausible* answer that is not the one spanreed sent, and an empty string papered
     * into `[]` was exactly that shape.
     *
     * Since #51 the runner says so itself: [ProcessResult.succeeded] is false for a run whose
     * stdout was cut off, whatever it exited with, so the check below now covers the shape it
     * previously only happened to catch through the decoder. The decoder is still the second
     * line and still worth keeping — the two guards fail on different things, and only the
     * decoder covers the residual [ProcessResult.shortRead] documents.
     *
     * **What the decoder rests on is value identity, not parseability.** In a document that is
     * one JSON array plus whitespace, the top-level `]` is the last non-whitespace character, so
     * a proper prefix that parses at all must contain it, and therefore differs from the whole
     * document only by trailing whitespace: **every parseable proper prefix of a JSON array
     * decodes to the same array.** A `list` cut off mid-answer is accordingly either a parse
     * failure or the identical answer. What it cannot be is a *different, plausible* answer —
     * which is the only outcome this second line exists to prevent.
     *
     * Two narrower claims stood here before, and both were false the same way, which is why the
     * property is stated as identity rather than as a rule about which documents parse:
     *  - "a JSON array has no proper prefix that parses" — `[]` is a proper prefix of `[]\n`.
     *  - "no proper prefix of a **non-empty** array parses" — the same construction one step
     *    along. `spanreed list` really does emit a trailing newline (measured against a scratch
     *    `SPANREED_STATE_ROOT` on 2026-08-07: `spanreed list | od -c` is `[ ] \n` on an empty
     *    bus and ends `] \n` on a bus with an agent on it), so the canonical text of a
     *    one-element answer is a proper prefix of the document that arrives, and it parses.
     *
     * Emptiness was never the distinction between the prefixes that are safe and the ones that
     * are not; trailing whitespace was, and identity is the property that survives it. Both
     * halves are pinned by `every parseable proper prefix of a list decodes to the same list`.
     *
     * That is why no framing check is needed here and one is needed for `agent-id`, whose
     * answer's every prefix is a valid — and a *different* — answer (#110).
     */
    override suspend fun liveAgents(): List<LiveAgent> {
        val result = withContext(Dispatchers.IO) {
            runner.run(
                listOf(config[RegistryFlags.spanreedCommand], "list"),
                mapOf("SPANREED_AGENT_NAME" to null),
            )
        }
        check(result.succeeded) { "spanreed list failed — ${result.problem}" }
        val listing = result.stdout.ifBlank {
            error("spanreed list exited 0 but printed nothing; refusing to read that as an empty bus")
        }
        // Lenient about *fields*: this list is spanreed's shape, not awakener's, and one added
        // there must not stop a hotkey working. Not lenient about the document, which is what
        // distinguishes an empty bus from an unread one.
        return busJson.decodeFromString(listing)
    }

    /**
     * Mints the identity for a surface.
     *
     * Read the flag snapshot once: minting can span a config reload, and deriving a name under
     * one prefix while asking spanreed for an id under another would produce a binding whose
     * two halves disagree forever.
     */
    override suspend fun mint(key: SurfaceKey, residuePath: String): AgentIdentity {
        val cfg = config
        val name = spanreedNameFor(key, cfg[RegistryFlags.agentNamePrefix])
        val id = when (cfg[RegistryFlags.agentIdSource]) {
            AgentIdSource.DERIVED -> derivedId(name)
            AgentIdSource.SPANREED -> spanreedId(cfg, name)
        }
        val identity = AgentIdentity(id, name)
        if (cfg[RegistryFlags.registerOnMint]) register(identity, residuePath)
        return identity
    }

    /**
     * spanreed's documented `SPANREED_AGENT_NAME` override rule, applied without a subprocess.
     *
     * Shared by [AgentIdSource.DERIVED] and by the unreachable fallback so the two cannot drift
     * apart: an id that depends on which way a surface arrived at the rule is not an identity.
     */
    private fun derivedId(name: String) = AgentId("agent-$name")

    /**
     * Asks spanreed what id it will derive for a session running under [name].
     *
     * The environment variable is the whole mechanism: spanreed's derivation is `sha256(cwd)`,
     * and a surface has no cwd, so `SPANREED_AGENT_NAME` is what gives a Lifeless an identity
     * that is about the surface instead of about wherever awakener happened to be started.
     *
     * Nothing short of a whole, zero-exit run yields an id. That is [ProcessResult.succeeded]'s
     * job now, and the reason it is worth stating: a *prefix* of an agent id is a valid agent
     * id. It routes — to some other surface's Lifeless, or to nothing — and no caller of this
     * method, and no row in the bindings file, can tell it from the real one afterwards. The
     * whole point of the registry is that a surface gets its own accumulated model back.
     */
    private suspend fun spanreedId(config: Config, name: String): AgentId {
        val result = exec(config, listOf("agent-id"), name)
        // Scoped to a command that could not be run at all, deliberately, and not to any failed
        // run: a spanreed that ran and exited non-zero is a bus that is present with something
        // else wrong, and deriving past that would hide it. A short read is likewise not
        // covered — that is a spanreed that answered, and the answer is the thing in doubt.
        if (result.notRunnable &&
            config[RegistryFlags.idSourceUnreachable] == UnreachableIdSource.DERIVE
        ) {
            warn(
                "warning: ${result.stderr.trim()}; deriving the id locally per " +
                    "${RegistryFlags.idSourceUnreachable.key}=${UnreachableIdSource.DERIVE}",
            )
            return derivedId(name)
        }
        check(result.succeeded) { "spanreed agent-id failed — ${result.problem}" }
        // The framing check, and the one guard here that does not depend on the plumbing having
        // noticed. `ProcessResult.shortRead` is a positive signal rather than a total one — see
        // its documentation for the JVM behaviour that makes a truncated read occasionally
        // arrive as a clean EOF — and this is the identity path, where "occasionally" is not
        // good enough. A whole line from a line-oriented CLI carries its terminator; a prefix of
        // one does not, which is precisely the distinction the id itself cannot make.
        //
        // The cost is a dependency on spanreed printing that newline, which it does and which
        // `the real spanreed derives agent-name from SPANREED_AGENT_NAME` exercises on every CI
        // run. If it ever stops, this fails loudly and says why — the outcome to prefer over a
        // silently accepted prefix by some distance.
        check(result.stdout.endsWith("\n")) {
            "spanreed agent-id printed '${result.stdout}' with no line terminator, so what " +
                "arrived is a prefix of an id and not an id — refusing to bind a surface to it"
        }
        return AgentId(result.stdout.trim().ifBlank { error("spanreed agent-id printed nothing") })
    }

    /**
     * Mirrors a Lifeless into spanreed's registry.
     *
     * `--working-dir` gets [residuePath], which is as close to a working directory as a surface
     * has and makes `spanreed list` point at the written-down model when an agent gets something
     * wrong. It is passed in rather than recomputed from the flags because the store is the
     * authority on where its own residue lives — a store opened over an explicit path would
     * otherwise be registered against a location nothing ever writes to. Note the known gap:
     * spanreed keys liveness on `pid` + `pid_start`, so an entry registered under awakener's pid
     * outlives the Lifeless it describes — which is why [RegistryFlags.registerOnMint] defaults
     * off.
     */
    suspend fun register(identity: AgentIdentity, residuePath: String): ProcessResult = exec(
        config,
        listOf(
            "register",
            "--agent-id", identity.id.raw,
            "--name", identity.spanreedName,
            "--working-dir", residuePath,
            "--pid", ownPid().toString(),
        ),
        identity.spanreedName,
    )

    private suspend fun exec(
        config: Config,
        args: List<String>,
        name: String,
    ): ProcessResult = withContext(Dispatchers.IO) {
        runner.run(
            listOf(config[RegistryFlags.spanreedCommand]) + args,
            mapOf("SPANREED_AGENT_NAME" to name),
        )
    }
}
