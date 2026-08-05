package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The durable [BindingStore], backed by a JSON file.
 *
 * **The file is the authority, not this object's map.** The file is state rather than
 * configuration — awakener writes it, nobody hand-authors it — but "awakener" is not one
 * process: a long-lived holder and an `awakener-registry forget` beside it is the shape this
 * runs in, and `forget` is the module's advertised repair path. So a store owns the *entry* it
 * is changing, never the whole file: each write re-reads under a lock and puts back what it
 * found plus its own change, and each resolve can be told to re-read too
 * ([RegistryFlags.storeReload]). A store that assumed sole ownership would write its snapshot
 * back over a forget and silently resurrect the binding the user had just removed.
 *
 * The file stays plain, readable JSON on purpose: the memory model's claim is that the durable
 * layer is *inspectable when the agent gets you wrong*, and that has to start with which agent a
 * surface even resolves to.
 */
class FileBindingStore(
    private val configStore: ConfigStore,
    private val identities: AgentIdentities,
    private val environment: Map<String, String> = System.getenv(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Overrides [RegistryFlags.storePath]; for tests and for a run over a scratch file. */
    path: Path? = null,
) : BindingStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val config: Config get() = configStore.config.value

    /**
     * The path is captured once rather than read per call. [RegistryFlags.storePath] reloads
     * like every flag, but a store that silently began writing somewhere else mid-session would
     * strand the bindings it had already made; moving the durable set is a restart, not a flip.
     */
    val path: Path = path ?: RegistryPaths.storePath(config, environment)

    /**
     * Serialises read-modify-write within this JVM. Keyed on the file rather than held per
     * instance because two stores over one file are two writers — and because a second
     * [FileChannel.lock] on a file this process already holds throws rather than waiting.
     */
    private val lock = lockFor(this.path)

    /** The lock file. A getter, so the property is read rather than the constructor parameter. */
    private val lockPath: Path get() = path.resolveSibling("${path.name}.lock")

    @Volatile
    private var snapshot: Loaded = load()

    /**
     * Why the file could not be used, as of the most recent read — construction or refresh.
     *
     * A store in this condition is **read-only**: it will bind in memory but never write, so a
     * file awakener failed to understand is never clobbered by a rebuilt one. The alternative —
     * starting empty and overwriting — would re-mint every agent on the desktop and abandon
     * every residue file already on disk, which is the single worst thing this module can do.
     */
    val loadError: String? get() = snapshot.error

    /**
     * Entries whose key this build cannot interpret — a bindings file from a newer awakener that
     * knows a surface kind this one does not. Reported, excluded from resolution, and written
     * back untouched so downgrading does not silently delete them.
     */
    val unreadableKeys: List<String> get() = snapshot.unreadable.keys.toList()

    private val state = MutableStateFlow(snapshot.bindings)

    override val bindings: StateFlow<Map<SurfaceKey, Binding>> = state.asStateFlow()

    override suspend fun resolve(key: SurfaceKey): Binding? {
        if (config[RegistryFlags.storeReload].onRead) refresh()
        return state.value[key]
    }

    override suspend fun bind(key: SurfaceKey, agent: AgentIdentity?): Binding {
        // What this store is carrying for the surface, captured before any refresh — a refresh
        // is precisely what takes it away, and whether it may be re-established afterwards is
        // [RegistryFlags.forgetConflict]'s call rather than an accident of read ordering.
        val held = state.value[key]?.identity
        // Refreshed before the lock so the mint decision is made against what is on disk now.
        // The mutation re-reads under the lock anyway, so losing a race here costs at worst one
        // unused mint — never a wrong write.
        if (config[RegistryFlags.storeReload].onRead) refresh()
        val couldRevive = held != null && config.holderWinsForget
        // Minted outside the lock: it can shell out to spanreed, and holding the write lock
        // across a subprocess would serialise every other surface behind one process spawn.
        val preMinted = if (agent == null && state.value[key] == null && !couldRevive) {
            identities.mint(key, residueLocation(key))
        } else {
            null
        }
        return mutate {
            val existing = state.value[key]
            // No entry but an identity in hand means this surface was forgotten underneath the
            // holder. Re-read the flag here rather than reusing the value above: a reload can
            // land between the two, and the write is the decision that matters.
            val supplied = agent ?: held.takeIf { existing == null && config.holderWinsForget }
            val next = existing.merge(supplied, config, clock()) {
                preMinted ?: identities.mint(key, residueLocation(key))
            }
            write(state.value + (key to next))
            next
        }
    }

    override suspend fun unbind(key: SurfaceKey): Boolean = mutate {
        val had = key in state.value
        if (had) write(state.value - key)
        had
    }

    override fun residueLocation(key: SurfaceKey): String =
        RegistryPaths.residueLocation(config, path, key).toString()

    /**
     * Creates the residue location so a distiller has somewhere to write and a curious human has
     * somewhere to look. Kept out of [bind] because binding must stay cheap and must not fail on
     * a read-only state directory.
     */
    suspend fun prepareResidue(key: SurfaceKey): Path = withContext(Dispatchers.IO) {
        val location = RegistryPaths.residueLocation(config, path, key)
        when (config[RegistryFlags.residueLayout]) {
            ResidueLayout.PER_KEY_DIR -> Files.createDirectories(location)
            ResidueLayout.PER_KEY_FILE -> {
                location.createParentDirectories()
                if (!location.exists()) Files.createFile(location)
            }
        }
        location
    }

    /**
     * Runs one read-modify-write: this JVM's lock, then the cross-process lock, then a re-read,
     * then [block]. All of it on [Dispatchers.IO], because `bind` is on the hotkey path and
     * blocking file work there would stall whatever dispatcher the caller invoked from.
     *
     * The re-read has to happen *inside* both locks. Reading before taking them is the classic
     * lost update: two processes read the same map, and the second one to write puts back a map
     * that never saw the first one's change.
     */
    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            withFileLock {
                if (config[RegistryFlags.storeReload].onWrite) refreshBlocking()
                block()
            }
        }
    }

    /**
     * Holds an exclusive lock on a file beside the bindings, for the duration of [block].
     *
     * A separate lock file rather than the bindings file itself, because the write replaces that
     * file by rename — a lock on the old inode would stop meaning anything the moment it landed.
     */
    private suspend fun <T> withFileLock(block: suspend () -> T): T {
        val channel = try {
            lockPath.createParentDirectories()
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
        } catch (e: IOException) {
            // A state directory this process cannot create a lock file in is one it cannot write
            // bindings to either. Let the write report that, rather than failing the whole
            // read-modify-write before it has read anything — resolution still works read-only.
            return block()
        }
        return channel.use { open -> open.lock().use { block() } }
    }

    /**
     * Re-reads the file from a read path. On [Dispatchers.IO] because a resolve may arrive on
     * any dispatcher, and under the same mutex as a write so that a refresh cannot land in the
     * middle of one and republish a map the write is on its way to replacing. The cross-process
     * lock is deliberately not taken: the write lands by atomic rename, so a reader sees one
     * whole file or the other, never a torn one.
     */
    private suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock { refreshBlocking() }
    }

    private fun refreshBlocking() {
        val next = load()
        snapshot = next
        // A file that has gone unreadable must not empty out a store that is serving bindings:
        // keep the live map, report the error, and stop writing until it is resolved.
        if (next.error == null) state.value = next.bindings
    }

    /**
     * Republishes [bindings] as this store's live view and persists them — unless the file could
     * not be read, in which case the store is read-only and only the in-memory view moves.
     *
     * Only ever called from [mutate], so it runs under both locks and against a map built on a
     * just-read snapshot — which is what makes the write a change to one entry rather than a
     * wholesale replacement of whatever another process has done since.
     */
    private fun write(bindings: Map<SurfaceKey, Binding>) {
        state.value = bindings
        snapshot = snapshot.copy(bindings = bindings)
        if (snapshot.error != null) return
        val file = BindingsFile(
            bindings = bindings.mapKeys { (key, _) -> key.canonical } + snapshot.unreadable,
        )
        path.createParentDirectories()
        val tmp = path.resolveSibling("${path.name}.tmp")
        Files.writeString(tmp, json.encodeToString(file))
        // fsync before the rename, or the rename can land while the contents it points at are
        // still in page cache: a power loss then leaves an empty file where the bindings were,
        // which reads as "nothing is bound" and re-mints every agent on the desktop.
        FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
        // Atomic rename, same discipline as the config file: a concurrent reader sees the old
        // bindings or the new ones, never a truncated file.
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private data class Loaded(
        val bindings: Map<SurfaceKey, Binding>,
        val unreadable: Map<String, Binding>,
        val error: String?,
    )

    /**
     * Reads the file. Called once on the constructing thread — construction is not a suspend
     * context, and deferring it would make every read of a not-yet-loaded store racy — and
     * thereafter from [refreshBlocking], which is already on [Dispatchers.IO].
     */
    private fun load(): Loaded {
        if (!path.exists()) return Loaded(emptyMap(), emptyMap(), null)
        val parsed = try {
            json.decodeFromString<BindingsFile>(path.readText())
        } catch (e: Exception) {
            return Loaded(emptyMap(), emptyMap(), "$path: ${e.message}")
        }
        if (parsed.version > BindingsFile.CURRENT_VERSION) {
            return Loaded(
                emptyMap(),
                emptyMap(),
                "$path: written by awakener format version ${parsed.version}; this build " +
                    "understands ${BindingsFile.CURRENT_VERSION} and will not rewrite it",
            )
        }
        val readable = mutableMapOf<SurfaceKey, Binding>()
        val unreadable = mutableMapOf<String, Binding>()
        parsed.bindings.forEach { (canonical, binding) ->
            val key = SurfaceKey.parse(canonical)
            if (key == null) unreadable[canonical] = binding else readable[key] = binding
        }
        return Loaded(readable, unreadable, null)
    }

    private companion object {
        /** Reads the same flag the same way everywhere, so the two call sites cannot drift. */
        private val Config.holderWinsForget: Boolean
            get() = get(RegistryFlags.forgetConflict) == ForgetConflict.HOLDER_WINS

        /**
         * One mutex per bindings file, for the lifetime of the process. Bounded by the number of
         * distinct store paths a process opens, which is one in every real deployment.
         */
        private val locks = ConcurrentHashMap<Path, Mutex>()

        fun lockFor(path: Path): Mutex =
            locks.computeIfAbsent(path.toAbsolutePath().normalize()) { Mutex() }
    }
}
