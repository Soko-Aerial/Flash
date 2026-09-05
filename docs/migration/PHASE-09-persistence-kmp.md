# Phase 09 — KMP conversion: `core:persistence`

> # ⛔ SUPERSEDED — DO NOT EXECUTE THIS FILE
>
> **2026-09-05.** This document is written for **D1 = A + D5 = A**. The repo is
> **D1 = B + D5 = C** (see `DECISIONS.md`; D1 = B was reaffirmed 2026-09-03). Executing it would
> violate CONVENTIONS.md R5 — it prescribes `jvmAndAndroidMain`, a source set that D1 = B forbids
> and that must never be created.
>
> Its own D5 gate is the instruction that retired it: *"**`B` or `C`** → **STOP and switch
> documents.** … **Stop, tell the human B/C was chosen, and author a dedicated PHASE-09B rather
> than stretching this move-only document.**"*
>
> **→ Execute [PHASE-09B-persistence-room-kmp.md](PHASE-09B-persistence-room-kmp.md) instead.**
>
> Two further factual errors, measured 2026-09-05 and recorded here per R1 rather than fixed in
> place:
> - The header below says the desktop `jvm()` target *"has no Room"*. It does.
>   `androidx.room:room-runtime:2.8.4` — the version already pinned — publishes `room-runtime-jvm`
>   plus Kotlin/Native variants. Room KMP therefore needs **no** `androidx.room3` bump, contrary to
>   this file's D5 = B/C box.
> - Its schema path is one directory level too shallow, and `schemas/…/2.json` does not exist.
>
> Still-accurate and reused by 09B: **Fact 2** (`testOptions.unitTests.isIncludeAndroidResources`
> becomes `withHostTest { isIncludeAndroidResources = true }`) and **Fact 3** (keep the schema
> directory; drop the `test` asset wiring).

**Blocked by:** Phase 06 (the pilot — DSL shape, host-test block, publication rewrite) **and
Decision D5** (desktop persistence strategy). **D1 must already be `A`** (it gated Phase 06);
this phase is written for **D1 = A + D5 = A**. If D5 is still `_pending_`, or is answered
`B`/`C`, **STOP** and read the "D5 gate" box below — under `B`/`C` this is a fundamentally
different, much larger phase (plugin, group, and `expect`/`actual` DB constructor all change).
**Risk: HIGH — the hardest core module.** It is the first module that combines **Room + KSP**
(a code-generating annotation processor) with **Robolectric** host tests. Two build-config
facts, both easy to get wrong, decide whether it builds at all: (1) in a KMP project the KSP
processor must be scoped **per target** (`add("kspAndroid", …)`) — the catch-all `ksp(…)` is
deprecated in KSP2 and would try to run Room codegen for the desktop `jvm()` target, which has
no Room, and fail; (2) the one Robolectric test needs Android resources turned on **inside the
host-test block** (`isIncludeAndroidResources = true`). Get those two right and the module is,
like 07 and 08, a **pure file-move** with zero content edits (R8: Room `@Entity`/`@Dao`/
`@Database`/migrations are move-only).

## What this phase is actually for

Convert `:core:persistence` from `com.android.library` (+ `ksp` + `maven-publish`) to the KMP
plugin with an Android target and a desktop `jvm()` target, **moving files into source sets
without editing their contents**. When it is done:

- `:core:persistence:compileKotlinJvm` compiles — proving the **pure** persistence contracts
  (`ConversationPreview`, `ConversationUnread`, `RetentionPolicy` + `PrunableSource`) build for
  **desktop**. Under D5 = A that is *all* desktop needs from this module; the Room stack stays
  Android-only and desktop never sees it.
- The Android host (unit) tests still run and still pass **at the same count** as before —
  including the Robolectric `FlashDatabaseInvariantTest`.
- `:core:persistence` still publishes as `core-persistence`.
- `:app:assembleDebug` still resolves it.

## Why persistence is *mostly* androidMain (unlike 07/08)

`core:security` (07) split 4/10/3 and `core:discovery` (08) split 10/2/4 toward the shared
sets, because their logic was JCA/pure-Kotlin. `core:persistence` is the opposite: its reason
for existing is the **Room database** — `@Entity`/`@Dao`/`@Database` classes and the two
DataStore settings files are all `androidx.*`/`android.*`, so **27 of its 30 files are
androidMain**. Only three files are pure Kotlin data/policy with no platform type. That is
expected and correct: under D5 = A the encrypted Room DB is an **Android-only capability**, and
this phase's job is to make that explicit in the source-set layout, not to port Room to desktop.

## THE D5 GATE — read and resolve before writing any code

This phase **cannot start** until `docs/migration/DECISIONS.md` shows D5 answered. You (the
executing agent) **may not pick D5** — it is a human decision with permanent consequences for
whether desktop can persist encrypted data (CONVENTIONS.md; the DECISIONS.md preamble). Find
the line `## D5 —` and read its `**ANSWER:**`.

- **`_pending_`** → **STOP.** Tell the human: *"Phase 09 (persistence) is blocked on D5. It
  decides whether desktop persists data at all. I cannot choose it. The three options and their
  consequences are laid out in PHASE-09; please answer D5 in DECISIONS.md, then I will proceed."*
  Do not guess; do not "temporarily" pick A to make progress.
