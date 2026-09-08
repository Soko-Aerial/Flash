# Phase 11 — KMP conversion: `core:transfer` + `core:messaging`

> ## REWRITTEN 2026-09-05 for D1 = B, and re-measured
>
> The original said so on its own line 4: **"D1 must be `A`."** D1 is **B** (CONVENTIONS.md
> amendment 2026-09-03), so `jvmAndAndroidMain` — the tier the old file routed 14 of
> `:core:transfer`'s files into — does not exist and must not be created. Phases 07, 08 and 10
> each rewrote their own file for the same reason; this is that rewrite.
>
> Re-measuring found the inventory had drifted and **six** of the old file's factual claims were
> wrong. They are listed under *Six claims measurement disproved* rather than quietly corrected,
> because four of them change what this phase does — including one that the old file called out
> as a judgment call requiring an ADR, and which D1 = B dissolves entirely.
>
> **Blocked by:** Phases 06, 07, 08, 10. **NOT blocked by Phase 09** — see the next section.
> **Risk: MEDIUM.** 26 files move; two lines of content change.

## The Phase 09 exception — read this before the preconditions

The old file's precondition 1 demanded Phase 09 (persistence → KMP) be complete, because
`:core:messaging` depends on `:core:persistence` and `RealFlashChatRepository` consumes 16 Room
types (7 DAOs, 7 entities, 2 DAO projections). **Phase 09 is blocked** — D5 is the human's decision, see
`PHASE-09B-persistence-room-kmp.md` — so `:core:persistence` is still `com.android.library`
today.

That does not block Phase 11, for the same reason that makes the dependency safe:
`RealFlashChatRepository` is an **`androidMain`** file, so `:core:persistence` is an
**`androidMain` dependency**, and the KMP Android target resolves a variant-ful Android library
through `localDependencySelection { selectBuildTypeFrom.set(listOf("release")) }` exactly as
`:app` resolves one. Nothing in `commonMain` and nothing on the `jvm()` target ever sees Room.
Gate 1 is the proof: a green `compileKotlinJvm` means no Room type reached the desktop
classpath, because the desktop target has no `:core:persistence` edge at all.

So this is a **precondition the old file got wrong**, not a precondition being skipped. Phase 12
(engine) will not get off this lightly: `:core:engine`'s `RoomTransferStore` is an *adapter*
whose whole job is to touch Room, and it needs the same androidMain treatment.

## What this phase is actually for

Convert both modules to `org.jetbrains.kotlin.multiplatform` +
`com.android.kotlin.multiplatform.library` with an `android { }` target and a desktop `jvm()`
target, and place their production files across `commonMain` and `androidMain`. When done:

- `:core:transfer:compileKotlinJvm` is green, proving the **shared transfer contract** builds
  off Android: `FlashTransferRepository`, the `FlashTransfer`/`FlashTransferId` model, the
  `TransferStore` port (ADR-024), the `StreamChannel` interface, and the **FLSH v2 control-frame
  wire format** (`WsTransferMessages`).
- `:core:messaging:compileKotlinJvm` is green, proving the **shared messaging contract** builds
  off Android: `FlashChatRepository` + its sample implementation, the whole messaging UI model
  set (20 types — what Phases 17–20 render), the grouping/preview helpers, and
  `MessageWireFrame`.
- Both Android host suites are unregressed at their measured baselines, and both modules still
  publish `core-transfer` / `core-messaging` at the root coordinate.

**What this phase does NOT do:** it does not give desktop a runnable transfer or chat. The
chunking/hashing/resume machinery, the multi-stream engine, the destination policies and both
concrete repositories stay Android-only. That is Phase 15's work, and R2 step 1 is why — see
*Why 5 of 20*.

## Six claims measurement disproved (2026-09-05)

**1. `:core:messaging` is 6 production / 4 test files, not 5 / 3.** `PresenceHold.kt` (95 lines)
and `PresenceHoldTest.kt` (3 tests) arrived with the ERROR-031 presence work after the old file
was written. `PresenceHold` is the one file in messaging whose placement the old file could not
have reasoned about, and it does not go where the old file's pattern would have put it — see
*Why `PresenceHold` is androidMain*.

**2. messaging's test baseline is 27 `@Test` in 4 classes, not 16 in 3.** Measured:
`FlashMessageContentSummaryTest 5`, `FlashMessageGroupingTest 5`, `PresenceHoldTest 3`,
`RealFlashChatRepositoryTest 14`. Gate 4 asserts 27, not 16.

