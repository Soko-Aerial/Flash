// PHASE-06 (KMP pilot). This is the first module converted from `com.android.library`
// to Kotlin Multiplatform. Every DSL spelling below was read off the AGP 9.3.1 API
// surface (javap on gradle-api-9.3.1.jar) and then proven by a configuration run —
// see docs/migration/logs/migration.md, Phase 06, Step 4b. Copy this file as the
// template for phases 07-12; the notes mark the places where the KMP DSL differs
// from the AGP `android { }` block it replaces.
plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under
    // AGP 9+. A converted module swaps it for these two rather than adding to it.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // Phase 3 Task 3.2, unchanged by the conversion: strict explicit-API mode, so
    // nothing reaches the published ABI by omission. See docs/publishing/PHASE-03-api-surface.md.
    explicitApi()

    // `expect`/`actual` CLASSES are still flagged Beta by the compiler (KT-61573), which
    // emits a warning per declaration site plus one per `actual`. `expect`/`actual`
    // FUNCTIONS are stable and warning-free, so three of the four Phase 06 seams
    // (platformLogSink, currentTimeMillisPlatform, randomUuidString) are functions on
    // purpose. `PlatformLock` cannot be: it has to carry per-platform state. The flag is
    // JetBrains' own recommendation in that YouTrack issue; it suppresses the warning
    // without changing codegen. Set at the extension level so it covers every target.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.transfer.flash.core.common"
        compileSdk = 35

        // NOT inside `defaultConfig { }` any more — the KMP Android target is
        // variant-free, so minSdk is a direct property of the target.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`.
        // `consumerProguardFiles` does not exist on this target. `consumerKeepRules`
        // is a read-only getter (not an Action-taking block), hence property access
        // plus `.apply`. Rules are dropped in SILENCE if this is omitted:
        // core/common's own rules file holds only comments, but core:persistence's
        // does not, so phases 07-12 must carry this block across.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces the `buildTypes { release { } }` block. The KMP Android target
        // publishes a single variant, so instead of declaring build types it states
        // which build type to resolve local project dependencies from.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates the `androidHostTest` source set + the `testAndroidHostTest` task
        // (the KMP replacement for `testDebugUnitTest`). `withHostTestBuilder { }`
        // also resolves but is for renaming the compilation, not for configuring it.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = ... }`. Creates the
        // `androidDeviceTest` source set. core/common has no instrumented tests yet;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // This target is what proves `commonMain` is actually free of Android APIs:
    // `compileKotlinJvm` cannot see android.jar at all.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        // D1 = B (strict commonMain): there is deliberately NO `jvmAndAndroidMain`
        // intermediate source set, because iOS/Kotlin-Native is in scope and a shared
        // JVM-only parent would let `java.*` leak back into shared code. Duplicated
        // one-line `actual`s in androidMain/jvmMain are the accepted cost.
        //
        // The 9 test files use JUnit 4 (org.junit.Assert.*) and one of them uses
        // java.util.concurrent, so tests stay on the Android host-test tier for the
        // pilot. Moving them to `commonTest` on kotlin.test is Phase 07+ work.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }

        // `implementation(libs.androidx.core.ktx)` was dropped: grep proves core/common
        // has ZERO androidx references, so the dependency was inert and would have
        // pinned an Android-only artifact onto a now-multiplatform module.
    }
}

publishing {
    publications {
        // KMP generates the publications itself (root `kotlinMultiplatform`, plus one
        // per target), so a module must NOT `register<MavenPublication>("release")`
        // any more. Their default artifactIds derive from the project name (`common`,
        // `common-android`, `common-jvm`); rename in place to keep the published
        // coordinates that 1.1.0 consumers already use. Version and group still come
        // from the root build file — never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("common", "core-common")
        }
    }
}
