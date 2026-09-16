plugins {
    // Born KMP. `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under
    // AGP 9+, so the Android side comes from `android.kotlin.multiplatform.library` instead — the
    // same pair every converted module in this repo uses.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The Compose COMPILER plugin; tracks the Kotlin version, hence `libs.plugins.kotlin.compose`.
    alias(libs.plugins.kotlin.compose)
    // The Compose dependency DSL (`compose.runtime`, `compose.ui`), versioned independently at CMP
    // 1.9.3. NOT a duplicate of the line above — since CMP 1.6 the compiler ships inside Kotlin and
    // a multiplatform Compose module applies both. No `composeResources/` here, so this plugin is
    // pulled in purely for the `compose.*` coordinates.
    alias(libs.plugins.jetbrains.compose)
    // Load-bearing, not boilerplate: `:ui:chat` publishes `ui-chat`, and AGP writes
    // `implementation(project(...))` into the published POM as a runtime-scoped dependency. An
    // unpublished shims module would leave every 1.2.0 consumer of `ui-chat` with an unresolvable
    // POM entry. See the `publishing` block at the bottom.
    `maven-publish`
}

kotlin {
    // Deliberately NO explicitApi(). The ui/* tier was never part of the ADR-023 rollout and R1/R7
    // say to preserve what is there, not to extend it. Every declaration is spelled `public`
    // anyway, so flipping this on later is a no-op for this module.

    android {
        namespace = "com.transfer.flash.ui.shims"
        // 37, matching the other three ui/* modules rather than core/*'s 35. R10 freezes both.
        compileSdk = 37
        // NOT inside `defaultConfig { }` — the KMP Android target is variant-free.
        minSdk = 24

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task. Body empty on purpose:
        // `isIncludeAndroidResources` stays false, matching every other module here.
        withHostTest { }

        // No `withDeviceTest { }` and no `optimization { consumerKeepRules { } }`: this module has
        // no `src/androidTest` and no `consumer-rules.pro`. Declaring either would name a file that
        // does not exist or a task with nothing to run.
    }

    // Desktop/CI target. Plain `jvm()`, never `jvm("desktop")` — R5. The real compile task is
    // therefore `compileKotlinJvm`; `compileKotlinDesktop` does not exist.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The multiplatform `compose.*` coordinates, exactly as `:ui:theme` uses them: on the
            // Android target each redirects (via CMP's Gradle metadata) to the same
            // `androidx.compose.*` artifact pinned by the BOM in `androidMain` below, so the Android
            // compile classpath sees no CMP version; on `jvm()` they resolve to the Skiko-backed
            // desktop artifacts.
            //
            // `api`, not `implementation` — unusual here and deliberate. Every seam in this module
            // has Compose types in its *public signature*: `@Composable` factories, `ImageBitmap`
            // return values, `AnnotatedString`. A consumer cannot call `rememberFlashImageDecoder()`
            // without `androidx.compose.ui` on its own compile classpath. `:ui:theme` could stay on
            // `implementation` because its consumers already bring Compose to name a `Color`; the
            // same is true of `:ui:chat` today, but leaking that assumption into a module whose
            // entire surface is Compose would be the room-runtime mistake from 09B-1 again.
            api(compose.runtime)
            api(compose.ui)
            // `suspend fun ensureGranted` and the `CancellableContinuation` the Android actual
            // resumes. Compose runtime drags coroutines in transitively; declaring it is what makes
            // the `kotlinx.coroutines` import in `FlashPermissions.android.kt` legitimate rather
            // than accidental.
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            // The androidx BOM tier stays on the Android side ON PURPOSE — it is what pins the
            // versions the `compose.*` coordinates above resolve to for Android, so CMP 1.9.3's
            // older Compose cannot silently downgrade the app. `KotlinDependencyHandler` has no
            // `platform()`, so the BOM goes through the project's own dependency handler.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.ui.graphics)
            // Three separate actuals need this one artifact: `BackHandler` (FlashBackHandler),
            // `rememberLauncherForActivityResult` + `ActivityResultContracts.OpenDocument`
            // (FlashFilePicker) and `ActivityResultContracts.RequestPermission` (FlashPermissions).
            // This is the dependency `:ui:chat` was carrying for those same three reasons.
            implementation(libs.androidx.activity.compose)
            // `androidx.core.content.ContextCompat.checkSelfPermission`, from `androidx.core:core`.
            implementation(libs.androidx.core.ktx)
        }

        // Shared tests for the parts that are pure arithmetic/string handling, so they run on both
        // targets (R3.1: an `actual` that is only compiled is not verified — these cover the shared
        // contract; the `android.media` actuals themselves need a device, which is recorded in the
        // log as unverifiable here).
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // `kotlin("test")` resolves to `kotlin-test-junit` on both JVM tiers, so JUnit 4 must be on
        // each runtime classpath for its runner. Declared per-tier, NEVER in `commonTest` — that
        // source set must stay platform-free.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        // ERROR-063: pure-Java MP4 demux + AAC decode for desktop voice notes (`.m4a`), which
        // `javax.sound.sampled` cannot open. jvmMain only — the Android actual plays through
        // MediaPlayer and never names this.
        jvmMain.dependencies {
            implementation(libs.jcodec)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            // Skiko's NATIVE runtime, and only for the test classpath.
            //
            // `compose.ui` (declared in commonMain above) brings `skiko-awt` — the Java API — but not
            // the platform `.dll`/`.so`. Nothing in `jvmMain` needs it: the desktop app that consumes
            // this module supplies its own via the Compose Desktop plugin. A *test*, though, calls
            // `BufferedImage.toComposeImageBitmap()` for real, and without the native the class
            // initializer throws `LibraryLoadException: Cannot find skiko-windows-x64.dll.sha256` —
            // which `JvmImageDecoder.decode`'s `runCatching` would then report as "this image does not
            // decode", i.e. six green-looking tests asserting null for the wrong reason.
            //
            // `compose.desktop.currentOs` resolves to the native bundle for whichever host is
            // building, so this stays correct on a Linux CI box. It adds no version to
            // `libs.versions.toml` and pins nothing new (R10): the coordinates come from the
            // `org.jetbrains.compose` plugin already applied above, at its already-frozen 1.9.3. And
            // because a test dependency is not published, `ui-platform-shims`'s POM is unchanged.
            implementation(compose.desktop.currentOs)
        }
    }
}

publishing {
    publications {
        // KMP generates the publications itself (root `kotlinMultiplatform` plus one per target), so
        // there is no `register<MavenPublication>("release")` and no `singleVariant` block. Default
        // artifactIds derive from the project name — `platform-shims`, `platform-shims-android`,
        // `platform-shims-jvm` — so rename in place for the `ui-` prefix the other two ui/* modules
        // publish under. Version and group come from the root build file and must NOT be set here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("platform-shims", "ui-platform-shims")
        }
    }
}
