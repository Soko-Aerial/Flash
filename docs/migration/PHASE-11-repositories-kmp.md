# Phase 11 — KMP conversion: `core:transfer` + `core:messaging`

**Blocked by:** Phases 06 (pilot), 07 (security), 08 (discovery), 09 (persistence), 10 (network).
**D1 must be `A`.** Written for **D1 = A** (`jvmAndAndroidMain`).
**Risk: HIGH** — two core modules, the first place where the **transfer wire format** (FLSH v2
frames) has to keep compiling for desktop JVM, plus **three dead project dependencies** that must be
relocated (not deleted) and **two orphaned `Ws*` files** whose fate I must flag explicitly rather
than silently decide. Like 07/08/09/10, under D1 = A this is a **pure file-move with zero content
edits** — no `expect`/`actual`, no rewrites. The work is in *placement*.

> ⚠️ **Two adjacent phases each "own" one of these files' Android coupling.** Phase 03
> (logging) removes `android.util.Log` from `RealFlashTransferRepository.kt`; Phase 09 pins
> persistence types to `androidMain`. Read both before you place a single file, or you will
> misplace `RealFlashTransferRepository.kt` (transfer) — it is **not** androidMain-bound by
> Phase 11; it is bound by the **JDK** (`java.io.InputStream`, `java.util.UUID`,
> `java.util.concurrent.ConcurrentHashMap`) and therefore belongs in `jvmAndAndroidMain`. See the
> Log note in Phase 10 for the same reasoning applied to `:core:network`.

## What this phase is actually for

Convert both `:core:transfer` and `:core:messaging` from `com.android.library` to the KMP plugin with
an Android target and a desktop `jvm()` target, moving files into source sets **unedited**. When done:

- `:core:transfer:compileKotlinJvm` compiles — proving the **shared transfer surface** builds for
  desktop: the repository contract (`FlashTransferRepository`), the pure transfer model
  (`FlashTransfer`, `FlashTransferId`), the **FLSH v2 wire format** (`WsTransferMessages`,
  `MessageWireFrame` counterpart) and its chunk codec (`ChunkFrame`), the chunking + hashing +
  resume machinery (`Chunker`, `Sha256`, `ResumeBitVector`), the pipeline/policy/transfer-store
  layer, and the multi-stream engine's **interfaces** plus its JVM-backed state machines.
- `:core:messaging:compileKotlinJvm` compiles — proving the **shared messaging surface** builds for
  desktop: the chat repository contract + sample impls, the messaging UI models, the grouping/util
  helpers, and the message wire frame.
- The Android host tests still run and still pass **at the same counts** — **86 `@Test` across 13
  classes** for `:core:transfer`, **16 `@Test` across 3 classes** for `:core:messaging`.
- Both modules still publish as `core-transfer` / `core-messaging`; `:app:assembleDebug` still
  resolves them.

**What this phase does NOT do:** it does not give desktop a *runnable* transfer or messaging
implementation. `RealFlashTransferRepository` (transfer) and `RealFlashChatRepository` (messaging)
are the *concrete* repos, and they stay `jvmAndAndroidMain` / `androidMain` respectively because they
are bound to JDK types, Room DAOs/entities, and platform timing. Desktop-facing concrete adapters are
built later (Phase 09's Room port, Phase 12's engine `RoomTransferStore`) against the same contracts.
Phase 11 only proves the shared half compiles off Android — exactly the 07/10 pattern.

## Why `core:transfer` splits 5 / 14 / 0 (read before placing files)

> The **5 / 14 / 0** split is the *post-Phase-02* state. Phase 02 already deleted five `wslegacy`
> files (`WsTransferManager`, `WsDiscovery`, `WsPairingStore`, `LegacyDiscoveredDevice` + the
> `WsPairingStoreTest`). This phase receives a **20-file** production tree and must further account
> for **two orphaned `Ws*` files** that Phase 02 left behind but whose only consumer Phase 02 just
> deleted. Re-read the `WsTransferModels` / `WsTransferMessages` notes before placing them.
>
> The **authoritative split is 5 commonMain / 14 jvmAndAndroidMain / 0 androidMain.** The `multistream/`
> package is the reason the count is easy to get wrong: only `StreamChannel.kt` is pure (commonMain),
> while the other four files in that package (`MultiStreamProgress`, `MultiStreamDispatcher`,
> `MultiStreamReceiver`, `TransferCompletionStateMachine`) are all JVM-backed
> (`@Synchronized`/`Atomic*`/`synchronized(lock)`) and belong in jvmAndAndroidMain.

- **commonMain (5):** the repository **interface** and pure models + the one transport contract
  that is pure-Kotlin. `FlashTransferRepository` is an interface referencing only `:core:common`
  types/`StateFlow`; `FlashTransfer` is a pure model with `@JvmInline`; `WsTransferMessages` is the
  R8 wire format (see orphan note); `TransferStore` is the ADR-024 storage port (interface);
  `StreamChannel` is a pure single-purpose interface. These reference nothing JDK or Android.
- **jvmAndAndroidMain (14):** everything that needs `java.*`/`java.util.concurrent.*` but **not**
  `android.*` — the hashing/codec/chunking layer (`MessageDigest`, `ByteArrayOutputStream`,
  `ByteBuffer`, `BitSet`, `InputStream`, `Closeable`), the JDK-backed state machines
  (`Atomic*`/`@Synchronized`/`@Volatile`), the policies (`RandomAccessFile`, `Closeable`),
  `RealFlashTransferRepository` (`InputStream`/`UUID`/`ConcurrentHashMap`),
  `manifest/TransferManifest` (`System.currentTimeMillis`), and the four JVM-backed `multistream/`
  files (see below).