**3. `FlashChatRepository.kt` is NOT commonMain-clean.** The old file lists it as commonMain
row 1 with no edit. It has two `System.currentTimeMillis()` calls — lines 147 and 215, both
inside `SampleFlashChatRepository` — which is a CONVENTIONS R6 stdlib trap that no import line
reveals. Following the old file verbatim would have produced a red `compileKotlinJvm`… **no**:
it would have produced a *green* one, because R6.1 says nothing in the build enforces R6 while
both targets are JVM. It would have shipped a `java.*`-dependent `commonMain`, undetected until
a Kotlin/Native target existed. This is exactly the leak R6.1 was written for.

**4. transfer's `:core:security` and `:core:discovery` dependencies do not exist.** The old file
instructs relocating them into `androidMain`. Phase 02 already deleted them and left a comment
in the build file recording it. Only the androidx pair (`core-ktx`, `lifecycle-runtime-ktx`) is
dead in transfer.

**5. transfer's `:core:network` edge is LIVE, not dead.** The old file lists it for relocation as
unused. `core/transfer/build.gradle.kts` says, in its own words: *"`:core:network` stays: it is
still used by model/WsTransferModels.kt, which references WsTransferServer."* Confirmed by grep:
`WsTransferModels.kt` reads `WsTransferServer.PREFERRED_PORT`. It must stay, and it becomes an
**`androidMain`** dependency, because `WsTransferServer` is `androidMain` in `:core:network` as
of Phase 10.

**6. `WsTransferModels.kt`'s "no legal source-set home" was a D1 = A artifact.** The old file
concludes this file cannot be placed, recommends deleting it, and demands an ADR for the
deletion. That conclusion follows only from D1 = A: under A the file's own content is
common-clean, so leaving it out of the shared tier looked arbitrary, while putting it in meant
dragging an Android-only `WsTransferServer` reference into shared code. Under **D1 = B**,
`androidMain` is simply the correct home — its one cross-module reference is androidMain, so it
lands next to it. **No deletion, no ADR.** Removing dead code is a cleanup-phase job (R1).

### One more finding, recorded not acted on

`manifest/TransferManifest.kt` (42 lines) has **zero consumers anywhere in the repo** — no
production reference, no test, not in `:app`, `:core:engine` or `:ui:chat`. It is dead code, not
merely orphaned. It moves to `androidMain` with everything else (it calls
`System.currentTimeMillis()` at line 29). Deleting it is out of scope; it goes under **Known
issues** in the log entry.

## Preconditions

1. Phases 06, 07, 08, 10 committed. Both modules consume `FlashSession`, which reached
   `commonMain` in Phase 10 (`428154d`) — that was the actual blocker.
2. **Phase 09 is NOT a precondition.** See *The Phase 09 exception* above.
3. Working tree clean. `git status --short` empty before starting.
4. Every Gradle command in this file runs with the recorded environment and
   `--no-configuration-cache` (R3):

```bash
export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix'
```

## Measured baselines — capture these before touching anything

```bash
./gradlew :core:transfer:testDebugUnitTest :core:messaging:testDebugUnitTest --no-configuration-cache --console=plain
```

Measured 2026-09-05, `BUILD SUCCESSFUL in 55s`:

| Module | `@Test` | classes | failures |
|---|---|---|---|
| `:core:transfer` | **86** | 13 | 0 |
| `:core:messaging` | **27** | 4 | 0 |

Per class — transfer: `RealFlashTransferRepositoryTest 8`, `chunked.ChunkFrameTest 8`,
`chunked.ChunkerTest 10`, `chunked.PipelineEndToEndTest 2`, `chunked.ReceivePipelineTest 15`,
`chunked.ResumeBitVectorTest 6`, `chunked.SendPipelineTest 6`, `chunked.Sha256Test 5`,
`model.FlashTransferModelTest 2`, `multistream.MultiStreamDispatcherTest 13`,
`multistream.MultiStreamReceiverTest 3`, `policy.DestinationPolicyTest 2`,
`protocol.WsTransferMessagesTest 6`. Messaging: see disproved claim 2.

Baseline POM dependency sets (`publishToMavenLocal`, both at `1.1.0`):

```
core-transfer:  compile{core-common, kotlinx-coroutines-core, kotlin-stdlib}
                runtime{core-network, core-ktx 1.10.1, lifecycle-runtime-ktx 2.6.1}
core-messaging: compile{core-common, kotlinx-coroutines-core, kotlin-stdlib}
                runtime{core-security, core-network, core-persistence, core-ktx,
                        lifecycle-runtime-ktx}
```

## `:core:transfer` — 20 production files, 5 commonMain / 15 androidMain

### commonMain (5)

