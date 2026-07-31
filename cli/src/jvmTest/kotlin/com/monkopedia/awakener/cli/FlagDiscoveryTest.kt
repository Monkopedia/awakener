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
     * `Class-Path` entries are URLs, and a URL says nothing about the file it names — so a real
     * archive called `.war`, or one with no extension at all, is a jar the JVM's own
     * `URLClassPath` opens happily. Deciding by name dropped it, and dropped it in silence.
     */
    @Test
    fun `a manifest classpath entry is followed whatever the archive is called`() {
        val report = discoverThrough(
            dir.resolve("pathing.jar").toFile(),
            listOf(stagedArchive("wm.war").path, stagedArchive("wm-no-extension").path),
        )
        assertTrue(
            "com.monkopedia.awakener.wm.WmFlags" in report.loaded,
            "an archive not named .jar or .zip was dropped: $report",
        )
        assertEquals(emptyList(), report.problems)
    }

    /**
     * And when the entry is not an archive at all there is nothing to follow — which still has
     * to be said, because a jar deliberately named it and every flag behind it is now missing.
     */
    @Test
    fun `a manifest classpath entry that is not an archive is reported`() {
        val junk = dir.resolve("notes.txt").toFile().apply { writeText("not an archive") }
        val report = discoverThrough(dir.resolve("pathing.jar").toFile(), listOf(junk.path))
        assertEquals(emptyList(), report.loaded)
        assertTrue(
            report.problems.singleOrNull()?.contains("notes.txt") == true,
            "an unusable Class-Path entry vanished instead of reporting: ${report.problems}",
        )
    }

    /**
     * The other half of that trade: the top-level classpath is the JVM's own, whatever is on it
     * was not put there by a jar of ours, and a warning per entry would bury the ones that mean
     * something. Silence there is deliberate, so pin it rather than let it drift into noise.
     */
    @Test
    fun `a top-level classpath entry that is not an archive stays quiet`() {
        val junk = dir.resolve("notes.txt").toFile().apply { writeText("not an archive") }
        val report = FlagDiscovery.discover(classPath = junk.path)
        assertEquals(FlagDiscovery.Report(emptyList(), emptyList()), report)
    }

    /**
     * `file://localhost/…` is the authority form of a local path — the JVM's own `URLClassPath`
     * accepts it — but `File(URI)` refuses any authority at all, so it was reported as "not a
     * local file", which it plainly is.
     */
    @Test
    fun `a file URL naming localhost resolves`() {
        val entries = stagedUnder("lib").map { "file://localhost${it.path}" }
        val report = discoverThrough(dir.resolve("pathing.jar").toFile(), entries)
        assertTrue(
            "com.monkopedia.awakener.wm.WmFlags" in report.loaded,
            "a local path spelt with an authority was refused: $report",
        )
        assertEquals(emptyList(), report.problems)
    }

    /**
     * A raw path through a directory called `odd#name` parses as a URI with a fragment, so the
     * URI's own path is the entry cut short — a different file. The raw-path fallback is what
     * the writer of an unencoded entry meant, and it beats reporting a local path as remote.
     */
    @Test
    fun `a raw path containing a fragment character resolves`() {
        val entries = stagedUnder("odd#name").map { it.path }
        val report = discoverThrough(dir.resolve("pathing.jar").toFile(), entries)
        assertTrue(
            "com.monkopedia.awakener.wm.WmFlags" in report.loaded,
            "a raw path containing '#' was dropped: $report",
        )
        assertEquals(emptyList(), report.problems)
    }

    /**
     * The property, asserted as a property rather than case by case. `loaded=[], problems=[]`
     * is the one outcome a caller cannot tell apart from "this module declares no flags", so no
     * manifest entry may produce it: either the flags behind it arrive, or it says why not.
     * A newly discovered corner belongs in [manifestCorpus], not in a test of its own.
     */
    @Test
    fun `no manifest classpath entry is dropped in silence`() {
        val pathingJar = dir.resolve("pathing.jar").toFile()
        val silent = manifestCorpus()
            .mapValues { (_, entry) -> discoverThrough(pathingJar, listOf(entry)) }
            .filterValues { it.loaded.isEmpty() && it.problems.isEmpty() }
        assertEquals(emptyMap(), silent, "these Class-Path entries vanished without a word")
    }

    /**
     * Every shape of `Class-Path` entry known to reach this code. Each one either names the real
     * `:wm` classpath or is unusable — none of them is legitimately empty, so a case reporting
     * neither flags nor a problem has lost something.
     */
    private fun manifestCorpus(): Map<String, String> {
        val jar = stagedUnder("lib").first()
        return mapOf(
            "an absolute file: URI" to jar.toURI().toString(),
            "a percent-encoded relative path" to
                dir.toFile().toURI().relativize(jar.toURI()).rawPath,
            "a raw relative path" to jar.path,
            "a path through a directory named with '#'" to stagedUnder("odd#name").first().path,
            "a path through a directory named with '?'" to stagedUnder("odd?name").first().path,
            "a path through a directory named with '%'" to stagedUnder("100%dir").first().path,
            "a file: URL naming localhost" to "file://localhost${jar.path}",
            "a file: URL naming another host" to "file://elsewhere.invalid${jar.path}",
            "an http: URL" to "http://elsewhere.invalid/wm.jar",
            "a jar: URL" to "jar:file:${jar.path}!/",
            "a relative path to nothing" to "gone/nowhere.jar",
            "an archive called .war" to stagedArchive("wm.war").path,
            "an archive with no extension" to stagedArchive("wm-no-extension").path,
            "a file that is not an archive" to
                dir.resolve("notes.txt").toFile().apply { writeText("not an archive") }.path,
            "something that is neither file nor directory" to "/dev/null",
        )
    }

    /** The real `:wm` jar under a name that says nothing true about what is inside it. */
    private fun stagedArchive(name: String): File {
        val source = classPath.split(File.pathSeparator).map(::File)
            .first { "/wm/build/" in it.path && it.isFile }
        val staged = dir.resolve("archives").toFile().resolve(name)
        staged.parentFile.mkdirs()
        source.copyTo(staged, overwrite = true)
        return staged
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
