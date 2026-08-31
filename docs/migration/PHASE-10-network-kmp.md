# Phase 10 — KMP conversion: `core:network`

**Blocked by:** Phases 06 (pilot), 07 (security), 08 (discovery). **D1 must be `A`.** Written for
**D1 = A** (`jvmAndAndroidMain`).
**Risk: MEDIUM-HIGH — the largest core module (34 production, 20 test files) and heavily
R8-sensitive** (TLS/TOFU trust management + two wire codecs). But like 07/08 it is, under D1 = A,
a **pure file-move with zero content edits**: no `expect`/`actual`, no rewrites. The complexity is
in *placement* (three source sets, split within the same packages) and in **two dead dependencies
plus a third unused project dependency** that must be relocated, not deleted.

## What this phase is actually for

Convert `:core:network` from `com.android.library` to the KMP plugin with an Android target and a
desktop `jvm()` target, moving files into source sets **unedited**. When done:

- `:core:network:compileKotlinJvm` compiles — proving the **shared network surface** builds for
  desktop: the transport **contracts** (`FlashNetwork`, `FlashSession`, connection-state/health
  models), the **resilience policies** (heartbeat, reconnect, health aggregation, session
  hardening, bounded send queue, chaos harness), the **TLS/TOFU machinery** (`FlashTlsContextFactory`,
  `SecureSocketUpgrader`, `TofuX509TrustManager`, `FlashPinVerifier`), and the two **wire codecs**
  (`WebSocketCodec`, `DataChannelFraming`). That is 22 of 34 files.
- The Android host tests still run and still pass **at the same count** (102 `@Test` across 19
  classes).
- `:core:network` still publishes as `core-network`; `:app:assembleDebug` still resolves it.

**What this phase does NOT do:** it does **not** give desktop a *runnable* network. The concrete
transport (Android's `WsFlashNetwork`/`WsTransferClient`/sessions/`DefaultFlashNetwork`) is
`androidMain` because it is bound to `android.content.Context` + `ConnectivityManager` (and, for six
files, `android.util.Log`). Desktop's concrete transport is built later, in **Phase 15 (desktop
transport)**, against the same contracts. Phase 10 only proves the shared half compiles off Android
— exactly the 07 pattern (compile the surface; platform impls come later).

## Why `core:network` splits 13 / 9 / 12 (read before placing files)

- **commonMain (13):** the transport *interfaces* + pure state/policy machines. `FlashNetwork` and
  `FlashSession` are interfaces; the resilience policies are pure math/state (injected clocks and
  `random01`, no platform clock); `FlashPinVerifier` is a `fun interface`; `LanProbeMessages` is a
  text wire format. All reference only `kotlinx.coroutines.*` (multiplatform) and `:core:common`
  commonMain types.
- **jvmAndAndroidMain (9):** everything that needs `java.*`/`javax.*` but **not** `android.*` — the
  TLS trio (`javax.net.ssl`, `java.security`), the two codecs (`java.io`/`java.nio`/`java.security`),
  the LAN probe server (`java.net.ServerSocket`), and the JDK-collection-backed queue/chaos helpers.
  These are desktop-safe: `jvm()` has the full JDK.
- **androidMain (12):** the concrete transport. Six files need `Context`/`ConnectivityManager`
  (real Android platform network services); six more need **only `android.util.Log`** — a trivial
  coupling that nonetheless pins them to Android under a move-only phase (see the Log note below).

## Preconditions — do not start until all are true

1. **Phases 06, 07, 08 complete and logged.** This phase reuses Phase 06's five DSL facts and
   depends on `:core:common` (06) and `:core:discovery` (08) being KMP with their consumed types in
   **commonMain**. Specifically verify in the 06/08 logs that these are commonMain: `FlashDevice`,
   `FlashDeviceId`, `FlashTransportType`, `FlashPeerPresence`, `FlashResult`, `FlashError`,
   `FlashInternalApi`, `FlashTextFraming` (all `:core:common`); `FlashDiscoveredEndpoint`
   (`:core:discovery`). If any is NOT commonMain upstream, a commonMain file here will not compile —
   stop and reconcile with that module's phase before moving anything.
2. **D1 = `A`** in `DECISIONS.md`.
3. **Clean working tree** on the migration branch.

## Verified starting state (read this, do not assume) — confirmed 2026-08-30

**Current `core/network/build.gradle.kts` (the parts that matter):**