- **androidMain (0):** none, after Phase 02. (Phase 03 will, if anything, *reduce* this — it removes
  the only remaining `android.util.Log` reference in `RealFlashTransferRepository` — but that does
  not change Phase 11's placement, which is JDK-driven.)

## Why `core:messaging` splits 4 / 0 / 1

- **commonMain (4):** the repository **interface** + sample impls, the messaging UI models, the
  grouping/util helpers, and the message wire frame. `FlashChatRepository` references
  `FlashPeerPresence`/`StateFlow`; `FlashMessagingModels` references `FlashDeviceId`/
  `FlashPeerPresence`; `FlashMessagingUtils` references commonMain types; `MessageWireFrame` has no
  imports (pure serialization).
- **jvmAndAndroidMain (0):** none.
- **androidMain (1):** `RealFlashChatRepository` — bound to `SimpleDateFormat`/`Date`/`Locale`/
  `UUID`/`ConcurrentHashMap` (JDK, but Phase 09 **pins** it to androidMain because it wires seven
  Room `Dao`s and eight Room `Entity`s that are themselves androidMain under Phase 09). Do **not**
  pull it down to `jvmAndAndroidMain`: the Room persistence types are `androidMain` in Phase 09, so
  this class cannot compile for `jvm()` without dragging Room into the desktop target — which Phase
  09 explicitly avoided. It stays `androidMain`.

## Preconditions — do not start until all are true

1. **Phases 06, 07, 08, 09, 10 complete and logged.** This phase reuses Phase 06's five DSL facts and
   depends on `core:common` (06), `core:discovery` (08), `core:persistence` (09), and `core:network`
   (10) being KMP. Specifically verify in the 06/08/09/10 logs that these are **commonMain**:
   `FlashDevice`, `FlashDeviceId`, `FlashTransportType`, `FlashPeerPresence`, `FlashResult`,
   `FlashError`, `FlashInternalApi`, `FlashTextFraming` (all `core:common`); `FlashDiscoveredEndpoint`
   (`core:discovery`); and — critically for messaging — that the **Messaging Kotlin** types
   (`FlashMessageUi`, `FlashConversationUiState`, etc.) are in **commonMain**. If any is NOT
   commonMain upstream, a commonMain file here will not compile — stop and reconcile with that
   module's phase before moving anything.
2. **D1 = `A`** in `DECISIONS.md`.
3. **Clean working tree** on the migration branch.
4. **Phase 02 (delete wslegacy) is fully applied and committed.** If the five `wslegacy` files are
   still present, the 20-file inventory and the `WsTransferModels` judgment call both shift — stop
   and apply Phase 02 first.

## Verified starting state (read this, do not assume) — confirmed 2026-08-30

### `core/transfer/build.gradle.kts` (the parts that matter)

```kotlin
plugins { alias(libs.plugins.android.library); `maven-publish` }

android {
    namespace = "com.transfer.flash.core.transfer"
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
    implementation(project(":core:network"))           // <-- ZERO imports in src/main (dead dep)
    implementation(project(":core:discovery"))         // <-- ZERO imports in src/main (dead dep)
    implementation(libs.androidx.core.ktx)             // <-- ZERO androidx imports in src/main (dead)
    implementation(libs.androidx.lifecycle.runtime.ktx)// <-- ZERO androidx imports in src/main (dead)
    testImplementation(libs.junit)
}
```

### `core/messaging/build.gradle.kts` (the parts that matter)

```kotlin
plugins { alias(libs.plugins.android.library); `maven-publish` }

android { /* identical shape; namespace = "com.transfer.flash.core.messaging" */ }
kotlin { explicitApi() }
dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)                 // public Flow/StateFlow → api
    implementation(project(":core:security"))          // <-- ZERO imports in src/main (dead dep)
    implementation(project(":core:network"))           // <-- ZERO imports in src/main (dead dep)
    implementation(project(":core:persistence"))       // LIVE — Room DAOs/Entities in RealFlashChatRepository
    implementation(libs.androidx.core.ktx)             // <-- ZERO androidx imports in src/main (dead)
    implementation(libs.androidx.lifecycle.runtime.ktx)// <-- ZERO androidx imports in src/main (dead)
    testImplementation(libs.junit)
}
```

**Dependency-usage findings (grep of `src/main`, R11 hygiene — excludes `build/`, `.git/`, `docs/`,
and the already-deleted `wslegacy/`):**

**`core:transfer`:**

- `:core:security` — **ZERO imports** (`grep '^import com.transfer.flash.core.security'` = none).
  Dead. Its only prior consumer was `wslegacy` code (deleted Phase 02). Relocate to
  `jvmAndAndroidMain.dependencies` for parity and flag `TODO(cleanup)`. **Do not delete here.**
- `:core:network` — **ZERO imports** in `src/main`. Between Phase 02's wslegacy deletion and Phase
  10 converting `:core:network` itself, transfer no longer references any network type by import.
  (The one cross-reference — `WsTransferModels.kt` importing `WsTransferServer` — is inside the
  orphaned/dead file; see the orphan note. It is **not** a live consumer and must not be used to
  justify keeping this dependency.) Relocate to `jvmAndAndroidMain` as a dead dep + flag.
- `:core:discovery` — **ZERO imports** in `src/main`. Dead. Relocate to `jvmAndAndroidMain`, flag.
- `:core:common` — used everywhere, including commonMain files → **commonMain `api`**.
- `androidx.core.ktx` / `androidx.lifecycle.runtime.ktx` — **ZERO `import androidx.*`** in
  `src/main`. Dead AARs; relocate to `androidMain.dependencies`, flag.
- `libs.junit` — test-only → `androidHostTest.dependencies`.

**`core:messaging`:**

- `:core:security` — **ZERO imports**. Dead. Relocate to `androidMain` (parity with its concrete
  repo tier; messaging's concrete repo is androidMain) — or, more precisely, to the tier where it
  *would* live if used. Since `RealFlashChatRepository` is the only concrete class and it is
  androidMain, `androidMain.dependencies` is the parity placement. Flag `TODO(cleanup)`.
- `:core:network` — **ZERO imports**. Dead. Same parity placement: `androidMain`. Flag.
- `:core:persistence` — **LIVE**. `RealFlashChatRepository.kt` imports 7 DAOs + 8 Entities. Since
  that class is androidMain, `:core:persistence` → **androidMain `implementation`** (it must not
  leak into the shared surface). Verify `:core:persistence` is already KMP (Phase 09) with its Room
  types in **androidMain** before placing this.
- `:core:common` — used everywhere, including commonMain files → **commonMain `api`**.
- `androidx.core.ktx` / `androidx.lifecycle.runtime.ktx` — **ZERO `import androidx.*`** in
  `src/main`. Dead AARs; relocate to `androidMain.dependencies`, flag.
- `libs.junit` — test-only → `androidHostTest.dependencies`.

> **Rule that governs every dead-dep relocation here:** this is a move-only phase. Deleting a
> dependency is a content decision with its own blast radius (it changes the published POM). Relocate
> each dead dep to the tier it *would* live in if used, and leave a `// TODO(cleanup): grep-unused as
> of Phase 11` marker. A dedicated cleanup phase deletes them after the migration is green.

### Directory facts

**`core:transfer`** production under
`core/transfer/src/main/java/com/transfer/flash/core/transfer/` — **24 `.kt`** in packages: root,
`chunked/`, `manifest/`, `model/`, `multistream/`, `policy/`, `protocol/`, `store/`, `wslegacy/`.
Phase 02 removes the five `wslegacy/` files (4 production + 1 test), leaving **20 production `.kt`**:
after the orphan judgment, **5 commonMain / 14 jvmAndAndroidMain / 0 androidMain / 1 deletion
(`model/WsTransferModels.kt`, recommended)**. Tests under `src/test/java/.../transfer/` — **14
files** (one of which, `wslegacy/WsPairingStoreTest.kt`, is deleted by Phase 02), leaving **13 test
files / `androidHostTest`** (incl. `protocol/WsTransferMessagesTest.kt`, which stays live — see orphan
note). The 13 test files carry **86 `@Test`** (89 counted in the tree minus `WsPairingStoreTest`'s 3).

**`core:messaging`** production under `core/messaging/src/main/java/.../messaging/` — **5 `.kt`** in
packages: root, `model/`, `protocol/`, `util/`. Tests under `src/test/java/.../messaging/` — **3
files**.

**Neither module** has an `AndroidManifest.xml`, `res/`, or `assets/` under `src/`, and **neither
module's host tests use Robolectric** — so **no `isIncludeAndroidResources`** is needed in either.

## Source-set strategy (D1 = A) — the exact wiring

Three source sets, identical topology to Phases 07/08/09/10. Copy `KMP_ANDROID_DSL_SNIPPET` and
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
        // ... dependency blocks (see build rewrites below)
    }
}
```

**Rules that do not change between phases (re-state them so a weak model cannot drift):**

- Always `getByName("commonMain")` / `getByName("androidMain")` / `getByName("jvmMain")` — **never**
  the typed accessors. Typed accessors are not registered for a hand-created intermediate set.
- The intermediate set is created with `create("jvmAndAndroidMain")` and wired with three explicit
  `dependsOn` calls. Do not rename it; do not use `jvmMain`/`androidMain` as the shared tier.
- KMP source roots are `src/<sourceSet>/kotlin/…`, **not** `src/<sourceSet>/java/…`.
- Unit tests live in **`androidHostTest`** — there is no `jvmTest` in this phase. The 86/16 `@Test`
  suites stay one suite each.
- `@JvmInline` **works in commonMain** (proven by `core:common` `FlashDeviceId.kt:6`). Do not move
  `FlashTransfer.kt` down just because of `@JvmInline`.

## Placement table — `core:transfer` (19 production files: 5 / 14 / 0) + 1 orphan deletion

Move files **unedited.** The R8-sensitive wire/codec files (`ChunkFrame`, `WsTransferMessages`) are
flagged; you may relocate them but must not touch a byte of their content (CONVENTIONS.md R8).

**commonMain — 5** (`src/commonMain/kotlin/com/transfer/flash/core/transfer/`):

| # | File (relative to package root) | Why commonMain |
|---|---|---|
| 1 | `FlashTransferRepository.kt` | repository **interface**; imports `FlashDevice`/`FlashResult`/`FlashTransfer`/`FlashTransferId`/`StateFlow` — all commonMain |
| 2 | `model/FlashTransfer.kt` | pure model with `@JvmInline`; no JDK/Android imports (`@JvmInline` is commonMain-safe) |
| 3 | `protocol/WsTransferMessages.kt` | **R8 wire** (FLSH v2 frame text); imports `FlashInternalApi` + `FlashTextFraming` (both commonMain). ⚠️ Orphaned-but-tested — see orphan note |
| 4 | `store/TransferStore.kt` | ADR-024 storage **port** (interface); no imports |
| 5 | `multistream/StreamChannel.kt` | pure single-purpose **interface** + `fun interface StreamChannelFactory`; no JDK/Android imports |

**jvmAndAndroidMain — 14** (`src/jvmAndAndroidMain/kotlin/com/transfer/flash/core/transfer/`):

| # | File | Why this tier (JDK, not `android.*`) |
|---|---|---|
| 1 | `chunked/Sha256.kt` | `java.security.MessageDigest` |
| 2 | `chunked/ChunkFrame.kt` | **R8 wire**; `java.io.ByteArrayOutputStream`/`java.nio.ByteBuffer`/`java.nio.ByteOrder` |
| 3 | `chunked/Chunker.kt` | `java.io.Closeable`/`InputStream`; contains `ChunkSource`(10)/`FileMeta`(21)/`ChunkPlan`(36)/`Chunker`(78)/`ChunkStream`(199) — all in one file |
| 4 | `chunked/ResumeBitVector.kt` | `java.util.BitSet` |
| 5 | `chunked/ReceivePipeline.kt` | `@Synchronized` (lines 100–165); contains `ChunkSink`(345)/`WholeFileDigestProvider`(354)/`ReceiveEvent`(359) |
| 6 | `chunked/SendPipeline.kt` | references `Chunker`/`ChunkFrame`/`Sha256`/`FileMeta` (all JVM tier); no JDK annotation of its own but pulls JVM types |
| 7 | `policy/DestinationPolicy.kt` | `java.io.*` + `@Volatile`/`@Synchronized` (lines 102–122) |
| 8 | `policy/RandomAccessChunkSink.kt` | wraps `ChunkSink` (jvmAndAndroidMain) |
| 9 | `RealFlashTransferRepository.kt` | `java.io.InputStream`/`java.util.UUID`/`java.util.concurrent.ConcurrentHashMap`. (Phase 03 removed its 4 fully-qualified `android.util.Log` calls; it is a **JDK** file here, not Android.) |
| 10 | `manifest/TransferManifest.kt` | `System.currentTimeMillis()` (line 29) is the only JDK call → jvmAndAndroidMain, **not** commonMain |
| 11 | `multistream/MultiStreamProgress.kt` | `@Synchronized` (lines 54/70/79) — **contains** the `MultiStreamResult` sealed interface at line 101 (there is **no** standalone `MultiStreamResult.kt`) |
| 12 | `multistream/MultiStreamDispatcher.kt` | `AtomicInteger`/`AtomicLong`/`AtomicBoolean`, `@Volatile`, `synchronized` |
| 13 | `multistream/MultiStreamReceiver.kt` | `synchronized(lock)` |
| 14 | `multistream/TransferCompletionStateMachine.kt` | JVM-backed state machine (`@Synchronized`/locking); **must not** be missed — it is the fourteenth row |

**androidMain — 0.** None, after Phase 02.

**The two extra `multistream/` files folded into the count above:** `StreamChannel.kt` is the only pure
file in the `multistream/` package (commonMain); the remaining four (`MultiStreamProgress`,
`MultiStreamDispatcher`, `MultiStreamReceiver`, `TransferCompletionStateMachine`) are all JVM-backed
and are rows 11–14. There is **no** separate `StreamChannelFactory.kt` file — it is
declared alongside `StreamChannel` in `StreamChannel.kt`. `MultiStreamResult` is a sealed interface
declared *inside* `MultiStreamProgress.kt`, not its own file.

> **Use this 5 / 14 / 0 split as the authoritative `git mv` target.** The header says **19**
> production files (5 + 14) because the post-Phase-02 tree is 20 live files minus the
> `model/WsTransferModels.kt` orphan — 20 − 1 = 19, matching the 19 rows exactly once you stop
> double-counting `TransferCompletionStateMachine`. The `WsTransferModels.kt` orphan is **not** part
> of the 19; it is handled separately below (recommend delete + ADR).

## Placement table — `core:messaging` (5 production files: 4 / 0 / 1)

**commonMain — 4** (`src/commonMain/kotlin/com/transfer/flash/core/messaging/`):

| # | File | Why commonMain |
|---|---|---|
| 1 | `FlashChatRepository.kt` | repository **interface** + sample impls; imports commonMain types + `StateFlow`/util |
| 2 | `model/FlashMessagingModels.kt` | imports `FlashDeviceId`/`FlashPeerPresence` (commonMain) |
| 3 | `util/FlashMessagingUtils.kt` | imports commonMain types only |
| 4 | `protocol/MessageWireFrame.kt` | **R8 wire**; no imports (pure serialization) |

**androidMain — 1** (`src/androidMain/kotlin/com/transfer/flash/core/messaging/`):

| # | File | Why pinned to Android |
|---|---|---|
| 1 | `RealFlashChatRepository.kt` | Phase 09 pins it to androidMain via 7 Room `Dao`s + 8 Room `Entity`s; also `SimpleDateFormat`/`Date`/`Locale`/`UUID`/`ConcurrentHashMap`. Do **not** pull to jvmAndAndroidMain (Room types are androidMain in Phase 09). |

## The two orphaned `Ws*` files — the judgment call this phase must NOT bury

Phase 02's **"do NOT delete `WsTransferMessages.kt` / `WsTransferModels.kt`"** warning
(`PHASE-02-delete-wslegacy.md:118–120`) is now **OUTDATED** for one of them. Phase 02 deleted the
only consumer (`WsTransferManager.kt`), so the warning that they are "live protocol, not legacy" no
longer holds for `WsTransferModels.kt`. Treat the two differently, and **do not silently decide**:

### `WsTransferMessages.kt` — orphaned-but-tested → **commonMain**

- Imports only `FlashInternalApi` + `FlashTextFraming` (both commonMain) → **commonMain-eligible**.
- Its only production reference was `wslegacy/WsTransferManager.kt` (deleted Phase 02).
- **But `WsTransferMessagesTest.kt` is LIVE** (6 `@Test`) and tests the wire format directly.
- **Placement: commonMain.** It is the R8 wire format that CONVENTIONS.md R8 protects; the live test
  keeps it load-bearing. Flag: *orphaned in production, kept because it is tested + R8-protected.*
  A later cleanup phase may delete it *after* deciding whether the desktop wire format needs it.

### `WsTransferModels.kt` — fully dead → **judgment call, needs an ADR note**

- Line 45 references `WsTransferServer.PREFERRED_PORT`, and **`WsTransferServer` is androidMain-only
  (Phase 10, row 12)**. Every `WsTransferModels` symbol (`WsPeer`, `WsTransferItem`,
  `WsDiscoveredDevice`, `WsTransferUiState`, enums) is `internal` and referenced **only** by
  `wslegacy/WsTransferManager.kt` (deleted Phase 02) and `LegacyDiscoveredDevice.kt`'s doc comment
  (also deleted Phase 02).
- **It cannot go to commonMain** (references an androidMain type) nor **jvmAndAndroidMain** (would
  break the `jvm()` compile under D1 = A, because `WsTransferServer` is Android-only). Under a
  move-only phase it is **orphaned dead code with no legal source-set home.**
- **Recommendation: delete it in Phase 11** (it has zero live references and cannot compile in any
  KMP tier), with an **ADR note** in `docs/decisions.md` stating that the Phase 02 "keep it" warning
  is superseded because its only consumer was deleted. This is a deliberate, documented deviation
  from the "move-only" rule — it removes dead code that has no KMP home, rather than leaving an
  uncompilable file that would fail `compileKotlinJvm`.
- **Do NOT silently pick.** If you choose to keep it (e.g., to preserve history), you must instead
  place it in `androidMain` AND accept that it drags `WsTransferServer` transitively — which is
  wrong (it is `internal` dead code). The clean resolution is deletion + ADR. Confirm with the owner
  if there is any doubt, but the technical facts point one way.

## Cross-module dependency placement — the tricky part of this phase

**`core:transfer`:**

| Dependency | Old scope | New tier + scope | Reason |
|---|---|---|---|
| `:core:common` | `api` | **commonMain `api`** | used by commonMain files |
| `libs.kotlinx.coroutines.core` | `api` | **commonMain `api`** | public `Flow`/`StateFlow` in signatures |
| `:core:security` | `implementation` | **jvmAndAndroidMain `implementation`** | **DEAD** (0 imports); flag |
| `:core:network` | `implementation` | **jvmAndAndroidMain `implementation`** | **DEAD** (0 imports); flag |
| `:core:discovery` | `implementation` | **jvmAndAndroidMain `implementation`** | **DEAD** (0 imports); flag |
| `libs.androidx.core.ktx` | `implementation` | **androidMain `implementation`** | **DEAD**; flag |
| `libs.androidx.lifecycle.runtime.ktx` | `implementation` | **androidMain `implementation`** | **DEAD**; flag |
| `libs.junit` | `testImplementation` | **androidHostTest `implementation`** | host tests |

**`core:messaging`:**

| Dependency | Old scope | New tier + scope | Reason |
|---|---|---|---|
| `:core:common` | `api` | **commonMain `api`** | used by commonMain files |
| `libs.kotlinx.coroutines.core` | `api` | **commonMain `api`** | public `Flow`/`StateFlow` in signatures |
| `:core:security` | `implementation` | **androidMain `implementation`** | **DEAD** (0 imports); parity with concrete-repo tier |
| `:core:network` | `implementation` | **androidMain `implementation`** | **DEAD** (0 imports); parity |
| `:core:persistence` | `implementation` | **androidMain `implementation`** | **LIVE** — Room DAOs/Entities in androidMain `RealFlashChatRepository` |
| `libs.androidx.core.ktx` | `implementation` | **androidMain `implementation`** | **DEAD**; flag |
| `libs.androidx.lifecycle.runtime.ktx` | `implementation` | **androidMain `implementation`** | **DEAD**; flag |
| `libs.junit` | `testImplementation` | **androidHostTest `implementation`** | host tests |

**Why `:core:common` and `coroutines.core` must be `api`, not `implementation`:** both repositories
expose `Flow`/`StateFlow` (and `FlashTransfer`/`FlashChatRepository` return `:core:common` types) in
their public signatures. `api` was already correct in the current builds — preserve it, do not
"tidy" it to `implementation`.

**Why `:core:network` in transfer is dead but `:core:network` is the *same module* Phase 10
converted:** transfer never called network by import in live code; the only reference was inside the
orphaned `WsTransferModels.kt`. Phase 10 converted `:core:network` for its own sake (it is consumed
directly by `:app` and `:core:engine`); transfer's edge to it is a leftover. Relocate it as dead +
flag, do not let it leak into commonMain.

## The target `build.gradle.kts` files (full, paste-ready)

Fill the `/* PASTE … */` regions from the Phase 06 log (`KMP_ANDROID_DSL_SNIPPET`,
`KMP_HOST_TEST_BLOCK`). Everything else is literal.

### `core/transfer/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "com.transfer.flash.core.transfer"
        compileSdk = 35
        minSdk = 24
        // PASTE KMP_HOST_TEST_BLOCK from the Phase 06 log (withHostTest { } enabling the
        // Android host unit-test compilation + task). core:transfer needs NO Android resources,
        // so do NOT add isIncludeAndroidResources (no Robolectric-resource test here).
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
        }
        getByName("jvmAndAndroidMain").dependencies {
            // All three are grep-unused (0 imports) as of Phase 11 — the wslegacy-only consumers
            // were deleted in Phase 02. Relocated for parity; do NOT delete here.
            // TODO(cleanup): grep-unused as of Phase 11 — a later phase may delete these.
            implementation(project(":core:security"))
            implementation(project(":core:network"))
            implementation(project(":core:discovery"))
        }
        getByName("androidMain").dependencies {
            // Both AARs are grep-unused (0 androidx imports). TODO(cleanup): relocated, not deleted.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            // Keep the root coordinate stable for existing Android consumers resolving core-transfer.
            artifactId = artifactId.replace(project.name, "core-transfer")
        }
    }
}
```

### `core/messaging/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "com.transfer.flash.core.messaging"
        compileSdk = 35
        minSdk = 24
        // PASTE KMP_HOST_TEST_BLOCK (withHostTest { }). NO isIncludeAndroidResources.
        // PASTE consumer-proguard wiring: consumerProguardFiles("consumer-rules.pro")
    }

    jvm()   // plain jvm().

    sourceSets {
        val jvmAndAndroidMain = create("jvmAndAndroidMain")
        jvmAndAndroidMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)

        getByName("commonMain").dependencies {
            api(project(":core:common"))
            api(libs.kotlinx.coroutines.core)
        }
        getByName("androidMain").dependencies {
            // :core:persistence is LIVE — Room DAOs/Entities consumed by RealFlashChatRepository
            // (androidMain). It must stay androidMain so Room/SQLCipher stays off the desktop target.
            implementation(project(":core:persistence"))
            // :core:security and :core:network are grep-unused (0 imports). TODO(cleanup): relocated,
            // not deleted, under the move-only rule.
            implementation(project(":core:security"))
            implementation(project(":core:network"))
            // Both AARs are grep-unused (0 androidx imports). TODO(cleanup): relocated, not deleted.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace(project.name, "core-messaging")
        }
    }
}
```

**What changed vs. the old files, and why each change is safe:**

- `com.android.library` → `com.android.kotlin.multiplatform.library` + `kotlin.multiplatform` (AGP 9
  requirement; `com.android.library` is incompatible with the KMP plugin).
- Deleted `buildTypes`/`singleVariant`/`compileOptions`/`testInstrumentationRunner` — none exist in
  the KMP Android DSL. Host tests are enabled by `withHostTest { }` (omitting it = 0 tests silently
  pass, the classic trap). `consumerProguardFiles` moves inside `androidLibrary { }`.
- No `isIncludeAndroidResources` — neither module has `res/`/assets and no Robolectric-resource test.
- The publication block switches to the KMP
  `withType<MavenPublication>().configureEach { artifactId = …replace(project.name, "core-transfer") }`
  form (same rewrite every converted module uses; root coordinates stay `core-transfer` /
  `core-messaging`).

## Steps (do them in order; every Gradle command uses `--no-configuration-cache`)

### Step 1 — Record the baselines (paste the outputs into the log)

Before touching anything, capture the two numbers each phase must preserve. Run from repo root:

```bash
./gradlew :core:transfer:testDebugUnitTest --no-configuration-cache
./gradlew :core:messaging:testDebugUnitTest --no-configuration-cache
```

Record the **exact `@Test` count** each runs. The verified baselines are **86 tests across 13
classes** (transfer) and **16 tests across 3 classes** (messaging); if either reports a different
number, STOP — the inventory drifted and the placement table must be re-derived before moving files.
Also capture the current publish coordinates:

```bash
./gradlew :core:transfer:publishToMavenLocal --no-configuration-cache
./gradlew :core:messaging:publishToMavenLocal --no-configuration-cache
```

Confirm each emits `core-transfer` / `core-messaging` (root artifactId) today.

### Step 2 — Create the KMP source-set directories

KMP uses `kotlin/`, not `java/`. Create the roots (production) plus the host-test root for **each**
module:

**`core:transfer`:**

```bash
cd core/transfer/src
mkdir -p commonMain/kotlin/com/transfer/flash/core/transfer
mkdir -p jvmAndAndroidMain/kotlin/com/transfer/flash/core/transfer
mkdir -p androidMain/kotlin/com/transfer/flash/core/transfer
mkdir -p androidHostTest/kotlin/com/transfer/flash/core/transfer
```

Create the sub-package dirs as needed by the `git mv` targets below (`chunked/`, `manifest/`,
`model/`, `multistream/`, `policy/`, `protocol/`, `store/`).

**`core:messaging`:**

```bash
cd core/messaging/src
mkdir -p commonMain/kotlin/com/transfer/flash/core/messaging
mkdir -p androidMain/kotlin/com/transfer/flash/core/messaging
mkdir -p androidHostTest/kotlin/com/transfer/flash/core/messaging
```

Create the sub-package dirs (`model/`, `protocol/`, `util/`).

### Step 3 — Rewrite both `build.gradle.kts` files

Replace each with the paste-ready target above. Do this **before** moving files so the IDE/Gradle sees
the new source sets when it re-syncs. Do not run a build yet — the files are still in `src/main`.

### Step 4 — `git mv` the transfer production files (5 commonMain / 14 jvmAndAndroidMain / 0 androidMain)

`git mv` preserves history. Source root is
`core/transfer/src/main/java/com/transfer/flash/core/transfer/`; abbreviate as `$SRC` and the two
destinations as `$COMMON`, `$JVMAND`:

```bash
cd core/transfer
SRC=src/main/java/com/transfer/flash/core/transfer
COMMON=src/commonMain/kotlin/com/transfer/flash/core/transfer
JVMAND=src/jvmAndAndroidMain/kotlin/com/transfer/flash/core/transfer
mkdir -p $COMMON/model $COMMON/protocol $COMMON/store $COMMON/multistream
mkdir -p $JVMAND/chunked $JVMAND/policy $JVMAND/multistream $JVMAND/model $JVMAND/manifest
```

**commonMain (5):**

```bash
git mv $SRC/FlashTransferRepository.kt           $COMMON/FlashTransferRepository.kt
git mv $SRC/model/FlashTransfer.kt               $COMMON/model/FlashTransfer.kt
git mv $SRC/protocol/WsTransferMessages.kt       $COMMON/protocol/WsTransferMessages.kt
git mv $SRC/store/TransferStore.kt               $COMMON/store/TransferStore.kt
git mv $SRC/multistream/StreamChannel.kt         $COMMON/multistream/StreamChannel.kt
```

**jvmAndAndroidMain (14):**

```bash
git mv $SRC/chunked/Sha256.kt                    $JVMAND/chunked/Sha256.kt
git mv $SRC/chunked/ChunkFrame.kt                $JVMAND/chunked/ChunkFrame.kt
git mv $SRC/chunked/Chunker.kt                   $JVMAND/chunked/Chunker.kt
git mv $SRC/chunked/ResumeBitVector.kt           $JVMAND/chunked/ResumeBitVector.kt
git mv $SRC/chunked/ReceivePipeline.kt           $JVMAND/chunked/ReceivePipeline.kt
git mv $SRC/chunked/SendPipeline.kt              $JVMAND/chunked/SendPipeline.kt
git mv $SRC/policy/DestinationPolicy.kt          $JVMAND/policy/DestinationPolicy.kt
git mv $SRC/policy/RandomAccessChunkSink.kt      $JVMAND/policy/RandomAccessChunkSink.kt
git mv $SRC/RealFlashTransferRepository.kt       $JVMAND/RealFlashTransferRepository.kt
git mv $SRC/manifest/TransferManifest.kt         $JVMAND/manifest/TransferManifest.kt
git mv $SRC/multistream/MultiStreamProgress.kt      $JVMAND/multistream/MultiStreamProgress.kt
git mv $SRC/multistream/MultiStreamDispatcher.kt    $JVMAND/multistream/MultiStreamDispatcher.kt
git mv $SRC/multistream/MultiStreamReceiver.kt      $JVMAND/multistream/MultiStreamReceiver.kt
git mv $SRC/multistream/TransferCompletionStateMachine.kt $JVMAND/multistream/TransferCompletionStateMachine.kt
```

**the orphaned/dead `model/WsTransferModels.kt`:** see the orphan note. If you follow the deletion
recommendation, `rm` (or `git rm`) `$SRC/model/WsTransferModels.kt` **with an ADR note** (it cannot
compile in any KMP tier and has zero live references). If instead you keep it, you must address the
compile break; the record above explains why keeping it is wrong. `TransferManifest.kt` is already in
the jvmAndAndroidMain block above — its placement note follows.

> **Placement-note for `TransferManifest.kt`:** the *only* JDK call is `System.currentTimeMillis()`
> at line 29. Its `internal` data classes are self-referenced only. Under any JDK-tier placement it
> compiles; it must **not** go to commonMain (JDK `System`). → **jvmAndAndroidMain.**

**Count check — the load-bearing assertion (post-Phase-02, 19 production renames + 1 deletion):**

```bash
git status --short | grep '^R' | wc -l      # expect 19 production renames (5 commonMain + 14
                                           # jvmAndAndroidMain) if WsTransferModels is deleted
