package com.monkopedia.awakener.connector

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a connector's `connector.json` says about itself.
 *
 * Deliberately tiny, and the smallness is the design rather than an unfinished version of it.
 * Jason's brief: *"I'd like us to not be super super specific about how we connect these"* — so
 * the manifest carries only what awakener has to **act** on, and everything about how the tool is
 * actually reached lives in the connector's own files, which awakener renders and never reads.
 *
 * The consequence is the property that matters: adding a connector is adding a directory. There
 * is no enum of connector kinds, no MCP schema in here, no branch on the name. Awakener could not
 * enumerate the connectors it supports if it wanted to.
 *
 * Strict parsing — an unknown key is an error rather than a shrug — because these files are
 * hand-written and a typo'd key that silently means nothing is exactly the failure a manifest
 * with five keys cannot afford.
 */
@Serializable
data class ConnectorManifest(
    /** One line for `awakener-invoke connectors`. */
    val description: String = "",
    /**
     * The facts about the window this connector cannot work without, as [SurfaceFact] ids.
     *
     * Checked **before anything is written**, so a refusal leaves the residue directory exactly
     * as it found it. Declaring nothing is legal and means "render what you have"; it is not a
     * claim that the connector needs no facts, only that it will not be stopped by their absence.
     */
    val requires: List<String> = emptyList(),
    /**
     * That the tool this points at has not been vouched for.
     *
     * A property of the *definition*, set by whoever wrote it, and surfaced to whoever runs the
     * hotkey — see [ConnectorFlags.allowProvisional]. `computer-use-linux` ships with this set
     * because an audit of that MCP server was still running when it was written.
     */
    val provisional: Boolean = false,
    /** Why it is provisional, printed with the warning. Required when [provisional] is set. */
    @SerialName("provisional_reason") val provisionalReason: String = "",
)

/** A connector definition found on the search path. */
data class Connector(
    val name: String,
    /** The directory it was loaded from. */
    val dir: String,
    val manifest: ConnectorManifest,
    /**
     * Every file that will be materialised, as paths relative to [dir], `/`-separated.
     *
     * `connector.json` is not among them: the manifest is awakener's input, not the agent's.
     */
    val files: List<String>,
) {
    /** The facts [manifest] declares, resolved; unknown ids are rejected at load time. */
    val required: List<SurfaceFact>
        get() = manifest.requires.mapNotNull(SurfaceFact::byId)
}

/** A directory on the search path that looks like a connector and could not be loaded. */
data class BrokenConnector(val name: String, val dir: String, val reason: String)

/** What [ConnectorCatalog.list] found. */
data class ConnectorListing(
    val connectors: List<Connector>,
    val broken: List<BrokenConnector>,
    /** The directories searched, in order, so `connectors` with an empty result can say where. */
    val searched: List<String>,
)

/** What applying a connector to one surface did. */
sealed interface ConnectorOutcome {
    /**
     * The definition's files were rendered into the residue directory.
     *
     * @param written destination paths, absolute.
     * @param kept destination paths left alone under [ExistingFile.KEEP].
     * @param warnings things the user should see but that did not stop the invocation.
     */
    data class Applied(
        val name: String,
        val dir: String,
        val target: String,
        val written: List<String>,
        val kept: List<String>,
        val warnings: List<String>,
    ) : ConnectorOutcome

    /**
     * Nothing was written and the invocation must not stand a panel up.
     *
     * The panel is withheld deliberately. A connector is the difference between an agent that can
     * reach its window and one that cannot, and the second is not a degraded version of the first
     * — it is an agent that will try anyway, against whatever it can find. See [MissingFact].
     */
    data class Refused(val name: String, val reason: String) : ConnectorOutcome

    /** The connector was not applied, and that is survivable. Carries the warning to print. */
    data class Skipped(val name: String, val reason: String) : ConnectorOutcome

    /** No connector was selected. The unconfigured case, and byte-for-byte the old behaviour. */
    data object None : ConnectorOutcome
}

