package com.monkopedia.awakener.wm

import com.monkopedia.awakener.config.Config
import com.monkopedia.awakener.config.ConfigFlags
import com.monkopedia.awakener.config.Flags
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
        Flags.requireLoaded(WmFlags, ConfigFlags)
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
        assertEquals(WmFlags.reservationGraceMs.key, config.problems.single().key)
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
}