git status --short | grep '^D' | wc -l      # expect 1 (the git-rm'd model/WsTransferModels.kt), or
                                           # 6 if you also count the 5 Phase-02 deletions still staged
find $SRC -name '*.kt' | wc -l              # expect 0 — src/main must be empty of .kt
```

If `src/main` still has `.kt` files, you missed one. (If you do NOT delete `WsTransferModels.kt`, cast
the find against the leftover — but see the orphan note for why that is not a valid end state.)

### Step 5 — `git mv` the messaging production files (4 commonMain / 0 jvmAndAndroid / 1 androidMain)

```bash
cd core/messaging
SRC=src/main/java/com/transfer/flash/core/messaging
COMMON=src/commonMain/kotlin/com/transfer/flash/core/messaging
ANDROID=src/androidMain/kotlin/com/transfer/flash/core/messaging
mkdir -p $COMMON/model $COMMON/protocol $COMMON/util
git mv $SRC/FlashChatRepository.kt            $COMMON/FlashChatRepository.kt
git mv $SRC/model/FlashMessagingModels.kt     $COMMON/model/FlashMessagingModels.kt
git mv $SRC/util/FlashMessagingUtils.kt       $COMMON/util/FlashMessagingUtils.kt
git mv $SRC/protocol/MessageWireFrame.kt      $COMMON/protocol/MessageWireFrame.kt
git mv $SRC/RealFlashChatRepository.kt        $ANDROID/RealFlashChatRepository.kt
```

**Count check:**

```bash
git status --short | grep '^R' | wc -l        # expect 5 production renames
find $SRC -name '*.kt' | wc -l                # expect 0 — src/main must be empty of .kt
```

### Step 6 — `git mv` the test files to `androidHostTest`

Both modules keep **one** test suite each (no `jvmTest` split). Move the whole tree by sub-package.

**`core:transfer` (13 files — `wslegacy/WsPairingStoreTest.kt` is already deleted by Phase 02):**

```bash
cd core/transfer
TSRC=src/test/java/com/transfer/flash/core/transfer
THOST=src/androidHostTest/kotlin/com/transfer/flash/core/transfer
mkdir -p $THOST
# move every .kt under the test package tree, preserving sub-package dirs:
git mv $TSRC $THOST/..
```

If Git refuses the directory move (some Windows builds do), move per-subpackage then the root
`.kt`. After the move:

```bash
find src/test -name '*.kt' | wc -l            # expect 0 — src/test emptied
find $THOST -name '*.kt' | wc -l              # expect 13
```

**`core:messaging` (3 files):**

```bash
cd core/messaging
TSRC=src/test/java/com/transfer/flash/core/messaging
THOST=src/androidHostTest/kotlin/com/transfer/flash/core/messaging
mkdir -p $THOST
git mv $TSRC $THOST/..
find src/test -name '*.kt' | wc -l            # expect 0
find $THOST -name '*.kt' | wc -l              # expect 3
```

### Step 7 — Compile the shared surface for desktop JVM

This is the proof the phase exists for — the shared half of both modules builds off Android:

```bash
./gradlew :core:transfer:compileKotlinJvm --no-configuration-cache
./gradlew :core:messaging:compileKotlinJvm --no-configuration-cache
```

Must be **BUILD SUCCESSFUL**. If it fails:

- `Unresolved reference: FlashTransfer` / `FlashPeerPresence` / `FlashInternalApi` (any `:core:common`
  type in a commonMain file) ⇒ that upstream type is **not** commonMain — return to Preconditions #1
  and fix the upstream module, don't move the file down to "solve" it.
- `Unresolved reference: android` / `Log` in a jvmAndAndroidMain or commonMain file ⇒ a file was
  placed too low; it belongs in androidMain. **But note:** `RealFlashChatRepository` is androidMain
  and references Room — if it appears in a jvmAndAndroidMain compiler error, you have placed it wrong.
- `Unresolved reference: java`/`javax` in a **commonMain** file ⇒ that file needs the JDK; move it to
  jvmAndAndroidMain.
- `Unresolved reference: WsTransferServer` (the orphaned `WsTransferModels`) ⇒ exactly why it cannot
  be placed in commonMain or jvmAndAndroidMain. If you kept it, this is the proof it must go —
  resolve per the orphan note (delete + ADR).

### Step 8 — Run the Android host test suites; assert the baseline counts

```bash
./gradlew :core:transfer:testAndroidHostTest --no-configuration-cache
./gradlew :core:messaging:testAndroidHostTest --no-configuration-cache
```

Must be **BUILD SUCCESSFUL running exactly the Step 1 baselines — 86 `@Test` / 13 classes** (transfer)
and **16 `@Test` / 3 classes** (messaging). A green build running 0 tests means `withHostTest { }` was
omitted or the tests did not land in `androidHostTest` — that is the silent-failure trap, not a pass.

### Step 9 — Publish to Maven Local; confirm the coordinates + variants

```bash
./gradlew :core:transfer:publishToMavenLocal --no-configuration-cache
./gradlew :core:messaging:publishToMavenLocal --no-configuration-cache
```

Inspect `~/.m2/repository/<group>/`. Confirm the **root** `core-transfer` / `core-messaging`
coordinates are still emitted (matching Step 1), now alongside `-android` and `-jvm` variants with
`.module` metadata.

### Step 10 — Prove the Android app still resolves and builds

```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

