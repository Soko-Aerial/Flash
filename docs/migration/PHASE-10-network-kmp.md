# Phase 10 — KMP conversion: `core:network`

**Blocked by:** Phase 07 (`:core:security` KMP) and Phase 08 (`:core:discovery` KMP). Both are
real edges in this module's build file, and `commonMain` cannot compile for `jvm()` until every
module it depends on publishes a `jvm` variant. Phase 09B is **not** a blocker — `:core:network`
has no persistence edge.
**Risk: HIGH.** 35 production + 21 test files, and the module owns three things that must not
move by a byte: the TLS/TOFU trust path, the WebSocket wire codec, and the LAN probe framing.
The ways to get this wrong are (a) misplacing a file across source sets, (b) "helping" an R8
file compile for common by rewriting it, and (c) silently losing tests the way Phase 06 nearly
did.
**Decisions:** none block this phase. `D6` (desktop discovery) is Phase 14; `D3`/`D4` (desktop
transport + TLS) are Phase 15. This phase creates **no** desktop transport and **no** seam for
one. It relocates and shares the *contract* layer only.

> ## REWRITTEN 2026-09-05 for D1 = B
>
> The previous text of this file was **written for D1 = A** — the
> `commonMain → jvmAndAndroidMain → {androidMain, jvmMain}` model — and its entire value
> proposition was the middle tier: it placed 9 of 34 files in `jvmAndAndroidMain` and claimed
> a 22-of-34 shared surface.
>
> D1 **is** B (`DECISIONS.md`, reaffirmed by the 2026-09-03 amendment at the head of
> `CONVENTIONS.md`: *"`jvmAndAndroidMain` must never be created; R2 step 2 is void"*). That
> deletes the middle tier, so the old placement table cannot be executed as written. This file
> has been rewritten. What changed, and what did not:
>
> - **`jvmAndAndroidMain` is gone.** The 9 files it was to hold are ~2 772 lines of
>   `java.net` / `javax.net.ssl` / `java.io` code. Under B the only two legal moves for them
>   (CONVENTIONS R2) are *leave them where they are* or *`expect`/`actual` with a per-target
>   `actual`*. Rewriting the TLS trio and the wire codecs onto multiplatform IO **is Phase 15's
>   job by this plan's own structure**, and duplicating them into both `androidMain` and
>   `jvmMain` would create two divergent copies of wire-format code — the exact failure R8
>   exists to prevent. R2 step 1 applies: they stay Android-only for now.
> - **The honest shared surface is therefore 14 of 35 files, not 22 of 34.** `jvmMain` gets
>   **zero** files this phase. That is a correct outcome, not a shortfall — see
>   "Why 14 and not 22" below. Do not attempt to reach 22.
> - **A `srcDir`-shared directory is forbidden.** Pointing `androidMain` and `jvmMain` at one
>   shared folder would functionally restore `jvmAndAndroidMain` under another name and
>   re-decide D1. D1 is reserved for the human (`DECISIONS.md` preamble). Do not do it.
> - **The inventory drifted and has been re-derived** (measured 2026-09-05). The old file's
>   own stop-gate fired: *"if your run reports a different number, STOP — the inventory
>   drifted and the placement table must be re-derived before moving files."* It is
>   re-derived below.
> - **The `android.util.Log` section is deleted as factually wrong.** See "Two claims
>   disproved by measurement".
> - **Unchanged and still binding:** the R8 no-content-edit rule for the TLS trio and the
>   codecs, `api` scope for `:core:common` + `coroutines-core`, the `core-network` root
>   coordinate, the "no Phase 15 seam" rule, and every `Do NOT` that did not mention
>   `jvmAndAndroidMain`.

## What this phase is actually for

Convert `:core:network` from `com.android.library` to
`com.android.kotlin.multiplatform.library` + `jvm()`, place its 35 source files into the KMP
source sets by the placement rule, and prove that:

1. the **session and network contract** — `FlashSession`, `FlashNetwork`,
   `FlashConnectionState`, `FlashConnectionHealth`, `FlashNetworkState`, the four resilience
   policies, the keepalive verdict machine, the LAN probe message model, and the pin verifier —
   compiles for the desktop `jvm()` target from `commonMain`;
2. every concrete transport (WS, TCP/TLS, data-channel) stays isolated in `androidMain`
   with **not one byte of its content changed**; and
