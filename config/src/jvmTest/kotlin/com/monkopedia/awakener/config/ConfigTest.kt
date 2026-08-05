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

        /** A range, which can name a nearest value and so can be clamped. */
        val ppt = Flags.int("test.ppt", 30, "a percentage", Flags.within(1..100))

        /** A bare predicate, which cannot — the case that degrades under every mode but KEEP. */
        val even = Flags.long(
            "test.even",
            2,
            "an even number",
            Flags.requires("even") { it % 2 == 0L },
        )

        val exploding = Flags.string(
            "test.exploding",
            "fine",
            "a requirement that throws on one value",
            Flags.requires("checkable") {
                if (it == "boom") error("the predicate itself is broken") else true
            },
        )

        val ceiling = Flags.long("test.ceiling", 100, "the other half of a cross-flag rule")

        /** Inert on defaults, so every other test's snapshot is unaffected by its existence. */
        val underCeiling = Flags.constraint(
            "test.even",
            "must not exceed test.ceiling",
        ) { config ->
            "is ${config[even]}, above test.ceiling's ${config[ceiling]}"
                .takeIf { config[even] > config[ceiling] }
        }

        val brokenConstraint = Flags.constraint("test.name", "a constraint that throws") { config ->
            if (config[name] == "detonate") error("the constraint itself is broken") else null
        }
    }

    init {
        // ConfigFlags too: the validation mode is itself a flag, so a test that pins it needs
        // the key to be declared or `Config.of` reads it as one nothing owns.
        Flags.requireLoaded(TestFlags, ConfigFlags)
    }

    private fun snapshot(vararg values: Pair<String, Any>): Config = Config.of(
        values.associate { (key, value) ->
            key to when (value) {
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )

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

    // ---- #62: a value that decodes and is still wrong ----

    /**
     * The half the decode check never covered. `-1` is a perfectly good Int and a useless
     * percentage, so before this it passed through with nothing in `problems` and the module
     * reading it quietly behaved differently from what was typed.
     */
    @Test
    fun `a value out of range degrades to the default and is reported`() {
        val config = snapshot("test.ppt" to 150)
        assertEquals(30, config[TestFlags.ppt], "an out-of-range value was applied")
        val problem = config.problems.single()
        assertEquals("test.ppt", problem.key)
        assertTrue(
            "must be between 1 and 100" in problem.reason && "using 30" in problem.reason,
            "the report does not say what was wrong or what is being used: ${problem.reason}",
        )
    }

    @Test
    fun `CLAMP takes the nearest allowed value instead, and still reports it`() {
        val config = snapshot(
            "config.validation.invalid_value" to "CLAMP",
            "test.ppt" to 150,
        )
        assertEquals(100, config[TestFlags.ppt])
        assertEquals(listOf("test.ppt"), config.problems.map { it.key }, "clamping went unsaid")
    }

    @Test
    fun `KEEP applies the value as typed, and still reports it`() {
        val config = snapshot(
            "config.validation.invalid_value" to "KEEP",
            "test.ppt" to 150,
        )
        assertEquals(150, config[TestFlags.ppt])
        assertEquals(listOf("test.ppt"), config.problems.map { it.key })
    }

    /**
     * A bare predicate knows what it rejects, not what it would have preferred, so there is no
     * nearest value to take — and the flag falls back to the one behaviour that is always
     * available rather than to the typed value.
     */
    @Test
    fun `a requirement with no nearest value degrades under CLAMP too`() {
        val config = snapshot(
            "config.validation.invalid_value" to "CLAMP",
            "test.even" to 3L,
        )
        assertEquals(2L, config[TestFlags.even])
    }

    @Test
    fun `a value within its range is neither changed nor reported`() {
        val config = snapshot("test.ppt" to 55)
        assertEquals(55, config[TestFlags.ppt])
        assertEquals(emptyList(), config.problems)
    }

    /** A defect in the declaring module must not be a defect in reading the config file. */
    @Test
    fun `a requirement that throws is reported and the value degrades`() {
        val config = snapshot("test.exploding" to "boom")
        assertEquals("fine", config[TestFlags.exploding])
        assertTrue(
            "could not be checked" in config.problems.single().reason,
            "${config.problems}",
        )
    }

    /**
     * Registration is the only place this can be caught, and it has to be: DEGRADE lands on the
     * default, so a default outside its own requirement makes the degradation path land
     * somewhere the flag says is unusable.
     */
    @Test
    fun `a default that breaks its own requirement is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Flags.int("test.impossible", 0, "out of its own range", Flags.within(1..100))
        }
        assertNull(Flags.byKey("test.impossible"), "the refused flag was registered anyway")
    }

    // ---- #62: rules about a snapshot rather than about a value ----

    /**
     * `reservation_grace_ms >= map_ms` in `:wm` is the shape this exists for: either number is
     * defensible alone and it is the pair that is wrong, so there is no single key to degrade.
     * It reports and changes nothing, which is the whole of what it can honestly do.
     */
    @Test
    fun `a cross-flag rule is reported without degrading either value`() {
        val config = snapshot("test.even" to 200L, "test.ceiling" to 100L)
        assertEquals(200L, config[TestFlags.even], "a cross-flag rule degraded a value")
        assertEquals(100L, config[TestFlags.ceiling])
        val problem = config.problems.single()
        assertEquals("test.even", problem.key, "the problem names no flag to go and look at")
        assertTrue("above test.ceiling" in problem.reason, problem.reason)
    }

    @Test
    fun `a cross-flag rule that holds says nothing`() {
        assertEquals(emptyList(), snapshot("test.even" to 50L).problems)
    }

    @Test
    fun `a constraint that throws is reported rather than escaping`() {
        val config = snapshot("test.name" to "detonate")
        assertTrue(
            "could not be checked" in config.problems.single().reason,
            "${config.problems}",
        )
    }

    // ---- #62: typing a bad value at something that can answer ----

    /**
     * The file degrades because nothing is watching it. A `set` is somebody at a prompt, so it
     * is refused instead — writing the value and then quietly using another would be the worse
     * of the two, since the file would then disagree with the behaviour.
     */
    @Test
    fun `set refuses a value that breaks the requirement, and writes nothing`() = runTest {
        val path = dir.resolve("refused.json")
        val store = FileConfigStore(path, environment = emptyMap())
        val failure = assertFailsWith<IllegalArgumentException> { store.set("test.ppt", "150") }
        assertTrue("between 1 and 100" in failure.message.orEmpty(), "${failure.message}")
        assertEquals(30, store.config.value[TestFlags.ppt])
        assertTrue(!path.toFile().exists(), "the refused value reached the file")
    }

    @Test
    fun `an environment override out of range degrades like a file value`() {
        val store = FileConfigStore(
            dir.resolve("env-range.json"),
            environment = mapOf("AWAKENER_TEST_PPT" to "150"),
        )
        assertEquals(30, store.config.value[TestFlags.ppt])
        assertEquals(listOf("test.ppt"), store.config.value.problems.map { it.key })
    }

    /** Dropping it silently left the file's value in effect while the variable looked applied. */
    @Test
    fun `an environment override that does not parse is reported rather than dropped`() {
        val store = FileConfigStore(
            dir.resolve("env-junk.json"),
            environment = mapOf("AWAKENER_TEST_COUNT" to "seven"),
        )
        assertEquals(7, store.config.value[TestFlags.count])
        assertTrue(
            store.config.value.problems.single().reason.contains("AWAKENER_TEST_COUNT"),
            "${store.config.value.problems}",
        )
    }

    // ---- #46: the write path taking the process down ----

    /**
     * Verified against unchanged code: this threw `java.nio.file.AccessDeniedException` out of
     * `set`, straight through `ConfigCli` — which catches only `IllegalArgumentException` — and
     * out of `main` as a stack trace. `load` had guarded the same read since it was written; the
     * write path never did, and the malformed-file case survived only because
     * `SerializationException` happens to extend `IllegalArgumentException`.
     */
    @Test
    fun `set over an unreadable file refuses by name instead of throwing IOException`() = runTest {
        val path = dir.resolve("locked.json")
        path.writeText("""{"test.count": 3}""")
        val store = FileConfigStore(path, environment = emptyMap())
        assertTrue(path.toFile().setReadable(false), "could not drop read permission")

        val failure = assertFailsWith<IllegalStateException> { store.set("test.name", "x") }
        assertTrue(
            failure.message.orEmpty().contains(path.toString()) &&
                failure.message.orEmpty().contains(ConfigFlags.unreadableWrite.key),
            "the refusal names neither the file nor the way out: ${failure.message}",
        )
        assertEquals(3, store.config.value[TestFlags.count], "the live snapshot was disturbed")
    }

    /** And the CLI answers rather than propagating, which is what `main` has no handler for. */
    @Test
    fun `the CLI reports an unwritable file instead of letting it out`() {
        val path = dir.resolve("locked-cli.json")
        path.writeText("""{"test.count": 3}""")
        val store = FileConfigStore(path, environment = emptyMap())
        assertTrue(path.toFile().setReadable(false), "could not drop read permission")

        val lines = mutableListOf<String>()
        val code = ConfigCli.run(arrayOf("set", "test.name", "x"), store, lines::add)
        assertEquals(2, code)
        assertTrue(
            lines.any { it.startsWith("error:") && path.toString() in it },
            "the failure was not reported to the user: $lines",
        )
    }

    @Test
    fun `REWRITE replaces a file nothing can read with the values in effect`() = runTest {
        val path = dir.resolve("rewrite.json")
        path.writeText("""{"test.count": 3}""")
        val store = FileConfigStore(
            path,
            environment = mapOf("AWAKENER_CONFIG_STORE_UNREADABLE_WRITE" to "REWRITE"),
        )
        assertTrue(path.toFile().setReadable(false), "could not drop read permission")

        store.set("test.name", "x")
        assertTrue(path.toFile().setReadable(true), "could not restore read permission")
        val written = path.toFile().readText()
        assertTrue(""""test.name": "x"""" in written, "the change was not written: $written")
        assertTrue(""""test.count": 3""" in written, "the last good contents were lost: $written")
        assertTrue(
            ConfigFlags.unreadableWrite.key !in written,
            "a one-off environment override was baked into the file: $written",
        )
        assertEquals("x", store.config.value[TestFlags.name])
    }

    /** The requirement has to reach the person editing the file, not only the declaring module. */
    @Test
    fun `list prints the requirement beside the flag`() {
        val store = FileConfigStore(dir.resolve("list.json"), environment = emptyMap())
        val lines = mutableListOf<String>()
        assertEquals(0, ConfigCli.run(arrayOf("list"), store, lines::add))
        assertTrue(
            lines.any { it.trim() == "must be between 1 and 100" },
            "the range is invisible to the person who has to get it right: $lines",
        )
    }
}
