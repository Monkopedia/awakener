plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        commonMain.dependencies {
            // `:config` and nothing else, and the "nothing else" is the point. A connector is
            // told the facts about one window as plain strings; it must never be able to reach a
            // compositor type, because that is how "nothing above :wm learns which compositor it
            // is talking to" stops being true one import at a time.
            api(project(":config"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// No tool fingerprint and no REQUIRE forwarding, and that is a claim rather than an omission:
// nothing in this module's suite shells out or gates on a tool being installed. Every test here
// runs against a temporary directory it creates itself. The moment a test here becomes
// tool-gated, this file owes the same `toolFingerprint`/`requireX` inputs `:wm`, `:registry` and
// `:cli` carry — see CLAUDE.md, which explains why the build cache makes that mandatory rather
// than tidy.
tasks.withType<Test>().configureEach {
    // The definitions this build ships, so the suite can load the real ones rather than a copy
    // of them. `ShippedConnectorsTest` is what says `connectors/chromium` actually parses,
    // requires facts that exist, and — for `computer-use-linux` — still declares no MCP server.
    val shipped = rootProject.layout.projectDirectory.dir("connectors")
    systemProperty("awakener.connectors", shipped.asFile.absolutePath)
    // The *contents*, as a separate declaration. The path string does not change when a
    // definition is edited, so without this Gradle would see identical inputs and replay the
    // previous green over the one change the suite exists to catch — the #29 shape with a
    // directory of data in the middle instead of a PATH.
    inputs.dir(shipped)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("shippedConnectors")

    testLogging {
        events("passed", "skipped", "failed")
    }
}
