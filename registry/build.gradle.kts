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

tasks.withType<Test>().configureEach {
    // One test drives the real `spanreed agent-id`, which is read-only. It skips when spanreed
    // is not installed; set AWAKENER_REQUIRE_SPANREED=1 to make absence a failure instead, the
    // same discipline :wm uses for sway.
    System.getenv("AWAKENER_REQUIRE_SPANREED")?.let { environment("AWAKENER_REQUIRE_SPANREED", it) }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
