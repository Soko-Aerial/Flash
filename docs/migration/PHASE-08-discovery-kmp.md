# Phase 08 — KMP conversion: `core:discovery`

**Blocked by:** Phase 06 (the pilot — it discovers and records the `KMP_*` facts this phase
copies). Depends only on `:core:common`, which Phase 06 already converted, so this phase can
run as soon as 06 is OPEN. It does **not** need Phase 07.
**Risk: MEDIUM.** No behaviour change, no wire-format change, no crypto. 16 production +
7 test files. The only ways to get this wrong are (a) misplacing a file across source sets
and (b) the Phase-06 silent-test-loss trap. Both are covered by explicit steps.
**Decisions:** none block this phase. `D6` (desktop discovery — JmDNS) is **Phase 14**, not
here; this phase only *creates the seam* (`FlashRadioTransport`) that Phase 14's desktop
transport will implement. Do not implement any desktop discovery here.

**Written for D1 = A** (the `commonMain → jvmAndAndroidMain → {androidMain, jvmMain}` model).
If `DECISIONS.md` records **D1 = B**, STOP: under B the two `jvmAndAndroidMain` files
(`CompositeDiscovery`, `FlashPeerGroupSession`) each need an `expect`/`actual` seam for their
`java.*` calls, which this phase does not describe. Confirm D1 = A before starting.

Under D1 = A this phase introduces **zero `expect`/`actual`** and **`jvmMain` gets zero
files** — every non-Android file already compiles for desktop from `commonMain` or
`jvmAndAndroidMain`. The desktop discovery *implementation* is Phase 14.

## What this phase is actually for

Convert `:core:discovery` from `com.android.library` to
`com.android.kotlin.multiplatform.library` + `jvm()`, moving its 16 source files into the
KMP source sets by the placement rule, and prove that:

1. the shared discovery contract (`FlashDiscovery`, `FlashRadioTransport`, the policies, the
   `TxtCodec` wire format) compiles for the desktop `jvm()` target, and
2. the Android NSD implementation (the 4 `nsd/` files) stays isolated in `androidMain`, and
3. Android still builds and every existing test still runs (same count as baseline).

The strategic payoff: `FlashRadioTransport` becomes a `commonMain` interface. In Phase 14 a
desktop JmDNS transport is written as a `jvmMain` `actual`-free implementation of that same
interface — no engine change needed, because the engine already talks to the interface, not
to `NsdManager`. This phase's job is to expose that seam cleanly, nothing more.

## Why `core:discovery` is a safe early conversion

Verified against the module (grep with R11 exclusions, and reading `build.gradle.kts`):

- **Dependency leaf.** Its only project dependency is `api(project(":core:common"))`, which
  Phase 06 already converted. Nothing here waits on Phase 07. (Same reason it sits at 08,
  next to security.)
- **10 of 16 production files are already pure Kotlin** — models, policies, the directory,
  the transport interface, and the `TxtCodec` wire format — so they go straight to
  `commonMain`.
- **Only 2 files touch `java.*`** (`java.util.Locale` / `System.currentTimeMillis` in
  `CompositeDiscovery`, `java.util.concurrent.ConcurrentHashMap` in `FlashPeerGroupSession`)
  → `jvmAndAndroidMain`, unchanged, no seam.
- **All `android.*` is confined to 4 files under `nsd/`** — the NSD adapter. They already
  form a natural `androidMain` cluster.
- **No `AndroidManifest.xml`, no `res/`, no `assets/`, no declared permissions** under
  `core/discovery/src` (verified: glob returns none). Nothing Android-resource-bound to
  migrate, so the Compose-resources gotcha and the manifest-merge gotcha cannot bite.
- **No KSP, no Room, no Hilt, no Compose.** Pure plugin swap + source move.

## A finding you must act on first — two dead dependencies

`core/discovery/build.gradle.kts` declares:

```kotlin
implementation(libs.androidx.core.ktx)
implementation(libs.androidx.lifecycle.runtime.ktx)
```

