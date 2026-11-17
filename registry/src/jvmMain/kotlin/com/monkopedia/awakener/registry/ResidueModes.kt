package com.monkopedia.awakener.registry

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * Takes group and other access off residue that already exists (#238).
 *
 * `PrivateFiles` governs creation, and residue created before #102 was never created by it:
 * those directories are `0755` and those files `0644`, and #210's migration is a rename, which
 * carries the mode along. So this is the half of #102 that applies to what is already on disk.
 *
 * **Only group and other bits are ever cleared.** That is "what awakener would have created" for
 * every ordinary case — `0755` becomes `0700`, `0644` becomes `0600`, a `0755` connector script
 * becomes `0700` and stays executable — and it can never widen anything: an owner-read-only file
 * stays read-only, which an absolute `0600` would have undone.
 *
 * **Symlinks are never followed and never changed.** The walk does not follow them, and every
 * mode is set through a `NOFOLLOW_LINKS` view, so a link inside the residue pointing out of it —
 * at a file or at a directory — leaves its target exactly as it was.
 *
 * Nothing here raises. An entry that cannot be read or changed is named in [Result.failed] and
 * the walk goes on; one that vanished mid-walk (a concurrent press, a connector cleaning up) is
 * not a failure.
 */
internal object ResidueModes {
    data class Result(val tightened: Int = 0, val failed: List<String> = emptyList()) {
        operator fun plus(other: Result) = Result(tightened + other.tightened, failed + other.failed)
    }

    private val OPEN = setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    )

    /**
     * Tightens [root], every directory between it and [location], and [location] — and, when
     * [recursive], everything under [location]. [location] must be under [root] (or be it), which
     * `RegistryPaths.containedResolve` guarantees for every residue path.
     *
     * [setMode] is the seam a test uses to make a `chmod` fail; production passes nothing.
     */
    fun tighten(
        root: Path,
        location: Path,
        recursive: Boolean,
        setMode: (Path, Set<PosixFilePermission>) -> Unit = ::setNoFollow,
    ): Result {
        if (!root.fileSystem.supportedFileAttributeViews().contains("posix")) return Result()
        val chain = generateSequence(location.parent) { it.parent }
            .takeWhile { it.startsWith(root) }
            .toList()
            .asReversed()
        val above = chain.fold(Result()) { acc, dir -> acc + one(dir, readAttributes(dir), setMode) }
        var walked = above
        // No FOLLOW_LINKS: a symlink is visited as itself, and `one` skips it. Depth 0 visits
        // [location] alone, and a [location] that does not exist is a NoSuchFileException.
        Files.walkFileTree(
            location,
            emptySet(),
            if (recursive) Int.MAX_VALUE else 0,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                    visit(dir, attrs)

                override fun visitFile(file: Path, attrs: BasicFileAttributes) = visit(file, attrs)

                override fun visitFileFailed(file: Path, exc: IOException) = failed(file, exc)

                override fun postVisitDirectory(dir: Path, exc: IOException?) = failed(dir, exc)

                private fun visit(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    walked += one(path, attrs as? PosixFileAttributes ?: readAttributes(path), setMode)
                    return FileVisitResult.CONTINUE
                }

                private fun failed(path: Path, exc: IOException?): FileVisitResult {
                    if (exc != null && exc !is NoSuchFileException) {
                        walked += Result(failed = listOf(describe(path, exc)))
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return walked
    }

    private fun one(
        path: Path,
        attrs: PosixFileAttributes?,
        setMode: (Path, Set<PosixFilePermission>) -> Unit,
    ): Result {
        if (attrs == null || !(attrs.isDirectory || attrs.isRegularFile)) return Result()
        val mode = attrs.permissions()
        if (mode.none { it in OPEN }) return Result()
        return try {
            setMode(path, mode - OPEN)
            Result(tightened = 1)
        } catch (_: NoSuchFileException) {
            Result()
        } catch (e: IOException) {
            Result(failed = listOf(describe(path, e)))
        }
    }

    /** Null for an entry that is not there, or one whose mode cannot be read — reported by the walk. */
    private fun readAttributes(path: Path): PosixFileAttributes? = try {
        Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    }

    private fun setNoFollow(path: Path, mode: Set<PosixFilePermission>) {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            .setPermissions(mode)
    }

    private fun describe(path: Path, e: IOException) = "$path (${e.message ?: e})"
}
