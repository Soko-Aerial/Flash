# Phase 12 — KMP conversion: `core:engine`

**Blocked by:** Phases 06 (pilot), 07 (security), 08 (discovery), 10 (network), 11 (transfer +
messaging). **Not** blocked by Phase 09 — see Preconditions.
**Written for D1 = `B`** (strict `commonMain`; `jvmAndAndroidMain` does not exist).
**D5 is irrelevant to this phase.** The inherited file said "D5 must be `A`"; measurement showed
the dependency was illusory. See *What the inherited file got wrong*, item 2.
**Risk: MEDIUM**, not HIGH. Two of the three things that made it look HIGH turned out to be
non-events: the seven-way `api` fan-out placed cleanly on the first attempt, and
`:core:persistence` still being `com.android.library` is handled by one line
(`localDependencySelection`). What remains genuinely risky is the resource opt-in — this is the
first and only module in the migration with Android resources, and omitting
`androidResources { enable = true }` drops `flash_bolt.xml` from the published AAR **while the
build reports SUCCESS**.

> **This file was rewritten after the phase was executed** (2026-09-05), like the rewrites for
> phases 07, 08, 10 and 11. The inherited body was written for D1 = A, routed the module through
> a `jvmAndAndroidMain` source set that CONVENTIONS.md R5 now forbids, and asserted three times
> that this is a "pure file-move with zero content edits" — a claim that, followed literally,
> puts `kotlin.jvm.Synchronized` into `commonMain` and still builds green. Everything below is
> what was actually measured and run. The inherited claims are not silently dropped: the ones
> that were wrong are listed at the end, with what replaced them.

## What this phase is actually for

Convert `:core:engine` — the composition root — from `com.android.library` to the AGP 9 KMP
plugin pair, with an Android target, a desktop `jvm()` target, and **Android resources
explicitly enabled**. When done:

- `:core:engine:compileKotlinJvm` compiles, proving the module's `commonMain` is free of
  `android.*` on a classpath that has no `android.jar`.
- `:core:engine:jvmTest` **executes** the desktop `actual` of this module's lock, rather than
  merely compiling it (CONVENTIONS.md R3.1).
- `:core:engine:testAndroidHostTest` still runs `DefaultFlashEngineTest`'s single `@Test`.
- The module still publishes as `core-engine` / `core-engine-android` / `core-engine-jvm`, and
  the AAR still carries `res/drawable/flash_bolt.xml`.
- `:app:assembleDebug` still resolves the module.

**What this phase does NOT do:** it does not give desktop a runnable engine. `Flash.kt` is
Android-bound at 714 lines, and `FlashEngine.kt` exposes `FlashSettingsDataStore` in its public
interface, so `core-engine-jvm-1.1.0.jar` contains exactly three classes —
`AutoConnectGate`, `AutoConnectGate$Companion`, `PlatformLock`. That is the honest outcome, and
it is worth stating plainly rather than describing the module as "ported": what desktop gains
here is the auto-connect admission policy and nothing else. A desktop `Flash.create()`
equivalent belongs to the desktop phases (13–16).

## Why `core:engine` splits 2 / 4 / 1

The inherited file called this "1 / 0 / 4" and named `AutoConnectGate.kt` as the one file that
needed no work to reach `commonMain`. The count of *files* is right; the "no work" is not.

```
commonMain ── 2  (internal/AutoConnectGate.kt, concurrent/PlatformLock.kt [expect])
    │
    ├── androidMain ── 4 + 1 actual + 1 resource
    │                  (Flash.kt, FlashEngine.kt, store/RoomTransferStore.kt,
    │                   store/KeystorePassphraseProvider.kt,
    │                   concurrent/PlatformLock.android.kt, res/drawable/flash_bolt.xml)
    └── jvmMain ─── 1 actual
                       (concurrent/PlatformLock.jvm.kt)
```