```kotlin
plugins { alias(libs.plugins.android.library); `maven-publish` }

android {
    namespace = "com.transfer.flash.core.network"
    compileSdk = 35
    defaultConfig { minSdk = 24; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro") }
    buildTypes { release { isMinifyEnabled = false /* + proguardFiles */ } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { explicitApi() }
dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)                 // public Flow/StateFlow → api
    implementation(project(":core:security"))          // <-- ZERO imports in src/main (dead dep)
    implementation(project(":core:discovery"))         // used ONLY by bridge/DiscoveryRouteBinder
    implementation(libs.androidx.core.ktx)             // <-- ZERO androidx imports in src/main (dead)
    implementation(libs.androidx.lifecycle.runtime.ktx)// <-- ZERO androidx imports in src/main (dead)
    testImplementation(libs.junit)
    testImplementation(libs.bouncycastle.pkix)         // TEST-ONLY: self-signed certs for JVM TLS tests
}
publishing { publications { register<MavenPublication>("release") {
    artifactId = "core-network"; afterEvaluate { from(components["release"]) } } } }
```

**Dependency-usage findings (grep of `src/main`, R11 hygiene — excludes `media-downloader-main/`,
`build/`, `.git/`, `docs/`):**

- **`:core:security` — ZERO imports** (`grep '^import com.transfer.flash.core.security'` = none).
  It is a **dead project dependency**. Network does its own pinning (`FlashPinVerifier`, defined
  here in commonMain); it never touches `:core:security` types. Relocate it to
  `jvmAndAndroidMain.dependencies` for parity (the tier its TLS code would use if it ever did) and
  flag it grep-unused — a later cleanup phase may delete it. **Do not delete it here** (move-only).
- **`:core:discovery` — used by exactly one file:** `bridge/DiscoveryRouteBinder.kt` (commonMain)
  imports `FlashDiscoveredEndpoint`. So `:core:discovery` → **commonMain.dependencies**.
- **`:core:common` — used everywhere**, including commonMain files → **commonMain.dependencies (api)**.
- **`androidx.core.ktx` / `androidx.lifecycle.runtime.ktx` — ZERO `import androidx.*`** in src/main
  (grep-confirmed). Dead AARs; relocate to `androidMain.dependencies`, flag for later cleanup.
- **`bouncycastle.pkix`** is TEST-ONLY (JVM handshake tests) → `androidHostTest.dependencies`. It is
  a plain JVM library and never ships in the AAR.

**Directory facts:** production under `core/network/src/main/java/com/transfer/flash/core/network/`
(34 `.kt` in packages: root, `bridge/`, `resilience/`, `tcp/`, `tls/`, `ws/`, `util/`,
`datachannel/`). Tests under `src/test/java/.../network/` (20 files = 19 JUnit4 classes + the
`tls/SoftwareCertMaker.kt` helper). **No `AndroidManifest.xml`, no `res/`, no `assets/`** under
`src/`. `consumer-rules.pro` + `proguard-rules.pro` at the module root.

## Source-set strategy (D1 = A) — the exact wiring

Three source sets, identical topology to Phases 07/08/09. Copy `KMP_ANDROID_DSL_SNIPPET` and
`KMP_JVM_TARGET_DSL` from the Phase 06 log verbatim; the source-set graph is:

```kotlin
kotlin {
    explicitApi()
    androidLibrary { /* PASTE KMP_ANDROID_DSL_SNIPPET: namespace, compileSdk=35, minSdk=24,
                        withHostTest { }  + consumerProguardFiles */ }
    jvm()                                   // plain jvm(), NEVER jvm("desktop")
    sourceSets {
        val jvmAndAndroidMain = create("jvmAndAndroidMain")
        jvmAndAndroidMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)
        // ... dependency blocks (see build rewrite below)
    }
}
```

**Rules that do not change between phases (re-state them so a weak model cannot drift):**

- Always `getByName("commonMain")` / `getByName("androidMain")` / `getByName("jvmMain")` — **never**
  the typed accessors `commonMain { }` / `androidMain { }`. Typed accessors are not registered for a
  hand-created intermediate set and will fail configuration.
- The intermediate set is created with `create("jvmAndAndroidMain")` and wired with three explicit
  `dependsOn` calls. Do not rename it, do not use `jvmMain`/`androidMain` as the shared tier.
- KMP source roots are `src/<sourceSet>/kotlin/…`, **not** `src/<sourceSet>/java/…`. The `git mv`
  destinations below all target `kotlin/`.
- Unit tests live in **`androidHostTest`** (Robolectric/JVM host), never instrumented `androidTest`.
  There is no `jvmTest` here — the 102 `@Test` are the Android host suite and stay one suite.

## Placement table — every one of the 34 production files (13 / 9 / 12)

Move files **unedited**. The R8-sensitive files (TLS trio, four wire codecs) are flagged; you may
relocate them but must not touch a byte of their content.

