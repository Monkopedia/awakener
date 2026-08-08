package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigStore
import com.monkopedia.awakener.config.PrivateFiles
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
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
 * is changing, never the whole file: under every value of [RegistryFlags.storeReload] except
 * `NEVER`, each write re-reads under the lock and puts back what it found plus its own change,
 * and under `BEFORE_READ` each resolve re-reads too. A store that assumed sole ownership would
 * write its snapshot back over a forget and silently resurrect the binding the user had just
 * removed — which is exactly what `NEVER` does, deliberately and documented on the value
 * itself. The qualification is here rather than only there because this paragraph is what a
 * caller reads first, and stated flat it made a property of one flag value read as a property
 * of the class (#109).
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
     * The path is captured once rather than read per call, and that is a decision rather than
     * an omission.
     *
     * [RegistryFlags.storePath] *would* reload like every other flag once anything in the build
     * outlives one operation (#43): the mechanism is `FileConfigStore.watch`, which publishes a
     * new snapshot for as long as somebody is running it, and nothing runs it yet — so today a
     * flip of this key applies to the next process whichever way this property were written.
     * The exception is stated against the property the store will have, not against that gap: a
     * store that silently began writing somewhere else mid-session would strand the bindings it
     * had already made, so moving the durable set is a restart and not a flip.
     */
    val path: Path = path ?: RegistryPaths.storePath(config, environment)

    /**
     * Serialises read-modify-write within this JVM. Keyed on the file rather than held per
     * instance because two stores over one file are two writers — and because a second
     * [FileChannel.lock] on a file this process already holds throws rather than waiting.
     *
     * The key is textual, so two spellings of one file — through a symlink, say — would take two
     * mutexes and then hit exactly that exception. Unreachable with the one store path a
     * deployment has, and normalising harder would mean touching the filesystem per store.
     */
    private val lock = lockFor(this.path)

    /** The lock file. A getter, so the property is read rather than the constructor parameter. */
    private val lockPath: Path get() = path.resolveSibling("${path.name}.lock")

    /**
     * The staging file the write is renamed from, named per process.
     *
     * A single fixed `<file>.tmp` is a race in its own right: two processes writing it at once
     * interleave, and one can rename it away between the other's write and its own rename, which
     * surfaces as `NoSuchFileException` on a path that plainly exists. The lock above makes that
     * unreachable on the normal path — but the unlocked degradation in [withFileLock] is a real
     * path, and a defect that is merely masked by another mechanism is still a defect. With a
     * per-process name the worst the unlocked path can do is lose an update.
     */
    private val tmpPath: Path
        get() = path.resolveSibling("${path.name}.${ProcessHandle.current().pid()}.tmp")

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

    @Volatile
    private var unlocked: String? = null

    /**
     * Why the last write went ahead without the cross-process lock, if it did.
     *
     * The same shape as [loadError] and for the same reason: a store in this condition still
     * answers, but it is degraded in a way only it can see. Unlocked writing *is* #50 — a holder
     * putting its own view back over another process's `forget` — so it is reported wherever a
     * degraded store is reported, and `awakener-registry` prints it beside its other warnings.
     * Cleared as soon as a write does get the lock.
     */
    val lockError: String? get() = unlocked

    @Volatile
    private var exposedResidue: String? = null

    /**
     * Why the residue directory prepared most recently was reachable by other users, if it was.
     *
     * The third property of this shape, for the third degradation only the store can see. It is
     * set by [prepareResidue] alone: that is the one call that creates anything under
     * `registry.residue.dir`, so it is the one moment the answer is both knowable and about to
     * matter. [residueLocation] deliberately does not set it — naming a path is not writing to
     * one, and a `awakener-registry residue` that warned would be reporting a hazard nobody had
     * yet run into.
     */
    val residueExposure: String? get() = exposedResidue

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
        // Minted outside the lock where the answer is already knowable: it can shell out to
        // spanreed, and holding the write lock across a subprocess would serialise every other
        // surface behind one process spawn. The fallback inside `mutate` covers the narrow case
        // where a forget lands between this read and the one under the lock, and pays exactly
        // that cost — rare enough to be worth a subprocess under the lock, and the only
        // alternative is minting speculatively on every bind.
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

    /**
     * The binding first, then the residue, and deliberately not in one transaction.
     *
     * Nothing here can make the two atomic — one is a rename over a JSON file, the other is a
     * rename or a delete in another directory — so the order is chosen for which way round a
     * crash between them is survivable. Binding dropped and residue still present is the state
     * `KEEP` produces on purpose, and it costs a stale file. Residue archived and the binding
     * still standing would hand a live agent a surface whose model had been moved out from
     * under it, which nothing would ever notice or repair.
     *
     * The disposal runs outside [mutate] for the same reason [bind] mints outside it: it is
     * filesystem work on a different directory, and holding the bindings lock across it would
     * serialise every other surface behind one rename.
     */
    override suspend fun unbind(key: SurfaceKey): Forget {
        val wasBound = mutate {
            val had = key in state.value
            if (had) write(state.value - key)
            had
        }
        // A surface that was not bound has had nothing forgotten, so there is nothing for the
        // flag to act on. Disposing anyway would make `forget` a residue command that also
        // happens to unbind, and would let a mistyped key delete a model under DELETE.
        if (!wasBound) return Forget(false, ResidueOutcome.Kept(residueLocation(key)))
        return Forget(true, disposeResidue(key))
    }

    /**
     * Applies [RegistryFlags.forgetResidue] to one surface's residue.
     *
     * Every failure comes back as [ResidueOutcome.Failed] rather than as a throw: by the time
     * this runs the binding is already gone durably, so raising here would report the whole
     * `forget` as failed when its durable half succeeded — and the caller would have no way to
     * tell that from a forget that never happened. The repair path is the last place to make
     * those two look alike.
     *
     * All three flags come off **one** snapshot. Reading them one at a time would let a reload
     * land mid-disposal and pair one snapshot's layout with another's residue directory, which
     * is the "never cache a flag across an operation that could span a reload" rule read the
     * right way round: the fix is to take the snapshot once, not to re-read more often.
     */
    @OptIn(ExperimentalPathApi::class)
    private suspend fun disposeResidue(key: SurfaceKey): ResidueOutcome =
        withContext(Dispatchers.IO) {
            val cfg = config
            val layout = cfg[RegistryFlags.residueLayout]
            val location = RegistryPaths.residueLocation(cfg, path, key)
            val at = location.toString()
            when (cfg[RegistryFlags.forgetResidue]) {
                ForgetResidue.KEEP -> ResidueOutcome.Kept(at)
                ForgetResidue.ARCHIVE -> when {
                    !location.exists() -> ResidueOutcome.Absent(at)
                    else -> try {
                        val archive = archivePath(key, layout, location)
                        Files.move(location, archive)
                        ResidueOutcome.Archived(at, archive.toString())
                    } catch (e: IOException) {
                        ResidueOutcome.Failed(at, e.message ?: e.toString())
                    }
                }
                ForgetResidue.DELETE -> when {
                    !location.exists() -> ResidueOutcome.Absent(at)
                    else -> try {
                        if (location.isDirectory()) {
                            location.deleteRecursively()
                        } else {
                            Files.delete(location)
                        }
                        ResidueOutcome.Deleted(at)
                    } catch (e: IOException) {
                        ResidueOutcome.Failed(at, e.message ?: e.toString())
                    }
                }
            }
        }

    /**
     * A free archive path beside [location].
     *
     * Seconds rather than milliseconds in the name because it is read by a human choosing which
     * archive to open, and a counter rather than more precision because that is what actually
     * settles it: two forgets of one surface inside a second is unlikely, but `Files.move`
     * without `REPLACE_EXISTING` would refuse rather than overwrite, and a refusal here reads
     * as "your model could not be archived" for a reason that is nobody's fault. UTC, so the
     * ordering of two archives does not invert across a daylight-saving boundary.
     */
    private fun archivePath(key: SurfaceKey, layout: ResidueLayout, location: Path): Path {
        val stamp = ARCHIVE_STAMP.format(Instant.ofEpochMilli(clock()))
        (0 until ARCHIVE_ATTEMPTS).forEach { attempt ->
            val suffix = if (attempt == 0) stamp else "$stamp-$attempt"
            val candidate = location.resolveSibling(archiveLeaf(key, layout, suffix))
            if (!candidate.exists()) return candidate
        }
        // Every name in the second is taken, which is not a filesystem this can archive into.
        // Reported as a failure by the caller rather than overwriting one of them.
        throw IOException("no free archive name beside $location for ${key.slug} at $stamp")
    }

    override fun residueLocation(key: SurfaceKey): String =
        RegistryPaths.residueLocation(config, path, key).toString()

    /**
     * Creates the residue location so a distiller has somewhere to write and a curious human has
     * somewhere to look. Kept out of [bind] because binding must stay cheap and must not fail on
     * a read-only state directory.
     *
     * Every directory and file it creates gets [RegistryFlags.filePermissions] — `0700` and
     * `0600` by default. Before #102 they took the process umask, so on any ordinary desktop
     * the residue, which the design brief calls the accumulated model of the user, was created
     * world-readable; what stopped that mattering was `/home/<user>` being `0700`, a property
     * of directories this store neither creates nor checks.
     *
     * The permissions are what this store can put on what it makes. Where it was *told* to
     * make it is a separate question, asked once here and answered by
     * [RegistryFlags.residueExposure].
     */
    suspend fun prepareResidue(key: SurfaceKey): Path = withContext(Dispatchers.IO) {
        val cfg = config
        val location = RegistryPaths.residueLocation(cfg, path, key)
        val permissions = cfg[RegistryFlags.filePermissions]
        checkExposure(
            RegistryPaths.residueDir(cfg, path),
            cfg[RegistryFlags.residueExposure],
        )
        when (cfg[RegistryFlags.residueLayout]) {
            ResidueLayout.PER_KEY_DIR -> PrivateFiles.createDirectories(location, permissions)
            ResidueLayout.PER_KEY_FILE -> {
                PrivateFiles.createParentDirectories(location, permissions)
                if (!location.exists()) PrivateFiles.createFile(location, permissions)
            }
        }
        location
    }

    /**
     * Applies [RegistryFlags.residueExposure] to the directory [residueDir] will be created in.
     *
     * The directory asked about is the nearest one that **already exists** on the way to
     * [residueDir], because that is the only one anybody else has had a chance at: everything
     * below it this call is about to create, at `0700`. `registry.residue.dir=/tmp/awakener` on a
     * machine with a second user is the case — `/tmp` is `1777`, so they can create
     * `/tmp/awakener` first, as a symlink onto a directory of theirs, and every model written
     * afterwards lands there. The sticky bit is not a defence against that; it prevents removing
     * an entry, not creating one.
     *
     * The residue *directory* rather than the per-surface file, so that both layouts ask one
     * question and so the answer does not change the second time a surface is prepared: under
     * `PER_KEY_FILE` the file exists by then, is `0600`, and would make an exposed directory
     * above it read as fine.
     *
     * **The deepest existing directory, and not the whole chain above it.** That answers the
     * question this can act on — whether somebody else can create the next component — and it
     * is the question `registry.residue.dir` pointed somewhere shared actually raises. Walking
     * every ancestor would answer a second one, whether an existing directory could be *swapped*
     * for another, and would do it badly: `/tmp` is `1777` on every Linux system, so a chain walk
     * warns about every path under it, including ones nobody can reach because the directory
     * below is `0700` and `/tmp`'s sticky bit stops it being removed. A warning that fires where
     * nothing is wrong is one nobody reads, and this one exists to be read.
     *
     * A path this cannot examine — a filesystem with no POSIX mode bits, a directory that
     * vanishes between the walk and the read — is treated as unexposed rather than as exposed,
     * for the same reason.
     */
    private fun checkExposure(residueDir: Path, policy: ResidueExposure) {
        if (policy == ResidueExposure.ALLOW) {
            exposedResidue = null
            return
        }
        val existing = generateSequence(residueDir.toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { it.exists() }
        val open = existing?.let {
            runCatching { Files.getPosixFilePermissions(it) }.getOrNull()
        }?.filter { it in WORLD_WRITABLE }.orEmpty()
        if (open.isEmpty()) {
            exposedResidue = null
            return
        }
        val why = "$existing is writable by others (${open.joinToString(", ") { it.name }}), so " +
            "another local user can create $residueDir before awakener does — as a symlink onto " +
            "something of theirs — and the model written there would be theirs to read"
        if (policy == ResidueExposure.REFUSE) {
            throw IOException("$why; set ${RegistryFlags.residueExposure.key}=REPORT to proceed")
        }
        exposedResidue = "$why; set ${RegistryFlags.residueExposure.key}=REFUSE to make this " +
            "refuse instead, or point ${RegistryFlags.residueDir.key} somewhere private"
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
     *
     * **Both ways of not getting the lock are the same situation from here**: a state directory
     * that will not take a lock file, and a filesystem that will not lock one (an NFS home
     * without `lockd`). What happens then is [RegistryFlags.lockRequired]'s call. By default the
     * write goes ahead unlocked, because refusing to bind takes the hotkey down on a live
     * desktop, and the write is atomic by rename over a per-process temporary — so the unlocked
     * path can lose an update but cannot tear or corrupt the file. **That state is reported
     * through [lockError] rather than assumed**: writing with no cross-process exclusion is #50's
     * own failure mode, so a store doing it silently would reproduce the property that made #50
     * severe — nothing tells the user to check.
     *
     * `OverlappingFileLockException` is *not* caught either way: that one means this process
     * already holds the lock, which is a bug in the mutex above rather than a fact about the
     * filesystem, and it should be loud.
     */
    private suspend fun <T> withFileLock(block: suspend () -> T): T {
        val permissions = config[RegistryFlags.filePermissions]
        val channel = try {
            PrivateFiles.createParentDirectories(lockPath, permissions)
            PrivateFiles.open(
                lockPath,
                permissions,
                setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
            )
        } catch (e: IOException) {
            return degrade("cannot create the lock file", e) { block() }
        }
        return channel.use { open ->
            val held = try {
                open.lock()
            } catch (e: IOException) {
                return@use degrade("the filesystem will not lock it", e) { block() }
            }
            unlocked = null
            try {
                block()
            } finally {
                held.release()
            }
        }
    }

    /**
     * Records that this store is about to write without cross-process exclusion, and does it —
     * unless [RegistryFlags.lockRequired], in which case [cause] is the answer instead.
     */
    private suspend fun <T> degrade(why: String, cause: IOException, block: suspend () -> T): T {
        if (config[RegistryFlags.lockRequired]) throw cause
        unlocked = "$lockPath: $why (${cause.message}); binding without cross-process " +
            "exclusion, so a concurrent `awakener-registry forget` can be undone"
        return block()
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
        val permissions = config[RegistryFlags.filePermissions]
        PrivateFiles.createParentDirectories(path, permissions)
        val tmp = tmpPath
        // The permissions go on the *staging* file, and the rename carries them to the bindings
        // file along with the contents — which is also what upgrades a bindings file an earlier
        // build left at 0644, with no migration step anywhere. `sync` is the fsync that used to
        // be a second `FileChannel.open` here: without it the rename can land while the contents
        // it points at are still in page cache, and a power loss then leaves an empty file where
        // the bindings were, which reads as "nothing is bound" and re-mints every agent on the
        // desktop.
        PrivateFiles.writeString(tmp, json.encodeToString(file), permissions, sync = true)
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
        /** UTC, seconds; see [archivePath] for why not milliseconds. */
        private val ARCHIVE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

        /** How many names within one second an archive will try before giving up. */
        private const val ARCHIVE_ATTEMPTS = 100

        /**
         * The mode bits that let somebody other than the owner create an entry in a directory.
         * Write only: see [ResidueExposure] for why a merely readable one is not reported.
         */
        private val WORLD_WRITABLE = setOf(
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )

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
