# Phase 12 — KMP conversion: `core:engine`

**Blocked by:** Phases 06 (pilot), 07 (security), 08 (discovery), 09 (persistence), 10 (network),
11 (transfer + messaging).
**D1 must be `A`.** Written for **D1 = A** (`jvmAndAndroidMain`).
**D5 must be `A`.** Written for **D5 = A** (desktop runs without persistence at first).
**Risk: HIGH** — the composition root is the first module with **Android resources** (`flash_bolt.xml`),
making it the first module where `androidResources { enable = true }` is required. It also has the
widest dependency fan-out (`api` on all seven `:core:*` modules), so a misplaced dependency tier
breaks the `jvm()` compile. Under D1 = A and D5 = A this is a **pure file-move with zero content
edits** — no `expect`/`actual`, no rewrites. The work is in *placement* and *androidResources
opt-in*.

> ⚠️ **The `FlashEngine.kt` placement was corrected mid-grounding.** Earlier analysis assumed
> `FlashEngine.kt` could be `jvmAndAndroidMain` because it only imports `java.io.Closeable` and
> commonMain module types. **That is wrong.** `FlashEngine.kt` exposes `settings: FlashSettingsDataStore`
> in its public interface — a concrete class that lives in `androidMain` (Phase 09). Therefore
> `FlashEngine.kt` must be **androidMain** so the `jvm()` target never sees `FlashSettingsDataStore`
> on its compile classpath. This is the same pinning rule as `RealFlashChatRepository` in Phase 11:
> if a file exposes an androidMain-only type in its public signature, it must be androidMain.

## What this phase is actually for

Convert `:core:engine` from `com.android.library` to the KMP plugin with an Android target, a
desktop `jvm()` target, and **Android resources enabled** (the first module to need this), moving
files into source sets **unedited**. When done:

- `:core:engine:compileKotlinJvm` compiles — proving the **engine contract** (`FlashEngine` interface)
  builds for desktop. The desktop target sees only `AutoConnectGate.kt` (commonMain) and nothing
  else — the concrete wiring (`Flash.kt`, `FlashEngine.kt`, `RoomTransferStore.kt`,
  `KeystorePassphraseProvider.kt`) stays androidMain exactly as the Android application needs it.
- The Android host test still runs and passes **at the same count** — **1 `@Test`**.
- The module still publishes as `core-engine`; `:app:assembleDebug` still resolves it.
- **Resources are namespaced:** `flash_bolt.xml` moves to `src/androidMain/res/drawable/` and the
  `androidResources` block is enabled, preserving the published AAR's `@drawable/flash_bolt` for
  Android consumers.

**What this phase does NOT do:** it does not give desktop a *runnable* engine. `Flash.kt` (the
composition root) is fundamentally Android-bound (`Context`, `ContentResolver`, `AndroidKeyStore`,
`Room`, `DataStore`, `AndroidPreferencesIdentityStore`, `NsdTransport`, `WsFlashNetwork`). Desktop
gets no `Flash.create()` equivalent yet. The `jvm()` target simply proves the shared contract
compile; a desktop-specific engine adapter is built later when the first desktop headless integration
phase wires it.

## Why `core:engine` splits 1 / 0 / 4

The split is driven by the `FlashSettingsDataStore` pin (see ⚠️ above) and the fact that all four
non-commonMain files are fundamentally Android-bound.

- **commonMain (1):** `AutoConnectGate.kt` — pure stdlib types (`HashMap`/`HashSet`/`@Synchronized`),
  zero imports beyond `kotlin.*`. The only file in the engine that can compile for any target.
- **jvmAndAndroidMain (0):** none. Unlike `:core:transfer` (14 JDK-backed files that shared a
  middle tier), the engine's files are either pure stdlib (commonMain) or deeply Android-bound
  (androidMain). No file lands in the middle tier.
- **androidMain (4):** `Flash.kt` (33 KB composition root, `android.content.Context`/`android.util.Log`/
  `android.net.Uri`/`ContentResolver`), `FlashEngine.kt` (pinned by `FlashSettingsDataStore` in its
  public interface even though its own imports are just `java.io.Closeable` + commonMain types),
  `RoomTransferStore.kt` (Room DAOs from `core:persistence` androidMain), `KeystorePassphraseProvider.kt`
  (`android.content.Context`/`android.security.keystore.*`/`android.util.Base64`/`javax.crypto.*`).

```
commonMain ── 1  (AutoConnectGate.kt)
    │
    ├── androidMain ── 4  (Flash.kt, FlashEngine.kt, RoomTransferStore.kt, KeystorePassphraseProvider.kt)
    └── (empty) jvmMain    (jvm() target compiles from commonMain only)
```

## Preconditions — do not start until all are true