**commonMain — `internal/AutoConnectGate.kt` (41 lines → 52).** The auto-connector's admission
gate: at most one dial attempt per peer per 15 s, never two concurrent for the same peer, and a
peer that already has a session is cleared so a later drop re-arms immediately. Its state is a
`HashMap<String, Long>` and a `HashSet<String>` — both common stdlib. Its *locking* was not:
both methods carried `@Synchronized`, which is `kotlin.jvm.Synchronized`. See *The one content
edit*.

**commonMain — `concurrent/PlatformLock.kt` (new).** `internal expect class PlatformLock` with a
single `fun <T> withLock(block: () -> T): T`. Third copy of the seam; see the same section.

**androidMain — the four production files, each pinned on its own merits:**

| File | Lines | Pin |
|---|---|---|
| `Flash.kt` | 714 | `android.content.Context`, `android.util.Log`, `android.net.Uri.parse` + `contentResolver` (fully qualified, line 669), `java.io.{File,IOException,InputStream}`, `java.util.{Locale,UUID}`, `java.util.concurrent.ConcurrentHashMap` (7 fields), `java.util.Collections.newSetFromMap`, 5 × `System.currentTimeMillis()`. Also imports six `:core:transfer` types that Phase 11 measured as androidMain (`Sha256`, `ReceivePipeline`, `IncrementalSha256`, `RandomAccessChunkSink`, `FileRandomAccessSinkHandle`, `RandomAccessSinkHandle`), so it would be pinned even without a single `android.*` reference. |
| `FlashEngine.kt` | 62 | `java.io.Closeable`, `java.util.concurrent.atomic.AtomicBoolean`, and decisively `public val settings: FlashSettingsDataStore` in the **public** interface — a `:core:persistence` androidMain type. Same rule as `RealFlashChatRepository` in Phase 11: a file that exposes an androidMain-only type in its public signature is androidMain. |
| `store/KeystorePassphraseProvider.kt` | 88 | `android.content.Context`, `android.security.keystore.{KeyGenParameterSpec,KeyProperties}`, `android.util.Base64`, `java.security.KeyStore`, `javax.crypto.*`. |
| `store/RoomTransferStore.kt` | 53 | Room DAO and entity types from `:core:persistence` (`TransferDao`, `TransferChunkDao`, `TransferEntity`, `TransferChunkEntity`). The ADR-024 port/adapter for `:core:transfer`'s `TransferStore`. |

**androidMain — `res/drawable/flash_bolt.xml`.** A teal→amber vector bolt with **zero consumers**
in this repository — the only non-build-intermediate hits are its own KDoc-style comment and the
`R.txt` it generates. It is published anyway because `@drawable/flash_bolt` is part of what a
1.1.0 consumer resolves, and dropping it is a silent breaking change. This single file is the
reason the whole `androidResources { }` block exists.

**jvmMain — one `actual`, nothing else.** Exactly the shape Phase 10 established: a `jvmMain`
that exists only to satisfy an `expect`.

**`androidHostTest` — `DefaultFlashEngineTest.kt`, unmodified.** One `@Test`, JUnit 4, a backtick
method name (illegal on Kotlin/Native), four inline fakes, and a real `FlashSettingsDataStore`
over a `java.io` temp file. It cannot move to `commonTest` and was not touched.

## The one content edit, and why it was necessary

`AutoConnectGate` guarded both of its methods with `@Synchronized`:

```kotlin
    @Synchronized
    fun tryBegin(deviceId: String, hasSession: Boolean, nowMs: Long): Boolean {
        …
        return false        // three of these
    }

    @Synchronized
    fun end(deviceId: String) { inFlight.remove(deviceId) }
```

`@Synchronized` is an alias for `kotlin.jvm.Synchronized`. It is **JVM-only** and cannot appear
in `commonMain` (CONVENTIONS.md R6). The inherited phase file missed this and described the file
as having "zero imports beyond `kotlin.*`" — literally true, because the annotation needs no
import line, and exactly why an import-only scan is not sufficient evidence.

That left the R2 escalation ladder with two rungs, D1 = B having voided the middle one:

