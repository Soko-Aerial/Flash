# PHASE-20 — `ui:chat` → KMP module

**Blocked by:** PHASE-17 (ui:resources), PHASE-18 (ui:theme KMP), PHASE-19 (ui:platform-shims)
**Gated by:** D7 (platform shims: agent may proceed with recommendation per DECISIONS.md §instructions)
**Risk:** HIGH — 46 production files, 31 test files, the largest single module migration
**Duration estimate:** 4–6 hours (file moves dominate; build + fix cycles)

---

## What this phase is for

Convert `ui:chat` from an `android.library` module to a Kotlin Multiplatform module using `com.android.kotlin.multiplatform.library` (AGP 9 KMP pattern). All 46 production files move to `commonMain` (or `androidMain`/`desktopMain` where platform-specific code cannot be shimmed). All 31 test files move to `commonTest`. The module becomes buildable for both Android and desktop JVM targets.

This phase does **not** change any source code logic. It:
- Moves files into KMP source sets
- Replaces Android-only imports with shim calls (already done by PHASE-19)
- Rewrites `build.gradle.kts` to the KMP pattern
- Updates `gradle/libs.versions.toml` with new plugin aliases
- Amends migration conventions where needed

---

## Preconditions

1. [ ] PHASE-17 is committed and `ui:resources` (or equivalent icon/string resources) exist in KMP form.
2. [ ] PHASE-18 is committed and `ui:theme` is a KMP module with `commonMain` + `androidMain` + `desktopMain` source sets.
3. [ ] PHASE-19 is committed and `ui:platform-shims` exists with all 8 shims (`FlashBackHandler`, `FlashTransientMessage`, `FlashFilePicker`, `FlashPermission`, `FlashDecodeImageBitmap`, `FlashAudioPlayer`, `FlashVoiceRecorder`, `FlashClipboard`).
4. [ ] D7 is answered **or** the agent has proceeded with the recommended approach (Snackbar for Toast, FileKit for file picking, hand-rolled permission shim for desktop).
5. [ ] The 7 Android-pinned files in `ui:chat` have already been edited by PHASE-19 to replace Android imports with shim calls. They compile cleanly against `ui:platform-shims` as an Android library.
6. [ ] Working tree is clean (no uncommitted changes in `ui:chat/`, `ui:theme/`, `ui:platform-shims/`, `settings.gradle.kts`, `gradle/libs.versions.toml`).
7. [ ] `./gradlew :ui:chat:assembleDebug` succeeds (baseline pre-migration build).
8. [ ] `./gradlew :ui:chat:testDebugUnitTest` succeeds (baseline pre-migration tests).

---

## Verified starting state

### File inventory (46 production files, 31 test files)

All source roots are `src/main/java` and `src/test/java` (not `src/main/kotlin`).

