rootProject.name = "awakener"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":config")
include(":registry")
include(":wm")

// Connector definitions are data — a directory each, discovered at runtime — but *finding* and
// materialising one is code, and it belongs below `:cli` so that nothing about the concept is
// tangled up in argument parsing. It depends on `:config` alone; see connector/build.gradle.kts.
include(":connector")

// Owns the command-line entry points. It is the only module allowed to depend on every other
// one, which is what puts every flag-declaring class on one classpath for discovery to find.
include(":cli")
