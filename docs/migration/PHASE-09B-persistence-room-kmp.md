# Phase 09B — `:core:persistence` re-platform onto Room KMP (D5 = C)

> **Status: 09B-1 EXECUTED 2026-09-05** at `328c553` + `24435bd` + `8b5fa5a`; all 7 verification
> gates pass. **09B-2 and 09B-3 remain BLOCKED** on the human decisions in *Decisions this phase
> needs*. Read **Execution amendments (09B-1)** at the bottom of this file before trusting any
> instruction above it — six statements in this document turned out to be wrong, one of them a
> verification gate that cannot fail. Authored 2026-09-05 by the agent that reached Phase 09 and
> found `PHASE-09-persistence-kmp.md` unexecutable; the working tree at authoring time was clean
> at `55cdc9c`.
>
> **This file supersedes `PHASE-09-persistence-kmp.md`.** That document is a *file-move* plan
> written for **D1 = A + D5 = A**. The repo is **D1 = B + D5 = C**. Its own D5 gate says:
>
> > **`B` or `C`** → **STOP and switch documents.** … **Stop, tell the human B/C was chosen, and
> > author a dedicated PHASE-09B rather than stretching this move-only document.**
>
> This is that document. PHASE-09 also prescribes `jvmAndAndroidMain`, which CONVENTIONS.md R5
> forbids outright under D1 = B. Do not execute PHASE-09.

## Why this is not one phase

Two things are true at once:

1. The **Room-KMP re-platform** (09B-1) is smaller than PHASE-09's D5 = B/C box predicted, and
   is executable today with **no version changes at all** (see *Ground truth*, item 1).
2. The **encrypted desktop driver** (09B-2) is blocked on a decision that DECISIONS.md reserves
   for the human: D5 says *"evaluate `s0d3s/SQLCipherMultiplatform`, `bloomberg/selekt`, Zetetic
   SQLCipher-for-JDBC for maintenance status + licence **before adoption**"*. The evaluation is
   done below; the adoption is not the agent's call.

D5's charter also says **"B without C is forbidden"** — shipping the desktop target without
encryption *"would put plaintext Flash data on desktop disk, which R8 prohibits."* That
constraint is what fixes the split point:

> **09B-1 ships no desktop open path whatsoever.** The `jvm()` target compiles, and `jvmTest`
> exercises the DAOs against an **in-memory** database, but no code in `jvmMain` can open a
> database file. Nothing is written to desktop disk, encrypted or otherwise, so 09B-1 is not
> "B without C" — it is B with the desktop product surface deliberately absent.

09B-2 then adds the encrypted file-backed opener, once the human has picked a driver.

## Decision inputs

| Decision | Value | Consequence here |
|---|---|---|
| **D1** | **B** — strict `commonMain`, Kotlin/Native in scope | No `jvmAndAndroidMain` (R5). One-line `actual`s get duplicated on purpose. |
| **D5** | **C** — Room KMP **and** encrypted desktop storage | The persistence layer is re-platformed, not moved. Never disable encryption to ease the port. |
| **D6** | A (JmDNS) | Irrelevant to this phase. |

## Ground truth, measured 2026-09-05

Every claim below was measured on this machine. Commands are reproducible; output is pasted, not
remembered (R9).

### 1. Room **2.8.4 is already a full KMP library**. D5's premise is out of date.

D5's wording and PHASE-09's D5 = B/C box both assume a jump to `androidx.room3` 3.0.x. That is
**not required**. The version already pinned in `gradle/libs.versions.toml` (`room = "2.8.4"`,
R10-frozen) publishes a `-jvm` variant and Kotlin/Native variants:

```
$ python -c "...json.load(room-runtime-2.8.4.module)['variants'] → available-at..."
['room-runtime-android', 'room-runtime-iosarm64', 'room-runtime-iossimulatorarm64',
 'room-runtime-iosx64', 'room-runtime-jvm', 'room-runtime-linuxarm64', 'room-runtime-linuxx64',
 'room-runtime-macosarm64', 'room-runtime-macosx64', 'room-runtime-tvosarm64',
 'room-runtime-tvossimulatorarm64', 'room-runtime-tvosx64', 'room-runtime-watchosarm32',
 'room-runtime-watchosarm64', 'room-runtime-watchosdevicearm64',
 'room-runtime-watchossimulatorarm64', 'room-runtime-watchosx64']
```

Corroborated by Google's own migration guide, which states Room 2.8 supports KMP; Room 3.0 is a
*maven-coordinate and modernization* release (`androidx.room` → `androidx.room3`,
`room-*` → `room3-*`), not the release that adds KMP.

**Therefore 09B changes no dependency version.** R10 stays clean. It also avoids Room 3.0's
breaking changes — mandatory `suspend`/observable DAOs, removal of `SupportSQLiteDatabase`,
`@TypeConverter` → `@ColumnTypeConverter`, `suspend fun migrate(connection: SQLiteConnection)`,
removal of `InvalidationTracker.Observer`, `@DaoReturnTypeConverters` — every one of which would
also have broken `:app`'s direct `FlashDatabaseOpener → FlashDatabase → *Dao` wiring.

