# Phase 06 — KMP Pilot: `core:common`

**Blocked by:** Phase 03 (must be done — it is what makes this module Android-free),
and decisions **D1** and **D2** in `DECISIONS.md`.
Phases 04 and 05 are recommended first but not strictly required.
**Risk: HIGH.** This is the hardest phase in the migration. It is the first build-file
change, it changes Gradle task names, and it can silently disable unit tests.
**Decisions needed: D1 and D2 must be answered before you start.** D3/D4 are Compose
decisions and do **not** block this phase — they block Phase 18.

## What this phase is actually for

You are converting **exactly one module** — `:core:common` — from
`com.android.library` to a Kotlin Multiplatform module with an Android target and a
desktop JVM target.

The point is **not** to make progress on the migration. 22 files is nothing. The point
is to discover, on the smallest possible module, every fact that phases 07–12 depend
on, and write those facts down. Specifically:

1. Which `androidLibrary`/`android` DSL shape AGP 9.3.1 actually accepts.
2. What the Android unit-test Gradle task is called after conversion.
3. Whether `explicitApi()` survives.
4. Whether `maven-publish` still produces a usable artifact.
5. Whether consumers (`:core:network` et al.) still resolve the module.

If the pilot fails, **the migration stops here** and the human decides whether to
proceed. Do not "work around" a failure by reverting to `com.android.library` in a way
that hides it.

## Why `core:common` is the right pilot

Verified:

- **14 production files, 8 test files.** Smallest module in the repo.
- **Exactly one file references `android.*`** — and after Phase 03 that is
  `logging/FlashPlatformLogSink.kt`, which was written specifically to be the
  `androidMain` file.
- **No `AndroidManifest.xml`, no `res/`, no assets.** `find core/common/src -type f
  ! -name "*.kt"` returns nothing. So `androidResources { enable = true }` is not needed
  and the Compose-resources crash gotcha cannot bite here.
- **No KSP, no Room, no Hilt, no Compose.**
- `consumer-rules.pro` contains **only comments, zero keep rules** (read it — it says so
  explicitly). So even if the new DSL drops consumer ProGuard rules, nothing is lost
  here. That is why the pilot is safe; it is *not* true of `:core:persistence`.
- **Every other module depends on it.** `:core:network` has
  `api(project(":core:common"))`. If cross-module resolution works here, it works
  everywhere.

## A finding you must act on first

`core/common/build.gradle.kts` declares:

```kotlin
implementation(libs.androidx.core.ktx)
```

`core/common` contains **no `androidx.*` reference at all**. Verify:

```bash
grep -rn --include=*.kt "androidx" core/common/src
```

Expected: no output. If confirmed, this dependency is unused, and removing it is what
makes the module genuinely Android-free. Remove it as part of Step 4. Record it.

## Background facts, verified against upstream docs

Read these before writing any Gradle code. Every one of them has bitten someone.

| Fact | Consequence for you |
|---|---|
| AGP 9.0+ makes `com.android.library` **incompatible** with `org.jetbrains.kotlin.multiplatform` in the same module. | You must switch the plugin, not add one. |
| The replacement plugin is **`com.android.kotlin.multiplatform.library`**. | Add it to the version catalog and to the root build with `apply false`. |
| AGP 9 has **built-in Kotlin**. `org.jetbrains.kotlin.android` conflicts and must not be applied. | Flash never applies it (verified: only `media-downloader-main/` does, and that is not in the build). Nothing to remove. |
| The new plugin has **no build variants**. | `buildTypes { }`, `singleVariant("release")`, and `debugImplementation` are all invalid. |
| Host (unit) tests are **opt-in**. Without an explicit test block, the module compiles and publishes with **zero tests** and the build still goes green. | This is the #1 danger. Step 6 exists solely to catch it. |
| Consumer ProGuard rules are **silently dropped** if not re-declared in the new DSL. | Harmless for `core:common` (rules file is empty), lethal for `core:persistence`. Record the correct syntax for later phases. |
| There is **no `BuildConfig`** in the new plugin. | Flash does not use `BuildConfig` in production code — the only match is a KDoc comment in `ui/chat/.../FlashStressTestScreen.kt:263,272`. Non-issue, but do not introduce one. |
| **NDK/JNI is unsupported.** | Flash has none. Non-issue. |
| The plugin is single-variant, so it cannot choose among a dependency's build types. | `localDependencySelection { }` may be required. See Step 4c. |
| Requires **Gradle 9.1.0+**, **JDK 17+**, **KSP 2.3.1+**. | Flash has Gradle 9.5.0 and KSP 2.3.11. Both fine. Confirm JDK with `java -version` (Phase 00 recorded it). |
| `android.enableLegacyVariantApi=true` exists as a temporary escape hatch, removed in AGP 10. | **Do not use it.** It hides exactly the errors this pilot is meant to surface. |