1. **Phases 06, 07, 08, 09, 10, 11 complete and logged.** This phase depends on every `:core:*`
   module being KMP. Specifically verify in the 06/07/08/09/10/11 logs that:
   - `:core:common` → `FlashDeviceId`, `FlashResult`, `FlashTextFraming` are **commonMain**
   - `:core:security` → `FlashTrustStore` is **commonMain** (Phase 07)
   - `:core:discovery` → `FlashDiscovery`, `FlashDiscoveryState`, `FlashDiscoveredEndpoint`
     are **commonMain** (Phase 08)
   - `:core:network` → `FlashNetwork`, `FlashNetworkState`, `FlashSession`, `FlashConnectionHealth`
     are **commonMain** (Phase 10)
   - `:core:transfer` → `FlashTransferRepository`, `FlashTransfer`, `FlashTransferId`,
     `StreamChannel`, `Sha256`, `ChunkFrame`, `ReceiveEvent`, `ReceivePipeline`, `RejectReason`,
     `IncrementalSha256`, `FileRandomAccessSinkHandle`, `RandomAccessChunkSink`,
     `RandomAccessSinkHandle` are **commonMain or jvmAndAndroidMain** (Phase 11)
   - `:core:messaging` → `FlashChatRepository`, `RealFlashChatRepository`, `MessageWireFrame`
     are at least **commonMain** (Phase 11); `RealFlashChatRepository` is **androidMain**
   - `:core:persistence` → `FlashSettingsDataStore`, `FlashDatabaseOpener`, `FlashMigrations`
     are **androidMain** (Phase 09); the DAO/Entity types are also **androidMain**
   If any is NOT in the expected source set upstream, a commonMain/androidMain file here will not
   compile — stop and reconcile with that module's phase before moving anything.
2. **D1 = `A`** and **D5 = `A`** in `DECISIONS.md`.
3. **Clean working tree** on the migration branch.
4. **Phase 11's dead-dependency `TODO(cleanup)` flags are in place** — engine depends on all seven
   `:core:*` modules as `api`, so if any of those modules still have dead `androidx` AARs in
   commonMain, the `jvm()` target will try to resolve an AAR it cannot consume. Phase 11 should have
   relocated them to androidMain. If not, do it here before proceeding.

## Verified starting state (confirmed 2026-08-30)

### `core/engine/build.gradle.kts` (current)

```kotlin
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.engine"
    compileSdk = 35
    resourcePrefix = "flash_"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "core-engine"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":core:common"))
    api(project(":core:security"))
    api(project(":core:discovery"))
    api(project(":core:network"))
    api(project(":core:transfer"))
    api(project(":core:messaging"))
    api(project(":core:persistence"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
}
```

### Directory facts (verified)

- Production sources: `core/engine/src/main/java/com/transfer/flash/core/engine/` — **5 `.kt` files**
  (all Kotlin despite the `java/` dir; KMP wants `.../src/<set>/kotlin/...`).
- Tests: `core/engine/src/test/java/com/transfer/flash/core/engine/` — **1 `.kt` file**,
  JUnit4, **no Robolectric**.
- **One resource file:** `src/main/res/drawable/flash_bolt.xml` — a vector drawable (the Flash
  lightning-bolt logo, teal→amber gradient). This is the **first module in the migration** with
  Android resources, so `androidResources { enable = true }` is required.
- **No `AndroidManifest.xml`**, no `src/main/assets/` anywhere under `src/`.
- `consumer-rules.pro` exists at module root (comment-only: "No first-party keep rules are needed").
  `proguard-rules.pro` exists at module root (comment-only).
- **No `androidx.core.ktx` or `androidx.lifecycle.runtime.ktx` usage** in engine source files.
  Grep for `androidx.core` and `androidx.lifecycle` in `src/` (both main and test) returns **zero
  matches**. These AARs are dead — relocate them to `androidMain` as the Phase 11 pattern, flag
  `TODO(cleanup)`, do not delete.

### Dependency-usage findings (verified by grep)

| Dependency | Used in engine? | Where/Why |
|---|---|---|
| `:core:common` | **Yes** | `FlashDeviceId`, `FlashResult`, `FlashTextFraming` in `Flash.kt` |
| `:core:security` | **Yes** | `AndroidPreferencesIdentityStore`, `AndroidPreferencesTrustStore` in `Flash.kt` |
| `:core:discovery` | **Yes** | `CompositeDiscovery`, `NsdTransport`, `BuildNsdApiLevel`, `StandardEndpointDirectory`, `FlashAdvertisedIdentity`, `FlashDiscoveryMode` in `Flash.kt` |
| `:core:network` | **Yes** | `WsFlashNetwork`, `WsSession`, `DataChannelServer`, `DataChannelClient`, `DiscoveryRouteBinder` in `Flash.kt` |
| `:core:transfer` | **Yes** | `RealFlashTransferRepository`, `Sha256`, `IncrementalSha256`, `ChunkFrame`, `ReceiveEvent`, `ReceivePipeline`, `RejectReason`, `StreamChannel`, `FileRandomAccessSinkHandle`, `RandomAccessChunkSink`, `RandomAccessSinkHandle` in `Flash.kt` |
| `:core:messaging` | **Yes** | `RealFlashChatRepository`, `MessageWireFrame` in `Flash.kt` |
| `:core:persistence` | **Yes** | `FlashSettingsDataStore` (in `Flash.kt` + `FlashEngine.kt` public interface), `FlashDatabaseOpener`, `FlashMigrations` (in `Flash.kt`), Room DAOs/Entities (via `RoomTransferStore.kt`) |
| `androidx.room.runtime` | **Yes** | `db.close()` (resolves to `RoomDatabase.close()`) at compile time; `RoomTransferStore` uses DAOs |
| `androidx.core.ktx` | **No** | **Zero imports** in engine sources. Dead. |
| `androidx.lifecycle.runtime.ktx` | **No** | **Zero imports** in engine sources. Dead. |

