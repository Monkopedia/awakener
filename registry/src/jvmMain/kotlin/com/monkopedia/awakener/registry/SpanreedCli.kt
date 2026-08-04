package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** spanreed's registry is its shape to grow, so an unknown field must never fail a hotkey. */
private val busJson = Json { ignoreUnknownKeys = true }

/** The outcome of running a subprocess. */
data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Runs a command. A seam, so tests can assert on the exact argv awakener sends to spanreed
 * without registering throwaway agents on the developer's live bus.
 *
 * [environment] is applied *over* the inherited environment, and **a null value removes the
 * variable** rather than setting it empty. Removal is not a convenience: awakener's own process
 * routinely runs with `SPANREED_AGENT_NAME` set — the hotkey path is reached from a session that
 * has one, and `awakener-invoke` is a child of whatever launched it — so a read of the whole bus
 * issued without clearing it would be issued *as* some other agent. Setting it empty is not the
 * same thing either, since spanreed's override is on presence.
 */
fun interface CommandRunner {
    fun run(command: List<String>, environment: Map<String, String?>): ProcessResult
}

/**
 * Runs commands as real subprocesses.
 *
 * Every call here is on the hotkey path — the default [AgentIdSource] shells out to mint an
 * identity, and `attach` awaits it — so the contract is that [run] returns within [timeoutMs]
 * whatever the child does.
 *
 * @param timeoutMs how long the child gets before it is killed. A parameter rather than a
 * constant so a test can prove the timeout fires without spending the production budget waiting.
 */
class ProcessCommandRunner(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : CommandRunner {
    override fun run(command: List<String>, environment: Map<String, String?>): ProcessResult {
        val process = ProcessBuilder(command)
            .apply {
                val inherited = environment()
                environment.forEach { (name, value) ->
                    if (value == null) inherited.remove(name) else inherited[name] = value
                }
            }
            .start()
        // Nothing awakener runs is fed on stdin, and a child that reads it would otherwise wait
        // forever for input that is never coming.
        process.outputStream.close()
        // Both pipes are drained concurrently, and the clock runs while they drain. Reading
        // stdout to EOF first deadlocks the moment the child writes more than a pipe buffer
        // (~64 KiB) to stderr: it blocks on that write, so it never closes stdout, so the read
        // never returns — and a timeout applied *after* the reads can then never fire. A
        // spanreed that logs a stack trace is enough to hit it.
        val stdout = Drain(process.inputStream)
        val stderr = Drain(process.errorStream)
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            // Killing the child closes its pipes, which is what lets the drains finish.
            process.waitFor()
            return ProcessResult(TIMED_OUT, stdout.collect(), "timed out after ${timeoutMs}ms")
        }
        return ProcessResult(process.exitValue(), stdout.collect(), stderr.collect())
    }

    /**
     * Reads one pipe on its own thread into a buffer the caller can take at any point.
     *
     * Partial rather than all-or-nothing on purpose: a child's exit does not close a pipe a
     * grandchild inherited, so the collecting side has to be able to stop waiting and still keep
     * what arrived — an unbounded wait here would put back exactly the unbounded call above.
     */
    private class Drain(stream: InputStream) {
        private val text = StringBuilder()

        private val thread = Thread {
            val reader = stream.bufferedReader()
            val chunk = CharArray(DEFAULT_BUFFER_SIZE)
            runCatching {
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(text) { text.appendRange(chunk, 0, read) }
                }
            }
        }.apply {
            name = "awakener-subprocess-drain"
            isDaemon = true
            start()
        }

        fun collect(): String {
            thread.join(DRAIN_GRACE_MS)
            return synchronized(text) { text.toString() }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DRAIN_GRACE_MS = 1_000L

        /** Distinguishable from any real exit code, which is 0..255. */
        const val TIMED_OUT = -1
    }
}

/**
 * awakener's only route to spanreed.
 *
 * spanreed publishes `register` / `send` / `recv` / `list` / `name` / `focus` / `status` as a
 * versioned contract; `~/.claude/spanreed/registry.json` and its lockfile are internal. Driving
 * the files directly would mean reimplementing spanreed's locking discipline in a second
 * language — duplicating invariants that are not ours to hold — so this shells out even where a
 * file read would be shorter.
 */
class SpanreedCli(
    private val configStore: ConfigStore,
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val ownPid: () -> Long = { ProcessHandle.current().pid() },
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
     * array is either blank or unparseable, and both now raise; what must never happen is the
     * one shape that would parse into a plausible answer, and an empty string papered into `[]`
     * was exactly that shape.
     *
     * That the *runner* cannot say it read short is #51, and it is not fixed here — `agent-id`
     * has the same exposure with worse consequences, since a truncated id is a valid string.
     */
    override suspend fun liveAgents(): List<LiveAgent> {
        val result = withContext(Dispatchers.IO) {
            runner.run(
                listOf(config[RegistryFlags.spanreedCommand], "list"),
                mapOf("SPANREED_AGENT_NAME" to null),
            )
        }
        check(result.succeeded) {
            "spanreed list failed (${result.exitCode}): ${result.stderr.ifBlank { result.stdout }}"
        }
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
            AgentIdSource.DERIVED -> AgentId("agent-$name")
            AgentIdSource.SPANREED -> AgentId(agentId(cfg, name))
        }
        val identity = AgentIdentity(id, name)
        if (cfg[RegistryFlags.registerOnMint]) register(identity, residuePath)
        return identity
    }

    /**
     * Asks spanreed what id it will derive for a session running under [name].
     *
     * The environment variable is the whole mechanism: spanreed's derivation is `sha256(cwd)`,
     * and a surface has no cwd, so `SPANREED_AGENT_NAME` is what gives a Lifeless an identity
     * that is about the surface instead of about wherever awakener happened to be started.
     */
    private suspend fun agentId(config: Config, name: String): String {
        val result = exec(config, listOf("agent-id"), name)
        check(result.succeeded) {
            "spanreed agent-id failed (${result.exitCode}): ${result.stderr.ifBlank { result.stdout }}"
        }
        return result.stdout.trim().ifBlank { error("spanreed agent-id printed nothing") }
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