Must be **BUILD SUCCESSFUL**. `:app` consumes both modules via the root coordinates and must get the
`-android` variants transparently. A failure here means an Android variant's API surface changed — it
must not have (move-only).

## Verification gate — all relevant greens (paste outputs into the log)

1. `:core:transfer:compileKotlinJvm` — **BUILD SUCCESSFUL** (shared transfer surface builds off
   Android, incl. the FLSH v2 wire format). — `:core:messaging:compileKotlinJvm` — **BUILD SUCCESSFUL**.
2. `:core:transfer:testAndroidHostTest` — **BUILD SUCCESSFUL, exactly 86 `@Test` / 13 classes**
   (equal to Step 1 baseline; a 0-test "pass" is a failure). —
   `:core:messaging:testAndroidHostTest` — **BUILD SUCCESSFUL, exactly 16 `@Test` / 3 classes**.
3. `:core:transfer:publishToMavenLocal` — root `core-transfer` **plus** `-android` **and** `-jvm`
   variants with `.module` metadata. — `:core:messaging:publishToMavenLocal` — root `core-messaging`
   **plus** `-android` **and** `-jvm`.
4. `:app:assembleDebug` — **BUILD SUCCESSFUL** (both Android consumers unregressed).

Any red ⇒ do not log PASS and do not proceed to Phase 12. Fix the owning cause (placement / upstream
commonMain / build DSL) and re-run.

