# Phase 13 — Desktop file I/O (`core:transfer`)

> # ⛔ SUPERSEDED — DO NOT EXECUTE THIS FILE
>
> **2026-09-05.** This document is written for **D1 = A**. The repo is **D1 = B** (see
> `DECISIONS.md`; reaffirmed 2026-09-03). It requires a `jvmAndAndroidMain` source set holding the
> whole transfer pipeline — a source set `CONVENTIONS.md` R5 forbids creating at all — and its
> premise that `FileSourceOpener`, `FileRandomAccessSinkHandle` and `ChunkSource` are reachable from
> `jvmMain` is false. All three are `androidMain`, and `jvmMain` cannot resolve even their
> **packages**; a compile probe of this file's own proposed code produced 11 `Unresolved reference`
> errors and `BUILD FAILED`.
>
> **→ Execute [PHASE-13B-desktop-fileio.md](PHASE-13B-desktop-fileio.md) instead.** Its **13B-1** is
> executable today with no decision; **13B-2** is blocked on the new **D10** (what replaces
> `java.io.InputStream` in a `commonMain` signature), and **13B-3** additionally needs an explicit
> R8 authorisation to rewrite `ChunkFrame`'s framing.
>
> Fifteen further factual errors — the file inventory, the `DestinationTarget` arms, the
> `createSinkHandle` signature, the `:core:security`/`:core:discovery` edges, `androidUnitTest`, the
> 22-class jar, the ban on typed source-set accessors — are measured and tabulated in 13B under
> *"What PHASE-13 asserts, and what is actually true"*, recorded there rather than fixed here per R1.
>
> Still accurate and reused by 13B: the framing of the goal (*"a `java.io.File` → `ChunkSource`
> adapter and a `DestinationTarget` → `RandomAccessSinkHandle` resolver"*), the warning against
> building a "desktop file I/O framework", and the four-item shape of the verification gate.

**Blocked by:** Phase 11 (core:transfer KMP conversion) — must have `commonMain` + `jvmAndAndroidMain` split and `jvm()` target registered.

**Risk:** Medium. Adding a new source set to an already-converted module is low-risk, but the `jvmMain` files must be kept small to avoid dragging javax.net.ssl or other jvmAndAndroidMain types into the desktop-only path. The main risk is over-engineering: writing a "desktop file I/O framework" when all that's needed is a `java.io.File` → `ChunkSource` adapter and a `DestinationTarget` → `RandomAccessSinkHandle` resolver.

---

## What this phase is actually for

Give the `core:transfer` module the concrete file-I/O implementations it needs to run on a desktop JVM. The module already has `FileSourceOpener` (jvmAndAndroidMain), `FileRandomAccessSinkHandle` (jvmAndAndroidMain), and `ChunkSource` (commonMain) — all pure Java/`java.io` types. What it lacks is a `jvmMain` source set that wires those seams to `java.io.File` paths.

**What this phase does NOT do:**
- Does NOT change any existing file content or placement (Phase 11 already did the split).
- Does NOT touch the 4 wslegacy/ files or the 2 orphan Ws* files (dead code, not part of the desktop path).
- Does NOT implement a desktop Discovery, Network, Engine, or UI component.
- Does NOT introduce `expect`/`actual` into the transfer module (R10 applies by default; no platform divergence exists in the transfer seam).

---

## Why `core:transfer` needs a `jvmMain` source set

Post-Phase 11, the module has:

```
core:transfer/src/
├── commonMain/kotlin/    (5 files — pure Kotlin, no android.*, no java.*)
│   ├── FlashTransferRepository.kt
│   ├── FlashTransferState.kt
│   ├── FlashTransferDirection.kt
│   ├── FlashTransferId.kt
│   └── chunked/
│       ├── Chunker.kt          (ChunkSource fun interface, FileMeta, ChunkPlan)
│       └── Sha256.kt
│
├── jvmAndAndroidMain/kotlin/   (14 files — java.io/java.nio/java.util.concurrent)
│   ├── RealFlashTransferRepository.kt   (FileSourceOpener fun interface)
│   ├── model/
│   │   └── FlashTransfer.kt
│   ├── chunked/
│   │   └── Sha256Impl.kt
│   ├── multistream/
│   │   ├── MultiStreamDispatcher.kt
│   │   ├── MultiStreamResult.kt
│   │   ├── StreamChannel.kt
│   │   ├── StreamChannelFactory.kt
│   │   └── StreamMetrics.kt
│   ├── policy/
│   │   ├── DestinationPolicy.kt           (internal: DestinationTarget, createSinkHandle)
│   │   ├── DestinationTarget.kt           (internal)
│   │   ├── FileRandomAccessSinkHandle.kt  (public, java.io.RandomAccessFile)
│   │   └── RandomAccessSinkHandle.kt      (public interface)
│   ├── store/
│   │   ├── TransferStore.kt               (public port interface)
│   │   └── InMemoryTransferStore.kt
│   └── protocol/
│       ├── FlashTransferProtocol.kt
│       └── WsTransferModels.kt            (ORPHAN — no imports, file-only)
│
├── androidMain/      (0 files — empty)
│
└── jvmMain/          (DOES NOT EXIST YET — this phase creates it)
```

The gap: `RealFlashTransferRepository` accepts a `FileSourceOpener` (jvmAndAndroidMain) and `TransferStore` (jvmAndAndroidMain). Desktop needs:
1. A concrete `FileSourceOpener` that opens `java.io.File` by path.
2. A concrete `DestinationTarget` → `RandomAccessSinkHandle` resolver for desktop file paths.
3. A desktop `TransferStore` (or none — the port accepts `null` to disable persistence, as the doc says: _"null = run without persistence; only resume-across-restart is disabled"_).

---

## Preconditions

1. Phase 11 (core:transfer KMP) is complete and verified.
2. `core:transfer/build.gradle.kts` has `kotlin("multiplatform")`, `kotlin("plugin.android")`, `jvm()`, `androidLibrary`, and three source sets (`commonMain` + `jvmAndAndroidMain` + `androidMain`).
3. `jvmTest` + `androidUnitTest` source sets exist.
4. `:core:transfer:compileKotlinJvm` passes.
5. `:core:transfer:check` (or the equivalent test task) passes at the baseline test count.

---

## Verified starting state

### Post-Phase 11 `build.gradle.kts` (expected)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}

kotlin {
    androidLibrary {
        namespace = "com.transfer.flash.core.transfer"
        compileSdk = 35
        minSdk = 24
    }
    jvm()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":core:common"))
                api(libs.kotlinx.coroutines.core)
            }
        }

        getByName("jvmAndAndroidMain") {
            dependencies {
                implementation(project(":core:security"))
                implementation(project(":core:network"))
                implementation(project(":core:discovery"))
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime.ktx)
            }
        }

        getByName("androidMain") {
            // No android-specific files yet — empty source set
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace(project.name, "core-transfer")
        }
    }
}
```

### Dependency usage (from Phase 11 analysis)

| Dependency | Files importing it | Scope |
|---|---|---|
| `:core:common` | 5 commonMain + 14 jvmAndAndroidMain | `api` (commonMain) |
| `kotlinx.coroutines.core` | 5 commonMain + 14 jvmAndAndroidMain | `api` (commonMain) |
| `:core:security` | 2 jvmAndAndroidMain | `implementation` (jvmAndAndroidMain) |
| `:core:network` | 3 jvmAndAndroidMain | `implementation` (jvmAndAndroidMain) |
| `:core:discovery` | 1 jvmAndAndroidMain | `implementation` (jvmAndAndroidMain) |
| `androidx.core.ktx` | 0 (dead — relocated from earlier) | `implementation` (jvmAndAndroidMain) |
| `androidx.lifecycle.runtime.ktx` | 0 (dead — relocated from earlier) | `implementation` (jvmAndAndroidMain) |

### Tests (post-Phase 11)

- `jvmTest`: unit tests from `src/test/` moved to `src/jvmTest/kotlin/`.
- `androidUnitTest`: empty or absent — no Android-specific tests for transfer.
- Baseline count: **TBD** (recorded from Phase 11's `testDebugUnitTest` run).

---

## The source-set strategy

**Placement rule (from CONVENTIONS.md):**
1. Imports `android.*` → `androidMain`.
2. Imports `java.*`/`javax.*` or exposes an `androidMain`-only type in public signature → `jvmAndAndroidMain`.
3. Otherwise → `commonMain`.

**For Phase 13 specifically:** The new jvmMain files must use only `java.io.*` types that are already available on both JVM and Android. If a file cannot be placed in `jvmMain` because it needs a type that lives in `jvmAndAndroidMain`, it stays in `jvmAndAndroidMain` and the desktop implementation is a thin wrapper in `jvmMain`.

**R2 (never move code to commonMain to make it compile) applies.** These are new files, not moves.

---

## Placement table

| New file | Source set | Reason |
|---|---|---|
| `DesktopFileSourceOpener.kt` | `jvmMain` | Implements `FileSourceOpener` (jvmAndAndroidMain) using `java.io.FileInputStream` (pure JVM, no Android). |
| `DesktopDestinationPolicy.kt` | `jvmMain` | Provides `DestinationTarget` → `RandomAccessSinkHandle` resolution for `java.io.File` paths. Needs `FileRandomAccessSinkHandle` (jvmAndAndroidMain) at the call site. |
| `DesktopTransferConfig.kt` | `jvmMain` | Convenience factory: `fun File.toChunkSource()`, `fun File.toFileSourceOpener()`, `fun File.toDestinationTarget()`. |

No existing files are moved or changed. All 3 new files are in `jvmMain`.

---

## Dependency table

| Dependency | Scope | Reason |
|---|---|---|
| `:core:transfer` (same module, jvmAndAndroidMain) | `implementation` | `jvmMain` depends on `jvmAndAndroidMain` implicitly (source-set hierarchy). No new external dependency. |

**No new Gradle dependencies are added.** All needed types (`java.io.File`, `java.io.FileInputStream`, `java.io.RandomAccessFile`) are part of the JDK.

---

## Steps

### Step 1 — Add `jvmMain` source set to the build file

Open `core:transfer/build.gradle.kts` (post-Phase 11). Add the `jvmMain` block inside `sourceSets { }`:

```kotlin
sourceSets {
    // ... existing commonMain, jvmAndAndroidMain, androidMain blocks ...

    getByName("jvmMain") {
        dependsOn(getByName("jvmAndAndroidMain"))
        dependencies {
            // jvmMain depends on jvmAndAndroidMain by default; no extra deps needed.
            // All required types (java.io.File, FileInputStream, RandomAccessFile)
            // are JDK standard — no kotlinx or Android dependency.
        }
    }
}
```

**Why this is safe:** The `jvm()` target already exists (Phase 11). Adding `jvmMain` does not change the Android build path. The `jvmMain` source set is only compiled for the `jvm()` target. Verify with:

```bash
./gradlew :core:transfer:compileKotlinJvm --no-configuration-cache
```

**Expected: BUILD SUCCESSFUL** (empty source set, no files yet).

- [ ] `jvmMain` source set registered in `build.gradle.kts`.
- [ ] `:core:transfer:compileKotlinJvm` green (empty source set).

---

### Step 2 — Create the `jvmMain` directory structure

```bash
cd core/transfer/src