Sources:
[Updating multiplatform projects to AGP 9](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html),
[Kotlin AGP-9 migration skill](https://github.com/Kotlin/kotlin-agent-skills/blob/main/skills/kotlin-tooling-agp9-migration/SKILL.md),
[Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
[KotlinMultiplatformAndroidLibraryTarget reference](https://developer.android.com/reference/tools/gradle-api/8.8/com/android/build/api/dsl/KotlinMultiplatformAndroidLibraryTarget).

### The DSL-shape ambiguity — read this carefully

Upstream documentation is **inconsistent** about the DSL. Two shapes appear:

**Shape 1** — top-level `androidLibrary { }` sibling to `kotlin { }`
(kotlinlang.org AGP-9 migration guide):

```kotlin
androidLibrary {
    namespace = "..."
    compileSdk = 35
}
```

**Shape 2** — nested inside `kotlin { }` as `android { }`
(Kotlin's own agent-skills doc, which also uses `withHostTest`/`withDeviceTest`):

```kotlin
kotlin {
    android {
        namespace = "..."
        compileSdk = 35
        minSdk = 24
        withHostTest { }
    }
}
```

Older AGP 8.x alphas used a third form, `kotlin { androidLibrary { } }`, with
`withHostTestBuilder { }` / `withDeviceTestBuilder { }`.

**Do not guess.** Your job in Step 4 is to determine empirically which shape AGP 9.3.1
accepts and record it. The procedure is in Step 4b. Whichever one works becomes the
template for phases 07–12, so getting this recorded correctly is the main deliverable of
this phase.

## Steps

### Step 1 — Preconditions. Verify, do not assume.

```bash
grep -rn --include=*.kt -E "^import android\.|android\.util\." core/common/src
```
Expected: **only** `logging/FlashPlatformLogSink.kt`. If `FlashLogger.kt` still appears,
Phase 03 is not done. **Stop.**

```bash
grep -rn --include=*.kt "androidx" core/common/src
```
Expected: no output.

```bash
./gradlew --version
```
Confirm Gradle ≥ 9.1.0 and record the JDK line.

```bash
git status --short
```
Must be clean. This phase can require `git mv` of 22 files; do not mix that with other
work.

Record the current test count so Step 6 has something to compare against:

```bash
./gradlew :core:common:testDebugUnitTest --no-configuration-cache
```

Open `core/common/build/reports/tests/testDebugUnitTest/index.html` and write down the
**exact number of tests**. You will need this number. There are 8 test files; the test
*count* is higher than 8 and is the number that matters.

### Step 2 — Version catalog

Edit `gradle/libs.versions.toml`. Add to `[plugins]`, next to the existing
`android-library` entry:

```toml
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

Use `version.ref = "agp"` and `version.ref = "kotlin"` — the existing refs. Do **not**
introduce new version numbers (CONVENTIONS.md R10).

Do not remove `android-library`. Nine modules still use it.

### Step 3 — Root build script

Edit the root `build.gradle.kts`. In the existing `plugins { }` block, alongside the
current `apply false` entries, add:

```kotlin
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
```

`apply false` is mandatory — it registers the plugin for subprojects without applying it
at the root. Omitting it causes "plugin already on the classpath" conflicts.

Change nothing else in the root script. In particular leave `flashLibraryVersion`,
`group`, and the ADR-023 comment about Binary Compatibility Validator alone.

### Step 4 — Rewrite `core/common/build.gradle.kts`

#### 4a — What you are replacing

The current file is the template all 10 library modules share. Four things in it are
**invalid** under the new plugin. Know which before you delete anything.

| Current | Status | Why |
|---|---|---|
| `alias(libs.plugins.android.library)` | replace | incompatible with KMP under AGP 9 |
| `android { namespace, compileSdk, defaultConfig { minSdk } }` | relocate | moves into the new DSL block |
| `defaultConfig { testInstrumentationRunner = ... }` | relocate | moves into the device-test block |
| `defaultConfig { consumerProguardFiles("consumer-rules.pro") }` | relocate, **different syntax** | silently dropped if you forget |
| `buildTypes { release { ... } }` | **delete** | no build variants exist |
| `compileOptions { sourceCompatibility/targetCompatibility = VERSION_11 }` | replace | becomes `compilerOptions { jvmTarget }` |
| `publishing { singleVariant("release") { withSourcesJar() } }` | **delete** | no variants to select |
| `publishing { publications { register<MavenPublication>("release") { ... from(components["release"]) } } }` | **delete** | KMP creates its own publications — see Step 7 |
| `kotlin { explicitApi() }` | keep | must survive; verified in Step 8 |
| `implementation(libs.androidx.core.ktx)` | **delete** | unused (verified in Step 1) |
| `testImplementation(libs.junit)` | relocate | moves into a source-set dependency block |

#### 4b — Determine the DSL shape empirically

Do this before writing the real file. It takes one minute and removes all guesswork.

Write this minimal probe as `core/common/build.gradle.kts` (save a copy of the original
first — `cp core/common/build.gradle.kts /tmp/core-common-build.gradle.kts.bak`):

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.transfer.flash.core.common"
        compileSdk = 35
        minSdk = 24
    }
    jvm()
}
```

Then:

```bash
./gradlew :core:common:help --no-configuration-cache
```

Interpret the result:

- **Configures successfully** → Shape 2 (`kotlin { android { } }`) is correct. Use it.
- **Fails with "Unresolved reference: android"** → try replacing `kotlin { android { } }`
  with `kotlin { androidLibrary { } }`, re-run. If that configures, Shape 3 is correct.
- **Fails with "Unresolved reference" on both** → move the block out of `kotlin { }` to a
  top-level `androidLibrary { }` (Shape 1) and re-run.
- **Fails with a plugin-resolution error** naming
  `com.android.kotlin.multiplatform.library` → the plugin is not published at AGP 9.3.1's
  coordinates. **Stop the phase.** Record the exact error and report to the human; this
  is a blocking toolchain problem, not something to work around.

Record in your log, verbatim:

```
KMP_ANDROID_DSL_SHAPE = <1 | 2 | 3>
KMP_ANDROID_DSL_SNIPPET =
<paste the exact block that configured successfully>
```

Phases 07–12 copy this. If you get it wrong, six later phases are wrong.

Also probe the test-block spelling the same way. Add to whichever block worked:

```kotlin
        withHostTest { }
```

If that fails to resolve, try `withHostTestBuilder { }`. Record which one works as
`KMP_HOST_TEST_BLOCK`.

#### 4c — Write the real build file (D1 = A path)

**If D1 = B, skip to 4d.** If D1 is unanswered, **stop the phase** — you cannot pick
this for the human (DECISIONS.md says so explicitly for D1 and D2).

Also check D2 first. If D2 = B (rename `core:*` → `flash-*`), that rename must happen
in its own phase **before** this one, because it changes `namespace` and `artifactId`.
If D2 = B and the rename has not happened, stop and report.

Below is the target file. Read the four annotated blocks before typing it — three of
them are places where guessing produces a green build that is silently wrong.

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // ADR-023. Must stay. Verified in Step 8.
    explicitApi()

    // ---- Replace this whole block with KMP_ANDROID_DSL_SNIPPET from Step 4b. ----
    // If Step 4b found Shape 1, this block is a TOP-LEVEL `androidLibrary { }` and
    // lives outside `kotlin { }` — move it, keep the contents.
    android {
        namespace = "com.transfer.flash.core.common"
        compileSdk = 35
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`.
        // The syntax is DIFFERENT here and rules are dropped in silence if omitted.
        // core/common's rules file is empty, so nothing breaks either way — the line
        // exists so phases 07-12 copy a correct template. core:persistence's file is
        // NOT empty.
        consumerProguardFiles.add(file("consumer-rules.pro"))

        // Was `defaultConfig { testInstrumentationRunner = ... }`.
        // core/common has no `src/androidTest`, so this block is declared and empty
        // apart from the runner. Keep it: Phase 11 needs the template.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        // ***** THE MOST IMPORTANT LINE IN THIS FILE. *****
        // Omit it and all 8 test files stop being compiled or run, and the build
        // still reports SUCCESS. Step 6 exists to prove this line worked.
        withHostTest { }
    }

    // Desktop target. Plain `jvm()`, giving jvmMain/jvmTest (CONVENTIONS.md R5).
    // Do NOT write `jvm("desktop")`.
    jvm()

    sourceSets {
        // D1 = A. The intermediate source set that makes `java.*` shareable.
        // Both targets are JVM, so their intersection includes the JDK.
        //
        // Use getByName(String), NOT the typed accessors (`androidMain { }`).
        // The typed accessors may not exist for the new plugin's source sets, and
        // getByName fails with a message that LISTS THE REAL NAMES — which is how you
        // discover them. If it throws, copy the list into your log as
        // KMP_SOURCE_SET_NAMES and use the real names.
        val jvmAndAndroidMain = create("jvmAndAndroidMain")
        jvmAndAndroidMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)

        // Tests stay exactly where they are: JUnit 4, Android host-test tier,
        // source UNMODIFIED. See "Why the tests do not move" below.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}

// Was `android { compileOptions { sourceCompatibility/targetCompatibility = VERSION_11 } }`.
// See "The jvmTarget question" below before deciding whether to keep this.
publishing {
    publications.withType<MavenPublication>().configureEach {
        // KMP produces SEVERAL publications, not one. Default artifactIds derive from
        // the Gradle project name, which is `common` — so they would be `common`,
        // `common-android`, `common-jvm`. The published coordinate today is
        // `core-common`, so rewrite the prefix rather than hard-coding one name.
        artifactId = artifactId.replace("common", "core-common")
    }
}

dependencies {
    // libs.androidx.core.ktx deliberately NOT restored. It was unused; see Step 1.
    // testImplementation(libs.junit) moved into the source-set block above.
}
```

Delete the empty `dependencies { }` block entirely rather than leaving it — an empty
block is noise. It is shown here only so you can see that nothing was forgotten.

**Expect a warning, and do not try to silence it.** Creating a `dependsOn` edge by
hand disables Kotlin's Default Hierarchy Template, so the build prints something like
"The Default Kotlin Hierarchy Template was not applied to ':core:common'". That is
expected and harmless with only two targets. Do **not** add
`kotlin.mpp.applyDefaultHierarchyTemplate=false` to `gradle.properties` — that is a
global switch and this is a single-module pilot. Record the exact warning text.

#### Why the tests do not move

The 7 test classes plus `time/FakeTimeSource.kt` use **JUnit 4** (`org.junit.Test`,
`org.junit.Assert.*`), and `FlashLoggerTest.kt` additionally imports
`java.util.concurrent.CountDownLatch`, `Executors`, and `TimeUnit`. None of that
compiles in `commonTest`, which would need `kotlin.test` instead.

Migrating them would mean editing every test file in the same commit that changes the
build system. That destroys the one check this phase depends on: **"the same tests, the
same count, still passing."** If tests are rewritten and the count changes, you cannot
tell a build-system regression from a test-rewrite typo.

So: tests stay in the Android host-test tier, JUnit 4, byte-for-byte unmodified. The
only thing that changes about them is the directory they live in (Step 5). Migrating
tests to `commonTest`/`kotlin.test` is a legitimate later task — record it under
**Known issues**, do not do it here.

Consequence to accept knowingly: the `jvm()` target has **zero tests** in this phase.
That is fine — `compileKotlinJvm` still proves the production code compiles for
desktop, which is the fact the pilot needs.

#### The jvmTarget question

The old file pinned `sourceCompatibility`/`targetCompatibility` to Java 11. The new
plugin has no `compileOptions`. The nearest equivalent is per-target:

```kotlin
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    android {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
```

Try it. Two outcomes, both acceptable:

- **Resolves** → keep it, record `KMP_JVM_TARGET_DSL = per-target compilerOptions`.
- **Does not resolve** (`Unresolved reference: compilerOptions` on either target) →
  delete those lines, let the module take the plugin default, and record
  `KMP_JVM_TARGET_DSL = plugin default (unset)`.

You do not need to hunt for a third spelling. A JVM-target mismatch is **loud**, not
silent: the nine consumer modules are all Java 11, so if `core:common` ends up on a
higher target the very next build fails with
`Cannot inline bytecode built with JVM target NN into bytecode being built with JVM
target 11`. If you see that message, the answer is to find the DSL that works, not to
raise the other modules.

#### `localDependencySelection` — probe it, then decide

The new plugin is single-variant, so when a KMP module depends on a *variant-ful*
Android project it cannot choose between `debug` and `release`. The reported shape is:

```kotlin
    android {
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }
    }
```

`core:common` has **no project dependencies at all**, so it cannot hit this. Add the
block once, run `./gradlew :core:common:help --no-configuration-cache`, and record:

- resolves → `KMP_LOCAL_DEP_SELECTION = <the exact snippet>`, then **remove it** from
  `core/common/build.gradle.kts` (unused config is noise) and hand the snippet to
  phases 07–12.
- does not resolve → record the exact error as
  `KMP_LOCAL_DEP_SELECTION = unresolved: <error>`, remove the block, move on. Do not
  invent a different spelling. Phase 07 will hit the real symptom
  (`Could not resolve project :core:...` / "cannot choose between ... debug ...
  release") and can search from there with a concrete error in hand.

#### 4d — The build file if D1 = B

Identical to 4c with **one** deletion: there is no `jvmAndAndroidMain`, so the
`sourceSets { }` block collapses to

```kotlin
    sourceSets {
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
```

Everything else — plugins, the android block, `withHostTest { }`, `jvm()`,
`explicitApi()`, the publication rewrite — is unchanged.

The cost of D1 = B lands in **Step 5** instead: three files in this module use `java.*`
or `synchronized` and must each grow an `expect`/`actual` seam. Step 5d gives the exact
code for all three. Do not attempt D1 = B without reading 5d first; it is roughly three
times the work of 5c.

### Step 5 — Move the sources into KMP source sets

This is 22 existing files plus the 3 that Phase 03 added. Use `git mv` for every one,
never copy-and-delete: `git mv` preserves history and, more importantly, makes
`git status` legible so a reviewer can see nothing was lost.

#### 5a — The directory renames

KMP replaces `src/main` with per-source-set directories. Note that KMP's conventional
language directory is `kotlin/`, not `java/`.

| Old | New |
|---|---|
| `core/common/src/main/java/...` | `core/common/src/<sourceSet>/kotlin/...` |
| `core/common/src/test/java/...` | `core/common/src/androidHostTest/kotlin/...` |

Use the **real** source-set names from Step 4c. If `getByName("androidHostTest")` threw
and the real name was different, use that name in the path too — the directory name
must match the source-set name exactly or the files are silently not compiled.

Create the trees first:

```bash
mkdir -p core/common/src/commonMain/kotlin/com/transfer/flash/core/common
mkdir -p core/common/src/jvmAndAndroidMain/kotlin/com/transfer/flash/core/common/logging
mkdir -p core/common/src/androidMain/kotlin/com/transfer/flash/core/common/logging
mkdir -p core/common/src/jvmMain/kotlin/com/transfer/flash/core/common/logging
mkdir -p core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common
```

#### 5b — Where each file goes (D1 = A)

This table was built by grepping every production file for `java.*`, `javax.*`,
`System.*`, `android.*`, `synchronized`, and `@Volatile`. **Exactly three** production
files touch a JVM-only API; everything else is pure Kotlin and goes to `commonMain`.

| File (under `.../core/common/`) | Source set | Why |
|---|---|---|
| `annotation/FlashAnnotations.kt` | `commonMain` | pure — just `@RequiresOptIn` markers |
| `logging/FlashLogEntry.kt` | `commonMain` | pure — `FlashLogLevel` enum + `FlashLogEntry` data class |
| `logging/FlashLogSink.kt` *(Phase 03)* | `commonMain` | pure interface |
| `logging/FlashLog.kt` *(Phase 03)* | `commonMain` | facade — **edited** in 5d for the `expect` seam |
| `model/FlashDevice.kt` | `commonMain` | pure (no imports) |
| `model/FlashDeviceId.kt` | `commonMain` | uses `@JvmInline` — **fine in commonMain**, see note |
| `model/FlashPeerPresence.kt` | `commonMain` | pure |
| `model/FlashTransportType.kt` | `commonMain` | pure enum |
| `protocol/FlashEnvelope.kt` | `commonMain` | pure (imports only `FlashInternalApi`) |
| `protocol/FlashProtocol.kt` | `commonMain` | pure (imports only `FlashInternalApi`) |
| `protocol/FlashTextFraming.kt` | `commonMain` | pure string ops |
| `result/FlashError.kt` | `commonMain` | pure |
| `result/FlashResult.kt` | `commonMain` | pure |
| `id/FlashIdGenerator.kt` | `jvmAndAndroidMain` | `java.util.UUID.randomUUID()` |
| `time/FlashTimeSource.kt` | `jvmAndAndroidMain` | `System.currentTimeMillis()` |
| `logging/FlashLogger.kt` | `jvmAndAndroidMain` | `synchronized(lock)` |
| `logging/FlashPlatformLogSink.kt` *(Phase 03)* | **deleted** | split into `androidMain`+`jvmMain` in 5d |
| all 7 `src/test/.../*Test.kt` + `time/FakeTimeSource.kt` | `androidHostTest` | JUnit 4, unmodified |

**The `@JvmInline` note — do not "fix" it.** `model/FlashDeviceId.kt` is a
`@JvmInline value class`. That annotation looks JVM-specific, but `kotlin.jvm.JvmInline`
is declared `expect annotation class JvmInline` in the **common** stdlib (Common + JVM,
Since Kotlin 1.5), so it resolves in `commonMain` and is required there whenever a JVM
target is present. Leave the file exactly as it is and put it in `commonMain`. Do not
remove `@JvmInline`, do not add an import, do not convert it to a data class. (Verified
against the kotlin-stdlib API reference for `kotlin.jvm.JvmInline`.)

**Why three files go to `jvmAndAndroidMain`, not `commonMain`:** `java.util.UUID`,
`System.currentTimeMillis()`, and `synchronized` are JVM-only (CONVENTIONS.md R6).
Under D1 = A they compile unchanged in `jvmAndAndroidMain`, which is shared by both the
Android and desktop targets — so nothing is lost and no `expect`/`actual` is needed for
them. The **only** unavoidable seam in this whole module is the `android.util.Log` one
(5d), because `android.*` is not available even in `jvmAndAndroidMain`.

#### 5c — The `git mv` commands (D1 = A)

Run from the repo root, in the bash shell (Git Bash on Windows — forward slashes and
`git mv` both work). Do them in order. `git mv` requires the destination directory to
exist, so each group creates its directories first.

Let `P=com/transfer/flash/core/common` to keep the lines readable:

```bash
P=com/transfer/flash/core/common
SRC=core/common/src/main/java/$P
CM=core/common/src/commonMain/kotlin/$P
JA=core/common/src/jvmAndAndroidMain/kotlin/$P
HT=core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common
```

**Group 1 — pure files to `commonMain`:**

```bash
mkdir -p "$CM/annotation" "$CM/logging" "$CM/model" "$CM/protocol" "$CM/result"
git mv "$SRC/annotation/FlashAnnotations.kt"   "$CM/annotation/"
git mv "$SRC/logging/FlashLogEntry.kt"         "$CM/logging/"
git mv "$SRC/logging/FlashLogSink.kt"          "$CM/logging/"
git mv "$SRC/logging/FlashLog.kt"              "$CM/logging/"
git mv "$SRC/model/FlashDevice.kt"             "$CM/model/"
git mv "$SRC/model/FlashDeviceId.kt"           "$CM/model/"
git mv "$SRC/model/FlashPeerPresence.kt"       "$CM/model/"
git mv "$SRC/model/FlashTransportType.kt"      "$CM/model/"
git mv "$SRC/protocol/FlashEnvelope.kt"        "$CM/protocol/"
git mv "$SRC/protocol/FlashProtocol.kt"        "$CM/protocol/"
git mv "$SRC/protocol/FlashTextFraming.kt"     "$CM/protocol/"
git mv "$SRC/result/FlashError.kt"             "$CM/result/"
git mv "$SRC/result/FlashResult.kt"            "$CM/result/"
```

**Group 2 — JVM-only files to `jvmAndAndroidMain`:**

```bash
mkdir -p "$JA/id" "$JA/time" "$JA/logging"
git mv "$SRC/id/FlashIdGenerator.kt"   "$JA/id/"
git mv "$SRC/time/FlashTimeSource.kt"  "$JA/time/"
git mv "$SRC/logging/FlashLogger.kt"   "$JA/logging/"
```

**Group 3 — the `android.util.Log` file is NOT moved — it is deleted and rewritten in
5d:**

```bash
git rm "$SRC/logging/FlashPlatformLogSink.kt"
```

**Group 4 — tests to `androidHostTest` (unmodified):**

```bash
mkdir -p "$HT/time"
git mv core/common/src/test/java/com/transfer/flash/core/common/FlashDeviceTest.kt       "$HT/"
git mv core/common/src/test/java/com/transfer/flash/core/common/FlashEnvelopeTest.kt     "$HT/"
git mv core/common/src/test/java/com/transfer/flash/core/common/FlashIdGeneratorTest.kt  "$HT/"
git mv core/common/src/test/java/com/transfer/flash/core/common/FlashLoggerTest.kt       "$HT/"
git mv core/common/src/test/java/com/transfer/flash/core/common/FlashResultTest.kt       "$HT/"
git mv core/common/src/test/java/com/transfer/flash/core/common/FlashTextFramingTest.kt  "$HT/"
git mv core/common/src/test/java/com/transfer/flash/core/common/FlashTimeSourceTest.kt   "$HT/"
git mv core/common/src/test/java/com/transfer/flash/core/common/time/FakeTimeSource.kt   "$HT/time/"
```

Then confirm the old trees are empty and remove them:

```bash
find core/common/src/main core/common/src/test -type f
```

Expected: **no output**. If anything remains, it was not in the table above — stop and
record it. Then:

```bash
rmdir -p core/common/src/main/java/$P 2>/dev/null; \
rmdir -p core/common/src/test/java/$P 2>/dev/null; true
```

Do **not** delete `core/common/consumer-rules.pro`, `core/common/build.gradle.kts`, or
`core/common/proguard-rules.pro` — they live at the module root, not under `src/`.

No `package` statement changes. Moving from `src/main/java/<pkg>` to
`src/commonMain/kotlin/<pkg>` keeps the same package `com.transfer.flash.core.common.*`,
so **no import in any other module changes**. That is the point of keeping the package
path identical across the source-set rename.

#### 5d — The logging `expect`/`actual` seam (required under BOTH D1 = A and D1 = B)

This is the only `expect`/`actual` pair in the whole module, and it exists because
`android.util.Log` is `android.*` — unavailable in `commonMain` **and** in
`jvmAndAndroidMain`. Phase 03 deliberately left this for Phase 06 (its KDoc says so).

**The seam is an `expect fun`, not an `expect object`.** Since Kotlin 1.9.20,
`expect`/`actual` **classes and objects** emit a Beta warning unless you pass
`-Xexpect-actual-classes`. A published 1.0.0 library must not depend on a Beta feature
or a compiler opt-in flag. `expect fun` has been stable since 1.0 and emits nothing.
So `FlashPlatformLogSink` (the `public object` Phase 03 created) is **replaced** by a
factory function plus one private sink object per platform.

**Edit 1 — `commonMain/.../logging/FlashLog.kt`.** Exactly two lines change.

Add this import (bare `@Volatile` resolves to `kotlin.jvm.Volatile`, which does **not**
exist in `commonMain`; the multiplatform annotation is `kotlin.concurrent.Volatile`,
a `typealias` to `kotlin.jvm.Volatile` on JVM — identical bytecode, verified in
Phase 05):

```kotlin
import kotlin.concurrent.Volatile
```

Change the sink initializer from the Phase 03 form to the factory call:

```kotlin
    // was:  private var sink: FlashLogSink = FlashPlatformLogSink
    private var sink: FlashLogSink = platformLogSink()
```

Nothing else in `FlashLog.kt` changes. It does **not** need `@file:OptIn`: `FlashLog`
is annotated `@FlashInternalApi`, which already propagates opt-in to its own members and
their initializers.

**Edit 2 — new file `commonMain/.../logging/PlatformLogSink.kt`:**

```kotlin
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Returns the platform's default log sink: `android.util.Log` on Android, `System.err`
 * on desktop JVM. `internal` — installed automatically as [FlashLog]'s initial sink;
 * tests and platform bootstrap override it via [FlashLog.installSink].
 *
 * `expect fun` (not `expect object`) deliberately: object expect/actual is Beta and
 * would require `-Xexpect-actual-classes`. See PHASE-06 Step 5d.
 */
internal expect fun platformLogSink(): FlashLogSink
```

The `@file:OptIn` is required here (unlike `FlashLog.kt`): `platformLogSink` is a
top-level `internal` function, not a member of a marked class, and its return type
`FlashLogSink` is `@FlashInternalApi`.

**Edit 3 — new file `androidMain/.../logging/AndroidLogSink.kt`.** This is the body of
the deleted `FlashPlatformLogSink.kt`, verbatim, wrapped in a private object and an
`actual`:

```kotlin
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import android.util.Log
import com.transfer.flash.core.common.annotation.FlashInternalApi

internal actual fun platformLogSink(): FlashLogSink = AndroidLogSink

/** Forwards to `android.util.Log`. On the JVM unit-test tier `Log` throws "not mocked", so every call is wrapped. */
private object AndroidLogSink : FlashLogSink {
    override fun write(
        level: FlashLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val priority = when (level) {
            FlashLogLevel.INFO -> Log.INFO
            FlashLogLevel.WARN -> Log.WARN
            FlashLogLevel.ERROR -> Log.ERROR
        }
        try {
            val full = if (throwable != null) {
                "$message\n${Log.getStackTraceString(throwable)}"
            } else {
                message
            }
            Log.println(priority, tag, full)
        } catch (_: Throwable) {
            // Unit-test tier: android.util.Log is not mocked. Intentionally ignored.
        }
    }
}
```

**Edit 4 — new file `jvmMain/.../logging/JvmLogSink.kt`.** The desktop sink writes to
`System.err`. Like the Android sink, it must never throw (the `FlashLogSink` contract):

```kotlin
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi

internal actual fun platformLogSink(): FlashLogSink = JvmLogSink

/** Desktop default: writes to stderr. Never throws — a logging failure must not reach callers. */
private object JvmLogSink : FlashLogSink {
    override fun write(
        level: FlashLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        try {
            System.err.println("$level/$tag: $message")
            throwable?.printStackTrace(System.err)
        } catch (_: Throwable) {
            // Match the Android sink's contract: swallow forwarding failures.
        }
    }
}
```

After these four edits the module has exactly one `expect` (`platformLogSink` in
`commonMain`) and two `actual`s (`androidMain`, `jvmMain`). Both `actual` file's
visibility (`internal`) matches the `expect` (R7). If the compiler says
`Expected function 'platformLogSink' has no actual declaration in module <jvm>`, you put
the `jvmMain` file in the wrong directory — the path must be
`src/jvmMain/kotlin/...`, matching the `jvm()` target's default source-set name.

#### 5e — The three extra seams, **only if D1 = B**

**If D1 = A, skip this entire sub-step** — those three files went to `jvmAndAndroidMain`
in 5c and need no seam. This sub-step exists so that if the human chose the strict
`commonMain` path (D1 = B), the pilot still has exact instructions.

Under D1 = B there is no `jvmAndAndroidMain`, so `id/FlashIdGenerator.kt`,
`time/FlashTimeSource.kt`, and `logging/FlashLogger.kt` move to `commonMain` (not the
`jvmAndAndroidMain` path in 5c) and each JVM-only call is replaced by an `internal
expect fun` with two `actual`s.

**Read this before typing:** every `actual` pair below is **byte-for-byte identical**
between `androidMain` and `jvmMain`, because both targets are the JVM. Writing the same
line twice, three times over, for zero behavioural difference, *is* the cost of D1 = B
that DECISIONS.md D1 describes. If you find yourself doing this, confirm the human really
answered D1 = B and did not leave it pending.

**Seam 1 — time.** In `commonMain/.../time/FlashTimeSource.kt`, change
`SystemTimeSource.nowMs()` to call an expect, and add the expect in the same file:

```kotlin
    // was:  override fun nowMs(): Long = System.currentTimeMillis()
    override fun nowMs(): Long = currentTimeMillisPlatform()
```
```kotlin
internal expect fun currentTimeMillisPlatform(): Long
```
`androidMain/.../time/SystemTime.kt` **and** `jvmMain/.../time/SystemTime.kt`, identical:
```kotlin
package com.transfer.flash.core.common.time
internal actual fun currentTimeMillisPlatform(): Long = System.currentTimeMillis()
```

**Seam 2 — UUID.** In `commonMain/.../id/FlashIdGenerator.kt`:

```kotlin
    // was:  override fun newId(): String = java.util.UUID.randomUUID().toString()
    override fun newId(): String = randomUuidString()
```
```kotlin
internal expect fun randomUuidString(): String
```
`androidMain/.../id/Uuid.kt` **and** `jvmMain/.../id/Uuid.kt`, identical:
```kotlin
package com.transfer.flash.core.common.id
internal actual fun randomUuidString(): String = java.util.UUID.randomUUID().toString()
```

**Seam 3 — `synchronized` (the hard one).** `FlashLogger` has three
`synchronized(lock) { ... }` blocks (in `recent()`, `clear()`, and `log()`). `synchronized`
is a JVM intrinsic with no `commonMain` equivalent, and the usual replacement
`Mutex.withLock` is `suspend` — which would force `recent()` and `clear()` to become
`suspend` and change every caller (this is the viral-suspend problem Phase 05 documents).
Avoid that entirely with a non-suspend `expect`:

In `commonMain/.../logging/FlashLogger.kt`, replace each `synchronized(lock) {` with
`synchronizedImpl(lock) {` (three sites; the closing braces are unchanged), and add the
expect (top-level, same file or a sibling `commonMain` file):

```kotlin
internal expect fun <T> synchronizedImpl(lock: Any, block: () -> T): T
```
`androidMain/.../logging/Synchronized.kt` **and** `jvmMain/.../logging/Synchronized.kt`,
identical:
```kotlin
package com.transfer.flash.core.common.logging
internal actual inline fun <T> synchronizedImpl(lock: Any, block: () -> T): T =
    synchronized(lock, block)
```

Note `inline` on the `actual` only: it lets the JVM `synchronized` intrinsic inline as
before, so the bytecode of the hot path is unchanged. The `expect` must **not** carry
`inline` (an `expect` cannot be `inline`); an `actual` may add it. This asymmetry is
legal and is the one place in this phase where `expect` and `actual` modifiers
intentionally differ (R7 governs *visibility*, which stays `internal` on both).

Under D1 = B the `git mv` targets in 5c change for exactly these three files: they go to
`src/commonMain/kotlin/...` instead of `src/jvmAndAndroidMain/kotlin/...`, and you add
the six `actual` files above. Everything else in 5c is unchanged.

### Step 6 — Discover the test task name and PROVE tests still run

This is the step that catches the migration's most dangerous silent failure: a green
build that runs zero tests.

First, enumerate the module's test tasks — the plugin does not create
`testDebugUnitTest` any more:

```bash
./gradlew :core:common:tasks --all --no-configuration-cache | grep -i test
```

Record every test task printed. You are looking for the host-unit-test task. Under the
new plugin it is typically **`testHost`** (or a name containing `Host`), not
`testDebugUnitTest`. Record the exact name verbatim as:

```
ANDROID_UNIT_TEST_TASK = :core:common:<the exact task path you found>
```

Then run it:

```bash
./gradlew :core:common:<that task> --no-configuration-cache
```

Open the HTML report it produces (the path is printed on failure; on success look under
`core/common/build/reports/tests/`). Find the **test count** and compare it to the Step 1
baseline.

```
BASELINE_TEST_COUNT (from Step 1) = <n>
POST_CONVERSION_TEST_COUNT        = <m>
```

**The pass condition is `m == n`, and `m > 0`.** Three failure shapes, each handled
differently:

- **`m == 0` or "no tests found" / the task does not exist** → `withHostTest { }` did not
  take effect. The tests are not being compiled. **This is the silent failure the whole
  phase is designed to catch.** Do not proceed. Re-check that (a) `withHostTest { }` is
  present in the `android { }` block, (b) the tests are under `src/androidHostTest/kotlin/`
  (matching the source-set name exactly), (c) the JUnit dependency is on the
  `androidHostTest` source set. Fix and re-run until `m == n`.
- **`0 < m < n`** → some tests moved to a directory that is not being picked up. Find the
  missing class(es); the most likely cause is `time/FakeTimeSource.kt` or a subpackage
  landing in the wrong place. Fix the path, re-run.
- **`m == n`, all green** → tests survived the conversion. This is the phase's core
  success signal. Record it.

Now **fill in the discovered task name in two places** so every later phase can use it:

1. `docs/migration/CONVENTIONS.md` R3.1 — replace
   `ANDROID_UNIT_TEST_TASK = <not yet discovered — Phase 06 must fill this in>` with the
   real task path (leave it as a per-module pattern, e.g. note that it is
   `:<module>:testHost`, not just `:core:common:testHost`).
2. `docs/migration/logs/migration.md` (your phase log) — the same value, plus the raw
   `tasks --all | grep -i test` output you captured.

Also confirm the desktop side compiles, even though it has no tests:

```bash
./gradlew :core:common:compileKotlinJvm --no-configuration-cache
```

Must succeed. This is the proof that the pure/`jvmAndAndroidMain` code is genuinely
desktop-compatible — the first time in the project's history that any code is compiled
for a non-Android target.

### Step 7 — Publishing must still produce a usable artifact

Phase 03–06 exist to keep the library publishable. The old
`register<MavenPublication>("release") { from(components["release"]) }` was deleted in
Step 4 because it references the `release` component, which no longer exists. The KMP
plugin **creates its own publications** instead. Verify they are produced and coordinated
correctly:

```bash
./gradlew :core:common:publishToMavenLocal --no-configuration-cache
```

Then inspect what landed:

```bash
find ~/.m2/repository/com/transfer/flash -name "*core-common*" -o -path "*core*common*" | sort
```

You expect **more than one** artifact now (KMP publishes a root module plus per-target
artifacts). Record the exact artifactIds produced as `KMP_PUBLISHED_ARTIFACTS`. The
critical checks:

- A root `.module` (Gradle Module Metadata) file exists — this is what lets consumers
  resolve the right target automatically.
- The artifactId prefix is `core-common` (from the Step 4 `artifactId.replace(...)`),
  not the bare Gradle project name `common`. If you see `common-*` instead of
  `core-common-*`, the publication rename did not apply — fix it, because the published
  coordinate `com.transfer.flash:core-common` is part of the library's contract
  (PR #1 set it).

### Step 8 — Cross-module resolution, `explicitApi()` survival, and recording the facts

**8a — Every consumer must still resolve `core:common`.** Nine Gradle files depend on
it (`app`, `core:network`, `core:security`, `core:transfer`, `core:discovery`,
`core:engine`, `core:messaging`, `core:persistence`, `ui:theme`, `ui:chat`, plus
`sample/consumer-granular` transitively). The single command that exercises the whole
graph is the app build:

```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

If this passes, cross-module resolution works and **the pilot has succeeded** — an
Android `com.android.library`/`com.android.application` consumer can depend on a
`com.android.kotlin.multiplatform.library` producer. If it fails with
`Could not resolve project :core:common` or a message about choosing between `debug` and
`release` variants, that is the single-variant selection problem: revisit the
`localDependencySelection` probe from Step 4c, apply the block that resolved, and record
that phases 07–12 will need it on every converted module that has consumers. Do **not**
work around it by making the consumers depend on a specific variant.

**8b — Prove `explicitApi()` is still enforced.** The build file kept `explicitApi()`,
but confirm the plugin honours it rather than silently dropping it. Temporarily add a
visibility-less top-level declaration to any `commonMain` file:

```kotlin
fun explicitApiProbe() = Unit
```

Re-run `./gradlew :core:common:compileKotlinJvm --no-configuration-cache`. It **must**
fail with `Visibility must be specified in explicit API mode` (or
`Function 'explicitApiProbe' must specify visibility`). **Delete the probe line** and
confirm the build goes green again. If the probe compiled *without* error, `explicitApi()`
is not being applied under the new plugin — record this as a **blocking** finding for the
human; the published-ABI guarantee (ADR-023) depends on it.

**8c — Record every discovered fact in the phase log.** Phases 07–12 read these. Your
log entry for Phase 06 must contain, filled in:

```
KMP_ANDROID_DSL_SHAPE      = <1 | 2 | 3>
KMP_ANDROID_DSL_SNIPPET    = <the exact android/androidLibrary block that configured>
KMP_HOST_TEST_BLOCK        = <withHostTest | withHostTestBuilder>
KMP_JVM_TARGET_DSL         = <per-target compilerOptions | plugin default (unset)>
KMP_LOCAL_DEP_SELECTION    = <the snippet, or "unresolved: <error>", or "not needed">
KMP_SOURCE_SET_NAMES       = <the real names, if getByName ever listed them>
ANDROID_UNIT_TEST_TASK     = :<module>:<task>   (also written into CONVENTIONS.md R3.1)
BASELINE_TEST_COUNT        = <n>
POST_CONVERSION_TEST_COUNT = <m>   (must equal n)
KMP_PUBLISHED_ARTIFACTS    = <list from Step 7>
EXPLICIT_API_ENFORCED      = <yes | no>
DEFAULT_HIERARCHY_WARNING  = <the exact warning text, or "none">
androidx.core.ktx removed  = <yes>   (the unused dep from Step 1)
```

## Verification

The full gate for this phase, in order. Every command must pass.

```bash
./gradlew :core:common:compileKotlinJvm --no-configuration-cache
```
```bash
./gradlew :core:common:<ANDROID_UNIT_TEST_TASK from Step 6> --no-configuration-cache
```
```bash
./gradlew :core:common:publishToMavenLocal --no-configuration-cache
```
```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

Plus the recorded evidence: `POST_CONVERSION_TEST_COUNT == BASELINE_TEST_COUNT`,
`EXPLICIT_API_ENFORCED = yes`, and the `KMP_*` block above filled in. A phase that
builds green but cannot show the test count matched baseline is **not verified** (R3, R9).

Do **not** run a bare root `./gradlew testDebugUnitTest` and treat it as proof — that
task no longer covers `core:common`, so it would report success while running the old
task set minus this module's tests. That is precisely the trap R3 warns about.

## Do NOT

- Do **not** apply `org.jetbrains.kotlin.android` anywhere. AGP 9 has built-in Kotlin;
  applying it conflicts.
- Do **not** add `android.enableLegacyVariantApi=true` to `gradle.properties`. It hides
  the exact errors this pilot exists to surface, and it is removed in AGP 10.
- Do **not** add `kotlin.mpp.applyDefaultHierarchyTemplate=false` or
  `-Xexpect-actual-classes` to silence warnings. The first is a global switch for a
  single-module pilot; the second papers over a Beta feature this phase deliberately
  avoids (5d uses `expect fun`, which needs neither).
- Do **not** convert the tests to `commonTest`/`kotlin.test` in this phase. They stay
  JUnit 4 in `androidHostTest`, unmodified, so the count comparison is meaningful.
  Record the migration as a Known issue.
- Do **not** split the `FlashTimeSource`/`FlashIdGenerator` interfaces into `commonMain`
  with impls in `jvmAndAndroidMain` in this phase (D1 = A). The whole files go to
  `jvmAndAndroidMain`; the interface/impl split is a Phase 07 refinement if a later
  `commonMain` consumer needs the interface. Record it as a Known issue.
- Do **not** convert any other module. This phase is `core:common` only. If
  `:app:assembleDebug` reveals a second module needs the same treatment, that is Phase 07.
- Do **not** restore `implementation(libs.androidx.core.ktx)`. It was unused.
- Do **not** touch wire formats, crypto, or Room (R8) — none are in `core:common` anyway.
- Do **not** "work around" a plugin-resolution failure or an `explicitApi()`-not-enforced
  finding. Both are blocking; stop and report to the human (the pilot is allowed to
  conclude "this toolchain is not ready", and that is a valid, useful outcome).

## Completion checklist

- [ ] Step 1 preconditions verified; `BASELINE_TEST_COUNT` recorded from the report
- [ ] Version catalog: two plugin aliases added, `android-library` left intact
- [ ] Root `build.gradle.kts`: two `apply false` aliases added, nothing else changed
- [ ] `KMP_ANDROID_DSL_SHAPE` + `KMP_ANDROID_DSL_SNIPPET` + `KMP_HOST_TEST_BLOCK`
      determined empirically (Step 4b) and recorded
- [ ] `core/common/build.gradle.kts` rewritten (D1 = A: 4c; D1 = B: 4d); `explicitApi()`
      kept, `buildTypes`/`singleVariant`/old publication/`androidx.core.ktx` removed,
      `consumerProguardFiles.add(file(...))` present, `withHostTest { }` present
- [ ] 22 files `git mv`d per 5c; `FlashPlatformLogSink.kt` deleted; old `src/main`,
      `src/test` trees gone
- [ ] Logging `expect`/`actual` seam created (5d): `platformLogSink` expect in
      `commonMain`, `AndroidLogSink` in `androidMain`, `JvmLogSink` in `jvmMain`;
      `FlashLog.kt` uses `import kotlin.concurrent.Volatile` and `= platformLogSink()`
- [ ] (D1 = B only) the three extra seams from 5e created, or explicitly "skipped — D1 = A"
- [ ] `ANDROID_UNIT_TEST_TASK` discovered; written into CONVENTIONS.md R3.1 and the log
- [ ] `POST_CONVERSION_TEST_COUNT == BASELINE_TEST_COUNT`, all green
- [ ] `:core:common:compileKotlinJvm` passes (desktop compiles)
- [ ] `publishToMavenLocal` produces `core-common`-prefixed artifacts incl. a `.module`
- [ ] `:app:assembleDebug` passes (cross-module resolution proven)
- [ ] `explicitApi()` proven still enforced (Step 8b probe)
- [ ] Full `KMP_*` fact block recorded in the log (Step 8c)
- [ ] Known issues listed: test→commonTest migration, interface/impl split deferral,
      anything `:app:assembleDebug` surfaced
- [ ] Log entry appended (R9: honest, with pasted evidence)

## Rollback

Everything in this phase is one commit. `git revert <sha>` restores the
`com.android.library` module and its `src/main`/`src/test` layout, because the file moves
were `git mv` (tracked renames) and the build-file rewrite is in the same commit (R4).
The two version-catalog plugin aliases and the two root `apply false` lines are inert
when nothing references them, but the revert removes them too. After reverting, re-run
`./gradlew :app:assembleDebug testDebugUnitTest --no-configuration-cache` to confirm the
pre-phase state is restored.

If the pilot **failed** (plugin not resolvable, `explicitApi()` not enforced, or tests
cannot be made to run), do not leave a half-converted module on the branch: revert, and
record the blocking finding in `DECISIONS.md` under a new note so the human decides
whether the migration proceeds. Per this phase's opening: if the pilot fails, the
migration stops here.
