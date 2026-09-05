plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under AGP 9+, so a
    // converted module swaps it for these two rather than adding to it. Same pair as `:ui:theme`
    // (Phase 17/18) and `:ui:platform-shims` (Phase 19).
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The Compose COMPILER plugin. Already here before this phase; it tracks the Kotlin version,
    // which is why it uses `libs.plugins.kotlin.compose` and not the CMP version.
    alias(libs.plugins.kotlin.compose)
    // The Compose dependency DSL (`compose.runtime`, `compose.ui`, `compose.components.*`),
    // versioned independently at CMP 1.9.3. NOT a duplicate of the line above: since CMP 1.6 the
    // Compose compiler ships inside Kotlin, so a multiplatform Compose module applies both. No
    // `composeResources/` here — `:ui:theme` owns every drawable (Phase 17) — so this plugin is
    // pulled in for the `compose.*` coordinates only.
    alias(libs.plugins.jetbrains.compose)
    `maven-publish`
}

kotlin {
    // Deliberately NO explicitApi(). The four ui/* modules were never part of the ADR-023 rollout;
    // R1 and R7 say to preserve what is there, not to extend it during a conversion.

    android {
        namespace = "com.transfer.flash.ui.chat"
        // 37, matching the other three ui/* modules rather than core/*'s 35. R10 freezes both.
        compileSdk = 37
        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Consumer rules are
        // dropped in SILENCE if this is omitted, so the file would still be on disk and no longer
        // reach any consumer.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`: the target publishes a single variant, so instead
        // of declaring build types it states which one local project dependencies resolve from. All
        // four project dependencies below are already KMP and variant-free, so this is declared for
        // symmetry with `:ui:theme` rather than because anything needs selecting.
        //
        // NOTE: the pre-KMP `buildTypes { release { isMinifyEnabled = false; proguardFiles(…) } }`
        // had no effect — a library applies its own proguardFiles only when minifying itself, and
        // minification was off. `proguard-rules.pro` therefore becomes unreferenced; it is left on
        // disk because deleting it is not this phase's job (R1). Same as `:ui:theme`.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility = VERSION_11 }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task — the KMP replacement for
        // `testDebugUnitTest`, which no longer exists once this module is multiplatform. The body is
        // empty ON PURPOSE: the pre-KMP module had no `testOptions` block, so
        // `isIncludeAndroidResources` stays at its default of false, which is what all 31 suites
        // already ran under.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No `src/androidTest` exists here;
        // the runner is carried over so the declaration is not silently lost.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5. The real compile
    // task is therefore `compileKotlinJvm`; `compileKotlinDesktop` does not exist. (PHASE-20 step 2
    // asks for an "R5.1 UI-track exception" declaring `jvm("desktop")`/`desktopMain`; that is wrong
    // on both counts — CMP does not require it, and `:ui:theme` and `:ui:platform-shims` already
    // shipped on plain `jvm()`. See this phase's STATUS box.)
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // All four are already KMP, which is the whole reason this module can be converted now:
            // `:core:common` (Phase 06), `:core:messaging` (Phase 11), `:ui:theme` (17 + 18) and
            // `:ui:platform-shims` (19). `:ui:platform-shims` is what replaced this module's last
            // `android.*` imports, so it belongs in commonMain, not androidMain.
            implementation(project(":core:common"))
            implementation(project(":core:messaging"))
            implementation(project(":ui:theme"))
            implementation(project(":ui:platform-shims"))

            // The multiplatform `compose.*` coordinates, exactly as the other two converted ui/*
            // modules use them: on the Android target each redirects (via CMP's Gradle metadata) to
            // the same `androidx.compose.*` artifact pinned by the BOM in `androidMain` below, so
            // the Android compile classpath is unchanged; on `jvm()` they resolve to the
            // Skiko-backed desktop artifacts.
            //
            // `implementation`, matching the pre-KMP block where every Compose dependency was
            // `implementation`. Not widened to `api`: `:app` brings its own Compose, and it already
            // needs `androidx.compose.runtime` on its own classpath to call a `@Composable` at all.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // Mandatory, and easy to miss because `compose.foundation` does not carry it: 120
            // imports across this module name `androidx.compose.animation.*` and
            // `androidx.compose.animation.core.*` — `AnimatedVisibility`, `animateFloatAsState`,
            // `Animatable`, `tween`, `Crossfade`. Same lesson as `:ui:theme`'s `FlashMotion`.
            implementation(compose.animation)
            // The CMP `@Preview` annotation: `org.jetbrains.compose.ui.tooling.preview.Preview`,
            // which the 29 preview-bearing files now import instead of the androidx one (an AAR,
            // therefore unresolvable for `jvm()`).
            //
            // This is the dependency that made the whole module movable. CMP 1.9.3's annotation
            // takes SEVEN parameters — name, group, widthDp, heightDp, locale, showBackground,
            // backgroundColor — and the 69 `@Preview` annotations here use only four of them
            // (showBackground 68, name 66, widthDp 45, heightDp 19), so every argument survived the
            // import swap verbatim. Nothing was stripped and no preview moved to androidMain.
            //
            // It pins no version (the coordinates come from the CMP plugin applied above, already
            // frozen at 1.9.3 — R10) and it is `implementation`, so it does not leak onto any
            // consumer's compile classpath.
            implementation(compose.components.uiToolingPreview)

            // 12 call sites: `Dispatchers`, `delay`, `launch`, `withContext`, `coroutineScope` in
            // the recorder/waveform/scroll paths. Compose runtime drags coroutines in transitively;
            // declaring it is what makes those imports legitimate rather than accidental.
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            // The androidx BOM tier stays on the Android side ON PURPOSE — it is what pins the
            // versions the `compose.*` coordinates above resolve to for Android, so this module's
            // Android compile classpath is bit-identical to 1.1.0's and CMP 1.9.3's older Compose
            // cannot silently downgrade the app. `KotlinDependencyHandler` has no `platform()`, so
            // the BOM goes through the project's own dependency handler.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.ui.graphics)
            implementation(libs.androidx.compose.material3)
            implementation("androidx.compose.foundation:foundation")
            // The next four have ZERO references anywhere in `ui/chat/src` — verified by grep, not
            // assumed. They are kept, not dropped, for the reason `:ui:theme` kept its own two:
            // removing a dependency from a published POM changes what every 1.2.0 consumer of
            // `ui-chat` resolves at runtime, and that is a release decision rather than part of
            // converting a module (R1). Recorded in the phase log so a cleanup phase can drop all
            // four together.
            //
            // `ui-tooling-preview` in particular became dead in THIS commit: Phase 19 left it
            // referenced by 29 files, and the CMP annotation above replaced every one of them.
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        // All 31 suites moved here from `src/test/java` and were converted from JUnit 4 assertions
        // to `kotlin.test`. `commonTest` cannot see `org.junit`, and per R3.1 this is the only way
        // the module's desktop compilation is *executed* rather than merely compiled: 239 tests now
        // run on both targets instead of 239 on Android alone.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // `kotlin("test")` resolves to `kotlin-test-junit` on both JVM tiers, so JUnit 4 must be on
        // each runtime classpath for its runner. Declared per-tier, NEVER in `commonTest` — that
        // source set must stay platform-free.
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
        // KMP generates the publications itself (root `kotlinMultiplatform`, plus one per target),
        // so a module must NOT `register<MavenPublication>("release")` any more — and
        // `android { publishing { singleVariant("release") { withSourcesJar() } } }` is gone too,
        // because KMP publishes sources for every target on its own. The default artifactIds derive
        // from the project name (`chat`, `chat-android`, `chat-jvm`); rename in place to keep the
        // `ui-chat` coordinate that 1.1.0 consumers already use. Version and group still come from
        // the root build file and must NOT be set here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("chat", "ui-chat")
        }
    }
}