## Do NOT

- **Do NOT edit file contents.** This is a move-only phase. The **R8-sensitive wire/codec files**
  (`WsTransferMessages`, `MessageWireFrame`, `ChunkFrame`) must not have a byte of content changed. No
  reformatting, no import reordering, no visibility tweaks.
- **Do NOT introduce an `expect`/`actual`** for anything here. If a file cannot be placed, resolve it
  by placement or (for the dead orphan) documented deletion, not by inventing a platform abstraction.
- **Do NOT pull `RealFlashChatRepository` (messaging) down to `jvmAndAndroidMain`.** It is pinned to
  androidMain by Phase 09's Room types. Attempting it drags Room into the desktop target.
- **Do NOT put `RealFlashTransferRepository` (transfer) in androidMain.** Its remaining coupling is
  JDK (`InputStream`/`UUID`/`ConcurrentHashMap`), and Phase 03 removed its `android.util.Log`. It is
  a **jvmAndAndroidMain** file. Only if Phase 03 was **not** yet applied would it still carry
  `android.util.Log` — in that case keep it in **androidMain** and do Phase 03 first. (Read Phase 03
  before proceeding.)
- **Do NOT delete the dead dependencies** (`:core:security`, `:core:network`, `:core:discovery` in
  transfer; `:core:security`, `:core:network` in messaging; the two `androidx` AARs in each).
  Relocate + flag `TODO(cleanup)`; a later phase deletes them.
