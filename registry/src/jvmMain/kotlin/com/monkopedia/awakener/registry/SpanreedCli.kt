package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The outcome of running a subprocess. */
data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Runs a command. A seam, so tests can assert on the exact argv awakener sends to spanreed
 * without registering throwaway agents on the developer's live bus.
 */
fun interface CommandRunner {
    fun run(command: List<String>, environment: Map<String, String>): ProcessResult
}

/** Runs commands as real subprocesses. */
object ProcessCommandRunner : CommandRunner {
    private const val TIMEOUT_SECONDS = 10L

    override fun run(command: List<String>, environment: Map<String, String>): ProcessResult {
        val process = ProcessBuilder(command)
            .apply { environment().putAll(environment) }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ProcessResult(-1, stdout, "timed out after ${TIMEOUT_SECONDS}s")
        }
        return ProcessResult(process.exitValue(), stdout, stderr)
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
    private val runner: CommandRunner = ProcessCommandRunner,
    private val ownPid: () -> Long = { ProcessHandle.current().pid() },
    private val residuePathFor: (Config, SurfaceKey) -> String = { config, key ->
        RegistryPaths.residueLocation(config, RegistryPaths.storePath(config), key).toString()
    },
) : AgentIdentities {
    private val config: Config get() = configStore.config.value

    /**
     * Mints the identity for a surface.
     *
     * Read the flag snapshot once: minting can span a config reload, and deriving a name under
     * one prefix while asking spanreed for an id under another would produce a binding whose
     * two halves disagree forever.
     */
    override suspend fun mint(key: SurfaceKey): AgentIdentity {
        val cfg = config
        val name = spanreedNameFor(key, cfg[RegistryFlags.agentNamePrefix])
        val id = when (cfg[RegistryFlags.agentIdSource]) {
            AgentIdSource.DERIVED -> AgentId("agent-$name")
            AgentIdSource.SPANREED -> AgentId(agentId(cfg, name))
        }
        val identity = AgentIdentity(id, name)
        if (cfg[RegistryFlags.registerOnMint]) register(key, identity)
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
     * `--working-dir` gets the residue location, which is as close to a working directory as a
     * surface has and makes `spanreed list` point at the written-down model when an agent gets
     * something wrong. Note the known gap: spanreed keys liveness on `pid` + `pid_start`, so an
     * entry registered under awakener's pid outlives the Lifeless it describes — which is why
     * [RegistryFlags.registerOnMint] defaults off.
     */
    suspend fun register(key: SurfaceKey, identity: AgentIdentity): ProcessResult {
        val cfg = config
        return exec(
            cfg,
            listOf(
                "register",
                "--agent-id", identity.id.raw,
                "--name", identity.spanreedName,
                "--working-dir", residuePathFor(cfg, key),
                "--pid", ownPid().toString(),
            ),
            identity.spanreedName,
        )
    }

    /** Every registered agent, as raw JSON. Used to tell a live Lifeless from a remembered one. */
    suspend fun list(): ProcessResult = exec(config, listOf("list"), name = null)

    private suspend fun exec(
        config: Config,
        args: List<String>,
        name: String?,
    ): ProcessResult = withContext(Dispatchers.IO) {
        runner.run(
            listOf(config[RegistryFlags.spanreedCommand]) + args,
            name?.let { mapOf("SPANREED_AGENT_NAME" to it) } ?: emptyMap(),
        )
    }
}