3. Android still builds and all 126 existing tests still run, plus a new `commonTest` suite.

The strategic payoff is narrow but load-bearing: **Phases 11 and 12 cannot start without it.**
`:core:transfer`, `:core:messaging` and `:core:engine` all consume `FlashSession` and
`FlashNetwork`; those types must be `commonMain` before any of those three modules can have a
`commonMain` at all. This phase does not make the network *work* on desktop — Phase 15 does
that. It makes the network *describable* on desktop.

## Verified starting state — measured 2026-09-05, supersedes the 2026-08-30 figures

| Fact | Value |
|---|---|
| Production `.kt` under `src/main/java/**` | **35** |
| Test `.kt` under `src/test/java/**` | **21** (20 test classes + 1 helper, `tls/SoftwareCertMaker.kt`) |
| `:core:network:testDebugUnitTest` | **126 tests, 20 classes, 0 failures, 0 skipped** |
| Published coordinate | `com.transfer.flash:core-network:1.1.0`, packaging `aar` |
| POM `compile` deps | `core-common`, `kotlinx-coroutines-core`, `kotlin-stdlib` |
| POM `runtime` deps | `core-security`, `core-discovery`, `androidx.core:core-ktx:1.10.1`, `androidx.lifecycle:lifecycle-runtime-ktx:2.6.1` |
| `android.util.Log` occurrences | **0** |
| `expect`/`actual` in module | **0** — and this phase adds none |
| Line split | commonMain tier **979**, androidMain tier **4 344** (1 572 Android + 2 772 JVM) |

Per-class baseline, to be compared exactly after the move:

```
DefaultFlashNetworkTest 2   FlashNetworkModelTest 2   DiscoveryRouteBinderTest 4
BoundedSendQueueTest 9      ChaosResilienceTest 7     ConnectionHealthAggregatorTest 8
HeartbeatTrackerTest 9      ReconnectPolicyTest 8     SessionHardeningPolicyTest 7
LanProbeMessagesTest 3      LanSessionHardenedTest 5  FlashPinVerifierTest 3
SecureSocketUpgraderTest 4  SoftwareCertMakerTest 4   TofuTlsHandshakeTest 4
TofuX509TrustManagerTest 6  SecureWsTransferLoopbackTest 3   WebSocketCodecTest 14
WsFlashNetworkTest 9        WsKeepaliveTest 15                        = 126
```

## Two claims disproved by measurement — do not restore them

**1. The file counts were 34 / 20; they are 35 / 21.** `ws/WsKeepalive.kt` (157 lines) and
`ws/WsKeepaliveTest.kt` (15 tests) were added after 2026-08-30 by the keepalive/liveness work
(ERROR-025 / ERROR-031). `WsKeepalive` is a **pure** verdict machine whose only import is
`kotlin.concurrent.Volatile` — it belongs in `commonMain` and the old placement table does not
mention it at all.

**2. There is no `android.util.Log` in this module.** The old file had a whole section built on
*"six files pinned to `androidMain` only by `android.util.Log`"* and a rule not to introduce a
log seam for them. `grep -rn 'android\.util\.Log' core/network/src` returns **0 hits**: Phase 03
already routed the whole module through `FlashLog`, which is `commonMain` in `:core:common`.
Those six files are pinned by `android.content.Context` / `android.net.ConnectivityManager` /
`android.net.NetworkCapabilities` instead — a hard Android pin no seam removes. The section is
therefore deleted rather than corrected, and the "do not introduce a log seam" rule survives
only as a `Do NOT` (there is nothing to seam).

## Why 14 and not 22 — read this before you try to "improve" the split

Under D1 = A the middle tier would have held nine files: the TLS trio
(`FlashTlsContextFactory`, `SecureSocketUpgrader`, `TofuX509TrustManager`), the WS pair
(`WebSocketCodec`, `WsConnection`, `WsSession`, `WsTransferServer`), `LanSession`, and
`BoundedSendQueue`. Under B each has exactly two legal destinations (R2), and each fails both:

