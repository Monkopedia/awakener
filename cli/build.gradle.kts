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
            siblingModules.forEach { api(project(it)) }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    // So the suite can assert the wiring above actually happened, for whatever the current set
    // of modules is rather than for a set frozen into a test.
    systemProperty("awakener.modules", siblingModules.joinToString(",") { it.removePrefix(":") })
    testLogging {
        events("passed", "skipped", "failed")
    }
}
