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

    // OPT-O27-01: cross-project test parallelism (org.gradle.parallel) is what helps here, not
    // per-task forks. With parallel=true Gradle already runs up to max-workers test tasks at once,
    // so four forks each multiplied into ~32 JVMs on 8 CPUs and made the compile-heavy gate 1 70%
    // slower (467s vs 275s on the same 153 tasks, 2026-10-30, EXP-80).
    //
    // One fork everywhere, deliberately, even though two forks for the Robolectric host suites
    // measured faster (gate 2 535s -> 376s): with 13 test JVMs running, two tests in
    // RealFlashChatRepositoryTest that synchronise with `delay(20)`/`delay(100)` read an empty list
    // and fail (ERROR-873). A gate that goes red under load costs every stream more than the
    // minutes it saves. :server:test keeps its own four, with its own heap, in
    // server/build.gradle.kts: a parallel run there is the only test task in the build, and 661
    // tests passed twice at 4 forks on this machine.
    // OPT-O27-02: `kotlinx.coroutines.debug` off is a test-runner property only; it is not
    // Kotlin coroutine *virtual time* and does not change scheduling.
    // OPT-O27-05: full test failure reporting infrastructure to capture flakes with stacktraces
    tasks.withType<Test>().configureEach {
        maxParallelForks = 1
        systemProperty("kotlinx.coroutines.debug", "off")
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