### File-by-file import analysis

| File | Lines | Imports | Placement |
|---|---|---|---|
| `AutoConnectGate.kt` | 42 | `kotlin.*` only (stdlib types: `HashMap`, `HashSet`, `@Synchronized`) | **commonMain** |
| `FlashEngine.kt` | 63 | `java.io.Closeable`, `FlashDiscovery` (commonMain), `FlashChatRepository` (commonMain/androidMain), `FlashNetwork` (commonMain), `FlashSettingsDataStore` (**androidMain**), `FlashTrustStore` (commonMain), `FlashTransferRepository` (commonMain) | **androidMain** — pinned by `FlashSettingsDataStore` in public interface |
| `Flash.kt` | 688 | `android.content.Context`, `android.util.Log` (2 android imports), `java.io.*`, `java.util.*`, `java.util.concurrent.*`, `kotlinx.coroutines.*`, plus types from all 7 `:core:*` modules | **androidMain** |
| `RoomTransferStore.kt` | 53 | Room DAOs/Entities (`com.transfer.flash.core.persistence.db.dao.*`, `db.entity.*`) from `core:persistence` (androidMain) | **androidMain** |
| `KeystorePassphraseProvider.kt` | 88 | `android.content.Context`, `android.security.keystore.*`, `android.util.Base64`, `java.security.KeyStore`, `javax.crypto.*` | **androidMain** |

### Test file analysis

| File | Lines | Imports | Notes |
|---|---|---|---|
| `DefaultFlashEngineTest.kt` | 109 | JUnit4, `FlashSettingsDataStore`, fake implementations of `FlashDiscovery`/`FlashNetwork`/`FlashTrustStore`/`FlashTransferRepository`, `SampleFlashChatRepository` | **No `android.*` imports.** The test constructs `FlashSettingsDataStore(produceFile = { java.io.File.createTempFile(…) })` — a pure-JVM path that uses `java.io.File` directly. **No Robolectric.** Controlled use of `androidx.*` only via `FlashSettingsDataStore` (which is an androidMain class). This test is a `androidHostTest` because it references `FlashSettingsDataStore` (androidMain), not because it needs Android stubs. |

## The source-set strategy (D1 = A)

Four source sets, wired exactly as Phase 06 established:

```
commonMain  ──────────────┐  (pure Kotlin only; no java/javax/android)
                          ▼
                 jvmAndAndroidMain   (java.*/javax.* allowed; shared by both JVM targets)
                    ┌─────┴─────┐
                    ▼           ▼
               androidMain    jvmMain   (platform-specific; android.* only in androidMain)
```

Wiring (goes in the `kotlin { sourceSets { } }` block — see Step 3):

```kotlin
val jvmAndAndroidMain = create("jvmAndAndroidMain")
jvmAndAndroidMain.dependsOn(getByName("commonMain"))
getByName("androidMain").dependsOn(jvmAndAndroidMain)
getByName("jvmMain").dependsOn(jvmAndAndroidMain)
```

**Placement rule (memorise — it is the one thing a weak model gets wrong):**

1. If a file imports `android.*` → **androidMain**.
2. Else if a file imports `java.*` or `javax.*` or exposes an androidMain-only type in its public
   signature → **jvmAndAndroidMain**.
3. Else → **commonMain**.

## Placement table — production files (5 files: 1 / 0 / 4)

### commonMain — 1

`src/commonMain/kotlin/com/transfer/flash/core/engine/`:

| # | File | Why commonMain |
|---|---|---|
| 1 | `internal/AutoConnectGate.kt` | Pure stdlib (`HashMap`/`HashSet`/`@Synchronized`); zero imports. |

### jvmAndAndroidMain — 0

None. The engine has no file that needs JDK types without also needing Android types or being
pinned by an androidMain type.

### androidMain — 4

`src/androidMain/kotlin/com/transfer/flash/core/engine/`:

| # | File | Why pinned to Android |
|---|---|---|
| 1 | `Flash.kt` | 33 KB composition root. `android.content.Context` (line 13), `android.util.Log` (line 14). Also `java.io.*`/`java.util.*`/`java.util.concurrent.*`/`kotlinx.coroutines.*` plus types from all 7 `:core:*` modules. |
| 2 | `FlashEngine.kt` | **Pinned by its public interface**: exposes `settings: FlashSettingsDataStore` (3 instances of `FlashSettingsDataStore` in the file: the import at line 6, the interface property at line 37, and the `DefaultFlashEngine` override at line 54). `FlashSettingsDataStore` is a concrete androidMain class (Phase 09). Even though the file's other imports are only `java.io.Closeable` + commonMain module types, the `settings` property is in the **public interface** — a `jvm()` consumer cannot resolve `FlashSettingsDataStore`, so the file must be androidMain. |
| 3 | `store/RoomTransferStore.kt` | Imports Room DAOs/Entities from `core:persistence` (androidMain in Phase 09). Cannot compile for `jvm()` without dragging Room into the desktop target. |
| 4 | `store/KeystorePassphraseProvider.kt` | `android.content.Context` (line 4), `android.security.keystore.*` (line 5–6), `android.util.Base64` (line 7). Also `java.security.KeyStore`/`javax.crypto.*`. |