- **`A`** → proceed with this document as written (the common case; the rest assumes A).
- **`B` or `C`** → **STOP and switch documents.** This file converts the *existing Android*
  Room stack into source sets; under B/C the persistence layer is *re-platformed*, not moved.
  See the "D5 = B/C box" below for why, and do not improvise it from here.

### D5 — the decision, in full (this is the choice the human is making)

**Where things stand today (verified in this module on 2026-08-30):** persistence uses **Room
`androidx.room` 2.x** with the **SQLCipher** encrypted driver (`net.zetetic:sqlcipher-android
4.17.0`) and **`androidx.datastore`** for settings. 27 of 30 files are Android-coupled. ADR-024
already inverted the *engine's* coupling behind ports (`TransferStore` lives in `:core:transfer`;
the Room adapter `RoomTransferStore` lives in `:core:engine`), so nothing *outside* this module
imports Room directly — but the Room classes themselves are unavoidably Android here.

| | **D5 = A — desktop has no persistence (RECOMMENDED for Phase 1)** | **D5 = B — Room 3 KMP** | **D5 = C — Room 3 KMP + encrypted desktop** |
|---|---|---|---|
| What changes here | Nothing re-platformed. Room/SQLCipher/DataStore all move to **androidMain**; `jvm()` compiles only the 3 pure files; `jvmMain` is **empty**. | Migrate to **`androidx.room3` 3.0.2** (group rename + major bump), add `expect object : RoomDatabaseConstructor`, per-platform `getDatabaseBuilder`, bundled SQLite driver. Room classes move to **commonMain**. | Everything in B, **plus** an encrypted desktop SQLite driver (see options below). |
| Desktop transfers | **Work** (engine uses in-memory adapters for the ports). | Work. | Work. |
| Desktop history / resume-across-restart | **Do not exist** (in-memory; lost on restart). Phase 16's G7/Phase 23's M6 are logged **DEFERRED (D5=A)**. | Exist, persisted. | Exist, persisted **and encrypted**. |
| Encryption charter (principle 3 / R8: never weaken encryption to ease the desktop port) | **Satisfied trivially** — desktop persists *nothing*, so there is nothing to encrypt. | **VIOLATED** — the Room 3 bundled driver is **not** SQLCipher; desktop data would be written in **plaintext**. B alone is not permissible under the charter. | **Satisfied** — desktop storage is encrypted; this is the only "desktop persists" option the charter allows. |
| Cost / risk | **Lowest.** Pure file-move; keeps Phase 16 reachable now. | High: schema-constructor rework, re-verify every DAO on JVM, migration parity across two Room majors. | Highest: B + integrate/evaluate an encrypted JVM SQLite driver. |
| Desktop encrypted-driver candidates (C only) | — | — | `SQLCipherMultiplatform`, Bloomberg **Selekt**, or **Zetetic SQLCipher-for-JDBC** (commercial). Each needs a licensing + KMP-support spike before C is committed. |

**Recommendation (for the human, not a choice the agent makes): D5 = A for Phase 1.** It ships
desktop transfers, satisfies the encryption charter with no caveat, and keeps the two hard
gates (16, 23) reachable now; desktop persistence (B→C) can be a *follow-on* once the product
is proven cross-platform. The one thing forbidden is **B without C** — that would put plaintext
Flash data on desktop disk, which R8 prohibits. Record the human's answer in DECISIONS.md; this
phase then proceeds only if the answer is A.

> ### D5 = B/C box — do NOT execute this document if D5 ∈ {B, C}
> Under B/C, `core:persistence` is re-platformed, not relocated:
> - **Plugin/deps:** `androidx.room` → **`androidx.room3` 3.0.2** (new group, major bump —
>   a CONVENTIONS R10 version change that must be pre-approved), bundled SQLite driver added.
> - **Source sets inverted:** the `@Entity`/`@Dao`/`@Database` classes move to **commonMain**
>   (Room 3 is KMP), not androidMain — the exact opposite of the table below.
> - **New seams:** `expect object FlashDatabaseConstructor : RoomDatabaseConstructor<FlashDatabase>`
>   with `androidMain`/`jvmMain` `actual`s, and a per-platform `getDatabaseBuilder(...)`.
> - **KSP:** processors added for **both** `kspAndroid` **and** `kspJvm` (desktop codegen).
> - **Encryption (C):** integrate the chosen encrypted JVM driver; re-run the security review.
> This is a separate phase's worth of work and re-verification. **Stop, tell the human B/C was
> chosen, and author a dedicated PHASE-09B rather than stretching this move-only document.** The
> remainder of THIS file assumes **A**.

## Preconditions — do not start until all are true

1. **Phase 06 merged and logged**, and its five facts are recorded (`KMP_ANDROID_DSL_SNIPPET`,
   `KMP_HOST_TEST_BLOCK`, `KMP_JVM_TARGET_DSL`, `KMP_LOCAL_DEP_SELECTION`,
   `ANDROID_UNIT_TEST_TASK`). This phase copies them; it never re-derives the Android-KMP DSL.