mkdir -p jvmMain/kotlin/com/transfer/flash/core/transfer/desktop
```

- [ ] `jvmMain/kotlin/com/transfer/flash/core/transfer/desktop/` exists.

---

### Step 3 — Create `DesktopFileSourceOpener.kt` (jvmMain)

`FileSourceOpener` (jvmAndAndroidMain) is a single-method `fun interface`:

```kotlin
public fun interface FileSourceOpener {
    public fun open(fileUri: String): InputStream
}
```

Desktop implementation — open a `java.io.File` by absolute path:

```kotlin
package com.transfer.flash.core.transfer.desktop

import com.transfer.flash.core.transfer.FileSourceOpener
import java.io.FileInputStream
import java.io.InputStream

/**
 * Desktop [FileSourceOpener] that opens [java.io.File] by absolute path.
 * The caller (e.g. RealFlashTransferRepository) expects a valid absolute path
 * that resolves on the local filesystem.
 */
public class DesktopFileSourceOpener : FileSourceOpener {

    override fun open(fileUri: String): InputStream {
        return FileInputStream(fileUri)
    }
}
```

**Design notes:**
- No validation: the caller is responsible for passing a valid, resolved path. Desktop file-picker APIs return resolved paths; no SAF content-URI on desktop.
- No `java.nio.file.Path` dependency (keeps Java 8 compatibility).
- No `java.io.File` wrapping the path — `FileInputStream(fileUri)` works directly with a string path.

- [ ] `DesktopFileSourceOpener.kt` created in `jvmMain`.

---

### Step 4 — Create `DesktopDestinationPolicy.kt` (jvmMain)

`DestinationPolicy` and `DestinationTarget` are `internal` in `jvmAndAndroidMain`. The `createSinkHandle` function maps `DestinationTarget` to `RandomAccessSinkHandle`. Desktop needs a policy that resolves `java.io.File` paths.

```kotlin
package com.transfer.flash.core.transfer.desktop