| File(s) | What pins it | Why not `expect`/`actual` in this phase |
|---|---|---|
| TLS trio (462 lines) | `javax.net.ssl.*`, `java.security.cert.X509Certificate` | The whole surface *is* the JVM TLS API. An `expect` would be a re-implementation, i.e. Phase 15 / D4. |
| `WebSocketCodec`, `WsConnection`, `WsTransferServer` (666) | `java.net.Socket`, `java.io.InputStream/OutputStream` | R8 wire format. Two `actual` copies = two codecs that can drift. |
| `WsSession` (151) | `public val connection: WsConnection` **in its constructor** | Extracting an interface is a public-ABI change and is the Phase 15 seam decision. |
| `LanSession` (465) | `java.net.Socket` + `java.io` streams | Same as the codecs; also R8-adjacent framing. |
| `BoundedSendQueue` (153) | `ReentrantLock` + `lock.newCondition()` + `Condition.awaitNanos` | A `Condition` has **no** common equivalent; `PlatformLock` does not provide one. `takeOrNull(timeoutMs)` is a genuinely blocking timed wait. |

Two more files that look shareable and are not: `ChaosSession` and `ChaosNetworkHarness` use
`Collections.newSetFromMap(ConcurrentHashMap())`, `Collections.synchronizedList` and
`CopyOnWriteArrayList`. They would need a lock seam, which would make this the **third**
`PlatformLock` copy in the repo — the decision Phase 08's Known issue #1 explicitly parks for
*"whichever phase first finds a third module needing it"*. Chaos test-support code is not worth
spending that decision on inside a phase not scoped to it. R1: leave them.

So the shared surface is the **contract**, not the **implementation**, and that is sufficient
for the phases that depend on this one. Phase 11 needs to name a `FlashSession`; it does not
need to open a socket.

## Placement table — all 35 production files, D1 = B

Paths are relative to `core/network/src/`. Source root is `<set>/kotlin/com/transfer/flash/core/network/`
(**`kotlin/`, never `java/`** — R5).

### commonMain — 14 files, 979 lines (13 move untouched, 1 has a one-line edit)

| # | File | Why it is common |
|---|---|---|
| 1 | `FlashConnectionHealth.kt` | data model, coroutines only |
| 2 | `FlashConnectionState.kt` | sealed state model |
| 3 | `FlashNetwork.kt` | the network contract |
| 4 | `FlashNetworkState.kt` | data model |
| 5 | `FlashSession.kt` | **the** session contract — **one-line edit, see below** |
| 6 | `bridge/DiscoveryRouteBinder.kt` | `fun interface EndpointMemory` + binder; only cross-module type is `FlashDiscoveredEndpoint` (commonMain since Phase 08) |
| 7 | `resilience/ConnectionHealthAggregator.kt` | pure; `MutableStateFlow` only |
| 8 | `resilience/HeartbeatPolicy.kt` | pure policy |
| 9 | `resilience/HeartbeatTracker.kt` | pure; `internal enum HeartbeatState` |
| 10 | `resilience/ReconnectPolicy.kt` | pure backoff arithmetic |
| 11 | `resilience/SessionHardeningPolicy.kt` | pure; `DuplicateSessionDecision` |
| 12 | `tcp/LanProbeMessages.kt` | `LanProbeHello` model + **R8 framing** — moves untouched |
| 13 | `tls/FlashPinVerifier.kt` | 31 lines, string/byte comparison only — no `javax.net.ssl` |
| 14 | `ws/WsKeepalive.kt` | pure verdict machine; `kotlin.concurrent.Volatile` already correct |

Verified: after stripping comment lines, the only JVM-only token anywhere in these 14 files is
`FlashSession.kt:29`. `WsKeepalive.kt:75`'s `@Volatile` resolves to the imported
`kotlin.concurrent.Volatile`, which is multiplatform — **do not "fix" it**.

Their complete import set — nothing outside it is allowed to appear:

```
com.transfer.flash.core.common.annotation.FlashInternalApi
com.transfer.flash.core.common.model.{FlashDevice, FlashDeviceId, FlashTransportType}
com.transfer.flash.core.common.protocol.FlashTextFraming
com.transfer.flash.core.common.result.FlashResult
com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
kotlin.concurrent.Volatile
kotlinx.coroutines.{Job, launch}
kotlinx.coroutines.flow.{MutableStateFlow, StateFlow, asStateFlow}
```

### androidMain — 21 files, 4 344 lines, all moved **byte-identical**

Six with a genuine Android API pin (1 572 lines):

