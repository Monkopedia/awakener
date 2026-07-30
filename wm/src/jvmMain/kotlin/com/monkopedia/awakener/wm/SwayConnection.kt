package com.monkopedia.awakener.wm

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * A single connection to sway's IPC socket.
 *
 * The protocol has no request ids — replies come back strictly in order — so one connection
 * can carry only one in-flight request at a time. [request] therefore serialises callers
 * rather than letting concurrent coroutines interleave frames and read each other's replies.
 *
 * A connection that has been subscribed to events must not be used for requests, because
 * events arrive interleaved with replies; use a dedicated connection for [subscribe].
 */
class SwayConnection private constructor(private val channel: SocketChannel) : AutoCloseable {
    private val lock = Mutex()

    suspend fun request(type: Int, payload: String = ""): String = lock.withLock {
        withContext(Dispatchers.IO) {
            writeMessage(type, payload)
            readMessage().second
        }
    }

    /**
     * Subscribes this connection to [events] and hands each raw payload to [onEvent] until the
     * connection is closed. Blocks the calling coroutine; run it on its own connection.
     */
    suspend fun subscribe(events: List<String>, onEvent: suspend (kind: Int, payload: String) -> Unit) {
        withContext(Dispatchers.IO) {
            writeMessage(I3Ipc.Request.SUBSCRIBE, Json.encodeToString(events))
            val (_, ack) = readMessage()
            check(swayJson.decodeFromString<CommandResult>(ack).success) {
                "sway refused the event subscription: $ack"
            }
            while (channel.isOpen) {
                val (type, payload) = try {
                    readMessage()
                } catch (e: IOException) {
                    return@withContext // closed underneath us; a normal shutdown
                }
                if (I3Ipc.isEvent(type)) onEvent(I3Ipc.eventKind(type), payload)
            }
        }
    }

    private fun writeMessage(type: Int, payload: String) {
        val body = payload.toByteArray()
        val frame = ByteBuffer.allocate(I3Ipc.HEADER_SIZE + body.size)
        frame.put(I3Ipc.encodeHeader(body.size, type))
        frame.put(body)
        frame.flip()
        while (frame.hasRemaining()) channel.write(frame)
    }

    private fun readMessage(): Pair<Int, String> {
        val header = I3Ipc.decodeHeader(readFully(I3Ipc.HEADER_SIZE))
        return header.type to readFully(header.payloadLength).decodeToString()
    }

    private fun readFully(length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw IOException("sway closed the IPC socket")
        }
        return buffer.array()
    }

    override fun close() = channel.close()

    companion object {
        /**
         * @param socketPath sway's IPC socket; defaults to `SWAYSOCK`, which sway sets for
         * processes it spawns and honours when creating the socket.
         */
        fun open(socketPath: String? = null): SwayConnection {
            val path = socketPath
                ?: System.getenv("SWAYSOCK")
                ?: throw IllegalStateException(
                    "no sway socket: pass one explicitly or set SWAYSOCK",
                )
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            channel.connect(UnixDomainSocketAddress.of(path))
            return SwayConnection(channel)
        }
    }
}
