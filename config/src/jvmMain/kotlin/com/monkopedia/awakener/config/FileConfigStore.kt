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
 * A [ConfigStore] backed by a JSON file, reloaded whenever that file changes on disk.
 *
 * Editing the file by hand is a first-class way to drive awakener, so the file is treated as
 * the source of truth and never rewritten except when [set]/[unset] are called. A malformed
 * file leaves the last good snapshot in place — the alternative, reverting every flag to its
 * default on a stray keystroke, would be a far worse failure against a live desktop.
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

    private val state = MutableStateFlow(load(fallback = Config.EMPTY))

    override val config: StateFlow<Config> = state.asStateFlow()

    /**
     * Watches [path] for changes until [scope] is cancelled.
     *
     * Watches the *parent directory*, because editors overwhelmingly save by writing a
     * temporary file and renaming it over the target — which destroys a watch registered on
     * the file itself, and would make hot reload work exactly once.
     */
    fun watch(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        path.createParentDirectories()
        val dir = path.parent
        val watcher = dir.fileSystem.newWatchService()
        dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        watcher.use {
            while (isActive) {
                val key = watcher.poll(250, TimeUnit.MILLISECONDS) ?: continue
                val touched = key.pollEvents().any { event ->
                    (event.context() as? Path)?.name == path.name
                }
                key.reset()
                if (!touched) continue
                // Rename-over-target arrives as several events; let them settle so we parse
                // the finished file rather than a half-written one.
                delay(RELOAD_DEBOUNCE_MS)
                state.value = load()
            }
        }
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
        val parsed = flag.parseRaw(raw)
        mutate { it + (key to parsed) }
    }

    override suspend fun unset(key: String) {
        require(Flags.byKey(key) != null) { "unknown flag '$key'" }
        mutate { it - key }
    }

    private suspend fun mutate(transform: (Map<String, JsonElement>) -> Map<String, JsonElement>) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val next = transform(readRaw())
                path.createParentDirectories()
                val tmp = path.resolveSibling("${path.name}.tmp")
                Files.writeString(tmp, json.encodeToString(JsonObject(next)))
                // Atomic rename: a reader either sees the old file or the new one, never a
                // partial write. The watch then reloads us from the file we just wrote.
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
     * @param fallback the snapshot to keep if the file cannot be read. Callers after
     * construction pass the currently live snapshot, so a malformed edit changes nothing.
     */
    private fun load(fallback: Config = state.value): Config {
        val fromFile = try {
            readRaw().also { loadError.value = null }
        } catch (e: Exception) {
            loadError.value = "$path: ${e.message}"
            // Keep whatever is already live rather than collapsing to defaults.
            return fallback
        }
        return Config.of(fromFile + environmentOverrides())
    }

    /**
     * Environment overrides, which win over the file. This is what lets a test or a CI job
     * pin behaviour without racing the watcher, and lets a one-off run try a flag without
     * editing the user's real config.
     */
    private fun environmentOverrides(): Map<String, JsonElement> =
        Flags.all().mapNotNull { flag ->
            val raw = environment[flag.key.toEnvName()] ?: return@mapNotNull null
            runCatching { flag.key to flag.parseRaw(raw) }.getOrNull()
        }.toMap()

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 40L

        fun String.toEnvName(): String =
            "AWAKENER_" + uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }
}
