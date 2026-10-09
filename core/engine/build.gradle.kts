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
        // The seventh, `:core:ptt`, is deliberately NOT here. It was a plain AGP Android library with no
        // JVM variant (ERROR-049); it became KMP in ADR-058, so the placement is now a choice, kept
        // because nothing in commonMain names PTT and moving it is a public-API/publication change (ENG-1).
        // See androidMain below.
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:security"))
            api(project(":core:discovery"))
            api(project(":core:network"))
            api(project(":core:transfer"))
            api(project(":core:messaging"))
            api(project(":core:persistence"))
            api(project(":core:swarm"))
            // ADR-058 / ENG-1: :core:ptt is KMP and provides FlashPtt for the unified engine facade.
            api(project(":core:ptt"))
            // compileOnly keeps FlashCalling on the facade compile classpath without transitively
            // pulling webrtc-kmp into consumers that never place a call (ADR-033).
            compileOnly(project(":core:calling"))
        }
        androidMain.dependencies {

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
    // First real-JmDNS run on this host (unit tests use a fake bridge) failed both binds with
    // "Invalid argument: setsockopt" — the classic Windows/JDK multicast failure when the IPv6
    // stack is preferred and IP_MULTICAST_IF cannot be set. Force the IPv4 stack, which is also
    // what mDNS interop with Android NSD expects (the bridge picks Inet4Address candidates only).
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    // UTF-8 stdout. Since JDK 18 `stdout.encoding` follows the CONSOLE's encoding, so on a Windows
    // console in cp437/cp850 every "—" and "…" in the harness's output renders as mojibake
    // (`paired ∩┐╜`), which is exactly the output a human reads during a hardware run. The strings
    // themselves are shared with the Android UI, where they are fine, so the fix belongs here and
    // not in the messages. `sun.stdout.encoding` is kept for JDK 17 and earlier.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    // Reuse the jvmTest task's own resolved classpath: it already wires the test compilation's
    // output + runtime + dependency files correctly for this Kotlin/Gradle pair.
    classpath = tasks.named<Test>("jvmTest").get().classpath
    // Interactive verbs print/loop; wire stdin so a Ctrl-C in the terminal reaches the harness.
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

// PC0 peer farm (docs/network/PRESENCE-CONNECTIONS-PLAN.md): N headless peers in one JVM for a phone
// to hold idle sessions with. Same classpath and JVM flags as the harness, for the same reasons.
//   peerFarm --args="--count=4 --minutes=60"      (--help lists every option)
val peerFarm by tasks.registering(JavaExec::class) {
    group = "interop"
    description = "Runs the PC0 peer farm (jvmTest classpath; never published)."
    mainClass.set("com.transfer.flash.core.engine.interop.farm.PeerFarmKt")
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    classpath = tasks.named<Test>("jvmTest").get().classpath
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