> Record this in the log as a **correction to D5's stated premise**, not as a decision. D5's
> *choice* (Room KMP + encrypted desktop storage) is unaffected and stands; only its assumption
> about which Room version delivers KMP was wrong.

### 2. `androidx.sqlite` 2.6.2 and `datastore-preferences` 1.1.7 are KMP at the pinned versions

```
$ ... sqlite-2.6.2.module → available-at
['sqlite-android', 'sqlite-iosarm64', ..., 'sqlite-jvm', 'sqlite-linuxarm64',
 'sqlite-linuxx64', 'sqlite-macosarm64', ..., 'sqlite-watchosx64']

$ curl -sI dl.google.com/.../sqlite-bundled/2.6.2/sqlite-bundled-2.6.2.module      → 200
$ curl -sI dl.google.com/.../sqlite-bundled-jvm/2.6.2/sqlite-bundled-jvm-2.6.2.jar → 200

$ ls ~/.gradle/caches/modules-2/files-2.1/androidx.datastore/
datastore-core-jvm  datastore-core-okio-jvm  datastore-preferences-core-jvm
datastore-preferences-jvm  …
```

Note the repository: androidx artifacts are on **Google Maven** (`dl.google.com/dl/android/maven2`),
**not** Maven Central — `repo1.maven.org` returns 404 for `sqlite-bundled`. Two catalog *aliases*
must be added, both at the existing `sqliteKtx = "2.6.2"` version ref so no version moves:

| New alias | Coordinate | Where used |
|---|---|---|
| `androidx-sqlite-core` | `androidx.sqlite:sqlite` | `commonMain` (the `SQLiteDriver` interface) |
| `androidx-sqlite-bundled` | `androidx.sqlite:sqlite-bundled` | **`jvmTest` only** — see the hard constraint in 09B-1 |

The existing `androidx-sqlite` alias points at `androidx.sqlite:sqlite-ktx`, which is **Android-only**
(there is no `sqlite-ktx-jvm`). Keep it, scoped to `androidMain`.

### 3. The KSP output **already** targets the KMP driver API

Room 2.8.4's code generator does not emit `SupportSQLite` calls any more. The DAO
implementations currently on disk under `core/persistence/build/generated/ksp/debug/` import the
driver types:

```
$ grep -rn --include=*.kt '^import androidx\.sqlite' core/persistence/build/generated/ksp/
…/dao/ConversationDao_Impl.kt:11:import androidx.sqlite.SQLiteStatement
…/dao/DraftDao_Impl.kt:10:import androidx.sqlite.SQLiteStatement          (× 11 DAOs)
…/db/FlashDatabase_Impl.kt:10:import androidx.sqlite.SQLiteConnection
…/db/FlashDatabase_Impl.kt:11:import androidx.sqlite.execSQL
```

So the 11 DAOs and 11 entities are **already** compiling against a KMP-safe generated surface on
Android today. This is the single biggest de-risking fact in this phase: the generated half of the
Room stack needs nothing done to it.

### 4. Only **two** production files reference a platform SQL type

```
$ grep -rn --include=*.kt -E '^import (androidx\.sqlite|net\.zetetic)' core/persistence/src
db/FlashDatabaseOpener.kt:6:import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
db/FlashMigrations.kt:4:import androidx.sqlite.db.SupportSQLiteDatabase
```

`FlashDatabase.kt` itself is clean — `androidx.room.Database` + `androidx.room.RoomDatabase` and
nothing else.

### 5. All 63 DAO functions are already `suspend` or return `Flow`

```
$ grep -rn --include=*Dao.kt -E '^\s*(public |internal )?(abstract )?fun ' …/db/dao/ \
    | grep -v suspend | grep -v 'Flow<'
(none — every DAO fn is suspend or returns Flow)
suspend : 50      Flow<   : 13
```

Room KMP forbids blocking DAO functions off Android. That constraint is **already satisfied**; no
DAO signature has to change, which matters because R8 protects DAOs.

### 6. `FlashSettingsDataStore`'s public constructor is not common-compatible

`PreferenceDataStoreFactory` splits its overloads by source set. From the 1.1.7 artifact:

```
$ javap -cp classes.jar androidx.datastore.preferences.core.PreferenceDataStoreFactory
Compiled from "PreferenceDataStoreFactory.jvmAndroid.kt"
  … create(ReplaceFileCorruptionHandler, List, CoroutineScope, Function0<? extends java.io.File>)
  … createWithPath(ReplaceFileCorruptionHandler, List, CoroutineScope, Function0<okio.Path>)
```

`create(… () -> java.io.File)` lives in datastore's **own** `jvmAndroid` source set. The common
factory is `createWithPath(… () -> okio.Path)`. Since `FlashSettingsDataStore`'s constructor takes
`produceFile: () -> File` as **published public API**, moving it to `commonMain` is an ABI change,
not a relocation. See *09B-1, settings tier* for how that is handled without breaking `:app`.