- **Do NOT delete `WsTransferMessages.kt`** — it is R8-protected and has a live test. Handle it via the
  orphan note. (The warning that was *outdated* is only about `WsTransferModels.kt`.)
- **Do NOT downgrade `:core:common` / `coroutines.core` from `api` to `implementation`.** They are in
  public signatures; `api` is load-bearing for transitive consumers.
- **Do NOT use `jvm("desktop")`, typed source-set accessors, or `src/**/java/` roots.**
- **Do NOT omit `withHostTest { }`** — omitting it produces a green build running 0 tests. And do NOT
  add `isIncludeAndroidResources` — neither module has Android resources or a Robolectric-resource
  test.
- **Do NOT change the `core-transfer` / `core-messaging` root coordinates.**
- **Do NOT run mid-migration Gradle without `--no-configuration-cache`.**
- **Do NOT silently pick a fate for `WsTransferModels.kt`.** It is a documented deviation
  (delete + ADR) — record it, do not bury it.

## Completion checklist

- [ ] Both `build.gradle.kts` rewritten to the KMP form (plugins, `androidLibrary`, `jvm()`, three
      source sets wired with `getByName`, dependency tiers per the tables, `withHostTest`, no
      `isIncludeAndroidResources`, KMP publication rewrites).
- [ ] `core:transfer`: 19 production files moved (5 commonMain / 14 jvmAndAndroidMain / 0 androidMain) + `WsTransferModels.kt` handled
      + `model/WsTransferModels.kt` resolved per the orphan note (deleted + ADR, or documented keep);
      `src/main` empty of `.kt`.