1. **Leave the file in `androidMain`.** `commonMain` would then be *empty*: `compileKotlinJvm`
   would compile nothing and certify nothing, no `commonTest` would be possible, and the phase
   would deliver build-file plumbing and file moves with no shared code at all.
2. **Introduce the `PlatformLock` seam** — `internal expect class` in `commonMain`, one
   `actual` each in `androidMain` and `jvmMain`, both `synchronized(monitor) { block() }` — and
   convert the two annotations to `withLock` blocks.

**Option 2 was taken**, with the same reasoning Phase 08 recorded for `:core:discovery`: R2's
carve-out names `PlatformLock` as the case where an `expect class` is the correct answer, and the
gate is genuinely platform-neutral policy that a desktop auto-connect sweep will need. Weakening
or removing the locking was never available — R2 forbids it.

The converted form:

```kotlin
    private val lock = PlatformLock()

    fun tryBegin(deviceId: String, hasSession: Boolean, nowMs: Long): Boolean = lock.withLock {
        …
        return@withLock false
        …
        true
    }

    fun end(deviceId: String) { lock.withLock { inFlight.remove(deviceId) } }
```

Three things to get right, all of them mechanical but all of them silent if missed:

- **`return@withLock`, not `return`.** `withLock` cannot be `inline` on an `expect class`, so a
  bare `return` is a non-local return out of a non-inline lambda. This one does *not* fail
  silently — it fails to compile, which is the good case.
- **The monitor identity changes** from `this` (what `@Synchronized` locks on for an instance
  method) to a private `Any()`. Mutual exclusion between `tryBegin` and `end` is preserved
  exactly, both maps are private, the class is `internal`, and its only two call sites are
  `Flash.kt:390` and `Flash.kt:673` — so nothing can observe the difference. Worth checking
  rather than assuming: if any caller had done `synchronized(gate) { … }`, this edit would have
  broken it without a compile error.
- **No `suspend` call may appear inside a `withLock` block.** None does here.

### This is the third copy of `PlatformLock`

`:core:common` (Phase 06) and `:core:discovery` (Phase 08) each have one. The Phase 08 log
explicitly asked for a dedicated phase to hoist a single `@FlashInternalApi` lock "before phases
09–12 make further copies". That phase was never written, and this is the copy it warned about.

Copying again was still the right call *for this phase*: `:core:common`'s lock is `internal` by an
explicit Phase 06 decision, `internal` does not cross a Gradle module boundary, and promoting it
to `public` would add a lock to `core-common`'s published ABI under `explicitApi()` and require
editing a second module's source and build file — R1, R4 and R7 all point away from doing that
here. It is re-filed as a Known issue on the Phase 12 log entry, now three call sites wide, with
the note that **no phase in the plan performs the hoist**.

## Preconditions

1. **Phases 06, 07, 08, 10, 11 complete and logged.** Phase 09 does **not** have to be complete,
   and was not: it is blocked on decision D5, so `:core:persistence` is still
   `com.android.library`. The inherited file listed 09 as a hard precondition and required
   "D5 must be `A`". Both are wrong. What this phase actually needs from persistence is that a KMP
   `androidMain` can resolve a variant-ful `com.android.library` project dependency, which one
   line provides:

   ```kotlin
   localDependencySelection { selectBuildTypeFrom.set(listOf("release")) }
   ```

   Phase 11 measured that this works across the plugin boundary. It is load-bearing here for the
   same reason.
2. **Do not check the inherited precondition list literally.** It required `:core:transfer`'s
   `Sha256`, `RandomAccessChunkSink`, `ReceivePipeline`, `IncrementalSha256`,
   `FileRandomAccessSinkHandle` and `RandomAccessSinkHandle` to be "commonMain or
   jvmAndAndroidMain". Phase 11 measured every one of them as **androidMain**. The check fails and
   it does not matter: the only file here that references them is `Flash.kt`, which is androidMain
   itself.
3. **D1 = `B`** in `DECISIONS.md`, and `jvmAndAndroidMain` must not exist anywhere in the repo.
4. **Clean working tree** on the migration branch.

## Dependency tiers

