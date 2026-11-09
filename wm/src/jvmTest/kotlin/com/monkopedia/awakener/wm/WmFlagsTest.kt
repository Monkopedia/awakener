package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigFlags
import com.monkopedia.awakener.config.Flags
import com.monkopedia.awakener.registry.RegistryFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * What `:wm`'s wait flags refuse, which needs no compositor: it is all snapshot arithmetic.
 *
 * These exist because splitting one `private const` into three flags (#49) created a rule that
 * a constant had held by construction — the reservation grace could not be shorter than the map
 * deadline when they were the same number. Three flags can disagree, so the rule has to be
 * stated somewhere that checks it.
 */
class WmFlagsTest {
    init {
        // ConfigFlags too: the invalid-value mode is itself a flag, so a snapshot that does not
        // declare it reads `config.invalid_value` as a key nothing owns and reports a problem
        // that has nothing to do with what is under test.
        Flags.requireLoaded(WmFlags, ConfigFlags, RegistryFlags)
    }

    private fun snapshot(vararg values: Pair<String, Long>): Config =
        Config.of(values.associate { (key, value) -> key to JsonPrimitive(value) })

    /**
     * Through [Config.of], because that is the only place a cross-flag [Flags.constraint] is
     * ever evaluated (`Config.of`, where `Flags.allConstraints()` runs). This read
     * `Config.EMPTY.problems`, which reaches the primary constructor instead — and there
     * `problems` is a stored parameter defaulting to `emptyList()`, so the assertion was
     * `assertEquals(emptyList(), emptyList())` and stayed green for *any* violated constraint,
     * including this file's own. Measured: with `reservationGraceMs`'s default dropped below
     * `mapWaitMs`'s, the old line passed and the new one fails (#142).
     */
    @Test
    fun `the shipped defaults trip nothing`() {
        assertEquals(
            emptyList(),
            snapshot().problems,
            "an unconfigured system has to be correct, which is the whole rule about defaults",
        )
    }

    /**
     * The pair rule. Neither number is wrong alone, so nothing is degraded and the snapshot
     * stays total — reporting is the whole of what a cross-flag rule can honestly do.
     */
    @Test
    fun `a reservation grace below the map deadline is reported`() {
        val config = snapshot(
            "wm.wait.map_ms" to 5_000L,
            "wm.wait.reservation_grace_ms" to 1_000L,
        )

        val problem = config.problems.single()
        assertEquals(
            WmFlags.reservationGraceMs.key,
            problem.key,
            "the problem has to name the flag a reader should move, and lowering the map " +
                "deadline instead would shorten every attach",
        )
        assertTrue("1000" in problem.reason && "5000" in problem.reason, problem.reason)
        assertEquals(
            1_000L,
            config[WmFlags.reservationGraceMs],
            "a cross-flag rule degraded a value it can only report on",
        )
        assertEquals(5_000L, config[WmFlags.mapWaitMs])
    }

    @Test
    fun `a grace at or above the map deadline says nothing`() {
        assertEquals(
            emptyList(),
            snapshot(
                "wm.wait.map_ms" to 1_000L,
                "wm.wait.reservation_grace_ms" to 1_000L,
            ).problems,
            "equal is the shipped relationship and has to be quiet",
        )
        assertEquals(
            emptyList(),
            snapshot(
                "wm.wait.map_ms" to 1_000L,
                "wm.wait.reservation_grace_ms" to 9_000L,
            ).problems,
        )
    }

    /**
     * Lowering only the map deadline is the ordinary way to make a test or a slow panel work,
     * and it must not start reporting a rule the operator did not break: the default grace is
     * 5s, so every deadline below it is fine.
     */
    @Test
    fun `lowering only the map deadline is quiet`() {
        assertEquals(emptyList(), snapshot("wm.wait.map_ms" to 500L).problems)
    }

    /**
     * Raising only the map deadline past the default grace *is* the trap, and it is the reason
     * this is a declared rule rather than a sentence in a description: the operator changed one
     * number, the other stayed at a default that was correct until they did, and nothing about
     * the edit looks wrong.
     */
    @Test
    fun `raising only the map deadline past the default grace is reported`() {
        val config = snapshot("wm.wait.map_ms" to 30_000L)
        // Two rules trip, both correctly: a 30s map wait also outgrows the default attach-lock
        // wait (36s of spanreed + 30s + 5s > 60s), which `wm.attach.lock_wait_ms` reports.
        assertEquals(
            setOf(WmFlags.reservationGraceMs.key, WmFlags.attachLockWaitMs.key),
            config.problems.map { it.key }.toSet(),
        )
    }

    @Test
    fun `a negative wait is rejected at the flag rather than coerced at the read site`() {
        for (flag in listOf(
            WmFlags.mapWaitMs,
            WmFlags.unmapWaitMs,
            WmFlags.reservationGraceMs,
            WmFlags.pollSpinMs,
            WmFlags.pollIntervalMs,
            WmFlags.closeWaitMs,
        )) {
            val config = snapshot(flag.key to -1L)
            assertTrue(
                config.problems.any { it.key == flag.key && "at least 0" in it.reason },
                "${flag.key} accepted -1 in silence: ${config.problems}",
            )
            assertEquals(
                flag.default,
                config[flag],
                "${flag.key} did not degrade to its default",
            )
        }
    }

    /**
     * Zero is *not* rejected, and that is deliberate for the two poll flags — a zero spin paces
     * from the first read, which is a real configuration. It is accepted for the waits too,
     * where it means "fail immediately"; that is stated on each rather than forbidden, since a
     * caller pinning a deadline to zero is testing the expiry path.
     */
    @Test
    fun `zero is accepted`() {
        assertEquals(
            emptyList(),
            snapshot("wm.wait.poll_spin_ms" to 0L, "wm.wait.poll_interval_ms" to 0L).problems,
        )
    }

    /** #184: a prefix sway would read as more than a mark is refused, not written. */
    @Test
    fun `a mark prefix that is not a plain name degrades to the default`() {
        for (bad in listOf("x;exec touch /tmp/f;", "a b", "")) {
            val config = Config.of(mapOf(WmFlags.dockMarkPrefix.key to JsonPrimitive(bad)))
            assertTrue(
                config.problems.any { it.key == WmFlags.dockMarkPrefix.key },
                "'$bad' accepted in silence: ${config.problems}",
            )
            assertEquals(WmFlags.dockMarkPrefix.default, config[WmFlags.dockMarkPrefix])
        }
        assertEquals(
            "my.dock-",
            Config.of(mapOf(WmFlags.dockMarkPrefix.key to JsonPrimitive("my.dock-")))[WmFlags.dockMarkPrefix],
        )
    }

    // -- wm.attach.lock_wait_ms (#199) --------------------------------------------------

    /**
     * Long.MAX_VALUE is how somebody types "wait forever", and it overflowed the deadline
     * conversion into a refusal after 233ms. It is now out of the declared range, so it is
     * reported and degraded to the default instead of reaching the conversion.
     */
    @Test
    fun `a lock wait above the ceiling is reported and degraded`() {
        for (value in listOf(Long.MAX_VALUE, WmFlags.MAX_ATTACH_LOCK_WAIT_MS + 1)) {
            val config = snapshot("wm.attach.lock_wait_ms" to value)
            val problem = config.problems.singleOrNull { it.key == WmFlags.attachLockWaitMs.key }
            assertTrue(problem != null, "$value was not reported: ${config.problems}")
            assertEquals(
                WmFlags.attachLockWaitMs.default,
                config[WmFlags.attachLockWaitMs],
                "$value has to degrade to the default, not be coerced or used",
            )
        }
    }

    @Test
    fun `a negative lock wait is reported and degraded`() {
        val config = snapshot("wm.attach.lock_wait_ms" to -5L)
        assertTrue(config.problems.any { it.key == WmFlags.attachLockWaitMs.key }, "${config.problems}")
        assertEquals(WmFlags.attachLockWaitMs.default, config[WmFlags.attachLockWaitMs])
    }

    /** Both ends of the range are legal values: zero yields at once, the ceiling waits ten minutes. */
    @Test
    fun `the lock wait's boundaries are accepted`() {
        for (value in listOf(0L, WmFlags.MAX_ATTACH_LOCK_WAIT_MS)) {
            val config = snapshot("wm.attach.lock_wait_ms" to value)
            assertEquals(emptyList(), config.problems, "$value")
            assertEquals(value, config[WmFlags.attachLockWaitMs])
        }
    }

    /**
     * The default has to outlast the press it waits behind, and the bound is stated against the
     * whole press: two spanreed list runs and a mint at 10s + 2 x 1s each, plus the map and
     * unmap waits — 46s.
     */
    @Test
    fun `the default lock wait outlasts a whole press`() {
        assertEquals(46_000L, attachLockPressBoundMs(snapshot()))
        assertTrue(WmFlags.attachLockWaitMs.default >= attachLockPressBoundMs(snapshot()))
    }

    @Test
    fun `a lock wait shorter than a press is reported, and zero is not`() {
        val short = snapshot("wm.attach.lock_wait_ms" to 15_000L)
        val problem = short.problems.single()
        assertEquals(WmFlags.attachLockWaitMs.key, problem.key)
        assertTrue("15000" in problem.reason && "46000" in problem.reason, problem.reason)
        assertEquals(15_000L, short[WmFlags.attachLockWaitMs], "a pair rule reports, it does not degrade")

        assertEquals(emptyList(), snapshot("wm.attach.lock_wait_ms" to 0L).problems)
        val raised = snapshot("registry.agent.command_timeout_ms" to 30_000L)
        assertTrue(
            raised.problems.any { it.key == WmFlags.attachLockWaitMs.key },
            "raising the spanreed timeout past what the default covers has to say so: ${raised.problems}",
        )
    }
}
