package com.monkopedia.awakener.connector

import com.monkopedia.awakener.config.Flags

/** What happens when a connector needs a fact about the window and awakener does not have it. */
enum class MissingFact {
    /**
     * Do not apply the connector, and do not stand the panel up.
     *
     * The default, and the reason is the one thing this whole feature has to get right: a
     * connector exists to point a tool at *one* window, and every fact awakener can supply is
     * measured to be non-unique on its own. `pid` is shared by all eight windows of one Chromium
     * instance; an `--app=` `app_id` is minted from the launch string and drops scheme and port
     * entirely. So a connector that declared it needs the title, and got an empty one, is a
     * connector whose join column has silently gone. The failure that costs the most here is not
     * a hotkey that refuses — it is an agent confidently driving the wrong window, and there is
     * no observation the user can make afterwards that distinguishes it from the right one.
     */
    REFUSE,

    /**
     * Apply it anyway, expanding the absent fact to the empty string, and say so — on stderr, and
     * in the generated `surface.json` where it appears as JSON `null`.
     *
     * For the connector whose declared requirement is genuinely advisory: an instruction file
     * that mentions the title for a human's benefit does not stop working because a window has
     * none. It is opt-in precisely because deciding that is the connector author's judgement and
     * not awakener's.
     */
    ANNOTATE,
}

/** What materialising a connector does about a file that is already at the destination. */
enum class ExistingFile {
    /**
     * Overwrite it.
     *
     * The default because everything a connector writes is *derived* — from the definition on
     * disk and from this window's facts as they are right now — so a stale copy is not a user's
     * work being protected, it is last week's pid in a file the agent is about to believe.
     *
     * The blast radius is bounded by construction rather than by care: the only paths written
     * are paths that exist inside the connector's own directory, so awakener cannot touch a file
     * no connector author named.
     */
    REPLACE,

    /** Leave it, and report which files were kept. For hand-edited instruction files. */
    KEEP,

    /** Refuse the whole materialisation, touching nothing and standing no panel. */
    REFUSE,
}

/** What happens when the connector asked for is not in the search path. */
enum class UnknownConnector {
    /**
     * Refuse the invocation. The default: `--connector chromium` on a machine where that
     * definition is not installed asked for something specific, and a panel that comes up
     * without it looks exactly like one that came up with it.
     */
    REFUSE,

    /**
     * Stand the panel up with no connector, and warn.
     *
     * For a `connector.default` shared across machines where the definition is only installed on
     * some of them — the case where losing the tool is annoying and losing the hotkey is worse.
     */
    IGNORE,
}

/**
 * Runtime switches for connectors.
 *
 * **[default] being empty is invariant, not taste.** An unconfigured awakener applies no
 * connector, materialises nothing, and runs exactly the dock command it ran before this module
 * existed — the flags doctrine's "the default must be the behaviour you would have hard-coded"
 * applied to a feature whose whole surface is opt-in.
 */
object ConnectorFlags {
    /**
     * Which connector an `invoke` with no `--connector` applies.
     *
     * Empty means none, which is the shipped behaviour.
     *
     * The requirement is declared rather than sanitised at the read site for the reason
     * `CLAUDE.md` gives: coercing a bad value keeps the process up and tells nobody. It is also
     * doing real work here — a connector name is joined onto a search-path directory, so
     * `../../etc` is a name that would name something outside it. `Config.get` degrades a
     * rejected value to the default, which is the empty string, which is no connector.
     */
    val default = Flags.string(
        "connector.default",
        "",
        "Connector applied by `awakener-invoke invoke` when no --connector is given. Empty " +
            "means none, which is the unconfigured behaviour: nothing is materialised and the " +
            "dock command runs exactly as it would have.",
        Flags.requires("empty, or a connector name with no path separator and no ..") {
            it.isEmpty() || isConnectorName(it)
        },
    )

