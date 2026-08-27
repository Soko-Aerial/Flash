// Throwaway consumer — NOT published (no `maven-publish`, no publishing block).
// Purpose: reproduce a downstream consumer's compile classpath so dependency-scope
// leaks (implementation-vs-api) surface here even though the library's own build
// hides them. Shape A: depend on the umbrella `:core:engine` artifact ONLY.
// Phase 2 Task 2.3 acceptance harness; reused by Phase 5's quick-start.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.transfer.flash.sample.consumer"
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
    // ONLY the engine. Its api(...) exposure must make the whole public vocabulary
    // (FlashDeviceId, FlashResult, FlashEngine, ...) compilable with no other core:* line.
    implementation(project(":core:engine"))
}