**commonMain — 13** (`src/commonMain/kotlin/com/transfer/flash/core/network/`):

| # | File (relative to package root) | Why commonMain |
|---|---|---|
| 1 | `FlashConnectionHealth.kt` | pure state model |
| 2 | `FlashConnectionState.kt` | pure state model |
| 3 | `FlashNetworkState.kt` | pure state model |
| 4 | `FlashNetwork.kt` | transport **interface** (coroutines only) |
| 5 | `FlashSession.kt` | transport **interface** (coroutines only) |
| 6 | `bridge/DiscoveryRouteBinder.kt` | pure; imports `FlashDiscoveredEndpoint` (commonMain in 08) |
| 7 | `resilience/ConnectionHealthAggregator.kt` | pure state machine |
| 8 | `resilience/HeartbeatPolicy.kt` | pure policy (injected clock) |
| 9 | `resilience/HeartbeatTracker.kt` | pure policy (injected clock) |
| 10 | `resilience/ReconnectPolicy.kt` | pure policy (injected `random01`) |
| 11 | `resilience/SessionHardeningPolicy.kt` | pure policy |
| 12 | `tcp/LanProbeMessages.kt` | **R8 wire** (text probe format) — move only |
| 13 | `tls/FlashPinVerifier.kt` | `fun interface`; network's own pinning contract |

**jvmAndAndroidMain — 9** (`src/jvmAndAndroidMain/kotlin/com/transfer/flash/core/network/`):

| # | File | Why this tier (JDK, not `android.*`) |
|---|---|---|
| 1 | `datachannel/DataChannelFraming.kt` | **R8 wire**; `java.nio`/`java.io` — move only |
| 2 | `resilience/BoundedSendQueue.kt` | JDK collections / `@Synchronized` |
| 3 | `resilience/ChaosNetworkHarness.kt` | JDK-only test-support harness (ships in main) |
| 4 | `resilience/ChaosSession.kt` | JDK-only |
| 5 | `tcp/LanProbeServer.kt` | `java.net.ServerSocket` |
| 6 | `tls/FlashTlsContextFactory.kt` | **R8**; `javax.net.ssl`/`java.security` — move only |
| 7 | `tls/SecureSocketUpgrader.kt` | **R8**; `javax.net.ssl` — move only |
| 8 | `tls/TofuX509TrustManager.kt` | **R8**; `javax.net.ssl`/`java.security` — move only |
| 9 | `ws/WebSocketCodec.kt` | **R8 wire**; `java.io`/`java.security` — move only |

**androidMain — 12** (`src/androidMain/kotlin/com/transfer/flash/core/network/`):

| # | File | Why pinned to Android |
|---|---|---|
| 1 | `DefaultFlashNetwork.kt` | `Context` / platform wiring |
| 2 | `resilience/AndroidNetworkWatcher.kt` | `ConnectivityManager` (Phase 15 seam) |
| 3 | `tcp/LanConnectionProbe.kt` | `ConnectivityManager` (Phase 15 seam) |
| 4 | `util/LocalNetworkAddresses.kt` | `ConnectivityManager` (Phase 15 seam) |
| 5 | `ws/WsFlashNetwork.kt` | `Context` |
| 6 | `ws/WsTransferClient.kt` | `Context`/`ConnectivityManager` (Phase 15 seam) |
| 7 | `datachannel/DataChannelClient.kt` | **only `android.util.Log`** |
| 8 | `datachannel/DataChannelServer.kt` | **only `android.util.Log`** |
| 9 | `tcp/LanSession.kt` | **only `android.util.Log`** |
| 10 | `ws/WsConnection.kt` | **only `android.util.Log`** |
| 11 | `ws/WsSession.kt` | **only `android.util.Log`** |
| 12 | `ws/WsTransferServer.kt` | **only `android.util.Log`** |

## The `android.util.Log` note — six files, do NOT introduce a log seam

Six androidMain files (rows 7–12 above) are on Android **only** because they call
`android.util.Log`. Nothing else in them touches `android.*`. It is tempting to define a tiny
`expect fun logD(tag, msg)` in commonMain with an `actual` per platform and pull these six down to
`jvmAndAndroidMain`. **Do not.** This is a move-only phase (like 07/08): introducing an
`expect`/`actual` log abstraction is a content rewrite across six files, it changes what R8 sees,
and it is exactly the kind of "small improvement" that turns a mechanical phase into a debugging
session. Leave them in androidMain. If desktop later needs these classes, Phase 15 builds desktop
transport against the same commonMain contracts and can carry its own logging — the seam is a
**later** decision, taken deliberately, not smuggled into a file-move.

## Phase 15 seam candidates (record, do not act)