2. **D1 = `A`** and **D5 = `A`** in `DECISIONS.md` (see the gate above). If D1 = B, the whole
   migration's shared-set strategy differs — stop and consult Phase 06/07's D1 = B guidance.
3. **Phases 07 and 08 are complete and logged.** `:core:persistence` depends only on
   `:core:common` (converted in 06), so ordering-wise it *could* run right after 06 — but the
   topological plan runs security 07 → discovery 08 → persistence 09, and later phases assume
   that order. Follow it.
4. **Clean working tree** on the migration branch (`git status` clean). Converting a module
   rewrites its file layout; start from a known state so `git mv` history stays legible.

## Verified starting state (read this, do not assume)

Confirmed by reading the module on 2026-08-30. If any fact is no longer true when you run the
phase, stop and re-inventory — a merge may have moved a file.

**Current `core/persistence/build.gradle.kts` (the parts that matter):**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)          // KSP is applied — persistence is the FIRST KMP module with it
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.persistence"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    sourceSets {
        // Wires the exported Room schema JSONs in as TEST assets (for MigrationTestHelper-style
        // tests). NOTE: no current test reads them (see "schema export vs schema assets" below).
        getByName("test").assets.directories.add("$projectDir/schemas")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }   // Robolectric needs this
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { explicitApi() }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")   // where KSP WRITES exported schemas
    arg("room.incremental", "true")
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)                 // <-- catch-all ksp(); becomes kspAndroid(...)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    publishing { publications { register<MavenPublication>("release") {
        artifactId = "core-persistence"; afterEvaluate { from(components["release"]) } } } }
}
```

**Directory facts (verified):**

- Production sources: `core/persistence/src/main/java/com/transfer/flash/core/persistence/` —
  **30 `.kt` files** (all Kotlin despite the `java/` dir; KMP wants `.../src/<set>/kotlin/...`).
- Tests: `core/persistence/src/test/java/com/transfer/flash/core/persistence/` — **4 `.kt`
  files**, JUnit4, one of them Robolectric.
- **No `AndroidManifest.xml`, no `src/main/res/`, no `src/main/assets/` anywhere under `src/`**
  (verified by glob for `*.{xml,json,properties,txt,pro}` under `src` → **no files**). So
  `androidResources` do not need enabling for *production*; the Robolectric test still needs
  `isIncludeAndroidResources` for its *host-test* runtime (see below).
- The exported Room schemas live at **`core/persistence/schemas/`** (module root, NOT under
  `src/`) — currently `1.json` and `3.json`; **`2.json` is missing**. `consumer-rules.pro` and
  `proguard-rules.pro` sit at the module root.
- **`libs` aliases (confirmed in `gradle/libs.versions.toml`):** `libs.plugins.ksp` =
  `com.google.devtools.ksp` **2.3.11** (this is **KSP2** — hence the per-target requirement);
  `libs.androidx.room.*` = `androidx.room` (Room **2.x**, *not* room3); `libs.sqlcipher.android`
  = `net.zetetic:sqlcipher-android 4.17.0`; `libs.robolectric` = 4.16.1.

## The source-set strategy (D1 = A) and the placement rule

Same four-set shape as 07/08 (wired in Step 3):

```
commonMain ──▶ jvmAndAndroidMain ──▶ { androidMain , jvmMain }
(pure Kotlin)   (java.*/javax.* ok)    (android.* only in androidMain)
```

```kotlin
val jvmAndAndroidMain = create("jvmAndAndroidMain")
jvmAndAndroidMain.dependsOn(getByName("commonMain"))
getByName("androidMain").dependsOn(jvmAndAndroidMain)
getByName("jvmMain").dependsOn(jvmAndAndroidMain)
```

**Placement rule (the one thing to get right):** (1) imports `android.*` **or**
`androidx.*` → **androidMain**; (2) else imports `java.*`/`javax.*` or JDK-only API →
**jvmAndAndroidMain**; (3) else pure Kotlin → **commonMain**, *but only if every same-module
symbol it references is also commonMain-eligible* (transitive rule). Note for this module:
**`androidx.room` and `androidx.datastore` are `androidx.*`** → their files are androidMain.
`kotlinx.coroutines.*` is multiplatform and is **not** a trigger (a `Flow`-returning DAO is
androidMain because of `androidx.room`, not because of coroutines).

## The verified file-placement table

Paths are relative to `core/persistence/src/main/java/com/transfer/flash/core/persistence/`.
Every file **moves unedited** (`git mv`). **R8:** all Room `@Entity`/`@Dao`/`@Database`/
migration files are move-only — relocating them is allowed; changing a byte of their contents,
package, annotations, or SQL is **not** (a changed `@Query` or column can corrupt the on-disk
schema and break migrations for existing Android users).

### → `commonMain` (3 files) — pure Kotlin, referenced only by androidMain (legal, one-way)

| File | Why commonMain is safe |
|---|---|
| `db/dao/ConversationPreview.kt` | Pure `data class ConversationPreview(conversationId, previewText, sentAt)`. No imports. It is a DAO **projection result**, not a `@Dao`. The androidMain DAO that returns it may reference a commonMain type (androidMain → commonMain is allowed). |
| `db/dao/ConversationUnread.kt` | Pure `data class ConversationUnread(conversationId, unread)`. No imports. Same projection reasoning. |
| `retention/RetentionPolicy.kt` | Pure `object RetentionPolicy` + `data class PrunableEntry` + `interface PrunableSource`; explicitly DAO-free (the port for pruning). No `java`/`javax`/`android`/`androidx`. |

### → `jvmAndAndroidMain` (0 files)

Nothing. No file here uses `java.*`/`javax.*` *without* also using `androidx.room`/`android.*`.
This set exists (it is wired) but stays empty — do not force a file into it.

### → `androidMain` (27 files) — `androidx.room` / `androidx.datastore` / `android.*`

| Group | Files | Trigger |
|---|---|---|
| Entities (11) | everything under `db/entity/` (`*Entity.kt`) | `@Entity` + `import androidx.room.*` (R8 — schema-defining) |
| DAOs (11) | everything under `db/dao/` **ending in `Dao.kt`** (i.e. all except the two projections above) | `@Dao` + `import androidx.room.*` (R8 — `@Query` SQL) |
| DB core (3) | `db/FlashDatabase.kt`, `db/FlashDatabaseOpener.kt`, `db/FlashMigrations.kt` | `androidx.room.Database/RoomDatabase/migration.Migration`, `android.content.Context`, `androidx.sqlite`, SQLCipher (R8 — `@Database`, versions, migrations) |
| Settings (2) | `settings/DiscoveryModeSetting.kt`, `settings/FlashSettingsDataStore.kt` | `androidx.datastore.*` + `java.io.File`/`IOException` |

> **The projection trap.** `ConversationPreview.kt` and `ConversationUnread.kt` live *inside*
> `db/dao/` next to the real DAOs, but they are **plain data classes with no `androidx.room`
> import** — they go to **commonMain**, not androidMain. This is why the entity/DAO bulk move
> below globs `db/dao/*Dao.kt` (which excludes them by name) and moves the two projections
> separately. Moving them to androidMain would still *compile*, but it needlessly pins a pure
> type to Android and blocks any future commonMain consumer — keep them in commonMain.

### Tests → `androidHostTest` (all 4, unmodified)

Move `src/test/java/.../persistence/` → `src/androidHostTest/kotlin/.../persistence/`:

| File | Note |
|---|---|
| `retention/RetentionPolicyTest.kt` | Pure JVM logic test. |
| `settings/DiscoveryModeSettingTest.kt` | DataStore settings test. |
| `settings/FlashSettingsDataStoreTest.kt` | DataStore settings test. |
| `db/FlashDatabaseInvariantTest.kt` | **Robolectric** (`@RunWith(RobolectricTestRunner)`, `@Config(sdk=[34])`). Opens Room **in-memory via `FlashDatabaseOpener.openInMemory(context)`** (framework SQLite, no SQLCipher `.so` on the JVM). This is the test that **requires `isIncludeAndroidResources = true`** in the host-test block. |

All 4 stay `androidHostTest` (they need `androidx.room` / Android stubs / Robolectric); do **not**
try to share any to a `commonTest`/`jvmTest` set — it changes the topology and risks the count.

## The three build-config facts that make or break this phase

These are the persistence-specific deltas versus the pilot (Phase 06 had no Room, no KSP, no
Robolectric). Getting them wrong is the top way a weaker model breaks the module.

### Fact 1 — KSP must be scoped to the Android target: `add("kspAndroid", …)`

`libs.plugins.ksp` is **KSP2** (2.3.11). In a KMP project the catch-all `ksp(libs.androidx.room.compiler)`
is **deprecated** and would attempt to run Room's annotation processor for **every** compilation
— including the desktop `jvm()` target, which has no Room on its classpath — and fail. Instead,
add the processor **only to the Android compilation** in a top-level `dependencies { }` block
(KSP configurations are named `ksp<TargetName>`):

```kotlin
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
}
```

Do **not** write `ksp(libs.androidx.room.compiler)`. There is **no** `kspJvm` under D5 = A
(desktop has no Room). Verified against kotlinlang.org/docs/ksp-multiplatform.html and
developer.android.com/kotlin/multiplatform/room. Confirm the exact config name for this repo in
Step 4 with `./gradlew :core:persistence:dependencies --no-configuration-cache | grep -i ksp`.

### Fact 2 — Robolectric needs Android resources turned on *inside the host-test block*

The old `android { testOptions { unitTests.isIncludeAndroidResources = true } }` has **no**
`testOptions.unitTests` path in the KMP library DSL. The property moves **directly into the
host-test block** (receiver `KotlinMultiplatformAndroidHostTest`):

```kotlin
// inside the androidLibrary { } — this is KMP_HOST_TEST_BLOCK from Phase 06, PLUS the resources line
withHostTest {
    isIncludeAndroidResources = true   // <-- persistence adds this; core:common (Phase 06) did not need it
}
```

Without it, `FlashDatabaseInvariantTest` fails at runtime (Robolectric cannot find the merged
resources/manifest). Other host-test properties available on the same receiver if ever needed:
`isReturnDefaultValues`, `enableCoverage`, `all { }`. Verify the exact member name against the
version-matched AGP KMP reference
(developer.android.com/reference/tools/gradle-api/…/com/android/build/api/dsl/KotlinMultiplatformAndroidHostTest);
if `isIncludeAndroidResources` is not present at your AGP version, the reference page for that
version lists the correct name — do not guess.

### Fact 3 — schema *export* (keep) vs schema *test-assets* (drop)

There are two schema wirings in the old build; they are **not** the same thing:

- **KSP `room.schemaLocation` arg → KEEP.** `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`
  is where KSP **writes** the exported schema JSONs at Android-compile time. Preserve it exactly
  (it is a `ksp {}` block, global to KSP; only `kspAndroid` runs, so it applies to the Android
  compile). Changing it would change what Room exports — a behaviour change (R10).
- **`sourceSets.getByName("test").assets … "$projectDir/schemas"` → DROP (safe).** This wired the
  schemas in as **test assets** for `MigrationTestHelper`-style tests. **No current test reads
  them** (`FlashDatabaseInvariantTest` uses `openInMemory`, not `MigrationTestHelper`; and
  `2.json` is missing so a 1→2→3 migration test cannot exist yet). Omit this line in the KMP
  rewrite. *If* a future migration test needs schema assets, that phase wires
  `androidHostTest` assets then — out of scope here. If Gate 2's count drops or the invariant
  test errors on a missing asset (it should not), re-add schema assets to the `androidHostTest`
  source set and re-run.

## Why `jvmMain` is empty under D5 = A (and must stay empty)

`compileKotlinJvm` builds the `jvm()` target from `commonMain + jvmAndAndroidMain` only. Here
that is just the **3 pure files** (`ConversationPreview`, `ConversationUnread`, `RetentionPolicy`
+ `PrunableSource`). That is the point: it proves desktop can consume the persistence *contracts*
without pulling in Room/SQLCipher, and it *proves desktop has no encrypted DB* — exactly the
D5 = A posture. **Do not** create a desktop Room implementation, an in-memory `TransferStore`,
or any `jvmMain` file in this phase. The desktop in-memory adapters that satisfy the ports
(so desktop transfers run without history) are wired later, in the **engine/desktop** phases
(they implement ports that live in `:core:transfer` etc.), **not** inside `:core:persistence`.
A `jvmMain` file here would be the module inventing a desktop persistence story it must not own.

## Steps

Do them in order; each ends with a check. All Gradle commands include `--no-configuration-cache`
(the KMP Android plugin is not config-cache-clean mid-migration; Phase 06 established this).

### Step 1 — Confirm the gates and pull Phase 06's facts

1. Re-read the **D5 gate** above against `DECISIONS.md`. If D5 ≠ `A`, stop now (pending → ask;
   B/C → author PHASE-09B). If D1 ≠ `A`, stop.
2. Open `docs/migration/logs/migration.md`, find the Phase 06 entry, copy the five recorded
   strings. You paste `KMP_ANDROID_DSL_SNIPPET` + `KMP_HOST_TEST_BLOCK` into Step 3 and use
   `ANDROID_UNIT_TEST_TASK` in Step 7. Do not re-invent the Android-KMP DSL.

### Step 2 — Record the baseline test count (before touching anything)

```bash
./gradlew :core:persistence:testDebugUnitTest --no-configuration-cache
```

Open `core/persistence/build/reports/tests/testDebugUnitTest/index.html` (or the XML under
`build/test-results/testDebugUnitTest/`) and record **total tests** as
`PERSISTENCE_TEST_BASELINE`. Note the Robolectric `FlashDatabaseInvariantTest` methods are part
of this count — they are the ones most likely to vanish if `isIncludeAndroidResources` is lost.
(If `testDebugUnitTest` is not the current task name, use whatever Android unit-test task exists
today; the point is a number to match in Step 7.)

### Step 3 — Rewrite `core/persistence/build.gradle.kts`

Replace the whole file with the following. Paste the Phase-06 snippets where marked. The only
persistence-specific additions versus Phase 07's build are: (a) the `ksp` **plugin** stays;
(b) `withHostTest { isIncludeAndroidResources = true }`; (c) Room/SQLite/SQLCipher/DataStore in
`androidMain.dependencies`; (d) the `add("kspAndroid", …)` block; (e) the `ksp { }` args block;
(f) Room/Robolectric test deps in `androidHostTest.dependencies`.

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library) // exact alias Phase 06 used
    alias(libs.plugins.ksp)                                   // KSP plugin stays; config becomes per-target
    `maven-publish`
}

kotlin {
    explicitApi()

    // ── Android target ── paste KMP_ANDROID_DSL_SNIPPET from the Phase 06 log verbatim, then
    //    add the host-test resources line (persistence-specific, for Robolectric).
    androidLibrary {
        namespace = "com.transfer.flash.core.persistence"
        compileSdk = 35
        minSdk = 24
        // <<PASTE KMP_HOST_TEST_BLOCK HERE>> and INSIDE it add:
        //     isIncludeAndroidResources = true    // Fact 2 — Robolectric FlashDatabaseInvariantTest
        // <<PASTE Phase 06's consumer-proguard wiring (consumer-rules.pro) identically>>
    }

    jvm()   // desktop target — plain jvm(), never jvm("desktop")

    sourceSets {
        val jvmAndAndroidMain = create("jvmAndAndroidMain")
        jvmAndAndroidMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)

        getByName("commonMain").dependencies {
            api(project(":core:common"))
            api(libs.kotlinx.coroutines.core)   // multiplatform; DAOs return Flow but live in androidMain
        }

        getByName("androidMain").dependencies {
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.room.ktx)
            implementation(libs.androidx.sqlite)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.datastore.preferences)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.robolectric)
            implementation(libs.androidx.room.testing)
        }
        // <<PASTE KMP_LOCAL_DEP_SELECTION HERE if Phase 06 recorded one>>
    }
}

// Fact 1 — KSP2 per-target: Room codegen runs for the Android compilation ONLY.
// Do NOT use the catch-all ksp(...); there is no kspJvm under D5 = A.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
}

// Fact 3 — KEEP the schema EXPORT arg (where KSP writes schema JSONs). Global to KSP; only
// kspAndroid runs, so it applies to the Android compile. (The old test-asset wiring is dropped.)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

publishing {
    publications {
        // KMP emits root "kotlinMultiplatform" + "android" + "jvm" publications; keep the
        // core-persistence prefix on all of them (same rewrite Phase 06 recorded).
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace(project.name, "core-persistence")
        }
    }
}
```

**What changed vs the old file, and why it is safe:** `com.android.library` → KMP + KMP-Android
plugins (AGP 9 forces it); `android { buildTypes/compileOptions/singleVariant }` gone (KMP has no
variants); `ksp(...)` → `add("kspAndroid", ...)` (Fact 1); `testOptions.unitTests.isInclude…` →
`withHostTest { isIncludeAndroidResources = true }` (Fact 2); the test-asset schema line dropped
(Fact 3); Room/SQLCipher/DataStore relocated to `androidMain` so the `jvm()` target never sees an
AAR it cannot consume; single `register("release")` publication → prefix rewrite. No version bump,
no new dependency, no `expect`/`actual` (R10).

### Step 4 — Move the production sources (`git mv`, zero content edits)

Run from the **repo root**. Create destination package dirs first. The bulk moves use globs so
you cannot miss or miscount a file; the two pure projections are moved separately **after** the
`*Dao.kt` glob (which excludes them by name).

```bash
cd core/persistence/src

SRC=main/java/com/transfer/flash/core/persistence
CM=commonMain/kotlin/com/transfer/flash/core/persistence
AM=androidMain/kotlin/com/transfer/flash/core/persistence

mkdir -p "$CM/db/dao" "$CM/retention"
mkdir -p "$AM/db/entity" "$AM/db/dao" "$AM/settings"
```

```bash
# ── androidMain (27) ──
git mv "$SRC"/db/entity/*.kt          "$AM/db/entity/"        # 11 @Entity (R8 move-only)
git mv "$SRC"/db/dao/*Dao.kt          "$AM/db/dao/"           # 11 @Dao  (R8; excludes the 2 projections)
git mv "$SRC"/db/FlashDatabase.kt     "$AM/db/FlashDatabase.kt"        # @Database (R8)
git mv "$SRC"/db/FlashDatabaseOpener.kt "$AM/db/FlashDatabaseOpener.kt" # Context + Room.open (R8)
git mv "$SRC"/db/FlashMigrations.kt   "$AM/db/FlashMigrations.kt"      # Migration objects (R8)
git mv "$SRC"/settings/*.kt           "$AM/settings/"         # 2 DataStore settings files
```

```bash
# ── commonMain (3) — the two projections (NOT *Dao.kt) + the retention port ──
git mv "$SRC"/db/dao/ConversationPreview.kt "$CM/db/dao/ConversationPreview.kt"
git mv "$SRC"/db/dao/ConversationUnread.kt  "$CM/db/dao/ConversationUnread.kt"
git mv "$SRC"/retention/RetentionPolicy.kt  "$CM/retention/RetentionPolicy.kt"
```

**Verify the counts before compiling** (a miscount here is the most likely silent error):

```bash
# from repo root
git -C core/persistence status --short | grep -c '^R'          # expect 30 renames
find core/persistence/src/androidMain -name '*.kt' | wc -l      # expect 27
find core/persistence/src/commonMain -name '*.kt' | wc -l       # expect 3
find core/persistence/src/main -name '*.kt' | wc -l             # expect 0 (main/java emptied)
```

- If `db/dao/*Dao.kt` matched 10 or 12 instead of 11, or `db/entity/*.kt` matched ≠ 11, a file
  is named unexpectedly — list it with `git -C core/persistence ls-files 'src/main/**/*.kt'` and
  place it by the **placement rule** (has `androidx.room`/`android.*` → androidMain; pure → check
  the transitive rule). Do not force a count; place by import.