| File | Android pin |
|---|---|
| `DefaultFlashNetwork.kt` (394) | `android.content.Context`, `ConnectivityManager` |
| `resilience/AndroidNetworkWatcher.kt` (62) | `ConnectivityManager`, `NetworkRequest`, `NetworkCapabilities` |
| `tcp/LanConnectionProbe.kt` (126) | `ConnectivityManager`, `Network`, `NetworkCapabilities` |
| `util/LocalNetworkAddresses.kt` (51) | `ConnectivityManager`, `Network`, `NetworkCapabilities` |
| `ws/WsFlashNetwork.kt` (825) | `Context`, `ConnectivityManager` |
| `ws/WsTransferClient.kt` (114) | `Network`, `NetworkCapabilities` |

Fifteen with a JVM-only pin (2 772 lines) — these are the files D1 = A would have shared:

```
datachannel/DataChannelClient.kt   (135)   resilience/BoundedSendQueue.kt      (153)
datachannel/DataChannelFraming.kt   (75)   resilience/ChaosNetworkHarness.kt   (166)
datachannel/DataChannelServer.kt   (146)   resilience/ChaosSession.kt          (220)
tcp/LanProbeServer.kt              (133)   tcp/LanSession.kt                   (465)
tls/FlashTlsContextFactory.kt       (77)   tls/SecureSocketUpgrader.kt         (273)
tls/TofuX509TrustManager.kt        (112)   ws/WebSocketCodec.kt                (290)
ws/WsConnection.kt                 (244)   ws/WsSession.kt                     (151)
ws/WsTransferServer.kt             (132)
```

`DataChannelFraming.kt`, `WebSocketCodec.kt` and the framing inside `LanSession.kt` are **R8
wire formats**. `FlashTlsContextFactory.kt`, `SecureSocketUpgrader.kt` and
`TofuX509TrustManager.kt` are **R8 crypto/trust**. Relocating them is allowed; changing a byte
is not. No reformatting, no import reordering, no visibility tweaks, no adding `@Suppress`.

### jvmMain — zero files

Deliberate. `jvmMain` exists as a source set (the `jvm()` target creates it) and stays empty
this phase. Desktop transport is Phase 15. **Do not create a placeholder file, a stub
`FlashNetwork` implementation, or a `TODO()`-bodied class here** — R2 forbids stubbing to force
a compile, and an empty `jvmMain` is a truthful statement of where the port stands.

## The single content edit — `FlashSession.kt:29`

```kotlin
// before
public suspend fun sendText(text: String): FlashResult<Unit> = send(text.toByteArray(Charsets.UTF_8))
// after
public suspend fun sendText(text: String): FlashResult<Unit> = send(text.encodeToByteArray())
```