```text
ui/chat/src/main/java/com/transfer/flash/ui/chat/
├── FlashAppState.kt
├── FlashAudioPlayer.kt               ← pinned (7 android imports → shim after PHASE-19)
├── FlashAudioRecording.kt
├── FlashAudioPlayerBar.kt
├── FlashAudioRecordingList.kt
├── FlashAudioRecordings.kt
├── FlashAudioWaveform.kt
├── FlashChatHeader.kt
├── FlashChatList.kt
├── FlashChatScreen.kt
├── FlashChatViewModel.kt
├── FlashComposer.kt
├── FlashConnectionStatus.kt
├── FlashContactPicker.kt
├── FlashContactPickerDialog.kt
├── FlashConversationScreen.kt        ← pinned (10 android imports → 5 shims after PHASE-19)
├── FlashDebugScreen.kt
├── FlashDeviceCard.kt
├── FlashDeviceDetails.kt
├── FlashDeviceList.kt
├── FlashDevicePairing.kt
├── FlashEmojiPicker.kt
├── FlashEncryptionBadge.kt
├── FlashErrorBanner.kt
├── FlashHelpScreen.kt
├── FlashImageGrid.kt                 ← pinned (BitmapFactory, Uri → shim after PHASE-19)
├── FlashImageMessage.kt
├── FlashMediaViewer.kt               ← pinned (BitmapFactory, Uri, BackHandler → shims after PHASE-19)
├── FlashMessage.kt
├── FlashMessageActions.kt
├── FlashMessageContextMenu.kt        ← pinned (BackHandler → shim after PHASE-19)
├── FlashMessageInput.kt
├── FlashMessageStatus.kt
├── FlashOnboardingScreen.kt
├── FlashPairingFlow.kt              ← pinned (BackHandler → shim after PHASE-19)
├── FlashPairingViewModel.kt
├── FlashPeerList.kt
├── FlashReplyPreview.kt
├── FlashSearchBar.kt
├── FlashSearchResults.kt
├── FlashTransferCard.kt
├── FlashTransferDetails.kt
├── FlashTransferList.kt
├── FlashVerificationCode.kt
├── FlashVoiceMessageCard.kt
├── FlashVoiceRecording.kt            ← already common-safe (0 android imports)
└── FlashVoiceRecorder.kt             ← pinned (5 android imports → shim after PHASE-19)
```

**Sub-package files (6 files, all common-safe):**

```text
ui/chat/src/main/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLayouts.kt
ui/chat/src/main/java/com/transfer/flash/ui/navigation/FlashNavigation.kt
ui/chat/src/main/java/com/transfer/flash/ui/nearby/FlashNearbyScreen.kt
ui/chat/src/main/java/com/transfer/flash/ui/settings/FlashSettingsScreen.kt
ui/chat/src/main/java/com/transfer/flash/ui/shell/FlashBottomNav.kt
ui/chat/src/main/java/com/transfer/flash/ui/transfers/FlashTransfersScreen.kt
```

**Test files (31 files, all pure JUnit — no Android imports):**

```text
ui/chat/src/test/java/com/transfer/flash/ui/chat/
├── FlashAppStateTest.kt
├── FlashAudioRecordingTest.kt
├── FlashChatViewModelTest.kt
├── FlashComposerTest.kt
├── FlashConnectionStatusTest.kt
├── FlashContactPickerTest.kt
├── FlashDebugScreenTest.kt
├── FlashDeviceCardTest.kt
├── FlashDeviceDetailsTest.kt
├── FlashDeviceListTest.kt
├── FlashDevicePairingTest.kt
├── FlashEmojiPickerTest.kt
├── FlashEncryptionBadgeTest.kt
├── FlashErrorBannerTest.kt
├── FlashHelpScreenTest.kt
├── FlashImageMessageTest.kt
├── FlashMessageActionsTest.kt
├── FlashMessageContextMenuTest.kt
├── FlashMessageInputTest.kt
├── FlashMessageStatusTest.kt
├── FlashMessageTest.kt
├── FlashOnboardingScreenTest.kt
├── FlashPairingViewModelTest.kt
├── FlashPeerListTest.kt
├── FlashReplyPreviewTest.kt
├── FlashSearchBarTest.kt
├── FlashSearchResultsTest.kt
├── FlashTransferCardTest.kt
├── FlashTransferDetailsTest.kt
├── FlashTransferListTest.kt
├── FlashVerificationCodeTest.kt
```

Plus sub-package test files (6 files, all common-safe):

```text
ui/chat/src/test/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLogicTest.kt
ui/chat/src/test/java/com/transfer/flash/ui/navigation/FlashNavigationTest.kt
ui/chat/src/test/java/com/transfer/flash/ui/nearby/FlashNearbyTest.kt
ui/chat/src/test/java/com/transfer/flash/ui/settings/FlashSettingsTest.kt
ui/chat/src/test/java/com/transfer/flash/ui/shell/FlashBottomNavTest.kt
ui/chat/src/test/java/com/transfer/flash/ui/transfers/FlashTransfersTest.kt
```

