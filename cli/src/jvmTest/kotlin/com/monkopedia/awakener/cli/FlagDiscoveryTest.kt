package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.FlagDiscovery
import com.monkopedia.awakener.config.FlagDiscoveryMode
import com.monkopedia.awakener.config.Flags
import java.io.File
import java.net.URI
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
     *
     * The entries are URLs relative to the jar, not paths. Gradle writes each one as
     * `jar.parentFile.toURI().relativize(entry.toURI()).rawPath`
     * (`org.gradle.api.internal.classpath.ManifestUtil.constructRelativeClasspathUri`), and
     * `getRawPath` is the *encoded* form — so one space anywhere in the path arrives as `%20`.
     * The fixture is built with that same expression rather than by hand, so it cannot drift
     * back to a format nothing emits; the `%20` assertion is what makes the drift visible.
     */
    @Test
    fun `a percent-encoded manifest classpath is followed`() {
        val pathingJar = dir.resolve("pathing.jar").toFile()
        val entries = stagedUnder("with space").map { target ->
            pathingJar.parentFile.toURI().relativize(target.toURI()).rawPath
        }
        assertTrue(
            entries.all { "%20" in it },
            "the fixture is not in the form Gradle emits, so it proves nothing: $entries",
        )

        val report = discoverThrough(pathingJar, entries)
        assertTrue(
            "com.monkopedia.awakener.wm.WmFlags" in report.loaded,
            "the manifest classpath was not followed: $report",
        )
        assertEquals(emptyList(), report.problems)
    }

    /** The other spec-legal form, and what several tools other than Gradle write. */
    @Test
    fun `a manifest classpath of file URIs is followed`() {
        val entries = stagedUnder("with space").map { it.toURI().toString() }
        assertTrue(entries.all { it.startsWith("file:") }, "$entries")

        val report = discoverThrough(dir.resolve("pathing.jar").toFile(), entries)
        assertTrue(
            "com.monkopedia.awakener.wm.WmFlags" in report.loaded,
            "the manifest classpath was not followed: $report",
        )
        assertEquals(emptyList(), report.problems)
    }

    /**
     * Not every tool obeys the spec, and a raw path with an unescaped `%` is not a URI at all.
     * Rejecting those would trade one silent loss of flags for another, so they stay supported.
     */
    @Test
    fun `a manifest classpath of raw paths is still followed`() {
        val entries = stagedUnder("100%dir").map { it.path }
        assertTrue(
            entries.all { runCatching { URI(it) }.isFailure },
            "the fixture parses as a URI, so it is not exercising the fallback: $entries",
        )

        val report = discoverThrough(dir.resolve("pathing.jar").toFile(), entries)
        assertTrue(
            "com.monkopedia.awakener.wm.WmFlags" in report.loaded,
            "a raw-path manifest classpath was dropped: $report",
        )
        assertEquals(emptyList(), report.problems)
    }

    /**
     * The failure this whole module exists to prevent, one level down: an entry that resolves
     * to nothing costs every flag behind it, so it has to say so rather than shrink `list`.
     */
    @Test
    fun `a manifest classpath entry that resolves to nothing is reported`() {
        val report = discoverThrough(
            dir.resolve("pathing.jar").toFile(),
            listOf("gone/nowhere.jar", "http://elsewhere.invalid/wm.jar"),
        )
        assertEquals(emptyList(), report.loaded)
        assertTrue(
            report.problems.any { "nowhere.jar" in it } &&
                report.problems.any { "elsewhere.invalid" in it },
            "an unresolvable Class-Path entry vanished instead of reporting: ${report.problems}",
        )
    }

    /**
     * Copies of the real `:wm` classpath entries, under a subdirectory whose name is what forces
     * the encoding. Copies rather than the originals because the entry format under test is
     * relative to the pathing jar, and the originals are not below it.
     */
    private fun stagedUnder(subdirectory: String): List<File> {
        val sources = classPath.split(File.pathSeparator).filter { "/wm/build/" in it }.map(::File)
        assertTrue(sources.isNotEmpty(), ":wm is not on the classpath, so this proves nothing")
        val home = dir.resolve(subdirectory).toFile()
        return sources.mapIndexed { index, source ->
            File(home, "$index-${source.name}").also { source.copyRecursively(it) }
        }
    }

    private fun discoverThrough(pathingJar: File, entries: List<String>): FlagDiscovery.Report {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ")
        }
        JarOutputStream(pathingJar.outputStream(), manifest).close()
        return FlagDiscovery.discover(classPath = pathingJar.path)
    }
}
