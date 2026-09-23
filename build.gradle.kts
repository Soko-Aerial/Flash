// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Single source of truth for the published library version.
// A release = bump this, commit, then `git tag vX.Y.Z` (tag must match).
// A module's own `publishing { }` block must NOT set `version` or `groupId`: the three
// ui/* modules did, so they silently published 1.0.0 for the whole 1.1.0 cycle while
// every core module tracked this constant. Set artifactId there and nothing else.
val flashLibraryVersion = "2.0.0-beta"

allprojects {
    version = flashLibraryVersion
    group = "com.transfer.flash"
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Phase 06 (KMP pilot). `apply false` registers the plugins on the subproject
    // classpath without applying them at the root; omitting it causes "plugin already
    // on the classpath" conflicts in the module that does apply them.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
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