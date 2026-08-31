# Phase 18 — `ui:theme` KMP conversion

**Blocked by:** Phase 17 (icon resources in CMP `composeResources`) — the `composeResources/` directory and `compose.components.resources` dependency must be in place before the module can compile for `commonMain`.

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