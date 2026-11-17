package com.monkopedia.awakener.registry

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
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
 * **No path below [tighten]'s `root` is ever resolved as a string.** The residue is written by the
 * Lifeless and its connectors while this runs, and a walk by path — `Files.walkFileTree` — re-reads
 * every entry from its full path, so a directory swapped for a symlink after it was classified
 * sends every child path through the link and out of the root; `NOFOLLOW_LINKS` only guards the
 * last component (found in review of #241, by a seam and by a real race). So the walk goes through
 * [SecureDirectoryStream]: each directory is opened relative to its parent's open descriptor with
 * `NOFOLLOW_LINKS`, each entry is stat'd and chmod'ed by its single name relative to the directory
 * holding it, and a directory's own mode is set on the descriptor it was opened as. An entry swapped
 * for a symlink between the stat and the open fails the open and is reported; it is never followed.
 * A platform whose directory stream is not secure tightens nothing and says so, rather than falling
 * back to walking by path.
 *
 * Nothing here raises. An entry that cannot be read or changed is named in [Result.failed] and
 * the walk goes on; one that vanished mid-walk (a concurrent press, a connector cleaning up) is
 * not a failure.
 */
internal object ResidueModes {
    data class Result(val tightened: Int = 0, val failed: List<String> = emptyList())

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
     * [root] itself is opened by path: it is what `registry.residue.dir` names, so following a
     * link there is following the configuration. Everything below it is reached by descriptor.
     *
     * [beforeSet] runs with the entry's path just before its mode is set, and is the seam a test
     * uses to fail a set or to swap a directory under the walk; production passes nothing.
     */
    fun tighten(
        root: Path,
        location: Path,
        recursive: Boolean,
        beforeSet: (Path) -> Unit = {},
    ): Result {
        if (!root.fileSystem.supportedFileAttributeViews().contains("posix")) return Result()
        val segments = root.relativize(location).filter { it.toString().isNotEmpty() }
        require(segments.none { it.toString() == ".." }) { "$location is not under $root" }
        val walk = Walk(recursive, beforeSet)
        val stream = try {
            Files.newDirectoryStream(root)
        } catch (_: NoSuchFileException) {
            return Result()
        } catch (e: IOException) {
            return Result(failed = listOf(describe(root, e)))
        }
        stream.use {
            if (it !is SecureDirectoryStream<Path>) {
                return Result(
                    failed = listOf(
                        "$root (${it.javaClass.simpleName} is not a SecureDirectoryStream on " +
                            "this platform, so nothing was tightened rather than walking by path)",
                    ),
                )
            }
            walk.toLocation(it, root, segments)
        }
        return Result(walk.tightened, walk.failed)
    }

    private class Walk(val recursive: Boolean, val beforeSet: (Path) -> Unit) {
        var tightened = 0
        val failed = mutableListOf<String>()

        /** Tightens the directory [dir] is open on, then follows [remaining] names to the location. */
        fun toLocation(dir: SecureDirectoryStream<Path>, at: Path, remaining: List<Path>) {
            set(at, dir.getFileAttributeView(PosixFileAttributeView::class.java), null)
            if (remaining.isEmpty()) {
                if (recursive) contents(dir, at)
                return
            }
            val name = remaining.first()
            val child = at.resolve(name.toString())
            val attrs = stat(dir, name, child) ?: return
            when {
                attrs.isDirectory -> open(dir, name, child)?.use {
                    toLocation(it, child, remaining.drop(1))
                }
                attrs.isRegularFile && remaining.size == 1 -> set(child, view(dir, name), attrs)
                // A symlink or anything else on the way: not followed, not changed.
            }
        }

        fun contents(dir: SecureDirectoryStream<Path>, at: Path) {
            try {
                dir.forEach { entry ->
                    // The entry's *name*: the stream yields full paths, and a full path handed back
                    // to the stream would be resolved from the root again.
                    val name = entry.fileName
                    val child = at.resolve(name.toString())
                    val attrs = stat(dir, name, child) ?: return@forEach
                    when {
                        attrs.isDirectory -> open(dir, name, child)?.use {
                            set(child, it.getFileAttributeView(PosixFileAttributeView::class.java), null)
                            contents(it, child)
                        }
                        attrs.isRegularFile -> set(child, view(dir, name), attrs)
                    }
                }
            } catch (e: DirectoryIteratorException) {
                failed += describe(at, e.cause ?: e)
            }
        }

        private fun view(dir: SecureDirectoryStream<Path>, name: Path): PosixFileAttributeView =
            dir.getFileAttributeView(name, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)

        private fun stat(dir: SecureDirectoryStream<Path>, name: Path, at: Path): PosixFileAttributes? =
            attempt(at) { view(dir, name).readAttributes() }

        private fun open(dir: SecureDirectoryStream<Path>, name: Path, at: Path) =
            attempt(at) { dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS) }

        /** [known] when the caller already stat'd the entry, else read through [view]. */
        fun set(at: Path, view: PosixFileAttributeView, known: PosixFileAttributes?) {
            attempt(at) {
                val mode = (known ?: view.readAttributes()).permissions()
                if (mode.any { it in OPEN }) {
                    beforeSet(at)
                    view.setPermissions(mode - OPEN)
                    tightened++
                }
            }
        }

        private fun <T> attempt(at: Path, block: () -> T): T? = try {
            block()
        } catch (_: NoSuchFileException) {
            null
        } catch (e: IOException) {
            failed += describe(at, e)
            null
        }
    }

    /**
     * The exception's type as well as its message: an `AccessDeniedException`'s message is the
     * path alone, which printed beside the path says nothing.
     */
    private fun describe(path: Path, e: Exception) =
        "$path (${e.javaClass.simpleName}${e.message?.takeIf { it != path.toString() }?.let { ": $it" }.orEmpty()})"
}
