plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // Phase 3 Task 3.2: strict explicit-API mode. See docs/publishing/PHASE-03-api-surface.md.
    explicitApi()

    // PlatformEcPrivateKey is an `expect interface` (opaque private-key handle, actualized by a
    // typealias to java.security.PrivateKey). expect/actual classifiers are still Beta (KT-61573)
    // and warn per declaration; CONVENTIONS.md R2 sanctions the classifier form when the seam must
    // carry per-platform state, as this one does.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.transfer.flash.core.security"
        compileSdk = 35
        minSdk = 24

        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        withHostTest { }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // Public API returns kotlinx.coroutines Flow/StateFlow (FlashPairingProtocol/trust), so
            // coroutines must be `api` — an `implementation` scope keeps those return types off a
            // downstream consumer's classpath.
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // Phase 4 (ADR-024): security no longer depends on core:persistence / Room. The only
            // coupling was the unused RoomTrustedStore adapter (deleted); the live trust store is
            // the SharedPreferences-backed AndroidPreferencesTrustStore. This keeps
            // Room/SQLCipher off the classpath of security and of everything downstream of it
            // (notably core:transfer).
            //
            // Phase 07: both of these are Android-only and stay Android-only. Neither has a source
            // reference in the module (verified by grep), so they are declared here rather than
            // deleted only to preserve the pre-KMP runtime classpath of the Android artifact
            // exactly — dependency pruning is not this phase's business (CONVENTIONS.md R1).
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        jvmMain.dependencies {
            // Phase 26 (ADR-035): JNA carries the Windows DPAPI calls behind
            // IdentityKeyVault.Dpapi — the desktop identity key's at-rest protection. JVM
            // target only, never Android (the Android identity key is Keystore-backed and
            // never leaves the TEE). `implementation`, not `api`: DPAPI is an implementation
            // detail of the vault; no consumer type mentions it.
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
        // Runs on BOTH the Android host-test JVM and the desktop jvm() target, so the two
        // PlatformCrypto actual sets are executed, not merely compiled. See
        // crypto/PlatformCryptoParityTest.kt.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
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
        // KMP generates one publication per target plus the root metadata publication; renaming
        // them here keeps the published coordinates at `core-security*` (Phase 06 pattern).
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("security", "core-security")
        }
    }
}
