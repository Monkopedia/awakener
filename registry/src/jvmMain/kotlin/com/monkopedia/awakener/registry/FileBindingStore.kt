package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    val loadError: StateFlow<String?> = MutableStateFlow(loaded.error).asStateFlow()

    /**
     * Entries whose key this build cannot interpret — a bindings file from a newer awakener that
     * knows a surface kind this one does not. Reported, excluded from resolution, and written
     * back untouched so downgrading does not silently delete them.
     */
    val unreadableKeys: List<String> = loaded.unreadable.keys.toList()

    private val state = MutableStateFlow(loaded.bindings)

    private var pendingWrite = false

    override val bindings: StateFlow<Map<SurfaceKey, Binding>> = state.asStateFlow()

    override suspend fun resolve(key: SurfaceKey): Binding? = state.value[key]

    override suspend fun bind(key: SurfaceKey, agent: AgentIdentity?): Binding {
        // Mint outside the lock: it can shell out to spanreed, and holding the write lock across
        // a subprocess would serialise every other surface behind one process spawn.
        val identity = agent ?: state.value[key]?.identity ?: identities.mint(key)
        return lock.withLock {
            val next = state.value[key].merge(identity, config, clock()) { identity }
            state.value = state.value + (key to next)
            persist()
            next
        }
    }

    override suspend fun unbind(key: SurfaceKey): Boolean = lock.withLock {
        val had = key in state.value
        if (had) {
            state.value = state.value - key
            persist()
        }
        had
    }

    override suspend fun flush() = lock.withLock {
        if (pendingWrite) write(state.value)
        pendingWrite = false
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

    private fun persist() {
        if (config[RegistryFlags.writePolicy] == WritePolicy.EVERY_CHANGE) {
            write(state.value)
            pendingWrite = false
        } else {
            pendingWrite = true
        }
    }

    private fun write(bindings: Map<SurfaceKey, Binding>) {
        if (loaded.error != null) return
        val file = BindingsFile(
            bindings = bindings.mapKeys { (key, _) -> key.canonical } + loaded.unreadable,
        )
        path.createParentDirectories()
        val tmp = path.resolveSibling("${path.name}.tmp")
        Files.writeString(tmp, json.encodeToString(file))
        // Atomic rename, same discipline as the config file: a concurrent reader sees the old
        // bindings or the new ones, never a truncated file that would read as "nothing is bound"
        // and cause every agent on the desktop to be re-minted.
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private class Loaded(
        val bindings: Map<SurfaceKey, Binding>,
        val unreadable: Map<String, Binding>,
        val error: String?,
    )

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
