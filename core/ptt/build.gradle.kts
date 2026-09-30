import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under
    // AGP 9+. A converted module swaps it for these two rather than adding to it.
    // Conversion: ADR-058 (2026-09-30) — this was the last plain AGP core module, kept that
    // way by ERROR-049 (no JVM variant) until desktop needed to take part in PTT.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // Strict explicit-API mode, matching the other core modules (ADR-023).
    explicitApi()

    // The platform seams (`pttElapsedRealtimeMs`, `PttLock`, `platformPttAudio`) are `expect`
    // declarations, and `PttLock` is an expect CLASS, which Kotlin 2.2 still flags as Beta.
    // Same flag as the other modules that declare expect classes.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.transfer.flash.core.ptt"
        compileSdk = 35
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. The file is
        // comment-only (no reflection, no native lookup; AudioRecord/AudioTrack are referenced
        // directly), but the block keeps the pre-KMP publishing behaviour exactly.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `compileOptions { source/targetCompatibility = 11 }`.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + `testAndroidHostTest`. The old module's
        // `unitTests.isReturnDefaultValues = true` is deliberately NOT carried over: nothing in the
        // common suite touches the Android framework (logging goes through `FlashLog`, the tests inject
        // the clock and the audio devices), and a stub that silently returned 0 would hide a test that
        // forgot to. The real `SystemClock` / `AudioRecord` paths are device-tested (ADR-032).
        withHostTest { }

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // `compileKotlinJvm` cannot see android.jar, which is what proves the engine in commonMain
    // is genuinely Android-free. Holds the javax.sound capture/playout the desktop host uses.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The wire frames, the floor state machine and the jitter buffer live in
            // :core:messaging's commonMain; this module is the driver that executes them.
            api(project(":core:messaging"))
            // Public API exposes Flow/StateFlow.
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // TODO(cleanup): dead — grep finds no `androidx.core` reference in this module.
            // Parked on androidMain rather than deleted so the published `core-ptt-android`
            // POM keeps the runtime-scope entry consumers resolve today (the precedent
            // `:core:calling` and `:core:messaging` set).
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications {
        // KMP generates the publications itself (root `kotlinMultiplatform`, plus one per
        // target); a module must NOT `register<MavenPublication>("release")` any more.
        // Default artifactIds derive from the project name (`ptt`, `ptt-android`, `ptt-jvm`);
        // rename in place to keep the published `core-ptt` coordinate. Version and group still
        // come from the root build file — never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("ptt", "core-ptt")
        }
    }
}
