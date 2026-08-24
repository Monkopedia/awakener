package com.monkopedia.awakener.connector

import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.config.InMemoryConfigStore
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The definitions this build ships, loaded from the repository rather than from a fixture.
 *
 * The point is the one thing a fixture cannot do: connector definitions are **data**, so nothing
 * in the compiler, the type system or any other test looks at them. A `requires` naming a fact
 * that does not exist, a manifest with a typo'd key, a `provisional` with no reason — every one of
 * those makes a definition load as broken at runtime, on a hotkey press, and nothing else in this
 * build would ever say so.
 *
 * The directory arrives as `awakener.connectors` from `connector/build.gradle.kts`, which also
 * declares its *contents* as a task input so an edit re-runs this rather than replaying a green.
 */
class ShippedConnectorsTest {
    private val shipped = Path(
        assertNotNull(
            System.getProperty("awakener.connectors"),
            "the build did not say where the shipped connectors are",
        ),
    )
    private val root = createTempDirectory("awakener-shipped")
    private val residue = root.resolve("residue").also { it.createDirectories() }

    private val facts = SurfaceFacts(
        surfaceId = 12,
        appId = "chrome-example.com__-Default",
        title = "Example Domain",
        pid = 4242,
        surfaceKey = "app:app_id:chrome-example.com__-Default",
        agentId = "agent-abc",
        agentName = "surface-chrome",
        residue = residue.toString(),
    )

    private val catalog by lazy {
        FileConnectorCatalog(
            InMemoryConfigStore().put(ConnectorFlags.dir, shipped.toString()).config.value,
        )
    }

    @BeforeTest
    fun loadFlags() {
        Flags.requireLoaded(ConnectorFlags)
    }

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `every shipped definition loads`() {
        val listing = catalog.list()
        assertEquals(
            emptyList(),
            listing.broken,
            "a shipped connector does not load, and only a hotkey press would have said so",
        )
        assertEquals(
            listOf("chromium", "computer-use-linux"),
            listing.connectors.map { it.name },
        )
    }

    /** A control on the loader, not on the definitions: `connectors/README.md` is not one. */
    @Test
    fun `the readme beside them is not mistaken for a connector`() {
        assertTrue(shipped.resolve("README.md").exists(), "the fixture this controls on is gone")
        assertTrue(catalog.list().connectors.none { it.name == "README.md" })
    }

    @Test
    fun `chromium applies to a surface and carries its skill`() {
        val applied = assertIs<ConnectorOutcome.Applied>(catalog.apply("chromium", facts))
        assertTrue(applied.warnings.isEmpty(), applied.warnings.toString())
        assertTrue(residue.resolve("cdp.sh").exists())
        assertTrue(residue.resolve(".claude/skills/chromium-window/SKILL.md").exists())
        assertTrue(residue.resolve(FileConnectorCatalog.FACTS).exists())
        // The pid is substituted into the helper, which is the only fact that may be: a title is
        // chosen by the program that owns the window, so it is read from surface.json at runtime.
        assertContains(residue.resolve("cdp.sh").readText(), "pid=4242")
    }

    @Test
    fun `chromium refuses a window with no title, because title is its join column`() {
        val refused = assertIs<ConnectorOutcome.Refused>(
            catalog.apply("chromium", facts.copy(title = null)),
        )
        assertContains(refused.reason, "title")
    }

    /**
     * The audit's verdict, held by a test rather than by a paragraph.
     *
     * The 2026-08-23 audit of `agent-sh/computer-use-linux` found its acting tools unscopable to
     * a window — `perform_action`, `set_value` and `drag` take no window parameter and resolve
     * against a server-wide cache — so declaring that server directly would hand every agent a
     * capability over every window in the session. This connector therefore wires **no** MCP
     * server, and this is what makes re-adding one a deliberate act with a red test in front of
     * it rather than an afternoon's tidying.
     */
    @Test
    fun `computer-use-linux declares no MCP server`() {
        val connector = catalog.list().connectors.single { it.name == "computer-use-linux" }
        assertFalse(
            connector.files.any { it.endsWith(".mcp.json") },
            "computer-use-linux ships ${connector.files}; the audit says a pass-through MCP " +
                "declaration hands the agent reach over every window in the session, so wiring " +
                "one has to come with the proxy its skill describes",
        )
    }

    @Test
    fun `computer-use-linux is provisional and says why`() {
        val connector = catalog.list().connectors.single { it.name == "computer-use-linux" }
        assertTrue(connector.manifest.provisional)
        assertContains(connector.manifest.provisionalReason, "audit")
        val applied = assertIs<ConnectorOutcome.Applied>(
            catalog.apply("computer-use-linux", facts),
        )
        assertTrue(
            applied.warnings.any { "provisional" in it && "audit" in it },
            applied.warnings.toString(),
        )
    }

    @Test
    fun `allow_provisional false makes it unreachable`() {
        val catalog = FileConnectorCatalog(
            InMemoryConfigStore()
                .put(ConnectorFlags.dir, shipped.toString())
                .put(ConnectorFlags.allowProvisional, false)
                .config.value,
        )
        assertIs<ConnectorOutcome.Refused>(catalog.apply("computer-use-linux", facts))
        // And the other one is unaffected, so the flag is about provisionality and not a
        // blanket off-switch.
        assertIs<ConnectorOutcome.Applied>(catalog.apply("chromium", facts))
    }

    /**
     * Every fact a shipped definition names is one awakener can actually supply.
     *
     * A `{typo}` in a markdown file is copied through untouched by design — that is what lets a
     * connector ship JSON and shell without escaping — so a mistyped placeholder is *invisible*
     * at every other layer. This is the one thing that looks.
     */
    @Test
    fun `no shipped file references a placeholder awakener cannot expand`() {
        val pattern = Regex("""\{([a-z_]{2,20})}""")
        val known = SurfaceFact.ids.toSet()
        val suspicious = mutableListOf<String>()
        catalog.list().connectors.forEach { connector ->
            connector.files.forEach { relative ->
                val text = Path(connector.dir).resolve(relative).readText()
                pattern.findAll(text).map { it.groupValues[1] }.distinct().forEach { candidate ->
                    // Anything shaped like a fact and not one. The bound and the character class
                    // are what keep shell `${VAR}` and JSON out of it; the risk this catches is
                    // `{app-id}` or `{titel}`, not `{}` or `{"x": 1}`.
                    if (candidate !in known) suspicious += "${connector.name}/$relative: {$candidate}"
                }
            }
        }
        assertEquals(emptyList(), suspicious, "a placeholder that would silently never expand")
    }
}
