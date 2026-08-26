// Throwaway consumer — NOT published (no `maven-publish`, no publishing block).
// Shape B (granular): depend on a SINGLE non-engine artifact, `:core:network`, ONLY.
// This directly validates Phase 2 Task 2.1: because `core:network` now declares
// `api(project(":core:common"))`, a consumer of network alone must resolve
// `FlashDevice` (a core:common type in FlashNetwork's public API) WITHOUT adding a
// manual `core:common` dependency. Pre-flip this failed: "unresolved reference: FlashDevice".
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.transfer.flash.sample.consumergranular"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // ONLY core:network. No manual core:common line — that is the whole point.
    implementation(project(":core:network"))
}
