rootProject.name = "rx"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":rx-protocol", ":rx-defs", ":rx-session", ":rx-app")
if (!providers.gradleProperty("rx.iosOnly").isPresent) include(":app")
