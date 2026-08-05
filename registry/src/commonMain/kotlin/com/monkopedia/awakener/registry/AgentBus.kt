package com.monkopedia.awakener.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One agent as the bus reports it right now.
 *
 * Only the fields awakener reasons about are modelled; spanreed's registry carries more (focus,
 * status, last-seen) and is free to grow, so decoding ignores what it does not know.
 *
 * Note what is *not* here: liveness is not a field. spanreed already excludes an entry whose
 * process is gone from `list`, so presence in the answer is the liveness, and re-deriving it
 * from a `pid` would be awakener holding an invariant that belongs to spanreed.
 */
@Serializable
data class LiveAgent(
    @SerialName("agent_id") val agentId: String,
    val name: String? = null,
    @SerialName("working_dir") val workingDir: String? = null,
    val pid: Long? = null,
) {
    val agent: AgentId get() = AgentId(agentId)
}

/**
 * What the bus can be asked, from awakener's side.
 *
 * Deliberately one question. "Is this Lifeless already animated?" is the only thing the invoke
 * path needs, and it is a better question than "is there already a panel beside this window?" for
 * two reasons: it is compositor-agnostic, so it does not put a fourth call on [WindowManager]'s
 * interface to ask the tree something the tree only accidentally knows; and it is the question
 * that stays right when the panel and the agent stop being one-to-one — a Chrome manager
 * mirroring several origins behind one window has agents with no window of their own, and a dock
 * whose program died leaves a window with no agent behind it.
 *
 * An interface rather than a concrete class because the answer comes from a subprocess, and a
 * test that wants to know what awakener does with "already animated" should not have to register
 * a throwaway agent on Jason's live bus to find out.
 */
fun interface AgentBus {
    /** Every agent the bus currently considers live. */
    suspend fun liveAgents(): List<LiveAgent>
}

/** Whether [agent] is animated — has a live session on the bus — right now. */
suspend fun AgentBus.isAnimated(agent: AgentId): Boolean =
    liveAgents().any { it.agentId == agent.raw }
