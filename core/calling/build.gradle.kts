import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under
    // AGP 9+. A converted module swaps it for these two rather than adding to it.
    // Conversion: Phase 25 A1 (2026-09-13).
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // Strict explicit-API mode, matching the other core modules (ADR-023).
    explicitApi()

    android {
        namespace = "com.transfer.flash.core.calling"
        compileSdk = 35
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles(...) }`. Here the rules are NOT
        // comment-only (unlike core/engine's): they carry the BLUNT org.webrtc.** keep
        // without which a minifying consumer crashes inside PeerConnectionFactory init —
        // libwebrtc's JNI resolves Java members by name, invisible to R8. See the file.
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

        // Creates `androidHostTest` + `testAndroidHostTest` (the KMP replacement for
        // `testDebugUnitTest`).
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // Empty until A2 lands the pure files in commonMain; the MEDIA half stays androidMain
    // until the vendored-fork substitution (D12/ADR-034) gives webrtc-kmp a JVM variant.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // Public API exposes Flow/StateFlow call-state streams.
            api(libs.kotlinx.coroutines.core)
            // LEGAL in commonMain since S2c: the vendored fork (ADR-034) substitutes the same
            // com.shepeliev:webrtc-kmp coordinates with a build that has BOTH android and jvm
            // variants, so the old F1/ERROR-049 wall no longer applies. Before the fork, this
            // edge HAD to stay androidMain (Maven webrtc-kmp 0.125.11 has no JVM target).
            api(libs.webrtc.kmp)
        }
        androidMain.dependencies {
            // TODO(cleanup): both androidx entries are dead — grep finds zero
            // `androidx.core` / `androidx.lifecycle` references in this module's main and
            // test sources. Parked on androidMain rather than deleted so the published
            // `core-calling-android` POM keeps the two implementation-scope entries 1.1.0
            // consumers resolve today (the same parked-dead-dep precedent as Phase 10/11
            // and `:core:engine`).
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The session test suites construct the androidMain session classes, so they stay
        // JUnit 4 on the Android host JVM (Phase 25 A4: only the platform-free suites move
        // to commonTest).
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
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
        // Default artifactIds derive from the project name (`calling`, `calling-android`,
        // `calling-jvm`); rename in place to keep the published `core-calling` coordinate
        // that 1.1.0 consumers already use — the root coordinate becomes the KMP umbrella
        // and `-android`/`-jvm` are the children (Phase 24's documented tree shape).
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("calling", "core-calling")
        }
    }
}