### 7. Test inventory — 35 tests, and the 12 known failures are all in the settings tier

```
$ grep -rc '@Test' core/persistence/src/test/…
db/FlashDatabaseInvariantTest.kt:7        (Robolectric — RuntimeEnvironment, @Config)
retention/RetentionPolicyTest.kt:9        (org.junit.Assert + kotlin.random only)
settings/DiscoveryModeSettingTest.kt:6    (1 known failure)
settings/FlashSettingsDataStoreTest.kt:13 (11 known failures)
```

7 + 9 + 6 + 13 = **35**, and 11 + 1 = **12** — exactly the pre-existing failure count R3 records.
Both failing suites are in the **settings** tier. That is the main reason the settings tier is
split out of 09B-1 below: the db-tier work then cannot perturb the known-failure baseline at all.

## Two real obstacles

### Obstacle A — `FlashMigrations` cannot move, and must not be edited

`FlashMigrations.MIGRATION_1_2` / `MIGRATION_2_3` override
`Migration.migrate(db: SupportSQLiteDatabase)`. `androidx.sqlite.db.*` is the **Android-only**
Support layer; it does not exist in the KMP `androidx.sqlite:sqlite` artifact. R8 names
`FlashMigrations` explicitly as untouchable, and PHASE-09 restates it: *"a one-character change
can corrupt the on-disk schema or break migrations for existing Android users."*

**Resolution: `FlashMigrations.kt` stays in `androidMain`, byte-for-byte unchanged.** It is not a
loss — migrations describe how to upgrade *an existing Android install*. The desktop has no
installed base and no v1/v2 databases, so a desktop database is created at
`DATABASE_VERSION = 3` directly and has nothing to migrate. Do **not** author
`suspend fun migrate(connection: SQLiteConnection)` equivalents "for symmetry"; that is a new
schema-mutating code path with no user to serve (R1).

Consequence: `FlashDatabaseOpener.openEncrypted(context, passphraseProvider, vararg migrations)`
keeps its exact current signature and stays in `androidMain` too, unchanged, so the published
Android ABI and `:app`'s call site are untouched.

### Obstacle B — `@ConstructedBy` requires editing `FlashDatabase.kt` (R8 exception, must be sanctioned)

Room's reflective builder (`Room.databaseBuilder(context, FlashDatabase::class.java, name)`) is
Android-only. Every non-Android target needs the generated-constructor route, which is **not
optional**:

```kotlin
// commonMain
@Database(entities = [...], version = FlashDatabase.DATABASE_VERSION, exportSchema = true)
@ConstructedBy(FlashDatabaseConstructor::class)          // <-- the one added line
public abstract class FlashDatabase : RoomDatabase() { /* 11 abstract dao() fns, unchanged */ }

// commonMain, separate file
@Suppress("KotlinNoActualForExpect")
public expect object FlashDatabaseConstructor : RoomDatabaseConstructor<FlashDatabase> {
    override fun initialize(): FlashDatabase
}
```

That is **one annotation line added to an R8-protected file**, and nothing else. It adds no
column, no index, no SQL, and does not change `DATABASE_VERSION`, the entity list, or
`exportSchema`. The generated schema JSON must be byte-identical afterwards — which is the gate
that proves it (see *Verification*, gate 6).

> **The executing agent must record this as an explicit, narrow R8 exception in its log entry**,
> quoting this paragraph, and must abort if gate 6 shows any schema drift. `@ConstructedBy` cannot
> be avoided: without it Room's KSP processor will not generate an initializer for the `jvm()`
> target at all.

## 09B-1 — Room KMP re-platform, db tier only (executable now)

**Scope:** the `db/` and `retention/` trees. The `settings/` tree is *not* in 09B-1.
**Product surface added: none.** Android behaviour must be identical; desktop gains a compiling
target and executed tests, but no way to open a database file.

### Source-set placement (30 production files)

| Count | Files | Destination | Edit? |
|---:|---|---|---|
| 11 | `db/entity/*Entity.kt` | `commonMain` | **no** — pure `androidx.room` annotations |
| 11 | `db/dao/*Dao.kt` | `commonMain` | **no** — all 63 fns already `suspend`/`Flow` |
| 2 | `db/dao/{ConversationPreview,ConversationUnread}.kt` | `commonMain` | **no** — zero imports |
| 1 | `db/FlashDatabase.kt` | `commonMain` | **1 line**: `@ConstructedBy` (Obstacle B) |
| 1 | `db/FlashDatabaseConstructor.kt` *(new)* | `commonMain` | new `expect object` |
| 1 | `retention/RetentionPolicy.kt` | `commonMain` | **no** — zero imports |
| 1 | `db/FlashMigrations.kt` | `androidMain` | **no** — Obstacle A |
| 1 | `db/FlashDatabaseOpener.kt` | `androidMain` | **no** — signature preserved |
| 1 | `db/FlashDatabaseConstructor.android.kt` *(new)* | `androidMain` | `actual object`, generated by KSP |
| 1 | `db/FlashDatabaseConstructor.jvm.kt` *(new)* | `jvmMain` | `actual object`, generated by KSP |
| 2 | `settings/*.kt` | **stays `androidMain`** | deferred to 09B-3 |

