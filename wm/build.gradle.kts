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

tasks.withType<Test>().configureEach {
    // Window-management behaviour is only meaningful against a live compositor, so the suite
    // needs the tools on PATH. CI sets AWAKENER_REQUIRE_SWAY=1 to turn "not installed" from a
    // skip into a failure — a green run that quietly skipped every test is worse than red.
    System.getenv("AWAKENER_REQUIRE_SWAY")?.let { environment("AWAKENER_REQUIRE_SWAY", it) }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
