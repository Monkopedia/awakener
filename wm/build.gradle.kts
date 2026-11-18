// Explicit: inside a build script `java` names the Java plugin extension, not the package.
import java.io.File
import java.nio.file.Files

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
val jvmTestClasses = configurations.create("jvmTestClasses") {
    isCanBeResolved = false
    isCanBeConsumed = true
}

val jvmTestJar = tasks.register<Jar>("jvmTestJar") {
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
    // A runtime directory of the suite's own (#199). The attach lock lives under
    // $XDG_RUNTIME_DIR/awakener, one file per compositor socket, and every harness sway has a
    // fresh socket — so a suite run against the developer's real runtime directory left hundreds
    // of lock files there (335 after one review). The harness hands this same directory to the
    // sways it starts, so their Wayland sockets land here too and the grim tests still find them.
    //
    // Under the system temp directory rather than build/ (#240): a Wayland socket path has to fit
    // in sun_path's 108 bytes, and build/ is as long as wherever the checkout happens to be — from
    // a worktree under the session scratchpad every sway test failed with "Unable to open wayland
    // socket". `createTempDirectory` makes it 0700, as the spec requires of a runtime directory.
    // Its name is random, which is fine only because it reaches the task through the environment,
    // which Gradle does not track: as a declared input it would give every run a new cache key.
    //
    // SwayHarness makes each session's own directory under it too (#243). Its `close` removes that,
    // but a worker that is SIGKILLed or cancelled mid-test never gets there, and before this every
    // such run left an `awakener-sway*` in /tmp — RAM on kaladin. The sweep below runs here, in the
    // daemon, so it outlives the worker. A daemon that is itself SIGKILLed still leaks the lot.
    var testRuntime: File? = null
    doFirst {
        val dir = Files.createTempDirectory("awakener-xdg").toFile()
        testRuntime = dir
        environment("XDG_RUNTIME_DIR", dir.absolutePath)
        environment("AWAKENER_SWAY_TMPDIR", dir.absolutePath)
    }
    // On the root suite finishing rather than in doLast, which a failing test run never reaches.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent != null) return
            // A suite that passed closed every session it started, so anything left is a test
            // that skipped `close` — swept all the same, but reported, since on a run outside
            // Gradle nothing would have swept it.
            val stranded = testRuntime?.listFiles { f -> f.name.startsWith("awakener-sway") }.orEmpty()
            testRuntime?.deleteRecursively()
            if (result.resultType == TestResult.ResultType.SUCCESS && stranded.isNotEmpty()) {
                throw GradleException(
                    "sessions outlived a passing suite: ${stranded.map { it.name }.sorted()}",
                )
            }
        }
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
    })
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
    // `grim` is here for the same reason `sway` and `foot` are, and it is not optional to declare
    // just because one suite uses it: `SwayTabTitleTest` reads a rendered tab back off the
    // compositor's output, so a cached run from a host without a screenshotter would replay as
    // this run's result with the only visual assertions in the build silently skipped.
    inputs.property("swayTooling", toolFingerprint("sway", "foot", "grim"))
    inputs.property("requireSway", System.getenv("AWAKENER_REQUIRE_SWAY").orEmpty())

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