Four androidMain files are pinned by real platform network services — `ConnectivityManager` /
`Context` for address enumeration, connectivity callbacks, and interface probing:

- `resilience/AndroidNetworkWatcher.kt`
- `util/LocalNetworkAddresses.kt`
- `tcp/LanConnectionProbe.kt`
- `ws/WsTransferClient.kt`

These are the natural `expect`/`actual` (or interface-in-commonMain) seams when **Phase 15**
builds the desktop transport (`java.net.NetworkInterface`-based address enumeration, no
`ConnectivityManager`). This phase only **names** them so Phase 15 does not have to re-discover
them. It does **not** create any seam now.

## Cross-module dependency placement (the tricky part of this phase)

Four project/library dependencies move to **different tiers**, and two are dead. Get this exactly
right — a dependency in the wrong tier either fails to resolve for `compileKotlinJvm` or silently
leaks an Android-only artifact into the shared surface.

| Dependency | Old scope | New tier + scope | Reason |
|---|---|---|---|
| `:core:common` | `api` | **commonMain `api`** | used by commonMain files (interfaces, models) |
| `libs.kotlinx.coroutines.core` | `api` | **commonMain `api`** | multiplatform; public `Flow`/`StateFlow` |
| `:core:discovery` | `implementation` | **commonMain `implementation`** | only `DiscoveryRouteBinder` (commonMain) uses it |
| `:core:security` | `implementation` | **jvmAndAndroidMain `implementation`** | **DEAD** (0 imports); parity tier; flag for cleanup |
| `libs.androidx.core.ktx` | `implementation` | **androidMain `implementation`** | **DEAD** (0 imports); flag for cleanup |
| `libs.androidx.lifecycle.runtime.ktx` | `implementation` | **androidMain `implementation`** | **DEAD** (0 imports); flag for cleanup |
| `libs.junit` | `testImplementation` | **androidHostTest `implementation`** | host unit tests |
| `libs.bouncycastle.pkix` | `testImplementation` | **androidHostTest `implementation`** | TEST-ONLY JVM cert-gen for TLS tests |

**Why `:core:common` and `coroutines.core` must be `api`, not `implementation`:** `FlashNetwork`
exposes `StateFlow<FlashNetworkState>` and returns `FlashResult`/`FlashDevice` in its public
signatures. Those types come from `:core:common` and `kotlinx-coroutines`; a consumer of
`core-network` must see them transitively or its own code won't compile. `api` was already correct
in the current build — preserve it, do not "tidy" it to `implementation`.

**Why the three dead deps are relocated, not deleted:** this is a move-only phase. Deleting a
dependency is a content decision with its own blast radius (it can change what the published POM
advertises). Relocate each to the tier it *would* live in if it were used (`:core:security` next to
the TLS code in jvmAndAndroidMain; the two `androidx` AARs in androidMain), and leave a
`// TODO(cleanup): grep-unused as of Phase 10` marker. A dedicated cleanup phase deletes them after
the migration is green.

## The target `core/network/build.gradle.kts` (full, paste-ready)

