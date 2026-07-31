package com.monkopedia.awakener.wm

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.test.fail

/**
 * sway's IPC socket with a valve on it: one request of the test's choosing is held on its way to
 * the compositor until the test lets it through.
 *
 * The races `attach`'s unwind has to survive are the ones where a dock maps *inside* a gap that is
 * a round trip wide — between the map deadline expiring and the unwind reading the tree, or
 * between that read and the `split none` it sends next. A test cannot land a window in a gap that
 * narrow by sleeping for the right length of time; the reviewer who found the first of them needed
 * a sweep of `sleep` offsets to hit it once in four to eight runs. Holding one request turns the
 * gap into one the test opens and closes, so the same scenario is a single deterministic run.
 *
 * Relays strictly one request to one reply, which is the whole of the i3 protocol on a connection
 * that has not subscribed to events — and the manager's command connection never does, since
 * [SwayWindowManager.changes] takes a second one.
 */
class SwayValve private constructor(
    private val server: ServerSocketChannel,
    private val target: Path,
    val socket: Path,
) : AutoCloseable {
    /** Set while a request is still to be caught; cleared by the one that matches. */
    @Volatile
    private var hold: ((type: Int, payload: String) -> Boolean)? = null

    private val held = CountDownLatch(1)
    private val released = CountDownLatch(1)

    /**
     * Holds the first request [predicate] accepts, until [release].
     *
     * The predicate is called on every request in order, on the relaying thread, so it can also be
     * used to remember what has gone past — "the first tree read after the exec" is a state
     * machine, not a property of one frame.
     */
    fun holdNext(predicate: (type: Int, payload: String) -> Boolean) {
        hold = predicate
    }

    /** Blocks until [holdNext]'s predicate has caught a request. */
    fun awaitHeld(timeoutMs: Long) {
        if (!held.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            fail("no request matched the valve's predicate within ${timeoutMs}ms")
        }
    }

    fun release() {
        released.countDown()
    }

    private fun serve() {
        while (server.isOpen) {
            val client = try {
                server.accept()
            } catch (closed: IOException) {
                return
            }
            Thread { relay(client) }.apply { isDaemon = true }.start()
        }
    }

    private fun relay(client: SocketChannel) {
        try {
            client.use {
                SocketChannel.open(UnixDomainSocketAddress.of(target.absolutePathString()))
                    .use { sway ->
                        while (true) {
                            val request = read(client) ?: return
                            val header = I3Ipc.decodeHeader(request.first)
                            val caught = hold?.invoke(header.type, request.second.decodeToString())
                            if (caught == true) {
                                hold = null
                                held.countDown()
                                released.await()
                            }
                            write(sway, request)
                            write(client, read(sway) ?: return)
                        }
                    }
            }
        } catch (gone: IOException) {
            // Either end going away ends this relay and nothing else: the test's assertions are
            // about sway's tree, not about the valve.
        }
    }

    private fun read(from: SocketChannel): Pair<ByteArray, ByteArray>? {
        val header = readFully(from, I3Ipc.HEADER_SIZE) ?: return null
        val payload = readFully(from, I3Ipc.decodeHeader(header).payloadLength) ?: return null
        return header to payload
    }

    private fun readFully(from: SocketChannel, length: Int): ByteArray? {
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            if (from.read(buffer) < 0) return null
        }
        return buffer.array()
    }

    private fun write(to: SocketChannel, frame: Pair<ByteArray, ByteArray>) {
        val buffer = ByteBuffer.allocate(frame.first.size + frame.second.size)
        buffer.put(frame.first).put(frame.second).flip()
        while (buffer.hasRemaining()) to.write(buffer)
    }

    /** Lets any held request through first: a relay parked on the latch would never notice. */
    override fun close() {
        released.countDown()
        server.close()
        socket.deleteIfExists()
    }

    companion object {
        fun open(target: Path): SwayValve {
            // Beside sway's own socket, which the harness keeps in a short temp path — a unix
            // socket address is limited to about 108 bytes and a deep path silently fails to bind.
            val socket = target.resolveSibling("awakener-valve.sock")
            socket.deleteIfExists()
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server.bind(UnixDomainSocketAddress.of(socket.absolutePathString()))
            return SwayValve(server, target, socket).also { valve ->
                Thread { valve.serve() }.apply { isDaemon = true }.start()
            }
        }
    }
}
