package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore

/** A spanreed address. Messages are routed by this and nothing else. */
@JvmInline
value class AgentId(val raw: String)

/**
 * Everything needed to bring a Lifeless up as itself.
 *
 * spanreed derives an agent's id from its session's working directory, and a surface has no
 * working directory — a Chrome origin is not a place on disk. So a Lifeless is launched with
 * [spanreedName] in `SPANREED_AGENT_NAME`, which spanreed documents as overriding the
 * derivation to `agent-<name>`. Carrying the name alongside the id (rather than recomputing it)
 * is what keeps the pair consistent if the naming rule is ever changed underneath an existing
 * bindings file.
 */
data class AgentIdentity(val id: AgentId, val spanreedName: String)

/**
 * The identity that would produce this id.
 *
 * The inversion is exact rather than a guess: spanreed documents the override as
 * `agent_id = "agent-<name>"`, so stripping the prefix recovers the `SPANREED_AGENT_NAME` a
 * session must run under to come out as this agent — even for an id that was originally derived
 * from a working directory. Used when a caller hands in an agent it already holds and only the
 * id is known.
 *
 * Exact for every id awakener can mint, and there is one input it does not invert: the literal
 * `"agent-"`, which strips to `""`. spanreed's override test is `if override:` rather than a
 * presence check, so a session launched with `SPANREED_AGENT_NAME=""` derives its id from the
 * cwd and does not come out as `agent-`. Unreachable here — [spanreedNameFor] is a prefix plus a
 * non-empty slug — and named because the same misreading of that `if` was stated as fact one
 * file over (#109).
 */
fun AgentId.asIdentity(): AgentIdentity = AgentIdentity(this, raw.removePrefix("agent-"))

/** Mints the durable identity for a surface that has never been bound before. */
fun interface AgentIdentities {
    /**
     * @param residuePath where this surface's residue will live, supplied by the store rather
     * than recomputed, because the store is the authority on that — one opened over an explicit
     * path does not agree with what the flags alone would say.
     */
    suspend fun mint(key: SurfaceKey, residuePath: String): AgentIdentity

    /**
     * Takes down whatever [mint] put anywhere other than the bindings file, once the binding
     * that [identity] belongs to has been forgotten (#188).
     *
     * A default rather than an abstract member because only a minter that publishes anything has
     * anything to retire: [DerivedAgentIdentities] writes nowhere, and neither does a test's.
     */
    suspend fun retire(identity: AgentIdentity): BusOutcome = BusOutcome.NotAsked
}

/**
 * The `SPANREED_AGENT_NAME` for a surface.
 *
 * Derived purely from the key, so it is reproducible from the bindings file alone and stable
 * for as long as the key is — which is precisely the property that makes a restart cheap.
 *
 * **This string is published.** spanreed lists it to every agent on the host, so what [label]
 * lets through of the key's window-chosen text is a decision about a shared surface awakener
 * does not own (#186). The kind is a literal from [SurfaceKey]'s sealed hierarchy rather than a
 * substring of [SurfaceKey.canonical], so no `app_id` can reach that position. Uniqueness is the
 * digest's under every label, exactly as it is for [SurfaceKey.slug].
 */
fun spanreedNameFor(key: SurfaceKey, prefix: String, label: AgentNameLabel): String {
    if (label == AgentNameLabel.SLUG) return prefix + key.slug
    val head = when (key) {
        is SurfaceKey.Window -> key.appId
        is SurfaceKey.Titled -> key.title
        is SurfaceKey.Unidentified -> ""
        is SurfaceKey.Origin -> key.origin
    }
    val token = when (label) {
        AgentNameLabel.BOUNDED -> head.lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(AGENT_NAME_TOKEN_MAX)
        else -> ""
    }
    return listOf(prefix + key.kind, token, key.digest).filter { it.isNotEmpty() }.joinToString("-")
}

/**
 * Applies spanreed's documented `SPANREED_AGENT_NAME` override rule without a subprocess.
 *
 * Selected by [RegistryFlags.agentIdSource]; see that flag for the trade-off. Also the only
 * minter available in `commonMain`, since shelling out is a platform concern.
 */
class DerivedAgentIdentities(private val config: () -> Config) : AgentIdentities {
    constructor(store: ConfigStore) : this({ store.config.value })

    override suspend fun mint(key: SurfaceKey, residuePath: String): AgentIdentity {
        val cfg = config()
        val name = spanreedNameFor(
            key,
            cfg[RegistryFlags.agentNamePrefix],
            cfg[RegistryFlags.agentNameLabel],
        )
        return AgentIdentity(AgentId("agent-$name"), name)
    }
}