- If any move shows as delete+add rather than rename `R`, that is functionally fine but confirm
  you did not edit the file (R8).

### Step 5 — Move the tests to `androidHostTest` (unmodified)

```bash
cd core/persistence/src   # from repo root

HT=androidHostTest/kotlin/com/transfer/flash/core/persistence
TST=test/java/com/transfer/flash/core/persistence
mkdir -p "$HT/db" "$HT/retention" "$HT/settings"

git mv "$TST/db/FlashDatabaseInvariantTest.kt"      "$HT/db/FlashDatabaseInvariantTest.kt"
git mv "$TST/retention/RetentionPolicyTest.kt"      "$HT/retention/RetentionPolicyTest.kt"
git mv "$TST/settings/DiscoveryModeSettingTest.kt"  "$HT/settings/DiscoveryModeSettingTest.kt"
git mv "$TST/settings/FlashSettingsDataStoreTest.kt" "$HT/settings/FlashSettingsDataStoreTest.kt"
```

> If a `git mv` fails with "no such file", run `git -C core/persistence ls-files 'src/test/**/*.kt'`
> and fix the source path — do **not** create a new file. Invariant: **all 4 test files end up
> under `androidHostTest/kotlin/` unchanged**, and `test/java/` is left empty.

### Step 6 — Verify the KSP config name, then compile the desktop target