- [ ] `core:messaging`: 5 production files moved (4 commonMain / 0 jvmAndAndroidMain / 1 androidMain);
      `src/main` empty of `.kt`.
- [ ] `core:transfer`: 13 test files moved to `androidHostTest`; `src/test` empty.
- [ ] `core:messaging`: 3 test files moved to `androidHostTest`; `src/test` empty.
- [ ] `:core:transfer:compileKotlinJvm` green; `:core:messaging:compileKotlinJvm` green.
- [ ] `:core:transfer:testAndroidHostTest` green at **86 tests**; `:core:messaging:testAndroidHostTest`
      green at **16 tests**.
- [ ] Both `publishToMavenLocal` emit root + `-android` + `-jvm` with `.module`.
- [ ] `:app:assembleDebug` green.
- [ ] Dead deps relocated + `TODO(cleanup)` flagged, not deleted.
- [ ] `WsTransferModels.kt` fate documented in `docs/decisions.md` (ADR) if deleted; the outdated
      Phase 02 warning cross-referenced.
- [ ] `WsTransferMessages.kt` orphaned-but-tested status recorded in the log (kept in commonMain).
- [ ] Log entry appended to `docs/migration/logs/migration.md` (append-only) with pasted outputs.

## Rollback

Move-only + build-DSL change, so rollback is mechanical and total (for each module):

