package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Where the durable layer lives on disk.
 *
 * Bindings and residue are *state*, not configuration: they are written by awakener, they are
 * not hand-authored, and losing them costs the accumulated model rather than a preference. So
 * they default under `XDG_STATE_HOME` while `:config` defaults under `XDG_CONFIG_HOME`.
 */
object RegistryPaths {
    fun stateHome(environment: Map<String, String> = System.getenv()): Path = Path(
        environment["XDG_STATE_HOME"]
            ?: "${System.getProperty("user.home")}/.local/state",
    ).resolve("awakener")

    fun storePath(config: Config, environment: Map<String, String> = System.getenv()): Path =
        config[RegistryFlags.storePath].takeIf { it.isNotBlank() }?.let(::Path)
            ?: stateHome(environment).resolve("bindings.json")

    /**
     * Defaults beside the bindings file rather than to a fixed location, so that pointing the
     * store at a scratch directory moves the whole durable set together — a half-redirected
     * state directory would bind against one file and read residue from another.
     *
     * @param storePath the bindings file actually in use, which is not always
     * [RegistryFlags.storePath]: a store can be constructed over an explicit path.
     */
    fun residueDir(config: Config, storePath: Path): Path =
        config[RegistryFlags.residueDir].takeIf { it.isNotBlank() }?.let(::Path)
            ?: storePath.parent.resolve("residue")

    fun residueLocation(config: Config, storePath: Path, key: SurfaceKey): Path =
        residueDir(config, storePath)
            .resolve(residueLeaf(key, config[RegistryFlags.residueLayout]))
}
