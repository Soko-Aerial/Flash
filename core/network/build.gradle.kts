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
    // an `expect class`. This module declares no expect/actual at all, so the flag would suppress
    // a warning that can never fire here. See docs/migration/PHASE-10-network-kmp.md.

    android {
        namespace = "com.transfer.flash.core.network"
        compileSdk = 35

        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Rules are
        // dropped in SILENCE if this is omitted; core/network's file is comment-only today,
        // but the block preserves the pre-KMP publishing behaviour exactly.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`: the target publishes a single variant, so
        // instead of declaring build types it states which one local project deps resolve from.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task (the KMP replacement for
        // `testDebugUnitTest`). Omit it and all 21 migrated test files stop compiling AND
        // running while the build still reports SUCCESS.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // `compileKotlinJvm` cannot see android.jar, which is what proves the 14 commonMain files
    // (the FlashSession/FlashNetwork contract + the resilience policies) are Android-free.
    // It carries NO desktop transport: every socket implementation stays in androidMain until
    // Phase 15. jvmMain is intentionally empty.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // FlashSession.connectionState/frameAcks and FlashNetwork's flows are public
            // signatures, so coroutines must be `api` — `implementation` keeps StateFlow/Flow
            // off a downstream consumer's compile classpath.
            api(libs.kotlinx.coroutines.core)
            // Real edge: bridge/DiscoveryRouteBinder.kt uses FlashDiscoveredEndpoint, which is
            // commonMain in :core:discovery since Phase 08.
            implementation(project(":core:discovery"))
        }
        androidMain.dependencies {
            // TODO(cleanup): all three are dead — grep finds zero references in main and test.
            // Parked here instead of deleted so core-network-android's POM keeps the three
            // runtime-scope entries 1.1.0 consumers resolve today; deleting them is a
            // consumer-visible resolution change that should be made repo-wide at once.
            // :core:security's only real users (app, core:engine) both declare it directly.
            implementation(project(":core:security"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        // Runs on BOTH the Android host-test JVM and the desktop jvm() target, so the shared
        // sendText contract is executed on each rather than merely compiled (CONVENTIONS.md R3.1).
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Catalog alias pinned at the same 1.10.2 as coroutines-core, so no version moves (R10).
            implementation(libs.kotlinx.coroutines.test)
        }
        // The 20 pre-existing suites + SoftwareCertMaker are JUnit 4 and use java.util.concurrent,
        // real loopback sockets and BouncyCastle, so they stay on the Android host-test tier,
        // byte-for-byte unchanged.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.bouncycastle.pkix) // tls/SoftwareCertMaker.kt only
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
        // Their default artifactIds derive from the project name (`network`, `network-android`,
        // `network-jvm`); rename in place to keep the published coordinates that 1.1.0
        // consumers already use. Version and group still come from the root build file —
        // never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("network", "core-network")
        }
    }
}
