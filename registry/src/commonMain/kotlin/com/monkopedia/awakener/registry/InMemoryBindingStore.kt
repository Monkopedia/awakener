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

    override suspend fun unbind(key: SurfaceKey): Boolean = lock.withLock {
        val had = key in state.value
        state.value = state.value - key
        had
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

/** The residue path relative to the residue directory, per [RegistryFlags.residueLayout]. */
internal fun residueLeaf(key: SurfaceKey, layout: ResidueLayout): String = when (layout) {
    ResidueLayout.PER_KEY_FILE -> "${key.slug}.md"
    ResidueLayout.PER_KEY_DIR -> key.slug
}
