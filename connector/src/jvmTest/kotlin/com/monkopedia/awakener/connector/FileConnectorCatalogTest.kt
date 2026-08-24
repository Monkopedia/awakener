package com.monkopedia.awakener.connector

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.config.InMemoryConfigStore
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Connector definitions on disk, and what applying one to a surface writes.
 *
 * Everything here runs against a temporary directory this class creates. Nothing shells out and
 * nothing is tool-gated, which is the claim `connector/build.gradle.kts` makes about this module
 * — see the note there for what changes the day that stops being true.
 */
class FileConnectorCatalogTest {
    private val root = createTempDirectory("awakener-connector")
    private val definitions = root.resolve("definitions").also { it.createDirectories() }
    private val residue = root.resolve("residue").also { it.createDirectories() }

    private val facts = SurfaceFacts(
        surfaceId = 7,
        appId = "chrome-example.com__-Default",
        title = "Example Domain",
        pid = 4242,
        surfaceKey = "app:app_id:editor",
        agentId = "agent-abc",
        agentName = "surface-editor",
        residue = residue.toString(),
    )

    @BeforeTest
    fun loadFlags() {
        Flags.requireLoaded(ConnectorFlags)
    }

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ helpers

    /** A connector directory named [name], with [files] in it. */
    private fun define(name: String, vararg files: Pair<String, String>) {
        val dir = definitions.resolve(name)
        files.forEach { (relative, text) ->
            val path = dir.resolve(relative)
            path.parent?.createDirectories()
            path.writeText(text)
        }
    }

    private fun catalog(configure: InMemoryConfigStore.() -> Unit = {}): FileConnectorCatalog {
        val store = InMemoryConfigStore().put(ConnectorFlags.dir, definitions.toString())
        store.configure()
        return FileConnectorCatalog(store.config.value)
    }

    private fun written(): List<String> =
        residue.toFile().walkTopDown().filter { it.isFile }
            .map { it.relativeTo(residue.toFile()).path }.sorted().toList()

    // ------------------------------------------------------------------ discovery

    @Test
    fun `a directory is a connector`() {
        define("chromium", "connector.json" to """{"description":"cdp"}""", "NOTES.md" to "hi")
        val listing = catalog().list()
        assertEquals(listOf("chromium"), listing.connectors.map { it.name })
        assertEquals("cdp", listing.connectors.single().manifest.description)
        assertEquals(listOf("NOTES.md"), listing.connectors.single().files)
    }

    /**
     * The whole of invariant 2 in one assertion. There is no registration step, no build file to
     * edit and no name in any source file — a directory that did not exist when this process
     * started is a connector, and the only reason it is one is that it is a directory.
     */
    @Test
    fun `a connector nothing in the build has ever heard of is discovered`() {
        define("something-nobody-wrote", "README.md" to "surface {surface_id}")
        val outcome = assertIs<ConnectorOutcome.Applied>(
            catalog().apply("something-nobody-wrote", facts),
        )
        assertEquals(listOf(".awakener/surface.json", "README.md"), written())
        assertEquals("surface 7", residue.resolve("README.md").readText())
        assertTrue(outcome.warnings.isEmpty(), outcome.warnings.toString())
    }

    @Test
    fun `a connector needs no manifest at all`() {
        define("bare", "SKILL.md" to "just this")
        val connector = catalog().list().connectors.single()
        assertEquals("bare", connector.name)
        assertEquals(ConnectorManifest(), connector.manifest)
    }

    @Test
    fun `nested files are part of the definition`() {
        define("nested", ".claude/skills/x/SKILL.md" to "deep", "top.md" to "shallow")
        assertEquals(
            listOf(".claude/skills/x/SKILL.md", "top.md"),
            catalog().list().connectors.single().files,
        )
    }

    @Test
    fun `the manifest is never materialised`() {
        define("m", "connector.json" to "{}", "a.md" to "a")
        catalog().apply("m", facts)
        assertFalse(residue.resolve("connector.json").exists())
    }

    @Test
    fun `a requires naming something that is not a surface fact is broken`() {
        define("bad", "connector.json" to """{"requires":["window_geometry"]}""", "a.md" to "a")
        val broken = catalog().list().broken.single()
        assertEquals("bad", broken.name)
        assertContains(broken.reason, "window_geometry")
        assertContains(broken.reason, "surface_id")
    }

    @Test
    fun `an unknown manifest key is broken rather than ignored`() {
        define("typo", "connector.json" to """{"descriptoin":"oops"}""", "a.md" to "a")
        assertContains(catalog().list().broken.single().reason, "connector.json could not be read")
    }

