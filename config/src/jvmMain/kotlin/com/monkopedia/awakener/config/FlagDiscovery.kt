package com.monkopedia.awakener.config

import java.io.File
import java.util.jar.JarFile

/**
 * Finds the classes that declare flags and loads them, so that enumerating flags reports all
 * of them rather than the ones something happened to touch.
 *
 * A flag exists from the moment its declaring object is class-loaded, and the modules that
 * declare flags depend on `:config` rather than the other way round — so `:config` cannot name
 * them, and an entry point that forgets to name one silently under-reports. Discovery is by
 * convention instead: any class under [PACKAGE] whose name ends in [SUFFIX], anywhere on the
 * classpath. A module added by someone who has never read this file is covered, provided it
 * follows the convention its three predecessors already follow.
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
                    entry.isArchive -> found += entry.scanJar(pending)
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
            .filter { it.isFile && it.name.endsWith(DECLARING) }
            .map { it.relativeTo(this).path.removeSuffix(CLASS_EXT).replace(File.separatorChar, '.') }
            .toList()
    }

    private fun File.scanJar(pending: ArrayDeque<File>): List<String> = JarFile(this).use { jar ->
        jar.manifest?.mainAttributes?.getValue("Class-Path")?.split(" ")
            ?.filter { it.isNotEmpty() }
            ?.forEach { pending += parentFile?.resolve(it) ?: File(it) }
        jar.entries().asSequence()
            .filter { it.name.startsWith("$PACKAGE/") && it.name.endsWith(DECLARING) }
            .map { it.name.removeSuffix(CLASS_EXT).replace('/', '.') }
            .toList()
    }
}
