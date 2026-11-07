package com.monkopedia.awakener.config

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Creates files and directories with the permissions [FilePermissions] asks for, instead of with
 * whatever the process umask happens to be (#102).
 *
 * **The permissions go on at creation, as a file attribute**, not as a `chmod` after the fact.
 * `open` then `chmod` leaves the file readable for the width of two syscalls, and the whole
 * content of this class is that nobody should have to reason about how wide that is. The one
 * place a mode is set after the fact is [open] finding a staging file a crashed run left behind:
 * that file already exists, so its creation attribute was never consulted, and tightening it is
 * the only lever left.
 *
 * Both stores in this build write through here so the two cannot drift — `:config` and
 * `:registry` share a directory convention and a lock-file convention already, and someone will
 * read one for guidance on the other.
 *
 * Non-POSIX filesystems are handled by doing nothing: `supportedFileAttributeViews` is asked
 * before any permission is named, so a store over a filesystem with no POSIX mode bits behaves
 * exactly as it did before this existed rather than raising `UnsupportedOperationException` out
 * of the write path.
 */
object PrivateFiles {
    /** `rwx------` — a directory only its owner may enter or list. */
    private val OWNER_ONLY_DIRECTORY: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rwx------")

    /** `rw-------` — a file only its owner may read or write. */
    private val OWNER_ONLY_FILE: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rw-------")

    /**
     * Creates [dir] and every missing ancestor.
     *
     * The mode goes on **each** directory this call creates, ancestors included, which is the
     * behaviour that matters for a state directory pointed at a fresh path: creating
     * `<new>/awakener/residue` at `0700` under an ancestor left at `0755` would have moved the
     * problem up one level rather than fixed it. Directories that already exist are left exactly
     * as they are — this creates, it does not audit; whether an existing residue directory is
     * exposed is `registry.residue.exposed_dir`'s question and it is asked separately.
     */
    fun createDirectories(dir: Path, permissions: FilePermissions): Path =
        Files.createDirectories(dir, *attributes(dir, permissions, OWNER_ONLY_DIRECTORY))

    /** Creates the directory [file] will live in, and every missing ancestor of it. */
    fun createParentDirectories(file: Path, permissions: FilePermissions): Path {
        file.parent?.let { createDirectories(it, permissions) }
        return file
    }

    /** Creates [file], empty. Raises `FileAlreadyExistsException` exactly as `Files.createFile`. */
    fun createFile(file: Path, permissions: FilePermissions): Path =
        Files.createFile(file, *attributes(file, permissions, OWNER_ONLY_FILE))

    /**
     * Opens [file] with [options], creating it with the requested permissions if it is not there.
     *
     * If it *is* there — a per-pid staging file a crashed run left behind is the case this build
     * actually has — the creation attribute is not consulted by the platform, so the mode is set
     * explicitly afterwards. That set is allowed to fail: the file may belong to somebody else,
     * in which case the write about to happen will fail on its own and say so with a better
     * message than a `chmod` error would.
     */
    fun open(file: Path, permissions: FilePermissions, options: Set<OpenOption>): FileChannel {
        val channel = FileChannel.open(
            file,
            options,
            *attributes(file, permissions, OWNER_ONLY_FILE),
        )
        restrict(file, permissions)
        return channel
    }

    /** Writes [text] as UTF-8, creating or truncating [file]. */
    fun writeString(file: Path, text: String, permissions: FilePermissions) {
        openTruncating(file, permissions).use { it.writeFully(text) }
    }

    /**
     * Replaces [target] with [text] so that a crash at any point leaves either the old contents
     * or the new ones — never an empty or truncated file. Both stores in this build that hold
     * something worth keeping write through here: the config file, which is hand-authored and
     * has no source to rebuild it from, and the bindings file (#192).
     *
     * The order is the whole of it:
     *
     * 1. **Write [staging] and fsync it.** Without this the rename can land while the contents
     *    it points at are still in page cache, and on ext4 with delayed allocation a power loss
     *    then leaves a zero-length file under the target's name.
     * 2. **Rename it over [target]**, atomically, so a concurrent reader sees the old file or
     *    the new one and never a partial write.
     * 3. **Fsync the directory**, which is what makes the rename itself durable rather than a
     *    directory entry still in page cache that a crash can roll back.
     *
     * The permissions go on the staging file and the rename carries them to [target] with the
     * contents, which is also what tightens a file an earlier build created 0644 — no migration
     * step, and no window in which a file exists at one mode and is about to be another (#102).
     *
     * [steps] is the seam that lets a test assert that order; production passes nothing.
     */
    fun replace(
        target: Path,
        staging: Path,
        text: String,
        permissions: FilePermissions,
        steps: ReplaceSteps = ReplaceSteps,
    ) {
        createParentDirectories(target, permissions)
        openTruncating(staging, permissions).use { channel ->
            channel.writeFully(text)
            steps.syncFile(staging, channel)
        }
        steps.move(staging, target)
        steps.syncDirectory(target.toAbsolutePath().parent)
    }

    /**
     * The three filesystem steps [replace] takes, one method each so a test can record their
     * order. The companion is the real implementation and the only one production uses.
     */
    interface ReplaceSteps {
        fun syncFile(file: Path, channel: FileChannel) = channel.force(true)

        fun move(from: Path, to: Path) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        /**
         * Best effort, deliberately: by the time this runs the rename has landed and the new
         * contents are what every reader sees, so failing the write here would report a change
         * that took effect as one that did not. Linux opens and fsyncs a directory without
         * complaint; a platform that will not open one for reading is a platform this cannot
         * help on.
         */
        fun syncDirectory(dir: Path) {
            try {
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: IOException) {
                // Deliberate: see above.
            }
        }

        companion object : ReplaceSteps
    }

    private fun openTruncating(file: Path, permissions: FilePermissions): FileChannel = open(
        file,
        permissions,
        setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
    )

    private fun FileChannel.writeFully(text: String) {
        val bytes = ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8))
        while (bytes.hasRemaining()) write(bytes)
    }

    /**
     * Tightens [path] that already exists. A no-op under [FilePermissions.UMASK] and on a
     * filesystem with no POSIX mode bits; see [open] for why a failure is swallowed.
     */
    private fun restrict(path: Path, permissions: FilePermissions) {
        if (permissions != FilePermissions.OWNER_ONLY || !supportsPosix(path)) return
        val mode = if (Files.isDirectory(path)) OWNER_ONLY_DIRECTORY else OWNER_ONLY_FILE
        try {
            Files.setPosixFilePermissions(path, mode)
        } catch (_: IOException) {
            // Deliberate: see the KDoc on [open].
        }
    }

    private fun attributes(
        path: Path,
        permissions: FilePermissions,
        mode: Set<PosixFilePermission>,
    ): Array<FileAttribute<*>> =
        if (permissions == FilePermissions.OWNER_ONLY && supportsPosix(path)) {
            arrayOf(PosixFilePermissions.asFileAttribute(mode))
        } else {
            emptyArray()
        }

    private fun supportsPosix(path: Path): Boolean =
        path.fileSystem.supportedFileAttributeViews().contains("posix")
}
