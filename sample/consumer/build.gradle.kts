// Throwaway consumer — NOT published (no `maven-publish`, no publishing block).
// Purpose: reproduce a downstream consumer's compile classpath so dependency-scope
// leaks (implementation-vs-api) surface here even though the library's own build
// hides them. Shape A: depend on the umbrella `:core:engine` artifact ONLY.
// Phase 2 Task 2.3 acceptance harness; reused by Phase 5's quick-start.
//
// It is ALSO the contract test for the facade's calling seam (UmbrellaFacadeContractTest,
// ADR-033 / logs/errors.md HAZARD-002): `:core:engine` declares :core:calling `compileOnly`, so
// this module's runtime classpath is the only place in the repo where `FlashCalling` is genuinely
// absent. Adding `core-calling` (or any other sibling) here would delete that property.
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

    // The seam test is a host-JVM unit test: it constructs a real DefaultFlashEngine and reads
    // class bytes off the classpath. Returning default values for un-mocked platform calls keeps
    // any `android.*` type in a resolved signature (e.g. android.content.Context in Wiring)
    // class-loadable without Robolectric — the repo deliberately has none.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // ONLY the engine. Its api(...) exposure must make the whole public vocabulary
    // (FlashDeviceId, FlashResult, FlashEngine, ...) compilable with no other core:* line.
    implementation(project(":core:engine"))

    // JUnit 4 only. No mocking framework and no Robolectric on purpose: the test's strength is that
    // it runs against the real engine classes and the real (calling-free) runtime classpath.
    testImplementation(libs.junit)
}