The inherited file's dependency table is **correct** and was implemented as written, with one
addition (`commonTest`) and one syntax change (typed accessors — see item 6 of the corrections).

| Source set | Dependency | Config | Why |
|---|---|---|---|
| `commonMain` | `:core:common`, `:core:security`, `:core:discovery`, `:core:network`, `:core:transfer`, `:core:messaging` | `api` | All six are KMP with a `jvm()` target. `commonMain`'s own two files import none of them, but `Flash.kt` uses all six and inherits them through the source-set hierarchy, and declaring them here is what puts them in the **root** `core-engine` POM. |
| `androidMain` | `:core:persistence` | `api` | `FlashSettingsDataStore` is in the public `FlashEngine` interface. `implementation` would break any consumer that reads `engine.settings`. This is also the pin that keeps `FlashEngine.kt` out of `commonMain`. |
| `androidMain` | `androidx.room.runtime` | `implementation` | **Live, despite having no import line.** `Flash.kt:180` spreads `*FlashMigrations.ALL` into `FlashDatabaseOpener.openEncrypted` and closes the `RoomDatabase` on teardown, so Room's types are in the expression types. `implementation` keeps Room an internal detail (ADR-024). |
| `androidMain` | `androidx.core.ktx`, `androidx.lifecycle.runtime.ktx` | `implementation` | **Dead** — grep finds zero `androidx.core` / `androidx.lifecycle` references in this module. Parked behind `TODO(cleanup)`, not deleted. |
| `commonTest` | `kotlin("test")`, `libs.kotlinx.coroutines.test` | `implementation` | New. `runTest` is the only way to launch coroutines from a non-suspend test function in common code, which the contention cases need. |
| `androidHostTest` | `libs.junit` | `implementation` | `DefaultFlashEngineTest` is JUnit 4. |
| `jvmTest` | `libs.junit` | `implementation` | Matches every converted module. |

Coroutines is deliberately **not** declared: it arrives transitively as `api` from all six core
modules, exactly as it did before the conversion. Adding it would change the published POMs.

On the two dead androidx entries: Phase 08 **deleted** the same pair in `:core:discovery`; phases
10, 11 and now 12 **park** them. The later precedent is followed on purpose — deleting them
changes what a 1.1.0 consumer resolves, and that is a repo-wide decision to make once, not one
module at a time. The inconsistency between Phase 08 and Phases 10–12 is real and is recorded as a
Known issue.

## Steps

The authoritative build file is the committed `core/engine/build.gradle.kts`, which carries a
comment on every block explaining what breaks if it is omitted. It is **not** duplicated here —
two copies of a 160-line build file drift, and the copy in a doc is the one that goes stale. What
follows is the skeleton plus the parts that fail *silently* if you get them wrong.

### Step 1 — Rewrite `core/engine/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)   // NOT com.android.library
    `maven-publish`
}