Fill the two `/* PASTE … */` regions from the Phase 06 log (`KMP_ANDROID_DSL_SNIPPET`,
`KMP_HOST_TEST_BLOCK`). Everything else is literal.

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "com.transfer.flash.core.network"
        compileSdk = 35
        minSdk = 24
        // PASTE KMP_HOST_TEST_BLOCK from the Phase 06 log (withHostTest { } enabling the
        // Android host unit-test compilation + task). core:network needs NO Android resources,
        // so do NOT add isIncludeAndroidResources here (unlike persistence/Phase 09).
        // PASTE consumer-proguard wiring: consumerProguardFiles("consumer-rules.pro")
    }

    jvm()   // plain jvm() — desktop JVM target. NEVER jvm("desktop").

    sourceSets {
        val jvmAndAndroidMain = create("jvmAndAndroidMain")
        jvmAndAndroidMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)

        getByName("commonMain").dependencies {
            api(project(":core:common"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":core:discovery"))   // only DiscoveryRouteBinder uses it
        }
        getByName("jvmAndAndroidMain").dependencies {
            // :core:security is grep-unused (0 imports). Relocated here for parity with the TLS
            // code that would use it. TODO(cleanup): grep-unused as of Phase 10 — a later phase
            // may delete this dependency outright.
            implementation(project(":core:security"))
        }
        getByName("androidMain").dependencies {
            // Both AARs are grep-unused (0 androidx imports). TODO(cleanup): grep-unused as of
            // Phase 10 — relocated, not deleted, under the move-only rule.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.bouncycastle.pkix)   // TEST-ONLY: self-signed certs for TLS tests
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            // Keep the root coordinate == PR #1's coordinate so existing Android consumers
            // resolving core-network keep working; the KMP plugin adds -android / -jvm variants.
            artifactId = artifactId.replace(project.name, "core-network")
        }
    }
}
```

**What changed vs. the old file, and why each change is safe:**

- `com.android.library` → `com.android.kotlin.multiplatform.library` + `kotlin.multiplatform`
  (AGP 9 requirement; `com.android.library` is incompatible with the KMP plugin).
- Deleted `buildTypes`/`singleVariant`/`compileOptions`/`testInstrumentationRunner` — none exist in
  the KMP Android DSL. Host tests are enabled by `withHostTest { }` (omitting it = 0 tests silently
  pass, the classic trap). `consumerProguardFiles` moves inside `androidLibrary { }`.
- No `isIncludeAndroidResources` — network has no `res/`/assets and no Robolectric-resource test.
- The publication block switches from a single hand-registered `release` publication to the KMP
  `withType<MavenPublication>().configureEach { artifactId = …replace(project.name, "core-network") }`
  form (the same rewrite every converted module uses; root coordinate stays `core-network`).

## Steps (do them in order; every Gradle command uses `--no-configuration-cache`)

### Step 1 — Record the baseline (paste the outputs into the log)

Before touching anything, capture the two numbers this phase must preserve. Run from repo root:

```bash
./gradlew :core:network:testDebugUnitTest --no-configuration-cache
```

Record the **exact `@Test` count** it runs. The verified baseline is **102 tests across 19
classes**; if your run reports a different number, STOP — the inventory drifted and the placement
table must be re-derived before moving files. Also capture the current publish coordinate:

```bash
./gradlew :core:network:publishToMavenLocal --no-configuration-cache
```

Confirm it emits `core-network` (root artifactId) today. This is the single coordinate the KMP
conversion must keep emitting (now with `-android`/`-jvm` variants added).

### Step 2 — Create the KMP source-set directories

KMP uses `kotlin/`, not `java/`. Create the three roots (production) plus the host-test root:

```bash
cd core/network/src
mkdir -p commonMain/kotlin/com/transfer/flash/core/network
mkdir -p jvmAndAndroidMain/kotlin/com/transfer/flash/core/network
mkdir -p androidMain/kotlin/com/transfer/flash/core/network
mkdir -p androidHostTest/kotlin/com/transfer/flash/core/network
```

Create the sub-package dirs as needed by the `git mv` targets below (`bridge/`, `resilience/`,
`tcp/`, `tls/`, `ws/`, `util/`, `datachannel/`). `git mv` will not create intermediate dirs on all
platforms, so `mkdir -p` each destination sub-package first, or use the per-file moves as written.

### Step 3 — Rewrite `core/network/build.gradle.kts`

Replace the file with the paste-ready target from the section above, filling the two `/* PASTE … */`
regions from the Phase 06 log. Do this **before** moving files so the IDE/Gradle sees the new source
sets when it re-syncs. Do not run a build yet — the files are still in `src/main` and won't be found
by the new source sets until Step 4.

### Step 4 — `git mv` the 34 production files (by explicit file list per set)

Use `git mv` (preserves history + stages the rename). Source root is
`core/network/src/main/java/com/transfer/flash/core/network/`; abbreviate it as `$SRC` and the three
destinations as `$COMMON`, `$JVMAND`, `$ANDROID`:

```bash
cd core/network
SRC=src/main/java/com/transfer/flash/core/network
COMMON=src/commonMain/kotlin/com/transfer/flash/core/network
JVMAND=src/jvmAndAndroidMain/kotlin/com/transfer/flash/core/network
ANDROID=src/androidMain/kotlin/com/transfer/flash/core/network
mkdir -p $COMMON/bridge $COMMON/resilience $COMMON/tcp $COMMON/tls
mkdir -p $JVMAND/datachannel $JVMAND/resilience $JVMAND/tcp $JVMAND/tls $JVMAND/ws
mkdir -p $ANDROID/datachannel $ANDROID/resilience $ANDROID/tcp $ANDROID/util $ANDROID/ws
```

**commonMain (13):**

```bash
git mv $SRC/FlashConnectionHealth.kt              $COMMON/FlashConnectionHealth.kt
git mv $SRC/FlashConnectionState.kt               $COMMON/FlashConnectionState.kt
git mv $SRC/FlashNetworkState.kt                  $COMMON/FlashNetworkState.kt
git mv $SRC/FlashNetwork.kt                       $COMMON/FlashNetwork.kt
git mv $SRC/FlashSession.kt                       $COMMON/FlashSession.kt
git mv $SRC/bridge/DiscoveryRouteBinder.kt        $COMMON/bridge/DiscoveryRouteBinder.kt
git mv $SRC/resilience/ConnectionHealthAggregator.kt $COMMON/resilience/ConnectionHealthAggregator.kt
git mv $SRC/resilience/HeartbeatPolicy.kt         $COMMON/resilience/HeartbeatPolicy.kt
git mv $SRC/resilience/HeartbeatTracker.kt        $COMMON/resilience/HeartbeatTracker.kt
git mv $SRC/resilience/ReconnectPolicy.kt         $COMMON/resilience/ReconnectPolicy.kt
git mv $SRC/resilience/SessionHardeningPolicy.kt  $COMMON/resilience/SessionHardeningPolicy.kt
git mv $SRC/tcp/LanProbeMessages.kt               $COMMON/tcp/LanProbeMessages.kt
git mv $SRC/tls/FlashPinVerifier.kt               $COMMON/tls/FlashPinVerifier.kt
```

**jvmAndAndroidMain (9):**

```bash
git mv $SRC/datachannel/DataChannelFraming.kt     $JVMAND/datachannel/DataChannelFraming.kt
git mv $SRC/resilience/BoundedSendQueue.kt        $JVMAND/resilience/BoundedSendQueue.kt
git mv $SRC/resilience/ChaosNetworkHarness.kt     $JVMAND/resilience/ChaosNetworkHarness.kt
git mv $SRC/resilience/ChaosSession.kt            $JVMAND/resilience/ChaosSession.kt
git mv $SRC/tcp/LanProbeServer.kt                 $JVMAND/tcp/LanProbeServer.kt
git mv $SRC/tls/FlashTlsContextFactory.kt         $JVMAND/tls/FlashTlsContextFactory.kt
git mv $SRC/tls/SecureSocketUpgrader.kt           $JVMAND/tls/SecureSocketUpgrader.kt
git mv $SRC/tls/TofuX509TrustManager.kt           $JVMAND/tls/TofuX509TrustManager.kt
git mv $SRC/ws/WebSocketCodec.kt                  $JVMAND/ws/WebSocketCodec.kt
```

**androidMain (12):**

```bash
git mv $SRC/DefaultFlashNetwork.kt                $ANDROID/DefaultFlashNetwork.kt
git mv $SRC/resilience/AndroidNetworkWatcher.kt   $ANDROID/resilience/AndroidNetworkWatcher.kt
git mv $SRC/tcp/LanConnectionProbe.kt             $ANDROID/tcp/LanConnectionProbe.kt
git mv $SRC/util/LocalNetworkAddresses.kt         $ANDROID/util/LocalNetworkAddresses.kt
git mv $SRC/ws/WsFlashNetwork.kt                  $ANDROID/ws/WsFlashNetwork.kt
git mv $SRC/ws/WsTransferClient.kt                $ANDROID/ws/WsTransferClient.kt
git mv $SRC/datachannel/DataChannelClient.kt      $ANDROID/datachannel/DataChannelClient.kt
git mv $SRC/datachannel/DataChannelServer.kt      $ANDROID/datachannel/DataChannelServer.kt
git mv $SRC/tcp/LanSession.kt                      $ANDROID/tcp/LanSession.kt
git mv $SRC/ws/WsConnection.kt                     $ANDROID/ws/WsConnection.kt
git mv $SRC/ws/WsSession.kt                         $ANDROID/ws/WsSession.kt
git mv $SRC/ws/WsTransferServer.kt                 $ANDROID/ws/WsTransferServer.kt
```

**Count check — the load-bearing assertion:**

```bash
git status --short | grep -c '^R'          # expect 34 renames staged (production only, so far)
find $SRC -name '*.kt' | wc -l             # expect 0 — src/main must be empty of .kt now
```

If `src/main` still has `.kt` files, you missed one — reconcile against the three lists (13+9+12=34)
before continuing. Do not proceed with a non-empty `src/main`.

### Step 5 — `git mv` the 20 test files to `androidHostTest`

All 20 test files (19 JUnit4 classes + the `tls/SoftwareCertMaker.kt` helper) go to
`androidHostTest`. They stay one suite; there is no `jvmTest` split in this phase. Move the whole
tree by sub-package (this preserves the package layout the 19 classes expect):

```bash
cd core/network
TSRC=src/test/java/com/transfer/flash/core/network
THOST=src/androidHostTest/kotlin/com/transfer/flash/core/network
mkdir -p $THOST
# move every .kt under the test package tree, preserving sub-package dirs:
git mv $TSRC $THOST/..    # moves the whole 'network' dir under androidHostTest/.../core/
```

If your Git refuses the directory move (some Windows Git builds do), fall back to per-subpackage
moves (`git mv $TSRC/tls $THOST/tls`, etc.), then move the root-level test `.kt`. After the move:

```bash
find src/test -name '*.kt' | wc -l                 # expect 0 — src/test emptied
find $THOST -name '*.kt' | wc -l                    # expect 20
git status --short | grep -c '^R'                   # expect 54 total renames (34 prod + 20 test)
```

`SoftwareCertMaker.kt` is a **helper, not a test class** — it must move with the others (the TLS
tests instantiate it). Do not drop it.

### Step 6 — Compile the shared surface for desktop JVM

This is the proof the phase exists for — the shared network half builds off Android:

```bash
./gradlew :core:network:compileKotlinJvm --no-configuration-cache
```

Must be **BUILD SUCCESSFUL**. If it fails:

- `Unresolved reference: FlashDiscoveredEndpoint` (or any `:core:common`/`:core:discovery` type in a
  commonMain file) ⇒ that upstream type is **not** commonMain — return to Preconditions #1 and fix
  the upstream module, do not move the file up to androidMain to "solve" it.
- `Unresolved reference: android` / `Log` in a jvmAndAndroidMain or commonMain file ⇒ a file was
  placed too low; it belongs in androidMain. Re-check the placement table.
- `Unresolved reference: javax`/`java.security` in a **commonMain** file ⇒ that file needs the JDK;
  move it to jvmAndAndroidMain.

### Step 7 — Run the Android host test suite; assert the baseline count

```bash
./gradlew :core:network:testAndroidHostTest --no-configuration-cache
```

(Use the exact `ANDROID_UNIT_TEST_TASK` recorded in the Phase 06 log if it differs.) Must be
**BUILD SUCCESSFUL running exactly the Step 1 baseline — 102 `@Test` across 19 classes**. A green
build running **0 tests** means `withHostTest { }` was omitted or the tests did not land in
`androidHostTest` — that is the silent-failure trap, not a pass. Compare the reported count to
Step 1 and paste both into the log.

### Step 8 — Publish to Maven Local; confirm the coordinate + variants

```bash
./gradlew :core:network:publishToMavenLocal --no-configuration-cache
```

Inspect `~/.m2/repository/<group>/`. Confirm the **root** `core-network` coordinate is still
emitted (matching Step 1), now alongside `core-network-android` and `core-network-jvm` variant
publications with their `.module` metadata. If only `core-network` (no `-jvm`) appears, the `jvm()`
target did not publish — verify `jvm()` is declared and you ran `publishToMavenLocal` (all
publications), not a single-target task.

### Step 9 — Prove the Android app still resolves and builds

```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

