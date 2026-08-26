// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Single source of truth for the published library version.
// A release = bump this, commit, then `git tag vX.Y.Z` (tag must match).
val flashLibraryVersion = "1.0.0"

allprojects {
    version = flashLibraryVersion
    group = "com.transfer.flash"
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}