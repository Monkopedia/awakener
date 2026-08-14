package com.monkopedia.awakener.config

import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val SLOTS = 20

/**
 * Somewhere for two writers to write that is not the same key, so a lost update shows up as a
 * missing key rather than as a value that could have been either.
 *
 * Not named `*Flags`, for the reason `FileConfigStoreWatchTest` gives: this suite runs
 * `ConfigCli.bootstrap`, whose classpath scan would otherwise reach it.
 */
private object LockKnobs {
    val slots: List<Flag<Int>> = listOf("a", "b").flatMap { prefix ->
        (0 until SLOTS).map { Flags.int("lock.$prefix.$it", -1, "a slot for the lock suite") }
    }
}

/**
 * What a `set` does when it is not the only writer (#88) — the half of it one JVM can state.
 *
 * The other half needs two processes and lives in `CrossProcessConfigTest`. The split is the
 * point: the per-path mutex and the file lock close overlapping windows, and a suite that only
 * ran in one JVM would be satisfied by the mutex alone. That is what got `:registry`'s first
 * attempt at the same fix blocked in #59.
 */
class FileConfigStoreLockTest {
    private val dir = createTempDirectory("awakener-config-lock")

    init {
        Flags.requireLoaded(LockKnobs, ConfigFlags)
    }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    /**
     * The mutex is keyed on the *path*, not held per store, and this is the difference: two
     * stores over one file are two writers, and a `Mutex` field would give them one each.
     *
     * Two stores rather than two coroutines on one store, because one store's writes are
     * serialised by any mutex at all — including a per-instance one, which is what this had.
     *
     * Measured: with `lockFor(path)` replaced by a plain `Mutex()`, this does not merely lose
     * values, it raises `OverlappingFileLockException` — the two stores reach `FileChannel.lock`
     * on one file from one JVM, which throws rather than waiting. That is the second half of why
     * the mutex is keyed on the path, and it is the half a per-instance mutex fails loudly on.
     */
    @Test
    fun `two stores over one file lose no concurrent set`() = runBlocking {
        val path = dir.resolve("shared.json")
        path.writeText("{}")
        val stores = listOf("a", "b").map { it to FileConfigStore(path, environment = emptyMap()) }

        coroutineScope {
            stores.forEach { (prefix, store) ->
                launch(Dispatchers.IO) {
                    repeat(SLOTS) { store.set("lock.$prefix.$it", it.toString()) }
                }
            }
        }

        val written = Json.parseToJsonElement(path.readText()).jsonObject.keys
        val missing = LockKnobs.slots.map { it.key }.toSet() - written
        assertTrue(missing.isEmpty(), "${missing.size} of ${SLOTS * 2} values were lost: $missing")
    }

    /**
     * The staging file is named per process, so nothing can be standing on the name this write
     * wants. Stated by putting something there that a write to a fixed `<file>.tmp` could not
     * survive: a directory, which `Files.writeString` refuses.
     *
     * The real hazard is the other process rather than a stray directory — two writers on one
     * fixed name interleave, and one can rename it away between the other's write and its own —
     * but that hazard has no deterministic test, and this pins the same property: the name a
     * write stages under is this process's alone.
     */
    @Test
    fun `a set does not stage through a fixed tmp name`() = runBlocking {
        val path = dir.resolve("staged.json")
        path.writeText("{}")
        Files.createDirectory(dir.resolve("staged.json.tmp"))

        val store = FileConfigStore(path, environment = emptyMap())
        store.set("lock.a.0", "5")

        assertEquals(5, store.config.value[LockKnobs.slots[0]])
        assertTrue(""""lock.a.0": 5""" in path.readText(), "nothing reached the file")
    }

