package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.FlagDiscovery
import com.monkopedia.awakener.config.FlagDiscoveryMode
import com.monkopedia.awakener.config.Flags
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlagDiscoveryTest {
    private val dir = createTempDirectory("awakener-discovery")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val classPath = System.getProperty("java.class.path").orEmpty()

    /**
     * Registration refuses a duplicate key, so anything that can load a declaring class twice
     * would take the CLI down on the second pass. Class initialisers run once, which is what
     * makes discovery safe to repeat — assert it rather than assume it.
     */
    @Test
    fun `discovery is repeatable`() {
        val first = FlagDiscovery.discover(classPath = classPath)
        val countAfterFirst = Flags.all().size
        val second = FlagDiscovery.discover(classPath = classPath)
        assertTrue(first.loaded.isNotEmpty(), "nothing was discovered at all")
        assertEquals(first.loaded, second.loaded)
        assertEquals(countAfterFirst, Flags.all().size, "the second pass registered something new")
        assertEquals(emptyList(), first.problems + second.problems)
    }

    @Test
    fun `the declared list is loaded when scanning is turned off`() {
        val report = FlagDiscovery.discover(
            FlagDiscoveryMode.DECLARED,
            declarations = " com.monkopedia.awakener.futuremodule.FutureFlags ",
            classPath = "",
        )
        assertEquals(listOf("com.monkopedia.awakener.futuremodule.FutureFlags"), report.loaded)
        assertEquals(emptyList(), report.problems)
    }

    /** The mode that can under-report has to say so, since that is the whole defect here. */
    @Test
    fun `declaring nothing under the declared mode is reported`() {
        val report = FlagDiscovery.discover(FlagDiscoveryMode.DECLARED, classPath = classPath)
        assertEquals(emptyList(), report.loaded)
        assertTrue(
            report.problems.single().contains("config.flags.declarations"),
            "an empty declared list passed silently: ${report.problems}",
        )
    }

    /** A name that does not resolve costs its own flags, not the CLI. */
    @Test
    fun `a declaration that cannot be loaded is reported`() {
        val report = FlagDiscovery.discover(
            FlagDiscoveryMode.DECLARED,
            declarations = "com.monkopedia.awakener.nope.MissingFlags",
            classPath = "",
        )
        assertEquals(emptyList(), report.loaded)
        assertTrue(report.problems.single().contains("MissingFlags"), "${report.problems}")
    }

    /**
     * A classpath too long for the command line reaches the JVM as one jar whose manifest names
     * the rest — which is how Gradle launches test workers. Following it is what keeps the scan
     * from finding nothing in exactly the environment the CI runs in.
     */
    @Test
    fun `a classpath handed over through a jar manifest is followed`() {
        val entries = classPath.split(File.pathSeparator).filter { "/wm/build/" in it }
        assertTrue(entries.isNotEmpty(), ":wm is not on the classpath, so this proves nothing")
        val pathingJar = dir.resolve("pathing.jar").toFile()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ")
        }
        JarOutputStream(pathingJar.outputStream(), manifest).close()

        val report = FlagDiscovery.discover(classPath = pathingJar.path)
        assertTrue(
            "com.monkopedia.awakener.wm.WmFlags" in report.loaded,
            "the manifest classpath was not followed: $report",
        )
    }
}