`Charsets` and `String.toByteArray(Charset)` are both on the R6 JVM-only list, and
`encodeToByteArray()` is the substitution R6 names (Phase 07's Rewrite 3 already made it).

**This is not a pure identity and must not be logged as one.** The two differ for exactly one
input class: unpaired surrogates. `String.getBytes(UTF_8)` on the JVM emits `0x3F` (`?`) per
unpaired surrogate; `encodeToByteArray()` emits the 3-byte U+FFFD replacement `EF BF BD`. For
every well-formed string — all ASCII, all multi-byte, all correctly-paired emoji — the output is
byte-identical.

Who is affected: `WsSession` **overrides** `sendText`, so the WS path is untouched.
`ChaosSession` delegates (`: FlashSession by delegate`). `LanSession` inherits the default, so
the LAN text path is the one observable site — and only for malformed input that no Flash
caller produces (`FlashTextFraming` builds the payloads). Record it in the log; do not hide it.

A new `commonTest` suite pins the identity for the well-formed cases (see below), so the claim
is executed rather than asserted.

## Tests — 21 files to `androidHostTest` unchanged, plus one new `commonTest` suite

All 21 existing test files move to `src/androidHostTest/kotlin/...` **unmodified**. Every one of
them is JUnit 4 and most reach for `java.util.concurrent`, real sockets, or BouncyCastle;
none can be `commonTest`. `tls/SoftwareCertMaker.kt` is a helper, not a test class — it is the
only consumer of `libs.bouncycastle.pkix` and must move with them.

The new `commonTest` file is required by CONVENTIONS R3.1: a converted module must **execute**
something on both targets, not merely compile. Without it `jvmTest` is a no-op and the desktop
target is unproven.

`src/commonTest/kotlin/com/transfer/flash/core/network/FlashSessionSendTextTest.kt` —
`kotlin.test` only, asserting the `sendText` default path against a recording fake:
ASCII, multi-byte (`"héllo wörld"`), CJK, a correctly-paired emoji surrogate pair, and the
empty string all round-trip byte-identically through `encodeToByteArray()`, and `sendText`
delegates to `send` exactly once with those bytes. That suite runs under **both**
`testAndroidHostTest` and `jvmTest`.

Consequence for the count gate: `testAndroidHostTest` must report **126 + n**, where `n` is the
number of `@Test`s in the new suite, and `jvmTest` must report exactly **n**. Record both.

## Cross-module dependency placement

| Dependency | Scope + set | Reason |
|---|---|---|
| `project(":core:common")` | `commonMain` **`api`** | Load-bearing: `FlashResult`, `FlashDevice`, `FlashTransportType` are in this module's public signatures. **Do not downgrade to `implementation`** — it silently breaks every consumer. |
| `libs.kotlinx.coroutines.core` | `commonMain` **`api`** | `StateFlow`/`Flow` appear in public signatures (`FlashSession.connectionState`, `frameAcks`). Same rule. |
| `project(":core:discovery")` | `commonMain` `implementation` | Real edge — `bridge/DiscoveryRouteBinder.kt` uses `FlashDiscoveredEndpoint`, which is commonMain since Phase 08. |
| `project(":core:security")` | `androidMain` `implementation` | **Dead**: zero `com.transfer.flash.core.security` references in main *or* test. Relocate, do not delete — see below. |
| `libs.androidx.core.ktx` | `androidMain` `implementation` | **Dead** (grep-unused). Relocate. |
| `libs.androidx.lifecycle.runtime.ktx` | `androidMain` `implementation` | **Dead** (grep-unused; the only textual hit is a stale KDoc note in `ConnectionHealthAggregator`). Relocate. |
| `libs.junit` | `androidHostTest` + `jvmTest` | JUnit 4 for the moved suites; `jvmTest` mirrors `:core:discovery`. |
| `libs.bouncycastle.pkix` | `androidHostTest` | Test-only, `SoftwareCertMaker.kt` only. |
| `kotlin("test")` + `libs.kotlinx.coroutines.test` | `commonTest` | The new suite. Both already pinned (coroutines 1.10.2) → R10 clean. No `kotlin-test` version-catalog alias exists; use `kotlin("test")`. |

### Why the three dead dependencies are relocated, not deleted

The previous text of this file said: *"Do NOT delete the three dead dependencies… relocate and
flag `TODO(cleanup)`; a later phase deletes them."* That instruction is **kept**.

Be aware that this makes the repo temporarily inconsistent: Phase 08 chose the opposite for
`:core:discovery`'s two dead `androidx.*` deps and deleted them (logged there as Deviation 1).
Both dead sets are equally unreferenced, so there is no principled difference — only a
sequencing preference. Deleting them changes `core-network-android`'s POM, where all three sit
today as `runtime`-scope entries, and that is a consumer-visible resolution change. It should be
made once, for every module at once, in the cleanup phase that can prove the whole graph still
resolves; not module-by-module across ten phases. Relocating to `androidMain` reproduces the
Android POM exactly and keeps them out of `core-network-jvm`.

If the cleanup phase never happens, deleting these three lines is a one-line-each change with
`:app:assembleDebug` as its only gate. Nothing here makes that harder.

## Phase 15 seam candidates — record, do NOT act

1. **`TlsOptions`** is declared as `public class TlsOptions(` at
   `tls/SecureSocketUpgrader.kt:30`, i.e. inside a 273-line `javax.net.ssl` file, but it is a
   plain config object imported by `core/engine` and `app` (4 imports). Once
   `SecureSocketUpgrader.kt` lands in `androidMain`, **`TlsOptions` is unreachable from
   `commonMain`**, which will block Phase 12 when `:core:engine` tries to make its
   configuration common. Splitting it into its own `commonMain` file is a zero-ABI-change file
   split, but it is out of this phase's scope (R1). Flag it as a Phase 12 prerequisite.
2. **`WsSession`'s `public val connection: WsConnection`** — the constructor parameter that
   pins the class. Introducing a transport interface is the Phase 15 decision; it is a public
   ABI change, so it is not a move-only edit.
3. **`BoundedSendQueue`'s `Condition`** — a timed blocking wait with no common equivalent.
   Phase 15 must either keep it JVM-side or restate it on `Channel`/`select`.

## The target `core/network/build.gradle.kts` (paste-ready)

Mirrors `core/discovery/build.gradle.kts` (the Phase 08 output). Differences from it: no
`-Xexpect-actual-classes` (this module declares **no** `expect`), and the dead deps sit in
`androidMain`.

```kotlin
plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under AGP 9+.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    explicitApi()

    // NOTE: no `compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }` here.
    // :core:common, :core:security and :core:discovery need it because each declares an
    // `expect class`. This module declares none, so the flag would be noise.

    android {
        namespace = "com.transfer.flash.core.network"
        compileSdk = 35
        minSdk = 24                      // NOT inside defaultConfig — the target is variant-free

        optimization {                   // was defaultConfig { consumerProguardFiles(...) }
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        localDependencySelection {       // replaces buildTypes { release { ... } }
            selectBuildTypeFrom.set(listOf("release"))
        }

        compilerOptions {                // replaces compileOptions { source/targetCompatibility }
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates androidHostTest + the `testAndroidHostTest` task. Omit it and all 21 migrated
        // test files stop compiling AND running while the build still reports SUCCESS.
        withHostTest { }

        withDeviceTest {                 // was defaultConfig { testInstrumentationRunner = ... }
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Plain `jvm()`, never `jvm("desktop")` — R5. `compileKotlinJvm` cannot see android.jar,
    // which is what proves the 14 commonMain files are genuinely Android-free.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // StateFlow/Flow appear in FlashSession's and FlashNetwork's public signatures, so
            // `implementation` would keep those types off a consumer's compile classpath.
            api(libs.kotlinx.coroutines.core)
            // Real edge: bridge/DiscoveryRouteBinder.kt uses FlashDiscoveredEndpoint, which is
            // commonMain in :core:discovery since Phase 08.
            implementation(project(":core:discovery"))
        }
        androidMain.dependencies {
            // TODO(cleanup): all three are dead — zero references in main and test. They are
            // parked here rather than deleted so core-network-android's POM keeps the three
            // runtime-scope entries 1.1.0 consumers see today. See the phase file.
            implementation(project(":core:security"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        // Runs on BOTH the Android host-test JVM and the desktop jvm() target (R3.1).
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The 20 pre-existing suites + 1 helper are JUnit 4 and use java.util.concurrent, real
        // sockets and BouncyCastle, so they stay on the Android host-test tier, unchanged.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.bouncycastle.pkix)   // tls/SoftwareCertMaker.kt only
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications {
        // KMP generates the publications itself (root `kotlinMultiplatform` + one per target),
        // so a module must NOT register<MavenPublication>("release") any more. Default
        // artifactIds derive from the project name (`network`, `network-android`, `network-jvm`);
        // rename in place to keep the coordinates 1.1.0 consumers already use. Version and group
        // still come from the root build file — never set them here.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("network", "core-network")
        }
    }
}
```

