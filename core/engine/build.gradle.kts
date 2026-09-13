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

    compilerOptions {
        // Required because this module declares `expect class PlatformLock`. expect/actual
        // CLASSES are still Beta (KT-61573) and warn once per declaration site; CONVENTIONS.md
        // R2 permits a class here because the seam carries per-platform state (a monitor).
        // Same wiring as :core:common (Phase 06) and :core:discovery (Phase 08).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.transfer.flash.core.engine"
        compileSdk = 35

        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // ─────────────────────────────────────────────────────────────────────────────
        // FIRST module in the migration to need this. Android resources are NOT enabled
        // by default under `com.android.kotlin.multiplatform.library`: omit this block and
        // `src/androidMain/res/drawable/flash_bolt.xml` is silently dropped, so the
        // published AAR loses `@drawable/flash_bolt` while the build still reports SUCCESS.
        //
        // `resourcePrefix` moves here from the old `android { resourcePrefix = "flash_" }`.
        // Verified against AGP 9.3.1's `com.android.build.api.dsl.LibraryAndroidResources`,
        // which declares exactly two members beyond the shared AndroidResources interface:
        //   boolean getEnable() / setEnable(boolean)
        //   String  getResourcePrefix() / setResourcePrefix(String)
        // ─────────────────────────────────────────────────────────────────────────────
        androidResources {
            enable = true
            resourcePrefix = "flash_"
        }

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Rules are
        // dropped in SILENCE if this is omitted. core/engine's file is comment-only today
        // (it documents why no first-party keep rule is needed), but the block preserves the
        // pre-KMP publishing behaviour exactly.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`: the target publishes a single variant, so
        // instead of declaring build types it states which one local project deps resolve
        // from. Load-bearing here — `:core:persistence` is still `com.android.library`
        // because Phase 09 is blocked on D5, so it is the variant-ful dependency this
        // selects from. Phase 11 measured that this does work across the plugin boundary.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task (the KMP replacement for
        // `testDebugUnitTest`). Omit it and DefaultFlashEngineTest stops compiling AND running
        // while the build still reports SUCCESS. No `isIncludeAndroidResources` — the suite
        // uses no Robolectric and reads no resource.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        // All six converted `:core:*` modules stay `api` and stay in commonMain even though
        // commonMain's own two files import none of them. `Flash.kt` (androidMain) uses all six
        // and inherits them through the source-set hierarchy, and declaring them here is what
        // puts them in the ROOT `core-engine` POM — which is what a Maven-only consumer that
        // resolves the platform-agnostic coordinate reads. (That POM lists them at `runtime`
        // scope, not `compile`; every converted module's root POM does, `core-transfer`'s
        // included. Gradle consumers read `.module` metadata instead and see `api`.) Coroutines
        // is not declared: it arrives transitively as `api` from every one of the six, exactly
        // as before.
        //
        // The seventh, `:core:ptt`, is deliberately NOT here: it is not a converted KMP module
        // but a plain AGP Android library, so it has no JVM variant to hand commonMain — see
        // androidMain below.
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:security"))
            api(project(":core:discovery"))
            api(project(":core:network"))
            api(project(":core:transfer"))
            api(project(":core:messaging"))
        }
        androidMain.dependencies {
            // `api` for the same reason as `core:persistence` below: `FlashPtt` appears in the
            // PUBLIC `FlashEngine.attachPtt(...)` / `FlashEngine.ptt` signatures, so a consumer
            // cannot call them without it on its compile classpath.
            //
            // And androidMain rather than commonMain, unlike the six above: `:core:ptt` is a
            // plain AGP Android library, so it exposes Android variants only. Declaring it in
            // commonMain makes `jvmMainCompileClasspath` request a JVM-compatible variant of it
            // and fail variant selection — which broke `:core:engine:compileKotlinJvm`, its
            // `jvmTest`, and `publishToMavenLocal` (hence the whole JitPack install list) while
            // `compileAndroidMain` stayed green. Nothing in commonMain or jvmMain names PTT.
            api(project(":core:ptt"))

            // `compileOnly`, NOT `api` — the module registers this in the OTHER direction.
            //
            // `FlashCalling` appears in the PUBLIC calling seam (`FlashEngine.calls`,
            // `attachCalling(engine)`, `onInboundCallText`), so this module must compile against
            // it. It is NOT propagated to consumers: `:core:calling` re-exports webrtc-kmp with
            // `api(libs.webrtc.kmp)`, and the README's dependency-shape promise is that
            // `core-engine` does not pull native WebRTC (~30 MB per ABI) into an app that never
            // places a call. `compileOnly` keeps the type on this module's compile classpath
            // while the published `core-engine` metadata declares neither `core-calling` nor
            // `webrtc-kmp` — a consumer that actually calls adds `core-calling` itself, which the
            // README already instructs.
            //
            // Consequence to respect in code, not just in the build file: nothing on a path a
            // NON-calling consumer executes may name a `:core:calling` type, or the JVM resolves
            // a class that is legitimately absent and throws NoClassDefFoundError. That is why
            // Flash.kt gates on a plain `FLASH_CALL` prefix constant rather than
            // `CallFrameCodec.decode`, and why the facade's routing entry points are typed
            // without `FlashCalling`. See docs/decisions.md (ADR-033).
            //
            // androidMain rather than commonMain for the same reason as `:core:ptt` above:
            // `:core:calling` is a plain AGP Android library with no JVM variant, so a commonMain
            // entry breaks `:core:engine`'s `jvm()` target at variant selection (ERROR-049).
            // Nothing in commonMain or jvmMain names calling.
            compileOnly(project(":core:calling"))

            // `api`, not `implementation`: FlashSettingsDataStore appears in the PUBLIC
            // FlashEngine interface (`val settings`), so a consumer cannot use the engine
            // without it on their compile classpath. This is also the pin that keeps
            // FlashEngine.kt itself out of commonMain.
            api(project(":core:persistence"))

            // Not dead: Flash.kt spreads `*FlashMigrations.ALL` into
            // FlashDatabaseOpener.openEncrypted and closes the RoomDatabase on teardown, so
            // Room's types are on this module's own compile classpath. `implementation` keeps
            // Room an internal detail rather than re-leaking it to consumers (ADR-024).
            implementation(libs.androidx.room.runtime)

            // TODO(cleanup): both androidx entries are dead — grep finds zero `androidx.core`
            // or `androidx.lifecycle` references in this module's main and test sources.
            // Parked on androidMain rather than deleted so core-engine-android's POM keeps the
            // two runtime-scope entries 1.1.0 consumers resolve today. Phase 08 deleted the
            // same pair in :core:discovery; Phases 10 and 11 parked them. Following the later
            // precedent — a consumer-visible resolution change should be made repo-wide at
            // once, not one module at a time.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        // Runs on BOTH the Android host-test JVM and the desktop jvm() target, so the two
        // PlatformLock actuals are executed and not merely compiled (CONVENTIONS.md R3.1).
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Needed by the gate's contention case: `runTest` is the only way to launch
            // coroutines from a non-suspend test function in common code. Existing catalog
            // alias, pinned at the same 1.10.2 as coroutines-core, so no version moves (R10).
            implementation(libs.kotlinx.coroutines.test)
        }
        // DefaultFlashEngineTest is JUnit 4, builds a real FlashSettingsDataStore over a
        // java.io temp file, and uses a backtick method name. Unchanged by the conversion.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)

            // Test-only, and NOT published: the stub `FlashCalling` that pins the facade's routing
            // semantics (consumed / dropped / not attached) needs the interface to implement, and a
            // host test is the only place it can be reached without a real WebRTC engine. The
            // production dependency stays `compileOnly` (see androidMain above), so this adds
            // nothing to the published `core-engine` metadata — the artifact a consumer resolves is
            // built from the main compilations, not from a test classpath.
            implementation(project(":core:calling"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

// Phase 16 hardware-day runner: `./gradlew :core:engine:interopHarness --args="<verb> …"`
// runs DesktopInteropHarness.main with the jvmTest classpath (which is the only place the
// harness lives — never shipped). Typical invocations, per the gate script in the log:
//   interopHarness --args="discover 30"
//   interopHarness --args="send <phone-ip> <port> <file>"
//   interopHarness --args="receive flash-received 120"
//   interopHarness --args="cancel <phone-ip> <port> <file>"
// `-Pargs` alternative is deliberately NOT provided: --args quoting is the documented Gradle
// form and survives spaces in paths.
val interopHarness by tasks.registering(JavaExec::class) {
    group = "interop"
    description = "Runs the Phase 16 desktop interop harness (jvmTest classpath; never published)."
    mainClass.set("com.transfer.flash.core.engine.interop.DesktopInteropHarnessKt")
    // Reuse the jvmTest task's own resolved classpath: it already wires the test compilation's
    // output + runtime + dependency files correctly for this Kotlin/Gradle pair.
    classpath = tasks.named<Test>("jvmTest").get().classpath
    // Interactive verbs print/loop; wire stdin so a Ctrl-C in the terminal reaches the harness.
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

publishing {
    publications {
        // KMP generates the publications itself (root `kotlinMultiplatform`, plus one per
        // target), so a module must NOT `register<MavenPublication>("release")` any more.
        // Their default artifactIds derive from the project name (`engine`, `engine-android`,
        // `engine-jvm`); rename in place to keep the published coordinates that 1.1.0
        // consumers already use. Version and group still come from the root build file —
        // never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("engine", "core-engine")
        }
    }
}
