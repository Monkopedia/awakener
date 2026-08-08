package com.monkopedia.awakener.registry

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isExecutable
import org.junit.AssumptionViolatedException

/**
 * The one gate every spanreed-dependent test in this build goes through.
 *
 * ### Why the skip has to throw
 *
 * A test method that returns normally is recorded as PASSED, so the early `return` this shape
 * replaced made a machine without spanreed report `skipped="0"` and a full green suite —
 * indistinguishable from a run that really did shell out (#26). An assumption failure is what
 * Gradle writes into the XML as `<skipped/>`, which is what `CLAUDE.md` claims the count means.
 *
 * ### Why it is published rather than copied
 *
 * `:cli` needs the same gate — its invoke suite mints through the real spanreed — and this object
 * is on `:cli`'s test classpath through `:registry`'s `jvmTestClasses` configuration, the same
 * arrangement `:wm` uses to lend `SwayHarness` out. Twelve lines are cheap enough to copy, which
 * is exactly the argument against copying them: the ordering inside [assumeAvailable] is the whole
 * mechanism — `AWAKENER_REQUIRE_SPANREED=1` is consulted *before* the skip, so a required tool
 * that is missing fails rather than skips — and a second copy is a second place for that ordering
 * to be got wrong, in a build whose stated protection against a green run that verified nothing is
 * this flag.
 */
object SpanreedHarness {
    /**
     * The name looked for on PATH, and deliberately the *default* of
     * [RegistryFlags.spanreedCommand] rather than an independent constant: this gate decides
     * whether a test that reaches the bus through the shipped default can run at all, so the two
     * must name the same executable.
     */
    const val COMMAND: String = "spanreed"

    /** The installed spanreed, or null. Same predicate as `toolFingerprint` in the build scripts. */
    fun path(): Path? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { Path.of(it, COMMAND) }
        .firstOrNull { it.isExecutable() }

    /** Whether [assumeAvailable] would return rather than skip. For teardown, which must not skip. */
    fun available(): Boolean = path() != null

    /**
     * The installed spanreed, a failure under `AWAKENER_REQUIRE_SPANREED=1`, or a skip the test
     * report actually shows — in that order, which is the part that matters.
     */
    fun assumeAvailable(): Path {
        path()?.let { return it }
        check(System.getenv("AWAKENER_REQUIRE_SPANREED") != "1") {
            "AWAKENER_REQUIRE_SPANREED=1 but spanreed is not installed"
        }
        throw AssumptionViolatedException("spanreed is not installed")
    }
}