kotlin {
    explicitApi()                                             // R7 — preserve
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    android {                                                 // `android { }`, NOT `androidLibrary { }`
        namespace = "com.transfer.flash.core.engine"
        compileSdk = 35
        minSdk = 24                                           // NOT inside defaultConfig — no variants
        androidResources { enable = true; resourcePrefix = "flash_" }
        optimization { consumerKeepRules.apply { file("consumer-rules.pro"); publish = true } }
        localDependencySelection { selectBuildTypeFrom.set(listOf("release")) }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        withHostTest { }                                      // creates testAndroidHostTest
        withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }

    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }   // plain jvm(), never jvm("desktop")

    sourceSets { /* per the dependency table above */ }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("engine", "core-engine")
        }
    }
}
```

Four of those lines translate a pre-KMP block that has **no** direct KMP equivalent, and three of
them fail without any error message:

| Was | Becomes | If omitted |
|---|---|---|
| `android { resourcePrefix = "flash_" }` | `androidResources { enable = true; resourcePrefix = "flash_" }` | **`flash_bolt.xml` is silently dropped from the AAR.** Resources are *off* by default under this plugin. Build reports SUCCESS. |
| `defaultConfig { consumerProguardFiles(…) }` | `optimization { consumerKeepRules { file(…); publish = true } }` | Consumer ProGuard rules vanish from the AAR, silently. |
| `buildTypes { release { … } }` | `localDependencySelection { selectBuildTypeFrom.set(listOf("release")) }` | Resolving the still-variant-ful `:core:persistence` fails — this one *is* loud. |
| `register<MavenPublication>("release")` | `withType<MavenPublication>().configureEach { artifactId = … }` | Duplicate/renamed coordinates. KMP creates the publications itself; registering another is wrong. |

`androidResources { }` was the one inherited claim confirmed rather than corrected. It was verified
against the jar before being written, because four phase files in a row had been wrong:

```
$ javap -cp gradle-api-9.3.1.jar com.android.build.api.dsl.LibraryAndroidResources
public interface LibraryAndroidResources extends AndroidResources {
    boolean getEnable();  void setEnable(boolean);
    String getResourcePrefix();  void setResourcePrefix(String);
}
```

Note what this shows: `resourcePrefix` is **not** on
`KotlinMultiplatformAndroidLibraryExtension`. It only exists inside `androidResources { }`, so the
two settings cannot be separated.

### Step 2 — Move the seven files

```bash
E=core/engine/src
mkdir -p $E/commonMain/kotlin/com/transfer/flash/core/engine/{internal,concurrent} \
         $E/androidMain/kotlin/com/transfer/flash/core/engine/{store,concurrent} \
         $E/androidMain/res/drawable \
         $E/jvmMain/kotlin/com/transfer/flash/core/engine/concurrent \
         $E/commonTest/kotlin/com/transfer/flash/core/engine/internal \
         $E/androidHostTest/kotlin/com/transfer/flash/core/engine

M=$E/main/java/com/transfer/flash/core/engine
git mv $M/internal/AutoConnectGate.kt         $E/commonMain/kotlin/com/transfer/flash/core/engine/internal/
git mv $M/Flash.kt $M/FlashEngine.kt          $E/androidMain/kotlin/com/transfer/flash/core/engine/
git mv $M/store/KeystorePassphraseProvider.kt $M/store/RoomTransferStore.kt \
                                              $E/androidMain/kotlin/com/transfer/flash/core/engine/store/
git mv $E/main/res/drawable/flash_bolt.xml    $E/androidMain/res/drawable/
git mv $E/test/java/com/transfer/flash/core/engine/DefaultFlashEngineTest.kt \
                                              $E/androidHostTest/kotlin/com/transfer/flash/core/engine/

