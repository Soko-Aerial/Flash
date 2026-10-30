// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Single source of truth for the published library version: `flashLibraryVersion` in the root
// gradle.properties (it moved there on 2026-10-09 so the vendored webrtc-kmp fork, a separate included
// build, can read the same value, ADR-103).
// A release = bump it there, commit, then `git tag vX.Y.Z` (tag must match).
// A module's own `publishing { }` block must NOT set `version` or `groupId`: the three
// ui/* modules did, so they silently published 1.0.0 for the whole 1.1.0 cycle while
// every core module tracked this constant. Set artifactId there and nothing else.
val flashLibraryVersion: String = providers.gradleProperty("flashLibraryVersion").get()

allprojects {
    version = flashLibraryVersion
    group = "com.transfer.flash"

    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle" && requested.name == "bcutil-jdk18on") {
                useVersion("1.81.1")
                because("Pin dynamic range [1.81, 1.82) to prevent daily configuration cache eviction (OPT-O21-07)")
            }
        }
    }

    // OPT-O27-01: configure test worker parallelization monorepo-wide
    // OPT-O27-05: full test failure reporting infrastructure to capture flakes with stacktraces
    tasks.withType<Test>().configureEach {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1).coerceAtMost(4)
        forkEvery = 100
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
            showCauses = true
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    // Phase 06 (KMP pilot). `apply false` registers the plugins on the subproject
    // classpath without applying them at the root; omitting it causes "plugin already
    // on the classpath" conflicts in the module that does apply them.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.aboutlibraries) apply false
    // NOTE (Phase 3 Task 3.1, ADR-023): the kotlinx binary-compatibility-validator
    // was evaluated for tracking the published ABI (apiDump/apiCheck). Under this
    // project's AGP 9.3.1 built-in Kotlin (no classic `kotlin.android`/JVM/MPP
    // plugin), BCV registers no tasks for Android library variants, so it is inert
    // here. Removed. The published ABI is instead enforced at the compiler by
    // `explicitApi()` (strict) in every `core/*` module — no symbol reaches the ABI
    // without a deliberate public/internal/@FlashInternalApi decision.
}