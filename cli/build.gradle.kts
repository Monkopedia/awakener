// Explicit: inside a build script `java` names the Java plugin extension, not the package.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Every other module, taken from the build rather than listed by hand. A module added later
// has to land on this classpath without its author knowing this file exists — otherwise flag
// discovery quietly goes back to reporting a subset, which is the bug this module fixes.
val siblingModules = rootProject.subprojects.map { it.path } - project.path

kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        commonMain.dependencies {
            siblingModules.forEach { implementation(project(it)) }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // SwayHarness, from `:wm`'s test classes. The invoke path's integration suite drives
            // a real headless sway, and reproducing "start one, and take down everything it
            // spawned" here would be a second copy of the hardest code in this repo's tests —
            // one whose drift shows up as leaked compositors on a shared machine rather than as
            // a failing test.
            implementation(project(path = ":wm", configuration = "jvmTestClasses"))
        }
    }
}

/** Whether every one of [tools] is an executable on this build's PATH. */
fun onPath(vararg tools: String): Boolean {
    val entries = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    return tools.all { tool -> entries.any { dir -> File(dir, tool).canExecute() } }
}

tasks.withType<Test>().configureEach {
    // So the suite can assert the wiring above actually happened, for whatever the current set
    // of modules is rather than for a set frozen into a test.
    systemProperty("awakener.modules", siblingModules.joinToString(",") { it.removePrefix(":") })

    // This module now has an integration suite of its own, so it inherits both tool gates whole.
    // The invoke path needs sway and foot to stand a dock up, and spanreed to answer whether a
    // Lifeless is animated; `AWAKENER_REQUIRE_*=1` turns a missing tool from a skip into a
    // failure, which is what stops a green run that verified nothing.
    System.getenv("AWAKENER_REQUIRE_SWAY")?.let { environment("AWAKENER_REQUIRE_SWAY", it) }
    System.getenv("AWAKENER_REQUIRE_SPANREED")?.let {
        environment("AWAKENER_REQUIRE_SPANREED", it)
    }

    // Gradle treats neither PATH nor the environment as a test input, so without these the build
    // cache will replay a run that skipped every tool-gated test into a `clean build` that
    // demanded the tools. Same declaration as `:wm` and `:registry`, and for the same measured
    // reason — a REQUIRE=1 clean build coming back in under a second with everything skipped.
    inputs.property("swayTooling", onPath("sway", "foot"))
    inputs.property("requireSway", System.getenv("AWAKENER_REQUIRE_SWAY").orEmpty())
    inputs.property("spanreedTooling", onPath("spanreed"))
    inputs.property("requireSpanreed", System.getenv("AWAKENER_REQUIRE_SPANREED").orEmpty())

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
