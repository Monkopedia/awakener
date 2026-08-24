package com.monkopedia.awakener.connector

/**
 * One fact awakener can tell a connector about the window it is being pointed at.
 *
 * An enum rather than free-form placeholder names, because a connector's manifest declares which
 * of these it *needs* — and a `requires` entry naming a fact that does not exist has to be a loud
 * error at load time rather than a placeholder that quietly never expands.
 *
 * **Every value here is compositor-agnostic by construction.** There is no `con_id`, no mark, no
 * workspace: [SURFACE_ID] is an opaque handle whose only promise is that it is what
 * `awakener-invoke invoke <id>` accepts for this window in this session, and nothing here says
 * what minted it. That is the same line `docs/design.md` draws around `:wm`, held one layer up
 * — a connector definition is data written by whoever wants a tool pointed at a window, and if
 * it could read a sway concept it would come to depend on one.
 *
 * @property id the name as it appears in a manifest's `requires` list.
 */
enum class SurfaceFact(val id: String) {
    /**
     * The compositor handle, as a decimal string.
     *
     * Valid only while the window is mapped, and deliberately so — it is here because a
     * connector's instructions want to be able to say "this is the window awakener acted on",
     * and because `awakener-invoke invoke <surface_id>` is the one command that round-trips it.
     * Nothing durable may be keyed on it; [SURFACE_KEY] is what outlives the window.
     */
    SURFACE_ID("surface_id"),

    /**
     * The window's own `app_id`, or absent when the window reports none.
     *
     * **Not an identity, and the measurement saying so is in the tree.**
     * `docs/findings/2026-08-23-chromium-app-mode-app-id.md`: a Chromium `--app=` window's
     * `app_id` is minted from the *launch string*, encodes host and path, drops scheme and port,
     * and is reported identically by a window that never loaded the site at all. Two windows of
     * one application share it by design. A connector that treats this as "which window" is
     * wrong, and this doc comment is the only place that will tell its author so.
     */
    APP_ID("app_id"),

    /**
     * The window's title right now, or absent when it has none.
     *
     * The most discriminating fact awakener has, and still not an identity: titles change under
     * you, and two windows of one app can share one. It is the *join* column — see the module
     * docs on [ConnectorCatalog] for the disambiguation rule connectors are expected to follow.
     */
    TITLE("title"),

    /**
     * The process id behind the window, or absent when the compositor does not report one.
     *
     * **Measured not to be unique per window**: all eight windows of one Chromium instance
     * report the same pid, because they are one Wayland client
     * (`docs/findings/2026-08-23-chromium-app-mode-app-id.md`). It narrows; it does not select.
     */
    PID("pid"),

    /** The durable identity the binding is keyed on — [SURFACE_ID]'s opposite in every way. */
    SURFACE_KEY("surface_key"),

    /** The agent bound to this surface. */
    AGENT_ID("agent_id"),

    /** The `SPANREED_AGENT_NAME` that agent answers to on the bus. */
    AGENT_NAME("agent_name"),

    /** Where this surface's durable residue lives, which is also the agent's working directory. */
    RESIDUE("residue"),

    /** The name of the connector being applied, so its own files can say what they are. */
    CONNECTOR("connector"),

    /** The directory the connector was loaded from, for instructions that reference a sibling. */
    CONNECTOR_DIR("connector_dir");

    /** How this fact is written inside a connector's files. */
    val placeholder: String get() = "{$id}"

    companion object {
        fun byId(id: String): SurfaceFact? = entries.firstOrNull { it.id == id }

        /** Every fact name, for an error message that has to say what was allowed. */
        val ids: List<String> get() = entries.map { it.id }
    }
}

/**
 * Everything awakener knows about one window, flattened to strings a connector can be told.
 *
 * A null field is an **honest absence** rather than an empty value, and the whole missing-fact
 * machinery rests on the difference: `app_id=` and "this window has no app_id" are the same
 * bytes once a placeholder has expanded, and only one of them is safe to act on. See
 * [ConnectorFlags.missingFact].
 */
data class SurfaceFacts(
    val surfaceId: Long,
    val appId: String?,
    val title: String?,
    val pid: Int?,
    val surfaceKey: String,
    val agentId: String,
    val agentName: String,
    val residue: String,
) {
    /**
     * The value of [fact], or null when awakener does not have it.
     *
     * [SurfaceFact.CONNECTOR] and [SurfaceFact.CONNECTOR_DIR] are about the connector rather than
     * the window, so they are supplied at render time and are absent here.
     */
    fun value(fact: SurfaceFact): String? = when (fact) {
        SurfaceFact.SURFACE_ID -> surfaceId.toString()
        SurfaceFact.APP_ID -> appId
        SurfaceFact.TITLE -> title
        SurfaceFact.PID -> pid?.toString()
        SurfaceFact.SURFACE_KEY -> surfaceKey
        SurfaceFact.AGENT_ID -> agentId
        SurfaceFact.AGENT_NAME -> agentName
        SurfaceFact.RESIDUE -> residue
        SurfaceFact.CONNECTOR, SurfaceFact.CONNECTOR_DIR -> null
    }
}