| File | Lines | Why it is clean |
|---|---|---|
| `FlashTransferRepository.kt` | 106 | Interface + `FlashTransferProgress`. coroutines `Flow` only. |
| `model/FlashTransfer.kt` | 51 | `@JvmInline value class FlashTransferId` + data class + enum. `@JvmInline` is available in `commonMain` — `:core:common`'s `FlashDeviceId` already proves it. |
| `protocol/WsTransferMessages.kt` | 116 | **R8 wire format.** Pure string framing via `:core:common`'s `FlashTextFraming`. Zero `java.*`. Moves byte-identical. |
| `store/TransferStore.kt` | 36 | The ADR-024 port. Suspend functions over model types. |
| `multistream/StreamChannel.kt` | 48 | Interface + `StreamId` value class. No implementation. |

### androidMain (15)

| File | Lines | Blocking reason (R2 step 1) |
|---|---|---|
| `RealFlashTransferRepository.kt` | 841 | `java.io.InputStream`, `java.util.Collections.newSetFromMap`, `java.util.UUID`, `ConcurrentHashMap` |
| `chunked/ChunkFrame.kt` | 466 | **R8 wire.** `ByteArrayOutputStream`, `ByteBuffer`, `ByteOrder`, `Charsets` |
| `chunked/Chunker.kt` | 306 | `Closeable`, `IOException`, `InputStream` |
| `chunked/ReceivePipeline.kt` | 421 | `@Synchronized` |
| `chunked/ResumeBitVector.kt` | 160 | `java.util.BitSet` |
| `chunked/SendPipeline.kt` | 216 | No trap of its own — pulled down by importing `Chunker`, `ChunkFrame`, `Sha256`, `FileMeta` |
| `chunked/Sha256.kt` | 108 | `java.security.MessageDigest`, `Charsets` |
| `manifest/TransferManifest.kt` | 42 | `System.currentTimeMillis()` (line 29). Also dead code — see above. |
| `model/WsTransferModels.kt` | 51 | References `WsTransferServer` (androidMain in `:core:network`) |
| `multistream/MultiStreamDispatcher.kt` | 675 | `AtomicBoolean/Integer/Long`, `Collections.synchronizedList`, `@Volatile`, `synchronized`, `System.currentTimeMillis` (line 67) |
| `multistream/MultiStreamProgress.kt` | 131 | `@Synchronized` |
| `multistream/MultiStreamReceiver.kt` | 109 | `synchronized(lock)` |
| `multistream/TransferCompletionStateMachine.kt` | 147 | `AtomicBoolean`, `synchronized` |
| `policy/DestinationPolicy.kt` | 133 | `java.io.File`, `OutputStream`, `RandomAccessFile`, `Closeable`, `@Volatile`, `@Synchronized` |
| `policy/RandomAccessChunkSink.kt` | 24 | Clean itself; references `ChunkSink` (in `ReceivePipeline.kt`) and `RandomAccessSinkHandle` (in `DestinationPolicy.kt`), both androidMain |

**Zero content edits in `:core:transfer`.** Note the consequence for R8: because `ChunkFrame.kt`
and `Sha256.kt` are androidMain anyway, their `Charsets` usage needs no replacement, so both wire
files move with not a byte changed. Do not "improve" them to `encodeToByteArray()` — that is a
Phase 15 concern and it is a wire-format edit (R8).

### Why 5 of 20

That ratio looks bad until you name what the 5 are. They are the module's **entire published
contract**: the repository interface, the model, the ADR-024 port, the stream abstraction, and the
control-frame wire format. The 15 are one 841-line concrete repository plus the chunking, hashing,
resume, multi-stream and destination-policy machinery — every one of which is a file whose job is
to touch bytes on a disk or a socket. R2 step 1 says leave those where they are; R2 step 3
(`expect`/`actual`) would mean writing a second implementation of each, which is Phase 15's brief,
not this one. Phase 15 gets `commonMain` interfaces to implement against precisely because this
phase moves the 5.

The two files worth arguing about, and the answers:

- **`ChunkFrame.kt` (466 lines, R8 wire).** Tempting to move: it is a codec, and Phase 10 found
  `WebSocketCodec` in the same position. Answer: no. It is `ByteBuffer`/`ByteOrder` throughout,
  and rewriting binary framing by hand in common Kotlin is a wire-format change under R8 even if
  the output happens to match. `WsTransferMessages` is the codec that moves, because it is
  *text* framing that was already `java.*`-free.
- **`ResumeBitVector.kt` (160 lines).** `java.util.BitSet` has no common equivalent, and a
  hand-rolled `LongArray` replacement would change resume behaviour at the edges. Leave it.

## `:core:messaging` — 6 production files, 4 commonMain / 2 androidMain

### commonMain (4)

