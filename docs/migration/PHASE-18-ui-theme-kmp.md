# Phase 18 — `ui:theme` KMP conversion

> ## STATUS: DONE — 2026-09-05, commit `96e8799`
>
> **Read this box before following anything below it.** Fourteen statements in this file are wrong,
> on top of the six the Phase-17 box below already corrects, and three of them make a step
> impossible to execute as written. Full account in `logs/migration.md` → "Phase 18 — `:ui:theme`
> KMP conversion proper".
>
> **Contradictions — a step and a rule that cannot both be obeyed:**
>
> 1. **Step 5 vs *"Do NOT change test imports"*.** `commonTest` cannot see `org.junit`, and step 5
>    puts all five suites in `commonTest`. The suites were converted to `kotlin.test`, as
>    `:core:security` and `:core:discovery` both did. Per R3.1 that is also the only way the three
>    `actual`s get *executed* on both targets rather than merely compiled.
> 2. **Step 1a's `isReduceMotionOnPlatform()` cannot compile:** the `actual` calls
>    `LocalContext.current` from a non-`@Composable` function. The `expect` must be `@Composable`.
> 3. **Step 1b marks `rememberFlashSounds` `internal actual`.** It is published 1.1.0 public API.
>    Narrowing it is an API deletion, which **R2 forbids outright**. It stays public.
>
> **Wrong claims about the code:**
>
> 4. **`FlashSoundPolicy` is not "pure JVM — uses compile-time constants only".** True of the
>    bytecode, false of the source: the constants were `android.media.AudioManager.RINGER_MODE_*`
>    and `android.app.NotificationManager.INTERRUPTION_FILTER_*`. They are now five private
>    `const val`s, read from `platforms/android-37.0/android.jar` with `javap -constants`, with the
>    public signature and default *values* unchanged.
> 5. **`FlashThemeSwatches.kt` is not pin-free.** The inventory lists it "None → commonMain". Its
>    seven `@Preview` functions pass `name`/`showBackground`/`widthDp`/`fontScale`, and CMP 1.9.3's
>    `org.jetbrains.compose.ui.tooling.preview.Preview` takes **no arguments** — moving it to
>    `commonMain` silently drops all of them. It is in `androidMain`.
> 6. **Step 1c's `FlashTheme.android.kt` omits the `androidx.compose.material3.ColorScheme` import**
>    its own return type needs.
> 7. **The production file table undercounts** (already in the Phase-17 box): 19 files, not 18.
>    `FlashBrandAnimation.kt` is missing. 19 = **18 `commonMain` + 1 `androidMain`**.
>
> **Step 6's build file is pre-AGP-9 and must not be pasted over Phase 17's:**
>
> 8. It uses `androidLibrary { }` — the real block inside `kotlin { }` is **`android { }`**; asserts
>    `consumerProguardFiles` *"are removed"* when Phase 17 preserved them via
>    `optimization { consumerKeepRules { file(…); publish = true } }` (they are dropped in **silence**
>    otherwise); declares `register<MavenPublication>("release") { from(components["release"]) }`,
>    which a KMP module must not do because KMP generates its own publications; and hardcodes
>    `version = "1.0.0"` — the exact bug that silently published 1.0.0 for the whole 1.1.0 cycle.
> 9. **`compose.animation` is missing and is mandatory.** `FlashMotion` and `FlashBrandAnimation`
>    import `EnterTransition`, `fadeIn`, `Animatable`, `CubicBezierEasing`, `spring`.
>    `compose.foundation` does not carry them.
> 10. **It downgrades `compose.components.resources` to `implementation`**, re-breaking what Phase 17
>     fixed: `DrawableResource` is the declared type of the public `FlashIconSpec.drawableRes`, so it
>     must stay `api`.
> 11. **It moves `libs.androidx.compose.ui.tooling.preview` and `libs.androidx.lifecycle.runtime.ktx`
>     to `commonMain`** — Android-only AARs, which cannot resolve for `jvm()`. Both stay in
>     `androidMain`, and tooling-preview is load-bearing because of correction 5.
> 12. **It puts `libs.junit` in `commonTest`**, which must stay platform-free. `kotlin("test")`
>     resolves to `kotlin-test-junit` on both JVM tiers and needs JUnit 4 at runtime for its runner,
>     so `libs.junit` is declared in `androidHostTest` **and** `jvmTest` separately.
> 13. **It hoists `project(":core:common")` to `commonMain`.** The module has zero references to it,
>     so that would only add a dependency to the new `ui-theme-jvm` POM. It, `libs.androidx.core.ktx`
>     and `libs.androidx.lifecycle.runtime.ktx` are all unreferenced and all stay where Phase 17 left
>     them — dropping a published runtime dependency is a separate decision (R1).
>
> **Gate table:**
>
> 14. **Task names and counts.** `compileKotlinDesktop` → **`compileKotlinJvm`** (also in the
>     Phase-17 box); `compileDebugKotlin` → **`compileAndroidMain`** (the KMP Android target is
>     variant-free); `allTests` is not how this repo tallies — CONVENTIONS R3's command line is, and
>     it now carries `:ui:theme:jvmTest`. Correct file counts: **18** `commonMain`, **4**
>     `androidMain` (1 moved + 3 `actual`), **3** `jvmMain`, **5** `commonTest`. The
>     `^internal expect fun` grep expects 3 and matches **2** (`rememberFlashSounds` is public per
>     correction 3); `^internal actual fun` over `jvmMain` matches **2**, not 3.
>
> **Naming:** platform files are `X.jvm.kt`, not `X.desktop.kt` — the repo convention since Phase 08.
>
> **Two call-site rewrites this file never mentions**, both argued to behavioural identity in code:
> `FlashTheme` passes `dynamicScheme?.let { DYNAMIC_ACCENT_MIN_SDK } ?: 0` where it used to pass
> `Build.VERSION.SDK_INT`, and `Theme.kt`'s `when` folds `dynamicColor && SDK_INT >= S` into
> `dynamicColor -> flashDynamicColorScheme(...) ?: authored`.
>
> **Baseline hit exactly:** 5 XMLs / 37 tests / 0 failures on **both** `testAndroidHostTest` and the
> new `jvmTest`; repo-wide **1055 / 12 / 0 across 140 XMLs**, the figure the Phase-17 box predicted.
> The 12 are the known pre-existing `:core:persistence` failures.
>
> **Not verified (R9):** the three `@Composable` actuals are executed only insofar as their
> non-`@Composable` logic is — there is no Compose UI-test harness in this repo and no device,
> emulator or desktop-window run happened. Desktop sound output and desktop reduce-motion detection
> are deliberately unimplemented (Phase 19 shim territory), not stubbed work in progress.