    @Test
    fun `provisional without a reason is broken`() {
        define("p", "connector.json" to """{"provisional":true}""", "a.md" to "a")
        assertContains(catalog().list().broken.single().reason, "provisional_reason")
    }

    @Test
    fun `a directory with no files to materialise is broken`() {
        definitions.resolve("empty").createDirectories()
        assertContains(catalog().list().broken.single().reason, "no files to materialise")
    }

    @Test
    fun `an earlier search path entry shadows a later one`() {
        val other = root.resolve("other").also { it.createDirectories() }
        other.resolve("chromium").createDirectories()
        other.resolve("chromium/a.md").writeText("shipped")
        define("chromium", "a.md" to "mine")
        val catalog = FileConnectorCatalog(
            InMemoryConfigStore()
                .put(ConnectorFlags.dir, "$definitions:$other")
                .config.value,
        )
        catalog.apply("chromium", facts)
        assertEquals("mine", residue.resolve("a.md").readText())
    }

    /**
     * A *broken* definition holds the name too. Without this, a user editing their own copy into
     * a syntax error would fall through to the shipped one and see their edit apparently
     * ignored — the failure mode where the fix looks like it did nothing.
     */
    @Test
    fun `a broken definition still shadows a working one further down the path`() {
        val other = root.resolve("other").also { it.createDirectories() }
        other.resolve("chromium").createDirectories()
        other.resolve("chromium/a.md").writeText("shipped")
        define("chromium", "connector.json" to "{ not json", "a.md" to "mine")
        val catalog = FileConnectorCatalog(
            InMemoryConfigStore()
                .put(ConnectorFlags.dir, "$definitions:$other")
                .config.value,
        )
        assertEquals(emptyList(), catalog.list().connectors.map { it.name })
        assertIs<ConnectorOutcome.Refused>(catalog.apply("chromium", facts))
    }

    @Test
    fun `an unset search path is the user directory then the shipped one`() {
        val catalog = FileConnectorCatalog(
            Config.EMPTY,
            mapOf("XDG_CONFIG_HOME" to "/xdg", "AWAKENER_CONNECTORS" to "/opt/awakener/connectors"),
        )
        assertEquals(
            listOf("/xdg/awakener/connectors", "/opt/awakener/connectors"),
            catalog.searchPath.map { it.toString() },
        )
    }

    @Test
    fun `a configured search path is the whole answer`() {
        val catalog = FileConnectorCatalog(
            InMemoryConfigStore().put(ConnectorFlags.dir, "/a:/b").config.value,
            mapOf("AWAKENER_CONNECTORS" to "/opt/awakener/connectors"),
        )
        assertEquals(listOf("/a", "/b"), catalog.searchPath.map { it.toString() })
    }

    // ------------------------------------------------------------------ applying

    @Test
    fun `no connector writes nothing at all`() {
        define("chromium", "a.md" to "a")
        assertEquals(ConnectorOutcome.None, catalog().apply(null, facts))
        assertEquals(emptyList(), written())
    }

    @Test
    fun `an empty name is no connector, not a missing one`() {
        assertEquals(ConnectorOutcome.None, catalog().apply("", facts))
    }

    @Test
    fun `facts are substituted into every file`() {
        define(
            "x",
            "a.md" to "pid={pid} title={title} app={app_id} agent={agent_name}",
            "sub/b.md" to "{connector} at {connector_dir}",
        )
        val outcome = assertIs<ConnectorOutcome.Applied>(catalog().apply("x", facts))
        assertEquals(
            "pid=4242 title=Example Domain app=chrome-example.com__-Default agent=surface-editor",
            residue.resolve("a.md").readText(),
        )
        assertEquals(
            "x at ${definitions.resolve("x")}",
            residue.resolve("sub/b.md").readText(),
        )
        assertEquals(3, outcome.written.size, outcome.written.toString())
    }

    /**
     * The generated half of the initial data, and the only place an *absent* fact is expressible:
     * once `{title}` has expanded, "no title" and "empty title" are the same bytes.
     */
    @Test
    fun `surface json carries every fact, with an absent one as null`() {
        define("x", "a.md" to "a")
        catalog { put(ConnectorFlags.missingFact, MissingFact.ANNOTATE) }
            .apply("x", facts.copy(title = null, pid = null))
        val json = Json.parseToJsonElement(
            residue.resolve(FileConnectorCatalog.FACTS).readText(),
        ) as JsonObject
        assertEquals(JsonNull, json["title"])
        assertEquals(JsonNull, json["pid"])
        assertEquals("chrome-example.com__-Default", json["app_id"]!!.jsonPrimitive.content)
        assertEquals("agent-abc", json["agent_id"]!!.jsonPrimitive.content)
        assertEquals("x", json["connector"]!!.jsonPrimitive.content)
    }