`core/discovery` contains **no `androidx.*` reference at all**. Verify:

```bash
grep -rn --include=*.kt "androidx" core/discovery/src
```

Expected: **no output.** If confirmed, both dependencies are unused. **Delete them** (do not
relocate them to `androidMain` — relocating a dead dependency just moves noise). Record that
you removed them. This is the discovery analogue of the `androidx.core.ktx` removal Phase 06
did on `core:common`, and it is what makes the module's dependency list honest.

> Divergence from Phase 07: in `core:security` the two `androidx.*` deps were **relocated**
> to `androidMain` because that module's `androidMain` files use them. Here they are
> **provably unused**, so they are **deleted**. Do not blindly copy Phase 07's "relocate"
> instruction — the grep above decides.

## Placement table — all 16 production files

Built by grepping every production file for `^import android.`, `^import androidx.`,
`^import java.`/`javax.`, `System.`, `synchronized`, `@Volatile`, and reading each file's
role. **Re-run the two greps in Step 1 to confirm before you move anything** — do not trust
this table blindly, verify it.

Base path: `core/discovery/src/main/java/com/transfer/flash/core/discovery/`

### commonMain — 10 (pure Kotlin; stdlib + coroutines + `:core:common` only)

| File | Role | Notes |
|---|---|---|
| `FlashDiscovery.kt` | Public facade interface | the API consumers call |
| `FlashDiscoveryState.kt` | State model | pure |
| `FlashDiscoveredEndpoint.kt` | Endpoint model | pure |
| `core/FlashDiscoveryMode.kt` | Mode enum | pure |
| `core/DiscoveryModePolicy.kt` | Mode-selection policy | pure logic |
| `core/DiscoveryRetryPolicy.kt` | Retry/backoff policy | pure logic |
| `core/TxtCodec.kt` | **WIRE FORMAT (R8)** | TXT record codec; keys `device_id/name/model/proto/caps/fp8`. Move-only, do NOT edit |
| `core/FlashRadioTransport.kt` | **THE SEAM (see below)** | defines `FlashRadioTransport` + `FlashAdvertisedIdentity` + `FlashTransportEvent` |
| `core/EndpointDirectory.kt` | Directory interface | pure |
| `core/StandardEndpointDirectory.kt` | Directory impl | pure Kotlin — verify no `java.*` crept in (Step 1) |

### jvmAndAndroidMain — 2 (`java.*`, shared by both JVM targets, no seam under D1 = A)

| File | Triggering symbol (verified file:line) | Role |
|---|---|---|
| `core/CompositeDiscovery.kt` | `import java.util.Locale` (:18) + `System.currentTimeMillis()` (:85) | fan-in over multiple transports |
| `group/FlashPeerGroupSession.kt` | `import java.util.concurrent.ConcurrentHashMap` (:18) | peer-group session state |

### androidMain — 4 (all under `nsd/`; genuine `android.*`)

| File | Triggering `android.*` imports (verified) | Role |
|---|---|---|
| `nsd/NsdTransport.kt` | `android.content.Context`, `android.net.nsd.NsdManager`, `android.net.nsd.NsdServiceInfo`, `android.net.wifi.WifiManager`, `android.util.Log` | **the production `FlashRadioTransport` impl** (~999 lines, `@file:OptIn(FlashInternalApi::class)`); contains the nested `NsdManagerBridge` Android isolation point |
| `nsd/NsdResolveQueue.kt` | `android.net.nsd.NsdManager`, `android.net.nsd.NsdServiceInfo`, `android.util.Log` | serializes NSD resolve calls (API constraint) |
| `nsd/NsdApiLevel.kt` | `android.os.Build` | API-level gating helper |
| `nsd/NsdFlashDiscovery.kt` | `android.content.Context`, `android.net.nsd.NsdManager`, `android.net.nsd.NsdServiceInfo`, `android.net.wifi.WifiManager`, `android.util.Log` | **legacy standalone, currently unused** — see "Do NOT delete it here" below |

