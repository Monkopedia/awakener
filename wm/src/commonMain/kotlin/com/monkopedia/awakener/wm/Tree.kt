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