rm -rf $E/main $E/test          # git does not track dirs; Gate 8 checks these are gone
```

### Step 3 — Add the seam and edit the gate

Three new files (`concurrent/PlatformLock.kt` + `.android.kt` + `.jvm.kt`, copied from
`:core:discovery` with the package changed) and the `@Synchronized` → `withLock` conversion
described above.

### Step 4 — Add the `commonTest` suite

`commonTest/kotlin/…/internal/AutoConnectGateTest.kt`, 8 tests. Five mirror
`app/src/test/java/com/transfer/flash/net/AutoConnectGateTest.kt`, which tests the app's
**independent duplicate** of this class — until this phase, `:core:engine`'s copy had no test at
all, so a divergence between the two was invisible. `defaultWindow_is15s` pins the 15 s constant
that `Flash.kt`'s sweep relies on. Two contention cases assert the swapped-in lock actually
excludes:

- `contendedTryBegin_admitsExactlyOnePerPeer` — 512 coroutines on `Dispatchers.Default` race
  `tryBegin` across 64 peers with `end` never called, so admission depends only on the in-flight
  set. Each coroutine records into **its own** array slot; counting into a shared per-peer `Int`
  would make the assertion unsound in precisely the case it exists to catch, because two admitted
  callers racing `+= 1` can still land on 1.
- `concurrent_tryBegin_and_end_keepBookkeepingConsistent` — `suppressMs = 0` removes the time
  window entirely, so 8 workers × 2 000 rounds hammer the two private collections as fast as the
  dispatcher allows. An unguarded `HashMap`/`HashSet` under that load does not merely lose an
  update on the JVM; it can throw or corrupt its table.

Being in `commonTest` is the point: `jvmTest` runs it too, which is what separates "the desktop
lock compiles" from "the desktop lock locks" (R3.1).

## Verification gate — nine gates, all must be green

Every command below is prefixed by the Gradle environment this repo requires:

```bash
export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2"
export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix'
```

1. **`:core:engine:compileKotlinJvm`** — BUILD SUCCESSFUL. The R2 proof task: the `jvm()` target
   has no `android.jar`, so this is what certifies `commonMain` is free of Android APIs. It
   certifies **nothing** about `java.*` — that is Gate 8.
2. **`:core:engine:compileAndroidMain`** — BUILD SUCCESSFUL. Watch for
   `packageAndroidMainResources` and `parseAndroidMainLocalResources` in the task list; if
   resources were left disabled they simply do not appear.
3. **`:core:engine:jvmTest :core:engine:testAndroidHostTest`** — BUILD SUCCESSFUL, and then read
   the XMLs rather than trusting the exit code:

   ```bash
   for f in $(find core/engine/build/test-results -name 'TEST-*.xml' | sort); do
     echo "--- $f"; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f" | head -1
   done
   ```

   Expect three XMLs: `jvmTest/…AutoConnectGateTest` at 8,
   `testAndroidHostTest/…AutoConnectGateTest` at 8, `testAndroidHostTest/…DefaultFlashEngineTest`
   at 1. **A fourth, `testDebugUnitTest/…DefaultFlashEngineTest`, survives the plugin swap as a
   stale directory and must be deleted** or the repo tally double-counts it (R3 documents this;
   Phase 07 hit it first):

   ```bash
   rm -rf core/engine/build/test-results/testDebugUnitTest core/engine/build/reports/tests/testDebugUnitTest
   ```

4. **Repo-wide R3 tally.** Run the full R3 command line from CONVENTIONS.md — with
   `:core:engine:testAndroidHostTest` and `:core:engine:jvmTest` appended — then tally every XML.
   `BUILD FAILED` is the **expected** outcome: the 12 known `:core:persistence` failures are
   pre-existing, and `--continue` is what keeps later modules running.
5. **`:core:engine:publishToMavenLocal`** — then verify the coordinates under the real Maven group:

   ```bash
   ls -d ~/.m2/repository/com/transfer/flash/core-engine*/1.1.0/
   ```

   The group is **`com.transfer.flash`** (root `build.gradle.kts:12`), *not* the JitPack
   `com.github.<user>` form. Getting this wrong is quiet — `ls` prints nothing while the build
   reports SUCCESS.
6. **AAR parity, including the resource.** This is the gate that exists for this module
   specifically. The pre-KMP `core-engine-1.1.0.aar` is still sitting in mavenLocal, so it can be
   compared directly:

   ```bash
   JB=/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2
   NEW=~/.m2/repository/com/transfer/flash/core-engine-android/1.1.0/core-engine-android-1.1.0.aar
   OLD=~/.m2/repository/com/transfer/flash/core-engine/1.1.0/core-engine-1.1.0.aar
   diff <("$JB/bin/jar.exe" tf "$OLD" | sort) <("$JB/bin/jar.exe" tf "$NEW" | sort)
   ```

   Then unpack both and diff `AndroidManifest.xml`, `R.txt`, `proguard.txt`, and the `classes.jar`
   entry list. `res/drawable/flash_bolt.xml` and `int drawable flash_bolt 0x0` in `R.txt` are the
   two things that must be present.
7. **POM tiers.** `core-engine-android`'s POM must still carry `core-persistence` at `compile` and
   Room + the two androidx at `runtime`; `core-engine-jvm`'s must carry **only** the six `-jvm`
   core modules and `kotlin-stdlib` — no Room, no androidx, no persistence.
8. **R6.1 leak scan.** `compileKotlinJvm` cannot see `java.*`, so this is grep-enforced. Use the
   command now recorded in CONVENTIONS.md R6.1 — **not** an ad-hoc regex. The `\b@Synchronized\b`
   form can never match (`\b` needs a word/non-word transition and both the preceding space and
   `@` are non-word characters), and that defect is why the first scan of this very module
   reported zero traps.
9. **No `java/` roots, no `src/main`, no `src/test`.**

   ```bash
   find core/engine/src -type d -name java     # → nothing
   ls -1 core/engine/src                       # → androidHostTest androidMain commonMain commonTest jvmMain
   ```

Any red ⇒ do not log PASS and do not proceed to Phase 13.

## Do NOT

- **Do NOT omit `androidResources { enable = true }`.** The single highest-consequence line in
  this phase. Resources are off by default under this plugin; omitting it publishes an AAR with no
  `res/` while the build reports SUCCESS, and every consumer that references `@drawable/flash_bolt`
  breaks at *their* compile time, not yours.
- **Do NOT use `androidLibrary { }`.** The block inside `kotlin { }` is `android { }`. The
  inherited file used `androidLibrary` throughout.
- **Do NOT create `jvmAndAndroidMain`.** It does not exist under D1 = B (R5).
- **Do NOT treat this as a move-only phase.** The inherited file said so three times. It is not:
  `AutoConnectGate.kt` needs the lock swap, and moving it unedited puts `kotlin.jvm.Synchronized`
  in `commonMain` — which nothing in the build will catch.
- **Do NOT delete the dead `androidx.core.ktx` / `androidx.lifecycle.runtime.ktx`.** Park them
  behind `TODO(cleanup)`; they change what a 1.1.0 consumer resolves.
- **Do NOT omit `withHostTest { }`** — `DefaultFlashEngineTest` stops compiling *and* running while
  the build reports SUCCESS. And do NOT add `isIncludeAndroidResources`: the suite uses no
  Robolectric and reads no resource.
- **Do NOT omit `-Xexpect-actual-classes`.** With an `expect class` present and the flag missing,
  the build fails — loudly, so this one is self-correcting, but it costs a cycle.
- **Do NOT use `jvm("desktop")` or `src/**/java/` roots.**
- **Do NOT run mid-migration Gradle without `--no-configuration-cache`.**
- **Do NOT change the published coordinates** (`core-engine`, `core-engine-android`,
  `core-engine-jvm`).

## Completion checklist

- [ ] `build.gradle.kts` rewritten: plugin pair, `explicitApi()`, `-Xexpect-actual-classes`,
      `android { }` with `androidResources`/`optimization`/`localDependencySelection`/
      `withHostTest`/`withDeviceTest`, `jvm()`, dependency tiers, KMP publication rename.
- [ ] 5 production files moved (1 commonMain / 4 androidMain), 1 resource moved, 1 test moved.
- [ ] 3 `PlatformLock` files added; `AutoConnectGate`'s two `@Synchronized` converted.
- [ ] 8-test `commonTest` suite added.
- [ ] `src/main` and `src/test` deleted; no `java/` dir under `core/engine/src`.
- [ ] Gates 1–9 green, with output pasted into the log.
- [ ] Stale `testDebugUnitTest` results directory deleted before tallying.
- [ ] Log entry appended to `docs/migration/logs/migration.md`.
- [ ] Two commits (R4): code, then docs.

## Rollback

```bash
git revert <code-commit>          # restores a working build on its own — the docs commit is separate
```

Before committing, the mechanical form:

```bash
git restore --staged core/engine && git checkout -- core/engine
git clean -fd core/engine/src
```

Stale `~/.m2/repository/com/transfer/flash/core-engine*` entries are harmless — the next real
publish overwrites them.

## What the inherited file got wrong

Eight items, measured. The consequential one is item 1 — the other seven cost time, not
correctness.

1. **"`AutoConnectGate.kt` — pure stdlib types (`HashMap`/`HashSet`/`@Synchronized`), zero imports
   beyond `kotlin.*`"** (old line 50), reinforced by *"a **pure file-move with zero content
   edits**"* (line 10), *"**Do NOT edit file contents.** This is a move-only phase"* (line 690) and
   *"**Do NOT introduce an `expect`/`actual`** for anything here"* (line 693). `@Synchronized` **is**
   `kotlin.jvm.Synchronized`. Following all four instructions produces a green build shipping a
   `commonMain` that depends on `kotlin.jvm` — the exact leak R6.1 exists to describe, and one no
   task in the build reports. Same class of error as Phase 11's `FlashChatRepository` claim, and the
   same root cause: an analysis that scanned `import` lines rather than the code.
2. **"D5 must be `A`"** (line 6) and **Phase 09 listed as a hard precondition** (line 70). Neither
   holds. D5 is undecided and Phase 09 is blocked, and this phase completed anyway. The inherited
   file never mentions `localDependencySelection`, which is the line that makes a variant-ful
   `:core:persistence` resolvable from a KMP `androidMain`.
3. **`androidLibrary { }`** as the DSL block (Step 3, and the completion checklist at lines 714 and
   729). The block is `android { }`.
4. **`val jvmAndAndroidMain = create("jvmAndAndroidMain")`** in Step 3, plus the
   `### jvmAndAndroidMain — 0` section. Void under D1 = B (R5). Harmless in this module only
   because the count was 0.