| File | Lines | Note |
|---|---|---|
| `FlashChatRepository.kt` | 276 | **Needs the one edit** — see below. Interface (lines 20–114) is already clean; the two traps are in `SampleFlashChatRepository`. |
| `model/FlashMessagingModels.kt` | 308 | 20 types. `@JvmInline` value classes only, no `java.*`. This is what Phases 17–20 render. |
| `protocol/MessageWireFrame.kt` | 73 | **R8 wire.** Clean already — moves byte-identical. |
| `util/FlashMessagingUtils.kt` | 269 | Grouping + preview helpers. Clean. |

### androidMain (2)

| File | Lines | Blocking reason |
|---|---|---|
| `PresenceHold.kt` | 95 | `System.currentTimeMillis()` ×2 **and** `HashMap.putIfAbsent` |
| `RealFlashChatRepository.kt` | 1306 | `SimpleDateFormat`, `Date`, `Locale`, `UUID`, `ConcurrentHashMap`, plus 16 Room types from `:core:persistence` (7 DAOs, 7 entities, 2 DAO projections) |

### Why `PresenceHold` is androidMain and not an edit

It has the same `System.currentTimeMillis()` trap as `FlashChatRepository.kt`, and this file gets
moved while that one gets edited. The difference is `MutableMap.putIfAbsent`, which is a JVM
default method with no `commonMain` equivalent — so an edit could not stop at the clock and would
have to restructure the map logic. Weigh that against what moving costs: `PresenceHold` is
`internal`, and its only consumers are `RealFlashChatRepository` and `PresenceHoldTest`, both of
which are androidMain-tier already. Moving it loses nothing and touches no bytes. R2 step 1.

### The one content edit

`core/messaging/src/commonMain/kotlin/.../FlashChatRepository.kt`, two lines, both inside
`SampleFlashChatRepository` (the preview/sample implementation — **not** the interface):

```kotlin
// line 147, in sendText:
id = "local-${System.currentTimeMillis()}",
// line 215, in updateListPreview:
sortOrder = System.currentTimeMillis(),
```

Replace both with `SystemTimeSource.nowMs()` from `:core:common`, adding one import:

```kotlin
import com.transfer.flash.core.common.time.SystemTimeSource
```

Unlike Phase 10's `toByteArray(Charsets.UTF_8)` → `encodeToByteArray()` change — which has one
divergent input class (unpaired surrogates) — **this one is a true identity on both current
targets.** Both actuals are literally the call being replaced:

```kotlin
// core/common/src/androidMain/.../time/PlatformTime.android.kt
internal actual fun currentTimeMillisPlatform(): Long = System.currentTimeMillis()
// core/common/src/jvmMain/.../time/PlatformTime.jvm.kt
internal actual fun currentTimeMillisPlatform(): Long = System.currentTimeMillis()
```

`currentTimeMillisPlatform()` is `internal expect`, so it is not callable across modules;
`SystemTimeSource.nowMs()` is the public route and `:core:common` is already an `api` dependency.

## Test placement — all 17 existing files go to `androidHostTest`, unchanged

Every existing suite in both modules is **JUnit 4** (`org.junit.Test`, `org.junit.Assert.*`,
`@Rule TemporaryFolder`, `@Ignore`) and most use `java.*` directly: `ByteArrayInputStream`,
`File`, `Collections`, `CompletableFuture`, `Executors`, `TimeUnit`, `AtomicBoolean/Integer`,
`ConcurrentHashMap`, plus `runBlocking`. There is **no `kotlinx-coroutines-test`, no Robolectric
and no Room** in either module's tests.

They all move to `androidHostTest` with zero content changes. The only test dependency either
module needs there is `libs.junit`, exactly as today.

## Two new `commonTest` suites — required, not optional

CONVENTIONS R3.1: *"Any phase that writes an `actual` should put at least one behavioural
assertion in `commonTest` so both platforms run it."* Neither module writes an `actual`, but the
same reasoning applies to a converted module with an empty `commonTest`: `jvmTest` running zero
tests means the desktop target is **compiled but unproven**. Phase 10 set the precedent with
`FlashSessionSendTextTest`.

Both suites are **additive**. Neither modifies an existing test, and neither touches an R8 file.
Use underscored test names, not backticks: backticked names are illegal on Kotlin/Native, and
`commonTest` is the one tier a future native target will compile.

### `core/transfer/src/commonTest/kotlin/.../protocol/WsTransferMessagesWireFormatTest.kt`

The existing `androidHostTest` suite round-trips the four frames (encode → parse → compare). The
common suite does something the round-trip cannot: it pins the **literal wire text**, so a change
to `FlashTextFraming`'s escaping that happens to be symmetric — and therefore invisible to a
round-trip test — fails the build on both targets. Derived from `FlashTextFraming.escape`
(`%`→`%25`, ` `→`%20`, `=`→`%3D`, applied in that order):