Must be **BUILD SUCCESSFUL**. `:app` consumes `core-network` via the root coordinate and must get
the `-android` variant transparently. A failure here means the Android variant's API surface
changed — it must not have (move-only). Re-check that no file's `package` or visibility changed.

## Verification gate — all four must be green (paste outputs into the log)

1. `:core:network:compileKotlinJvm` — **BUILD SUCCESSFUL** (shared surface builds off Android).
2. `:core:network:testAndroidHostTest` — **BUILD SUCCESSFUL, exactly 102 `@Test` / 19 classes**
   (equal to the Step 1 baseline; a 0-test "pass" is a failure).
3. `:core:network:publishToMavenLocal` — root `core-network` **plus** `-android` **and** `-jvm`
   variant publications with `.module` metadata.
4. `:app:assembleDebug` — **BUILD SUCCESSFUL** (Android consumer unregressed).

Any red ⇒ do not log PASS and do not proceed to Phase 11. Fix the owning cause (placement /
upstream commonMain / build DSL) and re-run.

## Do NOT

- **Do NOT edit file contents.** This is a move-only phase. The TLS trio
  (`FlashTlsContextFactory`, `SecureSocketUpgrader`, `TofuX509TrustManager`) and the four wire
  codecs (`LanProbeMessages`, `DataChannelFraming`, `WebSocketCodec`, plus the framing in the
  transport) are **R8-sensitive** — relocating them is allowed, changing a byte of their content is
  not (R8). No reformatting, no import reordering, no visibility tweaks.