5. **"jar contains only `AutoConnectGate.class`"** (gates 3 and the checklist). Even without the
   new seam this was wrong — `AutoConnectGate$Companion.class` is emitted for the
   `private companion object`. The measured `core-engine-jvm-1.1.0.jar` holds three classes.
6. **"Do NOT use … typed source-set accessors"** (line 706). Every converted module from Phase 06
   onward uses `commonMain.dependencies { }` / `androidMain.dependencies { }`, and they work. Only
   `androidHostTest` needs `getByName`, because there is no typed accessor for it.
7. **"`Flash.kt` (33 KB … 688 lines)"** (lines 55, 690). Measured: **714 lines / 36,393 bytes**.
   Its import list also does not contain `android.net.Uri` or `ContentResolver` as the file claims —
   both are used, but fully qualified at line 669 (`android.net.Uri.parse`, `appContext.contentResolver`).
   The substance is right; the evidence cited for it was not.
8. **Precondition 1's expectations for `:core:transfer`** (lines 78–81) — six types listed as
   "commonMain or jvmAndAndroidMain" that Phase 11 measured as androidMain. See Preconditions
   item 2.

**And the one it got right:** `androidResources { enable = true; resourcePrefix = "flash_" }`,
including the warning that omitting it silently drops the AAR's resources. Confirmed by `javap`
against `gradle-api-9.3.1.jar` before it was written. It is the first inherited claim in five phase
files that measurement confirmed rather than corrected — which is worth recording precisely because
the base rate has been so bad.

## Log entry (mandatory)

Append **one** entry to `docs/migration/logs/migration.md` (append-only, newest at the bottom;
never edit an earlier entry; never write PASS without pasted output). It must contain:

- The 2 / 4 / 1 placement and the pin for each androidMain file.
- The content edit, with the `@Synchronized` → `withLock` before/after, the monitor-identity note,
  and the fact that this is the **third** `PlatformLock` copy.
- All nine gates' **actual output**, including that Gate 4's `BUILD FAILED` is expected.
- The repo-wide tally against the running series (863 → 883 → 897 → 913 → 945 → this phase), with
  the arithmetic shown, including the −1 for the deleted stale results directory.
- The AAR parity diff, and explicit confirmation that `res/drawable/flash_bolt.xml` is in the
  published artifact.
- All three POMs' dependency tiers.
- The eight corrections above.
- Known issues, at minimum: the un-hoisted third `PlatformLock`; the Phase 08 vs 10–12
  inconsistency on dead androidx deps; `core-engine-jvm` containing only three classes;
  `flash_bolt.xml` having zero consumers; the inherited `TlsOptions` blocker from Phase 10; and the
  stale `.aar` sitting beside the fresh `.jar` in the root mavenLocal directory.