### Resource — 1

Move from `src/main/res/drawable/flash_bolt.xml` to `src/androidMain/res/drawable/flash_bolt.xml`.
This is the **first module in the migration** with Android resources. The KMP Android target does
**not** enable resources by default — you must opt in with `androidResources { enable = true }`
inside the `androidLibrary { }` block (see Step 3).

### Tests → `androidHostTest` (1 file, unmodified)

Move from `src/test/java/.../engine/` to `src/androidHostTest/kotlin/.../engine/`:

| File | Note |
|---|---|
| `DefaultFlashEngineTest.kt` | 1 `@Test`. **No Robolectric.** No `android.*` imports. Uses `java.io.File.createTempFile()` for `FlashSettingsDataStore`. |

The test stays in `androidHostTest` (not `jvmTest`) because it references `FlashSettingsDataStore`
(androidMain). Do **not** try to split it to a shared test set — it changes the topology and risks
the count.

## Why jvmMain is empty under D5 = A (and must stay empty)

`compileKotlinJvm` builds the `jvm()` target from `commonMain + jvmAndAndroidMain` only. Here that
is just `AutoConnectGate.kt`. That is the point of D5 = A: it proves desktop can consume the
engine *contract* (`FlashEngine` interface) without pulling in the Android-bound composition root,
Room, AndroidKeyStore, or DataStore. Desktop-specific engine wiring (a `jvmMain` function that
assembles in-memory/desktop-file adapters) is deferred to a later phase (Phase 16 and beyond).

**Do not** create a desktop `Flash.create()` equivalent, a desktop `RoomTransferStore` replacement,
or any `jvmMain` file in this phase. That work belongs in the desktop-integration phases.

## Dependency table — per source set

### commonMain dependencies (api — all are in public API signatures)

```kotlin
getByName("commonMain").dependencies {
    api(project(":core:common"))        // FlashDeviceId, FlashResult, FlashTextFraming
    api(project(":core:security"))      // FlashTrustStore (commonMain, Phase 07)
    api(project(":core:discovery"))     // FlashDiscovery, FlashDiscoveryState, FlashDiscoveredEndpoint (commonMain)
    api(project(":core:network"))       // FlashNetwork, FlashNetworkState, FlashSession, FlashConnectionHealth (commonMain)
    api(project(":core:transfer"))      // FlashTransferRepository, FlashTransfer, FlashTransferId, StreamChannel (commonMain)
    api(project(":core:messaging"))     // FlashChatRepository (commonMain)
}
```

### androidMain dependencies (api for persistence — it's in the public interface; implementation for Room and dead AARs)

```kotlin
getByName("androidMain").dependencies {
    api(project(":core:persistence"))       // FlashSettingsDataStore, FlashDatabaseOpener, FlashMigrations — all in FlashEngine's public interface
    implementation(libs.androidx.room.runtime)  // db.close() → RoomDatabase.close() at compile time; NOT leaked to consumers
    implementation(libs.androidx.core.ktx)           // DEAD — 0 imports. Relocated from catch-all, flagged TODO(cleanup)
    implementation(libs.androidx.lifecycle.runtime.ktx)  // DEAD — 0 imports. Relocated, flagged TODO(cleanup)
}
```

### androidHostTest dependencies

```kotlin
getByName("androidHostTest").dependencies {
    implementation(libs.junit)
}
```

### Key dependency tier rules

- **`:core:persistence` is `api` in androidMain**, not `implementation`. Reason: `FlashSettingsDataStore`
  is in the public `FlashEngine` interface (`settings: FlashSettingsDataStore`). Making it
  `implementation` would break consumers that read `engine.settings`. This is the same logic as
  `:core:common` being `api` — it's the public type surface.
- **`androidx.room.runtime` stays `implementation`** in androidMain. Room is an internal detail:
  `Flash.kt` calls `db.close()` (RoomDatabase), but that's not on the `FlashEngine` interface.
  Room is not re-leaked to library consumers — consistent with ADR-024.
- **`androidx.core.ktx` and `androidx.lifecycle.runtime.ktx` are DEAD** (0 imports in engine
  sources). They are relocated to `androidMain.implementation` (not deleted) to keep the runtime
  classpath byte-identical. Each gets a `TODO(cleanup)` comment. A later phase will delete them
  after confirming no transitive dependency needs them.

## Steps

Do them in order. Each step ends with a check; if a check fails, fix it before moving on.
All Gradle commands include `--no-configuration-cache` (the KMP Android plugin is not
config-cache-clean mid-migration; Phase 06 established this).