First confirm the per-target KSP config resolved (Fact 1):

```bash
./gradlew :core:persistence:dependencies --no-configuration-cache | grep -i ksp
```

You should see a `kspAndroid` configuration carrying `room-compiler`. There must be **no**
`kspJvm`/`kspCommonMainMetadata` carrying it (desktop has no Room). Then:

```bash
./gradlew :core:persistence:compileKotlinJvm --no-configuration-cache
```

This must succeed and must **not** run Room codegen (only the 3 pure files compile for `jvm()`).
Likely failures and fixes:

- `Unresolved reference: androidx` / `android` while compiling the **jvm** target → a Room or
  settings file leaked into `commonMain`/`jvmAndAndroidMain`; it belongs in `androidMain`.
- KSP tries to run for the jvm/metadata compilation (Room errors about missing DB) → you used
  the catch-all `ksp(...)`; switch to `add("kspAndroid", ...)` (Fact 1).
- `Unresolved reference: ConversationPreview`/`ConversationUnread` from an androidMain DAO → the
  projection was left in `main/java` or mis-moved; confirm it is in `commonMain/db/dao/`
  (androidMain can see commonMain, so this resolves once placed correctly).
- `package` errors → a file's package line was altered during the move. Restore it; moves must
  not edit contents (R8).

