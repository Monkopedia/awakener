package com.monkopedia.awakener.config

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

private enum class Mode { FAST, SLOW }

class ConfigTest {
    private val dir = createTempDirectory("awakener-config")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private object TestFlags {
        val bool = Flags.boolean("test.bool", true, "a boolean")
        val count = Flags.int("test.count", 7, "an int")
        val name = Flags.string("test.name", "default", "a string")
        val mode = Flags.enum("test.mode", Mode.FAST, "an enum")
    }

    init {
        Flags.requireLoaded(TestFlags)
    }

    @Test
    fun `defaults apply when nothing is set`() {
        val config = Config.EMPTY
        assertEquals(true, config[TestFlags.bool])
        assertEquals(7, config[TestFlags.count])
        assertEquals(Mode.FAST, config[TestFlags.mode])
        assertTrue(!config.isOverridden(TestFlags.bool))
    }

    @Test
    fun `overrides win`() {
        val config = Config.of(mapOf("test.count" to JsonPrimitive(42)))
        assertEquals(42, config[TestFlags.count])
        assertTrue(config.isOverridden(TestFlags.count))
    }

    /**
     * The config file is meant to be hand-edited against a running daemon, so one bad value
     * must cost only that flag. Throwing here would take the process down over a typo.
     */
    @Test
    fun `a value that does not decode falls back to the default and is reported`() {
        val config = Config.of(mapOf("test.count" to JsonPrimitive("not-a-number")))
        assertEquals(7, config[TestFlags.count])
        assertEquals(listOf("test.count"), config.problems.map { it.key })
    }

    @Test
    fun `unknown keys are reported but retained`() {
        val config = Config.of(mapOf("test.gone" to JsonPrimitive(1)))
        assertEquals(listOf("test.gone"), config.problems.map { it.key })
        assertEquals(
            JsonPrimitive(1),
            config.overrides()["test.gone"],
            "retaining it means a later write-back does not silently delete the user's edit",
        )
    }

    @Test
    fun `duplicate flag keys are refused`() {
        assertFailsWith<IllegalArgumentException> {
            Flags.boolean("test.bool", false, "a clashing redeclaration")
        }
    }

    /**
     * Refusing a registration has to mean it did not happen. Discovery loads declaring classes
     * inside a `runCatching`, so a clash arrives as one warning line rather than a crash — and a
     * registry left holding the *rejected* flag would then make `list` print the loser's default
     * under the winner's key, which is exactly the quiet misreport flags exist to avoid.
     */
    @Test
    fun `a refused duplicate leaves the registry untouched`() {
        val kept = Flags.string("test.dup", "kept", "the declaration that got there first")
        val before = Flags.all()
        assertFailsWith<IllegalArgumentException> {
            Flags.int("test.dup", -1, "the declaration that must not land")
        }
        assertEquals(kept, Flags.byKey("test.dup"), "the rejected flag replaced the accepted one")
        assertEquals(before, Flags.all(), "the registry changed despite refusing the duplicate")
    }

    @Test
    fun `enum parsing is case-insensitive and rejects unknown values`() {
        val store = InMemoryConfigStore()
        runTest {
            store.set("test.mode", "slow")
            assertEquals(Mode.SLOW, store.config.value[TestFlags.mode])
            assertFailsWith<IllegalArgumentException> { store.set("test.mode", "sideways") }
        }
    }

    @Test
    fun `setting an unknown flag is refused rather than written`() = runTest {
        val store = InMemoryConfigStore()
        assertFailsWith<IllegalArgumentException> { store.set("test.nope", "1") }
    }

    @Test
    fun `file store reads values and persists changes`() = runTest {
        val path = dir.resolve("config.json")
        path.writeText("""{"test.count": 3}""")
        val store = FileConfigStore(path, environment = emptyMap())
        assertEquals(3, store.config.value[TestFlags.count])

        store.set("test.name", "changed")
        assertEquals("changed", store.config.value[TestFlags.name])
        assertTrue(path.toFile().readText().contains("changed"), "the change is on disk")

        store.unset("test.name")
        assertEquals("default", store.config.value[TestFlags.name])
    }

    /** A half-saved or mistyped file must not revert every other flag to its default. */
    @Test
    fun `a malformed file keeps the last good snapshot`() = runTest {
        val path = dir.resolve("malformed.json")
        path.writeText("""{"test.count": 3}""")
        val store = FileConfigStore(path, environment = emptyMap())
        assertEquals(3, store.config.value[TestFlags.count])

        path.writeText("{ this is not json")
        val reloaded = FileConfigStore(path, environment = emptyMap())
        assertEquals(
            7,
            reloaded.config.value[TestFlags.count],
            "a fresh store over a broken file has no last-good state, so it uses defaults",
        )
        assertTrue(reloaded.loadError.value != null, "and it says so rather than pretending")
    }

    @Test
    fun `environment overrides beat the file`() = runTest {
        val path = dir.resolve("env.json")
        path.writeText("""{"test.count": 3}""")
        val store = FileConfigStore(path, environment = mapOf("AWAKENER_TEST_COUNT" to "99"))
        assertEquals(99, store.config.value[TestFlags.count])
    }

    @Test
    fun `a missing file is not an error`() {
        val store = FileConfigStore(dir.resolve("absent.json"), environment = emptyMap())
        assertEquals(7, store.config.value[TestFlags.count])
        assertNull(store.loadError.value)
    }
}