The two `actual object` declarations are the intentional D1 = B duplication (R5): Room's KSP
processor emits the body, so each file is a one-liner. If AGP/KSP generates them automatically for
both targets, delete the hand-written stubs — verify empirically, do not assume.

Use `git mv` for every relocation so the rename is visible in the diff (Phase 06–08 precedent).
Target directory is `kotlin/`, never `java/` (R5):
`core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/…`.

### Tests

| File | Destination | Edit |
|---|---|---|
| `retention/RetentionPolicyTest.kt` (9) | `commonTest` | `org.junit.Assert.*`/`org.junit.Test` → `kotlin.test.*`. Runs on **both** targets → 18 executed. |
| `db/FlashDatabaseInvariantTest.kt` (7) | `androidHostTest` | none — Robolectric + `RuntimeEnvironment`. Needs `withHostTest { isIncludeAndroidResources = true }`. |
| `settings/*Test.kt` (6 + 13) | `androidHostTest` | none — the 12 known failures stay exactly as they are. |
| `db/FlashDatabaseJvmTest.kt` *(new)* | `jvmTest` | New. See the hard constraint below. |

The new `jvmTest` suite is what makes 09B-1 worth doing rather than merely compiling: it must open
`FlashDatabase` on the desktop target via `FlashDatabaseConstructor` and round-trip at least one
entity through one DAO, proving the generated jvm `_Impl` actually works. Per R3.1, an `actual`
that is only ever compiled is not verified.

> ### Hard constraint — `sqlite-bundled` is `jvmTest` **only**
>
> `BundledSQLiteDriver` is **unencrypted**. It may be used to satisfy the new `jvmTest` suite, and
> only with an **in-memory** database (`":memory:"`), never a file path. It must be declared in the
> `jvmTest` dependency block and nowhere else, so no product code can reach it. This is the same
> posture as the existing Android `openInMemory`, which is already annotated TEST-ONLY.
>
> Verification gate 5 greps for it. A `jvmMain` reference to `sqlite-bundled`, or any `":memory:"`
> → file-path change, is **"B without C"** and is forbidden by D5's charter.

### `core/persistence/build.gradle.kts`

Start from `core/discovery/build.gradle.kts` (the Phase 08 reference template) and add the KSP and
Room bits. Only the parts that differ from that template are shown.

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)   // NEW alias, version.ref = "room" (2.8.4) — see note
    `maven-publish`
}

kotlin {
    explicitApi()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    android {
        namespace = "com.transfer.flash.core.persistence"
        compileSdk = 35
        minSdk = 24
        optimization { consumerKeepRules.apply { file("consumer-rules.pro"); publish = true } }
        localDependencySelection { selectBuildTypeFrom.set(listOf("release")) }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        // PHASE-09 Fact 2: `testOptions.unitTests.isIncludeAndroidResources` has no KMP
        // equivalent outside this block, and FlashDatabaseInvariantTest (Robolectric) needs it.
        withHostTest { isIncludeAndroidResources = true }
        withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(libs.kotlinx.coroutines.core)          // DAOs return Flow → must be `api`
            api(libs.androidx.room.runtime)             // @Dao/@Entity are in the public surface
            implementation(libs.androidx.sqlite.core)   // NEW alias: androidx.sqlite:sqlite
        }
        androidMain.dependencies {
            implementation(libs.androidx.sqlite)        // sqlite-ktx — Android-only, SupportSQLite
            implementation(libs.sqlcipher.android)      // net.zetetic — Android encryption, R8
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.room.ktx)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.kotlinx.coroutines.test) }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit); implementation(libs.robolectric); implementation(libs.androidx.room.testing)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.sqlite.bundled)  // NEW alias — TEST-ONLY, in-memory only
        }
    }
}

// Per-target KSP. A bare `ksp(...)` does NOT reach a KMP target's compilation.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