```
FLASH_WS_HELLO version=1 deviceId=device-1234 name=Kali%20Phone%20%3D%20Pro
FLASH_FILE_START version=1 transferId=ab12cd34 name=my%20100%25%20file%20(final).zip size=987654321
FLASH_FILE_END version=1 transferId=ab12cd34 bytes=42
FLASH_FILE_ACK version=1 transferId=ab12cd34 received=42 ok=true
```

8 tests: the four literals, `PROTOCOL_VERSION == 1`, a CJK+emoji friendly name round-trip (the
case where a per-target string difference would actually show), a value containing a literal
`%20` (which only survives because `unescape` undoes `%25` **last**), and cross-prefix rejection.

`WsTransferMessages` is `internal`, which a `commonTest` in the same module can see — test source
sets are associated with the main compilation. The file needs
`@file:OptIn(FlashInternalApi::class)` for `FlashTextFraming`, same as the existing suite.

### `core/messaging/src/commonTest/kotlin/.../SampleFlashChatRepositoryTest.kt`

Pins the file this phase **edits**. 8 tests over `SampleFlashChatRepository`: `sendText` appends a
mine-message and assigns `local-<millis>`; the id's numeric suffix parses and is `>=` a clock
reading taken before the call (this is the assertion that proves `SystemTimeSource.nowMs()`
behaves like the `System.currentTimeMillis()` it replaced, **on both targets**); the list
preview's `sortOrder` is likewise refreshed from the shared clock; blank text is ignored; a
`sendText` with no open conversation is a no-op; an unknown `openConversation` id is ignored;
`archiveConversation` drops the item; `toggleListSelection` adds then removes.

Find list items by `id`, never by index — `sortedChatListItems` reorders on every preview update.
Valid sample ids: `conv-false-school` (pinned), `conv-alex`, `conv-design`, `conv-transfer`,
`conv-offline`. `sendText` is **not** a suspend function, so this suite needs no
`kotlinx-coroutines-test`; `kotlin("test")` alone is enough.

### Expected test arithmetic

| Task | Before | After |
|---|---|---|
| `:core:transfer:testAndroidHostTest` | 86 | **94** (86 + 8 common) |
| `:core:transfer:jvmTest` | — | **8** |
| `:core:messaging:testAndroidHostTest` | 27 | **35** (27 + 8 common) |
| `:core:messaging:jvmTest` | — | **8** |
| **Repo total** | 913 | **945** (+32 = 8 × 2 targets × 2 modules) |

## `core/transfer/build.gradle.kts` — replace the file with this

The DSL block inside `kotlin { }` is **`android { }`**, not `androidLibrary { }`. The old file's
examples used the latter; it does not resolve under AGP 9.3.1. Everything here mirrors
`core/network/build.gradle.kts` as committed in Phase 10.

```kotlin
plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under
    // AGP 9+. A converted module swaps it for these two rather than adding to it.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // Phase 3 Task 3.2, unchanged by the conversion: strict explicit-API mode.
    explicitApi()

    // NOTE: deliberately NO `-Xexpect-actual-classes`. This module declares no expect/actual.

    android {
        namespace = "com.transfer.flash.core.transfer"
        compileSdk = 35
        minSdk = 24                       // NOT inside defaultConfig — the target is variant-free

        optimization {                    // was defaultConfig { consumerProguardFiles(...) }
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        localDependencySelection {        // was buildTypes { release { … } }
            selectBuildTypeFrom.set(listOf("release"))
        }

        compilerOptions {                 // was compileOptions { source/targetCompatibility }
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        withHostTest { }                  // creates androidHostTest + testAndroidHostTest
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI. Plain `jvm()`, never `jvm("desktop")` — R5. Carries the transfer
    // CONTRACT only; every byte-moving implementation stays in androidMain until Phase 15.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
```

```kotlin
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // FlashTransferRepository.activeTransfers is a public Flow, so coroutines stays `api`.
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // LIVE edge, not dead: model/WsTransferModels.kt reads WsTransferServer.PREFERRED_PORT,
            // and WsTransferServer is androidMain in :core:network as of Phase 10. The build file's
            // pre-existing comment about Phase 02 removing :core:security and :core:discovery is
            // preserved below because it documents an absence a reader will otherwise re-add.
            implementation(project(":core:network"))
            // TODO(cleanup): both are dead — grep finds zero androidx references in this module.
            // Parked here rather than deleted so core-transfer-android's POM keeps the two
            // runtime-scope entries that 1.1.0 consumers resolve today (Phase 10 precedent).
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        // Runs on BOTH the Android host-test JVM and the desktop jvm() target, so the FLSH v2
        // control-frame format is executed on each rather than merely compiled (R3.1).
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The 13 pre-existing suites are JUnit 4 and use java.io/java.util.concurrent and
        // TemporaryFolder, so they stay on the Android host-test tier, byte-for-byte unchanged.
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
        // KMP generates the publications itself (root `kotlinMultiplatform` + one per target), so
        // a converted module must NOT register<MavenPublication>("release") any more. Default
        // artifactIds derive from the project name (`transfer`, `transfer-android`,
        // `transfer-jvm`); rename in place to keep the coordinates 1.1.0 consumers already use.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("transfer", "core-transfer")
        }
    }
}
```

