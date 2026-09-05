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
// "Blocked by: Phase 17". That is a circular deadlock, and it is broken here by taking the
// KMP shell now and leaving every Kotlin file exactly where it is — see the srcDir shims
// below — so PHASE-18's move table (`src/main/java/...` -> `commonMain`/`androidMain`) stays
// executable as written and no file is moved twice.
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

    // Desktop/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5. PHASE-18
    // is where this target gets Kotlin sources; it is declared now because PHASE-17 step 5
    // makes a desktop compile its critical gate ("it proves the `Res.drawable.xxx` accessors
    // work for the JVM target"), and that gate is only real if the target exists. `jvmMain`
    // holds no files — the only thing compiled for JVM here is the generated `Res` object.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        // THE SRCDIR SHIMS. Every Kotlin file keeps its pre-KMP path, so PHASE-18 can still
        // `git mv` from `src/main/java/...` exactly as its move table says, and no file is
        // relocated twice. PHASE-18 deletes these two lines as part of that move.
        getByName("androidMain").kotlin.srcDir("src/main/java")
        getByName("androidHostTest").kotlin.srcDir("src/test/java")

        commonMain.dependencies {
            // `api`, NOT `implementation`: `DrawableResource` is the declared type of the
            // public `FlashIconSpec.drawableRes` property, so a consumer cannot touch
            // `FlashIcons` without this artifact on their compile classpath. Same lesson as
            // room-runtime in 09B-1.
            api(compose.components.resources)
            // NOT redundant with the line above, and not optional. `components-resources` does
            // NOT put the Compose runtime on a consumer's COMPILE classpath, so without this
            // `compileKotlinJvm` dies before it emits anything:
            //   e: androidx.compose.compiler.plugins.kotlin.IncompatibleComposeRuntimeVersionException:
            //   The Compose Compiler requires the Compose Runtime to be on the class path, but
            //   none could be found.
            // The Compose COMPILER plugin runs on every Kotlin compilation in the module and
            // performs that check unconditionally — even here, where the only JVM source is
            // CMP's own generated resource collectors and not one `@Composable` exists. The
            // Android target never hit it because the androidx BOM tier below supplies
            // `androidx.compose.runtime` transitively.
            // `implementation`, not `api`, to preserve the pre-KMP status quo: every Compose
            // dependency in this module was `implementation`. PHASE-18 rewrites this block for
            // D3 = A and can revisit it once real composables live in commonMain.
            implementation(compose.runtime)
        }
        androidMain.dependencies {
            // Unchanged from the pre-KMP dependency block, moved verbatim into the Android
            // source set. None of it is hoisted to commonMain: no commonMain Kotlin file
            // exists yet to need it, and hoisting is PHASE-18's job (D3 = A also replaces the
            // BOM with `compose.*` artifacts there, not here).
            implementation(project(":core:common"))
            // `KotlinDependencyHandler` has no `platform()`, so the BOM goes through the
            // project's own dependency handler.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.ui.graphics)
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.compose.material3)
            implementation("androidx.compose.foundation:foundation")
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        // The five existing suites are plain JUnit 4 (no Robolectric), and they stay Android-
        // only until PHASE-18 moves them to commonTest.
        getByName("androidHostTest").dependencies {
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
