package com.monkopedia.awakener.wm

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Where sway's IPC socket is, for a process that may have to ask more than once.
 *
 * [SwayConnection.open] is deliberately a dumb primitive — it opens the path it is given — and that
 * was enough while a manager connected once and kept the connection for the life of the process.
 * It stops being enough the moment anything reconnects, because **the environment cannot be
 * re-read**: `SWAYSOCK` is fixed at exec, and with it unset sway names its socket
 * `$XDG_RUNTIME_DIR/sway-ipc.<uid>.<pid>.sock`, so a successor session's path differs from the dead
 * one's by a pid nothing told awakener. A long-lived awakener re-reading `System.getenv` after a
 * sway restart gets the same dead path forever.
 *
 * So the successor is *found* rather than remembered, and only where the remembered one has stopped
 * answering. That ordering is what keeps a live desktop's behaviour identical: nothing here runs
 * until a connect fails.
 */
object SwaySocket {
    /**
     * A connection to sway, from the configured path, the environment, or discovery in that order.
     *
     * @param configured `wm.ipc.socket_path`, or null/blank for "use the environment". A configured
     * path is **authoritative**: it is opened and its failure is raised, with no discovery, because
     * a path an operator typed names the compositor they meant and falling back would silently talk
     * to another one.
     * @param discover `wm.ipc.socket_discovery`.
     */
    fun connect(configured: String?, discover: Boolean): SwayConnection =
        connect(configured, System.getenv("SWAYSOCK"), System.getenv("XDG_RUNTIME_DIR"), discover)

    /**
     * [connect] with the environment passed in, which is the form a test can drive.
     *
     * A JVM cannot set its own environment variables, so the two the resolution depends on are
     * parameters here and read from the environment exactly once, at the public entry point above.
     * That is also why this is `internal` rather than private: the alternative is a resolver whose
     * behaviour can only be observed by rebuilding the process.
     */
    internal fun connect(
        configured: String?,
        swaysock: String?,
        runtimeDir: String?,
        discover: Boolean,
    ): SwayConnection {
        if (!configured.isNullOrBlank()) return SwayConnection.open(configured)
        val named = swaysock?.takeIf { it.isNotBlank() }
        val refusal = named?.let { path ->
            runCatching { SwayConnection.open(path) }.onSuccess { return it }.exceptionOrNull()
        }
        if (!discover) {
            // The previous behaviour exactly: whatever SWAYSOCK said, including its absence.
            throw refusal ?: SwayConnection.noSocket()
        }
        val candidates = successors(runtimeDir)
        candidates.forEach { path ->
            runCatching { SwayConnection.open(path.absolutePathString()) }
                .onSuccess { return it }
        }
        val tried = candidates.joinToString { it.name }.ifEmpty { "none" }
        throw IllegalStateException(
            "no reachable sway IPC socket: SWAYSOCK=${named ?: "<unset>"}" +
                (refusal?.message?.let { " ($it)" } ?: "") +
                "; under ${runtimeDir ?: "<no XDG_RUNTIME_DIR>"} the sockets tried were $tried",
            refusal,
        )
    }

    /**
     * Sockets in [runtimeDir] that sway would have named, newest first.
     *
     * Newest first because the newest is the successor: the pid in the name is not ordered, and a
     * dead session's socket file is left behind on a `SIGKILL` — sway unlinks it on a clean exit
     * and cannot on a crash, which is precisely the case this exists for. Modification time is what
     * separates them, and a stale file that still sorts first is handled by trying the next one:
     * nothing here trusts a name, it just decides what order to try connecting in.
     *
     * The uid field is not matched. `XDG_RUNTIME_DIR` is this user's own directory, mode 0700, so a
     * socket in it is this user's by construction, and matching the uid would be a second check on
     * something the directory already decides — while breaking anyone who points
     * `XDG_RUNTIME_DIR` somewhere of their own.
     */
    internal fun successors(runtimeDir: String?): List<Path> {
        val dir = runtimeDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: return emptyList()
        if (!dir.isDirectory()) return emptyList()
        return runCatching {
            dir.listDirectoryEntries()
                .filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
                .sortedByDescending {
                    runCatching { it.getLastModifiedTime().toMillis() }.getOrDefault(0L)
                }
        }.getOrDefault(emptyList())
    }

    private const val PREFIX = "sway-ipc."
    private const val SUFFIX = ".sock"
}