## Steps — in order. Every Gradle command uses `--no-configuration-cache`.

Environment for every command in this phase:

```bash
export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2"
export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix'
```

### Step 1 — Preconditions. Verify, do not assume.

1. `git status --short` is empty.
2. `:core:common`, `:core:security`, `:core:discovery` are all KMP — each `build.gradle.kts`
   applies `libs.plugins.kotlin.multiplatform`. If any still applies `com.android.library`, this
   phase is blocked; go finish that phase first.
3. `find core/network/src/main -name '*.kt' | wc -l` → **35**, and
   `find core/network/src/test -name '*.kt' | wc -l` → **21**. Any other number means the
   inventory drifted **again**; stop and re-derive the placement table before moving anything.

### Step 2 — Record the baseline (before touching the build file)

```bash
./gradlew :core:network:testDebugUnitTest :core:network:publishToMavenLocal --no-configuration-cache
```

Extract the per-class `@Test` counts from `core/network/build/test-results/testDebugUnitTest/TEST-*.xml`
and the coordinate from `~/.m2/repository/com/transfer/flash/core-network/`. Paste both into the
log. Expected: **126 / 20 classes / 0 failures**, `core-network:1.1.0` packaging `aar`.

### Step 3 — Rewrite `core/network/build.gradle.kts`, alone

Replace it wholesale with the block above. **Only this module's build file in this commit** (R4).
Do not touch `core/discovery/build.gradle.kts`, `core/engine/build.gradle.kts`, or `app/`.

