package com.monkopedia.awakener.cli

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
     * Discovery can only find what is on the classpath, so the other half of the guarantee is
     * the build: `:cli` depends on every module in the build, computed from the build itself.
     * If a new module is included in `settings.gradle.kts` this starts covering it too.
     */
    @Test
    fun `every module in the build reaches the CLI classpath`() {
        val modules = System.getProperty("awakener.modules").orEmpty()
            .split(",").filter { it.isNotEmpty() }
        assertTrue(modules.isNotEmpty(), "the build did not say which modules exist")
        val classPath = System.getProperty("java.class.path").orEmpty()
        modules.forEach { module ->
            assertTrue(
                classPath.contains("/$module/build/"),
                "module :$module is not on the CLI classpath, so its flags cannot be found",
            )
        }
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