    @Test
    fun `surface json says which required facts were missing`() {
        define("x", "connector.json" to """{"requires":["title"]}""", "a.md" to "a")
        catalog { put(ConnectorFlags.missingFact, MissingFact.ANNOTATE) }.apply("x", facts.copy(title = null))
        val json = Json.parseToJsonElement(
            residue.resolve(FileConnectorCatalog.FACTS).readText(),
        ) as JsonObject
        assertEquals("title", json["missing_required"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an executable file arrives executable`() {
        define("x", "run.sh" to "#!/bin/sh\necho {pid}\n")
        val source = definitions.resolve("x/run.sh")
        source.setPosixFilePermissions(
            Files.getPosixFilePermissions(source) + PosixFilePermission.OWNER_EXECUTE,
        )
        catalog().apply("x", facts)
        assertTrue(residue.resolve("run.sh").isExecutable(), "the exec bit did not survive")
    }

    @Test
    fun `a missing required fact refuses and writes nothing`() {
        define("x", "connector.json" to """{"requires":["pid","title"]}""", "a.md" to "a")
        val refused = assertIs<ConnectorOutcome.Refused>(
            catalog().apply("x", facts.copy(pid = null)),
        )
        assertContains(refused.reason, "requires pid")
        assertContains(refused.reason, "connector.missing_fact=REFUSE")
        assertEquals(emptyList(), written(), "a refusal left files behind")
    }

    @Test
    fun `ANNOTATE applies it anyway and says so`() {
        define("x", "connector.json" to """{"requires":["title"]}""", "a.md" to "t=[{title}]")
        val applied = assertIs<ConnectorOutcome.Applied>(
            catalog { put(ConnectorFlags.missingFact, MissingFact.ANNOTATE) }.apply("x", facts.copy(title = null)),
        )
        assertEquals("t=[]", residue.resolve("a.md").readText())
        assertTrue(
            applied.warnings.any { "title" in it && "ANNOTATE" in it },
            applied.warnings.toString(),
        )
    }

    /**
     * The other kind of absence: a placeholder in a file that the manifest never claimed to need.
     * Reported and never fatal — the manifest is where a connector says what it cannot survive.
     */
    @Test
    fun `an undeclared placeholder that expands to nothing is a warning, not a refusal`() {
        define("x", "a.md" to "title=[{title}]")
        val applied = assertIs<ConnectorOutcome.Applied>(
            catalog().apply("x", facts.copy(title = null)),
        )
        assertEquals("title=[]", residue.resolve("a.md").readText())
        assertTrue(applied.warnings.any { "{title}" in it }, applied.warnings.toString())
    }

    @Test
    fun `an uninstalled connector refuses by default and names where it looked`() {
        val refused = assertIs<ConnectorOutcome.Refused>(catalog().apply("nope", facts))
        assertContains(refused.reason, "no such connector")
        assertContains(refused.reason, definitions.toString())
    }

    @Test
    fun `IGNORE stands the panel up without it`() {
        val skipped = assertIs<ConnectorOutcome.Skipped>(
            catalog { put(ConnectorFlags.unknown, UnknownConnector.IGNORE) }.apply("nope", facts),
        )
        assertContains(skipped.reason, "IGNORE")
        assertEquals(emptyList(), written())
    }

    /**
     * A name is a directory on the search path, so anything that could name something *else* is
     * refused before it is joined onto a path — and the flag declares the same requirement, so
     * the config file cannot be laxer than the CLI about it.
     */
    @Test
    fun `a name that is a path is refused`() {
        listOf("../etc", "a/b", "..", ".").forEach { name ->
            val refused = assertIs<ConnectorOutcome.Refused>(
                catalog().apply(name, facts),
                "'$name' was accepted as a connector name",
            )
            assertContains(refused.reason, "not usable as a connector name")
        }
        assertEquals(emptyList(), written())
    }

    @Test
    fun `REPLACE overwrites what the last invocation wrote`() {
        define("x", "a.md" to "pid={pid}")
        catalog().apply("x", facts)
        catalog().apply("x", facts.copy(pid = 99))
        assertEquals("pid=99", residue.resolve("a.md").readText())
    }

    @Test
    fun `KEEP leaves an existing file and reports it`() {
        define("x", "a.md" to "pid={pid}", "b.md" to "b")
        residue.resolve("a.md").writeText("mine")
        val applied = assertIs<ConnectorOutcome.Applied>(
            catalog { put(ConnectorFlags.existing, ExistingFile.KEEP) }.apply("x", facts),
        )
        assertEquals("mine", residue.resolve("a.md").readText())
        assertEquals(1, applied.kept.size, applied.kept.toString())
        assertEquals("b", residue.resolve("b.md").readText())
    }

    @Test
    fun `REFUSE aborts before writing any of the files`() {
        // `b.md` sorts after `a.md`, so a one-pass implementation would already have written it
        // by the time it reached the collision. That is what this asserts.
        define("x", "a.md" to "a", "b.md" to "b")
        residue.resolve("b.md").writeText("mine")
        val refused = assertIs<ConnectorOutcome.Refused>(
            catalog { put(ConnectorFlags.existing, ExistingFile.REFUSE) }.apply("x", facts),
        )
        assertContains(refused.reason, "already exists")
        assertEquals(listOf("b.md"), written())
        assertEquals("mine", residue.resolve("b.md").readText())
    }

    @Test
    fun `a provisional connector applies with a warning naming its own reason`() {
        define(
            "p",
            "connector.json" to
                """{"provisional":true,"provisional_reason":"unaudited MCP server"}""",
            "a.md" to "a",
        )
        val applied = assertIs<ConnectorOutcome.Applied>(catalog().apply("p", facts))
        assertTrue(
            applied.warnings.any { "unaudited MCP server" in it },
            applied.warnings.toString(),
        )
    }

    @Test
    fun `allow_provisional false refuses it outright`() {
        define(
            "p",
            "connector.json" to
                """{"provisional":true,"provisional_reason":"unaudited MCP server"}""",
            "a.md" to "a",
        )
        val refused = assertIs<ConnectorOutcome.Refused>(
            catalog { put(ConnectorFlags.allowProvisional, false) }.apply("p", facts),
        )
        assertContains(refused.reason, "unaudited MCP server")
        assertEquals(emptyList(), written())
    }

    /**
     * `registry.residue.layout=PER_KEY_FILE` gives each surface a *file*. Writing a connector's
     * tree beside it would put one surface's tool configuration in a directory every other
     * surface shares, which is the kind of wrong that only shows up as a second agent acting on
     * the first one's window.
     */
    @Test
    fun `a residue that is a file rather than a directory is refused`() {
        define("x", "a.md" to "a")
        val file = root.resolve("residue.md").also { it.writeText("model") }
        val refused = assertIs<ConnectorOutcome.Refused>(
            catalog().apply("x", facts.copy(residue = file.toString())),
        )
        assertContains(refused.reason, "not a directory")
        assertContains(refused.reason, "PER_KEY_DIR")
    }

    @Test
    fun `connector target puts everything under a sub path`() {
        define("x", "a.md" to "a")
        val applied = assertIs<ConnectorOutcome.Applied>(
            catalog { put(ConnectorFlags.target, ".awakener/connector") }.apply("x", facts),
        )
        assertEquals(
            listOf(".awakener/connector/.awakener/surface.json", ".awakener/connector/a.md"),
            written(),
        )
        assertContains(applied.target, ".awakener/connector")
    }

    @Test
    fun `a search path directory that does not exist is not an error`() {
        define("x", "a.md" to "a")
        val catalog = FileConnectorCatalog(
            InMemoryConfigStore()
                .put(ConnectorFlags.dir, "${root.resolve("nowhere")}:$definitions")
                .config.value,
        )
        assertEquals(listOf("x"), catalog.list().connectors.map { it.name })
    }

    @Test
    fun `a file on the search path is not a connector`() {
        definitions.resolve("README.md").writeText("not a connector")
        define("x", "a.md" to "a")
        val listing = catalog().list()
        assertEquals(listOf("x"), listing.connectors.map { it.name })
        assertEquals(emptyList(), listing.broken)
    }

    @Test
    fun `the residue directory is left exactly as found when nothing is applied`() {
        residue.resolve("CLAUDE.md").writeText("the agent's own model")
        assertEquals(ConnectorOutcome.None, catalog().apply(null, facts))
        assertEquals(
            listOf("CLAUDE.md"),
            residue.listDirectoryEntries().map { it.fileName.toString() },
        )
        assertEquals("the agent's own model", residue.resolve("CLAUDE.md").readText())
    }
}
