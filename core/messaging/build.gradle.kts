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
    // This module declares no expect/actual: PresenceHold and RealFlashChatRepository are
    // androidMain under CONVENTIONS.md R2 step 1, not behind a seam. See
    // docs/migration/PHASE-11-repositories-kmp.md.

    android {
        namespace = "com.transfer.flash.core.messaging"
        compileSdk = 35

        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Rules are
        // dropped in SILENCE if this is omitted; core/messaging's file is comment-only today,
        // but the block preserves the pre-KMP publishing behaviour exactly.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`: the target publishes a single variant, so
        // instead of declaring build types it states which one local project deps resolve from.
        // This is load-bearing here: `:core:persistence` is still `com.android.library` because
        // Phase 09 is blocked on D5, so it is the variant-ful dependency this selects from.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task (the KMP replacement for
        // `testDebugUnitTest`). Omit it and all 4 migrated test files stop compiling AND
        // running while the build still reports SUCCESS.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // `compileKotlinJvm` cannot see android.jar, which is what proves the 4 commonMain files
    // (the FlashChatRepository contract + its sample implementation, the 20-type messaging UI
    // model set that Phases 17–20 render, MessageWireFrame, and the grouping/preview helpers)
    // are Android-free. A green compile here also proves no Room type reached the desktop
    // classpath, because the jvm() target has no `:core:persistence` edge at all.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // Public API returns kotlinx.coroutines Flow/StateFlow (chat streams), so coroutines
            // must be `api` (implementation would keep those return types off a consumer's
            // classpath).
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // RealFlashChatRepository consumes 16 Room types — 7 DAOs, 7 entities and the 2 DAO
            // projections ConversationPreview/ConversationUnread. This is the module's one
            // genuinely load-bearing Android edge, and it is why that 1306-line file is
            // androidMain. Nothing in commonMain and nothing on the jvm() target sees Room.
            implementation(project(":core:persistence"))
            // TODO(cleanup): `:core:security`, `:core:network` and both androidx entries are dead
            // — grep finds zero references to any of them in this module's main and test sources.
            // Parked here rather than deleted so core-messaging-android's POM keeps the four
            // runtime-scope entries 1.1.0 consumers resolve today; deleting them is a
            // consumer-visible resolution change that should be made repo-wide at once
            // (Phase 10 precedent, which parked three the same way).
            implementation(project(":core:security"))
            implementation(project(":core:network"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        // Runs on BOTH the Android host-test JVM and the desktop jvm() target (CONVENTIONS.md
        // R3.1). Pins SampleFlashChatRepository, whose two System.currentTimeMillis() calls this
        // phase replaced with :core:common's SystemTimeSource.nowMs() — the one content change in
        // the module, so it is the one thing that must be executed on both platforms.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The 4 pre-existing suites are JUnit 4; RealFlashChatRepositoryTest and PresenceHoldTest
        // exercise androidMain types. Unchanged by the conversion.
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
        // Their default artifactIds derive from the project name (`messaging`,
        // `messaging-android`, `messaging-jvm`); rename in place to keep the published
        // coordinates that 1.1.0 consumers already use. Version and group still come from the
        // root build file — never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("messaging", "core-messaging")
        }
    }
}
