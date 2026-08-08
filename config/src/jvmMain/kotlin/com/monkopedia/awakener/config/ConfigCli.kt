package com.monkopedia.awakener.config

import java.nio.file.Path
import kotlin.io.path.Path
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * `awakener-config` — inspect and change flags without a rebuild.
 *
 * **Without a *restart* is the half that is true of one process** (#43). `FileConfigStore.watch`
 * is what makes an edit land in something already running, and `watch` below is its caller: it
 * follows the file and reports every change for as long as it lives. Every *other* entry point
 * — `awakener-invoke`, `awakener-registry`, and every other command here — is one-shot, so a
 * `set` reaches those on their next run. This file used to claim live reload outright while
 * nothing called the watch at all, and then claimed the flat opposite once that was found; both
 * were a sentence where the accurate answer is "in which process".
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
                listing(config, out)
                out("")
                // "on the next run", not "live", and it is still the honest word for what a
                // *one-shot* command sees: every other entry point reads the file once and
                // exits, so nothing of theirs is holding a snapshot for a change to reach.
                // `watch` is the one process that is, and it is named here rather than left
                // for the operator to discover, because "does anything running pick this up"
                // is the question this footer exists to answer.
                out(
                    "* = overridden; edit ${store.path} or use `set`. Applies on the next run " +
                        "of a one-shot command; `awakener-config watch` follows it live.",
                )
                0
            }

            // The command that makes a flag flip observable while something is running, and
            // the build's first entry point that outlives one operation (#43). Deliberately
            // an *inspector* and not a daemon: it holds no compositor connection, binds
            // nothing, drives nothing, and its whole effect is lines on stdout. That is what
            // keeps it on the right side of the design brief's rule against unattended
            // action — a person starts it, watches it, and stops it.
            "watch" -> runBlocking { follow(store, out) }

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
                    // After the write, not before: this is the one warning that cannot be known
                    // until the write has tried. A `set` that could not take the cross-process
                    // lock still succeeded, and still may have overwritten another one — the
                    // whole point of #88 is that the loss is silent, so the report is the fix.
                    store.lockError.value?.let { out("warning: $it") }
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
                    store.lockError.value?.let { out("warning: $it") }
                    0
                } catch (e: Exception) {
                    usage(out, e.message ?: "unknown flag")
                }
            }

            else -> usage(out, "unknown command '${args[0]}'")
        }
    }

    /** Every flag, its value, and what it is for — the body of `list`, and of `watch`'s ALL. */
    private fun listing(config: Config, out: (String) -> Unit) {
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
            // declaring module is a range the person editing the file finds out about by
            // getting it wrong.
            if (flag.requirement.isNotEmpty()) {
                out("  ${" ".repeat(width)}  must be ${flag.requirement}")
            }
        }
    }

    /**
     * Prints the values in effect and then keeps printing, as the file changes, for as long as
     * the process lives.
     *
     * **This is the one place `FileConfigStore.watch` is called, and the reason it now means
     * something.** The mechanism had been complete and tested since #87 with no caller, because
     * every entry point read the file once and exited — so the working model this repo runs on,
     * where a flag is flipped against a running system rather than a rebuilt one, was a property
     * of the design and of nothing that ran (#43). It is observable here.
     *
     * It ends when the process does. There is no timeout and no iteration count: an inspector
     * that stopped on its own would be answering a question nobody asked, and the operator
     * already has the two answers that matter, `^C` and closing the terminal. Under
     * [ConfigFlags.watchEnabled] set false it does not follow at all — it prints what is in
     * effect, says why it is not following, and exits, which is the same shape one-shot rather
     * than a different command.
     */
    private suspend fun follow(store: FileConfigStore, out: (String) -> Unit): Int {
        val startup = store.config.value
        listing(startup, out)
        out("")
        if (!startup[ConfigFlags.watchEnabled]) {
            out(
                "not following ${store.path}: ${ConfigFlags.watchEnabled.key} is false, so " +
                    "these values are what this process would hold for its whole life",
            )
            return 0
        }
        return coroutineScope {
            store.watch(this)
            // After the watch is launched rather than before, so a reader who sees this line
            // and then edits the file is not racing a registration that has not happened. It
            // is not a guarantee — registration completes on another thread — but printing it
            // first would make the line an outright lie.
            out("following ${store.path}; changes to it are reported below")
            launch { reportValues(store, out) }
            launch { reportProblems(store, out) }
            awaitCancellation()
        }
    }

    /**
     * Reports each replaced snapshot, in whichever of the two shapes [ConfigFlags.watchReport]
     * asks for — read out of the *new* snapshot, so an edit that changes the report style is
     * reported in the style it asks for rather than in the one it replaced.
     *
     * A re-read that changed no value prints nothing under CHANGES. That is not a dropped
     * report: a reload is total, so the store publishes a fresh snapshot whenever it reads the
     * file at all, including on an overflow that turned out to have lost nothing.
     */
    private suspend fun reportValues(store: FileConfigStore, out: (String) -> Unit) {
        var previous = store.config.value
        store.config.collect { next ->
            // Identity: `Config` defines no equals, and the question here is "did the store
            // re-read", which is exactly whether this is a different object.
            if (next === previous) return@collect
            when (next[ConfigFlags.watchReport]) {
                WatchReport.CHANGES -> Flags.all().forEach { flag ->
                    val before = previous.renderValue(flag)
                    val after = next.renderValue(flag)
                    if (before != after) out("$after  ${flag.key}  (was $before)")
                }

                WatchReport.ALL -> listing(next, out)
            }
            // Reported after the values, because a problem is about one of the lines above it.
            // Only what is new: a file with a standing typo in it would otherwise repeat that
            // typo on every unrelated edit until it was fixed.
            (next.problems - previous.problems.toSet()).forEach {
                out("warning: ${it.key}: ${it.reason}")
            }
            previous = next
        }
    }

    /**
     * Reports the store's load failures as they arrive and as they clear.
     *
     * Separate from [reportValues] because the two are genuinely different events: a file that
     * will not parse, or one that has gone, deliberately leaves the snapshot alone — that is
     * the keep-last-good rule — so there is no new snapshot for a value report to hang off, and
     * following the values alone would show the operator nothing at all in the one case where
     * what they can read and what is in effect have come apart.
     */
    private suspend fun reportProblems(store: FileConfigStore, out: (String) -> Unit) {
        var previous = store.loadError.value
        store.loadError.collect { next ->
            if (next == previous) return@collect
            previous = next
            out(if (next == null) "the file reads again" else "warning: $next")
        }
    }

    private fun <T> Config.renderValue(flag: Flag<T>): String = get(flag).toString()

    private fun usage(out: (String) -> Unit, error: String): Int {
        out("error: $error")
        out(
            "usage: awakener-config [list | watch | get <key> | set <key> <value> | " +
                "unset <key>]",
        )
        out("  watch follows the file and reports every change until the process is stopped")
        return 2
    }
}