// Replaces `ksp { arg("room.schemaLocation", …) }`. PHASE-09 Fact 3 stands: keep the schema
// directory, and DROP the old `android.sourceSets.getByName("test").assets` wiring — no test
// reads the schemas and `schemas/…/2.json` does not exist.
room { schemaDirectory("$projectDir/schemas") }
```

Three notes the executing agent must confirm rather than trust:

- **`libs.plugins.androidx.room`** is a new catalog entry (`id = "androidx.room"`,
  `version.ref = "room"`). Verified available: `androidx.room:androidx.room.gradle.plugin:2.8.4`
  returns HTTP 200 on Google Maven, and `settings.gradle.kts` already admits `androidx.*` into
  `pluginManagement`. It exists to give each target its own schema output; with a single global
  `ksp { arg("room.schemaLocation", …) }` both `kspAndroid` and `kspJvm` write the same path and
  can race under `--max-workers=2`. **Fallback** if the plugin misbehaves: revert to the KSP arg
  and run the two schema-producing tasks in separate invocations.
- `libs.androidx.room.runtime` is `api`, not `implementation`, because `@Dao` interfaces and
  `@Entity` data classes are public API here — the build file's existing comment records that
  `:app` wires the DB directly. Verify with `:sample:consumer`, as Phase 08 did for coroutines.
- The real schema path is `core/persistence/schemas/com.transfer.flash.core.persistence.db.FlashDatabase/{1.json,3.json}`
  — one level deeper than PHASE-09 claims, and `2.json` is genuinely absent.

### Publishing

Same rename-in-place pattern as Phase 08, so the `core-persistence` coordinate that 1.1.0
consumers use is preserved and `core-persistence-android` / `core-persistence-jvm` join it:

```kotlin
publishing {
    publications { withType<MavenPublication>().configureEach {
        artifactId = artifactId.replace("persistence", "core-persistence")
    } }
}
```

Delete the old `register<MavenPublication>("release")` block — KMP creates the publications itself.

### Verification gates for 09B-1

Run all seven. Paste the output of each into the log entry (R9).

1. **Android compiles** — `:core:persistence:compileAndroidMain`
2. **R2 proof: `commonMain` is free of `android.*`** — `:core:persistence:compileKotlinJvm`.
   This is the gate that actually certifies the move, because the `jvm()` target has no
   `android.jar`.
3. **R6.1 purity grep** (nothing in the build enforces `java.*`-freedom yet):
   ```bash
   grep -rnE '\b(java|javax|android|androidx)\.' --include=*.kt core/*/src/commonMain ui/*/src/commonMain 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'
   ```
   Expected: `androidx.room.*` and `androidx.sqlite.SQLiteDriver` hits from `core/persistence`
   **only**. Those are legitimate — Room and androidx.sqlite are KMP libraries whose packages
   merely begin with `androidx.`. **This is the first phase where the grep is not expected to be
   empty**, so the log must enumerate every hit and justify it individually. Any `java.*`,
   `javax.*`, `android.*`, `androidx.datastore.*`, or `androidx.sqlite.db.*` hit is a failure.
4. **Full R3 run**, with `:core:persistence` named explicitly (the module loses
   `testDebugUnitTest`):
   ```bash
   ./gradlew --stop >/dev/null 2>&1; sleep 8; ./gradlew :app:assembleDebug testDebugUnitTest :core:common:testAndroidHostTest :core:security:testAndroidHostTest :core:security:jvmTest :core:discovery:testAndroidHostTest :core:discovery:jvmTest :core:persistence:testAndroidHostTest :core:persistence:jvmTest --no-configuration-cache --continue --max-workers=2 --console=plain
   ```
   Expected total: **897 → 906 / 12 failures / 0 skipped.** The +9 is `RetentionPolicyTest`
   running on a second target (9 → 18); the new `jvmTest` db suite adds however many cases it
   contains on top. The 12 failures must stay at **11 `FlashSettingsDataStoreTest` + 1
   `DiscoveryModeSettingTest`** — a 13th failure, or a *different* file failing, aborts the phase.
   Delete `core/persistence/build/test-results/testDebugUnitTest/` before tallying; it survives the
   plugin swap and would be double-counted (Phase 07 hit this).
5. **No unencrypted driver in product code:**
   ```bash
   grep -rn --include=*.kt -E 'BundledSQLiteDriver|sqlite-bundled' core/persistence/src/commonMain core/persistence/src/jvmMain core/persistence/src/androidMain
   ```
   Expected: **nothing.** Any hit is "B without C".
6. **Schema is byte-identical** — the gate that discharges the Obstacle B R8 exception:
   ```bash
   git diff --stat -- core/persistence/schemas/
   ```
   Expected: **empty.** `@ConstructedBy` must not change the exported schema. If `3.json` moves at
   all, revert and report the phase blocked.
7. **Downstream compile classpath unchanged** — `:sample:consumer:compileDebugKotlin` (or its
   current task name), proving `api`/`implementation` scoping did not drop `@Dao`/`@Entity` types
   off a consumer's classpath.

## 09B-2 — Encrypted desktop driver (BLOCKED on a human decision)

D5 requires the candidates be evaluated *"for maintenance status + licence before adoption"*. The
evaluation is below. **Adoption is not the agent's call** — DECISIONS.md: *"An agent must not pick
for the human on D1, D2, D5, or D8."* Picking the driver is a sub-decision of D5.

The gap being filled: Room's own `BundledSQLiteDriver` bundles plain SQLite with **no cipher**, so
`androidx.sqlite.SQLiteDriver` has no encrypted implementation in the androidx stack.

### Evaluation, researched 2026-09-05

| Candidate | Licence | Maintenance signals | Blocking problems |
|---|---|---|---|
| **`io.github.willena:sqlite-jdbc:3.53.2.0`** (SQLite3MultipleCiphers; *not named in D5*) | Apache-2.0 + BSD-2-Clause | 2 317 commits, 219 ★, 39 forks, 1 open issue. Self-described *"maintained, but not actively developed"* with an explicit contract: *"We follow every new version of SQLite and will release a corresponding version of our driver."* Natives for Windows/Linux(glibc+musl)/macOS/FreeBSD/Android; GPG-signed by the upstream xerial identity. | It is a **JDBC** driver, not an `androidx.sqlite.SQLiteDriver` — we would own a ~200-line adapter. SQLCipher *file-format* compatibility is implied by repo topics but **not documented**; minimum Java version **not stated** (check `pom.xml`). |
| **`bloomberg/selekt`** | Apache-2.0 (+ bundled OpenSSL/SQLCipher licences) | 2 451 commits, 58 ★, in production in the Bloomberg Professional Android app. Not archived. | **Requires JVM 25+** (uses the Foreign Function & Memory API instead of JNI). This project targets `JVM_11` and builds on JBR 21 — a hard incompatibility, and raising it is an R10 toolchain change. Separately, it *"moves the responsibility for deriving keys to the caller"*, deliberately trading away SQLCipher's default per-key KDF cost for pooling. That is **weakening encryption**, which R2 forbids. |
| **`s0d3s/SQLCipherMultiplatform`** | Apache-2.0 (upstream SQLCipher BSD-style) | **5 commits, 2 ★, 0 forks, no releases, no published version** — the README shows `<latest-version>` as a literal placeholder. Toolchain baseline *"Kotlin 2.3.x"*. | Not adoptable for at-rest crypto of user data at this maturity. Kotlin 2.3.x also conflicts with the R10-frozen Kotlin 2.2.10. No `androidx.sqlite` integration; iOS explicitly unimplemented. |
| **`skolson/KmpSqlencrypt`** (*not named in D5*) | **No LICENSE file found** | 93 commits, 13 ★, 0 forks. **Not published to any repository** — *"has not so far been published to maven"*, consumption is `publishToMavenLocal` of two separate artifacts. Publishing all targets *"requires a Mac OSX … host with current Xcode"*. | Unlicensed and unpublished is disqualifying on its own. No `androidx.sqlite` surface; Kotlin 2.3.21; `mingwX64` claimed in the description but absent from the target list. |
| **Zetetic SQLCipher for JDBC** | **Commercial** | Zetetic are the upstream authors of SQLCipher; the most credible engineering, and the Android side of this project already ships `net.zetetic:sqlcipher-android`. | Requires the human to purchase a licence. Cannot be evaluated further without one. |

### Recommendation (the human decides)

**`io.github.willena:sqlite-jdbc` + a `jvmMain` adapter**, unless the human would rather pay Zetetic.

Reasoning: it is the only candidate that is simultaneously permissively licensed, actually
published, maintained on a stated cadence, natively packaged for all three desktop OSes, and free
of a toolchain conflict. `selekt` is excluded by JVM 25 and by R2, not by preference. The two
small-repo candidates fail D5's own maintenance bar.

The SQLCipher **file-format** question is worth stating plainly because it looks alarming and is
not: Flash databases are per-device local stores. No `flash.db` is ever transferred between
devices, and no wire format references it. Cross-reading an Android SQLCipher file from desktop is
therefore a non-requirement, and SQLite3MultipleCiphers' own default cipher is sufficient. **If
the human disagrees and wants format parity, Zetetic is the only safe answer.**

### Design sketch, if that option is taken

```
jvmMain/
  db/JdbcCipherSQLiteDriver.kt    : SQLiteDriver  — opens jdbc:sqlite: with PRAGMA key
  db/JdbcCipherConnection.kt      : SQLiteConnection
  db/JdbcCipherStatement.kt       : SQLiteStatement
  db/FlashDatabaseOpener.jvm.kt   — openEncrypted(path, PassphraseProvider): FlashDatabase
