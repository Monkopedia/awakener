// Explicit: inside a build script `java` names the Java plugin extension, not the package.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Only for the lifecycle tasks. The root project builds no artifact; what it needs is a
    // `check` to hang the repo-wide suite below off, so that the `build` task — the lifecycle
    // task CLAUDE.md's full autonomous check invokes — actually runs it.
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
    // New with #89: the suite now says how many of its declared mutants had to run for its green
    // to mean anything, and this switch moves that bar. It changes what a pass asserts, so it is a
    // task input — undeclared, the cache would replay a run that checked a subset into a build
    // whose point was the full roster. Unset, the suite requires every declared mutant.
    inputs.property("minMutants", providers.environmentVariable("AWAKENER_MATRIX_MIN_MUTANTS").orElse("all"))
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

// The `test-artifacts` job's body, and its suite.
//
// Wired to `check` for the same reason `summaryMatrixTest` is: the script is edited here, and a
// red that only arrives after a push arrives after the change has been reported as verified. It
// declares no tool fingerprint, and that is a decision rather than an omission — no row in it is
// gated on a tool being present, so there is no reduced-coverage run for the cache to replay. It
// dies outright if `awk` is missing (the splice needs one) or `grep`/`sed` (the mutant table is
// parsed with them), which is the loud failure the fingerprints elsewhere exist to convert a
// silent one into. That distinction is the test: a fingerprint earns its place when a *missing*
// tool would leave the suite running with less coverage and still green, and nothing here has
// that shape.
val uploadOutcomeMatrixTest by tasks.registering(Exec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs .github/scripts/upload-outcome.sh against its input and mutant matrix."
    val matrix = layout.projectDirectory.file(".github/scripts/upload-outcome-matrix.sh")
    val script = layout.projectDirectory.file(".github/scripts/upload-outcome.sh")
    val report = layout.buildDirectory.file("reports/upload-outcome-matrix.txt")
    inputs.file(matrix)
    inputs.file(script)
    // New with #133, matching the two sibling matrices: the suite now says how many of its
    // declared mutants had to run for its green to mean anything, and this switch moves that bar.
    // It changes what a pass asserts, so it is a task input — `org.gradle.caching=true` and Gradle
    // treats neither PATH nor the environment as an input, so undeclared, the cache would replay a
    // run that checked a subset into a build whose point was the full roster. Unset — the state
    // every build here is in — the suite requires every declared mutant.
    inputs.property("minMutants", providers.environmentVariable("AWAKENER_MATRIX_MIN_MUTANTS").orElse("all"))
    outputs.file(report)
    commandLine(
        "sh",
        matrix.asFile.absolutePath,
        script.asFile.absolutePath,
        layout.buildDirectory.dir("upload-outcome-matrix").get().asFile.absolutePath,
        report.get().asFile.absolutePath,
    )
}

tasks.named("check") { dependsOn(uploadOutcomeMatrixTest) }

// The staleness check's suite (#123).
//
// Wired to `check` for the third time and for the same reason: `check-staleness.sh` is the command
// CLAUDE.md tells an implementer and a reviewer to run before believing any count, it is edited
// here, and a red that only arrives after a push arrives after the change has been reported as
// verified.
//
// The tool fingerprint is `git`, and it is not decoration. Every row builds a real repository and
// every verdict is a git exit status, so which git answered is exactly what a green here is about
// — `merge-base --is-ancestor` and `rev-parse --verify` are the two calls the whole instrument
// rests on. Gradle treats neither PATH nor the environment as an input (#28, #29), so without this
// the build cache would replay a run recorded against one git into a run whose point was another,
// and an upgraded git would never re-run the suite that exists to characterise it.
val stalenessMatrixTest by tasks.registering(Exec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs .github/scripts/check-staleness.sh against its fixture and mutant matrix."
    val matrix = layout.projectDirectory.file(".github/scripts/staleness-matrix.sh")
    val script = layout.projectDirectory.file(".github/scripts/check-staleness.sh")
    val report = layout.buildDirectory.file("reports/staleness-matrix.txt")
    inputs.file(matrix)
    inputs.file(script)
    inputs.property("gitTooling", toolFingerprint("git"))
    // How much of the counterfactual phase has to have executed for the suite's green to mean
    // anything. Declared as an input because it changes what a pass asserts, and an undeclared
    // switch is how a cached run that checked a subset gets replayed into one that demanded all of
    // it. Unset — the state every build here is in — the suite requires every declared mutant.
    inputs.property("minMutants", providers.environmentVariable("AWAKENER_MATRIX_MIN_MUTANTS").orElse("all"))
    outputs.file(report)
    commandLine(
        "sh",
        matrix.asFile.absolutePath,
        script.asFile.absolutePath,
        layout.buildDirectory.dir("staleness-matrix").get().asFile.absolutePath,
        report.get().asFile.absolutePath,
    )
}

tasks.named("check") { dependsOn(stalenessMatrixTest) }

