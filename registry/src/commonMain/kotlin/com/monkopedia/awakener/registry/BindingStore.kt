package com.monkopedia.awakener.registry

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One surface's durable binding.
 *
 * Everything here has to survive a reboot, so nothing in it may reference a live process or a
 * compositor handle. [spanreedName] is stored rather than recomputed so that changing
 * [RegistryFlags.agentNamePrefix] cannot orphan agents that are already carrying residue.
 *
 * The corollary is the cost of that trade, and it is general: **a stored identity is
 * authoritative and is never re-derived.** [agentId] and [spanreedName] are read back as
 * written, and `bind` prefers the stored pair over minting a fresh one — so changing how
 * identities are derived (the name prefix, the id source, [SurfaceKey.slug] itself) applies to
 * what will be minted and never to what has been. Correcting an existing binding means editing
 * or deleting the entry: `awakener-registry forget <key>`, or the file.
 */
@Serializable
data class Binding(
    @SerialName("agent_id") val agentId: String,
    @SerialName("spanreed_name") val spanreedName: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("last_bound_at_ms") val lastBoundAtMs: Long,
) {
    val agent: AgentId get() = AgentId(agentId)

    val identity: AgentIdentity get() = AgentIdentity(agent, spanreedName)
}

/** The on-disk shape. Versioned so a format change can be detected rather than misparsed. */
@Serializable
internal data class BindingsFile(
    val version: Int = CURRENT_VERSION,
    val bindings: Map<String, Binding> = emptyMap(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * The durable half of the memory model.
 *
 * The design brief splits what an agent holds into durable residue — preferences, decisions,
 * learned quirks — and perishable in-flight state that dies with the session. This store owns
 * the durable half: which agent a surface is bound to, and where that agent's written-down
 * residue lives. It does not distil anything; it only guarantees that the same surface finds
 * the same agent and the same residue after a restart.
 */
interface BindingStore {
    /** Live view of every binding, keyed by [SurfaceKey]. Reload republishes it. */
    val bindings: StateFlow<Map<SurfaceKey, Binding>>

    suspend fun resolve(key: SurfaceKey): Binding?

    /**
     * Returns the binding for [key], creating one if this surface has never been bound.
     *
     * @param agent an id to use instead of minting one. Whether it wins over an existing
     * binding is [RegistryFlags.rebindPolicy]'s call, not the caller's — a caller that passes
     * the agent it happens to be holding must not be able to silently strand six weeks of
     * residue.
     */
    suspend fun bind(key: SurfaceKey, agent: AgentIdentity? = null): Binding

    /**
     * Forgets a binding. Returns whether there was one. Does not delete residue.
     *
     * This is the repair path, so it has to hold against a process that is still holding the
     * binding it removed. A durable implementation must not let such a holder write its own
     * view of the file back over this — see [RegistryFlags.storeReload] for how, and
     * [RegistryFlags.forgetConflict] for what a holder's next `bind` then does.
     */
    suspend fun unbind(key: SurfaceKey): Boolean

    /**
     * Where this surface's distilled residue lives.
     *
     * Resolved from the key rather than stored, so it is answerable for a surface that has
     * never been bound — which is what lets a cold spawn load residue before it has an agent.
     */
    fun residueLocation(key: SurfaceKey): String
}