    /**
     * Where connector definitions are looked for.
     *
     * A `:`-separated search path rather than one directory, because two sources are wanted at
     * once and neither can be the only one: the definitions shipped with awakener, and whatever
     * the user has written. Earlier entries win, so a user copy of `chromium` in their own
     * directory shadows the shipped one without having to delete anything.
     *
     * Empty means the built-in path — see `FileConnectorCatalog.searchPath` for what that
     * resolves to and why the shipped directory is found through the environment rather than
     * guessed at from the install layout.
     */
    val dir = Flags.string(
        "connector.dir",
        "",
        "Colon-separated search path for connector definitions; earlier entries win, so a " +
            "user's copy shadows a shipped one. Empty means \$XDG_CONFIG_HOME/awakener/" +
            "connectors followed by \$AWAKENER_CONNECTORS, which the launcher points at the " +
            "definitions shipped with the build.",
    )

    /**
     * Where inside the residue directory a connector's files are written.
     *
     * Empty means the residue directory itself, and that is the default because the residue
     * directory is the agent's working directory: an MCP declaration or a skill file one level
     * down is a file the agent's own tooling does not look at. A sub-path is for a deployment
     * that wants connector output quarantined and is willing to tell the agent where to look.
     */
    val target = Flags.string(
        "connector.target",
        "",
        "Path under the surface's residue directory that a connector's files are written to. " +
            "Empty means the residue directory itself, which is the agent's working directory " +
            "and therefore the only place its own tooling will find them.",
    )

    val missingFact = Flags.enum(
        "connector.missing_fact",
        MissingFact.REFUSE,
        "What happens when a connector's manifest requires a fact about the window that " +
            "awakener does not have — a window with no title, or a compositor reporting no pid. " +
            "REFUSE stands no panel, because every fact awakener can supply is measured to be " +
            "non-unique alone and a connector missing its join column can only guess; ANNOTATE " +
            "applies it anyway with the fact empty and says so on stderr.",
    )

    val existing = Flags.enum(
        "connector.existing",
        ExistingFile.REPLACE,
        "What materialising does about a file already at the destination. REPLACE overwrites, " +
            "because connector output is derived from the definition and from this window's " +
            "facts, so a stale copy is last invocation's pid rather than the user's work; KEEP " +
            "leaves it and reports; REFUSE aborts the invocation having written nothing.",
    )

    val unknown = Flags.enum(
        "connector.unknown",
        UnknownConnector.REFUSE,
        "What happens when the connector named is not in connector.dir. REFUSE fails the " +
            "invocation, since a panel that comes up without the tool you asked for looks " +
            "exactly like one that came up with it; IGNORE stands the panel up and warns, for a " +
            "connector.default shared across machines where only some have the definition.",
    )

    /**
     * Whether an `invoke` may apply a connector whose manifest declares itself provisional.
     *
     * Its own flag rather than a line in a README because `provisional` is a claim the
     * *definition* makes about itself — "the tool this points at has not been audited" — and the
     * person who has to act on it is the one running the hotkey, not the one who wrote the
     * directory. Allowed by default: refusing would make a provisional definition undeployable
     * and therefore untestable, which defeats shipping one. The warning is what carries it.
     */
    val allowProvisional = Flags.boolean(
        "connector.allow_provisional",
        true,
        "Whether a connector whose manifest sets \"provisional\": true may be applied. It is " +
            "applied with a warning naming the manifest's own reason; false refuses it outright, " +
            "for a deployment that wants unaudited tooling to be unreachable rather than loud.",
    )

    /**
     * A connector name, as opposed to a path.
     *
     * Shared by the flag requirement above and by `--connector` parsing, so the CLI cannot be
     * laxer than the config file about the same string.
     */
    fun isConnectorName(value: String): Boolean =
        value.isNotEmpty() &&
            value != "." &&
            value != ".." &&
            !value.contains('/') &&
            !value.contains('\\') &&
            !value.contains(' ')
}
