package com.monkopedia.awakener.config

import java.nio.file.Path
import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking

/**
 * `awakener-config` — inspect and change flags without a rebuild.
 *
 * Without a *restart* is the half that is not true yet. `FileConfigStore.watch` is what would
 * make a `set` here land in an already-running awakener, and nothing calls it, because nothing
 * in the build outlives one operation (#43). So today a `set` takes effect on the next process
 * that starts. This used to claim the opposite, which is worth correcting out loud rather than
 * quietly: it is the property the flags-first working model rests on.
 *
 * The commands live here, next to the config machinery, but the entry point lives in `:cli`:
 * this module cannot see the modules that declare flags, so a `main` here would enumerate an
 * empty registry and report that none of them exist.
 */
object ConfigCli {
    fun defaultPath() = Path(
        System.getenv("AWAKENER_CONFIG")
            ?: "${System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config"}" +
            "/awakener/config.json",
    )

    /**
     * Opens the config store with every flag on the classpath registered.
     *
     * Three steps that are only correct together, which is why every JVM entry point calls this
     * instead of spelling them out. The bootstrap flags have to be registered before the store
     * parses anything, or `config.flags.*`'s own overrides go unread. Discovery then loads every
     * other module's declarations. And the snapshot the first parse produced was read against a
     * registry holding only the bootstrap flags — so it called every other module's key unknown,
     * and has to be read again now that they exist.
     *
     * Doing only the first step is a quiet wrong rather than a loud one: the store still answers,
     * with a snapshot that disowns every key outside the caller's own module. `awakener-registry`
     * did exactly that until #45, in a repo whose stated reason for `:cli` existing is that a
     * `main` which cannot see the other modules reports their flags as nonexistent.
     */
    fun bootstrap(
        path: Path = defaultPath(),
        environment: Map<String, String> = System.getenv(),
        out: (String) -> Unit,
    ): FileConfigStore {
        Flags.requireLoaded(ConfigFlags)
        val store = FileConfigStore(path, environment)
        val config = store.config.value
        val report = FlagDiscovery.discover(
            config[ConfigFlags.discovery],
            config[ConfigFlags.declarations],
        )
        report.problems.forEach { out("warning: flag discovery: $it") }
        store.reload()
        return store
    }

    fun run(args: Array<String>, store: FileConfigStore, out: (String) -> Unit): Int {
        val config = store.config.value
        store.loadError.value?.let { out("warning: $it (showing last good values)") }
        config.problems.forEach { out("warning: ${it.key}: ${it.reason}") }

        return when (args.firstOrNull() ?: "list") {
            "list" -> {
                val width = Flags.all().maxOfOrNull { it.key.length } ?: 0
                Flags.all().forEach { flag ->
                    val value = config.renderValue(flag)
                    val marker = if (config.isOverridden(flag)) "*" else " "
                    out("$marker ${flag.key.padEnd(width)}  $value")
                    out("  ${" ".repeat(width)}  ${flag.description}")
                    if (flag.choices.isNotEmpty()) {
                        out("  ${" ".repeat(width)}  one of: ${flag.choices.joinToString(", ")}")
                    }
                    // Printed for the same reason `choices` is: a range that only exists in the
                    // declaring module is a range the person editing the file finds out about
                    // by getting it wrong.
                    if (flag.requirement.isNotEmpty()) {
                        out("  ${" ".repeat(width)}  must be ${flag.requirement}")
                    }
                }
                out("")
                // "on the next run", not "live": no awakener process outlives one operation
                // yet, so nothing is holding a snapshot for a change to reach. Telling the
                // operator otherwise sends them looking for a process that ignored them.
                out("* = overridden; edit ${defaultPath()} or use `set`. Applies on the next run.")
                0
            }

            "get" -> {
                val key = args.getOrNull(1) ?: return usage(out, "get needs a flag key")
                val flag = Flags.byKey(key) ?: return usage(out, "unknown flag '$key'")
                out(config.renderValue(flag))
                0
            }

            // Both write arms catch Exception rather than IllegalArgumentException. The write
            // path touches the filesystem, so the reachable failures are not all about the
            // argument — an unreadable file, a directory that has gone — and the one guarantee
            // this CLI owes a hand-edited config is that it answers instead of printing a stack
            // trace. A refusal that names the file is a report; an escaping IOException is not.
            "set" -> {
                val key = args.getOrNull(1) ?: return usage(out, "set needs a flag key")
                val raw = args.getOrNull(2) ?: return usage(out, "set needs a value")
                try {
                    runBlocking { store.set(key, raw) }
                    out("$key = $raw")
                    0
                } catch (e: Exception) {
                    // `e.toString()` rather than a stand-in like "invalid value": catching
                    // broadly also catches things that are not about the argument, and naming
                    // one of those as a bad value would be a wrong answer rather than a vague
                    // one. A message-less exception at least says what it was.
                    usage(out, e.message ?: e.toString())
                }
            }

            "unset" -> {
                val key = args.getOrNull(1) ?: return usage(out, "unset needs a flag key")
                try {
                    runBlocking { store.unset(key) }
                    out("$key reset to default")
                    0
                } catch (e: Exception) {
                    usage(out, e.message ?: "unknown flag")
                }
            }

            else -> usage(out, "unknown command '${args[0]}'")
        }
    }

    private fun <T> Config.renderValue(flag: Flag<T>): String = get(flag).toString()

    private fun usage(out: (String) -> Unit, error: String): Int {
        out("error: $error")
        out("usage: awakener-config [list | get <key> | set <key> <value> | unset <key>]")
        return 2
    }
}
