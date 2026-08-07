package com.monkopedia.awakener.wm

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.absolutePathString
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.io.path.setLastModifiedTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Finding the successor session's socket, which is the half of #33 no compositor can answer.
 *
 * The manager's side of reconnection is "call `connect()` again"; this is what `connect()` has to
 * do to come back with anything. It needs no sway because the question is not what a client
 * observes — it is which path a process that cannot re-read its own environment should try, and in
 * what order. Real unix sockets stand in for compositors: a bound one accepts, and one whose
 * listener has gone but whose file is still on disk is exactly what a `SIGKILL`ed sway leaves
 * behind, which is the case the whole mechanism exists for.
 */
class SwaySocketTest {
    private lateinit var runtimeDir: Path

    @BeforeTest
    fun setUp() {
        runtimeDir = Files.createTempDirectory("awakener-swaysock")
    }

    @AfterTest
    fun tearDown() {
        listening.forEach { it.close() }
        listening.clear()
        runtimeDir.toFile().deleteRecursively()
    }

    private val listening = mutableListOf<ServerSocketChannel>()

    @Test
    fun `only sway's own sockets are candidates, newest first`() {
        val old = dead("sway-ipc.1000.11.sock", modified = 1_000)
        val new = dead("sway-ipc.1000.12.sock", modified = 3_000)
        val middle = dead("sway-ipc.1000.13.sock", modified = 2_000)
        file("pulse-shm-1234", modified = 9_000)
        file("sway-ipc.1000.14.sock.bak", modified = 9_000)

        assertEquals(
            listOf(new.name, middle.name, old.name),
            SwaySocket.successors(runtimeDir.absolutePathString()).map { it.name },
            "newest first, because the newest is the successor — and only sockets sway would " +
                "have named, or discovery becomes 'connect to whatever is in the runtime dir'",
        )
    }

    @Test
    fun `no runtime directory is no candidates rather than a failure`() {
        assertEquals(emptyList(), SwaySocket.successors(null))
        assertEquals(emptyList(), SwaySocket.successors(""))
        assertEquals(
            emptyList(),
            SwaySocket.successors(runtimeDir.resolve("nothing-here").absolutePathString()),
        )
    }

    /**
     * The case the flag exists for: `SWAYSOCK` names the session that died, because a process
     * cannot re-read its own environment and the successor's path carries a pid nobody told it.
     */
    @Test
    fun `a dead SWAYSOCK is replaced by the socket that answers`() {
        val stale = dead("sway-ipc.1000.11.sock", modified = 1_000)
        live("sway-ipc.1000.12.sock", modified = 2_000).use {
            SwaySocket.connect(
                configured = null,
                swaysock = stale.absolutePathString(),
                runtimeDir = runtimeDir.absolutePathString(),
                discover = true,
            ).close()
        }
    }

    /**
     * And the newest is tried first rather than merely listed first, which is the whole of the
     * ordering being worth anything: two sockets that both answer are two compositors, and the
     * successor is the later one.
     */
    @Test
    fun `the newest socket is the one connected to`() {
        val older = live("sway-ipc.1000.11.sock", modified = 1_000)
        val newer = live("sway-ipc.1000.12.sock", modified = 5_000)

        SwaySocket.connect(
            configured = null,
            swaysock = null,
            runtimeDir = runtimeDir.absolutePathString(),
            discover = true,
        ).close()

        assertNotNull(accepted(newer), "the newer socket got no connection")
        assertEquals(null, accepted(older), "and the older one must not have been tried at all")
    }

    /**
     * Discovery is a fallback and never an override. A path an operator typed names the compositor
     * they meant, so its failure is theirs to see — quietly connecting to a different sway is the
     * one outcome nobody asked for.
     */
    @Test
    fun `a configured path is authoritative even when another socket would answer`() {
        val configured = dead("sway-ipc.1000.11.sock", modified = 1_000)
        live("sway-ipc.1000.12.sock", modified = 2_000).use {
            assertFailsWith<Exception> {
                SwaySocket.connect(
                    configured = configured.absolutePathString(),
                    swaysock = null,
                    runtimeDir = runtimeDir.absolutePathString(),
                    discover = true,
                )
            }
            assertEquals(
                null,
                accepted(it),
                "the live socket must not have been tried: a configured path that fails is a " +
                    "refusal, not the first entry in a search",
            )
        }
    }

    @Test
    fun `discovery off leaves a dead SWAYSOCK dead`() {
        val stale = dead("sway-ipc.1000.11.sock", modified = 1_000)
        live("sway-ipc.1000.12.sock", modified = 2_000).use {
            assertFailsWith<Exception> {
                SwaySocket.connect(
                    configured = null,
                    swaysock = stale.absolutePathString(),
                    runtimeDir = runtimeDir.absolutePathString(),
                    discover = false,
                )
            }
        }
    }

    @Test
    fun `nothing to connect to says so, and says what it tried`() {
        val stale = dead("sway-ipc.1000.11.sock", modified = 1_000)

        val failure = assertFailsWith<IllegalStateException> {
            SwaySocket.connect(
                configured = null,
                swaysock = stale.absolutePathString(),
                runtimeDir = runtimeDir.absolutePathString(),
                discover = true,
            )
        }
        val message = assertNotNull(failure.message)
        assertTrue(
            stale.name in message && runtimeDir.absolutePathString() in message,
            "a reconnect that found nothing has to name what it looked at, since the whole " +
                "failure is about paths: was '$message'",
        )
    }

    // -- helpers ------------------------------------------------------------------------

    /** A socket with something listening on it. */
    private fun live(name: String, modified: Long): ServerSocketChannel {
        val path = runtimeDir.resolve(name)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path.absolutePathString()))
        server.configureBlocking(false)
        listening += server
        path.setLastModifiedTime(FileTime.fromMillis(modified))
        return server
    }

    /**
     * A socket file with nothing listening — what a compositor that was killed leaves behind.
     *
     * Bound and then closed rather than merely `touch`ed, because Java does not unlink a unix
     * socket on close and neither does a crashed process: the inode that is left is a socket, and
     * connecting to it fails in the way a dead session's does.
     */
    private fun dead(name: String, modified: Long): Path {
        val server = live(name, modified)
        server.close()
        listening -= server
        val path = runtimeDir.resolve(name)
        path.setLastModifiedTime(FileTime.fromMillis(modified))
        return path
    }

    private fun file(name: String, modified: Long): Path =
        runtimeDir.resolve(name).createFile().also {
            it.setLastModifiedTime(FileTime.fromMillis(modified))
        }

    /**
     * The connection [server] was given, or null if it was never tried.
     *
     * Polled rather than asked once: a connect lands in the listener's backlog before `accept`
     * returns it, and a bounded poll answers both directions honestly — it is the difference
     * between "not yet" and "not at all" for the positive assertions, and time in which a
     * connection nobody expected could still turn up for the negative ones.
     */
    private fun accepted(server: ServerSocketChannel): Any? {
        val deadline = System.nanoTime() + ACCEPT_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            server.accept()?.let { return it }
            Thread.sleep(5)
        }
        return null
    }

    private companion object {
        const val ACCEPT_TIMEOUT_NANOS = 500_000_000L
    }
}