### Step 7 — Run the Android host tests and assert the count equals baseline

Use `ANDROID_UNIT_TEST_TASK` from the Phase 06 log (under the KMP Android plugin typically
`:core:persistence:testAndroidHostTest`, **not** the old `testDebugUnitTest`).

```bash
./gradlew :core:persistence:testAndroidHostTest --no-configuration-cache
```

Read **total tests** from the report. It must equal `PERSISTENCE_TEST_BASELINE`.

- **Count == baseline, all green** → correct.
- **Count == 0 but "BUILD SUCCESSFUL"** → host tests never turned on (`KMP_HOST_TEST_BLOCK`
  missing/wrong). Fix the Android block; do not proceed on a green-but-empty run.
- **`FlashDatabaseInvariantTest` errors with a Robolectric resources/manifest failure** →
  `isIncludeAndroidResources = true` is missing from `withHostTest { }` (Fact 2). Add it.
- **`FlashDatabaseInvariantTest` errors on a missing schema asset** → (unexpected — it uses
  `openInMemory`) re-add the schema dir to `androidHostTest` assets (Fact 3) and re-run.
- **Count < baseline** → tests left behind in `src/test/` or in a set the task does not see;
  check `git status` for stragglers under `test/java/`.

### Step 8 — Publish locally and confirm coordinates

