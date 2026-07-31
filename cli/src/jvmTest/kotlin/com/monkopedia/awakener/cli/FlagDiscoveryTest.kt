package com.monkopedia.awakener.cli

import com.monkopedia.awakener.config.FlagDiscovery
import com.monkopedia.awakener.config.FlagDiscoveryMode
import com.monkopedia.awakener.config.Flags
import java.io.File
import java.net.URI
import java.nio.file.Files
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
        // A directory staged unreadable would otherwise refuse the delete too, and the fixture
        // would outlive the run. Top-down, so a directory is reopened before the walk lists it.
        dir.toFile().walkTopDown().forEach {
            it.setReadable(true, false)
            it.setExecutable(true, false)
        }
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

    /**
     * The registry is one class — `com.monkopedia.awakener.config.Flags` — and it is excluded for
     * being that class. Excluding it by simple name excluded every class that shares the name,
     * which no package has to ask permission for: a module holding one flag set calls the object
     * `Flags`, and lost its flags with nothing in [FlagDiscovery.Report.problems] to say so.
     * [com.monkopedia.awakener.futuremodule.Flags] is that shape, and it is referenced by nothing.
     */
    @Test
    fun `a holder named Flags outside the config package declares flags`() {
        val report = FlagDiscovery.discover(classPath = classPath)
        assertTrue(
            "com.monkopedia.awakener.futuremodule.Flags" in report.loaded,
            "a declarer was dropped for sharing the registry's simple name: $report",
        )
        assertTrue(
            "com.monkopedia.awakener.config.Flags" !in report.loaded,
            "the registry itself was loaded as though it declared flags: $report",
        )
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
     * The other property, on the side that had no guard. Top-level silence is deliberate for an
     * entry making no claim, but a file *named* `.jar` that will not open is broken rather than
     * unrecognised — and reading content to decide what an entry is must not turn the broken
     * ones into the `loaded=[], problems=[]` this module exists to abolish. A newly discovered
     * way for an archive to be broken belongs in [brokenArchives], not in a test of its own.
     */
    @Test
    fun `no top-level classpath entry claiming to be an archive is dropped in silence`() {
        val silent = brokenArchives()
            .mapValues { (_, entry) -> FlagDiscovery.discover(classPath = entry.path) }
            .filterValues { it.loaded.isEmpty() && it.problems.isEmpty() }
        assertEquals(emptyMap(), silent, "these classpath entries vanished without a word")
    }

    /**
     * And of the broken ones, the entry that could not be read at all has to say so rather than
     * be diagnosed. "It is not an archive" is a fact we never established — we never got to
     * look — and permissions are the one fault here an operator can actually fix, so naming the
     * wrong one costs the fix as surely as silence would. True on both paths, and true whether
     * the permission that stops us is the jar's own or a directory's above it: the second shape
     * used to be reported as "does not exist", about a real jar that is exactly where the
     * manifest says it is.
     */
    @Test
    fun `an archive that cannot be read is reported as unreadable, not as something else`() {
        val pathingJar = dir.resolve("pathing.jar").toFile()
        for ((shape, entry) in unreadableArchives()) {
            val reports = mapOf(
                "top level" to FlagDiscovery.discover(classPath = entry.path),
                "manifest" to discoverThrough(pathingJar, listOf(entry.path)),
            )
            for ((path, report) in reports) {
                assertTrue(
                    report.problems.singleOrNull()?.contains("could not be read") == true,
                    "$path: $shape was not reported as unreadable: ${report.problems}",
                )
            }
        }
    }

    /**
     * The mirror of [brokenArchives], and the commonest classpath fault of the lot: an evicted
     * cache, a deleted build directory, a moved artifact. Nothing opened these, so nothing may
     * say what is inside them — "is named as an archive but is not one" is the same fact never in
     * evidence that the unreadable case is about, one cell over. What each path may say differs
     * because what each path knows differs: at the top level the entry is the JVM's own and we
     * looked at nothing, so silence; under a manifest a jar of ours named it, and "does not
     * exist" is both true and the whole of what we found out.
     */
    @Test
    fun `a named archive that is not there is not diagnosed as a broken one`() {
        val pathingJar = dir.resolve("pathing.jar").toFile()
        for ((shape, entry) in missingArchives()) {
            assertEquals(
                FlagDiscovery.Report(emptyList(), emptyList()),
                FlagDiscovery.discover(classPath = entry.path),
                "top level: $shape was diagnosed by a reader that never opened it",
            )
            val problem = discoverThrough(pathingJar, listOf(entry.path)).problems.single()
            assertTrue(
                "does not exist" in problem,
                "manifest: $shape was not reported as missing: $problem",
            )
        }
    }

    /**
     * The widening in the other direction, asserted because it is a choice rather than an
     * oversight. Top-level silence covers the entry we recognised as nothing; it cannot cover one
     * we never managed to read, because deciding what an entry is now means opening it, and a
     * failed open leaves no answer rather than a negative one. `main` was silent here having
     * never looked — and permission is the one fault on this list an operator can fix.
     */
    @Test
    fun `a top-level entry that cannot be read is reported though it claims nothing`() {
        val unreadable = dir.resolve("unnamed-and-unreadable").toFile()
        unreadable.writeText("not an archive, and not readable either")
        unreadable.setReadable(false, false)
        assertTrue(
            !unreadable.canRead(),
            "$unreadable is still readable, so this proves nothing — run as root?",
        )
        val report = FlagDiscovery.discover(classPath = unreadable.path)
        assertTrue(
            report.problems.singleOrNull()?.contains("could not be read") == true,
            "an entry nothing could read said nothing: ${report.problems}",
        )
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
     * Reading an entry to decide what it *is* must not make its content its *identity*. Entries
     * are still de-duplicated by absolute path, and this is the case where collapsing them by
     * content would cost a whole module's flags: a `Class-Path` entry is a URL resolved against
     * the jar that names it, so two byte-identical pathing jars in two directories name two
     * different files. Identical bytes, different declarations — both have to be followed.
     */
    @Test
    fun `byte-identical classpath entries in different directories are both followed`() {
        stagedArchive("first/lib/module.jar")
        stagedArchive("second/lib/module.jar", module = "registry")
        val first = writePathingJar(
            dir.resolve("first/pathing.jar").toFile(),
            listOf("lib/module.jar"),
        )
        val second = first.copyTo(dir.resolve("second/pathing.jar").toFile(), overwrite = true)
        assertTrue(
            first.readBytes().contentEquals(second.readBytes()),
            "the two entries do not have identical content, so this proves nothing",
        )

        val report = FlagDiscovery.discover(
            classPath = listOf(first, second).joinToString(File.pathSeparator) { it.path },
        )
        assertEquals(
            listOf(
                "com.monkopedia.awakener.wm.WmFlags",
                "com.monkopedia.awakener.registry.RegistryFlags",
            ),
            report.loaded,
            "two entries with identical content did not both contribute: $report",
        )
        assertEquals(emptyList(), report.problems)
    }

    /**
     * Every shape of `Class-Path` entry known to reach this code. Each one either names the real
     * `:wm` classpath or is unusable — none of them is legitimately empty, so a case reporting
     * neither flags nor a problem has lost something.
     *
     * A directory that opens fine and genuinely holds no `com/monkopedia/awakener` package is
     * the one entry that may report nothing at all, and it is deliberately not a row here: it
     * was read successfully and is empty, which is not the ambiguity this property is about.
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
        ) + (brokenArchives() + missingArchives()).mapValues { (_, file) -> file.path }
    }

    /**
     * The ways a file that calls itself an archive fails to be one. Deciding an entry's type by
     * reading it rather than by its name is what this change is for, and the failure mode it
     * risks is exactly the one being fixed: a read that goes wrong answering "not an archive"
     * and vanishing. These are the commonest classpath faults there are — an interrupted
     * download, a half-written copy, a permission-mangled cache — and none may pass in silence.
     *
     * A `.jar` that is simply *not there* is [missingArchives] instead, because nothing opened
     * it: it is not an archive that failed to be one, and the two must not collapse into one
     * message. It is also the one member of this family the top level may pass over in silence,
     * which is why it cannot be a row here.
     *
     * [unreadableArchives] is spliced in rather than listed, because those rows are real archives
     * and the fault is only ever the permission — they need an assertion naming the message, not
     * just the non-silence this corpus drives.
     */
    private fun brokenArchives(): Map<String, File> {
        val home = dir.resolve("broken").toFile()
        home.mkdirs()
        return mapOf(
            "a zero-byte .jar" to File(home, "zero.jar").apply { writeBytes(ByteArray(0)) },
            "a .jar truncated past its header" to File(home, "truncated.jar")
                .apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x0A, 0x00)) },
            "a .jar that is not an archive at all" to
                File(home, "text.jar").apply { writeText("not an archive") },
        ) + unreadableArchives()
    }

    /**
     * Real archives that discovery is not allowed to reach, by the two routes a permission can
     * stop it: the jar's own mode, and a directory above it. Nothing here is malformed — the
     * flags are inside, intact — so every diagnosis except the permission is a fact not in
     * evidence, and "does not exist" is the one that sends an operator after a file that is
     * exactly where it should be.
     *
     * The locked directory is reopened before it is staged into, so that calling this twice in
     * one test builds the fixture rather than tripping over the previous call's lock.
     */
    private fun unreadableArchives(): Map<String, File> {
        val unreadable = stagedArchive("unreadable/unreadable.jar")
        unreadable.setReadable(false, false)
        dir.resolve("locked").toFile().apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
        val behindLockedDirectory = stagedArchive("locked/inner.jar")
        val locked = behindLockedDirectory.parentFile
        locked.setReadable(false, false)
        locked.setExecutable(false, false)
        assertTrue(
            !unreadable.canRead() && !behindLockedDirectory.exists(),
            "the fixtures still grant access, so the permission cases prove nothing — run as root?",
        )
        return mapOf(
            "a .jar that cannot be read" to unreadable,
            "a .jar behind a directory that cannot be read" to behindLockedDirectory,
        )
    }

    /**
     * The ways a `.jar` named on a classpath is not there at all — the file gone, or the symlink
     * standing where it used to be. Spliced into [manifestCorpus] because a jar of ours naming a
     * file that has gone has lost every flag behind it; the top-level half is pinned separately,
     * since there the honest report is the one `main` gave, which is none.
     */
    private fun missingArchives(): Map<String, File> {
        val home = dir.resolve("missing").toFile()
        home.mkdirs()
        val dangling = File(home, "dangling.jar")
        Files.createSymbolicLink(dangling.toPath(), File(home, "deleted.jar").toPath())
        assertTrue(
            Files.isSymbolicLink(dangling.toPath()) && !dangling.exists(),
            "$dangling is not a dangling symlink, so it proves nothing",
        )
        return mapOf(
            "a .jar that is not there" to File(home, "gone.jar"),
            "a .jar that is a dangling symlink" to dangling,
        )
    }

    /** A real module jar under a name and place that say nothing true about what is inside. */
    private fun stagedArchive(path: String, module: String = "wm"): File {
        val source = classPath.split(File.pathSeparator).map(::File)
            .first { "/$module/build/" in it.path && it.isFile }
        val staged = dir.toFile().resolve(path)
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

    private fun discoverThrough(pathingJar: File, entries: List<String>): FlagDiscovery.Report =
        FlagDiscovery.discover(classPath = writePathingJar(pathingJar, entries).path)

    private fun writePathingJar(file: File, entries: List<String>): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ")
        }
        file.parentFile?.mkdirs()
        JarOutputStream(file.outputStream(), manifest).close()
        return file
    }
}
