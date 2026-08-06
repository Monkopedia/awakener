package com.monkopedia.awakener.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A [ConfigStore] backed by a JSON file, re-read whenever that file changes on disk *for as
 * long as somebody is running [watch]*.
 *
 * Editing the file by hand is a first-class way to drive awakener, so the file is treated as
 * the source of truth and never rewritten except when [set]/[unset] are called. A malformed
 * file leaves the last good snapshot in place — the alternative, reverting every flag to its
 * default on a stray keystroke, would be a far worse failure against a live desktop. A file
 * that disappears gets the same treatment by default; see [ConfigFlags.watchMissingFile].
 *
 * **Nothing in the build calls [watch] yet** (#43). Every entry point today is one-shot: it
 * builds a store, reads the file once and exits, so no snapshot is replaced under a running
 * process. The mechanism is here, and tested, for the first entry point that outlives a
 * single operation — which is why code above this module reads flags out of the snapshot per
 * operation rather than caching them at construction. That is written against the property
 * this class provides, not against the process that exists.
 */
class FileConfigStore(
    private val path: Path,
    private val environment: Map<String, String> = System.getenv(),
) : ConfigStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    /**
     * The most recent load failure, if the file is currently unreadable or malformed.
     *
     * Declared before [state] on purpose: [load] writes to it, and [state]'s initialiser calls
     * [load].
     */
    val loadError: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * The last contents [load] managed to read out of [path].
     *
     * Kept separate from the snapshot because the snapshot has environment overrides merged into
     * it, and a rewrite built from that would bake a one-off `AWAKENER_*` variable into the
     * user's file permanently. Declared before [state] for the same reason as [loadError].
     */
    private var lastGoodFile: Map<String, JsonElement> = emptyMap()

    private val state = MutableStateFlow(load(fallback = Config.EMPTY))

    override val config: StateFlow<Config> = state.asStateFlow()

    /**
     * Watches [path] for changes until [scope] is cancelled, replacing the snapshot each time
     * it changes. Nothing calls this yet; see the class documentation.
     *
     * Watches the *parent directory*, and has to: a `WatchService` registers directories only,
     * and `Path.register` on a regular file raises `NotDirectoryException` rather than
     * watching it. That is not merely an API shape to route around — it is the shape the job
     * needs. A save is normally an atomic rewrite: write a temporary file, rename it over the
     * target. [mutate] below does exactly that, so the target's identity does not survive its
     * own writes, and anything bound to the file rather than to its name would stop seeing
     * them. Both halves are pinned by tests in `FileConfigStoreWatchTest`.
     *
     * The events for one save are several, so they are given a settling window before the
     * read — [ConfigFlags.watchDebounceMs], read from the live snapshot every time round, so
     * that changing it applies to the watcher that is already running rather than to the next
     * one. That is the same rule this module asks of everyone else.
     */
    fun watch(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        path.createParentDirectories()
        val dir = path.parent
        val watcher = dir.fileSystem.newWatchService()
        dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        watcher.use {
            while (isActive) {
                // A timed poll rather than a blocking take: cancelling [scope] does not
                // interrupt the thread a WatchService is parked on, and the close that would
                // wake it is the one this `use` performs on the way out.
                val key = watcher.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS) ?: continue
                val touched = key.pollEvents().any { event ->
                    // By name, so the `.tmp` file [mutate] writes beside the target — and
                    // everything else sharing the directory — does not provoke a re-read.
                    (event.context() as? Path)?.name == path.name
                }
                key.reset()
                if (!touched) continue
                delay(config.value[ConfigFlags.watchDebounceMs])
                reloadFromWatch()
            }
        }
    }

    /**
     * The re-read [watch] performs, which differs from [reload] in one case: a file that is no
     * longer there.
     *
     * [load] treats an absent file as an empty one, which is right for a store being built —
     * an unconfigured system has no file and must read defaults. It is not automatically right
     * for a *running* one, where the file existed a moment ago: a delete-then-write save
     * leaves it absent for as long as the writer takes, and reading defaults for every flag in
     * that gap is the failure the malformed-file rule already exists to prevent. Which of the
     * two it is is [ConfigFlags.watchMissingFile]'s to say.
     */
    private fun reloadFromWatch() {
        if (!path.exists() && config.value[ConfigFlags.watchMissingFile] == MissingFile.KEEP) {
            // Reported for the same reason an unreadable file is: the values in effect are no
            // longer the ones anybody can look at, and nothing else would say so.
            loadError.value = "$path no longer exists; keeping the values last read from it"
            return
        }
        state.value = load()
    }

    /**
     * Re-reads the file against the currently registered flags.
     *
     * The first snapshot is built while the store is being constructed, which is before flag
     * discovery has run — so every key in it belongs to a flag that does not exist yet, and is
     * reported as unknown. An entry point calls this once discovery is done.
     */
    fun reload() {
        state.value = load()
    }

    override suspend fun set(key: String, raw: String) {
        val flag = requireNotNull(Flags.byKey(key)) { "unknown flag '$key'" }
        val parsed = flag.parseChecked(raw)
        mutate { it + (key to parsed) }
    }

    override suspend fun unset(key: String) {
        require(Flags.byKey(key) != null) { "unknown flag '$key'" }
        mutate { it - key }
    }

    private suspend fun mutate(transform: (Map<String, JsonElement>) -> Map<String, JsonElement>) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val next = transform(currentForWrite())
                path.createParentDirectories()
                val tmp = path.resolveSibling("${path.name}.tmp")
                Files.writeString(tmp, json.encodeToString(JsonObject(next)))
                // Atomic rename: a reader either sees the old file or the new one, never a
                // partial write. This is also why [watch] is registered on the directory —
                // the target's inode does not survive this, so the store's own writes are the
                // first thing a watch bound to the file rather than the name would miss.
                // The reload below is this process's own; a watch elsewhere sees the rename.
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                state.value = load()
            }
        }
    }

    private fun readRaw(): Map<String, JsonElement> {
        if (!path.exists()) return emptyMap()
        return json.parseToJsonElement(path.readText()).jsonObject.toMap()
    }

    /**
     * What a write starts from: whatever is on disk, so a `set` preserves the keys around the
     * one it changes rather than the subset this process happens to hold.
     *
     * The read is guarded because everything else here is. [load] has always kept the last good
     * snapshot when the file cannot be read, so the same unreadable file that is a warning on
     * the read path used to reach `main` from the write path as an unhandled `IOException` — a
     * stack trace where the contract promises a report. Only the *malformed* case survived, and
     * by accident: `SerializationException` extends `IllegalArgumentException`, which
     * [ConfigCli] happens to catch. Neither half is decided by luck now.
     */
    private fun currentForWrite(): Map<String, JsonElement> = try {
        readRaw()
    } catch (e: Exception) {
        loadError.value = "$path: ${e.message}"
        when (config.value[ConfigFlags.unreadableWrite]) {
            // The remedy names the *environment*, not `set`: recording this flag with `set`
            // would have to read the file, which is the thing that cannot be read. Advertising
            // the flag without saying that sent the operator round a circle.
            UnreadableWrite.REFUSE -> throw IllegalStateException(
                "$path could not be read (${e.message}), so it cannot be rewritten without " +
                    "discarding whatever it holds. Fix the file, or re-run with " +
                    "${ConfigFlags.unreadableWrite.key.toEnvName()}=REWRITE to replace it with " +
                    "the last contents this process read.",
                e,
            )

            UnreadableWrite.REWRITE -> lastGoodFile
        }
    }

    /**
     * @param fallback the snapshot to keep if the file cannot be read. Callers after
     * construction pass the currently live snapshot, so a malformed edit changes nothing.
     */
    private fun load(fallback: Config = state.value): Config {
        val base = try {
            readRaw().also { loadError.value = null; lastGoodFile = it }
        } catch (e: Exception) {
            loadError.value = "$path: ${e.message}"
            // Keep whatever is already live rather than collapsing to defaults — but keep
            // *building a snapshot*, rather than returning the old one whole. Returning early
            // skipped the environment entirely, so on an unreadable file no `AWAKENER_*`
            // variable applied at all: a store built in that state read every flag at its
            // default however the environment was set. That is worst for the one flag whose
            // whole job is to be readable when the file is not — `config.store.unreadable_write`
            // could not be set on the path that consults it, so its escape hatch was inert in
            // `awakener-config` while the refusal message advertised it. The environment is the
            // only source still standing here, which is exactly why it has to be read.
            fallback.overrides()
        }
        val environment = environmentOverrides()
        return Config.of(base + environment.values, environment.problems) { key ->
            environment.names[key]
        }
    }

    /**
     * Environment overrides, which win over the file. This is what lets a test or a CI job
     * pin behaviour without racing the watcher, and lets a one-off run try a flag without
     * editing the user's real config.
     *
     * Only a value that will not *parse* is dropped here, and dropping it is now said out loud:
     * an unusable variable that vanished silently left the file's value in effect while the
     * environment appeared to override it, which is a difference nothing could see. A value that
     * parses goes through as an override whatever it says, so anything out of range is reported
     * and degraded by [Config.of] like every other stored value rather than by a second rule.
     *
     * [Applied.names] is what lets a problem about one of these say so. A key reported without
     * it sends the reader to grep a config file that does not contain the value.
     */
    private fun environmentOverrides(): Applied {
        val values = mutableMapOf<String, JsonElement>()
        val names = mutableMapOf<String, String>()
        val problems = mutableListOf<Config.Problem>()
        Flags.all().forEach { flag ->
            val name = flag.key.toEnvName()
            val raw = environment[name] ?: return@forEach
            runCatching { flag.parseRaw(raw) }
                .onSuccess { values[flag.key] = it; names[flag.key] = name }
                .onFailure {
                    problems += Config.Problem(
                        flag.key,
                        "$name='$raw' does not parse (${it.message}), so it was ignored",
                    )
                }
        }
        return Applied(values, names, problems)
    }

    /**
     * What the environment contributed: the overrides, which variable supplied each, and what
     * had to be dropped to get there.
     *
     * Returned rather than filled in through an out-parameter, so this reads like the rest of
     * the file — everything else here answers with what it computed.
     */
    private class Applied(
        val values: Map<String, JsonElement>,
        val names: Map<String, String>,
        val problems: List<Config.Problem>,
    )

    private companion object {
        /**
         * How long [watch] blocks before checking whether it has been cancelled. Left a
         * constant rather than made a flag: an event wakes the poll immediately, so this
         * delays no reload — all it bounds is how long after `scope.cancel()` the watcher
         * notices, which nothing yet depends on because nothing yet calls [watch].
         */
        const val POLL_INTERVAL_MS = 250L

        fun String.toEnvName(): String =
            "AWAKENER_" + uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }
}