### Step 1 — Confirm the gates and pull Phase 06's facts

1. Re-read the **D1 and D5 gates** above against `DECISIONS.md`. If either ≠ `A`, stop now.
2. Open `docs/migration/logs/migration.md`, find the Phase 06 entry, and copy the five recorded
   strings (`KMP_ANDROID_DSL_SNIPPET`, `KMP_HOST_TEST_BLOCK`, `KMP_JVM_TARGET_DSL`,
   `KMP_LOCAL_DEP_SELECTION`, `ANDROID_UNIT_TEST_TASK`). You will paste the DSL snippets into
   Step 3 verbatim and use `ANDROID_UNIT_TEST_TASK` in Step 7. **Do not re-invent the Android-KMP
   DSL** — this is the first module with `androidResources { enable = true }`, so the DSL snippet
   from Phase 06 may need one addition (see Step 3). If the log lacks any of these facts, stop
   and finish Phase 06's logging first.

### Step 2 — Record the baseline test count (before you touch anything)

While the module is still `com.android.library`, run its current unit tests and record how many ran.

```bash
./gradlew :core:engine:testDebugUnitTest --no-configuration-cache
```

Open the HTML report it prints
(`core/engine/build/reports/tests/testDebugUnitTest/index.html`) or the XML under
`core/engine/build/test-results/testDebugUnitTest/` and record the **total tests** count as
`ENGINE_TEST_BASELINE`. Expect **1** (the single `DefaultFlashEngineTest`). Write it into your
working notes now — you will assert the post-conversion count equals it.

### Step 3 — Rewrite `core/engine/build.gradle.kts`

Replace the whole file with the following. Paste the Phase-06 snippets where marked. The
engine-specific additions versus Phase 07's build are: (a) `androidResources { enable = true;
resourcePrefix = "flash_" }` inside the `androidLibrary { }` block (FIRST phase to need this);
(b) the `:core:persistence` dependency is `api` in `androidMain` (it's in the public interface);
(c) the two dead AARs relocated to `androidMain` with `TODO(cleanup)`.

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library) // exact alias Phase 06 used
    `maven-publish`
}

kotlin {
    explicitApi()   // preserved from the old build; ADR-023 keeps strict public API

    // ── Android target ── paste KMP_ANDROID_DSL_SNIPPET from the Phase 06 log verbatim, then
    //    ADD androidResources block (Phase 12 is the FIRST module with bundled resources).
    //    The resourcePrefix from the old build moves inside androidResources.
    androidLibrary {
        namespace = "com.transfer.flash.core.engine"
        compileSdk = 35
        minSdk = 24
        // <<PASTE KMP_HOST_TEST_BLOCK HERE — no isIncludeAndroidResources needed (test has no Robolectric)>>
        // <<PASTE Phase 06's consumer-proguard wiring here (consumer-rules.pro is comment-only,
        //    but keep the wiring identical to Phase 06 so nothing regresses)>>

        // ═══════════════════════════════════════════════════════════════════════
        // Phase 12 addition — FIRST module with Android resources.
        // androidResources is NOT enabled by default in AGP's KMP library plugin.
        // Without this, flash_bolt.xml at src/androidMain/res/drawable/ is silently
        // ignored and the published AAR has no resources.
        //
        // Reference: developer.android.com/kotlin/multiplatform/plugin
        //   "Android resources are not enabled by default. Use the androidResources
        //    block inside the kotlin { android { } } block to enable them."
        //
        // resourcePrefix moved from the old android { resourcePrefix = "flash_" } block.
        // It is now INSIDE androidResources, per AGP 9.3.1 KMP:
        //   KmpGlobalTaskCreationConfigImpl.kt confirms:
        //   override val resourcePrefix: String? get() = extension.androidResources.resourcePrefix
        // ═══════════════════════════════════════════════════════════════════════
        androidResources {
            enable = true
            resourcePrefix = "flash_"
        }
    }

    jvm()   // desktop target — plain jvm(), never jvm("desktop")

    sourceSets {
        val jvmAndAndroidMain = create("jvmAndAndroidMain")
        jvmAndAndroidMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)

        getByName("commonMain").dependencies {
            api(project(":core:common"))
            api(project(":core:security"))
            api(project(":core:discovery"))
            api(project(":core:network"))
            api(project(":core:transfer"))
            api(project(":core:messaging"))
        }

        getByName("androidMain").dependencies {
            // FlashSettingsDataStore is in the public FlashEngine interface → api, not implementation
            api(project(":core:persistence"))

            // Room is internal wiring (db.close()). Not leaked → implementation.
            implementation(libs.androidx.room.runtime)

            // ── DEAD dependencies (0 imports in engine sources) ──
            // Relocated here from the catch-all dependencies block so the jvm() target never
            // sees an AAR it cannot consume. Do NOT delete — a later cleanup phase audits
            // and removes them after confirming no transitive consumer needs them.
            // TODO(cleanup): verify no consumer depends on these transitively via engine,
            // then delete both lines.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        // <<PASTE KMP_LOCAL_DEP_SELECTION HERE if Phase 06 recorded one>>
    }
}

publishing {
    publications {
        // KMP emits root "kotlinMultiplatform" + "android" + "jvm" publications; keep the
        // core-engine prefix on all of them (same rewrite Phase 06 recorded).
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace(project.name, "core-engine")
        }
    }
}
```

