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

// Owns the command-line entry points. It is the only module allowed to depend on every other
// one, which is what puts every flag-declaring class on one classpath for discovery to find.
include(":cli")