Two notes on what is deliberately absent. There is no `dependencies { }` block at the top level
any more — KMP puts them in `sourceSets`. And `:core:persistence` is still absent from transfer:
Phase 04's ADR-024 inversion put storage behind the `TransferStore` port, and this phase does not
reopen that.

## `core/messaging/build.gradle.kts` — same shape, different dependency split

Identical to the transfer file above except for `namespace`, the `artifactId` rename token, and
the source-set dependencies:

```kotlin
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // chatListState / conversationState are public StateFlows — coroutines stays `api`.
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // RealFlashChatRepository consumes 16 Room types — 7 DAOs, 7 entities and the 2 DAO
            // projections ConversationPreview/ConversationUnread. This is the module's one
            // genuinely load-bearing Android edge, and it is why that 1306-line file is
            // androidMain. :core:persistence is still `com.android.library` (Phase 09 blocked),
            // so this is a KMP androidMain target consuming a variant-ful Android library — it
            // resolves through the `localDependencySelection { selectBuildTypeFrom }` above,
            // the same mechanism :app uses. Gate 1 proves none of it reaches the desktop target.
            implementation(project(":core:persistence"))
            // TODO(cleanup): :core:security, :core:network and both androidx entries are dead —
            // grep finds zero references to any of them in main or test. Parked, not deleted, so
            // core-messaging-android's POM keeps the runtime-scope entries 1.1.0 consumers
            // resolve today (Phase 10 precedent, which logged the same for :core:network).
            implementation(project(":core:security"))
            implementation(project(":core:network"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The 4 pre-existing suites are JUnit 4; RealFlashChatRepositoryTest and PresenceHoldTest
        // exercise androidMain types. Unchanged.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
```

and

```kotlin
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("messaging", "core-messaging")
        }
```

`:core:security` is dead in messaging as well — that is measured, not assumed (`grep -rnE
'com\.transfer\.flash\.core\.(security|network)' core/messaging/src` returns nothing). All four
dead edges are parked in `androidMain` together, which is also the only tier where a dead
`com.android.library` edge can legally sit now.

## Procedure

R4 normally forbids editing two modules' build files in one commit. **This phase is the
exception it names** — it converts two modules, and both build files are rewritten in the same
commit. Keep it to those two modules and nothing else.

### Step 1 — create the directories

```bash
cd core/transfer/src && mkdir -p commonMain/kotlin/com/transfer/flash/core/transfer/{model,multistream,protocol,store} \
  androidMain/kotlin/com/transfer/flash/core/transfer/{chunked,manifest,model,multistream,policy} \
  commonTest/kotlin/com/transfer/flash/core/transfer/protocol \
  androidHostTest/kotlin/com/transfer/flash/core/transfer/{chunked,model,multistream,policy,protocol} && cd -
cd core/messaging/src && mkdir -p commonMain/kotlin/com/transfer/flash/core/messaging/{model,protocol,util} \
  androidMain/kotlin/com/transfer/flash/core/messaging \
  commonTest/kotlin/com/transfer/flash/core/messaging \
  androidHostTest/kotlin/com/transfer/flash/core/messaging && cd -
```

`kotlin/`, never `java/` (R5). No `jvmMain` directory is created for either module: both are
empty by design, and Gradle does not require the directory to exist.

### Step 2 — `git mv` every file

Use `git mv` so rename detection keeps the history; a delete-plus-add makes the diff unreadable
and hides whether content changed. 43 files: 20 + 13 in transfer, 6 + 4 in messaging.

Transfer commonMain (5): `FlashTransferRepository.kt`, `model/FlashTransfer.kt`,
`protocol/WsTransferMessages.kt`, `store/TransferStore.kt`, `multistream/StreamChannel.kt`.
Transfer androidMain (15): everything else under `src/main/java/`.
Transfer androidHostTest (13): everything under `src/test/java/`.
Messaging commonMain (4): `FlashChatRepository.kt`, `model/FlashMessagingModels.kt`,
`protocol/MessageWireFrame.kt`, `util/FlashMessagingUtils.kt`.
Messaging androidMain (2): `PresenceHold.kt`, `RealFlashChatRepository.kt`.
Messaging androidHostTest (4): all four test files.

