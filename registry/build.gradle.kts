// Explicit: inside a build script `java` names the Java plugin extension, not the package.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":config"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

/**
 * A cache key naming *which* of [tools] this build found on PATH, not merely that it found any.
 * Path, size and mtime rather than a boolean, so upgrading spanreed re-runs the suite instead of
 * replaying a green recorded against the previous one. Same helper and same reason as `:wm`,
 * where the full note lives (#29).
 */
fun toolFingerprint(vararg tools: String): String {
    val entries = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    return tools.joinToString(" ") { tool ->
        val found = entries.map { File(it, tool) }.firstOrNull { it.canExecute() }
        val id = found?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        "$tool=${id ?: "absent"}"
    }
}

tasks.withType<Test>().configureEach {
    // One test drives the real `spanreed agent-id`, which is read-only. It skips when spanreed
    // is not installed; set AWAKENER_REQUIRE_SPANREED=1 to make absence a failure instead, the
    // same discipline :wm uses for sway.
    System.getenv("AWAKENER_REQUIRE_SPANREED")?.let { environment("AWAKENER_REQUIRE_SPANREED", it) }

    // Neither PATH nor the environment is a test input as far as Gradle is concerned, so
    // without these the build cache will serve a run that skipped the real-spanreed test to a
    // run that required it. See the same note in :wm.
    inputs.property("spanreedTooling", toolFingerprint("spanreed"))
    inputs.property("requireSpanreed", System.getenv("AWAKENER_REQUIRE_SPANREED").orEmpty())

    testLogging {
        events("passed", "skipped", "failed")
    }
}