> ## AMENDED BY PHASE 17 — 2026-09-05 (`23267ed`). Read before executing.
>
> Phase 17 could not avoid doing part of this phase's work: its step 4a (keep
> `com.android.library`, add `org.jetbrains.compose`) is **impossible** under AGP 9, while this
> phase declares itself blocked by 17 — a circular deadlock. Phase 17 broke it by converting the
> module and leaving every Kotlin file where it was. So:
>
> - **Already done, do not redo.** The plugin pair (`kotlin.multiplatform` +
>   `android.kotlin.multiplatform.library`), `alias(libs.plugins.jetbrains.compose)`, the
>   `android { }` block (namespace, compileSdk 37, minSdk 24, `optimization { consumerKeepRules }`,
>   `localDependencySelection`, `compilerOptions.jvmTarget`, `withHostTest { }`,
>   `withDeviceTest { }`), the **`jvm()` target**, `compose.resources { packageOfResClass }`, and
>   the KMP publication rename (`theme*` → `ui-theme*`). `FlashIcons.kt` is already on
>   `Res.drawable.*` / `DrawableResource` — **do not touch it** beyond moving the file.
> - **You MUST delete these two lines** from `ui/theme/build.gradle.kts`, as part of the same
>   commit that moves the files. If you move files and leave the shims, or delete the shims
>   without moving, the module compiles from neither path:
>   ```kotlin
>   getByName("androidMain").kotlin.srcDir("src/main/java")
>   getByName("androidHostTest").kotlin.srcDir("src/test/java")
>   ```
>   They exist precisely so this phase's move table below stays executable verbatim.
> - **`compileKotlinDesktop` does not exist** anywhere in this repo. R5 mandates plain `jvm()`, so
>   every occurrence below means **`compileKotlinJvm`**. The Android compile task is
>   `compileAndroidMain`; the unit-test task is `testAndroidHostTest` (see CONVENTIONS R3.1).
> - **The file table undercounts: there are 19 production files, not 18.**
>   `FlashBrandAnimation.kt` is missing from it. Reconcile before trusting the
>   "expect 14 commonMain / 3–4 androidMain" gate.
> - **Add `:ui:theme:jvmTest` to the CONVENTIONS R3 command.** Phase 17 added only
>   `:ui:theme:testAndroidHostTest`, because the five suites are still `androidHostTest`-only and
>   `jvmTest` would run zero tests. This phase moves them to `commonTest`, so this phase is what
>   makes `jvmTest` meaningful — and per R3.1, an `actual` that is only compiled is not verified.
> - **CMP is 1.9.3 and cannot be raised.** 1.11+/1.12 need Kotlin 2.3; R10 freezes 2.2.10. When
>   this phase replaces the androidx BOM tier with `compose.*` artifacts per D3 = A, note that
>   `compose.runtime` is **already** in `commonMain` and is **not optional** — the Compose compiler
>   plugin fails every compilation in the module without it, `@Composable` or not.
> - **Baseline to preserve:** `:ui:theme:testAndroidHostTest` = 5 XMLs, **37 tests, 0 failures**.
>   Moving suites to `commonTest` should take this to 37 Android + 37 JVM = **74**, i.e. repo-wide
>   1018 → 1055 across 135 → 140 XMLs. Anything less means a suite stopped running.

