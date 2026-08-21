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
 * What a [BindingStore.unbind] did.
 *
 * @param wasBound whether there was a binding to drop. False means nothing was forgotten, and
 * nothing is disposed of either: `forget` is about the binding, so a surface that has none has
 * had nothing taken away and its residue is not the command's to touch.
 */
data class Forget(val wasBound: Boolean, val residue: ResidueOutcome)

/**
 * What became of a forgotten surface's residue.
 *
 * Every case names the [path] it is talking about, because the sentence a user reads after
 * deciding an agent was wrong about them is the one place "the model is gone" and "the model
 * is still there" must not look alike.
 */
sealed interface ResidueOutcome {
    /** Where the residue was, whatever happened to it. */
    val path: String

    /** Left where it is — [ForgetResidue.KEEP], and what the fresh Lifeless will read. */
    data class Kept(override val path: String) : ResidueOutcome

    /** Renamed to [archive], which the fresh Lifeless does not read and a human still can. */
    data class Archived(override val path: String, val archive: String) : ResidueOutcome

    /** Removed. Nothing in awakener can bring it back. */
    data class Deleted(override val path: String) : ResidueOutcome

    /**
     * There was nothing at [path] to dispose of — the surface was bound but never wrote
     * anything down. Distinct from [Kept] on purpose: "nothing to archive" and "archiving is
     * switched off" leave the same empty directory behind and mean opposite things.
     */
    data class Absent(override val path: String) : ResidueOutcome

    /**
     * The disposal was asked for and did not happen; the residue is still at [path].
     *
     * Not thrown, because the binding is already gone by then and the forget did succeed —
     * but not silent either, since under [ForgetResidue.DELETE] this is a model the user asked
     * to be rid of that is still on disk.
     */
    data class Failed(override val path: String, val reason: String) : ResidueOutcome
}

/**
 * What an exposure check found, and about which directory.
 *
 * Four states rather than the `String?` this replaced, because that one collapsed three different
 * situations into the same `null`: the flag being [ResidueExposure.ALLOW] so nothing was asked, a
 * check that ran and found the directory private, and a store on which `prepareResidue` has never
 * been called at all. "Did not happen" and "was not looked for" reaching a reader as the same
 * silence is the shape this repository keeps finding, and the warning string cannot tell them
 * apart on its own.
 *
 * @param examined the directory the finding is about, or null when none was looked at. This is
 * the field that makes a *change* of answer legible: under
 * [ResidueExposureScope.DEEPEST_EXISTING] the second `prepareResidue` of a deployment reports
 * [ResidueExposureFinding.PRIVATE] naming the residue directory awakener has since created,
 * where the first reported [ResidueExposureFinding.EXPOSED] naming the directory above it. Same
 * check, different subject — and without this field that is indistinguishable from a deployment
 * where nothing was ever exposed.
 * @param warning the sentence to print, non-null exactly when the finding is
 * [ResidueExposureFinding.EXPOSED] **and** the policy is [ResidueExposure.REPORT]. Under
 * [ResidueExposure.REFUSE] the finding is still recorded and this stays null, because there the
 * sentence a human reads is the exception `prepareResidue` raised and a caller printing both
 * would report one hazard twice.
 */
data class ResidueExposureCheck(
    val finding: ResidueExposureFinding = ResidueExposureFinding.NOT_RUN,
    val examined: String? = null,
    val warning: String? = null,
)

/** What [ResidueExposureCheck] found. */
enum class ResidueExposureFinding {
    /**
     * No residue has been prepared through this store yet, so nothing has been asked.
     *
     * Distinct from [ALLOWED] because they differ in what a reader should do: this one clears the
     * moment somebody presses the hotkey, and that one never will while the flag says so.
     */
    NOT_RUN,

    /** `registry.residue.exposed_dir=ALLOW`, so the question was not put. */
    ALLOWED,

    /**
     * Examined, and no other local user can write it.
     *
     * Read [ResidueExposureCheck.examined] before reading this as "the residue is somewhere
     * private": under [ResidueExposureScope.DEEPEST_EXISTING] this is also what a deployment
     * reports from its second press onward, about the `0700` directory awakener itself created
     * inside a shared one.
     */
    PRIVATE,

    /** Examined, and another local user can write it. [ResidueExposureCheck.warning] says why. */
    EXPOSED,
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
     * Forgets a binding, and disposes of its residue per [RegistryFlags.forgetResidue].
     *
     * This is the repair path, so it has to hold against a process that is still holding the
     * binding it removed. A durable implementation must not let such a holder write its own
     * view of the file back over this — see [RegistryFlags.storeReload] for how, and
     * [RegistryFlags.forgetConflict] for what a holder's next `bind` then does.
     *
     * **The residue half is reported rather than assumed** ([Forget.residue]). Dropping the
     * binding and disposing of what the agent wrote down are two operations against two
     * different pieces of state, and the second can fail — a read-only state directory — with
     * the first already durable. A caller told only "forgotten" would then believe a model had
     * been archived, or deleted, that is still sitting on disk.
     */
    suspend fun unbind(key: SurfaceKey): Forget

    /**
     * Where this surface's distilled residue lives.
     *
     * Resolved from the key rather than stored, so it is answerable for a surface that has
     * never been bound — which is what lets a cold spawn load residue before it has an agent.
     */
    fun residueLocation(key: SurfaceKey): String
}