**What changed vs the old file, and why it is safe:** `com.android.library` → KMP + KMP-Android
plugins (AGP 9 forces it); `android { buildTypes/compileOptions/singleVariant }` gone (KMP has no
variants); `resourcePrefix` moved inside `androidResources { enable = true; resourcePrefix = … }`
(new KMP-required location — the `android { resourcePrefix }` no longer exists in KMP library
plugin); the seven `:core:*` dependencies split between `commonMain` (6) and `androidMain` (1);
Room relocated to `androidMain.implementation`; dead AARs relocated to `androidMain.implementation`
with `TODO(cleanup)`; single `register("release")` publication → prefix rewrite. No version bump,
no new dependency, no `expect`/`actual` (R10).

### Step 4 — Move the production sources (`git mv`, zero content edits)

Run from the **repo root**. Create destination package dirs first. Move files individually (only 5
production files) — the small count makes a glob-based approach fragile.

```bash
# ── Create destination dirs ──
cd core/engine/src

# commonMain (1 file)
mkdir -p commonMain/kotlin/com/transfer/flash/core/engine/internal

# androidMain (4 files)
mkdir -p androidMain/kotlin/com/transfer/flash/core/engine/store

# androidHostTest (1 file)
mkdir -p androidHostTest/kotlin/com/transfer/flash/core/engine

# androidMain resources (1 file)
mkdir -p androidMain/res/drawable

# jvmAndAndroidMain + jvmMain — empty dirs (create for git to track? No — KMP tolerates
# missing source-set dirs. Only create them if the build complains about a missing source set.
# Do NOT create empty dirs preemptively.)
```

**Move production files — commonMain (1):**

```bash
git mv main/java/com/transfer/flash/core/engine/internal/AutoConnectGate.kt \
       commonMain/kotlin/com/transfer/flash/core/engine/internal/AutoConnectGate.kt
```

**Move production files — androidMain (4):**

```bash
git mv main/java/com/transfer/flash/core/engine/Flash.kt \
       androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt

git mv main/java/com/transfer/flash/core/engine/FlashEngine.kt \
       androidMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt

git mv main/java/com/transfer/flash/core/engine/store/RoomTransferStore.kt \
       androidMain/kotlin/com/transfer/flash/core/engine/store/RoomTransferStore.kt

git mv main/java/com/transfer/flash/core/engine/store/KeystorePassphraseProvider.kt \
       androidMain/kotlin/com/transfer/flash/core/engine/store/KeystorePassphraseProvider.kt
```

**Move resource — androidMain (1):**

```bash
git mv main/res/drawable/flash_bolt.xml \
       androidMain/res/drawable/flash_bolt.xml
```

**Move test — androidHostTest (1):**

```bash
git mv test/java/com/transfer/flash/core/engine/DefaultFlashEngineTest.kt \
       androidHostTest/kotlin/com/transfer/flash/core/engine/DefaultFlashEngineTest.kt
```

**Verify the old `src/main` and `src/test` are empty:**

```bash
# Production (should be 0 .kt and 0 .xml)
find main -name '*.kt' -o -name '*.xml' 2>/dev/null | wc -l
# → 0

# Test (should be 0 .kt)
find test -name '*.kt' 2>/dev/null | wc -l
# → 0
```

### Step 5 — Verify `git status` shows the expected renames

```bash
cd /path/to/repo/root
git status --short -- core/engine/
```

Expected output pattern (5 production renames + 1 resource rename + 1 test rename):

```
R  core/engine/src/main/java/.../AutoConnectGate.kt → core/engine/src/commonMain/kotlin/.../AutoConnectGate.kt
R  core/engine/src/main/java/.../Flash.kt → core/engine/src/androidMain/kotlin/.../Flash.kt
R  core/engine/src/main/java/.../FlashEngine.kt → core/engine/src/androidMain/kotlin/.../FlashEngine.kt
R  core/engine/src/main/java/.../RoomTransferStore.kt → core/engine/src/androidMain/kotlin/.../RoomTransferStore.kt
R  core/engine/src/main/java/.../KeystorePassphraseProvider.kt → core/engine/src/androidMain/kotlin/.../KeystorePassphraseProvider.kt
R  core/engine/src/main/res/drawable/flash_bolt.xml → core/engine/src/androidMain/res/drawable/flash_bolt.xml
R  core/engine/src/test/java/.../DefaultFlashEngineTest.kt → core/engine/src/androidHostTest/kotlin/.../DefaultFlashEngineTest.kt
```

Count: `git status --short -- core/engine/ | grep -c '^R'` → **7** (5 production renames +
1 resource rename + 1 test rename). No `D` (deletions) — this is a pure move, no files are deleted.

### Step 6 — `jvm()` compile (the narrowest proof that the split works)

