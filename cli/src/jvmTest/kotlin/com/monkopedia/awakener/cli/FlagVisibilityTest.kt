package com.monkopedia.awakener.cli

import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `awakener-config list` is meant to be how Jason finds out what he can tune. A list that
 * silently omits a module's flags is worse than no list: it says those knobs do not exist.
 *
 * Every key here is a string literal on purpose. Naming `WmFlags.dockSide` instead would load
 * the declaring object as a side effect of running the test, which is exactly the accident
 * that made the old CLI look like it worked.
 */
class FlagVisibilityTest {
    private val dir = createTempDirectory("awakener-cli")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun list(vararg args: String, file: String? = null): List<String> {
        val path = dir.resolve("config.json")
        file?.let { path.writeText(it) }
        val lines = mutableListOf<String>()
        val code = AwakenerConfigCli.run(
            if (args.isEmpty()) arrayOf("list") else arrayOf(*args),
            path,
            lines::add,
            environment = emptyMap(),
        )
        assertEquals(0, code, lines.joinToString("\n"))
        return lines
    }

    private fun List<String>.keys(): Set<String> =
        mapNotNull { it.removePrefix("*").trim().substringBefore("  ").ifEmpty { null } }.toSet()

    @Test
    fun `list reports the flags of every module, not just the ones already loaded`() {
        val keys = list().keys()
        assertTrue("wm.dock.side" in keys, "no :wm flags in $keys")
        assertTrue("wm.events.enabled" in keys, "only some :wm flags in $keys")
        assertTrue("registry.key.window_identity" in keys, "no :registry flags in $keys")
        assertTrue("registry.agent.spanreed_command" in keys, "only some :registry flags in $keys")
    }

    /**
     * The guarantee has to be structural, not a one-time fixup: whoever adds the fourth module
     * will not know this problem ever existed. [com.monkopedia.awakener.futuremodule.FutureFlags]
     * is referenced by nothing, so the only way this key can appear is discovery finding it.
     */
    @Test
    fun `a module nothing knows about still gets its flags listed`() {
        assertTrue("futuremodule.knob" in list().keys(), "a module added later is invisible")
    }

    /**
     * And a module that calls its holder `Flags` — the name the registry itself uses, which no
     * package outside `:config` has any reason to avoid. The declarer is
     * [com.monkopedia.awakener.futuremodule.Flags], referenced by nothing either, so this key
     * appears only if discovery kept it.
     */
    @Test
    fun `a holder sharing the registry's name still gets its flags listed`() {
        assertTrue(
            "futuremodule.plain_named_knob" in list().keys(),
            "a declarer named Flags is invisible to list",
        )
    }

    /**
     * Discovery can only find what is on the classpath, so the other half of the guarantee is
     * the build: `:cli` depends on every module in the build. If a new module is included in
     * `settings.gradle.kts` this starts covering it too.
     *
     * **The expectation comes from `settings.gradle.kts`, not from the build script under
     * test**, and that is the whole content of this test rather than a detail of it.
     * `cli/build.gradle.kts` derives `:cli`'s dependencies *and* the `awakener.modules`
     * property it publishes from one expression, so while this read only that property a
     * module the expression failed to enumerate was absent from both sides at once: the loop
     * iterated a set that was correct by construction and the docstring above promised
     * something it could not keep (#142). Measured on this branch — with that expression
     * replaced by a hand-written three-module list and a genuine fourth module included, this
     * test fails, and before the rewrite it passed while `awakener-config list` could not see
     * that module's flags.
     */
    @Test
    fun `every module in the build reaches the CLI classpath`() {
        val included = includedProjects()
        // A control on the parse, not on the build. An expectation derived by reading a file
        // fails *open*: a moved file or a regex that stops matching yields an empty set and
        // every assertion below passes for the reason this test was rewritten to stop passing
        // for. `:cli` is the one project that must be in there — this class is compiled into
        // it — so its absence indicts the parse rather than the wiring.
        assertTrue(
            ":cli" in included,
            "settings.gradle.kts parsed to $included, which does not include the module this " +
                "test is compiled into, so the parse is what is wrong and not the build",
        )

        val expected = included - ":cli"
        val declared = System.getProperty("awakener.modules").orEmpty()
            .split(",").filter { it.isNotEmpty() }.toSet()
        assertEquals(
            expected,
            declared,
            "cli/build.gradle.kts builds :cli's dependencies from the same list it publishes " +
                "as awakener.modules, so a module missing from it is a module whose flags " +
                "`awakener-config list` cannot report",
        )

        val classPath = System.getProperty("java.class.path").orEmpty()
        expected.forEach { module ->
            val dir = module.removePrefix(":").replace(':', '/')
            assertTrue(
                classPath.contains("/$dir/build/"),
                "module $module is not on the CLI classpath, so its flags cannot be found",
            )
        }
    }

    /**
     * Every `include(...)` in the root settings file, as Gradle project paths. Text rather than
     * anything Gradle computed, on purpose: the point is a source of truth that
     * `cli/build.gradle.kts` has no way to narrow.
     */
    private fun includedProjects(): Set<String> {
        val settings = assertNotNull(
            System.getProperty("awakener.settings"),
            "the build did not say where settings.gradle.kts is",
        )
        val text = Path(settings).readText()
        return Regex("""\binclude\s*\(([^)]*)\)""").findAll(text)
            .flatMap { call -> Regex(""""([^"]+)"""").findAll(call.groupValues[1]) }
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * The user-visible half of the same bug: with only some flags registered, every key in the
     * config file that belongs to an unloaded module is reported as one no flag declares — so
     * a correct config file reads as a broken one, and `set` on those keys is refused.
     */
    @Test
    fun `a value set for another module's flag is honoured, not flagged as unknown`() {
        val lines = list(file = """{"wm.dock.size_ppt": 55}""")
        assertTrue(
            lines.none { it.startsWith("warning:") },
            "a valid config file produced warnings: ${lines.filter { it.startsWith("warning:") }}",
        )
        assertTrue(
            lines.any { it.startsWith("*") && it.contains("wm.dock.size_ppt") && it.endsWith("55") },
            "the override is not shown as applied: ${lines.filter { "size_ppt" in it }}",
        )
    }
}
