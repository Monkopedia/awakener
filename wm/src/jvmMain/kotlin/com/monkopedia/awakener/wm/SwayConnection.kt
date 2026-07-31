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

    /**
     * Set before the socket is torn down, so that a read failing because *we* closed this
     * connection is separable from one failing because the compositor went away. The socket
     * itself cannot tell them apart — both arrive as an [IOException] on the blocked read — and
     * they are the two endings [subscribe] has to report differently.
     */
    @Volatile
    private var closedByCaller = false

    suspend fun request(type: Int, payload: String = ""): String = lock.withLock {
        withContext(Dispatchers.IO) {
            writeMessage(type, payload)
            readMessage().second
        }
    }

    /**
     * Subscribes this connection to [events] and hands each raw payload to [onEvent].
     *
     * Returns when the caller closes this connection, and throws [CompositorSessionEnded] when
     * the compositor ends it instead. Blocks the calling coroutine; run it on its own connection.
     *
     * The distinction is the point. Both endings reach a blocked reader as an [IOException], and
     * treating them alike — which this did, as "closed underneath us; a normal shutdown" — makes
     * a dead compositor indistinguishable from a desktop on which nothing is happening. The
     * second is the product's resting state; the first means every handle held above here
     * describes a session that has stopped existing.
     *
     * [request] deliberately keeps no such rule: a reply sway had already written to the socket
     * buffer arrives after the process is gone (measured, 6 of 6 with 5ms of slack), so "this
     * request failed" and "the session is over" are separate events and only one of them is here.
     */
    suspend fun subscribe(events: List<String>, onEvent: suspend (kind: Int, payload: String) -> Unit) {
        withContext(Dispatchers.IO) {
            // Null is the caller's own close(); anything else the socket does is the session
            // ending. onEvent stays outside, so a callback's own failure is never reported as one.
            fun <T> onSocket(io: () -> T): T? = try {
                io()
            } catch (broken: IOException) {
                if (closedByCaller) null
                else throw CompositorSessionEnded("sway closed the IPC socket", broken)
            }

            onSocket { writeMessage(I3Ipc.Request.SUBSCRIBE, Json.encodeToString(events)) }
                ?: return@withContext
            val (_, ack) = onSocket { readMessage() } ?: return@withContext
            check(swayJson.decodeFromString<CommandResult>(ack).success) {
                "sway refused the event subscription: $ack"
            }
            while (channel.isOpen) {
                val (type, payload) = onSocket { readMessage() } ?: return@withContext
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

    // The flag is set first on purpose: a reader blocked in readMessage wakes on the close, and
    // it has to find the reason already recorded.
    override fun close() {
        closedByCaller = true
        channel.close()
    }

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