```

- `PassphraseProvider` already exists in `commonMain`-compatible form
  (`public fun interface PassphraseProvider { public fun passphrase(): ByteArray }`) — promote the
  interface to `commonMain` and leave the Android opener that consumes it untouched.
- Where the desktop passphrase comes from is **out of scope here**: Android wraps it with the
  Keystore in `:app`. The desktop equivalent (OS keychain / DPAPI / libsecret) is a Phase 13–15
  concern and must be decided before 09B-2 ships a *product* path.
- Mandatory test, and the reason 09B-2 is a separate commit: **open, write a known plaintext
  string, close, then read the raw file bytes and assert the string does not appear** and that the
  header is not `SQLite format 3 `. Without that assertion the phase has not shown encryption
  is on.
- Known risk to check with a spike first: a report of stale `Flow` data / invalidation not firing
  with Room on KMP desktop (`SQLiteConnection`-based drivers). Verify `InvalidationTracker` works
  on the jvm target before building on it.

## 09B-3 — Settings tier (deferred; contains an ABI decision)

`DiscoveryModeSetting` needs only `java.io.IOException` → `okio.IOException`, which on JVM is a
`typealias` for `java.io.IOException`, so behaviour is unchanged. It already takes an injected
`DataStore<Preferences>`, so it can move to `commonMain` cleanly. **Verify the typealias claim
against the okio artifact before relying on it.**

`FlashSettingsDataStore` is harder: its **published** constructor takes `produceFile: () -> File`
(Ground truth 6). Two options, both needing a decision because this module is published:

- **(a) Zero-ABI-risk:** leave both settings files in `androidMain`. Desktop gets no settings until
  a later phase. Costs nothing, defers everything.
- **(b) Common core + platform factory:** move the class to `commonMain` with a
  `DataStore<Preferences>` primary constructor, and add a top-level
  `public fun FlashSettingsDataStore(produceFile: () -> File, scope: CoroutineScope)` "fake
  constructor" in `androidMain` **and** `jvmMain` (the intentional R5 duplication). Source-compatible
  for `:app`; **binary-incompatible** (constructor → static method) for any 1.1.0 consumer.

09B-1 assumes (a) so that it can land without this decision. Do not silently choose (b).

## Decisions that remain the human's

1. **Which encrypted desktop driver** (09B-2). Recommendation above; `selekt` is technically
   excluded, the two small repos fail D5's maintenance bar, Zetetic costs money.
2. **Whether a commercial licence is acceptable** — if yes, Zetetic SQLCipher for JDBC is the
   lowest-engineering-risk answer and the only one giving Android/desktop file-format parity.
3. **Whether desktop needs SQLCipher file-format parity with Android at all.** Argued above as a
   non-requirement; if the human wants it, item 2 becomes the answer.
4. **Settings ABI**: 09B-3 option (a) or (b).
5. **Whether to add a Kotlin/Native target** to make R6 a compiler error instead of a review rule.
   R6.1 already flags this as *"a hole in the plan, not just in a phase"*. `:core:persistence` would
   be the first module where it bites (Room and androidx.sqlite both publish native variants, so
   the module could genuinely support it).

None of these block **09B-1**.

## What must not happen

- **Do not bump to `androidx.room3`.** Ground truth 1: unnecessary, and it breaks `:app`.
- **Do not change any version in `gradle/libs.versions.toml`.** New *aliases* at existing version
  refs only (R10).
- **Do not edit any `@Entity`, `@Dao`, or `FlashMigrations` file** — R8. The only sanctioned edit is
  the single `@ConstructedBy` line on `FlashDatabase.kt` (Obstacle B), gated by verification 6.
- **Do not create `jvmAndAndroidMain`** — R5, D1 = B.
- **Do not put `BundledSQLiteDriver` anywhere but `jvmTest`, and never on a file path** — D5's
  "B without C is forbidden".
- **Do not weaken or disable encryption to make the desktop port easier** — R2, and D5's charter
  repeats it twice. This is what excludes `selekt`'s caller-side key derivation.
- **Do not "fix" the 12 known `:core:persistence` test failures.** They are pre-existing and
  out of scope (R1). If the count changes, that is a regression, not progress.
- **Do not re-enable the configuration cache.** That is Phase 24.

## Commit plan

| Commit | Contents |
|---|---|
| 1 | `gradle/libs.versions.toml` — 3 new aliases + 1 new plugin alias, no version changes |
| 2 | `core/persistence/build.gradle.kts` — the KMP conversion (R4: this module only) |
| 3 | The `git mv` relocations + the `@ConstructedBy` line + the new `expect`/`actual` files + test moves |
| 4 | Docs: this file's execution amendments, `CONVENTIONS.md` R3 command + new total, `logs/migration.md` |

Commits 1 and 2 are separated because R4 makes build-file changes individually revertible, and a
catalog edit is repo-wide rather than module-scoped.

## References

- [Set up Room for KMP](https://developer.android.com/kotlin/multiplatform/room)
- [Migrate from Room 2.x to Room 3.0](https://developer.android.com/training/data-storage/room/migration-2-to-3) — read only to confirm 09B does **not** need it
- [Willena/sqlite-jdbc-crypt](https://github.com/Willena/sqlite-jdbc-crypt)
- [bloomberg/selekt](https://github.com/bloomberg/selekt) · [s0d3s/SQLCipherMultiplatform](https://github.com/s0d3s/SQLCipherMultiplatform) · [skolson/KmpSqlencrypt](https://github.com/skolson/KmpSqlencrypt)
- [Zetetic SQLCipher (commercial editions)](https://www.zetetic.net/sqlcipher/)
- [KMP desktop Room + SQLiteConnection: stale Flow report](https://stackoverflow.com/questions/79885775/kmp-desktop-room-sqliteconnection)

## Execution amendments (09B-1)

Written by the agent that executed 09B-1 on 2026-09-05. Full evidence is in
`logs/migration.md` under *Phase 09B-1*; this section exists so nobody re-executing or reviewing
this document is misled by the six statements above that turned out to be wrong.

**1. Verification 6 as specified cannot fail — it was replaced.** The gate is
`git diff --stat -- core/persistence/schemas/`, expected empty. Room's processor writes to
`room.internal.schemaOutput` **only when the schema it computes differs from
`room.internal.schemaInput`**, so an unchanged working tree is also what "export never ran" looks
like. `> Task :core:persistence:copyRoomSchemas NO-SOURCE` and an empty
`build/intermediates/room/schemas/` are the plugin's **success** signal, not a misconfiguration — I
lost four builds to that reading and removed the Room Gradle plugin before working it out.

The gate was discharged with positive evidence instead: temporarily set
`ksp { arg("room.schemaLocation", "$projectDir/build/schema-probe") }` (a plain
`room.schemaLocation` takes precedence over the plugin's internal pair), run `kspKotlinJvm` and
`kspAndroidMain` separately with `--rerun-tasks`, and `cmp` each result against the committed
`3.json`. **Both byte-identical, 16324 bytes.** That is what discharges Obstacle B's R8 exception.
Any future re-run of this gate must do the same; the `git diff` form is decoration.

**2. The `@ConstructedBy` R8 exception is discharged**, quoted verbatim in the log entry as
Obstacle B requires. One line plus one import on `FlashDatabase.kt`; `DATABASE_VERSION` still 3;
no `@Entity`, `@Dao` or `FlashMigrations` file edited (they appear in `8b5fa5a` as pure renames).

**3. The flagged open question is answered: KSP generates both `actual object`s.** No stub was
hand-written and none should be. Both
`build/generated/ksp/{android/androidMain,jvm/jvmMain}/kotlin/…/FlashDatabaseConstructor.kt` contain
`actual override fun initialize(): FlashDatabase = …FlashDatabase_Impl()`. The `expect object` needs
`@Suppress("NO_ACTUAL_FOR_EXPECT")` — the compiler diagnostic. `KotlinNoActualForExpect` is the IDE
inspection id and does **not** silence a build.

**4. Two aliases, not three.** `androidx-sqlite-core` and `androidx-sqlite-bundled`, plus the
`androidx-room` plugin alias. The commit plan's third library alias counted a dependency for the
Room Gradle plugin, which has no separate coordinate. No version changed (R10 held).

**5. `":memory:"` is not needed and does not appear.** `Room.inMemoryDatabaseBuilder` passes
`name = null`. The hard constraint is still satisfied — more strictly than written, since there is no
path-shaped string in the module at all. **The load-bearing detail this document does not mention:**
that builder's `factory` parameter defaults to a *reflective* `findAndInstantiateDatabaseImpl`, so a
suite that omits `factory = FlashDatabaseConstructor::initialize` passes even if `@ConstructedBy`
does nothing and no `actual` exists. Pass it explicitly or the phase's own subject goes untested.

**6. Verification 3's predicted `androidx.sqlite.SQLiteDriver` hits do not exist.** Only generated
`_Impl` code names driver types and that is not under `src/`. The grep returns 69 hits, all
`androidx.room.*` imports, all from `core/persistence/src/commonMain`.

**7. Verification 4's numbers were stale.** The predicted `897 → 906` used a Phase 08 baseline.
Real baseline **1005 / 12 / 0 across 133 XMLs** (Phase 14) → **1018 / 12 / 0 across 135**. The `+9`
reasoning was right; the document forgot to count the 4 new db tests. Its command also names only
four modules — it must name `:core:network`, `:core:transfer`, `:core:messaging` and `:core:engine`
as well, or four converted modules go unrun. Use CONVENTIONS.md R3's command, which now includes the
two `:core:persistence` tasks. Delete the orphaned
`core/persistence/build/test-results/testDebugUnitTest/` before tallying: it survives the plugin swap
and double-counts 35 tests.

**8. `jvmMain` was never created** for this module, so the "no `BundledSQLiteDriver` outside
`jvmTest`" constraint has no `jvmMain` to check. The desktop target therefore **cannot persist
anything yet** — correct under D5's charter, but it is a state, not a finished port. 09B-2 is what
ends it.

**9. `room-runtime` had to become `api`, not `implementation`.** With `FlashDatabase`, 11 `@Dao`
interfaces and 11 `@Entity` classes all public, a consumer cannot touch this module's surface
without `androidx.room` on its compile classpath. Verified by `:sample:consumer:compileDebugKotlin`
and by `room-runtime-jvm` appearing at `compile` scope in the published `core-persistence-jvm` POM.

**10. Stale comments left behind deliberately.** `:core:engine`'s and `:core:messaging`'s build
files still say `:core:persistence` "is still `com.android.library`". R4 forbids editing another
module's build file here and R1 forbids drive-by fixes; whichever phase next touches those files
should correct them.

