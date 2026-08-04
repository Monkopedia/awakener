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

// `:cli`'s integration suite drives the same headless sway this module's does, and SwayHarness is
// the thing that knows how to start one and — harder — how to take every process it spawned back
// down. Publishing the test classes is deliberate over the two alternatives: a second copy in
// `:cli` would drift from this one, and the drift would show up as leaked compositors on a shared
// machine rather than as a failing test; and moving the harness into `jvmMain` would ship test
// scaffolding in the production artifact, where `org.junit.Assume` has no business being.
val jvmTestClasses: Configuration by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}

val jvmTestJar by tasks.registering(Jar::class) {
    archiveClassifier.set("jvm-test")
    from(kotlin.jvm().compilations.getByName("test").output.allOutputs)
}

artifacts.add(jvmTestClasses.name, jvmTestJar)

/**
 * A cache key naming *which* of [tools] this build found on PATH, not merely that it found any.
 *
 * A boolean was enough to stop the build cache replaying a run that skipped everything into one
 * that demanded the tools (#28), but not enough to stop it replaying a run against sway 1.12
 * into a run against 1.13 (#29): the key does not move on an upgrade, so the suite never
 * re-runs, and `CLAUDE.md`'s standing requirement — say what you exercised and against which
 * sway version — becomes unverifiable. Resolved path, size and mtime move on any upgrade and
 * cost no subprocess, which matters because this runs at configuration time on every build.
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
    inputs.property("swayTooling", toolFingerprint("sway", "foot"))
    inputs.property("requireSway", System.getenv("AWAKENER_REQUIRE_SWAY").orEmpty())

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
