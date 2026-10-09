pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Phase 24 Step 2: mavenLocal() is FIRST so `:sample:consumer-desktop` (the only module
        // that consumes published coordinates rather than project() edges) resolves the freshly
        // published `com.transfer.flash:*` tree. Harmless to every other module: local
        // publications only shadow a dependency if a version collides AND the cache prefers the
        // local one, and Gradle checks mavenLocal first only for coordinates that exist there.
        mavenLocal()
        google()
        mavenCentral()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
    }
    versionCatalogs {
        // ADR-103: the vendored webrtc-kmp fork is versioned with the library (flashLibraryVersion, gradle.properties).
        // The catalog entry `webrtc-kmp` cannot carry that version in the toml (a toml cannot read a property), and a
        // version-less entry makes :core:calling / :ui:callui publish a POM dependency without a version. So the version
        // is added here, to the default `libs` catalog (already imported from gradle/libs.versions.toml, which therefore has no
        // `webrtc-kmp` line), from the same property the root build and the fork read.
        create("libs") {
            val flashLibraryVersion = providers.gradleProperty("flashLibraryVersion").get()
            library("webrtc-kmp", "com.transfer.flash", "webrtc-kmp").version(flashLibraryVersion)
        }
    }
}

rootProject.name = "Flash"

// ADR-034 (D12) + ADR-103: the vendored webrtc-kmp fork (upstream com.shepeliev:webrtc-kmp, plus a jvm() target).
// Since ADR-103 the fork carries Flash's own group (com.transfer.flash) and the library version, so the same build
// publishes it next to the other modules (jitpack.yml). Gradle's composite-build dependency substitution still redirects
// every `libs.webrtc.kmp` edge (declared above, with the version) to this included build, so the consuming modules'
// dependency lines do not change. See third_party/webrtc-kmp/MODIFICATIONS.md.
includeBuild("third_party/webrtc-kmp")

include(":app")
include(":core:common")
include(":core:persistence")
include(":core:security")
include(":core:discovery")
include(":core:network")
include(":core:transfer")
include(":core:messaging")
include(":core:calling")
include(":core:ptt")
include(":core:swarm")
include(":core:engine")
include(":ui:theme")
// Phase 19. Sits between `:ui:theme` and `:ui:chat` in the build graph: it owns the seven
// platform seams (back handling, clipboard, file picking, permissions, image decode, audio
// playback, voice capture) that `:ui:chat` used to reach through `android.*` imports.
include(":ui:platform-shims")
include(":ui:chat")
include(":ui:callui")
// Phase 21: the Compose Desktop application shell. A pure-JVM consumer of the KMP library
// modules — never a dependency of anything. `:app` stays the Android application.
include(":desktop")

// Test-harness consumers that reproduce a downstream compile classpath (Phase 2 Task 2.3).
// NOT published — they have no maven-publish plugin. See docs/publishing/PHASE-02-dependency-scope.md.
include(":sample:consumer")
include(":sample:consumer-granular")
// Phase 24 Step 2 / D9 = Option A: the desktop tier of the same contract — a plain JVM module
// resolving the ROOT published coordinates from mavenLocal() so variant-aware selection of the
// `-jvm` artifact is proven by compilation. Kept alongside the Android samples, never published.
include(":sample:consumer-desktop")
