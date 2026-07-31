package com.monkopedia.awakener.config

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.JarFile

/**
 * Finds the classes that declare flags and loads them, so that enumerating flags reports all
 * of them rather than the ones something happened to touch.
 *
 * A flag exists from the moment its declaring object is class-loaded, and the modules that
 * declare flags depend on `:config` rather than the other way round — so `:config` cannot name
 * them, and an entry point that forgets to name one silently under-reports. Discovery is by
 * convention instead: any class under [PACKAGE] whose name ends in [SUFFIX] and is not [Flags]
 * itself, anywhere on the classpath. A module added by someone who has never read this file is
 * covered, provided it follows the convention its three predecessors already follow.
 *
 * Loading is idempotent — the JVM runs a class initialiser once — so calling this twice does
 * not trip [Flags]' duplicate-key check.
 */
object FlagDiscovery {
    private const val PACKAGE = "com/monkopedia/awakener"
    private const val SUFFIX = "Flags"
    private const val CLASS_EXT = ".class"
    private const val DECLARING = "$SUFFIX$CLASS_EXT"

    /** [Flags] itself, spelt the way a classpath entry holds it. See [declares]. */
    private const val REGISTRY = "$PACKAGE/config/$DECLARING"

    /**
     * @param loaded fully-qualified names of the declaring classes that were initialised.
     * @param problems what could not be read or loaded. Reported, never thrown: a jar that has
     * gone missing should cost the flags it declares, not the whole CLI.
     */
    data class Report(val loaded: List<String>, val problems: List<String>)

    fun discover(
        mode: FlagDiscoveryMode = FlagDiscoveryMode.CLASSPATH,
        declarations: String = "",
        classPath: String = System.getProperty("java.class.path").orEmpty(),
        loader: ClassLoader = FlagDiscovery::class.java.classLoader,
    ): Report {
        val problems = mutableListOf<String>()
        val names = LinkedHashSet<String>()
        names += declarations.split(",").map(String::trim).filter(String::isNotEmpty)

        when (mode) {
            FlagDiscoveryMode.CLASSPATH -> names += scan(classPath, problems)
            FlagDiscoveryMode.DECLARED -> if (names.isEmpty()) {
                problems += "${ConfigFlags.discovery.key} is DECLARED but " +
                    "${ConfigFlags.declarations.key} is empty, so no flags will be reported"
            }
        }

        val loaded = names.filter { name ->
            runCatching { Class.forName(name, true, loader) }
                .onFailure { problems += "$name did not load: $it" }
                .isSuccess
        }
        return Report(loaded, problems)
    }

    /**
     * Classpath entries are followed through jar manifests as well, because a long classpath
     * reaches the JVM as a single jar whose `Class-Path` names the rest — which is how Gradle
     * launches test workers, and would otherwise be a place discovery quietly found nothing.
     */
    private fun scan(classPath: String, problems: MutableList<String>): List<String> {
        val pending = ArrayDeque(
            classPath.split(File.pathSeparator).filter(String::isNotEmpty).map { Candidate(File(it)) },
        )
        val visited = mutableSetOf<String>()
        val found = LinkedHashSet<String>()
        while (pending.isNotEmpty()) {
            val (entry, declaredBy) = pending.removeFirst()
            if (!visited.add(entry.absolutePath)) continue
            runCatching {
                when {
                    entry.isDirectory -> found += entry.classesUnderPackage()
                    entry.isArchive -> found += entry.scanJar(pending, problems)
                    // An extension is a claim, and a file that claims to be an archive and is
                    // not one is broken rather than unrecognised — an interrupted download, a
                    // half-written copy. That is the commonest classpath fault there is, and it
                    // is worth contradicting wherever the entry came from.
                    entry.namedArchive ->
                        problems += "$entry is named as an archive but is not one"
                    // Anything else at the top level is not ours to open — the JVM was handed
                    // that classpath, not us — and a warning per entry would put noise in front
                    // of the ones that mean something. That silence is for the entry we looked
                    // at and recognised as nothing, and for the one there was nothing to look
                    // at; an entry whose stat or read was refused reaches the handler below
                    // instead, because [isArchive] throws rather than answering. `main` was
                    // silent there too, having decided by name and never opened it — reporting
                    // is the deliberate widening, since a failed read is not a recognition and
                    // permission is a fault an operator can fix. A manifest entry is the
                    // opposite case: a jar of ours deliberately named it, so dropping it costs
                    // every flag behind it and reads exactly like a module that declares none.
                    declaredBy != null -> problems += "$declaredBy points at $entry, which is " +
                        "neither a directory nor an archive"
                }
            }.onFailure { problems += "$entry could not be read: $it" }
        }
        return found.toList()
    }

    /** A classpath entry, and — when a jar manifest named it — how to say which one did. */
    private data class Candidate(val file: File, val declaredBy: String? = null)

