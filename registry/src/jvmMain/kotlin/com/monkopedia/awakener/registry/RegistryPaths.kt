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
        containedResolve(
            residueDir(config, storePath),
            residueSegments(key, config[RegistryFlags.residueLayout]),
        )

    /**
     * Resolves [segments] under [base] one component at a time, refusing any that leaves it.
     *
     * The second of two guards, and it exists because the first one is unfalsifiable on its own.
     * [SurfaceKey.appSlug] makes an escaping component unconstructible, which is the right
     * primary defence and is also the kind of defence that quietly stops holding the day someone
     * adds a layout whose component is not a slug. This one is about the property itself — no
     * residue path leaves the residue directory — so it can be handed a hostile component
     * directly and made to fail, which is what a test needs in order to prove it fires.
     *
     * Component at a time rather than `base.resolve(segments.joinToString("/"))`, because the
     * joined form cannot distinguish nesting from traversal, and rather than one `normalize` at
     * the end, because `a/../b` normalises back inside while having named `a`'s parent on the way.
     *
     * The returned path keeps [base]'s own shape — relative if it was relative — since callers
     * print it and one test pins a relative `registry.store.path`. Only the containment
     * arithmetic is done on the absolute, normalised form.
     */
    internal fun containedResolve(base: Path, segments: List<String>): Path {
        val root = base.toAbsolutePath().normalize()
        var probe = root
        var at = base
        segments.forEach { segment ->
            // `Path.resolve` raises InvalidPathException — an IllegalArgumentException — on a NUL,
            // and returns the argument itself when it is absolute; both end up refused here.
            val before = probe
            probe = probe.resolve(segment).normalize()
            // One condition rather than a list of hazards, and it is the stronger claim: each
            // component must name a child of the directory the previous one named. `..` and an
            // absolute path fail it by landing elsewhere, `""` by landing on `before` itself, and
            // `a/b` by landing two levels down — which is not an escape but is a component
            // inventing a nesting level the layout did not ask for. Containment follows by
            // induction from the first component, so it does not need asserting separately.
            require(probe.parent == before) {
                "residue component '$segment' is not a single name under $before " +
                    "(resolved to $probe, root $root)"
            }
            at = at.resolve(segment)
        }
        return at
    }
}