**Total: 46 production files + 37 test files (31 + 6 sub-package).**

### Current build file (`ui/chat/build.gradle.kts`, 71 lines)

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.ui.chat"
    compileSdk = 37

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

    buildFeatures {
        compose = true
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
            groupId = "com.transfer.flash"
            artifactId = "ui-chat"
            version = "1.0.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:messaging"))
    implementation(project(":ui:theme"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
}
```

### Current `gradle/libs.versions.toml` (plugins section)

```toml
[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

**Missing:** `kotlin-multiplatform`, `org.jetbrains.compose`, `android-kotlin-multiplatform-library`.

### Current CONVENTIONS.md R5 (lines 80–97)

```markdown
## R5 — Source-set naming is fixed

Use these exact names. Do not invent variants.

| Source set | Contains |
|---|---|
| `commonMain` | Pure Kotlin. stdlib + coroutines + kotlinx only. No `java.*`. |
| `jvmAndAndroidMain` | JVM-only code shared by Android and desktop: `java.*`, `javax.*`. |
| `androidMain` | `android.*`, `androidx.*` |
| `jvmMain` | Desktop-only JVM code |
| `commonTest`, `jvmAndAndroidTest`, `androidUnitTest`, `jvmTest` | Test mirrors |

The desktop target is declared as plain `jvm()`, giving `jvmMain`/`jvmTest`.
Do **not** use `jvm("desktop")` — it would give `desktopMain` and every path in
these phase files would be wrong.
```

---

## The change

### Step 1 — Add required plugin aliases to `gradle/libs.versions.toml`

Add these entries to the `[plugins]` section in `gradle/libs.versions.toml`:

```toml
[plugins]
# ... existing entries ...
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
org-jetbrains-compose = { id = "org.jetbrains.compose", version = "1.12.0" }
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
```

> ⚠️ **Version verification:** Compose Multiplatform 1.12.0 is compatible with Kotlin 2.2.10 (per jetbrains.com CMP compatibility page). Verify the latest CMP version compatible with Kotlin 2.2.10 before committing — if a newer CMP release exists, use that instead. The `org.jetbrains.compose` plugin ID is the correct one for CMP 1.12+ (the older `org.jetbrains.compose` ID was used for CMP 1.x; double-check the exact plugin ID at the JetBrains docs).

> ⚠️ **R10 compliance:** No version bumps to existing entries. Only new aliases are added.

### Step 2 — Amend CONVENTIONS.md R5 with UI-track exception

The UI track (`ui:theme`, `ui:platform-shims`, `ui:chat`) uses the Compose Multiplatform plugin (`org.jetbrains.compose`) which requires `jvm("desktop")`/`desktopMain` for the desktop target. This is an intentional deviation from R5's `jvm()`/`jvmMain` rule.

Add this as R5.1 after the existing R5 text:

```markdown
## R5.1 — UI track exception: `jvm("desktop")`/`desktopMain`

The theme, platform-shims, and chat UI modules (`ui:theme`, `ui:platform-shims`,
`ui:chat`) target the Compose Multiplatform desktop stack. They use:

| Source set | Contains |
|---|---|
| `desktopMain` | Desktop JVM code with CMP desktop APIs. Source files live under `src/desktopMain/kotlin/`. |
| `desktopTest` | Desktop JVM tests. |

The desktop target is declared as `jvm("desktop")` in these modules, producing
`desktopMain`/`desktopTest` source sets. The `compileKotlinDesktop` Gradle task
verifies desktop compilation.

Core modules (`core:*`) and non-UI modules use plain `jvm()`/`jvmMain` per R5.
```

### Step 3 — Move production files to KMP source sets

All 46 production files move from `src/main/java` to `src/commonMain/kotlin`. The 7 Android-pinned files (already shim-migrated by PHASE-19) move alongside the 39 already-common-safe files.

```powershell
# Create commonMain source root
$commonSrc = "ui/chat/src/commonMain/kotlin/com/transfer/flash/ui"
New-Item -Path "$commonSrc/chat" -ItemType Directory -Force
New-Item -Path "$commonSrc/adaptive" -ItemType Directory -Force
New-Item -Path "$commonSrc/navigation" -ItemType Directory -Force
New-Item -Path "$commonSrc/nearby" -ItemType Directory -Force
New-Item -Path "$commonSrc/settings" -ItemType Directory -Force
New-Item -Path "$commonSrc/shell" -ItemType Directory -Force
New-Item -Path "$commonSrc/transfers" -ItemType Directory -Force

# Move chat/ root files (40 files)
$chatSrc = "ui/chat/src/main/java/com/transfer/flash/ui/chat"
Get-ChildItem "$chatSrc/*.kt" | ForEach-Object {
    git mv $_.FullName "$commonSrc/chat/$($_.Name)"
}

# Move sub-package files (6 files)
$packages = @("adaptive", "navigation", "nearby", "settings", "shell", "transfers")
foreach ($pkg in $packages) {
    Get-ChildItem "ui/chat/src/main/java/com/transfer/flash/ui/$pkg/*.kt" | ForEach-Object {
        git mv $_.FullName "$commonSrc/$pkg/$($_.Name)"
    }
}

# Remove empty android source directories
Remove-Item -Recurse -Force "ui/chat/src/main/java/com/transfer/flash/ui/chat" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "ui/chat/src/main/java/com/transfer/flash/ui" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "ui/chat/src/main/java" -ErrorAction SilentlyContinue
```

> **Note:** `git mv` preserves file history. The `Remove-Item` commands clean up the now-empty parent directories. The `-ErrorAction SilentlyContinue` handles cases where Android Studio may have locked the directories.

### Step 4 — Move test files to `commonTest`

All 37 test files (31 chat root + 6 sub-package) move to `commonTest`:

```powershell
$testCommon = "ui/chat/src/commonTest/kotlin/com/transfer/flash/ui"
New-Item -Path "$testCommon/chat" -ItemType Directory -Force
New-Item -Path "$testCommon/adaptive" -ItemType Directory -Force
New-Item -Path "$testCommon/navigation" -ItemType Directory -Force
New-Item -Path "$testCommon/nearby" -ItemType Directory -Force
New-Item -Path "$testCommon/settings" -ItemType Directory -Force
New-Item -Path "$testCommon/shell" -ItemType Directory -Force
New-Item -Path "$testCommon/transfers" -ItemType Directory -Force

# Move chat test files (31 files)
$testSrc = "ui/chat/src/test/java/com/transfer/flash/ui/chat"
Get-ChildItem "$testSrc/*.kt" | ForEach-Object {
    git mv $_.FullName "$testCommon/chat/$($_.Name)"
}

# Move sub-package test files (6 files)
foreach ($pkg in $packages) {
    Get-ChildItem "ui/chat/src/test/java/com/transfer/flash/ui/$pkg/*.kt" | ForEach-Object {
        git mv $_.FullName "$testCommon/$pkg/$($_.Name)"
    }
}

# Clean up empty directories
Remove-Item -Recurse -Force "ui/chat/src/test/java/com/transfer/flash/ui" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "ui/chat/src/test/java" -ErrorAction SilentlyContinue
```

### Step 5 — Rewrite `ui/chat/build.gradle.kts`

Replace the entire 71-line `android.library` build file with a KMP build using `com.android.kotlin.multiplatform.library` + `org.jetbrains.compose` + `kotlin.compose`.

```kotlin
plugins {
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose")
    `maven-publish`
}

kotlin {
    androidLibrary {
        namespace = "com.transfer.flash.ui.chat"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // Module dependencies
            implementation(project(":core:common"))
            implementation(project(":core:messaging"))
            implementation(project(":ui:theme"))
            implementation(project(":ui:platform-shims"))

            // Compose Multiplatform (via org.jetbrains.compose plugin)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            // Explicit Compose dependencies (available in CMP commonMain)
            implementation(libs.androidx.compose.ui.graphics)
            implementation(libs.androidx.compose.ui.tooling.preview)
        }

        androidMain.dependencies {
            // Android-specific Compose
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        desktopMain.dependencies {
            // No desktop-specific dependencies for chat in this phase
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
            artifactId = "ui-chat"
            version = "1.0.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
```

> ⚠️ **Key differences from the current file:**
> - `com.android.kotlin.multiplatform.library` replaces `android.library`
> - `android {}` block is replaced by `kotlin { androidLibrary { ... } }` — the `androidLibrary` DSL does NOT support `buildTypes`, `publishing.singleVariant`, `consumerProguardFiles`, `compileOptions`, or `buildFeatures.compose`
> - `org.jetbrains.compose` plugin provides `compose.runtime`, `compose.foundation`, `compose.material3`, `compose.ui` accessors
> - `compose.ui` in commonMain replaces `libs.androidx.compose.ui` and `"androidx.compose.foundation:foundation"`
> - `libs.androidx.compose.ui.graphics` stays in commonMain (CMP provides it)
> - `libs.androidx.compose.ui.tooling.preview` stays in commonMain (CMP provides `@Preview` in common)
> - `libs.androidx.activity.compose`, `libs.androidx.core.ktx`, `libs.androidx.lifecycle.runtime.ktx` move to `androidMain` only
> - `libs.androidx.compose.bom` is removed — under CMP, the BOM is not needed for desktop targets; the `org.jetbrains.compose` plugin handles version alignment. If the BOM is still desired for Android version alignment, it can be added to `androidMain.dependencies`.
> - `jvm("desktop")` declares the desktop target, producing `desktopMain`/`desktopTest` source sets
> - `compileOptions` is removed — use `kotlin { jvmToolchain(11) }` or `gradle.properties` for JVM target

### Step 6 — Verify import changes (post-PHASE-19)

After PHASE-19 has been executed, the 7 pinned files should have these import changes. **If PHASE-19 has NOT been executed, do this step now — the 7 files must be edited to replace Android imports with shim calls before the module can compile as KMP.**

**Change summary per file:**

| File | Was | Now |
|---|---|---|
| `FlashConversationScreen.kt` | `import android.widget.Toast` (×13) | `FlashTransientMessage.show(context, ...)` or SnackbarHostState |
| | `import androidx.activity.compose.BackHandler` (×3) | `import com.transfer.flash.ui.shims.BackHandler` (renamed to `FlashBackHandler`) |
| | `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument)` | `FlashFilePicker.pickFile(...)` (via FileKit) |
| | `ContextCompat.checkSelfPermission(...)` | `FlashPermission.ensure(permission)` |
| | `getSystemService(CLIPBOARD_SERVICE)` | `FlashClipboard.copy(text)`, `FlashClipboard.paste()` |
| | `resolveFileMetadata(context, uri)` with `contentResolver.query` | `FlashFilePicker.resolveMetadata(uri)` |
| `FlashAudioPlayer.kt` | `import android.media.MediaPlayer` | `import com.transfer.flash.ui.shims.FlashAudioPlayer` (expect class) |
| | `import android.content.Context` | Removed — player initialized via `rememberFlashAudioPlayer(uri)` |
| `FlashVoiceRecorder.kt` | `import android.media.MediaRecorder` | `import com.transfer.flash.ui.shims.FlashVoiceRecorder` (expect class) |
| | `import android.content.Context` | Removed — recorder initialized via `rememberFlashVoiceRecorder()` |
| `FlashImageGrid.kt` | `import android.graphics.BitmapFactory` | `import com.transfer.flash.ui.shims.rememberDecodeImageBitmap` |
| | `import android.net.Uri` | Removed — shim takes `String` (file path) |
| | `contentResolver.openInputStream(uri)` | `rememberDecodeImageBitmap(path, ...)` |
| `FlashMediaViewer.kt` | `import android.graphics.BitmapFactory` | `import com.transfer.flash.ui.shims.rememberDecodeImageBitmap` |
| | `import android.net.Uri` | Removed — shim takes `String` |
| | `import androidx.activity.compose.BackHandler` | `import com.transfer.flash.ui.shims.FlashBackHandler` |
| | `contentResolver.openInputStream(uri)` | `rememberDecodeImageBitmap(path, ...)` |
| `FlashMessageContextMenu.kt` | `import androidx.activity.compose.BackHandler` | `import com.transfer.flash.ui.shims.FlashBackHandler` |
| `FlashPairingFlow.kt` | `import androidx.activity.compose.BackHandler` | `import com.transfer.flash.ui.shims.FlashBackHandler` |

**For the remaining 39 production files:** No import changes needed. They are already common-safe (no Android imports, no `java.*` imports, no `androidx.activity.compose` imports).

> **Note on `LocalContext.current`:** `FlashConversationScreen.kt` uses `LocalContext.current` (line 109). Under CMP, `LocalContext.current` is available in `commonMain` — CMP provides `androidx.compose.ui.platform.LocalContext` in common. However, it returns `Nothing?` on non-Android targets. For the desktop path, shim functions should not rely on `LocalContext`. After PHASE-19, the shim calls should eliminate the need for `LocalContext` in `FlashConversationScreen.kt` — the `FileKit` picker, `FlashPermission.ensure()`, `FlashClipboard`, and `FlashTransientMessage` all handle their own platform context. If `LocalContext` remains in `FlashConversationScreen.kt`, verify it's only used for Android-specific paths that are already behind shim calls.

### Step 7 — Build and verify

```powershell
# 1. Desktop compilation
./gradlew :ui:chat:compileKotlinDesktop --no-configuration-cache

# 2. Android compilation
./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache

# 3. All tests
./gradlew :ui:chat:allTests --no-configuration-cache

# 4. Verify app still compiles
./gradlew :app:assembleDebug --no-configuration-cache
```

---

## Verification gate

| Check | Command | Expected result |
|---|---|---|
| commonMain file count | `Get-ChildItem ui/chat/src/commonMain -Recurse -Filter *.kt \| Measure-Object` | 46 files |
| commonTest file count | `Get-ChildItem ui/chat/src/commonTest -Recurse -Filter *.kt \| Measure-Object` | 37 files |
| androidMain file count | `Get-ChildItem ui/chat/src/androidMain -Recurse -Filter *.kt \| Measure-Object` | 0 files (all common) |
| desktopMain file count | `Get-ChildItem ui/chat/src/desktopMain -Recurse -Filter *.kt \| Measure-Object` | 0 files (all common) |
| No `android.*` in commonMain | `Select-String "android\." ui/chat/src/commonMain -Recurse -SimpleMatch` | 0 matches (except `androidx.compose.*`) |
| No `android.*` in desktopMain | `Select-String "android\." ui/chat/src/desktopMain -Recurse` | 0 matches (directory is empty) |
| No `java.*` in commonMain | `Select-String "^import java\." ui/chat/src/commonMain -Recurse` | 0 matches |
| No `androidx.activity.compose.BackHandler` | `Select-String "androidx.activity.compose.BackHandler" ui/chat/src/commonMain -Recurse` | 0 matches |
| No `Toast` references | `Select-String "Toast" ui/chat/src/commonMain -Recurse` | 0 matches (if D7a chose Snackbar) |
| No `BitmapFactory` | `Select-String "BitmapFactory" ui/chat/src/commonMain -Recurse` | 0 matches |
| No `MediaPlayer` | `Select-String "MediaPlayer" ui/chat/src/commonMain -Recurse` | 0 matches |
| No `MediaRecorder` | `Select-String "MediaRecorder" ui/chat/src/commonMain -Recurse` | 0 matches |
| No `CLIPBOARD_SERVICE` | `Select-String "CLIPBOARD_SERVICE" ui/chat/src/commonMain -Recurse` | 0 matches |
| Desktop compiles | `./gradlew :ui:chat:compileKotlinDesktop --no-configuration-cache` | `BUILD SUCCESSFUL` |
| Android compiles | `./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache` | `BUILD SUCCESSFUL` |
| Tests pass | `./gradlew :ui:chat:allTests --no-configuration-cache` | `BUILD SUCCESSFUL` (all tests pass) |
| No `Context` in commonMain | `Select-String "import android.content.Context" ui/chat/src/commonMain -Recurse` | 0 matches |
| `ui:platform-shims` dependency | `Select-String "platform-shims" ui/chat/build.gradle.kts` | Match found in commonMain |

---

## Do NOT

- **Do NOT** change the `package` declarations of any source file. The package is `com.transfer.flash.ui.chat` (or sub-package) — only the file location changes.
- **Do NOT** use `testDebugUnitTest` in the verification gate. Under KMP, the task is `allTests` (R3). See CONVENTIONS.md R3 for the full explanation.
- **Do NOT** version-bump any existing dependency in `libs.versions.toml` (R10). Only add new plugin aliases.
- **Do NOT** change `compose.ui` to `libs.androidx.compose.ui` — the CMP `compose.ui` accessor is the correct cross-platform dependency.
- **Do NOT** add the Compose BOM to `commonMain` — it's Android-specific. If needed, add it to `androidMain.dependencies` only.
- **Do NOT** delete `ui/chat/src/main/java/` until all file moves are confirmed successful. Keep the old directory until the build passes.
- **Do NOT** add `ui/chat/src/androidMain` or `ui/chat/src/desktopMain` directories unless a file cannot be made common-safe. All 46 files should compile in `commonMain` after PHASE-19 shim migration.
- **Do NOT** change `ui/theme/build.gradle.kts` or `ui/platform-shims/build.gradle.kts` — those are PHASE-18 and PHASE-19's responsibility.
- **Do NOT** submit a commit that includes changes to `ui:chat` source files beyond file moves and import replacements. No logic changes, no refactoring.
- **Do NOT** use `compose.components.resources` in `ui:chat` — it's already in `ui:theme` commonMain and transitively available. If `ui:chat` needs it directly, add it explicitly.

---

## Rollback

If the KMP migration causes build failures that cannot be quickly resolved:

1. **Revert `build.gradle.kts`:** `git checkout dev -- ui/chat/build.gradle.kts`
2. **Revert file moves:**
   ```powershell
   # Move files back to main/java
   $common = "ui/chat/src/commonMain/kotlin/com/transfer/flash/ui"
   $main = "ui/chat/src/main/java/com/transfer/flash/ui"
   New-Item -Path "$main/chat" -ItemType Directory -Force
   Get-ChildItem "$common/chat/*.kt" | ForEach-Object {
       git mv $_.FullName "$main/chat/$($_.Name)"
   }
   foreach ($pkg in @("adaptive", "navigation", "nearby", "settings", "shell", "transfers")) {
       New-Item -Path "$main/$pkg" -ItemType Directory -Force
       Get-ChildItem "$common/$pkg/*.kt" | ForEach-Object {
           git mv $_.FullName "$main/$pkg/$($_.Name)"
       }
   }
   # Move test files back
   $testCommon = "ui/chat/src/commonTest/kotlin/com/transfer/flash/ui"
   $testMain = "ui/chat/src/test/java/com/transfer/flash/ui"
   New-Item -Path "$testMain/chat" -ItemType Directory -Force
   Get-ChildItem "$testCommon/chat/*.kt" | ForEach-Object {
       git mv $_.FullName "$testMain/chat/$($_.Name)"
   }
   foreach ($pkg in @("adaptive", "navigation", "nearby", "settings", "shell", "transfers")) {
       New-Item -Path "$testMain/$pkg" -ItemType Directory -Force
       Get-ChildItem "$testCommon/$pkg/*.kt" | ForEach-Object {
           git mv $_.FullName "$testMain/$pkg/$($_.Name)"
       }
   }
   ```
3. **Revert toml changes:** `git checkout dev -- gradle/libs.versions.toml`
4. **Revert CONVENTIONS.md:** `git checkout dev -- docs/migration/CONVENTIONS.md`
5. **Verify pre-migration build:** `./gradlew :ui:chat:assembleDebug --no-configuration-cache`

---

## Log entry for `logs/migration.md`

```markdown
## 2026-08-31 — PHASE-20: ui:chat → KMP

**Agent:** Copilot (autonomous)
**Expected commit:** `<commit-hash-after-phase>`

**Decisions relied on:** D7 (platform shims — proceeded with recommendation);
PHASE-19 (shim APIs); PHASE-18 (KMP build pattern); R5.1 amendment (desktopMain
for UI track)

**Change:**
- Added `kotlin-multiplatform`, `org-jetbrains-compose`, and
  `android-kotlin-multiplatform-library` plugin aliases to `gradle/libs.versions.toml`
- Amended CONVENTIONS.md R5 with R5.1 (UI-track exception: `jvm("desktop")`/`desktopMain`)
- Moved all 46 production files from `src/main/java/` → `src/commonMain/kotlin/`
- Moved all 37 test files from `src/test/java/` → `src/commonTest/kotlin/`
- Rewrote `ui/chat/build.gradle.kts` from `android.library` to
  `com.android.kotlin.multiplatform.library` + `org.jetbrains.compose`
- Replaced Android-only dependencies with CMP equivalents in commonMain;
  moved activity-compose, core-ktx, lifecycle-runtime-ktx to androidMain
- Removed `android {}` block (buildTypes, compileOptions, singleVariant, etc.)
  — replaced by `kotlin { androidLibrary { ... } }`
- Removed Compose BOM from commonMain (Android-only concept)

**Files changed:**
- `gradle/libs.versions.toml` — 3 new plugin aliases
- `docs/migration/CONVENTIONS.md` — R5.1 amendment
- `ui/chat/build.gradle.kts` — full rewrite
- `ui/chat/src/` — 83 files relocated (46 prod + 37 test)

**Verification:**
- [ ] `compileKotlinDesktop` succeeds
- [ ] `compileDebugKotlin` succeeds
- [ ] `allTests` passes (37 tests)
- [ ] `:app:assembleDebug` succeeds
- [ ] No `android.*` imports in commonMain
- [ ] No `java.*` imports in commonMain
- [ ] No `Toast`, `BitmapFactory`, `MediaPlayer`, `MediaRecorder`, `CLIPBOARD_SERVICE`, `BackHandler` in commonMain

**Deviations from CONVENTIONS.md:**
- R5 overridden by R5.1 for UI track modules: `desktopMain` instead of `jvmMain`

**Known issues:**
- `ui:chat` no longer has `compileDebugUnitTest` — uses `allTests` per R3
- `ui:theme` (PHASE-18) has `lifecycle-runtime-ktx` in commonMain — this is a
  latent bug; PHASE-20 correctly places it in androidMain. A future cleanup
  phase should move it in `ui:theme` as well.
- PHASE-18's verification gate references `compileKotlinDesktop` while using
  `jvmMain` naming — this is internally inconsistent. The UI track now uses
  `jvm("desktop")`/`desktopMain` per R5.1, so `compileKotlinDesktop` is correct.

**Next step:** PHASE-21 — desktop app shell (`:app` KMP conversion)
```