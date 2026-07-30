package com.monkopedia.awakener.config

import kotlin.io.path.Path
import kotlinx.coroutines.runBlocking

/**
 * `awakener-config` — inspect and change flags without a rebuild or a restart.
 *
 * The daemon watches the same file, so a `set` here takes effect in a running awakener.
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
                }
                out("")
                out("* = overridden; edit ${defaultPath()} or use `set`. Changes apply live.")
                0
            }

            "get" -> {
                val key = args.getOrNull(1) ?: return usage(out, "get needs a flag key")
                val flag = Flags.byKey(key) ?: return usage(out, "unknown flag '$key'")
                out(config.renderValue(flag))
                0
            }

            "set" -> {
                val key = args.getOrNull(1) ?: return usage(out, "set needs a flag key")
                val raw = args.getOrNull(2) ?: return usage(out, "set needs a value")
                try {
                    runBlocking { store.set(key, raw) }
                    out("$key = $raw")
                    0
                } catch (e: IllegalArgumentException) {
                    usage(out, e.message ?: "invalid value")
                }
            }

            "unset" -> {
                val key = args.getOrNull(1) ?: return usage(out, "unset needs a flag key")
                try {
                    runBlocking { store.unset(key) }
                    out("$key reset to default")
                    0
                } catch (e: IllegalArgumentException) {
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
