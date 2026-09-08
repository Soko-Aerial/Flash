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

    // `concurrent/PlatformLock` is an `expect` CLASS: the seam has to carry per-platform state
    // (the monitor object), which CONVENTIONS.md R2 sanctions. expect/actual classifiers are
    // still Beta (KT-61573) and warn per declaration site, so the flag is set at the extension
    // level to cover every target — same reason as :core:common and :core:security.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.transfer.flash.core.discovery"
        compileSdk = 35

        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Rules are
        // dropped in SILENCE if this is omitted; core/discovery's file is comment-only today,
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
        // `testDebugUnitTest`). Omit it and all 7 migrated test files stop compiling AND
        // running while the build still reports SUCCESS.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // `compileKotlinJvm` cannot see android.jar, which is what proves CompositeDiscovery and
    // FlashPeerGroupSession are genuinely free of Android APIs now that they live in commonMain.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // Public API returns kotlinx Flow/StateFlow (discoveredEndpoints, state,
            // mergedEvents), so coroutines must be `api` — an `implementation` scope keeps
            // those return types off a downstream consumer's compile classpath (verified via
            // :sample:consumer).
            api(libs.kotlinx.coroutines.core)
        }
        // No androidMain dependency block: `androidx.core.ktx` and
        // `androidx.lifecycle.runtime.ktx` were DELETED rather than relocated, because grep
        // proves this module has zero `androidx.*` references. Phase 07 relocated the same two
        // in :core:security only because that module's androidMain genuinely uses them.

        // Phase 14: desktop mDNS. `implementation`, not `api` — no JmDNS type appears in
        // JmdnsTransport's or JmdnsBridge's signatures (the bridge deliberately exposes only
        // neutral DTOs), so JmDNS must not land on a consumer's compile classpath.
        //
        // There is deliberately NO `dependsOn(getByName("jvmAndAndroidMain"))` here, contrary
        // to PHASE-14 step 2: D1 = Option B means that source set does not exist (CONVENTIONS
        // R5). `jvmMain` already sees `commonMain` — including its `internal` declarations,
        // since they are the same Gradle module — so `TxtCodec`, `EndpointDirectory` and
        // `CompositeDiscovery` are all reachable with no extra wiring.
        jvmMain.dependencies {
            implementation(libs.jmdns)
        }

        // Runs on BOTH the Android host-test JVM and the desktop jvm() target, so the two
        // PlatformLock actuals are executed, not merely compiled (CONVENTIONS.md R3.1).
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Needed by PlatformLockTest's contention case: `runTest` is the only way to launch
            // coroutines from a non-suspend test function in common code. Existing catalog alias
            // pinned at the same 1.10.2 as coroutines-core, so no version moves (R10).
            implementation(libs.kotlinx.coroutines.test)
        }
        // The 7 pre-existing suites are JUnit 4 and use java.util.concurrent for pacing, so
        // they stay on the Android host-test tier, byte-for-byte unchanged.
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
        // Their default artifactIds derive from the project name (`discovery`,
        // `discovery-android`, `discovery-jvm`); rename in place to keep the published
        // coordinates that 1.1.0 consumers already use. Version and group still come from the
        // root build file — never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("discovery", "core-discovery")
        }
    }
}
