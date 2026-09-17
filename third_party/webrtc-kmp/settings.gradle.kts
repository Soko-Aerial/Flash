@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        google()
    }
}

pluginManagement {
    repositories {
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        google()
        gradlePluginPortal()
    }
}

rootProject.name = "webrtc-kmp"

include(":webrtc-kmp")