```bash
git restore --staged core/transfer core/messaging
git checkout -- core/transfer core/messaging
git clean -fd core/transfer/src/commonMain core/transfer/src/jvmAndAndroidMain \
               core/transfer/src/androidMain core/transfer/src/androidHostTest \
               core/messaging/src/commonMain core/messaging/src/androidMain \
               core/messaging/src/androidHostTest
```

This un-stages the renames, restores the original `build.gradle.kts` and `src/main`/`src/test` trees,
and removes the empty KMP source-set dirs. If `WsTransferModels.kt` was deleted, `git restore` brings
it back. If `publishToMavenLocal` ran, stale `~/.m2/repository/.../core-transfer*` /
`core-messaging*` entries are harmless (overwritten on the next real publish).

## Log entry (mandatory)

Append **one** entry to `docs/migration/logs/migration.md` (append-only, newest at the bottom —
never edit an earlier entry; never write PASS without pasted command output). Include:

- The Step 1 **baselines** (`testDebugUnitTest` counts = 86/13 classes for transfer, 16/3 for
  messaging) and the current publish coordinates (`core-transfer`, `core-messaging`), with pasted
  output.
- Confirmation of the split moved: **transfer 5 commonMain / 14 jvmAndAndroidMain / 0 androidMain**
  + 1 deletion (`model/WsTransferModels.kt`) (post-Phase-02), **messaging 4 commonMain /
  0 jvmAndAndroidMain / 1 androidMain**; `src/main` empty (paste the `find … | wc -l` = 0 and
  `git status --short | grep -c '^R'` = 19 / `grep -c '^D'` = 1 counts).
- Test moves: transfer 13 `androidHostTest`, messaging 3 `androidHostTest`.
- The four verification-gate outputs pasted: both `compileKotlinJvm` SUCCESS; both `testAndroidHostTest`
  SUCCESS **at the stated counts** (86 / 16, not just "passed"); both `publishToMavenLocal` emitting
  root + `-android` + `-jvm`; `:app:assembleDebug` SUCCESS.
- The dead dependencies relocated + flagged (transfer → jvmAndAndroidMain: `:core:security`,
  `:core:network`, `:core:discovery`; messaging → androidMain: `:core:security`, `:core:network`;
  both → androidMain: `androidx.core.ktx`, `androidx.lifecycle.runtime.ktx`), explicitly noted as
  grep-unused and **not** deleted.
- **`WsTransferModels.kt`** — the explicit judgment call: state its fate (recommend delete + ADR),
  cite that its only consumer (`WsTransferManager.kt`) was deleted in Phase 02, state that Phase 02's
  "keep it" warning (lines 118–120) is now superseded, and cross-reference the ADR.
- **`WsTransferMessages.kt`** — orphaned-but-tested: kept in commonMain because it is R8-protected
  and its 6-test suite is live.
- **`TransferManifest.kt`** — note the `System.currentTimeMillis()` line 29 as the JDK pin and that
  it went to jvmAndAndroidMain.
- The final statement: **`core:transfer` and `core:messaging` are KMP; the shared transfer surface
  (repository contract, FLSH v2 wire format + chunk codec, chunking/hashing/resume machinery, the
  multi-stream engine interfaces, the store port) compiles for desktop JVM; the shared messaging
  surface (repository contract + sample impls, messaging UI models, grouping/util, message wire
  frame) compiles for desktop JVM; the Android host suites are unregressed at 86 + 16 tests; the
  concrete repos stay bound to JDK/Room (`RealFlashTransferRepository` jvmAndAndroidMain,
  `RealFlashChatRepository` androidMain) and desktop-facing adapters are deferred to Phase 12 + 09.**
