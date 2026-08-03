package com.monkopedia.awakener.registry

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A hand-rolled digest that is wrong is still perfectly stable, so nothing downstream would ever
 * notice — the slugs would just be a different, weaker function than the one the security
 * argument in [SurfaceKey] is made about. These pin it to the real thing.
 */
class Sha256Test {
    @Test
    fun `matches the published vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ByteArray(0).sha256().toHex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abc".encodeToByteArray().sha256().toHex(),
        )
    }

    /**
     * Lengths either side of the 64-byte block and of the 56-byte point where the length field
     * no longer fits in the final block — the two places a padding mistake hides — plus enough
     * random input to catch an error in the compression function itself.
     */
    @Test
    fun `agrees with the JDK across block boundaries and random input`() {
        val jdk = MessageDigest.getInstance("SHA-256")
        val random = Random(13)
        val lengths = (0..130).toList() + listOf(255, 256, 1000)
        lengths.forEach { length ->
            val input = random.nextBytes(length)
            assertEquals(
                jdk.digest(input).toHex(),
                input.sha256().toHex(),
                "digest of $length bytes",
            )
        }
    }
}