```bash
./gradlew :core:engine:compileKotlinJvm --no-configuration-cache
```

**Expected: BUILD SUCCESSFUL.** If it fails:

- **`AutoConnectGate` not found** → you placed it in `androidMain` or forgot to move it. Check
  `src/commonMain/kotlin/.../`.
- **`FlashSettingsDataStore` not found** → `FlashEngine.kt` was placed in `commonMain` or
  `jvmAndAndroidMain` instead of `androidMain`. Move it to `androidMain`.
- **`FlashDiscovery` not found** → `:core:discovery` is not yet KMP or its `FlashDiscovery` is
  not in `commonMain`. Complete Phase 08 first.
- **`RoomTransferStore` not found** → you placed it in `commonMain` or `jvmAndAndroidMain`.
  Move it to `androidMain`.
- **`Context` or `android.*` import error** → `Flash.kt`, `KeystorePassphraseProvider.kt`, or
  `FlashEngine.kt` (if pinned) was placed in `commonMain` or `jvmAndAndroidMain`. Move to
  `androidMain`.
- **AAR resolution error for `androidx.core.ktx`** → one of the dead AARs leaked into
  `commonMain.dependencies`. Move it to `androidMain.dependencies` (Step 3 already does this;
  verify the dependency block placement).

### Step 7 — Host test (assert the test count equals the baseline)

```bash
./gradlew :core:engine:${ANDROID_UNIT_TEST_TASK} --no-configuration-cache
```

Replace `${ANDROID_UNIT_TEST_TASK}` with the task name Phase 06 recorded (likely
`testAndroidHostTest`). If the task name is not available, use:

```bash
./gradlew :core:engine:check --no-configuration-cache
```

Open the test report. Verify **1 test ran** (matching the baseline from Step 2). If 0 tests ran,
you omitted the `withHostTest { }` block — add it. If >1 ran, the test moved to both
`androidHostTest` and `jvmTest` — undo the `jvmTest` placement.

### Step 8 — `jvm()` Jar (proves the desktop artifact has no Android bytecode)

```bash
# Create a temporary jvmJar
./gradlew :core:engine:jvmJar --no-configuration-cache
# Verify it only contains AutoConnectGate
jar tf core/engine/build/libs/core-engine-jvm-*.jar
```

Expected: exactly one `.class` entry (`com/transfer/flash/core/engine/internal/AutoConnectGate.class`)
and no `android/` paths. This confirms the `jvm()` target is Android-free.

### Step 9 — Publish to local Maven (proves the publication rewrite works)

```bash
./gradlew :core:engine:publishToMavenLocal --no-configuration-cache
```

Check `~/.m2/repository/com/transfer/flash/core-engine/`:

```bash
ls ~/.m2/repository/com/transfer/flash/core-engine/*/ | head -5
```

Expected: root `core-engine` (kotlinMultiplatform metadata) + `core-engine-android` + `core-engine-jvm`
variants, each with `.module` metadata. The root artifactId matches `core-engine` (the old
single-variant coordinate). If the root is `core-engine-kotlinMultiplatform`, the prefix rewrite
in Step 3 is wrong — verify `artifactId.replace(project.name, "core-engine")`.

### Step 10 — `:app:assembleDebug` (proves the Android consumer still resolves)

```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

**Expected: BUILD SUCCESSFUL.** This verifies that the Android app resolves `core-engine` from
Maven local (or from Gradle module dependency) and that `@drawable/flash_bolt` is still available.

### Step 11 — Final sanity: no Android bytecode leaks into the JVM artifact

```bash
# Re-confirm: decompile the jvmJar to check for android symbols
javap -p core/engine/build/libs/core-engine-jvm-*.jar 2>/dev/null \
  || jar tf core/engine/build/libs/core-engine-jvm-*.jar \
  | grep -v '\.class$' \
  | head -3