Afterwards `src/main/java` and `src/test/java` must be **gone** in both modules:

```bash
find core/transfer/src core/messaging/src -type d -name java
```

Expected output: nothing. A leftover `src/main/java` is not harmless — the KMP Android target
does not read it, so any file left behind silently stops compiling while the build stays green.

### Step 3 — rewrite both build files, then make the one content edit

Both build files per the sections above. Then the two `System.currentTimeMillis()` lines in
`commonMain/.../FlashChatRepository.kt`, plus the `SystemTimeSource` import. Nothing else.

### Step 4 — add the two `commonTest` suites

Per *Two new `commonTest` suites* above.

## Gates — all nine must pass, and the log entry must paste each one's output (R9)

**Gate 1 — desktop compile, the R2 proof (both modules).**

```bash
./gradlew :core:transfer:compileKotlinJvm :core:messaging:compileKotlinJvm --no-configuration-cache --console=plain
```

Green means no `android.*` and no Room reached the shared tier. Per R6.1 it certifies nothing
about `java.*` — that is Gate 7's job.

**Gate 2 — Android compile (both modules).**

```bash
./gradlew :core:transfer:compileAndroidMain :core:messaging:compileAndroidMain --no-configuration-cache --console=plain
```

Any pre-existing warning must reappear with its path rewritten to `androidMain/` and must not
change in count. A *new* warning means a file landed in the wrong tier.

**Gate 3 — desktop tests actually execute.**

```bash
./gradlew :core:transfer:jvmTest :core:messaging:jvmTest --no-configuration-cache --console=plain
```

**8 tests each, 0 failures.** A result of 0 tests means `commonTest` did not reach the desktop
target and the gate has failed even though Gradle reports success.

**Gate 4 — Android host suites unregressed.**

```bash
./gradlew :core:transfer:testAndroidHostTest :core:messaging:testAndroidHostTest --no-configuration-cache --console=plain
```

**94 and 35**, 0 failures. Compare per class against the measured baselines above — a matching
total with a missing class is the exact failure R3 exists to catch.

**Gate 5 — publication coordinates unchanged.**

```bash
./gradlew :core:transfer:publishToMavenLocal :core:messaging:publishToMavenLocal --no-configuration-cache --console=plain
ls ~/.m2/repository/com/transfer/flash/{core-transfer,core-messaging}*/1.1.0/
```

The group is **`com.transfer.flash`** (root `build.gradle.kts:12`), *not* `com.github.<user>` — the
JitPack coordinate consumers type is not the Maven group the build writes. Measured 2026-09-05;
this line originally guessed the JitPack form and the `ls` silently returned nothing while the
build itself reported SUCCESS.

Three publications per module: `core-transfer`, `core-transfer-android`, `core-transfer-jvm` (and
the messaging trio). Paste both `-jvm` POMs' dependency lists and confirm neither carries a dead
`:core:network` / `:core:persistence` / androidx entry — those are `androidMain`-scoped now, so a
desktop consumer must not see them.

**Gate 6 — the whole app still builds.**

```bash
./gradlew :app:assembleDebug --no-configuration-cache --console=plain
```

`:app`, `:core:engine` and `:ui:chat` consume these modules. All are Android, so androidMain
placement breaks nothing — but `:core:engine` declares both as `api`, so a mis-tiered file shows
up here rather than in Gates 1–5.

**Gate 7 — R6.1 purity grep.**

```bash
grep -rnE '\b(java|javax|android|androidx)\.' --include=*.kt core/*/src/commonMain ui/*/src/commonMain 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'
```

Expected: nothing. Then re-scan by hand for the stdlib traps no import reveals —
`System.currentTimeMillis`, `synchronized`, `@Synchronized`, `Charsets`, `String.format`,
`putIfAbsent`, `::class.java`, `@kotlin.jvm.Volatile` — across both new `commonMain` trees.

**Gate 8 — repo-wide R3 tally.** Run the full CONVENTIONS R3 command with `:core:transfer` and
`:core:messaging` added (see *CONVENTIONS edit* below). `BUILD FAILED` is the **expected**
outcome: the 12 known `:core:persistence` failures (11 `FlashSettingsDataStoreTest` + 1
`DiscoveryModeSettingTest`) are pre-existing. Then tally:

```bash
find . -path '*/build/test-results/*' -name 'TEST-*.xml' -not -path './media-downloader-main/*' | wc -l
```

Expect **945 / 12 failures / 0 errors / 0 skipped**. **Delete the dead results directories first**
— `core/transfer/build/test-results/testDebugUnitTest/` and the messaging one survive the plugin
swap and will be double-counted (Phase 07 and Phase 10 both hit this):

