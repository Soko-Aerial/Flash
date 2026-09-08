plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under
    // AGP 9+. A converted module swaps it for these two rather than adding to it.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The Compose COMPILER plugin. Already here before this phase; it must track the Kotlin
    // version, which is why it uses `libs.plugins.kotlin.compose` and not the CMP version.
    alias(libs.plugins.kotlin.compose)
    // Phase 17, enacting D3 = Option A. This is NOT a duplicate of the line above: since CMP
    // 1.6 the Compose compiler ships inside Kotlin, so a CMP module applies both. This one
    // contributes the `compose` dependency DSL and the `composeResources/` -> `Res` accessor
    // generation, and is versioned independently (CMP 1.9.3 — see libs.versions.toml for why
    // it is not 1.12.0).
    alias(libs.plugins.jetbrains.compose)
    `maven-publish`
}

// WHY PHASE 17 CONVERTS THIS MODULE AT ALL.
//
// PHASE-17 step 4 says to keep `com.android.library` and merely add the CMP plugin. That is
// impossible here. Under AGP 9's built-in Kotlin an Android-only module exposes no Kotlin
// Gradle extension (see the root build file's ADR-023 note, which is why BCV was removed),
// and CMP's resource generation hooks `KotlinProjectExtension` — so `composeResources/` is
// never read until the module is KMP. PHASE-17 precondition 1 assumed Phase 06 had already
// converted `ui:theme`; it had not. PHASE-18, which does the conversion, declares itself
// "Blocked by: Phase 17". That is a circular deadlock, and it was broken by taking the KMP
// shell in Phase 17 and leaving every Kotlin file where it was, behind two `srcDir` shims, so
// PHASE-18's move table (`src/main/java/...` -> `commonMain`/`androidMain`) stayed executable as
// written and no file was moved twice. Phase 18 has since performed that move and deleted the
// shims; `src/main` and `src/test` no longer exist.
kotlin {
    // Deliberately NO explicitApi(). The three ui/* modules were never part of the ADR-023
    // rollout; R1 and R7 say to preserve what is there, not to extend it in a resource phase.

    android {
        namespace = "com.transfer.flash.ui.theme"
        // 37, not the 35 the core modules use. R10 freezes both values as they are.
        compileSdk = 37

        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Rules are
        // dropped in SILENCE if this is omitted.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`: the target publishes a single variant, so
        // instead of declaring build types it states which one local project deps resolve
        // from. `:core:common` is already KMP and variant-free, so this is declared for
        // template symmetry rather than because anything needs selecting.
        //
        // NOTE: the pre-KMP `buildTypes { release { isMinifyEnabled = false; proguardFiles(…) } }`
        // had no effect — a library only applies its own proguardFiles when minifying itself,
        // and minification was off. `proguard-rules.pro` therefore becomes unreferenced; it is
        // left on disk because deleting it is not this phase's job (R1).
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task (the KMP replacement for
        // `testDebugUnitTest`). The body is empty ON PURPOSE: the pre-KMP module had no
        // `testOptions` block at all, so `isIncludeAndroidResources` stays at its default of
        // false, which is what the five existing suites already ran under.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5, which is
    // why the real task is `compileKotlinJvm` and `compileKotlinDesktop` does not exist. Phase
    // 17 declared it empty to make its `Res` accessor gate real; Phase 18 gave it sources:
    // 18 shared files plus 3 `actual`s in `jvmMain`, and `jvmTest` now runs the 37 shared tests.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        // PHASE-17's two `srcDir` shims are GONE, in the same commit that moved the files.
        // They had to change together: leaving them would have Gradle compile nothing from
        // `src/main/java` (now empty) while the real sources sat unregistered, and deleting
        // them without moving would have left the module with no Kotlin at all.

        commonMain.dependencies {
            // D3 = Option A, enacted. These are the multiplatform `compose.*` coordinates, not
            // the androidx ones: on the Android target each resolves to the same
            // `androidx.compose.*` artifact (CMP's Gradle metadata redirects the `android`
            // variant), pinned by the BOM in `androidMain` below, so the Android compile
            // classpath is unchanged. On `jvm()` they resolve to the Skiko-backed desktop
            // artifacts, which is what makes 18 files compile for desktop at all.
            //
            // `implementation`, matching the pre-KMP block where every Compose dependency was
            // `implementation`. It is deliberately not widened to `api`: `:ui:chat` and `:app`
            // bring their own Compose, and a consumer that touches `FlashTheme.colors` already
            // needs `androidx.compose.ui` on its own classpath to name a `Color`.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            // Not in PHASE-18 step 6, and mandatory: `FlashMotion` (355 lines of it) and
            // `FlashBrandAnimation` import `androidx.compose.animation.*` and
            // `androidx.compose.animation.core.*` — `EnterTransition`, `fadeIn`, `Animatable`,
            // `CubicBezierEasing`, `spring`. `compose.foundation` does not carry them.
            implementation(compose.animation)
            // `api`, NOT `implementation`: `DrawableResource` is the declared type of the
            // public `FlashIconSpec.drawableRes` property, so a consumer cannot touch
            // `FlashIcons` without this artifact on their compile classpath. Same lesson as
            // room-runtime in 09B-1. (PHASE-18 step 6 downgrades this to `implementation`;
            // that would re-break what Phase 17 fixed.)
            api(compose.components.resources)
        }
        androidMain.dependencies {
            // Kept, not hoisted. `:core:common` has zero references anywhere in this module
            // (grep for `com.transfer.flash.core.common` over `ui/theme/src` returns nothing),
            // so PHASE-18 step 6's move of it to `commonMain` would only add a dependency to
            // the *new* `ui-theme-jvm` POM. Deleting it outright is the `:core:discovery`
            // precedent but would change the published Android POM's runtime classpath, which
            // is not this phase's business (R1). It stays exactly where Phase 17 left it.
            implementation(project(":core:common"))
            // The androidx BOM tier stays on the Android side ON PURPOSE. `compose.*` above
            // resolves to these same artifacts for the Android target, and this BOM is what
            // pins their versions — so `:ui:theme`'s Android compile classpath is bit-identical
            // to 1.1.0's, and CMP 1.9.3's own (older) Compose versions cannot silently
            // downgrade the app. `KotlinDependencyHandler` has no `platform()`, so the BOM goes
            // through the project's own dependency handler.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.ui.graphics)
            // Android-only, and now load-bearing rather than vestigial: `FlashThemeSwatches.kt`
            // stayed in `androidMain` precisely because its seven `@Preview` functions need
            // THIS artifact's `Preview` annotation. CMP 1.9.3's
            // `org.jetbrains.compose.ui.tooling.preview.Preview` takes no arguments, so moving
            // the file to commonMain would silently drop every `name`/`widthDp`/`fontScale`.
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.compose.material3)
            implementation("androidx.compose.foundation:foundation")
            // Zero references in this module, kept for the same reason as `:core:common`:
            // PHASE-18 step 6 keeps both (it puts lifecycle in commonMain, where an Android AAR
            // cannot resolve for `jvm()`), and dropping a published runtime dependency is a
            // separate decision from converting a module.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        // The five suites moved here from `src/test/java`, converted from JUnit 4 assertions to
        // `kotlin.test`. That is a deviation from PHASE-18's "Do NOT change test imports", and
        // it is forced: `commonTest` cannot see `org.junit`, and step 5 puts the suites in
        // `commonTest`. The two instructions cannot both be obeyed. Converting is what every
        // other module did (`:core:security`, `:core:discovery`), and per R3.1 it is the only
        // way the three `actual`s get *executed* on both targets rather than merely compiled.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // `kotlin("test")` resolves to `kotlin-test-junit` on both JVM tiers, and JUnit 4 must
        // be on the runtime classpath for its runner. Declared per-tier, never in `commonTest`
        // (PHASE-18 step 6 puts `libs.junit` there; that would put a JVM-only artifact in a
        // source set that must stay platform-free).
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

// The generated accessor package. CMP derives it from `<group>.<project name>` by default,
// which here would be `com.transfer.flash.theme.generated.resources` — NOT the import PHASE-17
// step 3d mandates. Pinning it makes that import literally correct instead of "verify it after
// the build and adjust", which is what the phase file resorts to.
compose.resources {
    packageOfResClass = "com.transfer.flash.ui.theme.generated.resources"
}

publishing {
    publications {
        // KMP generates the publications itself (root `kotlinMultiplatform`, plus one per
        // target), so a module must NOT `register<MavenPublication>("release")` any more — and
        // `android { publishing { singleVariant("release") { withSourcesJar() } } }` is gone
        // too, because KMP publishes sources for every target on its own. The default
        // artifactIds derive from the project name (`theme`, `theme-android`, `theme-jvm`);
        // rename in place to keep the `ui-theme` coordinate 1.1.0 consumers already use.
        // Version and group still come from the root build file — the ui/* modules used to set
        // them here and silently published 1.0.0 for the whole 1.1.0 cycle.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("theme", "ui-theme")
        }
    }
}
