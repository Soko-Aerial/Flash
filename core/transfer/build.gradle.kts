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

    // Phase 13B-1: this module now declares `expect class PlatformLock`. expect/actual CLASSES are
    // still Beta (KT-61573) and warn once per declaration site without this flag; CONVENTIONS.md R2
    // permits a class here because the seam carries per-platform state (a monitor), which an
    // `expect fun` cannot. FOURTH copy of the lock — :core:common (06), :core:discovery (08),
    // :core:engine (12), here (13B-1). Phase 11's note that this module "declares no expect/actual
    // at all" was true when written; RollingRateMeter's three @Synchronized annotations are what
    // changed it. See docs/migration/PHASE-13B-desktop-fileio.md §13B-1.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.transfer.flash.core.transfer"
        compileSdk = 35

        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Rules are
        // dropped in SILENCE if this is omitted; core/transfer's file is comment-only today,
        // but the block preserves the pre-KMP publishing behaviour exactly.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`: the target publishes a single variant, so
        // instead of declaring build types it states which one local project deps resolve from.
        // Load-bearing for the `:core:network` edge below, which is still variant-ful.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task (the KMP replacement for
        // `testDebugUnitTest`). Omit it and every test file in this module stops compiling AND
        // running while the build still reports SUCCESS. That task now runs 18 suites: the 5 left
        // in androidHostTest plus all 13 in commonTest, which the Android target also executes.
        withHostTest { }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // `compileKotlinJvm` cannot see android.jar, which is what proves the commonMain files are
    // Android-free.
    // 13B-2 (D10 = Option A) landed the first *file I/O* on this target: the four re-typed seams
    // (ChunkSource, ChunkSink, FileSourceOpener, RandomAccessSinkHandle) plus a working
    // OkioRandomAccessSinkHandle are all commonMain, so desktop can already open a destination
    // file and write chunks at arbitrary offsets. 13B-3 then added hashing (Sha256/IncrementalSha256
    // over okio's HashingSink, 13B-3a), the FLSH v2 CHUNK framing (ChunkFrame, 13B-3b), resume
    // (ResumeBitVector on a LongArray, 13B-3c), the concurrency primitives (13B-3d) and finally, in
    // 13B-3e, the pipelines themselves: Chunker/ChunkStream, SendPipeline, ReceivePipeline,
    // MultiStreamReceiver, MultiStreamDispatcher and RealFlashTransferRepository. A desktop host can
    // therefore now chunk, hash, frame, send, receive, verify and resume a file end-to-end in common
    // code — PipelineEndToEndTest asserts exactly that on this target.
    // Only three files are still androidMain: the PlatformLock `actual`, model/WsTransferModels.kt
    // (pinned by the `:core:network` edge below) and policy/DestinationPolicy.kt (SAF/MediaStore).
    // jvmMain still holds exactly one file, the PlatformLock `actual`; no okio seam needed a second.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // Public API returns kotlinx.coroutines Flow/StateFlow (e.g. activeTransfers), so
            // coroutines must be `api` (implementation would keep those return types off a
            // consumer's classpath).
            api(libs.kotlinx.coroutines.core)
            // Phase 13B-2 (D10 = Option A). `api`, not `implementation`: okio.Source is the return
            // type of ChunkSource.open() and FileSourceOpener.open(), and okio.Path/FileSystem are
            // OkioRandomAccessSinkHandle's constructor parameters — all public under explicitApi()
            // (R7), so a consumer cannot compile against this module without okio on its own
            // classpath. Resolution-neutral: `:app` already resolves okio 3.4.0 transitively via
            // androidx.datastore, and this declares that same version (R10). Chosen over
            // kotlinx-io because only okio has FileHandle/positional writes, which
            // RandomAccessSinkHandle.writeAt() requires — see the catalog comment.
            api(libs.okio)
        }
        androidMain.dependencies {
            // Phase 02 (migration): `:core:security` and `:core:discovery` were removed here when
            // `wslegacy/` was deleted — WsPairingStore was the only consumer of FlashTrustStore
            // and WsDiscovery the only consumer of the discovery module. Recorded because a reader
            // will otherwise re-add them.
            //
            // `:core:network` is a LIVE edge, not a dead one: model/WsTransferModels.kt reads
            // WsTransferServer.PREFERRED_PORT, and WsTransferServer is androidMain in
            // :core:network as of Phase 10 — which is exactly why WsTransferModels.kt is
            // androidMain here.
            implementation(project(":core:network"))
            // Phase 4 (ADR-024): transfer does not depend on core:persistence. Storage is behind
            // the transfer-owned TransferStore port; core:engine's RoomTransferStore adapts Room
            // to it. This keeps Room/SQLCipher (4 native ABIs) off a lightweight consumer.
            //
            // TODO(cleanup): both androidx entries are dead — grep finds zero androidx references
            // in this module's main and test sources. Parked here rather than deleted so
            // core-transfer-android's POM keeps the two runtime-scope entries 1.1.0 consumers
            // resolve today; deleting them is a consumer-visible resolution change that should be
            // made repo-wide at once (Phase 10 precedent).
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        // Runs on BOTH the Android host-test JVM and the desktop jvm() target, so the FLSH v2 wire
        // formats, the rate meter's PlatformLock (13B-1), Sha256's known-answer vectors (13B-3a),
        // ChunkFrame's golden vectors (13B-3b) and — since 13B-3e — the whole send/receive round
        // trip are executed on each rather than merely compiled (CONVENTIONS.md R3.1). Without it,
        // jvmTest would run zero tests and the desktop target would be compiled but unproven.
        commonTest.dependencies {
            implementation(kotlin("test"))
            // 13B-1: needed by RollingRateMeterTest's contention case — `runTest` is the only way
            // to launch coroutines from a non-suspend test function in common code. Existing
            // catalog alias, pinned at the same 1.10.2 as coroutines-core, so no version moves
            // (R10). Same edge :core:engine added in Phase 12 for AutoConnectGateTest. 13B-3e made
            // it load-bearing for three more suites: SendPipelineTest and PipelineEndToEndTest
            // traded `runBlocking` (JVM/native-only) for `runTest`, and MultiStreamReceiverTest
            // moved alongside them.
            implementation(libs.kotlinx.coroutines.test)
        }
        // The 5 suites still here are JUnit 4 and use java.io, java.util.concurrent or
        // TemporaryFolder, so they stay on the Android host-test tier, byte-for-byte unchanged.
        // There were 13 when Phase 11 converted this module; the count fell as each pin cleared —
        // RollingRateMeterTest and TransferManifestTest in 13B-1, Sha256Test in 13B-3a,
        // ResumeBitVectorTest in 13B-3c, TransferCompletionStateMachineTest in 13B-3d, and five at
        // once in 13B-3e (ChunkFrameTest, ChunkerTest, SendPipelineTest, ReceivePipelineTest,
        // PipelineEndToEndTest) plus MultiStreamReceiverTest. The two largest holdouts —
        // MultiStreamDispatcherTest and RealFlashTransferRepositoryTest — cover code that is now
        // commonMain, so those two files have no desktop coverage; logged as a known gap in 13B-3e.
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
        // Their default artifactIds derive from the project name (`transfer`, `transfer-android`,
        // `transfer-jvm`); rename in place to keep the published coordinates that 1.1.0
        // consumers already use. Version and group still come from the root build file —
        // never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("transfer", "core-transfer")
        }
    }
}
