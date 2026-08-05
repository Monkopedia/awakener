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
            ?: storePath.holder().resolve("residue")

    /**
     * The directory [this] sits in, for a path that may be relative.
     *
     * `Path.getParent` answers null for a bare filename — `registry.store.path: "bindings.json"`
     * is a perfectly reasonable thing to type — and Kotlin sees `Path!` back from the Java API,
     * so nothing at the call site was nullable and the NPE landed on the bind path, one module
     * and several calls away from the line that was edited. Resolving against the working
     * directory first is also what the value meant: a relative bindings file is relative to
     * somewhere, and that somewhere is where its residue belongs too.
     *
     * A path with no parent even when absolute is a filesystem root, which is not a bindings
     * file at all; it keeps itself, so the failure is a report about a directory rather than a
     * throw about a null.
     */
    private fun Path.holder(): Path = toAbsolutePath().let { it.parent ?: it }

    fun residueLocation(config: Config, storePath: Path, key: SurfaceKey): Path =
        residueDir(config, storePath)
            .resolve(residueLeaf(key, config[RegistryFlags.residueLayout]))
}