**Blocked by:** Phase 17 (icon resources in CMP `composeResources`) — **satisfied 2026-09-05**; and 17 pre-paid this phase's plugin/target work, so what remains is the file move, the three `expect`/`actual` pairs, the test move, the shim deletion and the dependency rewrite.

**Gated by:** D3 (Compose dependency source — `org.jetbrains.compose` plugin) and D4 (dynamic color replacement — `expect fun flashDynamicColorScheme`). Per DECISIONS.md, both are in the "agent may proceed with the recommendation" class. **This phase is written assuming D3 Option A and D4 Option A.** If D3 is resolved differently, the build-plugin and dependency instructions change; if D4 is resolved differently, the `flashDynamicColorScheme` expect/actual is replaced with a simpler no-op.

**Risk: Medium.** This is the first module with Compose content to be KMP-converted. The interplay between `org.jetbrains.compose` plugin, `kotlin.compose` plugin, `com.android.library` (or `com.android.kotlin.multiplatform.library`), and `compose.components.resources` must be resolved correctly. The ui:theme module has 18 prod files and 5 test files, with 4 files carrying Android-specific code (FlashMotion, FlashSounds, FlashTheme, Theme) and the rest being pure Compose.

---

## What this phase is actually for

Convert the `ui:theme` module from a single-source-set Android library (`com.android.library`) to a KMP module with `commonMain`, `androidMain`, and `jvmMain` source sets. The 18 prod Kotlin files split as follows:

- **14 files → `commonMain`** — pure Compose/Kotlin code, no Android/`java.*` dependency
- **4 files → Android-pinned → `commonMain` + `androidMain` `expect`/`actual` split** — FlashMotion.kt, FlashSounds.kt, FlashTheme.kt, Theme.kt
- **5 test files → `commonTest`** — all are pure JUnit tests; move unchanged

**What this phase does NOT do:**
- Does NOT change `ui:chat` (Phase 20).
- Does NOT change `app` (Phase 21 for desktop shell).
- Does NOT touch the `FlashIcons.kt` or `composeResources/` (Phase 17 already did).
- Does NOT implement desktop sounds — the `jvmMain` `actual` for sound support is a no-op stub (desktop has no audio synthesis requirement yet).
- Does NOT implement desktop reduce-motion detection — the `jvmMain` `actual` returns `false` (no reduce-motion query mechanism on desktop).
- Does NOT change any behavior on Android — all `actual` implementations in `androidMain` preserve the current code exactly.

---

## Preconditions — do not start until all are true

1. **Phase 17 is complete and logged** — `ui/theme/src/commonMain/composeResources/drawable/` exists with 51 files, and `flash_ic_arrow_left.xml` is moved alongside the rest.
2. **Phase 06 is complete and logged** — the KMP pilot established the project's source-set layout, toolchain, and verified that `com.android.kotlin.multiplatform.library` (or the chosen AGP 9 KMP approach) works.
3. **D3 is resolved or the agent has proceeded with Option A** — the `org.jetbrains.compose` plugin is available.
4. **D4 is resolved or the agent has proceeded with Option A** — the `expect fun flashDynamicColorScheme()` approach is acceptable.

---

## Verified starting state

### File inventory (18 prod Kotlin files)

| # | File | Android pins | Moves to |
|---|---|---|---|
| 1 | `icons/FlashIcons.kt` | None (post-Phase 17) | `commonMain` |
| 2 | `avatar/FlashAvatar.kt` | None | `commonMain` |
| 3 | `Color.kt` | None | `commonMain` |
| 4 | `FlashColors.kt` | None | `commonMain` |
| 5 | `FlashDimensions.kt` | None | `commonMain` |
| 6 | `FlashElevation.kt` | None | `commonMain` |
| 7 | `FlashFeedback.kt` | `LocalHapticFeedback` (CMP common) | `commonMain` |
| 8 | `FlashInteraction.kt` | None | `commonMain` |
| 9 | **`FlashMotion.kt`** | `Context`, `Build`, `Settings`, `AccessibilityManager`, `LocalContext` | **`commonMain` + `androidMain` expect/actual** |
| 10 | `FlashShapes.kt` | None | `commonMain` |
| 11 | **`FlashSounds.kt`** | `Context`, `AudioTrack`, `AudioManager`, `NotificationManager`, `AudioAttributes`, `AudioFormat`, `LocalContext` | **`commonMain` + `androidMain` expect/actual** |
| 12 | `FlashSpacing.kt` | None | `commonMain` |
| 13 | `FlashText.kt` | None | `commonMain` |
| 14 | **`FlashTheme.kt`** | `Build`, `dynamicDarkColorScheme`, `dynamicLightColorScheme`, `LocalContext` | **`commonMain` + `androidMain` expect/actual** |
| 15 | `FlashThemeSwatches.kt` | None | `commonMain` |
| 16 | `FlashTypography.kt` | None | `commonMain` |
| 17 | **`Theme.kt`** | `Build`, `dynamicDarkColorScheme`, `dynamicLightColorScheme`, `LocalContext` | **`commonMain` + `androidMain` expect/actual** |
| 18 | `Type.kt` | None | `commonMain` |