```bash
./gradlew :core:persistence:publishToMavenLocal --no-configuration-cache
```

Inspect `~/.m2/repository/com/transfer/flash/` (group per the root build). Expect the KMP
publications with the **`core-persistence` prefix**: the root module (`core-persistence`), the
per-target variants (`core-persistence-android`, `core-persistence-jvm`), and a `.module`
Gradle-metadata file. The invariant: the coordinate consumers already use (`core-persistence`)
still resolves; metadata routes Android consumers to `-android`, desktop to `-jvm`. If artifacts
are named `persistence*` (no prefix), the publication rewrite did not run — re-check Step 3.

### Step 9 — Prove the Android app still builds against it

```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

`:app` (still a normal Android app) must resolve the newly-KMP `:core:persistence` and compile.
Every file kept its package and public API and `explicitApi()` is retained, so no consumer source
changes. If `:app` fails with an "unresolved reference" into
`com.transfer.flash.core.persistence.*`, a public symbol changed visibility or package during the
move — diff the offending file against its pre-move version (`git show HEAD:<old-path>`).

## Verification gate — all four must pass (paste results into the log)

| # | Command | Pass condition |
|---|---|---|
| 1 | `:core:persistence:compileKotlinJvm --no-configuration-cache` | BUILD SUCCESSFUL — the 3 pure contracts compile for desktop; **no Room codegen ran** |
| 2 | `:core:persistence:<ANDROID_UNIT_TEST_TASK> --no-configuration-cache` | total tests **== `PERSISTENCE_TEST_BASELINE`**, all green (never 0); Robolectric test passes |
| 3 | `:core:persistence:publishToMavenLocal --no-configuration-cache` | artifacts under the `core-persistence` prefix (+ `.module`) |
| 4 | `:app:assembleDebug --no-configuration-cache` | BUILD SUCCESSFUL — consumer still resolves the module |

If any fail, this phase is **not done**. Do not log it complete; do not start Phase 10.

## Do NOT

- **Do NOT pick D5.** It is a human decision; stop if it is `_pending_` and switch to a PHASE-09B
  if it is `B`/`C`. This document is valid **only for D5 = A**.
- **Do NOT edit the contents of any moved file.** R8: Room `@Entity`/`@Dao`/`@Database`/
  `FlashMigrations` files are schema- and SQL-defining; a one-character change can corrupt the
  on-disk schema or break migrations for existing Android users. `git mv` only.
- **Do NOT move a Room/DataStore file to `commonMain`.** `androidx.*` ⇒ androidMain, always.
- **Do NOT move `ConversationPreview.kt`/`ConversationUnread.kt` to androidMain** — they are pure
  projections and belong in commonMain (the projection trap).
- **Do NOT create any `jvmMain` file** or a desktop Room/in-memory store here — desktop
  persistence adapters (D5 = A) live in the engine/desktop phases, not this module.
- **Do NOT use the catch-all `ksp(...)`** — use `add("kspAndroid", ...)`; there is no `kspJvm`.
- **Do NOT migrate to `androidx.room3`** or bump any version — that is D5 = B/C, a different phase.
- **Do NOT disable encryption** or drop SQLCipher to "help desktop" — under A desktop simply has
  no DB; under B/C encryption stays on (charter principle 3 / R8).
- **Do NOT drop `explicitApi()`** or change any symbol's visibility (ADR-023).
- **Do NOT re-add the test-asset schema wiring** unless Gate 2 proves a test needs it (Fact 3).

## Completion checklist

- [ ] D5 confirmed `A` and D1 confirmed `A` in DECISIONS.md; Phases 06/07/08 complete in the log.
- [ ] `PERSISTENCE_TEST_BASELINE` recorded from the pre-conversion run (incl. Robolectric tests).
- [ ] `build.gradle.kts` rewritten: KMP + KMP-Android + `ksp` plugins; `androidLibrary { }` with
      `withHostTest { isIncludeAndroidResources = true }`; `jvm()`; four sets wired; Room/SQLCipher/
      DataStore in `androidMain`; `add("kspAndroid", …)`; `ksp { room.schemaLocation }` kept;
      test-asset schema line dropped; publication prefix rewrite; `explicitApi()` kept.
- [ ] 30 production files moved — 3 commonMain, 0 jvmAndAndroidMain, 27 androidMain — all `git mv`,
      contents unchanged; `main/java/` empty; count checks in Step 4 pass.
- [ ] 4 test files moved to `androidHostTest`, unchanged; `src/test/` empty.
- [ ] `jvmMain` has zero files.
- [ ] `grep -i ksp` on `:dependencies` shows `kspAndroid` with `room-compiler` and **no** `kspJvm`.
- [ ] Gate 1 `compileKotlinJvm` — pass, no Room codegen. Gate 2 host tests — **== baseline**, green,
      Robolectric passes. Gate 3 `publishToMavenLocal` — `core-persistence` prefix. Gate 4
      `:app:assembleDebug` — pass.
- [ ] Log entry appended to `docs/migration/logs/migration.md`.

## Rollback

```bash
git checkout -- core/persistence/
git clean -fd core/persistence/src/commonMain core/persistence/src/jvmAndAndroidMain \
               core/persistence/src/androidMain core/persistence/src/androidHostTest