import com.transfer.flash.core.transfer.policy.DestinationTarget
import com.transfer.flash.core.transfer.policy.FileRandomAccessSinkHandle
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import java.io.File
import java.io.IOException

/**
 * Desktop destination policy that resolves [DestinationTarget] to
 * [FileRandomAccessSinkHandle] for the local filesystem.
 *
 * ## Path resolution
 *
 * - [DestinationTarget.File] — taken as-is (absolute path).
 * - [DestinationTarget.Directory] — appends the suggested file name if available,
 *   otherwise throws [IllegalArgumentException].
 * - [DestinationTarget.Temp] — resolves to `System.getProperty("java.io.tmpdir")`.
 */
public class DesktopDestinationPolicy {

    /**
     * Creates a [RandomAccessSinkHandle] for the given [target].
     * The caller is responsible for ensuring the parent directory exists.
     */
    public fun createSinkHandle(
        target: DestinationTarget,
        suggestedFileName: String? = null,
    ): RandomAccessSinkHandle {
        val file = resolveFile(target, suggestedFileName)
        ensureParentDir(file)
        return FileRandomAccessSinkHandle(file)
    }

    private fun resolveFile(
        target: DestinationTarget,
        suggestedFileName: String?,
    ): File = when (target) {
        is DestinationTarget.File -> File(target.absolutePath)
        is DestinationTarget.Directory -> {
            val name = suggestedFileName
                ?: throw IllegalArgumentException(
                    "DestinationTarget.Directory requires a suggestedFileName"
                )
            File(target.absolutePath, name)
        }
        is DestinationTarget.Temp -> {
            val tmpDir = System.getProperty("java.io.tmpdir")
                ?: throw IOException("java.io.tmpdir is not set")
            val name = suggestedFileName ?: "flash-transfer-${System.nanoTime()}"
            File(tmpDir, name)
        }
    }

