package com.monkopedia.awakener.registry

/**
 * SHA-256 (FIPS 180-4) over the receiver, as its 32 raw bytes.
 *
 * Hand-rolled for the same reason `:wm` hand-rolls the i3 wire format: `commonMain` has no
 * hashing, and the alternatives are worse than sixty lines of arithmetic that has not changed
 * since 2001. A crypto dependency to disambiguate filenames is out of proportion, and an
 * `expect`/`actual` over `java.security.MessageDigest` would close the Native target this
 * module is deliberately kept open for.
 *
 * `Sha256Test` pins it against the JDK's implementation across the block-boundary lengths where
 * a padding mistake hides, because a wrong digest here would be perfectly stable and therefore
 * invisible.
 */
internal fun ByteArray.sha256(): ByteArray {
    val state = INITIAL_STATE.copyOf()
    val bitLength = size.toLong() * 8
    // One 0x80 byte, then zeroes, then the length as 64 big-endian bits, rounded up to a block.
    val padded = ByteArray(((size + 9 + 63) / 64) * 64)
    copyInto(padded)
    padded[size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
    }

    val schedule = IntArray(64)
    var block = 0
    while (block < padded.size) {
        for (i in 0 until 16) {
            val at = block + i * 4
            schedule[i] = (padded[at].toInt() and 0xff shl 24) or
                (padded[at + 1].toInt() and 0xff shl 16) or
                (padded[at + 2].toInt() and 0xff shl 8) or
                (padded[at + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val prev = schedule[i - 15]
            val recent = schedule[i - 2]
            val s0 = prev.rotateRight(7) xor prev.rotateRight(18) xor (prev ushr 3)
            val s1 = recent.rotateRight(17) xor recent.rotateRight(19) xor (recent ushr 10)
            schedule[i] = schedule[i - 16] + s0 + schedule[i - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + choice + ROUND_CONSTANTS[i] + schedule[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + majority
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
        block += 64
    }

    return ByteArray(32) { i -> (state[i / 4] ushr (24 - 8 * (i % 4))).toByte() }
}

/** Lowercase hex, two digits per byte. */
internal fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { byte ->
        append(HEX_DIGITS[byte.toInt() shr 4 and 0xf])
        append(HEX_DIGITS[byte.toInt() and 0xf])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

/** The first 32 bits of the fractional parts of the square roots of the first eight primes. */
private val INITIAL_STATE = intArrayOf(
    0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
    0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
)

/** The first 32 bits of the fractional parts of the cube roots of the first 64 primes. */
private val ROUND_CONSTANTS = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)
