package com.monkopedia.awakener.registry

import com.monkopedia.awakener.config.Config
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * `:config`'s contract is that a snapshot is total — a hand-edited value degrades rather than
 * taking the process down. These are the two values that broke it (#46), and both broke it a
 * module away from the flag, which is what made them expensive: the failure named neither the
 * config file nor the key.
 */
class RegistryPathsTest {
    /**
     * Verified against unchanged code: `registry.store.path: "bindings.json"` threw
     * `NullPointerException: Cannot invoke "java.nio.file.Path.resolve(String)" because the
     * return value of "java.nio.file.Path.getParent()" is null`, on the bind path. Kotlin sees
     * `Path!` back from the Java API, so there was no nullability to notice at the call site.
     */
    @Test
    fun `a bare relative store path resolves its residue beside itself`() {
        val here = Path("").toAbsolutePath()
        assertEquals(
            here.resolve("residue"),
            RegistryPaths.residueDir(Config.EMPTY, Path("bindings.json")),
        )
    }

    @Test
    fun `an absolute store path is unaffected`() {
        assertEquals(
            Path("/var/lib/awakener/residue"),
            RegistryPaths.residueDir(Config.EMPTY, Path("/var/lib/awakener/bindings.json")),
        )
    }

    /** Not a bindings file, but the answer is still a directory rather than a throw. */
    @Test
    fun `a store path with no parent at all still answers`() {
        assertEquals(Path("/residue"), RegistryPaths.residueDir(Config.EMPTY, Path("/")))
    }

    @Test
    fun `an explicit residue dir still wins over both`() {
        val config = Config.of(mapOf(RegistryFlags.residueDir.key to JsonPrimitive("/tmp/r")))
        assertEquals(Path("/tmp/r"), RegistryPaths.residueDir(config, Path("bindings.json")))
    }

    /**
     * Verified against unchanged code: a typo in `registry.agent.spanreed_command` threw
     * `java.io.IOException: Cannot run program "awakener-no-such-spanreed": Exec failed, error: 2
     * (No such file or directory)` out of `ProcessBuilder.start()`, with nothing on the hotkey
     * path catching it — despite every other way this call can fail already being a
     * [ProcessResult] the caller inspects.
     */
    @Test
    fun `a spanreed command that is not there is a failed run, not a throw`() {
        val result = ProcessCommandRunner().run(listOf("awakener-no-such-spanreed"), emptyMap())
        assertFalse(result.succeeded)
        assertTrue(
            "awakener-no-such-spanreed" in result.stderr &&
                RegistryFlags.spanreedCommand.key in result.stderr,
            "the failure names neither the command nor the flag that chose it: ${result.stderr}",
        )
    }

    /** And it is distinguishable from a command that ran and was killed for taking too long. */
    @Test
    fun `not runnable is not reported as a timeout`() {
        val absent = ProcessCommandRunner().run(listOf("awakener-no-such-spanreed"), emptyMap())
        val timedOut = ProcessCommandRunner(timeoutMs = { 50L }).run(listOf("sleep", "5"), emptyMap())
        assertFalse(absent.succeeded)
        assertFalse(timedOut.succeeded)
        assertTrue(
            absent.exitCode != timedOut.exitCode,
            "a missing command and a wedged one report the same code ${absent.exitCode}",
        )
    }
}