### 5 test files

| File | Android imports | Moves to |
|---|---|---|
| `FlashDarkPaletteTest.kt` | `Color` (Compose, not Android) | `commonTest` |
| `FlashDynamicAccentTest.kt` | `Color` (Compose, not Android) | `commonTest` |
| `FlashFeedbackLogicTest.kt` | None | `commonTest` |
| `FlashSoundsTest.kt` | None | `commonTest` |
| `FlashThemeTokensTest.kt` | None | `commonTest` |

### Current build.gradle.kts

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.ui.theme"
    compileSdk = 37
    defaultConfig { minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro") }
    buildTypes { release { isMinifyEnabled = false
        proguardFiles(...) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11 }
    buildFeatures { compose = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

publishing { publications { register<MavenPublication>("release") { ... } } }

dependencies {
    implementation(project(":core:common"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
}
```

---

## The change

### Step 1 — Split the 4 Android-pinned files into `commonMain` + `androidMain`

For each of the 4 files, the strategy is:
- The `commonMain` version contains the pure API surface (data classes, composables with `expect` platform queries)
- The `androidMain` version contains the `actual` implementations using the current Android code

#### Step 1a — `FlashMotion.kt`

**Split into:**

`commonMain/com/transfer/flash/ui/theme/FlashMotion.kt`:
- Keep all animation specs, data classes, `FlashMotion` data class, `FlashMotionDefaults`, easing curves, `CubicBezierEasing` constants, `ScreenSlide`, `TabSlide`, `BadgePopStartScale`, spring constants
- Add `expect fun isReduceMotionOnPlatform(): Boolean`
- Move `isReduceMotionEnabled(context: Context)` + `isReduceMotionEnabledCompat()` to `androidMain`

```kotlin
// commonMain — keep everything except the Android-specific detection
// Replace the isReduceMotionEnabled companion method with:
internal expect fun isReduceMotionOnPlatform(): Boolean

@Composable
fun rememberFlashMotion(): FlashMotion {
    val reduceMotion = isReduceMotionOnPlatform()
    return remember(reduceMotion) { FlashMotion(reduceMotion = reduceMotion) }
}
```

`androidMain/com/transfer/flash/ui/theme/FlashMotion.android.kt`:
```kotlin
package com.transfer.flash.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.platform.LocalContext

internal actual fun isReduceMotionOnPlatform(): Boolean {
    val context = LocalContext.current
    return FlashMotion.isReduceMotionEnabled(context)
}

// Keep the original companion method on FlashMotion unchanged
// (already in the commonMain data class file — or move it here)
```

> **Decision:** The `FlashMotion.isReduceMotionEnabled(context: Context)` companion method is Android-specific. Move it to the `androidMain` file. The `commonMain` `FlashMotion` companion object keeps only the pure-Kotlin constants (easing curves, spring values, screen/tab slide fractions, etc.). The `rememberFlashMotion()` composable in `commonMain` calls the `expect fun` instead of `LocalContext`.

#### Step 1b — `FlashSounds.kt`

**Split into:**

`commonMain/com/transfer/flash/ui/theme/FlashSounds.kt`:
- Keep `FlashSound` enum with all `ToneSegment` entries (pure data)
- Keep `ToneSegment` data class
- Keep `FlashSoundPolicy` (pure JVM — uses compile-time constants only)
- Keep `FlashSoundSettings` (pure JVM)
- Keep `FlashSoundSynth` (pure JVM — PCM synthesis from math)
- Add `expect fun rememberFlashSounds(): (FlashSound) -> Unit`
- Replace the `rememberFlashSounds()` composable and `FlashSoundPlayer` with an `expect` declaration

`androidMain/com/transfer/flash/ui/theme/FlashSounds.android.kt`:
```kotlin
package com.transfer.flash.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.app.NotificationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberFlashSounds(): (FlashSound) -> Unit {
    val context = LocalContext.current.applicationContext
    return remember { { sound -> FlashSoundPlayer.play(context, sound) } }
}

// Keep the full FlashSoundPlayer internal object as-is
internal object FlashSoundPlayer {
    // ... identical to current code, full AudioTrack pipeline ...
}
```

`jvmMain/com/transfer/flash/ui/theme/FlashSounds.desktop.kt`:
```kotlin
package com.transfer.flash.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop: no-op sound stub. Desktop audio synthesis is not in scope for the migration.
 * A future phase can add javax.sound.sampled or a similar backend.
 */
@Composable
internal actual fun rememberFlashSounds(): (FlashSound) -> Unit {
    return remember { { /* no-op */ } }
}
```

#### Step 1c — `FlashTheme.kt`

**Split into:**

`commonMain/com/transfer/flash/ui/theme/FlashTheme.kt`:
- Keep `LocalFlashColors`, `LocalFlashTypography`, `LocalFlashMotion` composition locals
- Keep `FlashTheme` object (accessors)
- Keep `FlashTheme()` composable — but replace the `dynamicScheme` block with a call to `expect fun flashDynamicColorScheme()`
- Keep `DYNAMIC_ACCENT_MIN_SDK = 31` (pure JVM constant)
- Keep `resolveAccent()` (pure function — no Android dependency)

```kotlin
// commonMain
@Composable
fun FlashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicAccent: Boolean = false,
    hapticsEnabled: Boolean = true,
    colors: FlashColors = if (darkTheme) FlashColors.dark() else FlashColors.light(),
    typography: FlashTypography = FlashTypography.default(),
    motion: FlashMotion = rememberFlashMotion(),
    content: @Composable () -> Unit,
) {
    val dynamicScheme = if (dynamicAccent) {
        flashDynamicColorScheme(dark = darkTheme)  // ← expect fun
    } else {
        null
    }
    val resolvedColors = remember(colors, dynamicScheme) {
        resolveAccent(
            dynamicAccent = dynamicAccent,
            sdkInt = dynamicScheme?.let { DYNAMIC_ACCENT_MIN_SDK } ?: 0,
            dynamicPrimary = dynamicScheme?.primary,
            dynamicSecondary = dynamicScheme?.secondary,
            fallback = colors,
        )
    }
    // ... remainder unchanged
}
```

> **Note:** The `sdkInt` parameter in `resolveAccent()` is always `0` when `dynamicScheme` is `null`, and `DYNAMIC_ACCENT_MIN_SDK` when it's non-null. The `androidMain` `actual` controls whether a scheme is returned. This is a minor simplification: the `sdkInt` check is redundant because the `actual` function already gates on the real SDK version, but keeping it in `resolveAccent()` doesn't hurt (belt-and-suspenders).

`androidMain/com/transfer/flash/ui/theme/FlashTheme.android.kt`:
```kotlin
package com.transfer.flash.ui.theme

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun flashDynamicColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
```

`jvmMain/com/transfer/flash/ui/theme/FlashTheme.desktop.kt`:
```kotlin
package com.transfer.flash.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal actual fun flashDynamicColorScheme(dark: Boolean): ColorScheme? = null
```

#### Step 1d — `Theme.kt`

**Split into:**

`commonMain/com/transfer/flash/ui/theme/Theme.kt`:
- Keep `DarkColorScheme`, `LightColorScheme` (pure Material3 — no Android dependency)
- Keep `FlashMaterialTheme()` composable — replace the `dynamicColor` block with `flashDynamicColorScheme()`

```kotlin
// commonMain
@Composable
fun FlashMaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            flashDynamicColorScheme(darkTheme) ?: if (darkTheme) DarkColorScheme else LightColorScheme
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
```

`androidMain` and `jvmMain` reuse the same `flashDynamicColorScheme` expect/actual from Step 1c — no additional files needed.

### Step 2 — Move all pure-Compose files to `commonMain`

Move the 14 files that have no Android dependency:

```powershell
# From repo root
$src = "ui/theme/src/main/java/com/transfer/flash/ui"
$dst = "ui/theme/src/commonMain/kotlin/com/transfer/flash/ui"

# Create target directory structure
New-Item -Path "$dst/icons" -ItemType Directory -Force
New-Item -Path "$dst/avatar" -ItemType Directory -Force
New-Item -Path "$dst/theme" -ItemType Directory -Force

# Move icons
git mv "$src/icons/FlashIcons.kt" "$dst/icons/FlashIcons.kt"

# Move avatar
git mv "$src/avatar/FlashAvatar.kt" "$dst/avatar/FlashAvatar.kt"

# Move theme files (14 files — but skip FlashMotion, FlashSounds, FlashTheme, Theme)
$themeFiles = @(
    "Color.kt", "FlashColors.kt", "FlashDimensions.kt", "FlashElevation.kt",
    "FlashFeedback.kt", "FlashInteraction.kt", "FlashShapes.kt",
    "FlashSpacing.kt", "FlashText.kt", "FlashThemeSwatches.kt",
    "FlashTypography.kt", "Type.kt"
)
foreach ($f in $themeFiles) {
    git mv "$src/theme/$f" "$dst/theme/$f"
}
```

### Step 3 — Create `androidMain` directory with the 4 `actual` files

```powershell
New-Item -Path "ui/theme/src/androidMain/kotlin/com/transfer/flash/ui/theme" -ItemType Directory -Force
```

Create the 4 files:
- `FlashMotion.android.kt` — `actual fun isReduceMotionOnPlatform()` using `LocalContext` + `Build` + `Settings` + `AccessibilityManager`
- `FlashSounds.android.kt` — `actual fun rememberFlashSounds()` + full `FlashSoundPlayer` object
- `FlashTheme.android.kt` — `actual fun flashDynamicColorScheme()` using `Build` + `dynamicDarkColorScheme`/`dynamicLightColorScheme` + `LocalContext`

> `Theme.kt`'s Android part is already covered by the `flashDynamicColorScheme` expect/actual in `FlashTheme.android.kt` — no separate file needed.

### Step 4 — Create `jvmMain` directory with desktop stubs

```powershell
New-Item -Path "ui/theme/src/jvmMain/kotlin/com/transfer/flash/ui/theme" -ItemType Directory -Force
```

Create the 2 files:
- `FlashMotion.desktop.kt` — `actual fun isReduceMotionOnPlatform(): Boolean = false`
- `FlashSounds.desktop.kt` — `actual fun rememberFlashSounds(): (FlashSound) -> Unit = { }` (no-op)
- `FlashTheme.desktop.kt` — `actual fun flashDynamicColorScheme(dark: Boolean): ColorScheme? = null`

### Step 5 — Move test files to `commonTest`

```powershell
# List of test files to move
$testFiles = @(
    "FlashDarkPaletteTest.kt", "FlashDynamicAccentTest.kt",
    "FlashFeedbackLogicTest.kt", "FlashSoundsTest.kt", "FlashThemeTokensTest.kt"
)
$testSrc = "ui/theme/src/test/java/com/transfer/flash/ui/theme"
$testDst = "ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme"
New-Item -Path $testDst -ItemType Directory -Force
foreach ($f in $testFiles) {
    git mv "$testSrc/$f" "$testDst/$f"
}
```

> **Note:** The `FlashSoundsTest.kt` tests `FlashSoundPolicy` — a pure JVM object. It moves to `commonTest` unchanged. The test does not reference `FlashSoundPlayer` (the Android-specific part), so it works on all platforms.

### Step 6 — Rewrite `ui/theme/build.gradle.kts`

Replace the current `android.library`-only build file with a KMP build that uses `com.android.kotlin.multiplatform.library` (per AGP 9 KMP migration) + `org.jetbrains.compose` plugin + `kotlin.compose` plugin.

```kotlin
plugins {
    id("com.android.kotlin.multiplatform.library")  // replaces com.android.library
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose")                       // CMP plugin
    `maven-publish`
}

kotlin {
    androidLibrary {
        namespace = "com.transfer.flash.ui.theme"
        compileSdk = 37
        minSdk = 24
        // buildTypes { release { ... } }  — NOT SUPPORTED by androidLibrary DSL
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.compose.ui.graphics)
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }

        jvmMain.dependencies {
            // no desktop-specific dependencies for theme
        }

        commonTest.dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.transfer.flash"
            artifactId = "ui-theme"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
```

> ⚠️ **Important AGP 9 constraint:** `com.android.kotlin.multiplatform.library` does NOT support `buildTypes { release { ... } }` or `publishing { singleVariant("release") { ... } }`. The `proguardFiles` and `consumerProguardFiles` settings are removed. The `publishing` block at the top level uses `components["release"]` per the KMP AGP 9 pattern. Verify this against the actual project's `gradle.properties` AGP version and the Kotlin KMP AGP 9 migration guide.
>
> ⚠️ **`compose.components.resources` is already in `commonMain`** from Phase 17. It stays here.
>
> ⚠️ **`compileOptions` is removed** — under KMP, `sourceCompatibility`/`targetCompatibility` are set via `kotlin { jvmToolchain(11) }` or the `kotlin.jvm.target` in `gradle.properties`.

### Step 7 — Build and verify

```powershell
./gradlew :ui:theme:compileKotlinDesktop --no-configuration-cache
./gradlew :ui:theme:compileDebugKotlin --no-configuration-cache
./gradlew :ui:theme:allTests --no-configuration-cache         # runs commonTest
```

All three must succeed.

### Step 8 — Verify `ui:chat` and `app` still compile

```powershell
./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache
./gradlew :app:assembleDebug --no-configuration-cache
```

---

## Verification gate

| Check | Command | Expected result |
|---|---|---|
| commonMain file count | `Get-ChildItem ui/theme/src/commonMain -Recurse -Filter *.kt | Measure-Object` | 14 files |
| androidMain file count | `Get-ChildItem ui/theme/src/androidMain -Recurse -Filter *.kt | Measure-Object` | 3–4 files |
| jvmMain file count | `Get-ChildItem ui/theme/src/jvmMain -Recurse -Filter *.kt | Measure-Object` | 3 files |
| commonTest file count | `Get-ChildItem ui/theme/src/commonTest -Recurse -Filter *.kt | Measure-Object` | 5 files |
| No `android.*` in commonMain | `Select-String "android\." ui/theme/src/commonMain -Recurse -SimpleMatch` | 0 matches (except `androidx.compose.*`) |
| No `android.*` in jvmMain | `Select-String "android\." ui/theme/src/jvmMain -Recurse` | 0 matches |
| `expect` declarations | `Select-String "^internal expect fun" ui/theme/src -Recurse` | 3 matches (isReduceMotionOnPlatform, rememberFlashSounds, flashDynamicColorScheme) |
| `actual` in androidMain | `Select-String "^internal actual fun" ui/theme/src/androidMain -Recurse` | 3 matches |
| `actual` in jvmMain | `Select-String "^internal actual fun" ui/theme/src/jvmMain -Recurse` | 3 matches |
| Desktop compiles | `./gradlew :ui:theme:compileKotlinDesktop` | `BUILD SUCCESSFUL` |
| Android compiles | `./gradlew :ui:theme:compileDebugKotlin` | `BUILD SUCCESSFUL` |
| Tests pass | `./gradlew :ui:theme:allTests` | `BUILD SUCCESSFUL` (all tests pass) |
| ui:chat compiles | `./gradlew :ui:chat:compileDebugKotlin` | `BUILD SUCCESSFUL` |
| :app compiles | `./gradlew :app:assembleDebug` | `BUILD SUCCESSFUL` |

---

## Do NOT

- Do NOT change `FlashFeedback.kt` — `LocalHapticFeedback` is already available in CMP `commonMain` (CORRECTION 8). The `rememberFlashHaptics()` composable works unchanged.
- Do NOT move `FlashIcons.kt` back to `main/java` — it stays in `commonMain` (moved in Phase 17).
- Do NOT move `composeResources/` — it stays in `commonMain` (Phase 17 established it).
- Do NOT add `org.jetbrains.compose` to `ui:chat` or `app` — those modules are converted in Phases 20 and 21 respectively.
- Do NOT move `FlashAvatar.kt` — it is pure Compose, no Android pins, stays in `commonMain`.
- Do NOT rewrite `FlashSoundSynth` — it is pure math (`PCM_16BIT` synthesis from `sin`, `exp`, `min`), no Android dependency, stays in `commonMain`.
- Do NOT change test imports — the 5 test files are pure JUnit and move to `commonTest` unchanged.
- Do NOT delete `src/main/java/` yet — verify the build works first, then clean up the empty directory.

---

## Rollback

If the build fails, the most likely causes are:

1. **`com.android.kotlin.multiplatform.library` not registered** — add it to the root `build.gradle.kts` plugins block or check Phase 06's plugin setup.
2. **`org.jetbrains.compose` plugin version mismatch** — verify the CMP version is compatible with Kotlin 2.2.10. Per D3 notes, CMP 1.8.0+ requires Kotlin 2.1.0+; 2.2.10 is fine.
3. **`compose.components.resources` not resolving** — ensure the `org.jetbrains.compose` plugin is applied before the dependency block.
4. **`androidLibrary {}` DSL syntax wrong** — verify against the official AGP 9 KMP migration guide. The exact API may differ from the snippet above.
5. **Missing `compose.runtime`/`compose.foundation`** — these come from the `org.jetbrains.compose` plugin, not from the BOM. Ensure the plugin is applied.

If all else fails:
```powershell
git checkout ui/theme/src/main/        # restore original files
git checkout ui/theme/build.gradle.kts  # restore original build file
git rm -rf ui/theme/src/commonMain/     # remove new source sets
git rm -rf ui/theme/src/androidMain/
git rm -rf ui/theme/src/jvmMain/
git rm -rf ui/theme/src/commonTest/
# Phase 18 is not committed — wait for resolution
```

---

## Log entry for `logs/migration.md`

```markdown
## Phase 18 — ui:theme KMP conversion

- **Date:** 2026-08-31
- **Agent/model:** Copilot (autopilot)
- **Commit:** <short sha, or "not committed — blocked">
- **Decisions relied on:** D3=A, D4=A (both proceeded with recommendation)

### Change
Converted `ui:theme` module from single-source-set Android library to KMP with `commonMain`/`androidMain`/`jvmMain`/`commonTest`. Moved 14 pure-Compose files to `commonMain`. Split 4 Android-pinned files (FlashMotion, FlashSounds, FlashTheme, Theme) into `commonMain` + `androidMain` `actual` with `jvmMain` stubs, using 3 `expect`/`actual` pairs: `isReduceMotionOnPlatform()`, `rememberFlashSounds()`, `flashDynamicColorScheme()`. Moved 5 test files to `commonTest`. Replaced `com.android.library` with `com.android.kotlin.multiplatform.library` + `org.jetbrains.compose` plugin.

### Files changed
- **Move:** 14 files to `commonMain/kotlin/`
- **Move:** 5 files to `commonTest/kotlin/`
- **Create:** 3 files in `androidMain/kotlin/` (FlashMotion.android.kt, FlashSounds.android.kt, FlashTheme.android.kt)
- **Create:** 3 files in `jvmMain/kotlin/` (FlashMotion.desktop.kt, FlashSounds.desktop.kt, FlashTheme.desktop.kt)
- **Modify:** `ui/theme/build.gradle.kts` (full rewrite to KMP)
- **Delete:** `src/main/java/` (empty after move)

### Verification
Command run:
```
./gradlew :ui:theme:compileKotlinDesktop :ui:theme:compileDebugKotlin :ui:theme:allTests :ui:chat:compileDebugKotlin :app:assembleDebug --no-configuration-cache
```
Result: PASS / FAIL
<Paste tail of output>

Additional checks:
- `Select-String "android\." ui/theme/src/commonMain -Recurse -SimpleMatch` → 0 matches (androidx.compose.* is fine)
- `Select-String "^internal expect fun" ui/theme/src -Recurse` → 3 matches
- `Select-String "^internal actual fun" ui/theme/src/androidMain -Recurse` → 3 matches
- `Select-String "^internal actual fun" ui/theme/src/jvmMain -Recurse` → 3 matches

### Deviations from the phase file
None.

### Known issues
- Desktop `rememberFlashSounds()` is a no-op stub. Flash sounds on desktop require a `javax.sound.sampled` or equivalent backend — not in migration scope.
- Desktop `isReduceMotionOnPlatform()` always returns `false`. Desktop has no standard reduce-motion query API. If needed, a future phase can add a desktop settings read.
- `flashDynamicColorScheme()` on desktop always returns `null`. Desktop uses the static Flash palette.
- The `com.android.kotlin.multiplatform.library` DSL does not support `buildTypes` or `proguardFiles` — those are removed. Verify with the actual AGP 9 KMP migration guide.

### Next step
Phase 19 — UI platform shims (D7 gated).
```

---

## Appendix: Android-pin detail per file

### `FlashMotion.kt` — Android pins

| Import | Usage | Seam |
|---|---|---|
| `android.content.Context` | `isReduceMotionEnabled(context)` | `actual fun isReduceMotionOnPlatform()` |
| `android.os.Build` | `Build.VERSION.SDK_INT >= TIRAMISU` | Moves to `actual` |
| `android.provider.Settings` | `Settings.Global.getFloat(... ANIMATOR_DURATION_SCALE)` | Moves to `actual` |
| `android.view.accessibility.AccessibilityManager` | `getSystemService` + `isReduceMotionEnabledCompat()` | Moves to `actual` |
| `LocalContext` | `rememberFlashMotion()` | `expect`/`actual` |

### `FlashSounds.kt` — Android pins

| Import | Usage | Seam |
|---|---|---|
| `android.content.Context` | `FlashSoundPlayer.play(context, ...)` | `actual fun rememberFlashSounds()` |
| `android.media.AudioTrack` | PCM playback | Moves to `actual` |
| `android.media.AudioManager` | Ringer mode query | Moves to `actual` |
| `android.media.AudioAttributes` | Track configuration | Moves to `actual` |
| `android.media.AudioFormat` | PCM format | Moves to `actual` |
| `android.app.NotificationManager` | DND filter query | Moves to `actual` |
| `LocalContext` | `rememberFlashSounds()` | `expect`/`actual` |

### `FlashTheme.kt` — Android pins

| Import | Usage | Seam |
|---|---|---|
| `android.os.Build` | `Build.VERSION.SDK_INT >= S` | `actual fun flashDynamicColorScheme()` |
| `dynamicDarkColorScheme` | Monet scheme | `actual` |
| `dynamicLightColorScheme` | Monet scheme | `actual` |
| `LocalContext` | `LocalContext.current` | `actual` |

### `Theme.kt` — Android pins

| Import | Usage | Seam |
|---|---|---|
| `android.os.Build` | `Build.VERSION.SDK_INT >= S` | `flashDynamicColorScheme()` (shared) |
| `dynamicDarkColorScheme`/`dynamicLightColorScheme` | Monet scheme | `flashDynamicColorScheme()` (shared) |
| `LocalContext` | `LocalContext.current` | `flashDynamicColorScheme()` (shared) |