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

// `:cli`'s invoke suite mints through the real spanreed, so it needs the same gate this module's
// tests go through — `SpanreedHarness`, which consults AWAKENER_REQUIRE_SPANREED before it skips.
// Published rather than copied for the reason `:wm` publishes `SwayHarness`: a second copy is a
// second place for that ordering to drift, and a drifted gate is invisible until the day the tool
// is missing. `jvmMain` is the wrong home for it — `org.junit.Assume` has no business in the
// production artifact.
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
    // Two tests drive the real spanreed, and both are read-only: `the real spanreed derives
    // agent-name from SPANREED_AGENT_NAME` runs `agent-id`, which only prints a derivation, and
    // `the real spanreed list decodes into live agents` runs `list`, which reads the registry
    // under spanreed's own lock and writes nothing. The second is worth naming for a second
    // reason: `list` reads **the developer's live bus**, so on kaladin this build opens
    // ~/.claude/spanreed/registry.json on every run. It asserts on the shape of what it finds
    // and never on a count, so Jason's bus being busy or empty cannot fail it — but a build that
    // reads live state should say so rather than leave an auditor to find it (#109). Both skip
    // when spanreed is not installed; set AWAKENER_REQUIRE_SPANREED=1 to make absence a failure
    // instead, the same discipline :wm uses for sway.
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
