package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [BindingStore] held entirely in memory.
 *
 * Bindings that do not survive a restart are, strictly, the thing this module exists to stop
 * happening — so this is for tests and for a run that deliberately wants no durable trace, not
 * a default anyone should reach for.
 */
class InMemoryBindingStore(
    private val configStore: ConfigStore = InMemoryConfigStore(),
    private val identities: AgentIdentities = DerivedAgentIdentities(configStore),
    private val clock: () -> Long = { 0L },
) : BindingStore {
    private val state = MutableStateFlow<Map<SurfaceKey, Binding>>(emptyMap())
    private val lock = Mutex()

    override val bindings: StateFlow<Map<SurfaceKey, Binding>> = state.asStateFlow()

    private val config: Config get() = configStore.config.value

    override suspend fun resolve(key: SurfaceKey): Binding? = state.value[key]

    override suspend fun bind(key: SurfaceKey, agent: AgentIdentity?): Binding = lock.withLock {
        val now = clock()
        val existing = state.value[key]
        val next = existing.merge(agent, config, now) {
            identities.mint(key, residueLocation(key))
        }
        state.value = state.value + (key to next)
        next
    }

    /**
     * [RegistryFlags.forgetResidue] is not consulted, and the outcome is always
     * [ResidueOutcome.Kept].
     *
     * Not an oversight and not a shortcut: this store has no filesystem, and its
     * [residueLocation] is a bare leaf name rather than a path — there is nothing on disk for
     * an archive or a delete to act on. Reporting `Kept` is the honest answer, since nothing
     * was disposed of; a store that claimed `Archived` here would be describing a file it
     * never wrote.
     */
    override suspend fun unbind(key: SurfaceKey): Forget = lock.withLock {
        val had = key in state.value
        state.value = state.value - key
        Forget(had, ResidueOutcome.Kept(residueLocation(key)))
    }

    override fun residueLocation(key: SurfaceKey): String =
        residueLeaf(key, config[RegistryFlags.residueLayout])
}

/**
 * The rebind decision, shared by every store so the flag cannot mean two things.
 *
 * [mint] is a lambda rather than a value because minting through the spanreed CLI costs a
 * subprocess, and the overwhelmingly common case — a surface that is already bound — must not
 * pay it.
 */
internal suspend inline fun Binding?.merge(
    supplied: AgentIdentity?,
    config: Config,
    now: Long,
    mint: () -> AgentIdentity,
): Binding {
    if (this != null) {
        val replacement = supplied?.takeIf {
            config[RegistryFlags.rebindPolicy] == RebindPolicy.REPLACE && it.id != agent
        }
        return if (replacement == null) {
            copy(lastBoundAtMs = now)
        } else {
            Binding(replacement.id.raw, replacement.spanreedName, now, now)
        }
    }
    val identity = supplied ?: mint()
    return Binding(identity.id.raw, identity.spanreedName, now, now)
}

/**
 * The residue path relative to the residue directory, per [RegistryFlags.residueLayout], as its
 * components.
 *
 * A list rather than a joined string because [ResidueLayout.PER_APP_SESSION_DIR] has two
 * components and the guard that keeps them inside the residue directory has to see them
 * separately: a single `"$app/$session"` handed to `Path.resolve` is a string in which an `app`
 * of `../..` is indistinguishable from nesting, which is precisely the confusion the guard
 * exists to refuse.
 *
 * Every component here is a slug — see [SurfaceKey.appSlug] — so none of them can contain a
 * separator, be `.` or `..`, or be absolute, whatever the window called itself.
 */
internal fun residueSegments(key: SurfaceKey, layout: ResidueLayout): List<String> = when (layout) {
    ResidueLayout.PER_KEY_FILE -> listOf("${key.slug}.md")
    ResidueLayout.PER_KEY_DIR -> listOf(key.slug)
    ResidueLayout.PER_APP_SESSION_DIR -> listOf(key.appSlug, key.slug)
}

/** [residueSegments] as one relative path, for a store with no filesystem under it. */
internal fun residueLeaf(key: SurfaceKey, layout: ResidueLayout): String =
    residueSegments(key, layout).joinToString("/")

/**
 * Where [ForgetResidue.ARCHIVE] moves that residue to.
 *
 * Beside [residueLeaf] rather than derived from it by string surgery, so the two cannot drift:
 * an archive name built by chopping `.md` off the live name would silently become a directory
 * name the day a third layout arrives. [stamp] carries the timestamp and, where one archive
 * already holds that second, a counter — the name is what a human reads to tell two archives
 * of one surface apart, so it stays readable rather than becoming an opaque id.
 */
internal fun archiveLeaf(key: SurfaceKey, layout: ResidueLayout, stamp: String): String =
    when (layout) {
        ResidueLayout.PER_KEY_FILE -> "${key.slug}.$stamp.md"
        ResidueLayout.PER_KEY_DIR -> "${key.slug}.$stamp"
        // A leaf, not a path: the caller resolves it as a *sibling* of the live residue, so
        // under this layout the archive stays inside the application's own directory rather
        // than being flattened back up beside every other app's.
        ResidueLayout.PER_APP_SESSION_DIR -> "${key.slug}.$stamp"
    }
