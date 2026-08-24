package com.monkopedia.awakener.connector

/**
 * Who chose the bytes in a fact.
 *
 * **This is the axis that matters, and it is a property of the fact rather than a list kept
 * somewhere else.** The first version of this module excluded `{title}` from the one file where
 * substituting it was obviously dangerous, by name, in a comment — and review found two more
 * members of the same class on the first look: `{app_id}` is the verbatim value the Wayland
 * client declared, and `{surface_key}` is built by `SurfaceKey.escapeSegment`, which escapes `%`
 * and `:` and passes every other byte through, newlines included.
 *
 * Excluding by name is a denylist, and a denylist over a set that grows is a defect waiting for
 * the next fact. So the classification lives on the declaration, [SurfaceFact] takes it as a
 * **required** constructor argument with no default, and everything that enforces it iterates the
 * class rather than naming its members. Adding a fact is not possible without answering this.
 */
enum class Trust {
    /**
     * Minted by awakener or by the compositor, in a bounded alphabet.
     *
     * Verified rather than assumed for each one: [SurfaceFact.SURFACE_ID] and
     * [SurfaceFact.PID] are integers; [SurfaceFact.RESIDUE], [SurfaceFact.AGENT_NAME] and
     * [SurfaceFact.AGENT_ID] are built from `SurfaceKey.slug`, whose `slugOf` maps every
     * character that is not a letter, a digit or `-` to `-` and appends a digest, so they are
     * `[a-z0-9-]` plus operator-configured path and prefix; [SurfaceFact.CONNECTOR] is checked
     * by [ConnectorFlags.isConnectorName]; [SurfaceFact.CONNECTOR_DIR] is a directory on the
     * operator's own search path.
     */
    AWAKENER,

    /**
     * Chosen by the program that owns the window, and therefore by whatever is running in it.
     *
     * A web page sets `document.title`; any Wayland client declares its own `app_id`; `:wm`
     * hands both through verbatim and is right to — sanitising at that layer would be a
     * compositor-specific guess about what some consumer needs. The consumer is this module,
     * and [ConnectorFlags.windowFacts] is where it decides.
     */
    WINDOW,
}

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
enum class SurfaceFact(val id: String, val trust: Trust) {
    /**
     * The compositor handle, as a decimal string.
     *
     * Valid only while the window is mapped, and deliberately so — it is here because a
     * connector's instructions want to be able to say "this is the window awakener acted on",
     * and because `awakener-invoke invoke <surface_id>` is the one command that round-trips it.
     * Nothing durable may be keyed on it; [SURFACE_KEY] is what outlives the window.
     */
    SURFACE_ID("surface_id", Trust.AWAKENER),

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
    APP_ID("app_id", Trust.WINDOW),

    /**
     * The window's title right now, or absent when it has none.
     *
     * The most discriminating fact awakener has, and still not an identity: titles change under
     * you, and two windows of one app can share one. It is the *join* column — see the module
     * docs on [ConnectorCatalog] for the disambiguation rule connectors are expected to follow.
     */
    TITLE("title", Trust.WINDOW),

    /**
     * The process id behind the window, or absent when the compositor does not report one.
     *
     * **Measured not to be unique per window**: all eight windows of one Chromium instance
     * report the same pid, because they are one Wayland client
     * (`docs/findings/2026-08-23-chromium-app-mode-app-id.md`). It narrows; it does not select.
     */
    PID("pid", Trust.AWAKENER),

    /**
     * The durable identity the binding is keyed on — [SURFACE_ID]'s opposite in every way.
     *
     * **[Trust.WINDOW], which is not obvious and was missed.** It reads like an awakener-minted
     * identifier and it is one — but `SurfaceKey.escapeSegment` escapes `%` and `:` and nothing
     * else, precisely because its job is injectivity rather than safety, so the `app_id` or title
     * it was built from is carried through byte for byte, newlines and backticks included.
     */
    SURFACE_KEY("surface_key", Trust.WINDOW),

    /** The agent bound to this surface. */
    AGENT_ID("agent_id", Trust.AWAKENER),

    /** The `SPANREED_AGENT_NAME` that agent answers to on the bus. */
    AGENT_NAME("agent_name", Trust.AWAKENER),

    /** Where this surface's durable residue lives, which is also the agent's working directory. */
    RESIDUE("residue", Trust.AWAKENER),

    /** The name of the connector being applied, so its own files can say what they are. */
    CONNECTOR("connector", Trust.AWAKENER),

    /** The directory the connector was loaded from, for instructions that reference a sibling. */
    CONNECTOR_DIR("connector_dir", Trust.AWAKENER);

    /** How this fact is written inside a connector's files. */
    val placeholder: String get() = "{$id}"

    companion object {
        fun byId(id: String): SurfaceFact? = entries.firstOrNull { it.id == id }

        /** Every fact whose bytes the window chose. Derived, never written down twice. */
        val windowChosen: List<SurfaceFact> get() = entries.filter { it.trust == Trust.WINDOW }

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
