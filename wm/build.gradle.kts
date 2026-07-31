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
            // The durable binding lives below the compositor layer, not inside it: `resolve`
            // has to answer the same way after a reboot, and a con_id does not survive one.
            api(project(":registry"))
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
    // Window-management behaviour is only meaningful against a live compositor, so the suite
    // needs the tools on PATH. CI sets AWAKENER_REQUIRE_SWAY=1 to turn "not installed" from a
    // skip into a failure — a green run that quietly skipped every test is worse than red.
    System.getenv("AWAKENER_REQUIRE_SWAY")?.let { environment("AWAKENER_REQUIRE_SWAY", it) }

    // Both of these decide what a run of this task *means*, and Gradle tracks neither: PATH is
    // not a test input and nor is the environment. Undeclared, `clean build` will restore the
    // outputs of a cached run whose tools were absent and report them as this run's — the
    // build cache handing a skipped suite to the very run that set AWAKENER_REQUIRE_SWAY=1 to
    // forbid one. Observed, not theorised: a REQUIRE=1 clean build on a host with sway
    // installed came back in 904ms with skipped=32.
    inputs.property("swayTooling", onPath("sway", "foot"))
    inputs.property("requireSway", System.getenv("AWAKENER_REQUIRE_SWAY").orEmpty())

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