### Step 4 — `git mv` the 14 `commonMain` files

Target root: `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/`. Use `git mv`
so the history is a rename, never `cp` + delete.

```
FlashConnectionHealth.kt  FlashConnectionState.kt  FlashNetwork.kt  FlashNetworkState.kt
FlashSession.kt  bridge/DiscoveryRouteBinder.kt  resilience/ConnectionHealthAggregator.kt
resilience/HeartbeatPolicy.kt  resilience/HeartbeatTracker.kt  resilience/ReconnectPolicy.kt
resilience/SessionHardeningPolicy.kt  tcp/LanProbeMessages.kt  tls/FlashPinVerifier.kt
ws/WsKeepalive.kt
```

### Step 5 — `git mv` the remaining 21 files to `androidMain`

Target root: `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/`. After this
step `find core/network/src/main -name '*.kt'` must print **nothing**; remove the now-empty
`src/main` tree.

### Step 6 — `git mv` all 21 test files to `androidHostTest`

Target root: `core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/`. After
this step `find core/network/src/test -name '*.kt'` must print **nothing**. 56 renames total.

### Step 7 — The one content edit, then the new `commonTest` suite

Apply the `FlashSession.kt:29` change, then write
`src/commonTest/kotlin/com/transfer/flash/core/network/FlashSessionSendTextTest.kt`.
Nothing else in the 35 files may change. Verify with
`git diff --cached --numstat -- '*/commonMain/*' '*/androidMain/*'` — every moved file must show
`0 0` except `FlashSession.kt`.

## Verification gates — seven, all must pass

### Gate 1 — the desktop target compiles (the load-bearing check)

```bash
./gradlew :core:network:compileKotlinJvm --no-configuration-cache
```

This is the R2 proof: the `jvm()` compilation has no `android.jar`, so success means the 14
`commonMain` files are genuinely Android-free. It certifies **nothing** about `java.*` (R6.1) —
Gate 6 covers that.

### Gate 2 — Android main compiles

```bash
./gradlew :core:network:compileAndroidMain --no-configuration-cache
```

### Gate 3 — the Android host tests; the count MUST equal baseline + `commonTest`

```bash
./gradlew :core:network:testAndroidHostTest --no-configuration-cache
```

`testDebugUnitTest` **no longer exists** in this module (no build variants). Compare the
per-class table against the Step 2 baseline: 126 pre-existing + the new `commonTest` suite's `n`.
A total below 126 means tests were silently dropped — the Phase 06 trap — and the phase is not
done. Paste the full per-class table into the log, not just the total.

### Gate 4 — `jvmTest`: the shared code is executed on desktop, not just compiled

```bash
./gradlew :core:network:jvmTest --no-configuration-cache
```

Must report exactly `n` tests — the `commonTest` suite. Zero tests means the suite did not land
in `commonTest` and the desktop target is unproven (R3.1).

### Gate 5 — published coordinates

```bash
./gradlew :core:network:publishToMavenLocal --no-configuration-cache
ls ~/.m2/repository/com/transfer/flash/ | grep network
```

Must list exactly `core-network`, `core-network-android`, `core-network-jvm`. Any of `network`,
`network-android`, `network-jvm` means the `artifactId.replace` did not fire. Also confirm
`core-network-jvm-1.1.0.pom` does **not** contain `core-security`, `core-ktx` or
`lifecycle-runtime-ktx`, and that `core-network-android-1.1.0.pom` still does.

### Gate 6 — the R6.1 purity grep (nothing in the build enforces R6)

```bash
grep -rnE '\b(android|androidx|java|javax|kotlin\.jvm)\.[A-Za-z]|::class\.java|\bCharsets\b|String\.format' \
  core/network/src/commonMain --include='*.kt' | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'
```

Must print nothing. The comment filter is required: `ConnectionHealthAggregator`'s KDoc mentions
`androidx.lifecycle` in prose. That note is stale — coroutines has always been a direct `api`
dependency, not a transitive one — but fixing prose is not this phase's job (R1). Record it as a
known issue instead.

### Gate 7 — the Android app still resolves and builds, then the repo-wide R3 command