Also present in the two shared/Android `nsd` files: `java.nio.charset.StandardCharsets`
(`NsdTransport.kt:39`, `NsdFlashDiscovery.kt:21`). These are `androidMain` regardless, so the
`java.*` import is irrelevant to their placement.

## The `FlashRadioTransport` seam — the whole point of the module split

`core/FlashRadioTransport.kt` is a **`commonMain` interface**. Its shape (verify by reading
the file — do not edit it):

- `transportName`
- `events: Flow<FlashTransportEvent>`
- `startAdvertising(...)`, `startBrowsing(...)`, `stop()`, `setMode(...)`

`NsdTransport` (androidMain) implements it using `NsdManager`. In **Phase 14** a desktop
JmDNS-backed transport is written in `jvmMain` implementing the **same** interface. Because
the interface and its event types (`FlashTransportEvent`, `FlashAdvertisedIdentity`) live in
`commonMain`, both implementations satisfy the same contract and the discovery facade
(`FlashDiscovery`) and the engine never learn which platform they are on.

**This phase must not add any desktop implementation.** It only moves `FlashRadioTransport`
(and its event/identity types) to `commonMain` so Phase 14 has something to implement. If you
find yourself writing a `jvmMain` file in this phase, stop — that is Phase 14's job (R1).

The `NsdManagerBridge` nested inside `NsdTransport` is the Android-only isolation point that
made `NsdTransportLogicTest` runnable on a plain JVM (it passes `context = null` + a
`FakeBridge`). Leave that structure intact; it is why the Android transport's *logic* is
testable without a device, and Phase 14 mirrors the pattern for JmDNS.

## The `android.util.Log` question — do NOT migrate it here

Three `nsd/` files import `android.util.Log` (`NsdTransport`, `NsdResolveQueue`,
`NsdFlashDiscovery`). All three are `androidMain`, where `android.*` is fully available, so
their `Log` calls are **correct as-is** and require no change for KMP placement. Do **not**
route them through `FlashLog` in this phase — that would be a Phase 03 logging change, and
R1 forbids "also fixing" things outside the phase. If the project intended zero direct
`android.util.Log` anywhere, record it under **Known issues** as a Phase 03 gap and move on.

## `NsdFlashDiscovery.kt` — legacy, unused, but do NOT delete it here

The inventory flags `nsd/NsdFlashDiscovery.kt` as a legacy standalone discovery path that is
currently unused. Deleting dead code is a **Phase 02 (delete-wslegacy) / hygiene** concern,
not a KMP-placement concern. Under R1, this phase **moves it to `androidMain` unchanged**. If
you want it gone, note it under **Known issues**; do not delete it in this commit (a deletion
mixed into a source-set migration makes `git revert` ambiguous — R4).

## Build script rewrite — `core/discovery/build.gradle.kts`

