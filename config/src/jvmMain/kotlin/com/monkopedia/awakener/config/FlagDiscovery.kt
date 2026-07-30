package com.monkopedia.awakener.config

import java.io.File
import java.net.URI
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
        val pending = ArrayDeque(classPath.split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File))
        val visited = mutableSetOf<String>()
        val found = LinkedHashSet<String>()
        while (pending.isNotEmpty()) {
            val entry = pending.removeFirst()
            if (!visited.add(entry.absolutePath)) continue
            runCatching {
                when {
                    entry.isDirectory -> found += entry.classesUnderPackage()
                    // Anything else on a classpath is not ours to open, and complaining about
                    // it would put noise in front of the warnings that mean something.
                    entry.isArchive -> found += entry.scanJar(pending, problems)
                }
            }.onFailure { problems += "$entry could not be read: $it" }
        }
        return found.toList()
    }

    private val File.isArchive: Boolean
        get() = isFile && (extension.equals("jar", true) || extension.equals("zip", true))

    private fun File.classesUnderPackage(): List<String> {
        val root = resolve(PACKAGE)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && declares(it.name) }
            .map { it.relativeTo(this).path.removeSuffix(CLASS_EXT).replace(File.separatorChar, '.') }
            .toList()
    }

    private fun File.scanJar(
        pending: ArrayDeque<File>,
        problems: MutableList<String>,
    ): List<String> = JarFile(this).use { jar ->
        jar.manifest?.mainAttributes?.getValue("Class-Path")?.split(" ")
            ?.filter { it.isNotEmpty() }
            ?.forEach { entry ->
                val target = manifestEntry(entry)
                when {
                    target == null ->
                        problems += "$this: Class-Path entry '$entry' is not a local file"
                    !target.exists() ->
                        problems += "$this: Class-Path entry '$entry' points at $target, " +
                            "which does not exist"
                    else -> pending += target
                }
            }
        jar.entries().asSequence()
            .filter { it.name.startsWith("$PACKAGE/") && declares(it.name.substringAfterLast('/')) }
            .map { it.name.removeSuffix(CLASS_EXT).replace('/', '.') }
            .toList()
    }

    /** [Flags] itself matches the suffix exactly but declares nothing, so it is not a declarer. */
    private fun declares(fileName: String) =
        fileName.endsWith(DECLARING) && fileName != DECLARING

    /**
     * A `Class-Path` entry is a URL resolved against the jar's own URL, not a filesystem path.
     * Gradle writes each one as `jar.parentFile.toURI().relativize(entry.toURI()).rawPath`
     * (`ManifestUtil.constructRelativeClasspathUri`) — the *encoded* path — so a single space
     * anywhere above the build directory would turn a path-based read into silence. Entries no
     * URI parser accepts are still tried as paths, because tools that emit them exist and losing
     * their flags would be the same failure from the other side.
     *
     * @return null when the entry names something that is not a local file, such as an `http:`
     * URL, which discovery has no way to open.
     */
    private fun File.manifestEntry(entry: String): File? {
        val uri = runCatching { toURI().resolve(URI(entry)) }.getOrNull()
            ?: return absoluteFile.parentFile?.resolve(entry) ?: File(entry)
        if (uri.scheme != "file") return null
        return runCatching { File(uri) }.getOrNull()
    }
}