    /**
     * `PK` and a header type: an entry, an archive with no entries, or a spanned one. A
     * `Class-Path` entry is a URL, and a URL says nothing about the file it names, so a `.war`
     * or an extension-less jar is an archive the JVM's own `URLClassPath` opens — deciding by
     * name would drop the flags inside it. Four bytes settle what the name cannot.
     *
     * The cost is an open and a read of four bytes per classpath entry where there used to be a
     * string compare — a handful of syscalls once per process, against a module's whole flag set.
     */
    private val ARCHIVE_HEADERS = listOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),
        byteArrayOf(0x50, 0x4B, 0x07, 0x08),
    )

    /**
     * What the filesystem says this path is, or null when there is no such path.
     *
     * [File.isFile] and [File.isDirectory] answer `false` both for "it is not that" and for "the
     * lookup was refused", so a jar under a directory the process cannot traverse read as one
     * that is not there. [Files.readAttributes] separates them: a [NoSuchFileException] is the
     * path being absent, and every other failure propagates into [scan]'s handler, which reports
     * the fault it met instead of a fact about a file nothing managed to stat.
     */
    private fun File.attributes(): BasicFileAttributes? = try {
        Files.readAttributes(toPath(), BasicFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        null
    }

    /**
     * Throws rather than answering `false` when the file cannot be read, because "we could not
     * look" is a different fact from "we looked and it is not an archive" — [scan] turns the
     * throw into the problem naming the real fault, which for a permission-denied entry is the
     * only one an operator can act on. The throw covers the stat as well as the read, so a jar
     * behind an unreadable parent reports the permission rather than being read as absent.
     */
    private val File.isArchive: Boolean
        get() = attributes()?.isRegularFile == true && inputStream().use { it.readNBytes(4) }
            .let { header -> ARCHIVE_HEADERS.any { it contentEquals header } }

    /** What the name claims, against what [ARCHIVE_HEADERS] establishes. */
    private val ARCHIVE_EXTENSIONS = listOf("jar", "zip")

    /**
     * A claim worth contradicting, but only once there is a file to contradict: a `.jar` that has
     * been evicted from a cache, deleted with its build directory or left behind as a dangling
     * symlink was never opened, so saying it is not an archive would be the fact never in
     * evidence [isArchive] exists to avoid. What we know about a missing entry is that it is
     * missing, which the manifest path already says and the top level has no standing to.
     */
    private val File.namedArchive: Boolean
        get() = attributes()?.isRegularFile == true &&
            ARCHIVE_EXTENSIONS.any { extension.equals(it, ignoreCase = true) }

    private fun File.classesUnderPackage(): List<String> {
        val root = resolve(PACKAGE)
        if (root.attributes()?.isDirectory != true) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(this).path.replace(File.separatorChar, '/') }
            .filter(::declares)
            .map { it.removeSuffix(CLASS_EXT).replace('/', '.') }
            .toList()
    }

    private fun File.scanJar(
        pending: ArrayDeque<Candidate>,
        problems: MutableList<String>,
    ): List<String> = JarFile(this).use { jar ->
        jar.manifest?.mainAttributes?.getValue("Class-Path")?.split(" ")
            ?.filter { it.isNotEmpty() }
            ?.forEach { entry ->
                val declaredBy = "$this: Class-Path entry '$entry'"
                val target = manifestEntry(entry)
                when {
                    target == null -> problems += "$declaredBy is not a local file"
                    // Confirmed absent, which [File.exists]'s `false` is not: that also covers a
                    // file we were refused a look at, and a real jar under an unreadable parent
                    // took this arm and was reported as missing. What we cannot resolve here is
                    // queued instead, so the fault reported is the one [scan] establishes by
                    // trying to open it.
                    Files.notExists(target.toPath()) ->
                        problems += "$declaredBy points at $target, which does not exist"
                    else -> pending += Candidate(target, declaredBy)
                }
            }
        jar.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("$PACKAGE/") && declares(it) }
            .map { it.removeSuffix(CLASS_EXT).replace('/', '.') }
            .toList()
    }

    /**
     * Whether a class file at [path] — the slash-separated path a classpath entry holds it
     * under — declares flags.
     *
     * [Flags] matches the suffix exactly and registers nothing, so it is excluded; the exclusion
     * is by [REGISTRY], its whole path, because `Flags` is a simple name any package may mint.
     * Comparing simple names dropped a `com.monkopedia.awakener.chrome.Flags` with nothing in
     * [Report.problems] to say so — a module's flags gone, and `list` complete-looking without
     * them.
     */
    private fun declares(path: String) = path.endsWith(DECLARING) && path != REGISTRY

    /**
     * A `Class-Path` entry is a URL resolved against the jar's own URL, not a filesystem path.
     * Gradle writes each one as `jar.parentFile.toURI().relativize(entry.toURI()).rawPath`
     * (`ManifestUtil.constructRelativeClasspathUri`) — the *encoded* path — so a single space
     * anywhere above the build directory would turn a path-based read into silence. Entries no
     * URI parser accepts are still tried as paths, because tools that emit them exist and losing
     * their flags would be the same failure from the other side.
     *
     * @return null when the entry names something that is genuinely not a local file — an
     * `http:` or `jar:` URL, or a `file:` URL on another host — which discovery cannot open.
     */
    private fun File.manifestEntry(entry: String): File? {
        val uri = runCatching { toURI().resolve(URI(entry)) }.getOrNull() ?: return asPath(entry)
        if (uri.scheme != "file") return null
        // `file://localhost/…` is a local path spelt with the authority the JVM's own
        // `URLClassPath` accepts, but `File(URI)` refuses any authority at all.
        uri.authority?.let { authority ->
            val path = uri.path
            return if (authority == "localhost" && path != null) File(path) else null
        }
        // What is left is a `file:` URI that is not a plain path: a `#` or `?` the writer left
        // unencoded parses as a fragment or a query, which truncates the URI's own path to a
        // different file. The raw path is what a writer of an unencoded entry meant, and it
        // beats reporting a path that is in fact local as though it were remote.
        return runCatching { File(uri) }.getOrNull() ?: asPath(entry)
    }

    /** The entry read literally, relative to the jar that named it. */
    private fun File.asPath(entry: String) = absoluteFile.parentFile?.resolve(entry) ?: File(entry)
}