```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

Then the full R3 command from `CONVENTIONS.md`, with `:core:network:testDebugUnitTest` swapped
for `:core:network:testAndroidHostTest :core:network:jvmTest`. `--continue` is mandatory and
`BUILD FAILED` is the **expected** outcome: 12 pre-existing failures in `:core:persistence`
(11 `FlashSettingsDataStoreTest` + 1 `DiscoveryModeSettingTest`). The pass count must be
**≥ 897 + n** (the Phase 08 floor plus this phase's new tests) with failures still exactly 12
and all 12 still in `:core:persistence`. Update R3's command line in `CONVENTIONS.md` as part
of this phase.

## Do NOT

- Do **not** create `jvmAndAndroidMain`, or any `srcDir` that two platform source sets share.
  That re-decides D1, which is the human's call.
- Do **not** edit the content of the 34 files that are not `FlashSession.kt`. The TLS trio and
  the four wire codecs are R8-sensitive: relocating them is allowed, changing a byte is not.
  No reformatting, no import reordering, no visibility tweaks, no `@Suppress`.
- Do **not** introduce an `expect`/`actual` log seam. There is no `android.util.Log` left to
  seam — Phase 03 already did this via `FlashLog`.
- Do **not** create any Phase 15 seam: no transport interface extracted from `WsConnection`, no
  multiplatform-IO rewrite, no `TlsOptions` file split. Record them, do not act.
- Do **not** put anything in `jvmMain`, including a stub or a `TODO()`.
- Do **not** downgrade `:core:common` or `libs.kotlinx.coroutines.core` from `api`.
- Do **not** delete the three dead dependencies here.
- Do **not** use `jvm("desktop")`, a `src/**/java/` source root, or typed source-set accessors
  for hand-created sets (`androidHostTest` needs `getByName`).
- Do **not** omit `withHostTest { }` — the 21 test files stop running while the build stays green.
- Do **not** add `isIncludeAndroidResources` or any Robolectric wiring; nothing here needs it.
- Do **not** change the `core-network` root coordinate, the group, or the version.
- Do **not** run any Gradle command without `--no-configuration-cache`, and never with
  `--offline`.
- Do **not** edit a second module's build file in this commit (R4).

## Completion checklist — every box, or the phase is not done

- [ ] `core/network/build.gradle.kts` applies the two KMP plugins; `com.android.library` is gone
- [ ] 14 files in `src/commonMain/kotlin/**`, 21 in `src/androidMain/kotlin/**`, 0 in `src/main`
- [ ] `src/jvmMain` is empty or absent
- [ ] 21 test files in `src/androidHostTest/kotlin/**`, 0 in `src/test`
- [ ] 1 new file in `src/commonTest/kotlin/**`
- [ ] `git diff` shows exactly one content change across all 35 moved production files
- [ ] `explicitApi()` preserved; no `-Xexpect-actual-classes` added
- [ ] Gates 1–7 all pass, with outputs pasted into the log
- [ ] `testAndroidHostTest` = 126 + n; `jvmTest` = n
- [ ] `core-network` / `-android` / `-jvm` all published at 1.1.0
- [ ] `CONVENTIONS.md` R3 command updated
- [ ] `logs/migration.md` entry appended from `TEMPLATE-phase-log.md`, including the
      `sendText` behavioural note, the two disproved claims, and the 14-of-35 correction

## Rollback

One commit, one module. `git revert <sha>` restores `src/main`/`src/test` and the AGP build file
in a single step, because every move is a `git mv` rename. If the revert is needed after
`publishToMavenLocal`, also
`rm -rf ~/.m2/repository/com/transfer/flash/core-network-{android,jvm}` so a stale KMP variant
cannot satisfy a later resolution.

## Log entry (mandatory)

Append one entry to `logs/migration.md` using `TEMPLATE-phase-log.md`. It must record, at
minimum:

1. the measured baseline (126/20) and the post-move counts;
2. that the phase file was **rewritten for D1 = B** before execution, and that the shared surface
   is **14 of 35**, not the old file's 22 of 34, with the R2-step-1 reasoning;
3. the two claims the old file made that measurement disproved;
4. the `sendText` UTF-8 change and its exact behavioural boundary (unpaired surrogates only);
5. the three dead dependencies parked in `androidMain` and the inconsistency with Phase 08;
6. the Phase 12 blocker (`TlsOptions` unreachable from `commonMain`) and the Phase 15 seams;
7. the stale `ConnectionHealthAggregator` KDoc note, unfixed by R1.





