plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under
    // AGP 9+. A converted module swaps it for these two rather than adding to it.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // Phase 3 Task 3.2, unchanged by the conversion: strict explicit-API mode.
    // See docs/publishing/PHASE-03-api-surface.md.
    explicitApi()

    // NOTE: deliberately NO `compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }`.
    // :core:common, :core:security and :core:discovery each need that flag because each declares
    // an `expect class`. This module declares no expect/actual at all — its 15 androidMain files
    // are CONVENTIONS.md R2 step 1 (leave it where it is), not step 3 (expect/actual). See
    // docs/migration/PHASE-11-repositories-kmp.md.

    android {
        namespace = "com.transfer.flash.core.transfer"
        compileSdk = 35

        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Rules are
        // dropped in SILENCE if this is omitted; core/transfer's file is comment-only today,
        // but the block preserves the pre-KMP publishing behaviour exactly.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`: the target publishes a single variant, so
        // instead of declaring build types it states which one local project deps resolve from.
        // Load-bearing for the `:core:network` edge below, which is still variant-ful.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task (the KMP replacement for
        // `testDebugUnitTest`). Omit it and all 13 migrated test files stop compiling AND
        // running while the build still reports SUCCESS.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // `compileKotlinJvm` cannot see android.jar, which is what proves the 5 commonMain files
    // (FlashTransferRepository, the FlashTransfer model, the ADR-024 TransferStore port,
    // StreamChannel, and the FLSH v2 control-frame format) are Android-free.
    // It carries NO desktop transfer implementation: the chunking, hashing, resume, multi-stream
    // and destination-policy machinery all stay in androidMain until Phase 15. jvmMain is empty.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // Public API returns kotlinx.coroutines Flow/StateFlow (e.g. activeTransfers), so
            // coroutines must be `api` (implementation would keep those return types off a
            // consumer's classpath).
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // Phase 02 (migration): `:core:security` and `:core:discovery` were removed here when
            // `wslegacy/` was deleted — WsPairingStore was the only consumer of FlashTrustStore
            // and WsDiscovery the only consumer of the discovery module. Recorded because a reader
            // will otherwise re-add them.
            //
            // `:core:network` is a LIVE edge, not a dead one: model/WsTransferModels.kt reads
            // WsTransferServer.PREFERRED_PORT, and WsTransferServer is androidMain in
            // :core:network as of Phase 10 — which is exactly why WsTransferModels.kt is
            // androidMain here.
            implementation(project(":core:network"))
            // Phase 4 (ADR-024): transfer does not depend on core:persistence. Storage is behind
            // the transfer-owned TransferStore port; core:engine's RoomTransferStore adapts Room
            // to it. This keeps Room/SQLCipher (4 native ABIs) off a lightweight consumer.
            //
            // TODO(cleanup): both androidx entries are dead — grep finds zero androidx references
            // in this module's main and test sources. Parked here rather than deleted so
            // core-transfer-android's POM keeps the two runtime-scope entries 1.1.0 consumers
            // resolve today; deleting them is a consumer-visible resolution change that should be
            // made repo-wide at once (Phase 10 precedent).
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        // Runs on BOTH the Android host-test JVM and the desktop jvm() target, so the FLSH v2
        // control-frame wire format is executed on each rather than merely compiled
        // (CONVENTIONS.md R3.1). Without it, jvmTest would run zero tests and the desktop target
        // would be compiled but unproven.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The 13 pre-existing suites are JUnit 4 and use java.io, java.util.concurrent and
        // TemporaryFolder, so they stay on the Android host-test tier, byte-for-byte unchanged.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications {
        // KMP generates the publications itself (root `kotlinMultiplatform`, plus one per
        // target), so a module must NOT `register<MavenPublication>("release")` any more.
        // Their default artifactIds derive from the project name (`transfer`, `transfer-android`,
        // `transfer-jvm`); rename in place to keep the published coordinates that 1.1.0
        // consumers already use. Version and group still come from the root build file —
        // never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("transfer", "core-transfer")
        }
    }
}
