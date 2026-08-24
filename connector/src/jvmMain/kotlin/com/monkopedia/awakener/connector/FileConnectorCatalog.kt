package com.monkopedia.awakener.connector

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.FilePermissions
import com.monkopedia.awakener.config.PrivateFiles
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.setPosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Connector definitions read from directories on a search path.
 *
 * See [ConnectorCatalog] for what a connector *is* and for the disambiguation rule; this class is
 * the filesystem half of it.
 *
 * **The snapshot is passed in rather than read.** One hotkey press is one operation and must act
 * on one set of flag values — the same reason `Awakening.invoke` reads its snapshot once. A
 * catalog that re-read the store would let a reload land between "which connector" and "where to
 * write it".
 */
class FileConnectorCatalog(
    private val config: Config,
    private val environment: Map<String, String> = System.getenv(),
) : ConnectorCatalog {

    /**
     * Where definitions are looked for, in order. Earlier wins.
     *
     * When `connector.dir` is set it is the whole answer, and that is deliberate: someone who
     * configured a search path said where to look, and quietly appending a directory they did not
     * name is how a definition they thought they had shadowed comes back.
     *
     * Unset, two entries and neither can be dropped:
     *
     *  - `$XDG_CONFIG_HOME/awakener/connectors` (or `~/.config/...`) — the user's own.
     *  - `$AWAKENER_CONNECTORS` — the definitions shipped with the build. The **launcher** sets
     *    this, pointing at `$APP_HOME/connectors`, because the install layout is the launcher's
     *    knowledge and not this class's. Deriving it here from the code source location would
     *    work for a jar in `lib/` and silently produce nothing for every other way this class can
     *    be loaded — a test classpath, a fat jar, an IDE run — which is the shape of answer that
     *    looks like "no connectors are installed".
     */
    val searchPath: List<Path> = run {
        val configured = config[ConnectorFlags.dir]
        if (configured.isNotBlank()) {
            configured.split(':').filter { it.isNotBlank() }.map { Path(it) }
        } else {
            listOfNotNull(
                Path(
                    environment["XDG_CONFIG_HOME"]
                        ?: "${System.getProperty("user.home")}/.config",
                ).resolve("awakener/connectors"),
                environment["AWAKENER_CONNECTORS"]?.takeIf { it.isNotBlank() }?.let(::Path),
            )
        }
    }

    override fun list(): ConnectorListing {
        val found = LinkedHashMap<String, Connector>()
        val broken = mutableListOf<BrokenConnector>()
        searchPath.forEach { root ->
            if (!root.isDirectory()) return@forEach
            root.listDirectoryEntries().sortedBy { it.name }.forEach { entry ->
                if (!entry.isDirectory()) return@forEach
                val name = entry.name
                // Earlier entries win, and a *broken* definition still occupies the name: if it
                // did not, `~/.config` shadowing a shipped connector with a typo'd manifest would
                // fall through to the shipped one, and the user's edit would appear to have been
                // ignored rather than to have failed.
                if (name in found || broken.any { it.name == name }) return@forEach
                when (val loaded = load(name, entry)) {
                    is Loaded.Ok -> found[name] = loaded.connector
                    is Loaded.Bad -> broken += loaded.broken
                }
            }
        }
        return ConnectorListing(
            found.values.toList(),
            broken,
            searchPath.map { it.absolutePathString() },
        )
    }

    /** One definition, or the reason the directory is not one. */
    private sealed interface Loaded {
        data class Ok(val connector: Connector) : Loaded
        data class Bad(val broken: BrokenConnector) : Loaded
    }

    private fun load(name: String, dir: Path): Loaded {
        fun broken(reason: String) =
            Loaded.Bad(BrokenConnector(name, dir.absolutePathString(), reason))
        if (!ConnectorFlags.isConnectorName(name)) {
            return broken("'$name' is not usable as a connector name")
        }
        val manifestFile = dir.resolve(MANIFEST)
        val manifest = if (manifestFile.isRegularFile()) {
            runCatching { JSON.decodeFromString<ConnectorManifest>(manifestFile.readText()) }
                .getOrElse { return broken("$MANIFEST could not be read: ${it.message}") }
        } else {
            // A directory with no manifest is a valid connector that declares nothing. The
            // alternative — requiring the file — would mean the smallest possible connector, a
            // single markdown file telling the agent what window it is on, could not be written
            // without ceremony.
            ConnectorManifest()
        }
        manifest.requires.firstOrNull { SurfaceFact.byId(it) == null }?.let {
            return broken(
                "$MANIFEST requires '$it', which is not a surface fact; known facts are " +
                    SurfaceFact.ids.joinToString(", "),
            )
        }
        if (manifest.provisional && manifest.provisionalReason.isBlank()) {
            return broken(
                "$MANIFEST sets \"provisional\": true without a \"provisional_reason\", so " +
                    "nothing would tell the user what has not been vouched for",
            )
        }
        // `Files.walk` does not follow directory links, but `isRegularFile` follows *file* links,
        // so a symlink in a definition used to be dereferenced and its target copied into the
        // residue directory — a connector directory that reads as four harmless files while
        // materialising something outside it. A symlink is refused rather than skipped: a
        // definition that quietly materialises fewer files than it contains is a connector that
        // half works, and the author would have no way to tell.
        val entries = runCatching {
            Files.walk(dir).use { stream -> stream.toList() }
        }.getOrElse { return broken("could not be listed: ${it.message}") }
        entries.firstOrNull { Files.isSymbolicLink(it) }?.let {
            return broken(
                "${it.relativeTo(dir).joinToString("/")} is a symbolic link. A connector is " +
                    "materialised into the agent's working directory, so a link would copy " +
                    "whatever it points at; copy the file in instead.",
            )
        }
        val files = entries
            .filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
            .map { it.relativeTo(dir).joinToString("/") }
            .filter { it != MANIFEST }
            .sorted()
        if (files.isEmpty()) return broken("holds no files to materialise")

        // The window-fact scan, at load rather than at apply, so `awakener-invoke connectors`
        // reports it and a hotkey press is not where you find out. See [WindowFacts].
        val windowFacts = LinkedHashMap<String, List<SurfaceFact>>()
        files.forEach { relative ->
            val text = runCatching { dir.resolve(relative).readText() }
                .getOrElse { return broken("$relative could not be read as text: ${it.message}") }
            windowFactsIn(text).takeIf { it.isNotEmpty() }?.let { windowFacts[relative] = it }
        }
        if (windowFacts.isNotEmpty() && config[ConnectorFlags.windowFacts] == WindowFacts.REFUSE) {
            return broken(
                windowFacts.entries.joinToString("; ") { (file, facts) ->
                    "$file references ${facts.joinToString(", ") { it.placeholder }}"
                } +
                    " — those bytes are chosen by the program that owns the window, and a " +
                    "connector's files are rendered into the agent's working directory as " +
                    "instructions. Read them from $FACTS instead, where they are JSON-escaped " +
                    "(connector.window_facts=REFUSE).",
            )
        }
        return Loaded.Ok(
            Connector(name, dir.absolutePathString(), manifest, files, windowFacts),
        )
    }

    override fun apply(name: String?, facts: SurfaceFacts): ConnectorOutcome {
        if (name.isNullOrEmpty()) return ConnectorOutcome.None
        if (!ConnectorFlags.isConnectorName(name)) {
            return ConnectorOutcome.Refused(name, "'$name' is not usable as a connector name")
        }
        val listing = list()
        listing.broken.firstOrNull { it.name == name }?.let {
            // Always a refusal, never a skip: a definition that is *present and wrong* is
            // somebody's edit that did not take, and `connector.unknown=IGNORE` is about a
            // definition that is not installed here at all.
            return ConnectorOutcome.Refused(name, "${it.dir}: ${it.reason}")
        }
        val connector = listing.connectors.firstOrNull { it.name == name }
            ?: return when (config[ConnectorFlags.unknown]) {
                UnknownConnector.REFUSE -> ConnectorOutcome.Refused(
                    name,
                    "no such connector; searched ${listing.searched.joinToString(":")}",
                )

                UnknownConnector.IGNORE -> ConnectorOutcome.Skipped(
                    name,
                    "no such connector in ${listing.searched.joinToString(":")}; standing the " +
                        "panel up without it (connector.unknown=IGNORE)",
                )
            }

        val warnings = mutableListOf<String>()
        if (connector.manifest.provisional) {
            val note = "connector '$name' is provisional: ${connector.manifest.provisionalReason}"
            if (!config[ConnectorFlags.allowProvisional]) {
                return ConnectorOutcome.Refused(
                    name,
                    "$note (connector.allow_provisional=false)",
                )
            }
            warnings += note
        }

        val windowPolicy = config[ConnectorFlags.windowFacts]

        // **What awakener knows, which is not the same as what may be written down.** `requires`
        // asks whether awakener *has* the fact — chromium requires `title` because the title is
        // its join column, and it reads it from surface.json precisely because a window-chosen
        // fact may not be rendered. Filtering this map by trust made every shipped connector
        // report its own join column as missing and refuse; the trust rule belongs in `render`.
        val available = LinkedHashMap<SurfaceFact, String>()
        SurfaceFact.entries.forEach { fact -> facts.value(fact)?.let { available[fact] = it } }
        available[SurfaceFact.CONNECTOR] = connector.name
        available[SurfaceFact.CONNECTOR_DIR] = connector.dir

        // Not a filtered copy of `available`. The trust rule is enforced inside `render`, which
        // is the one function that emits bytes, so it holds whatever this map contains and can be
        // unit-tested on its own — see the note on `render`'s `allowWindowFacts`.
        val allowWindowFacts = windowPolicy == WindowFacts.RENDER_RAW
        if (windowPolicy == WindowFacts.RENDER_RAW && connector.windowFacts.isNotEmpty()) {
            warnings += "connector '$name' is rendering window-chosen bytes into " +
                connector.windowFacts.entries.joinToString("; ") { (file, facts) ->
                    "$file (${facts.joinToString(", ") { it.placeholder }})"
                } +
                " — the program that owns the window picks those, and these files are read by " +
                "the agent as instructions (connector.window_facts=RENDER_RAW)"
        }

        // Before a single byte is written. A refusal that had already half-materialised would
        // leave the agent a directory it cannot tell from a complete one.
        val missing = connector.required.filter { it !in available }
        if (missing.isNotEmpty()) {
            val named = missing.joinToString(", ") { it.id }
            val reason = "requires $named, and this window has no $named"
            when (config[ConnectorFlags.missingFact]) {
                MissingFact.REFUSE -> return ConnectorOutcome.Refused(
                    name,
                    "$reason (connector.missing_fact=REFUSE)",
                )

                MissingFact.ANNOTATE -> warnings +=
                    "connector '$name' $reason; applying it anyway with those empty " +
                        "(connector.missing_fact=ANNOTATE)"
            }
        }

        val residue = Path(facts.residue)
        if (!residue.isDirectory()) {
            // Not a guessed-at parent directory: `registry.residue.layout=PER_KEY_FILE` gives
            // each surface a *file*, and writing a connector's tree next to it would put one
            // surface's tool configuration in a directory every other surface shares.
            return ConnectorOutcome.Refused(
                name,
                "this surface's residue is not a directory (${facts.residue}) — a connector " +
                    "needs one, so set registry.residue.layout to PER_KEY_DIR or " +
                    "PER_APP_SESSION_DIR",
            )
        }
        val targetSub = config[ConnectorFlags.target]
        // Resolved against the residue directory's *real* path and required to stay under it.
        // The flag declares the same rule over the string a user typed, and neither check
        // subsumes the other: the requirement cannot see a symlink, and this cannot refuse a
        // value before it is used. `connector.default` was validated for exactly this reason
        // from the start and `connector.target` was not, which is the gap review found — an
        // absolute path and a `../ESCAPED` both returned Applied.
        val realResidue = runCatching { residue.toRealPath() }.getOrElse { residue }
        val target = realResidue.resolve(targetSub).normalize()
        if (!target.startsWith(realResidue)) {
            return ConnectorOutcome.Refused(
                name,
                "connector.target '$targetSub' resolves to ${target.absolutePathString()}, " +
                    "which is outside this surface's residue directory " +
                    "($realResidue). A connector writes into the residue directory and " +
                    "nowhere else.",
            )
        }

        // Two passes: decide everything, then write. `ExistingFile.REFUSE` has to be able to
        // touch nothing, and it cannot promise that from inside a loop that has already written.
        val plan = mutableListOf<Triple<Path, Path, Rendered>>()
        val kept = mutableListOf<String>()
        val absent = LinkedHashSet<SurfaceFact>()
        connector.files.forEach { relative ->
            val source = Path(connector.dir).resolve(relative)
            val destination = target.resolve(relative)
            // A symlink anywhere on the way down, or at the leaf, is refused outright and is not
            // `connector.existing`'s to decide. That flag is about a *file* that is already
            // there; a link is about where the write lands, and `REPLACE` following one out of
            // the residue directory would write this window's facts wherever it pointed. The
            // residue directory is 0700, so planting one takes a foothold already — which is
            // exactly the case containment is for.
            escapingLink(target, destination)?.let {
                return ConnectorOutcome.Refused(
                    name,
                    "${it.absolutePathString()} is a symbolic link, so writing $relative would " +
                        "follow it out of the residue directory; nothing was written",
                )
            }
            if (destination.exists(LinkOption.NOFOLLOW_LINKS)) {
                when (config[ConnectorFlags.existing]) {
                    ExistingFile.REFUSE -> return ConnectorOutcome.Refused(
                        name,
                        "${destination.absolutePathString()} already exists " +
                            "(connector.existing=REFUSE); nothing was written",
                    )

                    ExistingFile.KEEP -> {
                        kept += destination.absolutePathString()
                        return@forEach
                    }

                    ExistingFile.REPLACE -> Unit
                }
            }
            val rendered = runCatching { render(source.readText(), available, allowWindowFacts) }
                .getOrElse {
                    return ConnectorOutcome.Refused(
                        name,
                        "$relative could not be read as text: ${it.message}",
                    )
                }
            absent += rendered.absent
            plan += Triple(source, destination, rendered)
        }

        // Placeholders that expanded to nothing but were never declared in `requires`. Reported
        // and never fatal — the manifest is where a connector says which absences it cannot
        // survive, and this is the other kind: a file that mentions the title in prose.
        (absent - connector.required.toSet()).takeIf { it.isNotEmpty() }?.let { undeclared ->
            warnings += "connector '$name' expanded " +
                undeclared.joinToString(", ") { it.placeholder } +
                " to nothing; this window has no such fact and $MANIFEST does not require it"
        }

        val permissions = config[ConnectorFlags.permissions]
        val written = mutableListOf<String>()
        runCatching {
            plan.forEach { (source, destination, rendered) ->
                // Through `PrivateFiles` rather than `writeText`, so the mode is a creation
                // attribute rather than a chmod afterwards: a connector's files carry this
                // window's title and pid, and the interval between "exists" and "is private" is
                // exactly the interval another local process would need.
                PrivateFiles.createParentDirectories(destination, permissions)
                PrivateFiles.writeString(destination, rendered.text, permissions)
                // The execute bit and nothing else, added to whatever the mode already is. A
                // connector may ship a helper script, and a script that arrives non-executable
                // fails as "command not found" — a message that sends the reader looking for a
                // missing program rather than for a mode bit.
                if (source.isExecutable() &&
                    config[ConnectorFlags.executable] == ExecutableFiles.PRESERVE
                ) {
                    runCatching {
                        destination.setPosixFilePermissions(
                            Files.getPosixFilePermissions(destination) +
                                PosixFilePermission.OWNER_EXECUTE,
                        )
                    }
                }
                written += destination.absolutePathString()
            }
            written += writeFacts(target, connector, facts, missing, permissions)
        }.getOrElse {
            return ConnectorOutcome.Refused(
                name,
                "writing into ${target.absolutePathString()} failed: ${it.message}; " +
                    "${written.size} file(s) were already written",
            )
        }
        return ConnectorOutcome.Applied(
            name = connector.name,
            dir = connector.dir,
            target = target.absolutePathString(),
            written = written,
            kept = kept,
            warnings = warnings,
        )
    }

    /**
     * The first symbolic link between [root] (exclusive) and [path] (inclusive), or null.
     *
     * Only components that already exist are looked at — the rest are about to be created by this
     * call, as real directories, so there is nothing there to follow.
     */
    private fun escapingLink(root: Path, path: Path): Path? {
        var current: Path? = path
        while (current != null && current != root && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) return current
            current = current.parent
        }
        return null
    }

    /**
     * The generated half of the initial data: every fact, as JSON, with an absent one as `null`.
     *
     * This exists **beside** placeholder expansion rather than instead of it, because the two
     * answer different needs and neither covers the other. A placeholder is for a fact that has
     * to appear inline — an MCP server's `--pid=` argument cannot be a lookup. This file is for
     * the agent reading its own situation, and it is the one place where "this window has no
     * title" is expressible: once `{title}` has expanded, an absent title and an empty one are
     * the same bytes, and the whole disambiguation problem turns on telling them apart.
     */
    private fun writeFacts(
        target: Path,
        connector: Connector,
        facts: SurfaceFacts,
        missing: List<SurfaceFact>,
        permissions: FilePermissions,
    ): String {
        fun text(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull
        val json = JsonObject(
            mapOf(
                "connector" to JsonPrimitive(connector.name),
                "connector_dir" to JsonPrimitive(connector.dir),
                "surface_id" to JsonPrimitive(facts.surfaceId),
                "app_id" to text(facts.appId),
                "title" to text(facts.title),
                "pid" to (facts.pid?.let(::JsonPrimitive) ?: JsonNull),
                "surface_key" to JsonPrimitive(facts.surfaceKey),
                "agent_id" to JsonPrimitive(facts.agentId),
                "agent_name" to JsonPrimitive(facts.agentName),
                "residue" to JsonPrimitive(facts.residue),
                // Named rather than left to be inferred from the nulls: a connector that
                // declared it needs the title and got none is in a different situation from one
                // that never asked, and only the manifest knows which this is.
                "missing_required" to JsonPrimitive(missing.joinToString(",") { it.id }),
            ),
        )
        val out = target.resolve(FACTS)
        escapingLink(target, out)?.let {
            throw IllegalStateException(
                "${it.absolutePathString()} is a symbolic link, so writing $FACTS would follow " +
                    "it out of the residue directory",
            )
        }
        PrivateFiles.createParentDirectories(out, permissions)
        val staging = out.resolveSibling("${out.name}.tmp")
        PrivateFiles.writeString(
            staging,
            PRETTY.encodeToString(JsonObject.serializer(), json),
            permissions,
        )
        // Through a staging file and a rename, so an agent reading this while a second press is
        // rewriting it sees one press's answer or the other's and never half of either. The
        // residue directory is the agent's working directory, so a concurrent reader is the
        // ordinary case rather than a contrived one.
        Files.move(staging, out, StandardCopyOption.REPLACE_EXISTING)
        return out.absolutePathString()
    }

    companion object {
        /** The manifest file inside a connector directory. Never materialised. */
        const val MANIFEST = "connector.json"

        /**
         * Where the generated facts land, relative to the target directory.
         *
         * Under a dot-directory so it does not sit in the agent's face beside the instructions
         * that tell it what to do with it, and under a name no connector author is likely to
         * pick for their own file — a collision would be a connector silently overwritten by
         * awakener's own output.
         */
        const val FACTS = ".awakener/surface.json"

        private val JSON = Json { ignoreUnknownKeys = false }
        private val PRETTY = Json { prettyPrint = true }
    }
}