```bash
rm -rf core/transfer/build/test-results/testDebugUnitTest core/messaging/build/test-results/testDebugUnitTest
```

**Gate 9 — no leftover `java/` source roots.** `find core/transfer/src core/messaging/src -type d
-name java` returns nothing.

## CONVENTIONS edit (goes in the docs commit, not the code commit)

Add to R3's verification command:

```
:core:transfer:testAndroidHostTest :core:transfer:jvmTest
:core:messaging:testAndroidHostTest :core:messaging:jvmTest
```

and change *"as phases 11–12 land"* to *"as phase 12 lands"*. Update the baseline note to record
**945 / 12 / 0** after Phase 11 (+32 over Phase 10's 913: two 8-test `commonTest` suites, each
running once per target).

## Prohibitions

- **Do NOT create `jvmAndAndroidMain`**, and do not achieve it by another name: a `srcDir` shared
  between `androidMain` and `jvmMain` is the same thing and re-decides D1, which `DECISIONS.md`
  reserves for the human.
- **Do NOT introduce `expect`/`actual` in this phase.** Neither module needs one. The 15 + 2
  androidMain files are R2 step 1, not step 3.
- **Do NOT change a byte of `WsTransferMessages.kt`, `MessageWireFrame.kt` or `ChunkFrame.kt`**
  (R8). No reformatting, no import reordering, no visibility tweaks, and specifically no
  `Charsets` → `encodeToByteArray()` "cleanup" in `ChunkFrame`/`Sha256`.
- **Do NOT delete `WsTransferModels.kt` or `TransferManifest.kt`.** Both are dead-ish; both move
  to `androidMain`. Deleting them is a cleanup phase's job (R1). No ADR is needed for either — see
  disproved claim 6.
- **Do NOT delete the four dead dependency edges.** Park them in `androidMain` with
  `TODO(cleanup)`, matching Phase 10.
- **Do NOT pull `RealFlashChatRepository` or `RealFlashTransferRepository` toward `commonMain`,**
  and do not stub, weaken or delete anything to make a file compile there (R2).
- **Do NOT downgrade `:core:common` or `kotlinx-coroutines-core` from `api` to
  `implementation`** in either module — public signatures return `Flow`/`StateFlow`.
- **Do NOT change the `core-transfer` / `core-messaging` root coordinates**, and do not set
  `group` or `version` in either module's `publishing { }` — both come from the root build file.
- **Do NOT** use `jvm("desktop")`, `androidLibrary { }`, typed source-set accessors, or
  `src/**/java/` roots. **Do NOT** omit `withHostTest { }`. **Do NOT** add
  `isIncludeAndroidResources`.
- **Do NOT** run any Gradle command without `--no-configuration-cache` (R3), and do not upgrade a
  single dependency version (R10).

## Commits

Two, following Phase 10's precedent:

1. `refactor(transfer,messaging): convert :core:transfer and :core:messaging to Kotlin
   Multiplatform (Phase 11)` — the 43 moves, both build files, the one content edit, the two new
   suites. `core/transfer/**` and `core/messaging/**` only.
2. `docs(migration): Phase 11 repositories KMP logged; CONVENTIONS R3 + PHASE-11 rewritten for
   D1 = B` — this file, the log entry, and the R3 edit.

Splitting them means `git revert` of commit 1 alone restores a working build (R4).

## Log entry

Append to `docs/migration/logs/migration.md` with the six standard sections — `### Change`,
`### Files changed`, `### Verification`, `### Deviations from the phase file`, `### Known issues`,
`### Next step`. Paste **all nine gates' actual output**, not a claim that they passed (R9). The
entry must state the honest share: **9 of 26 production files reached `commonMain`** (5 of 20 in
transfer, 4 of 6 in messaging), and why — not the old file's 9-of-26-plus-14-in-a-tier-that-does-
not-exist.

Known issues to carry forward, at minimum: `TransferManifest.kt` is fully dead code; messaging's
`:core:security`, `:core:network` and both androidx edges are dead; transfer's two androidx edges
are dead; `RandomAccessChunkSink.kt` is androidMain only by reference-transitivity and would move
to `commonMain` for free the day `ChunkSink` and `RandomAccessSinkHandle` are extracted from their
host files; and Phase 12's `:core:engine` conversion inherits the `TlsOptions` blocker Phase 10
logged.

## Next step

**Phase 12 — `:core:engine`.** It is the last `core/*` module before the desktop work, it
`api`-depends on both modules this phase converted, and its `RoomTransferStore` is the ADR-024
adapter — so unlike messaging's Room edge, that one exists *to* touch Room and cannot be reasoned
away. Read `PHASE-12-*.md` for a D1 = A header first; four phases in a row have needed the same
rewrite.