```

(If `jar tf` outputs only `AutoConnectGate.class`, the jvm artifact is clean.)

## Verification gate — do NOT log PASS until all are green

1. `:core:engine:compileKotlinJvm` — **BUILD SUCCESSFUL**.
2. `:core:engine:${ANDROID_UNIT_TEST_TASK}` (or `check`) — **BUILD SUCCESSFUL, exactly 1 `@Test`**.
3. `:core:engine:jvmJar` (or `jvmJar`) — **BUILD SUCCESSFUL**, jar contains only `AutoConnectGate.class`.
4. `:core:engine:publishToMavenLocal` — root `core-engine` + `-android` + `-jvm` variants with `.module`.
5. `:app:assembleDebug` — **BUILD SUCCESSFUL**.

Any red ⇒ do not log PASS and do not proceed to Phase 13. Fix the owning cause (placement /
upstream commonMain / build DSL / androidResources enabled) and re-run.

## Do NOT

- **Do NOT edit file contents.** This is a move-only phase. The composition root (`Flash.kt`, 688
  lines) must not have a byte of content changed. No reformatting, no import reordering, no
  visibility tweaks.
- **Do NOT introduce an `expect`/`actual`** for anything here. If a file cannot be placed, resolve
  it by moving it to the correct source set, not by inventing a platform abstraction.
- **Do NOT try to pull `FlashEngine.kt` down to `jvmAndAndroidMain`.** It is pinned to androidMain
  by `FlashSettingsDataStore` in its public interface. Attempting it drags DataStore into the desktop
  target.
- **Do NOT create a `jvmMain` file** — no desktop engine adapter in this phase. D5 = A means desktop
  gets no persistence, no runnable engine. A desktop `Flash.create()` is deferred to the
  desktop-integration phases.
- **Do NOT delete the dead dependencies** (`androidx.core.ktx`, `androidx.lifecycle.runtime.ktx`).
  Relocate + flag `TODO(cleanup)`; a later phase deletes them.
- **Do NOT omit `withHostTest { }`** — omitting it produces a green build running 0 tests. And do NOT
  add `isIncludeAndroidResources` — the engine's only test (`DefaultFlashEngineTest.kt`) has no
  Robolectric and needs no Android resources at test time.
- **Do NOT use `jvm("desktop")`, typed source-set accessors, or `src/**/java/` roots.**
- **Do NOT run mid-migration Gradle without `--no-configuration-cache`.**
- **Do NOT forget `androidResources { enable = true }`** — this is the first module with resources.
  Without it, `flash_bolt.xml` is silently ignored and the published AAR has no resources.
  Consumer apps that reference `@drawable/flash_bolt` will fail at compile time.

## Completion checklist

- [ ] `build.gradle.kts` rewritten to the KMP form (plugins, `androidLibrary`, `androidResources
      { enable = true; resourcePrefix = "flash_" }`, `jvm()`, three source sets wired with
      `getByName`, dependency tiers per the tables, `withHostTest`, no `isIncludeAndroidResources`,
      KMP publication rewrite).
- [ ] 5 production files moved (1 commonMain / 0 jvmAndAndroidMain / 4 androidMain).
- [ ] 1 resource file moved (`flash_bolt.xml` → `src/androidMain/res/drawable/`).
- [ ] 1 test file moved to `androidHostTest`.
- [ ] `src/main` empty of `.kt` and `.xml`.
- [ ] `src/test` empty of `.kt`.
- [ ] `:core:engine:compileKotlinJvm` green.
- [ ] `:core:engine:testAndroidHostTest` green at **1 test**.
- [ ] `:core:engine:jvmJar` green, jar contains only `AutoConnectGate.class`.
- [ ] `:core:engine:publishToMavenLocal` emits root + `-android` + `-jvm` with `.module`.
- [ ] `:app:assembleDebug` green.
- [ ] Dead deps relocated + `TODO(cleanup)` flagged, not deleted.
- [ ] `androidResources { enable = true }` confirmed present in the `androidLibrary { }` block.
- [ ] Log entry appended to `docs/migration/logs/migration.md` (append-only) with pasted outputs.

## Rollback

Move-only + build-DSL change, so rollback is mechanical and total:

```bash
git restore --staged core/engine
git checkout -- core/engine
git clean -fd core/engine/src/commonMain core/engine/src/androidMain \
               core/engine/src/androidHostTest
```

This un-stages the renames, restores the original `build.gradle.kts` and `src/main`/`src/test` trees,
and removes the empty KMP source-set dirs. If `publishToMavenLocal` ran, stale
`~/.m2/repository/.../core-engine*` entries are harmless (overwritten on the next real publish).

## Log entry (mandatory)

Append **one** entry to `docs/migration/logs/migration.md` (append-only, newest at the bottom —
never edit an earlier entry; never write PASS without pasted command output). Include:

- The Step 2 **baseline** (`testDebugUnitTest` count = 1 @Test) and the current publish coordinates
  (`core-engine`), with pasted output.
- Confirmation of the split moved: **1 commonMain / 0 jvmAndAndroidMain / 4 androidMain** + 1 resource;
  `src/main` empty (paste the `find … | wc -l` = 0 and `git status --short | grep -c '^R'` = 7 counts).
- Test move: 1 `androidHostTest`.
- The five verification-gate outputs pasted: `compileKotlinJvm` SUCCESS; `testAndroidHostTest`
  SUCCESS at **1 test** (not just "passed"); `jvmJar` SUCCESS (jar contains only `AutoConnectGate`);
  `publishToMavenLocal` emitting root + `-android` + `-jvm`; `:app:assembleDebug` SUCCESS.
- The dead dependencies relocated + flagged (`androidx.core.ktx`, `androidx.lifecycle.runtime.ktx`
  → androidMain implementation), explicitly noted as grep-unused and **not** deleted.
- **`androidResources { enable = true }`** confirmed present and working (`flash_bolt.xml` found
  in the published AAR's `res/`).
- The final statement: **`core:engine` is KMP; the engine contract (`FlashEngine` interface) compiles
  for desktop JVM; the Android-bound composition root (`Flash.kt`) stays androidMain; the Android
  host suite is unregressed at 1 test; resources are namespaced under `flash_` prefix; the dead
  `androidx.core.ktx` and `androidx.lifecycle.runtime.ktx` libs are relocated (not deleted) with
  `TODO(cleanup)`.**