package com.monkopedia.awakener.config

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private object DurabilityKnobs {
    val value: Flag<Int> = Flags.int("durability.value", -1, "written by the durability suite")
}

/**
 * How a `set` reaches the disk (#192): fsync the staging file, rename it over the config, fsync
 * the directory.
 *
 * **What this can and cannot prove.** A power loss is not something a test can arrange, so
 * nothing here shows the file survives one. What it does show is the order of the three calls
 * and that the fsync covers the whole of the new contents — which is the property the crash
 * safety rests on, and the one #192 found missing — plus that a reader racing the writer never
 * observes a partial file. That second test passes without the fsync too: the rename alone is
 * what a live reader depends on, and it is here as a guard on that, not as evidence for #192.
 */
class FileConfigStoreDurabilityTest {
    private val dir = createTempDirectory("awakener-config-durability")

    @BeforeTest
    fun loadFlags() {
        Flags.requireLoaded(DurabilityKnobs, ConfigFlags)
    }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a set fsyncs the staging file before the rename and the directory after it`() = runBlocking {
        val path = dir.resolve("config.json")
        path.writeText("{}")
        val steps = RecordingSteps()

        FileConfigStore(path, environment = emptyMap(), replaceSteps = steps)
            .set(DurabilityKnobs.value.key, "7")

        val staging = path.resolveSibling("config.json.${ProcessHandle.current().pid()}.tmp")
        assertEquals(
            listOf(
                "syncFile $staging",
                "move $staging -> $path",
                "syncDirectory ${dir.toAbsolutePath()}",
            ),
            steps.calls,
        )
        assertEquals(
            path.readText(),
            steps.syncedText,
            "the fsync ran before the staging file held the contents the rename then published",
        )
        assertTrue(""""durability.value": 7""" in path.readText(), "nothing reached the file")
    }

    @Test
    fun `a reloader racing the writer never reads a partial file`() {
        val path = dir.resolve("raced.json")
        path.writeText("{}")
        val writer = FileConfigStore(path, environment = emptyMap())
        val reader = FileConfigStore(path, environment = emptyMap())
        val done = AtomicBoolean(false)
        val failures = mutableListOf<String>()
        var reads = 0

        val readerThread = thread {
            while (!done.get()) {
                reader.reload()
                reads++
                reader.loadError.value?.let { failures += it }
            }
        }
        runBlocking {
            repeat(WRITES) { writer.set(DurabilityKnobs.value.key, it.toString()) }
        }
        done.set(true)
        readerThread.join()

        assertTrue(failures.isEmpty(), "a reload saw a partial file: ${failures.firstOrNull()}")
        assertTrue(reads > 0, "the reader never ran, so this proved nothing")
        assertEquals(WRITES - 1, reader.also { it.reload() }.config.value[DurabilityKnobs.value])
    }

    /** Records each step and delegates to the real one, so the write still happens. */
    private class RecordingSteps : PrivateFiles.ReplaceSteps {
        val calls = mutableListOf<String>()
        var syncedText: String? = null

        override fun syncFile(file: Path, channel: FileChannel) {
            calls += "syncFile $file"
            syncedText = file.readText()
            super.syncFile(file, channel)
        }

        override fun move(from: Path, to: Path) {
            calls += "move $from -> $to"
            super.move(from, to)
        }

        override fun syncDirectory(dir: Path) {
            calls += "syncDirectory $dir"
            super.syncDirectory(dir)
        }
    }

    private companion object {
        const val WRITES = 300
    }
}