- **Do NOT introduce an `expect`/`actual` log seam** for the six `android.util.Log`-only files.
  Leave them in androidMain (see the Log note). The seam, if ever wanted, is a deliberate later
  decision, not part of a file-move.
- **Do NOT create any Phase 15 seam now.** Record the four `ConnectivityManager`/`Context`
  candidates; do not abstract them. Desktop transport is Phase 15.
- **Do NOT delete the three dead dependencies** (`:core:security`, `androidx.core.ktx`,
  `androidx.lifecycle.runtime.ktx`). Relocate + flag `TODO(cleanup)`; a later phase deletes them.
- **Do NOT downgrade `:core:common` / `coroutines.core` from `api` to `implementation`.** They are
  in public signatures; `api` is correct and load-bearing for transitive consumers.
- **Do NOT use `jvm("desktop")`, typed source-set accessors, or `src/**/java/` roots.** Plain
  `jvm()`, `getByName(String)`, `src/**/kotlin/`.
- **Do NOT omit `withHostTest { }`** — omitting it produces a green build running 0 tests (the
  silent-failure trap). And do NOT add `isIncludeAndroidResources` — network has no Android
  resources and no Robolectric-resource test.
- **Do NOT change the `core-network` root coordinate.** PR #1's Android consumers resolve it; the
  prefix rewrite keeps it stable while adding the KMP variants.