/**
 * Finds connector definitions and applies one to a surface.
 *
 * ### What a connector is
 *
 * A directory. Its files are rendered — every `{fact}` placeholder replaced with what awakener
 * knows about the window — and copied into the surface's residue directory, which is the agent's
 * working directory. That is the entire mechanism, and it is why a new connector needs no
 * rebuild: the layout inside the directory is the connector author's to choose, so an MCP
 * declaration, a skill markdown, a helper script and a README are just four files.
 *
 * ### How a connector is expected to disambiguate a window, and what happens when it cannot
 *
 * This is the hard part and awakener cannot solve it for the connector — but it can refuse to
 * pretend it has. What awakener supplies is [SurfaceFacts]: a pid, an `app_id`, a title, and a
 * durable key. Measured, in `docs/findings/`:
 *
 * - **`pid` narrows and never selects.** All eight windows of one Chromium instance report one
 *   pid, because they are one Wayland client. Two separate Epiphany processes gave two pids;
 *   one process with several windows will not.
 * - **`app_id` is not an identity.** A Chromium `--app=` window's `app_id` is minted from the
 *   launch string, encodes host and path, drops scheme and port, and is reported identically by
 *   a window that never loaded the site at all.
 * - **`title` is the only discriminating column, and it moves.**
 *
 * So the join a connector performs is *pid first, then title within that pid* — AT-SPI's
 * `application → frame` shape is exactly this, and CDP's target list is the same shape one
 * protocol over. The rule every shipped connector's instructions state, and that any new one
 * should copy:
 *
 * > Filter to the pid. Then match the title. **If zero or more than one candidate remains, stop
 * > and tell the user.** Do not pick the first, do not pick the most recently focused, do not
 * > widen the filter.
 *
 * That rule lives in the connector's markdown rather than in this code because it is the agent
 * that performs the join — awakener never talks to the tool. What awakener holds up its own end
 * of is the half it *can*: [MissingFact] refuses before writing anything when a declared fact is
 * absent, so a connector never receives a silently-empty join column.
 */
interface ConnectorCatalog {
    /** Every definition on the search path, plus the directories that look like one and are not. */
    fun list(): ConnectorListing

    /**
     * Renders [name] into [facts]`.residue` and returns what happened.
     *
     * @param name the connector to apply, or null for none — which returns [ConnectorOutcome.None]
     * without touching the filesystem.
     */
    fun apply(name: String?, facts: SurfaceFacts): ConnectorOutcome
}

/**
 * Expands every `{fact}` placeholder in [template].
 *
 * One pass over the input rather than a `replace` per fact, which is not an optimisation: a
 * chain of `replace` calls lets an *expansion* be rescanned, so a window whose title is
 * literally `{residue}` would have the residue path substituted into it. Rendering is a
 * substitution, not an evaluation, and a window title is attacker-controlled in the ordinary
 * sense — the program that owns the window chooses it.
 *
 * @param values what each fact expands to. A fact absent from the map expands to the empty
 * string; deciding whether that is acceptable is [ConnectorFlags.missingFact]'s and has already
 * happened by the time this is called.
 * @return the rendered text, and every fact whose placeholder was present but had no value.
 */
fun render(template: String, values: Map<SurfaceFact, String>): Rendered {
    val out = StringBuilder(template.length)
    val absent = LinkedHashSet<SurfaceFact>()
    var i = 0
    while (i < template.length) {
        val open = template.indexOf('{', i)
        if (open < 0) {
            out.append(template, i, template.length)
            break
        }
        val close = template.indexOf('}', open + 1)
        val fact = if (close < 0) null else SurfaceFact.byId(template.substring(open + 1, close))
        if (fact == null) {
            // Not a placeholder we know: copied through untouched, so a connector file may
            // contain JSON objects, shell parameter expansion and anything else with braces in
            // it without having to escape them.
            out.append(template, i, open + 1)
            i = open + 1
            continue
        }
        out.append(template, i, open)
        val value = values[fact]
        if (value == null) absent += fact else out.append(value)
        i = close + 1
    }
    return Rendered(out.toString(), absent.toList())
}

/** [render]'s result: the text, and which placeholders in it had nothing to expand to. */
data class Rendered(val text: String, val absent: List<SurfaceFact>)