// The wrapper-pin check's suite (#141).
//
// Wired to `check` for the fourth time and for the same reason as the three above: the script is
// edited here, and a red that only arrives after a push arrives after the change has already been
// reported as verified. This one was the outlier — `.github/workflows/build.yml` was the only
// thing in the tree that named `check-wrapper-pin-matrix.sh`, so it *did* run on every CI build
// (that job is a required check) but `./gradlew build` on a developer machine did not touch it.
// Confirmed two ways before this task existed: an exhaustive `git grep` at `99877a6` returned the
// workflow line and nothing else, with `staleness-matrix.sh` as the control that turns up in this
// file; and `./gradlew check --dry-run` at the same commit listed the three sibling matrix tasks
// and no fourth.
//
// No tool fingerprint, and that is a decision rather than an omission — the same one
// `uploadOutcomeMatrixTest` records. No row in the suite is gated on a tool being present: it dies
// outright with `CANNOT VOUCH` if `grep` or `awk` is missing, so there is no reduced-coverage run
// for the cache to replay. A fingerprint earns its place when a *missing* tool would leave the
// suite running with less coverage and still green, and nothing here has that shape.
val wrapperPinMatrixTest by tasks.registering(Exec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs .github/scripts/check-wrapper-pin.sh against its case and mutant matrix."
    val matrix = layout.projectDirectory.file(".github/scripts/check-wrapper-pin-matrix.sh")
    val script = layout.projectDirectory.file(".github/scripts/check-wrapper-pin.sh")
    val report = layout.buildDirectory.file("reports/check-wrapper-pin-matrix.txt")
    inputs.file(matrix)
    inputs.file(script)
    // How much of the counterfactual phase has to have executed for the suite's green to mean
    // anything. Declared as an input because it changes what a pass asserts, and an undeclared
    // switch is how a cached run that checked a subset gets replayed into one that demanded all of
    // it. Unset — the state every build here is in — the suite requires every declared mutant.
    inputs.property("minMutants", providers.environmentVariable("AWAKENER_MATRIX_MIN_MUTANTS").orElse("all"))
    outputs.file(report)
    commandLine(
        "sh",
        matrix.asFile.absolutePath,
        "--script",
        script.asFile.absolutePath,
        "--work",
        layout.buildDirectory.dir("check-wrapper-pin-matrix").get().asFile.absolutePath,
        "--report",
        report.get().asFile.absolutePath,
    )
}

tasks.named("check") { dependsOn(wrapperPinMatrixTest) }

// What this build should have run, checked against what it did run.
//
// `.github/scripts/test-summary.sh` holds the comparison and CI runs it as a step after the
// build. That step is the backstop; this task is the one that matters day to day, because the
// command CLAUDE.md names as the full autonomous check is `./gradlew build`, and #97's whole
// point is that `BUILD SUCCESSFUL` was reachable by a run in which an entire module's suite did
// not execute. A guard that only reds after a push reds after the reasoning that produced the
// change has already been reported as verified.
//
// `dependsOn` every subproject's `check` rather than `mustRunAfter` anything: this has to run
// after every task that writes JUnit XML, and a `Test` task that is *disabled* still satisfies a
// dependency — which is precisely the case being caught. The build's own `subprojects` is handed
// over as the module roster, so the floor file is checked against what the build contains rather
// than against a list somebody keeps up to date by hand.
//
// One honest limit, and it is the reason the CI step is not redundant: this reads the XML that is
// on disk, so on a repeat build without `clean` a module whose tests stopped running keeps
// satisfying its floor from the previous run's results. `clean build` — the command the doctrine
// names — has no stale XML, and CI checks out fresh. Nothing here can tell a stale file from a
// current one, which is the same reason `git status` cannot tell a stale checkout from a current
// one, and it is worth stating rather than papering over: an mtime rule would red every legitimate
// up-to-date test task, trading a narrow blind spot for a broad false alarm.
val verifyTestFloors by tasks.registering(Exec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if a module ran fewer tests than .github/scripts/test-floors declares."
    dependsOn(subprojects.map { "${it.path}:check" })
    val script = layout.projectDirectory.file(".github/scripts/test-summary.sh")
    val floors = layout.projectDirectory.file(".github/scripts/test-floors")
    inputs.file(script)
    inputs.file(floors)
    // No outputs, and no up-to-date check to skip it with: the whole question is about the tree
    // as it stands after this build's test tasks, and a cached answer to that question is the
    // #28/#29 failure in the guard against #97.
    outputs.upToDateWhen { false }
    commandLine(
        "env",
        // Under `Build and test` on CI this variable is set, and the script appends its report
        // to whatever it names. Left alone, every build would write a second test-count table
        // into the run summary ahead of the real one — the leak #86's canary was written about,
        // arriving through a different caller.
        "-u", "GITHUB_STEP_SUMMARY",
        "AWAKENER_TEST_FLOORS=${floors.asFile.absolutePath}",
        "AWAKENER_TEST_MODULES=${subprojects.joinToString(",") { it.name }}",
        "bash",
        script.asFile.absolutePath,
        rootDir.absolutePath,
    )
}

tasks.named("check") { dependsOn(verifyTestFloors) }
