package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
 * The file is state rather than configuration — awakener writes it, nobody hand-authors it — so
 * unlike `:config` this store loads once at construction and owns the file thereafter. It is
 * still plain, readable JSON on purpose: the memory model's claim is that the durable layer is
 * *inspectable when the agent gets you wrong*, and that has to start with which agent a surface
 * even resolves to.
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
    private val lock = Mutex()

    private val config: Config get() = configStore.config.value

    /**
     * The path is captured once rather than read per call. [RegistryFlags.storePath] reloads
     * like every flag, but a store that silently began writing somewhere else mid-session would
     * strand the bindings it had already made; moving the durable set is a restart, not a flip.
     */
    val path: Path = path ?: RegistryPaths.storePath(config, environment)

    private val loaded = load()

    /**
     * Why the existing file could not be used, if it could not.
     *
     * A store in this condition is **read-only**: it will bind in memory but never write, so a
     * file awakener failed to understand is never clobbered by a rebuilt one. The alternative —
     * starting empty and overwriting — would re-mint every agent on the desktop and abandon
     * every residue file already on disk, which is the single worst thing this module can do.
     */
    val loadError: String? = loaded.error

    /**
     * Entries whose key this build cannot interpret — a bindings file from a newer awakener that
     * knows a surface kind this one does not. Reported, excluded from resolution, and written
     * back untouched so downgrading does not silently delete them.
     */
    val unreadableKeys: List<String> = loaded.unreadable.keys.toList()

    private val state = MutableStateFlow(loaded.bindings)

    override val bindings: StateFlow<Map<SurfaceKey, Binding>> = state.asStateFlow()

    override suspend fun resolve(key: SurfaceKey): Binding? = state.value[key]

    override suspend fun bind(key: SurfaceKey, agent: AgentIdentity?): Binding {
        // Mint outside the lock: it can shell out to spanreed, and holding the write lock across
        // a subprocess would serialise every other surface behind one process spawn.
        val identity = agent
            ?: state.value[key]?.identity
            ?: identities.mint(key, residueLocation(key))
        return lock.withLock {
            val next = state.value[key].merge(identity, config, clock()) { identity }
            state.value = state.value + (key to next)
            write(state.value)
            next
        }
    }

    override suspend fun unbind(key: SurfaceKey): Boolean = lock.withLock {
        val had = key in state.value
        if (had) {
            state.value = state.value - key
            write(state.value)
        }
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
     * Persists every change, on [Dispatchers.IO] because `bind` is on the hotkey path and a
     * blocking write there would stall whatever dispatcher the caller invoked from.
     */
    private suspend fun write(bindings: Map<SurfaceKey, Binding>) = withContext(Dispatchers.IO) {
        if (loaded.error != null) return@withContext
        val file = BindingsFile(
            bindings = bindings.mapKeys { (key, _) -> key.canonical } + loaded.unreadable,
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

    private class Loaded(
        val bindings: Map<SurfaceKey, Binding>,
        val unreadable: Map<String, Binding>,
        val error: String?,
    )

    /**
     * Reads the file once, on the constructing thread. Deliberately not dispatched: construction
     * is not a suspend context, and deferring it would make every read of a not-yet-loaded store
     * racy. It is a one-time startup cost and never on the hotkey path.
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
}
