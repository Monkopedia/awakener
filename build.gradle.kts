// Explicit: inside a build script `java` names the Java plugin extension, not the package.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Only for the lifecycle tasks. The root project builds no artifact; what it needs is a
    // `check` to hang the repo-wide suite below off, so that `./gradlew build` — the command
    // CLAUDE.md names as the full autonomous check — actually runs it.
    base
}

/**
 * A cache key naming *which* of [tools] this build found on PATH, not merely that it found any.
 *
 * Same helper and same reason as `:wm`, `:registry` and `:cli`, where the full note lives (#28,
 * #29): Gradle treats neither PATH nor the environment as an input, so without this the build
 * cache replays a recorded run into one whose tooling has changed underneath it. Resolved path,
 * size and mtime rather than a boolean, so *upgrading* an awk re-runs the suite instead of
 * replaying a green recorded against the previous one.
 */
fun toolFingerprint(vararg tools: String): String {
    val entries = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    return tools.joinToString(" ") { tool ->
        val found = entries.map { File(it, tool) }.firstOrNull { it.canExecute() }
        val id = found?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        "$tool=${id ?: "absent"}"
    }
}

// The test-summary script's own suite.
//
// It is wired to `check` rather than run as a workflow step, and that is a decision rather than
// a default. `.github/scripts/test-summary.sh` is CI-support code, so a workflow step is the
// obvious home for its tests — but a workflow step only ever runs on GitHub, and the script is
// edited here, by agents whose whole verification routine is `./gradlew clean build`. A red that
// arrives only after a push is a red that arrives after the reasoning that produced the change
// has already been reported as verified. `cli:launcherTest` is the precedent for exactly this
// shape, for exactly this reason, and it is a shell suite too.
//
// What the workflow-step option would have bought is a smaller `check` and a `.github/`
// directory that is self-contained; what it would have cost is (a) the local red, and (b) most
// of the awk coverage, since a workflow step runs under the runner's awk alone while a `check`
// task runs under whatever the developer host has. Both hosts are needed to cover mawk *and*
// gawk *and* busybox awk between them.
val summaryMatrixTest by tasks.registering(Exec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs .github/scripts/test-summary.sh against its fixture and mutant matrix."
    val matrix = layout.projectDirectory.file(".github/scripts/test-summary-matrix.sh")
    val script = layout.projectDirectory.file(".github/scripts/test-summary.sh")
    val report = layout.buildDirectory.file("reports/test-summary-matrix.txt")
    inputs.file(matrix)
    inputs.file(script)
    // The suite's verdict depends on which awks answered — it fails outright below two distinct
    // ones — and installing or upgrading one changes what a green here means. Undeclared, the
    // cache would hand a run that covered gawk and busybox to a later run whose point was mawk.
    inputs.property("awkTooling", toolFingerprint("awk", "gawk", "mawk", "original-awk", "busybox"))
    outputs.file(report)
    commandLine(
        "sh",
        matrix.asFile.absolutePath,
        script.asFile.absolutePath,
        layout.buildDirectory.dir("test-summary-matrix").get().asFile.absolutePath,
        report.get().asFile.absolutePath,
    )
}

tasks.named("check") { dependsOn(summaryMatrixTest) }
