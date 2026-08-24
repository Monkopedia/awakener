package com.monkopedia.awakener.wm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A node in sway's layout tree. Only the fields awakener actually reasons about are modelled;
 * sway sends considerably more, so decoding must ignore unknown keys.
 */
@Serializable
data class Node(
    val id: Long,
    val name: String? = null,
    val type: String,
    val layout: String? = null,
    @SerialName("app_id") val appId: String? = null,
    val pid: Int? = null,
    val focused: Boolean = false,
    /**
     * The fraction of its parent this node occupies, or null where sway reports none.
     *
     * The readback for `resize set width <n> ppt`, which is the only reason it is modelled: a
     * restore has to be able to assert that the dock *landed* at its configured width rather than
     * that the command was accepted. sway reports it as a double, so `0.29999999999999999` is what
     * 30 ppt comes back as and a comparison against it has to carry a tolerance.
     *
     * Null in two cases that both matter here: a window in the scratchpad has no parent to be a
     * fraction of, and a `floating_con` reports whatever fraction it last had rather than one that
     * means anything. So this is never on its own enough to say a dock is docked — see
     * [SwayWindowManager] `restoreFault`, which asks about the scratchpad and about [type] first.
     */
    val percent: Double? = null,
    val marks: List<String> = emptyList(),
    val nodes: List<Node> = emptyList(),
    @SerialName("floating_nodes") val floatingNodes: List<Node> = emptyList(),
) {
    val children: List<Node> get() = nodes + floatingNodes

    /**
     * Windows, as opposed to the containers holding them. A node is a window when it has no
     * children and owns a process — the `pid` test matters because sway's tree also contains
     * childless nodes that are not windows (an empty workspace, the scratch output).
     */
    val windows: List<Node>
        get() = if (children.isEmpty()) {
            if (pid != null) listOf(this) else emptyList()
        } else {
            children.flatMap { it.windows }
        }

    fun find(id: Long): Node? =
        if (this.id == id) this else children.firstNotNullOfOrNull { it.find(id) }

    /** The node whose [children] contain [id], or null if [id] is absent or is the root. */
    fun parentOf(id: Long): Node? = when {
        children.any { it.id == id } -> this
        else -> children.firstNotNullOfOrNull { it.parentOf(id) }
    }

    fun workspace(name: String): Node? = when {
        type == "workspace" && this.name == name -> this
        else -> children.firstNotNullOfOrNull { it.workspace(name) }
    }
}

@Serializable
data class WindowEvent(val change: String, val container: Node? = null)

@Serializable
data class CommandResult(val success: Boolean = false, val error: String? = null)

internal val swayJson: Json = Json { ignoreUnknownKeys = true }