```

Restores `build.gradle.kts` and returns every file to `src/main/java` / `src/test/java`. Nothing
downstream was edited, so no other module needs reverting. Confirm with
`./gradlew :app:assembleDebug --no-configuration-cache`.

## Log entry (mandatory — a phase with no entry is "not done")

Append one entry to `docs/migration/logs/migration.md` (format per `TEMPLATE-phase-log.md`;
append-only, newest at the bottom — never edit an earlier entry, CONVENTIONS R1/R9). Record:

- **D5 = A confirmed** (quote the DECISIONS.md answer) — this phase is only valid under A.
- The Phase-06 facts reused; the persistence-specific DSL delta (`isIncludeAndroidResources`
  added inside `withHostTest`; `add("kspAndroid", …)` instead of `ksp(...)`; test-asset schema
  line dropped, `room.schemaLocation` kept).
- `PERSISTENCE_TEST_BASELINE` and the post-conversion count (proving they match), and that the
  Robolectric `FlashDatabaseInvariantTest` still passes.
- The four gate results (paste `BUILD SUCCESSFUL` lines / test totals) and the `grep -i ksp`
  output proving `kspAndroid`-only.
- The two structural facts a later reader must not "fix": **`jvmAndAndroidMain` and `jvmMain` are
  both empty** here, and **desktop has no persistence under D5 = A** (Phase 16 G7 / Phase 23 M6
  resume are DEFERRED for desktop by this decision).
- The published artifact coordinates observed in `~/.m2`.
