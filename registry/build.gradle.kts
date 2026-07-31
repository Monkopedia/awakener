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

/** Whether every one of [tools] is an executable on this build's PATH. */
fun onPath(vararg tools: String): Boolean {
    val entries = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    return tools.all { tool -> entries.any { dir -> File(dir, tool).canExecute() } }
}

tasks.withType<Test>().configureEach {
    // One test drives the real `spanreed agent-id`, which is read-only. It skips when spanreed
    // is not installed; set AWAKENER_REQUIRE_SPANREED=1 to make absence a failure instead, the
    // same discipline :wm uses for sway.
    System.getenv("AWAKENER_REQUIRE_SPANREED")?.let { environment("AWAKENER_REQUIRE_SPANREED", it) }

    // Neither PATH nor the environment is a test input as far as Gradle is concerned, so
    // without these the build cache will serve a run that skipped the real-spanreed test to a
    // run that required it. See the same note in :wm.
    inputs.property("spanreedTooling", onPath("spanreed"))
    inputs.property("requireSpanreed", System.getenv("AWAKENER_REQUIRE_SPANREED").orEmpty())

    testLogging {
        events("passed", "skipped", "failed")
    }
}
