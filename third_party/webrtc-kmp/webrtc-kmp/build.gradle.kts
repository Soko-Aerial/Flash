import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    `maven-publish`
}

// Vendored into the Flash repository per ADR-034 (D12). Provenance: aschulz90/webrtc-kmp
// (a fork of shepeliev/webrtc-kmp), copied 2026-09-13, then trimmed and adapted:
//  - iOS targets + cocoapods + js/wasmJs targets REMOVED — Flash needs Android + JVM only,
//    and every target removed is one that is never built or fixed (Phase 25 S2b).
//  - The old `com.android.library` + KMP pattern was ILLEGAL here: this build compiles under
//    Gradle 9.5 / AGP 9.3.1 / Kotlin 2.2.10 (the consuming build's toolchain), where a KMP
//    module must use `com.android.kotlin.multiplatform.library` — the same shape as every
//    converted `:core:*` module (see core/engine/build.gradle.kts). Source sets renamed to
//    match: androidUnitTest -> androidHostTest, androidInstrumentedTest -> androidDeviceTest.
//  - `signing`/nexus publish removed: nothing here is published; the composite build
//    substitutes the artifacts directly into the consuming build (same
//    `com.shepeliev:webrtc-kmp` coordinates as the Maven original, which is what makes
//    substitution automatic).
//  - webrtc-java bumped 0.8.0 -> 0.17.0 (gradle/libs.versions.toml) — the ONE version
//    movement D12 authorizes (R10 exception, ADR-034).

group = "com.shepeliev"

version = "0.125.11-flash-1"

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.shepeliev.webrtckmp"
        compileSdk = 35
        minSdk = 21

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest { }

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.coroutines)
        }

        androidMain.dependencies {
            api(libs.webrtc.android)
            implementation(libs.kotlin.coroutines.android)
            implementation(libs.androidx.coreKtx)
            implementation(libs.androidx.startup)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlin.coroutines.test)
        }

        jvmMain.dependencies {
            api(libs.webrtc.java)
            implementation(libs.java.bouncycastle)
        }
        jvmTest.dependencies {
            // Native libwebrtc binaries for the HOST, pulled per-OS at test time (the fork's
            // original mechanism, kept). Main code only needs the API jar.
            val osName = System.getProperty("os.name")
            val hostOS = when {
                osName == "Mac OS X" -> "macos"
                osName.startsWith("Win") -> "windows"
                osName.startsWith("Linux") -> "linux"
                else -> error("Unsupported OS: $osName")
            }
            val hostArch = when (val arch = System.getProperty("os.arch").lowercase()) {
                "amd64" -> "x86_64"
                else -> arch
            }
            implementation("${libs.webrtc.java.get()}:$hostOS-$hostArch")
        }
    }
}
