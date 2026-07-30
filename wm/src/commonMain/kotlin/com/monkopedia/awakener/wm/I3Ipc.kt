package com.monkopedia.awakener.wm

/**
 * The i3/sway IPC wire format.
 *
 * A message is a 14-byte header — the ASCII magic `i3-ipc`, a 32-bit payload length, and a
 * 32-bit type — followed by a JSON payload. Replies use the same framing.
 *
 * The two length/type fields are written in the **host's native** byte order, not network
 * order. Every host awakener targets is little-endian, so this encodes little-endian
 * explicitly rather than dragging a platform byte-order abstraction into `commonMain`; a
 * big-endian host would need [littleEndian] threaded through.
 */
object I3Ipc {
    val MAGIC: ByteArray = byteArrayOf(0x69, 0x33, 0x2d, 0x69, 0x70, 0x63) // "i3-ipc"
    const val HEADER_SIZE: Int = 14

    /** Requests we issue. Values are protocol constants, not ours to renumber. */
    object Request {
        const val RUN_COMMAND: Int = 0
        const val SUBSCRIBE: Int = 2
        const val GET_TREE: Int = 4
        const val GET_MARKS: Int = 5
    }

    /**
     * Event replies carry the high bit set, so a reply type must be tested against this mask
     * before being compared to a [Request] code — otherwise an event is indistinguishable
     * from the reply to whatever request happened to be in flight.
     */
    const val EVENT_MASK: Int = 0x8000_0000.toInt()

    object Event {
        const val WINDOW: Int = 3
    }

    fun isEvent(type: Int): Boolean = (type and EVENT_MASK) != 0

    fun eventKind(type: Int): Int = type and EVENT_MASK.inv()

    fun encodeHeader(payloadLength: Int, type: Int, littleEndian: Boolean = true): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(out)
        writeInt(out, 6, payloadLength, littleEndian)
        writeInt(out, 10, type, littleEndian)
        return out
    }

    /**
     * @throws IllegalArgumentException if the magic is absent — which in practice means the
     * stream has desynchronised, and continuing would silently misread every later frame.
     */
    fun decodeHeader(header: ByteArray, littleEndian: Boolean = true): Header {
        require(header.size >= HEADER_SIZE) {
            "short IPC header: ${header.size} bytes, need $HEADER_SIZE"
        }
        for (i in MAGIC.indices) {
            require(header[i] == MAGIC[i]) { "bad IPC magic — stream desynchronised" }
        }
        return Header(
            payloadLength = readInt(header, 6, littleEndian),
            type = readInt(header, 10, littleEndian),
        )
    }

    data class Header(val payloadLength: Int, val type: Int)

    private fun writeInt(target: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        for (i in 0 until 4) {
            val shift = if (littleEndian) i * 8 else (3 - i) * 8
            target[offset + i] = ((value ushr shift) and 0xff).toByte()
        }
    }

    private fun readInt(source: ByteArray, offset: Int, littleEndian: Boolean): Int {
        var value = 0
        for (i in 0 until 4) {
            val shift = if (littleEndian) i * 8 else (3 - i) * 8
            value = value or ((source[offset + i].toInt() and 0xff) shl shift)
        }
        return value
    }
}
