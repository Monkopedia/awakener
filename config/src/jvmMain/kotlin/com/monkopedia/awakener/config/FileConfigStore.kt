package com.monkopedia.awakener.config

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.util.concurrent.ConcurrentHashMap
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
 *
 * **Writing is not single-writer and never was** (#88). Two `awakener-config set` runs are two
 * processes over one file, and so is a `set` beside an editor saving it. Every write is
 * therefore a read-modify-write under two locks — see [mutate] — the same discipline
 * `FileBindingStore` holds one module over for the same defect (#50, fixed in #59). The two
 * stores share a directory and a lock-file convention deliberately: someone will read one for
 * guidance on the other.
 */
class FileConfigStore(
    private val path: Path,
    private val environment: Map<String, String> = System.getenv(),
) : ConfigStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Serialises read-modify-write within this JVM.
     *
     * Keyed on the file rather than held per instance, because two stores over one file are two
     * writers — and because a second [FileChannel.lock] on a file this process already holds
     * throws rather than waiting. The key is textual, so two spellings of one path (through a
     * symlink, say) would take two mutexes and then hit exactly that exception; normalising
     * harder would mean touching the filesystem per store, for a case no deployment has.
     */
    private val lock = lockFor(path)

    /**
     * The lock file, beside the config rather than the config itself: the write lands by rename,
     * so a lock held on the target's inode stops meaning anything the moment it does.
     */
    private val lockPath: Path get() = path.resolveSibling("${path.name}.lock")

    /**
     * The staging file the write is renamed from, named per process.
     *
     * A single fixed `<file>.tmp` is a race of its own: two processes writing it at once
     * interleave, and one can rename it away between the other's write and its own rename,
     * which surfaces as `NoSuchFileException` on a path that plainly exists — and can leave the
     * config unparseable, which for a hand-authored file is worse than a lost update. The lock
     * below makes that unreachable on the normal path, but the unlocked degradation in
     * [withFileLock] is a real path, and a defect merely masked by another mechanism is still a
     * defect.
     */
    private val tmpPath: Path
        get() = path.resolveSibling("${path.name}.${ProcessHandle.current().pid()}.tmp")

    /**
     * The most recent load failure, if the file is currently unreadable or malformed.
     *
     * Declared before [state] on purpose: [load] writes to it, and [state]'s initialiser calls
     * [load].
     */
    val loadError: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Why the last write went ahead without the cross-process lock, if it did.
     *
     * The same shape as [loadError] and for the same reason: a store in this condition still
     * answers, but it is degraded in a way only it can see. Writing with no cross-process
     * exclusion *is* #88 — a second `set` putting its own view of the file back over this one —
     * so a store doing it silently would reproduce the property that made the defect worth
     * fixing, which is that nothing tells the operator to look. Cleared as soon as a write does
     * get the lock. `awakener-config` prints it beside its other warnings.
     */
    val lockError: MutableStateFlow<String?> = MutableStateFlow(null)

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
        // OVERFLOW is deliberately absent: it is delivered whether or not it was registered
        // for, so naming it here would suggest it is opt-in. [reactionTo] is where it is
        // handled.
        dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        watcher.use {
            while (isActive) {
                // A timed poll rather than a blocking take: cancelling [scope] does not
                // interrupt the thread a WatchService is parked on, and the close that would
                // wake it is the one this `use` performs on the way out.
                val key = watcher.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS) ?: continue
                val events = key.pollEvents()
                key.reset()
                when (reactionTo(events, path.name, config.value[ConfigFlags.watchOverflow])) {
                    Reaction.IGNORE -> Unit

                    // Reported through the same channel an unreadable file uses, because it is
                    // the same situation for the reader: the values in effect are no longer
                    // guaranteed to be the ones they can look at.
                    Reaction.REPORT_LOSS -> loadError.value =
                        "$dir: the change queue overflowed and events were lost, so $path may " +
                        "no longer hold the values in effect; set " +
                        "${ConfigFlags.watchOverflow.key}=REREAD to re-read on an overflow"

                    Reaction.REREAD -> {
                        delay(config.value[ConfigFlags.watchDebounceMs])
                        reloadFromWatch()
                    }
                }
            }
        }
    }

    /** What one batch of watch events means for the snapshot. */
    internal enum class Reaction { IGNORE, REREAD, REPORT_LOSS }

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
     *
     * Under the same mutex as a write, so that a reload cannot land in the middle of one and
     * publish a snapshot the write is on its way to replacing. Both read the whole file, so
     * neither can publish a *partial* view — what the mutex rules out is the ordering, where a
     * reload that read the newer file publishes before a write that read the older one.
     */
    private suspend fun reloadFromWatch() = lock.withLock {
        if (!path.exists() && config.value[ConfigFlags.watchMissingFile] == MissingFile.KEEP) {
            // Reported for the same reason an unreadable file is: the values in effect are no
            // longer the ones anybody can look at, and nothing else would say so.
            loadError.value = "$path no longer exists; keeping the values last read from it"
            return@withLock
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

    /**
     * Runs one read-modify-write: this JVM's mutex, then the cross-process lock, then the
     * re-read, then the write. All of it on [Dispatchers.IO], because `set` can be called from
     * anywhere and blocking file work on the caller's dispatcher is not this store's to spend.
     *
     * **The re-read has to happen inside both locks**, which is why [currentForWrite] is called
     * here rather than by the caller. Reading before taking them is the classic lost update:
     * two processes read the same file, and the second to write puts back a map that never saw
     * the first one's change. `set` already re-read before this — it just did so unlocked,
     * which is a narrower window and the same defect (#88).
     */
    private suspend fun mutate(transform: (Map<String, JsonElement>) -> Map<String, JsonElement>) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                withFileLock {
                    val next = transform(currentForWrite())
                    path.createParentDirectories()
                    val tmp = tmpPath
                    Files.writeString(tmp, json.encodeToString(JsonObject(next)))
                    // Atomic rename: a reader either sees the old file or the new one, never a
                    // partial write. This is also why [watch] is registered on the directory —
                    // the target's inode does not survive this, so the store's own writes are
                    // the first thing a watch bound to the file rather than the name would
                    // miss. The reload below is this process's own; a watch elsewhere sees the
                    // rename.
                    Files.move(
                        tmp,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    state.value = load()
                }
            }
        }
    }

    /**
     * Holds an exclusive lock on [lockPath] for the duration of [block].
     *
     * **Both ways of not getting the lock are the same situation from here**: a config directory
     * that will not take a lock file, and a filesystem that will not lock one (an NFS home
     * without `lockd`). What happens then is [ConfigFlags.lockRequired]'s call. By default the
     * write goes ahead unlocked, because a config that cannot be written is worse than one
     * whose concurrent writes can race, and the write is atomic by rename over a per-process
     * staging file — so the unlocked path can lose an update but cannot tear the file. **That
     * state is reported through [lockError] rather than assumed.**
     *
     * `OverlappingFileLockException` is *not* caught either way: that one means this process
     * already holds the lock, which is a bug in the mutex above rather than a fact about the
     * filesystem, and it should be loud.
     */
    private fun <T> withFileLock(block: () -> T): T {
        val channel = try {
            lockPath.createParentDirectories()
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            return degrade("cannot create the lock file", e, block)
        }
        return channel.use { open ->
            val held = try {
                open.lock()
            } catch (e: IOException) {
                return@use degrade("the filesystem will not lock it", e, block)
            }
            lockError.value = null
            try {
                block()
            } finally {
                held.release()
            }
        }
    }

    /**
     * Records that this store is about to write without cross-process exclusion, and does it —
     * unless [ConfigFlags.lockRequired], in which case [cause] is the answer instead.
     */
    private fun <T> degrade(why: String, cause: IOException, block: () -> T): T {
        if (config.value[ConfigFlags.lockRequired]) throw cause
        lockError.value = "$lockPath: $why (${cause.message}); writing without cross-process " +
            "exclusion, so a concurrent `awakener-config set` can be lost"
        return block()
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

    internal companion object {
        /**
         * How long [watch] blocks before checking whether it has been cancelled. Left a
         * constant rather than made a flag: an event wakes the poll immediately, so this
         * delays no reload — all it bounds is how long after `scope.cancel()` the watcher
         * notices, which nothing yet depends on because nothing yet calls [watch].
         */
        const val POLL_INTERVAL_MS = 250L

        /**
         * What one batch of watch events means for a file called [name].
         *
         * Separated from [watch] because the interesting case cannot be provoked to order: an
         * `OVERFLOW` is the kernel's queue giving up, and a test that tried to induce one would
         * be measuring how fast the machine is. Pulled out, the decision is a total function of
         * the events and the flag, and `FileConfigStoreWatchTest` states it directly.
         *
         * The name filter is what keeps the `.tmp` and `.lock` files [mutate] writes beside the
         * target — and everything else sharing the directory — from provoking a re-read. An
         * **overflow carries no context path**, so it fails that filter: it is a statement
         * about the queue rather than about a file. Dropping it on that basis is what #43's
         * last comment recorded, and it is the wrong way round — an overflow says events were
         * lost, which is precisely when a re-read is most warranted, so the watch went quiet
         * exactly where it had most reason to read. A re-read is total, so one repairs any
         * number of dropped events; [ConfigFlags.watchOverflow] is the switch for a deployment
         * that would rather be told than reloaded.
         *
         * A batch holding both is a re-read either way, and then says nothing about the
         * overflow: the read that the named event earned is the same read the overflow would
         * have asked for, and it leaves nothing outstanding to report.
         */
        internal fun reactionTo(
            events: List<WatchEvent<*>>,
            name: String,
            onOverflow: WatchOverflow,
        ): Reaction = when {
            events.any { (it.context() as? Path)?.name == name } -> Reaction.REREAD
            events.none { it.kind() === OVERFLOW } -> Reaction.IGNORE
            onOverflow == WatchOverflow.REREAD -> Reaction.REREAD
            else -> Reaction.REPORT_LOSS
        }

        /**
         * One mutex per config file, for the lifetime of the process. Bounded by the number of
         * distinct config paths a process opens, which is one in every real deployment.
         */
        private val locks = ConcurrentHashMap<Path, Mutex>()

        fun lockFor(path: Path): Mutex =
            locks.computeIfAbsent(path.toAbsolutePath().normalize()) { Mutex() }

        fun String.toEnvName(): String =
            "AWAKENER_" + uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }
}