    /**
     * A lock that cannot be taken is reported, and by default the write still happens.
     *
     * A directory where the lock file goes is the arrangeable version of the situation the flag
     * is really for — an NFS home without `lockd`, where `FileChannel.lock` raises rather than
     * blocks. Both arrive here as an `IOException` from the same two lines, which is why they
     * are one flag rather than two.
     */
    @Test
    fun `a lock that cannot be taken is reported and the write goes ahead`() = runBlocking {
        val path = dir.resolve("unlockable.json")
        path.writeText("{}")
        Files.createDirectory(dir.resolve("unlockable.json.lock"))

        val store = FileConfigStore(path, environment = emptyMap())
        store.set("lock.a.1", "3")

        assertEquals(3, store.config.value[LockKnobs.slots[1]])
        assertTrue(
            store.lockError.value?.contains("unlockable.json.lock") == true,
            "the store wrote with no cross-process exclusion and said nothing: " +
                "${store.lockError.value}",
        )
    }

    /**
     * Reported, and then *stopped* being reported once a `set` does get the lock.
     *
     * The same property `StorePermissionsTest` holds for the residue exposure report, and the
     * same reason: a warning that latches is a warning that stops meaning anything the moment
     * somebody acts on it. `ConfigCli` prints [FileConfigStore.lockError] after every `set`, so
     * a store that never cleared it would print `warning: … writing without cross-process
     * exclusion` on every subsequent **successful** write, for the life of the process.
     *
     * The transition is the whole test, because it is the half nothing observed (#140). Every
     * assertion this suite had was either *set* — block the lock, write once, assert non-null,
     * stop — or *never-set*, an `assertNull` on a store that was never degraded and would pass
     * against a store that cannot clear at all. Measured: with `lockError.value = null` deleted
     * from `withFileLock`, the whole build stayed green at 351 tests.
     */
    @Test
    fun `the unlocked-write warning clears once a set does get the lock`() = runBlocking {
        val path = dir.resolve("relockable.json")
        path.writeText("{}")
        val lock = dir.resolve("relockable.json.lock")
        // A directory where the lock file goes, for the reason the test above gives: it is the
        // arrangeable version of a filesystem that will not lock, and it is *removable*, which
        // is what makes the recovery half of this arrangeable too.
        Files.createDirectory(lock)

        val store = FileConfigStore(path, environment = emptyMap())
        store.set("lock.a.3", "7")
        assertTrue(
            store.lockError.value?.contains("relockable.json.lock") == true,
            "the degraded write has to be reported before its clearing can mean anything: " +
                "${store.lockError.value}",
        )

        Files.delete(lock)
        store.set("lock.a.4", "8")

        assertNull(
            store.lockError.value,
            "this `set` took the lock, so the warning describes nothing that is still true — " +
                "left standing it makes every later successful write print it too",
        )
        assertEquals(8, store.config.value[LockKnobs.slots[4]], "and the write itself happened")
        assertEquals(7, store.config.value[LockKnobs.slots[3]], "beside the degraded one")
    }

    /** The other arm: refuse rather than write without exclusion. */
    @Test
    fun `lock_required refuses the write instead of degrading`() = runBlocking {
        val path = dir.resolve("required.json")
        path.writeText("{}")
        Files.createDirectory(dir.resolve("required.json.lock"))

        val store = FileConfigStore(
            path,
            environment = mapOf("AWAKENER_CONFIG_STORE_LOCK_REQUIRED" to "true"),
        )
        assertFailsWith<IOException> { store.set("lock.a.2", "4") }

        assertEquals("{}", path.readText().trim(), "the refusal still wrote")
    }

    /**
     * And the operator hears about it. A `set` that could not lock still reports success for the
     * write it made — which is exactly #88's shape, a loss with nothing to look at — so the
     * warning is what makes the degradation visible where the degradation happens.
     */
    @Test
    fun `the CLI says so when a set could not take the lock`() {
        val path = dir.resolve("unlockable-cli.json")
        path.writeText("{}")
        Files.createDirectory(dir.resolve("unlockable-cli.json.lock"))

        val lines = mutableListOf<String>()
        val store = ConfigCli.bootstrap(path, emptyMap(), lines::add)
        val code = ConfigCli.run(arrayOf("set", "lock.b.0", "6"), store, lines::add)

        assertEquals(0, code, "the write itself should still have succeeded: $lines")
        assertTrue(
            lines.any { it.startsWith("warning:") && "cross-process" in it },
            "the unlocked write was invisible to the person who made it: $lines",
        )
    }
}