- **Do NOT run mid-migration Gradle without `--no-configuration-cache`.**

## Completion checklist

- [ ] `build.gradle.kts` rewritten to the KMP form (plugins, `androidLibrary`, `jvm()`, three
      source sets wired with `getByName`, dependency tiers per the table, `withHostTest`, no
      `isIncludeAndroidResources`, KMP publication rewrite to `core-network`).
- [ ] 34 production files moved unedited: 13 commonMain / 9 jvmAndAndroidMain / 12 androidMain;
      `src/main` empty of `.kt`.
- [ ] 20 test files moved to `androidHostTest` (incl. `SoftwareCertMaker.kt`); `src/test` empty.
- [ ] `:core:network:compileKotlinJvm` green.
- [ ] `:core:network:testAndroidHostTest` green at **102 tests** (== baseline).
- [ ] `publishToMavenLocal` emits `core-network` + `-android` + `-jvm` with `.module`.
- [ ] `:app:assembleDebug` green.
- [ ] Three dead deps relocated + `TODO(cleanup)` flagged, not deleted.
- [ ] Four Phase-15 seam candidates recorded in the log; no seam created.
- [ ] Log entry appended to `docs/migration/logs/migration.md` (append-only) with pasted outputs.

## Rollback

Move-only + build-DSL change, so rollback is mechanical and total:

```bash
git restore --staged core/network
git checkout -- core/network
git clean -fd core/network/src/commonMain core/network/src/jvmAndAndroidMain \
               core/network/src/androidMain core/network/src/androidHostTest
```

This un-stages the renames, restores the original `build.gradle.kts` and `src/main`/`src/test`
trees, and removes the empty KMP source-set dirs. Because nothing was published to a remote and no
content changed, there is no downstream to unwind. If `publishToMavenLocal` ran, the stale
`~/.m2/repository/.../core-network*` entries are harmless (overwritten on the next real publish) but
may be deleted for cleanliness.

## Log entry (mandatory)

Append **one** entry to `docs/migration/logs/migration.md` (append-only, newest at the bottom —
never edit an earlier entry; never write PASS without pasted command output). Include:

- The Step 1 **baseline** (`testDebugUnitTest` count = 102/19 classes) and the current
  `publishToMavenLocal` coordinate (`core-network`), both with pasted output.
- Confirmation of the split moved: **13 commonMain / 9 jvmAndAndroidMain / 12 androidMain**
  production + **20 androidHostTest** (incl. `SoftwareCertMaker.kt`); `src/main` and `src/test`
  empty (paste the `find … | wc -l` = 0 and `git status --short | grep -c '^R'` = 54).
- The four verification-gate outputs pasted: `compileKotlinJvm` SUCCESS; `testAndroidHostTest`
  SUCCESS **at 102 tests** (state the number, not just "passed"); `publishToMavenLocal` emitting
  `core-network` + `-android` + `-jvm` (list the `.m2` dirs observed); `:app:assembleDebug` SUCCESS.
- The three **dead dependencies** relocated + flagged (`:core:security` → jvmAndAndroidMain;
  `androidx.core.ktx` + `androidx.lifecycle.runtime.ktx` → androidMain), explicitly noted as
  grep-unused and **not** deleted.
- The four **Phase-15 seam candidates** named (`AndroidNetworkWatcher`, `LocalNetworkAddresses`,
  `LanConnectionProbe`, `WsTransferClient`) for the desktop-transport phase, with a note that no
  seam was created here.
- The six `android.util.Log`-only files named, with a note that no log seam was introduced
  (move-only).
- The final statement: **`:core:network` is KMP; the shared transport surface (contracts +
  resilience policies + TLS/TOFU + wire codecs, 22 files) compiles for desktop JVM; the Android
  host suite is unregressed at 102 tests; the concrete Android transport stays androidMain and
  desktop transport is deferred to Phase 15.**