    private fun ensureParentDir(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
    }
}
```

**Note:** `FileRandomAccessSinkHandle` is `public` in jvmAndAndroidMain and its constructor takes a `java.io.File`. The `DesktopDestinationPolicy` is `public` in jvmMain so that desktop callers can instantiate it directly.

- [ ] `DesktopDestinationPolicy.kt` created in `jvmMain`.

---

### Step 5 — Create `DesktopTransferConfig.kt` (jvmMain)

Convenience factory providing extension functions on `java.io.File`:

```kotlin
package com.transfer.flash.core.transfer.desktop

import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.chunked.ChunkSource
import com.transfer.flash.core.transfer.policy.DestinationTarget
import java.io.File
import java.io.FileInputStream

/**
 * Convenience factories for creating transfer components from [java.io.File].
 *
 * Usage:
 * ```kotlin
 * val source = file.toChunkSource()
 * val opener = file.toFileSourceOpener()
 * val target = file.toDestinationTarget()
 * ```
 */
public fun File.toChunkSource(): ChunkSource = ChunkSource {
    FileInputStream(this)
}

public fun File.toFileSourceOpener(): FileSourceOpener = DesktopFileSourceOpener()

public fun File.toDestinationTarget(): DestinationTarget = DestinationTarget.File(
    absolutePath = absolutePath,
)
```

- [ ] `DesktopTransferConfig.kt` created in `jvmMain`.

---

### Step 6 — Verify `jvmMain` compilation

```bash
./gradlew :core:transfer:compileKotlinJvm --no-configuration-cache
```

**Expected: BUILD SUCCESSFUL.** If it fails:
- **`FileSourceOpener` not found** → Phase 11 placed it in `jvmAndAndroidMain`; verify `jvmMain` has `dependsOn(jvmAndAndroidMain)`.
- **`DestinationTarget` not found** → same cause; verify `dependsOn` in the `jvmMain` block.
- **`FileRandomAccessSinkHandle` not found** → same cause.
- **`java.io.*` not found** → JVM target is not set to Java 11; verify `compileOptions` in the `androidLibrary` block.

- [ ] `:core:transfer:compileKotlinJvm` green.

---

### Step 7 — Run existing tests (assert no regression)

```bash
./gradlew :core:transfer:jvmTest --no-configuration-cache
```

**Expected: BUILD SUCCESSFUL** at the same test count recorded in Phase 11's baseline. No new tests are added in this phase (the desktop files are pure infrastructure; unit-testing them requires a filesystem, which belongs in the desktop-app-shell integration test suite).

- [ ] `:core:transfer:jvmTest` green at baseline count.

---

### Step 8 — Final sanity: `jvmJar` contains only the expected classes

```bash
./gradlew :core:transfer:jvmJar --no-configuration-cache
jar tf core/transfer/build/libs/core-transfer-jvm-*.jar | grep '\.class$' | sort
```

Expected entries:
- All 14 `jvmAndAndroidMain` classes (from Phase 11).
- 3 new `jvmMain` classes: `DesktopFileSourceOpener`, `DesktopDestinationPolicy`, `DesktopTransferConfigKt` (file-level extension functions).
- 5 `commonMain` classes.
- No `android/` paths.

- [ ] `jvmJar` green, no `android/` paths in the jar.

---

### Step 9 — Publish to local Maven (proves artifact is complete)

```bash
./gradlew :core:transfer:publishToMavenLocal --no-configuration-cache
```

Check `~/.m2/repository/com/transfer/flash/core-transfer/` for root + `-android` + `-jvm` variants.

- [ ] `publishToMavenLocal` emits root + `-android` + `-jvm`.

---

## Verification gate — do NOT log PASS until all are green

1. `:core:transfer:compileKotlinJvm` — **BUILD SUCCESSFUL**.
2. `:core:transfer:jvmTest` — **BUILD SUCCESSFUL**, baseline test count unchanged.
3. `:core:transfer:jvmJar` — **BUILD SUCCESSFUL**, jar contains 14+3+5 = 22 class entries, no `android/` paths.
4. `:core:transfer:publishToMavenLocal` — root + `-android` + `-jvm` with `.module`.

Any red ⇒ do not log PASS. Fix the owning cause (missing `dependsOn`, wrong source set, compile error) and re-run.

---

## Do NOT

- **Do NOT edit any existing file.** Phase 13 adds only new files in `jvmMain`. Existing `jvmAndAndroidMain` and `commonMain` files are untouched.
- **Do NOT introduce `expect`/`actual`** into the transfer module. The desktop needs only concrete implementations of existing interfaces.
- **Do NOT add a desktop-specific `TransferStore`** implementation in this phase. Desktop persistence is deferred to the engine phase (Phase 12/16). The `TransferStore` port accepts `null` to disable it.
- **Do NOT touch the 4 wslegacy/ files or the 2 orphan Ws* files.** They are dead code marked for later cleanup.
- **Do NOT reformat, reorder imports, or change visibility** of any existing file.
- **Do NOT run mid-migration Gradle without `--no-configuration-cache`.**
- **Do NOT use `jvm("desktop")`, typed source-set accessors, or `src/**/java/` roots.**

---

## Completion checklist

- [ ] `jvmMain` source set registered in `build.gradle.kts` with `dependsOn(jvmAndAndroidMain)`.
- [ ] 3 new files created in `core/transfer/src/jvmMain/kotlin/.../desktop/`:
  - `DesktopFileSourceOpener.kt`
  - `DesktopDestinationPolicy.kt`
  - `DesktopTransferConfig.kt`
- [ ] `:core:transfer:compileKotlinJvm` green.
- [ ] `:core:transfer:jvmTest` green at baseline count.
- [ ] `:core:transfer:jvmJar` green, no `android/` paths.
- [ ] `:core:transfer:publishToMavenLocal` emits root + `-android` + `-jvm`.
- [ ] Log entry appended to `docs/migration/logs/migration.md`.

---

## Rollback

```bash
git restore --staged core/transfer/build.gradle.kts
git checkout -- core/transfer/build.gradle.kts
git clean -fd core/transfer/src/jvmMain
```

This restores the pre-Phase-13 `build.gradle.kts` and removes the `jvmMain` tree. If `publishToMavenLocal` ran, stale `core-transfer*` entries in `~/.m2/repository/` are harmless.

---

## Log entry (mandatory)

Append **one** entry to `docs/migration/logs/migration.md` (append-only, newest at the bottom). Include:

- Confirmation that Phase 13 added 3 files to `jvmMain` of `core:transfer`.
- `compileKotlinJvm` SUCCESS output.
- `jvmTest` SUCCESS at baseline test count.
- `jvmJar` output (22 class entries, no `android/` paths).
- `publishToMavenLocal` output (root + `-android` + `-jvm`).
- The final statement: **`core:transfer` has a `jvmMain` source set with `DesktopFileSourceOpener`, `DesktopDestinationPolicy`, and `DesktopTransferConfig`; no existing files were changed; the baseline test count is unregressed.**