Use the **exact** DSL shapes Phase 06 recorded (`KMP_ANDROID_DSL_SNIPPET`,
`KMP_HOST_TEST_BLOCK`, `KMP_JVM_TARGET_DSL`, `KMP_LOCAL_DEP_SELECTION`). Do not re-derive
them. The current file (read above) is the standard Android-library template; replace it with:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // ADR-023. Must stay. Proven still enforced in Verification.
    explicitApi()

    // ---- Replace with KMP_ANDROID_DSL_SNIPPET from the Phase 06 log. ----
    // If Phase 06 recorded Shape 1, this is a TOP-LEVEL `androidLibrary { }` block
    // outside `kotlin { }`; keep the contents identical.
    android {
        namespace = "com.transfer.flash.core.discovery"
        compileSdk = 35
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`.
        consumerProguardFiles.add(file("consumer-rules.pro"))

        // Was `defaultConfig { testInstrumentationRunner = ... }`. No src/androidTest
        // exists, but keep the template for consistency with Phase 06/07.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        // ***** Omit this and all 7 test files stop compiling/running while the build
        // still reports SUCCESS. Step 7 exists to catch that. *****
        withHostTest { }
    }

    // Desktop target. Plain jvm(). Do NOT write jvm("desktop").
    jvm()

    sourceSets {
        // D1 = A intermediate set. Use getByName(String), not typed accessors.
        val jvmAndAndroidMain = create("jvmAndAndroidMain")
        jvmAndAndroidMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)

        // Public Flow/StateFlow return types → coroutines stays `api`, in commonMain.
        getByName("commonMain").dependencies {
            api(project(":core:common"))
            api(libs.kotlinx.coroutines.core)
        }

        // Tests stay JUnit 4 in the Android host-test tier, UNMODIFIED (see Step 5).
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        // Keep the published root coordinate `core-discovery` (PR #1 set it). The KMP
        // default derives from the Gradle project name `discovery`, so rewrite the prefix.
        artifactId = artifactId.replace("discovery", "core-discovery")
    }
}
```

Deletions from the old file, and why (mirror Phase 06 §4a):

- `alias(libs.plugins.android.library)` → replaced (incompatible with KMP under AGP 9).
- `buildTypes { release { ... } }` → **deleted** (no build variants exist).
- `compileOptions { VERSION_11 }` → replaced by `KMP_JVM_TARGET_DSL` if Phase 06 kept it,
  else dropped to the plugin default.
- `publishing { singleVariant("release") { withSourcesJar() } }` → **deleted** (no variants).
- `register<MavenPublication>("release") { … from(components["release"]) }` → **deleted**;
  KMP creates its own publications, renamed by the `configureEach` block above.
- `implementation(libs.androidx.core.ktx)` + `implementation(libs.androidx.lifecycle.runtime.ktx)`
  → **deleted** (unused; verified in the finding above).
- `kotlin { explicitApi() }` (was at the file's bottom) → folded into the `kotlin { }` block.

`core:discovery` has **no project dependencies other than `:core:common`**, which after
Phase 06 is itself a KMP module, so the `localDependencySelection` single-variant issue may
not arise. If `:app:assembleDebug` (Step 9) fails with "cannot choose between debug/release",
apply `KMP_LOCAL_DEP_SELECTION` from the Phase 06 log inside the `android { }` block.

## Steps

### Step 1 — Preconditions. Verify, do not assume.

Confirm **Phase 06 is OPEN** in `logs/migration.md` and that D1 = A in `DECISIONS.md`. If
D1 is `_pending_`, STOP — you cannot pick it (DECISIONS.md forbids the agent choosing D1).

Pull these values from the Phase 06 log and keep them in front of you:

```
KMP_ANDROID_DSL_SNIPPET, KMP_HOST_TEST_BLOCK, KMP_JVM_TARGET_DSL,
KMP_LOCAL_DEP_SELECTION, ANDROID_UNIT_TEST_TASK
```

Then verify the module's current shape (R11: these paths already exclude `media-downloader-main/`,
`build/`, `docs/`):

```bash
grep -rn --include=*.kt -E "^import android\." core/discovery/src/main
```
Expected: matches in **exactly** these four files — `nsd/NsdTransport.kt`,
`nsd/NsdFlashDiscovery.kt`, `nsd/NsdResolveQueue.kt`, `nsd/NsdApiLevel.kt`. If any **other**
file imports `android.*`, the placement table is stale — stop and re-classify that file.

```bash
grep -rn --include=*.kt "androidx" core/discovery/src
```
Expected: **no output** (confirms the two dead deps can be deleted).

```bash
grep -rn --include=*.kt -E "^import java\.|^import javax\.|System\.currentTimeMillis" core/discovery/src/main
```
Expected: `CompositeDiscovery.kt` (Locale + currentTimeMillis), `FlashPeerGroupSession.kt`
(ConcurrentHashMap), and the two `nsd` files (`StandardCharsets`). Any pure-Kotlin
`commonMain` file appearing here (especially `StandardEndpointDirectory.kt`) must be
**demoted to `jvmAndAndroidMain`** — the transitive rule (CONVENTIONS R2/R5). Record it.

```bash
git status --short
```
Must be clean. This phase does `git mv` on 23 files; do not mix with other work.

### Step 2 — Record the test baseline

```bash
./gradlew :core:discovery:testDebugUnitTest --no-configuration-cache
```

Open `core/discovery/build/reports/tests/testDebugUnitTest/index.html` and record the
**exact test count** as `DISCOVERY_TEST_BASELINE`. (The inventory counts ~80 `@Test` methods
across 7 files; the report's number is the one that matters, not the file count.) Step 7
must reproduce this number after conversion. If this task fails to run *before* you touch
anything, stop — you cannot prove non-regression against a baseline you never captured.

### Step 3 — Rewrite `core/discovery/build.gradle.kts`

Replace the file with the target script in "Build script rewrite" above, substituting the
Phase 06 `KMP_*` snippets. Do not touch any other module's build file (R4). Leave
`consumer-rules.pro` and `proguard-rules.pro` at the module root untouched.

### Step 4 — Move production sources into KMP source sets (`git mv`)

Run from the repo root in bash. `git mv` (never copy+delete) so history and `git status`
stay legible. Package path is unchanged, so **no import in any other module changes**.

```bash
P=com/transfer/flash/core/discovery
SRC=core/discovery/src/main/java/$P
CM=core/discovery/src/commonMain/kotlin/$P
JA=core/discovery/src/jvmAndAndroidMain/kotlin/$P
AM=core/discovery/src/androidMain/kotlin/$P
```

**Group 1 — 10 pure files → `commonMain`:**

```bash
mkdir -p "$CM/core"
git mv "$SRC/FlashDiscovery.kt"                 "$CM/"
git mv "$SRC/FlashDiscoveryState.kt"            "$CM/"
git mv "$SRC/FlashDiscoveredEndpoint.kt"        "$CM/"
git mv "$SRC/core/FlashDiscoveryMode.kt"        "$CM/core/"
git mv "$SRC/core/DiscoveryModePolicy.kt"       "$CM/core/"
git mv "$SRC/core/DiscoveryRetryPolicy.kt"      "$CM/core/"
git mv "$SRC/core/TxtCodec.kt"                  "$CM/core/"
git mv "$SRC/core/FlashRadioTransport.kt"       "$CM/core/"
git mv "$SRC/core/EndpointDirectory.kt"         "$CM/core/"
git mv "$SRC/core/StandardEndpointDirectory.kt" "$CM/core/"
```

**Group 2 — 2 `java.*` files → `jvmAndAndroidMain`:**

```bash
mkdir -p "$JA/core" "$JA/group"
git mv "$SRC/core/CompositeDiscovery.kt"     "$JA/core/"
git mv "$SRC/group/FlashPeerGroupSession.kt" "$JA/group/"
```

**Group 3 — 4 `android.*` files → `androidMain`:**

```bash
mkdir -p "$AM/nsd"
git mv "$SRC/nsd/NsdTransport.kt"      "$AM/nsd/"
git mv "$SRC/nsd/NsdResolveQueue.kt"   "$AM/nsd/"
git mv "$SRC/nsd/NsdApiLevel.kt"       "$AM/nsd/"
git mv "$SRC/nsd/NsdFlashDiscovery.kt" "$AM/nsd/"
```

Confirm the old tree is empty, then remove it:

```bash
find core/discovery/src/main -type f
```
Expected: **no output**. If anything remains, it was not in the table — stop and classify it.

### Step 5 — Move the 7 test files into `androidHostTest` (`git mv`, unmodified)

All 7 tests are JUnit 4 and run on the JVM today. They move to the Android **host-test**
tier (`androidHostTest` — the KMP name for the old `testDebugUnitTest` tier), **byte-for-byte
unchanged**: no `import` rewrite, no package change, no assertion touched (R1 — this phase is
a source-set move, not a test rewrite). The package path is identical, so nothing else in the
repo references them by a path that changes.

> Why `androidHostTest` and not `commonTest`: promoting these to `commonTest` would force them
> to compile for **both** the jvm and android host targets, and several of them
> (`NsdTransportLogicTest`, `CompositeDiscoveryTest`) exercise types that live in
> `jvmAndAndroidMain`/`androidMain`, which `commonTest` cannot see. Keeping them in
> `androidHostTest` reproduces the exact pre-migration compilation scope, so the count is
> apples-to-apples against `DISCOVERY_TEST_BASELINE`. Moving tests to `commonTest` is a
> **later, opt-in** exercise, explicitly out of scope here (R1).

```bash
TST=core/discovery/src/test/java/$P
HT=core/discovery/src/androidHostTest/kotlin/$P
mkdir -p "$HT/core" "$HT/group" "$HT/nsd"

git mv "$TST/FlashDiscoveryModelTest.kt"          "$HT/"
git mv "$TST/core/DiscoveryRetryPolicyTest.kt"    "$HT/core/"
git mv "$TST/core/StandardEndpointDirectoryTest.kt" "$HT/core/"
git mv "$TST/core/TxtCodecTest.kt"                "$HT/core/"
git mv "$TST/core/CompositeDiscoveryTest.kt"      "$HT/core/"
git mv "$TST/group/FlashPeerGroupSessionTest.kt"  "$HT/group/"
git mv "$TST/nsd/NsdTransportLogicTest.kt"        "$HT/nsd/"
```

Confirm the old test tree is empty:

```bash
find core/discovery/src/test -type f
```
Expected: **no output.** If a file remains, it was not in the 7-file list — a test was added
since the inventory. Classify it (it is almost certainly `androidHostTest` too) and move it,
then note the addition in the log; do not leave it behind (silent-test-loss trap #2).

Sanity-check the full new tree before building:

```bash
find core/discovery/src -type f -name '*.kt' | sort
```
Expected: **23 files** — 10 under `commonMain/…`, 2 under `jvmAndAndroidMain/…`, 4 under
`androidMain/…/nsd`, 7 under `androidHostTest/…`. Any other count means a `git mv` was missed.

### Step 6 — Compile the desktop target (proves the seam crosses the boundary)

```bash
./gradlew :core:discovery:compileKotlinJvm --no-configuration-cache
```

This is the load-bearing check of the whole phase: it compiles `commonMain` +
`jvmAndAndroidMain` + `jvmMain` (which is empty) for the desktop `jvm()` target, with **no
Android SDK on the classpath**. If it succeeds, `FlashDiscovery`, `FlashRadioTransport`, the
policies and `TxtCodec` are all genuinely platform-neutral and Phase 14 has a `commonMain`
interface to implement.

If it **fails** with an unresolved `android.*` symbol, a `commonMain`/`jvmAndAndroidMain` file
still references Android — re-run the Step 1 `android.*` grep against the moved files and push
the offender to `androidMain` (and, if the engine needs it cross-platform, that is a seam for a
later phase — do **not** invent an `expect`/`actual` here; record it under Known issues). If it
fails with an unresolved `java.*` symbol in a `commonMain` file, that file was mis-placed —
demote it to `jvmAndAndroidMain` (the transitive rule) and re-run.

### Step 7 — Run the Android host tests; the count MUST equal the baseline

```bash
./gradlew ANDROID_UNIT_TEST_TASK --no-configuration-cache
```
Substitute the literal task string recorded as `ANDROID_UNIT_TEST_TASK` in the Phase 06 log
(e.g. `:core:discovery:testHost` or `:core:discovery:testAndroidHostTest` — Phase 06 recorded
which one this AGP build actually exposes; do not guess between them).

Open the generated report (`core/discovery/build/reports/tests/**/index.html`) and confirm the
test count **equals `DISCOVERY_TEST_BASELINE`** from Step 2. This is the single most important
number in the phase.

- **count == baseline** → good.
- **count == 0 while BUILD SUCCESSFUL** → the `withHostTest { }` block is missing from the
  build script (silent-failure trap #1). Add it (Step 3) and re-run. A green build that ran
  zero tests is the failure mode this step exists to catch — never record it as PASS (R9).
- **count < baseline** → some tests did not migrate into `androidHostTest`. Re-check Step 5's
  empty-tree assertion and the 23-file count.
- **any test now FAILS** → a real regression. The move is content-preserving, so a failure
  means placement changed a type's visibility or a dependency edge — do not "fix" the test;
  fix the placement.

### Step 8 — Verify the published coordinate is still `core-discovery`

```bash
./gradlew :core:discovery:publishToMavenLocal --no-configuration-cache
```

Then confirm the KMP publication set landed under the group path in `~/.m2/repository/…`:

- root umbrella `core-discovery/<v>/` with `.module` + `.pom`,
- `core-discovery-android/<v>/`, and
- `core-discovery-jvm/<v>/`.

The **root artifactId must read `core-discovery`, not `discovery`** — that is what the
`artifactId.replace("discovery", "core-discovery")` block in the build script guarantees, and
what keeps PR #1's existing consumers resolving. If the root coordinate is bare `discovery`,
the `publishing { }` rewrite block is missing or wrong — fix it before moving on (Phase 24
depends on every module carrying the `core-` prefix). Full cross-platform resolution is
Phase 24's job; here you are only proving the coordinate name and that a `-jvm` variant now
exists at all.

### Step 9 — Prove Android is not regressed

```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

Must succeed. `:app` consumes `:core:discovery`'s **Android** variant through the unchanged
root coordinate/project dependency; a green `assembleDebug` proves the NSD implementation and
its `android.*` usages still resolve from `androidMain` and that the KMP conversion is
invisible to the app. If it fails with "cannot choose between debug/release" on the
`:core:discovery` dependency, apply `KMP_LOCAL_DEP_SELECTION` from the Phase 06 log inside the
`android { }` block and re-run.

## Do NOT

- **Do NOT write any `jvmMain` file.** Under D1 = A this phase adds zero desktop code; the
  desktop JmDNS transport is Phase 14 (R1). An empty `jvmMain` source set is correct and
  expected.
- **Do NOT add any `expect`/`actual`.** Every non-Android file already compiles for `jvm()`
  from `commonMain`/`jvmAndAndroidMain`. If you feel the urge to write `expect`, you have
  mis-placed a file — move it, don't seam it.
- **Do NOT edit `core/TxtCodec.kt`** or any TXT-record key/format. It is the on-wire discovery
  contract (R8); move-only. Changing a key (`device_id/name/model/proto/caps/fp8`) silently
  breaks cross-platform discovery in Phase 16.
- **Do NOT delete `nsd/NsdFlashDiscovery.kt`** even though it is unused. Deleting dead code is
  a hygiene/Phase-02 concern; mixing a delete into a source-set move makes `git revert`
  ambiguous (R4). Move it to `androidMain` unchanged; note it under Known issues if you want it
  gone later.
- **Do NOT route `android.util.Log` through `FlashLog`.** The three `nsd/` files are
  `androidMain`; their `Log` calls are valid there. That refactor is Phase 03's, not this
  one's (R1).
- **Do NOT relocate the two dead `androidx.*` deps to `androidMain` — delete them.** They are
  provably unused (Step 1 grep). This diverges from Phase 07 deliberately; the grep decides,
  not Phase 07's precedent.
- **Do NOT modify any other module's `build.gradle.kts`** (R4). `:core:discovery` is a
  dependency leaf below `:app`; nothing upstream needs editing.
- **Do NOT rewrite the tests** into `commonTest`, kotlin-test, or a different assertion style.
  They move unchanged to `androidHostTest` (R1); the baseline count comparison is only valid
  if the tests are byte-identical.

## Completion checklist — every box or the phase is not done

- [ ] Phase 06 is OPEN and D1 = A, both **verified in the files** (not assumed).
- [ ] `DISCOVERY_TEST_BASELINE` recorded from a green pre-migration `testDebugUnitTest` run.
- [ ] `build.gradle.kts` rewritten to the KMP form; both dead `androidx.*` deps **deleted**;
      `explicitApi()` retained; root artifactId rewrite to `core-discovery` present.
- [ ] 16 production files `git mv`'d: 10 → `commonMain`, 2 → `jvmAndAndroidMain`, 4 →
      `androidMain/nsd`; `find core/discovery/src/main` returns nothing.
- [ ] 7 test files `git mv`'d unchanged → `androidHostTest`; `find core/discovery/src/test`
      returns nothing; full tree is exactly 23 `.kt` files.
- [ ] `:core:discovery:compileKotlinJvm` **SUCCESS** (the seam crosses to desktop).
- [ ] `ANDROID_UNIT_TEST_TASK` run: test count **== `DISCOVERY_TEST_BASELINE`**, zero
      failures (not "SUCCESS with 0 tests").
- [ ] `publishToMavenLocal`: root coordinate is **`core-discovery`** with a `.module` file and
      a `-jvm` variant present.
- [ ] `:app:assembleDebug` **SUCCESS** (Android unregressed).
- [ ] No `jvmMain` file, no `expect`/`actual`, no edit to `TxtCodec.kt` or any test.
- [ ] Log entry appended to `logs/migration.md` with pasted command output (R9).

**Every box checked ⇒ record Phase 08 OPEN in `logs/migration.md`. Any box unchecked ⇒ the
phase is not done; do not open it.**

## Rollback

This phase is one commit of `git mv`s + one build-script rewrite, no content edits, so
rollback is clean and total:

```bash
git revert <this-phase-commit>
```

Because every file moved with `git mv` (history preserved) and no source content changed, the
revert restores the exact pre-phase tree — the `com.android.library` build script, the
`src/main/java` + `src/test/java` layout, and the two `androidx.*` deps. Nothing downstream
consumed a new coordinate (Phase 24 has not run), so no published artifact is affected. If only
the build script is wrong but the moves are fine, fix the script alone rather than reverting
the whole phase — the moves are the expensive part and they are independent of the script.

## Log entry (mandatory)

Append one entry to `docs/migration/logs/migration.md` (append-only, newest at the bottom;
never edit an earlier entry — R1/R9), using `TEMPLATE-phase-log.md`. It must contain:

- **Commit** hash of this phase.
- **Decisions:** D1 = A confirmed (this phase does not resolve any pending decision).
- **Change:** `:core:discovery` converted to `com.android.kotlin.multiplatform.library` +
  `jvm()`; 16 sources placed 10/2/4 across `commonMain`/`jvmAndAndroidMain`/`androidMain`;
  `FlashRadioTransport` seam now in `commonMain` for Phase 14; two dead `androidx.*` deps
  deleted.
- **Files changed:** the 23 `git mv`s (old → new path) + `build.gradle.kts`.
- **Verification:** the exact commands from Steps 6–9 with **pasted output** — the
  `compileKotlinJvm` result, the host-test **count vs `DISCOVERY_TEST_BASELINE`** (paste both
  numbers), the `publishToMavenLocal` coordinate tree showing `core-discovery` +
  `core-discovery-jvm` + `.module`, and the `:app:assembleDebug` result. Never write PASS
  without the pasted number (R9).
- **Deviations:** the `androidx.*` **deletion** (vs Phase 07's relocation) and why (grep-proven
  unused).
- **Known issues:** `nsd/NsdFlashDiscovery.kt` is dead code kept in `androidMain` (deferred
  hygiene); `android.util.Log` in the three `nsd/` files not yet routed through `FlashLog`
  (Phase 03 gap). Both intentionally out of scope (R1).
- **Next step:** Phase 10 (network) — or note that Phase 09 (persistence) may proceed in
  parallel since both depend only on the now-converted `:core:common`/Phase 06.