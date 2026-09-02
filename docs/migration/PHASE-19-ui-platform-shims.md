# PHASE-19: UI Platform Shims

**Blocked by:** PHASE-18 (ui:theme KMP)
**Gated by:** D7 (UI platform shims: build or adopt?) — **DO NOT EXECUTE until D7 answered**
**Risk:** Significant — 8 distinct shims touch 7 files; Desktop no-op stubs must be verified for correctness
**Migration type:** `expect`/`actual` + library adoption (FileKit, conditional)

---

## What this phase is actually for

Create a `platform-shims` module under `ui/` that provides `expect`/`actual` declarations for Android-pinned APIs in `ui:chat`, so that the 7 files that currently import Android-only classes can compile against `commonMain`. The `ui:chat` module itself stays an Android module in this phase — it is converted to KMP in PHASE-20. This phase creates the shim source set that PHASE-20 depends on.

**Phase 19 does NOT convert `ui:chat` to KMP.** However, it DOES migrate the 7 Android-pinned `ui:chat` files off `android.*` imports onto the new shims (Steps 3–10), because `ui:platform-shims` has `androidMain` actuals that keep behaviour identical on Android. This is a deliberate two-step ordering: by the time Phase 20 flips `ui:chat` to a KMP module, its sources are already platform-neutral and only the Gradle rewrite remains. Each shim step below marks whether it is an `expect`/`actual` (new shim) or a pure-common replacement.

---

## Preconditions — do not start until all are true

1. [ ] PHASE-18 is committed and `ui:theme` is a KMP module with `commonMain` + `androidMain` + `desktopMain` source sets.
2. [ ] `gradle/libs.versions.toml` has the `org.jetbrains.compose` plugin ID usable (Phase 18 established this pattern).
3. [ ] D7 is answered in `docs/migration/DECISIONS.md` — the `ANSWER` field for D7 is no longer `_pending_`.
4. [ ] The decision for D7a (Toast→Snackbar vs. `expect fun showTransientMessage`) is explicitly recorded.
5. [ ] The decision for D7b (FileKit vs. hand-rolled `expect`/`actual`) is explicitly recorded.
6. [ ] The decision for D7c (`rememberPermissionHelper` composable expect/actual) is confirmed.
7. [ ] `docs/migration/CONVENTIONS.md` R10–R11 are followed (no version bumps unless justified; exclude `media-downloader-main/`, `build/`, `docs/` from greps).

---

## Verified starting state

**Must be verified just before starting** — do not trust stale data.

### File inventory: `ui:platform-shims` module (to create)

| Path | Role |
|---|---|
| `ui/platform-shims/build.gradle.kts` | KMP module build (android + desktop) |
| `ui/platform-shims/src/commonMain/.../shims/` | `expect` declarations |
| `ui/platform-shims/src/androidMain/.../shims/` | Android `actual` implementations |
| `ui/platform-shims/src/desktopMain/.../shims/` | Desktop `actual` implementations (no-ops or Desktop equivalents) |

Base package: `com.transfer.flash.ui.shims`

### Android-pinned files in `ui:chat` (7 of 46 production files)

| File | Android imports | Seams needed |
|---|---|---|
| `FlashConversationScreen.kt` | 12 imports (Toast ×13, BackHandler ×3, file picker, clipboard, resolveFileMetadata, mic permission) | 5 shims |
| `FlashAudioPlayer.kt` | 5 imports (Context, MediaPlayer, Uri, Build, Log) | `expect class FlashAudioPlayer` |
| `FlashVoiceRecorder.kt` | 5 imports (Context, MediaRecorder, Uri, Build, Log) | `expect class FlashVoiceRecorder` |
| `FlashImageGrid.kt` | 2 imports (BitmapFactory, Uri) | `rememberDecodeImageBitmap` |
| `FlashMediaViewer.kt` | 3 imports (BitmapFactory, Uri, BackHandler) | `rememberDecodeImageBitmap` + BackHandler |
| `FlashMessageContextMenu.kt` | 1 import (BackHandler) | BackHandler |
| `FlashPairingFlow.kt` | 1 import (BackHandler) | BackHandler |

### ui:chat test files (31 files)

All pure logic tests — no Android imports. They move to `commonTest` in PHASE-20. No changes needed here.

### Existing `ui:chat/build.gradle.kts` (71 lines)

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("maven-publish")
}

android {
    namespace = "com.transfer.flash.ui.chat"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") {
        withSourcesJar()
    }}
    buildTypes.release { isMinifyEnabled = false; proguardFiles(...) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:messaging"))
    implementation(project(":ui:theme"))
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-geometry")
    implementation("androidx.core:core-ktx")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx")
    // Tests
    testImplementation(libs.junit)
}
```

### D7 decision text (from `docs/migration/DECISIONS.md`)

```markdown
## D7 — UI platform shims: build or adopt?

Three capabilities in `ui:chat` have no common equivalent.

**a) `Toast` — 13 call sites, all in `FlashConversationScreen.kt`.**
Recommended: replace with Material 3 `SnackbarHost` + `SnackbarHostState`, which is
already available in common and already used elsewhere in the app's Scaffolds. This
is a genuine behaviour change on Android (snackbar, not toast) and needs sign-off.
Alternative: an `expect fun showTransientMessage(String)` shim that keeps `Toast` on
Android. Preserves behaviour, adds a platform seam.

**b) File picking — `rememberLauncherForActivityResult(OpenDocument)`.**
Recommended: **FileKit** (`vinceglb/FileKit`) — supports Android, JVM, iOS, macOS,
web, uses native pickers per platform, and covers the save-dialog case too.
Alternative: hand-rolled `expect`/`actual` — `ActivityResultContracts` on Android,
`JFileChooser`/`FileDialog` on desktop. No dependency, more code, worse UX.

**c) Runtime permissions — `RequestPermission` launcher, `ContextCompat`.**
Recommended: `expect suspend fun ensurePermission(...)` returning granted on desktop
unconditionally, since desktop has no runtime permission model. Do **not** pull in a
permissions library for one call site.

**ANSWER:** _pending_
```

---

## The change

### Step 1: Create `ui/platform-shims/` module structure

Create the directory tree:

```
ui/platform-shims/
├── build.gradle.kts
└── src/
    ├── commonMain/
    │   └── kotlin/com/transfer/flash/ui/shims/
    │       ├── FlashBackHandler.kt
    │       ├── FlashClipboard.kt            (pure common — no platform actuals)
    │       ├── FlashDecodeImageBitmap.kt
    │       ├── FlashTransientMessage.kt
    │       ├── FlashFilePicker.kt
    │       ├── FlashPermission.kt
    │       └── player/
    │           ├── FlashAudioPlayer.kt       (expect class)
    │           └── FlashVoiceRecorder.kt     (expect class)
    ├── androidMain/
    │   └── kotlin/com/transfer/flash/ui/shims/
    │       ├── FlashBackHandler.android.kt
    │       ├── FlashDecodeImageBitmap.android.kt
    │       ├── FlashTransientMessage.android.kt
    │       ├── FlashFilePicker.android.kt
    │       ├── FlashPermission.android.kt
    │       └── player/
    │           ├── FlashAudioPlayer.android.kt
    │           └── FlashVoiceRecorder.android.kt
    └── desktopMain/
        └── kotlin/com/transfer/flash/ui/shims/
            ├── FlashBackHandler.desktop.kt
            ├── FlashDecodeImageBitmap.desktop.kt
            ├── FlashTransientMessage.desktop.kt
            ├── FlashFilePicker.desktop.kt
            ├── FlashPermission.desktop.kt
            └── player/
                ├── FlashAudioPlayer.desktop.kt
                └── FlashVoiceRecorder.desktop.kt
```

> `FlashClipboard` (Shim 2) lives only in commonMain — `LocalClipboard` is a CMP common API, so there are no platform actuals. `resolveFileMetadata` is not a shim (Step 11) — its Android/desktop helpers are private inside the picker actuals (Step 6).

### Step 2: Write `build.gradle.kts` for `ui:platform-shims`

Mirror the KMP build pattern established in PHASE-18 but for a library module with no Android-specific publishing (it's an internal module, not published).

```kotlin
plugins {
    alias(libs.plugins.android.library)   // keeps Android variant for androidMain
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)    // compose plugin for desktopMain
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            // FileKit dependency — only if D7b chooses FileKit
            // implementation(libs.filekit)  // UNCOMMENT per D7 answer
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "com.transfer.flash.ui.shims"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

**Note:** This requires adding `kotlin.multiplatform` plugin alias to `libs.versions.toml` if not already present. Check PHASE-18 — it used `kotlin("multiplatform")` applied directly. The android library plugin alias must also be compatible with KMP (AGP 9.3.1 supports `com.android.library` with KMP).

### Step 3: Shim 1 — `FlashBackHandler` (expect/actual)

**Used in:** FlashConversationScreen.kt (lines 223, 226, 229), FlashMediaViewer.kt (line 259), FlashMessageContextMenu.kt (line 88), FlashPairingFlow.kt (line 152) — **6 total call sites**.

**CMP status:** `androidx.compose.ui.backhandler.BackHandler` exists in CMP 1.12.0 but is `@ExperimentalComposeUiApi` AND `@Deprecated("Use NavigationEventHandler instead")`. Relying on a deprecated experimental API is risky. Instead, create a project-local wrapper.

**commonMain — `FlashBackHandler.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Platform-agnostic back handler wrapper.
 *
 * On Android: delegates to [androidx.activity.compose.BackHandler].
 * On Desktop: no-op (no system back button). Override in desktop UI when
 * window-level back gesture is implemented.
 */
@Composable
expect fun FlashBackHandler(enabled: Boolean = true, onBack: () -> Unit)
```

**androidMain — `FlashBackHandler.android.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler as AndroidBackHandler

@Composable
actual fun FlashBackHandler(enabled: Boolean, onBack: () -> Unit) {
    AndroidBackHandler(enabled = enabled, onBack = onBack)
}
```

**desktopMain — `FlashBackHandler.desktop.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

@Composable
actual fun FlashBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop has no system back button. No-op.
    // If window-level Escape-key handling is needed, add KeyEvent handling here.
}
```

**Migration impact on call sites:** Replace `BackHandler(` → `FlashBackHandler(` and change `import androidx.activity.compose.BackHandler` → `import com.transfer.flash.ui.shims.FlashBackHandler` in all 6 call sites.

### Step 4: Shim 2 — `FlashClipboard` (pure common, no expect/actual)

**Used in:** FlashConversationScreen.kt `copyToClipboard()` function (lines 643–648).

**CMP status:** `androidx.compose.ui.platform.LocalClipboard` exists in CMP commonMain (`Clipboard` interface with suspend `setText(AnnotatedString)` / `getText(): AnnotatedString?`). The deprecated `LocalClipboardManager` (with `setText(AnnotatedString)` / `getText(): AnnotatedString?`) also exists.

**Design:** Use the current `LocalClipboard` (suspend-based) to avoid the deprecation warning.

**commonMain — `FlashClipboard.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Copies [text] to the system clipboard and shows a confirmation message.
 * Platform-agnostic via [LocalClipboard].
 */
@Composable
fun FlashCopyToClipboard(text: String, scope: CoroutineScope) {
    val clipboard = LocalClipboard.current
    scope.launch {
        clipboard.setText(AnnotatedString(text))
    }
    // The transient message (Toast/Snackbar) is handled by Shim 3.
}
```

**Note:** This is a `fun`, not `expect fun`, because `LocalClipboard` is available in CMP common. The existing `copyToClipboard` private function in FlashConversationScreen.kt becomes a call to `FlashCopyToClipboard` plus a call to the transient-message shim.

**Migration impact on FlashConversationScreen.kt — two call sites:**

1. Selection toolbar copy (line 275–281): `copyToClipboard(context, selectedTexts)` — joins multi-selected message texts with `\n`, then clears selection.
2. Context-menu copy (line 498): `copyToClipboard(context, msg.text)`.

Replace the private function (lines 643–648):
```kotlin
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Flash Message", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
```

With a composable-scoped version (both call sites are inside the screen composable, so they can capture `coroutineScope`):
```kotlin
@Composable
private fun copyToClipboard(scope: CoroutineScope, text: String) {
    scope.launch { LocalClipboard.current.setText(AnnotatedString(text)) }
    showTransientMessage("Copied to clipboard")  // composable call — see Shim 3
}
```

> `copyToClipboard` must be `@Composable` because `showTransientMessage` and `LocalClipboard.current` are composition-scoped. Both call sites (line 279 in the selection toolbar `onCopy`, line 498 in the context menu) are inside the screen composable, so this works.

And change both invocations from `copyToClipboard(context, ...)` to `copyToClipboard(coroutineScope, ...)`, using the existing `val coroutineScope = rememberCoroutineScope()` at line 111.

### Step 5: Shim 3 — `showTransientMessage` (D7a-gated)

**Used in:** FlashConversationScreen.kt — **13 call sites** (all `Toast.makeText(...).show()`).

**D7a options:**

**Option A (Snackbar, if D7a chooses Snackbar):** No expect/actual needed. `SnackbarHostState` + `SnackbarHost` are already in CMP common. This is a pure behaviour change — see `docs/migration/DECISIONS.md` D7a.

**Option B (expect/actual, if D7a chooses to preserve Toast):**

**commonMain — `FlashTransientMessage.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Shows a short transient message to the user.
 * Android: [android.widget.Toast].
 * Desktop: console.log or a lightweight popup.
 */
@Composable
expect fun showTransientMessage(message: String)
```

**androidMain — `FlashTransientMessage.android.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun showTransientMessage(message: String) {
    Toast.makeText(LocalContext.current, message, Toast.LENGTH_SHORT).show()
}
```

**desktopMain — `FlashTransientMessage.desktop.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

@Composable
actual fun showTransientMessage(message: String) {
    // Desktop: log to console. Replace with a snackbar or popup when
    // the desktop window has a host.
    println("Flash: $message")
}
```

**Migration impact:** Replace all 13 `Toast.makeText(context, ...)` calls with `showTransientMessage(...)`. The `context` variable is no longer needed at those call sites (but `context` is still used for file picker, audio player, recorder, and image decode).

### Step 6: Shim 4 — `FlashFilePicker` (D7b-gated)

**Used in:** FlashConversationScreen.kt lines 117–132 (file picker + `takePersistableUriPermission`).

**D7b options:**

**Option A (FileKit):** Add `implementation(libs.filekit)` to `commonMain` dependencies in `ui:platform-shims/build.gradle.kts`. Create a thin wrapper over FileKit's `rememberFilePickerLauncher`.

> **Critical detail:** FileKit's `rememberFilePickerLauncher` with `OpenDocument` returns `UriWrapper` but does NOT call `takePersistableUriPermission` automatically. The Android code at line 123 explicitly persists permission so the transfer engine can stream the file after the screen dies. This must be handled manually in the wrapper's `onFilePicked` callback.

**Option B (hand-rolled expect/actual):** `expect` a composable picker function, `actual` with `ActivityResultContracts.OpenDocument` + `takePersistableUriPermission` on Android, `JFileChooser` on desktop. No dependency, more code, worse UX. The full code below is the **Option B** design; if Option A is chosen, the same commonMain `expect` stays and only the Android/desktop actuals become FileKit wrappers.

**commonMain (shared by both options — `FlashFilePicker.kt`):**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Launches a file picker and calls [onFilePicked] with the selected URI string,
 * display name, and size in bytes.
 *
 * Platform implementations:
 * - Android: [ActivityResultContracts.OpenDocument] + [takePersistableUriPermission]
 * - Desktop: [javax.swing.JFileChooser] (or FileKit's native dialog)
 */
@Composable
expect fun rememberFlashFilePickerLauncher(
    onFilePicked: (uri: String, displayName: String, size: Long) -> Unit,
): () -> Unit
```

**androidMain (Option B — `FlashFilePicker.android.kt`):**

```kotlin
package com.transfer.flash.ui.shims

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberFlashFilePickerLauncher(
    onFilePicked: (uri: String, displayName: String, size: Long) -> Unit,
): () -> Unit {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read access for transfer streaming
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            // Resolve metadata
            val (name, size) = resolveFileMetadata(context, uri)
            onFilePicked(uri.toString(), name, size)
        }
    }

    return { launcher.launch(arrayOf("*/*")) }
}

/**
 * Resolves display name and byte size from a content URI via [OpenableColumns].
 * This is a private helper — the public API is [rememberFlashFilePickerLauncher].
 */
private fun resolveFileMetadata(context: android.content.Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    return name to size
}
```

**desktopMain (Option B — `FlashFilePicker.desktop.kt`):**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberFlashFilePickerLauncher(
    onFilePicked: (uri: String, displayName: String, size: Long) -> Unit,
): () -> Unit {
    return {
        val chooser = JFileChooser().apply {
            dialogTitle = "Select a file to send"
            fileFilter = FileNameExtensionFilter("All Files", "*")
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = true
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            val file: File = chooser.selectedFile
            onFilePicked(
                uri = file.toURI().toString(),
                displayName = file.name,
                size = file.length(),
            )
        }
    }
}
```

**Migration impact on FlashConversationScreen.kt:**

Replace the entire file picker block (lines 117–132):
```kotlin
val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
) { uri: Uri? ->
    if (uri != null) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, ...)
        }
        val (name, size) = resolveFileMetadata(context, uri)
        onSendFile(uri.toString(), name, size)
        Toast.makeText(context, "Sending $name", Toast.LENGTH_SHORT).show()
    }
}
```

With:
```kotlin
val filePicker = rememberFlashFilePickerLauncher { uri, name, size ->
    onSendFile(uri, name, size)
    showTransientMessage("Sending $name")
}
```

### Step 7: Shim 5 — `FlashPermission` (D7c)

**Used in:** FlashConversationScreen.kt lines 399–409 (`onVoiceRecordStart` callback) and lines 142–150 (permission result toast).

The `onVoiceRecordStart` callback (line 400) synchronously checks `ContextCompat.checkSelfPermission` and conditionally launches the permission request. The result callback (line 142–150) shows a toast. Because the check and the launch both need a `Context` (which only exists in composition on Android), the shim is a **composable `expect`/`actual` pair** — the non-composable `onVoiceRecordStart` lambda reads the `isGranted` boolean captured at composition time:

1. **`rememberPermissionHelper(permission): PermissionHelper`** — composable; returns a `PermissionHelper(isGranted: Boolean, launchRequest: () -> Unit)`.
2. **`rememberPermissionRequest(permission, onResult): () -> Unit`** — composable; returns a launch lambda that reports the grant result to `onResult`.

**commonMain — `FlashPermission.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Snapshot of runtime permission state for a single permission string.
 *
 * Android: [ContextCompat.checkSelfPermission] + an `ActivityResultContracts.RequestPermission` launcher.
 * Desktop: always granted (no runtime permission model).
 */
data class PermissionHelper(
    val isGranted: Boolean,
    val launchRequest: () -> Unit,
)

/**
 * Captures the current grant state for [permission] and returns a launch lambda.
 * The `isGranted` value is captured at composition time — safe to read inside
 * non-composable callbacks (e.g. `onVoiceRecordStart`).
 */
@Composable
expect fun rememberPermissionHelper(permission: String): PermissionHelper

/**
 * Returns a lambda that, when invoked, requests [permission] and calls
 * [onResult] with the grant result.
 *
 * Android: launches [ActivityResultContracts.RequestPermission].
 * Desktop: invokes [onResult](true) immediately.
 */
@Composable
expect fun rememberPermissionRequest(
    permission: String,
    onResult: (Boolean) -> Unit,
): () -> Unit
```

**androidMain — `FlashPermission.android.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberPermissionHelper(permission: String): PermissionHelper {
    val context = LocalContext.current
    val isGranted = ContextCompat.checkSelfPermission(
        context,
        permission,
    ) == PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* result handled by the caller's onResult, wired via rememberPermissionRequest */ }

    return PermissionHelper(
        isGranted = isGranted,
        launchRequest = { launcher.launch(permission) },
    )
}

@Composable
actual fun rememberPermissionRequest(
    permission: String,
    onResult: (Boolean) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }
    return { launcher.launch(permission) }
}
```

**desktopMain — `FlashPermission.desktop.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPermissionHelper(permission: String): PermissionHelper =
    PermissionHelper(isGranted = true, launchRequest = {})

@Composable
actual fun rememberPermissionRequest(
    permission: String,
    onResult: (Boolean) -> Unit,
): () -> Unit = {
    // Desktop has no runtime permissions — always granted.
    onResult(true)
}
```

> **Phase 20 note:** Both shims are `@Composable` `expect`s, so they are callable from `ui:chat`'s `commonMain` once it becomes a KMP module. The plain non-composable `checkPermission` idea was rejected because the Android `actual` cannot reach `LocalContext` from a non-composable function — the `PermissionHelper` captured-at-composition design is the workable one.

**Migration impact on FlashConversationScreen.kt — permission check + request:**

Replace the two Android-pinned patterns:

1. The `onVoiceRecordStart` callback (lines 399–409):
```kotlin
onVoiceRecordStart = {
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        false
    } else {
        voiceRecorder.start()
    }
},
```

With:
```kotlin
onVoiceRecordStart = {
    if (!micPermissionHelper.isGranted) {
        micPermissionHelper.launchRequest()
        false
    } else {
        voiceRecorder.start()
    }
},
```

Where `micPermissionHelper` is created at screen scope:
```kotlin
val micPermissionHelper = rememberPermissionHelper(Manifest.permission.RECORD_AUDIO)
```

2. The permission result toast (lines 142–150):
```kotlin
val micPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    if (granted) {
        Toast.makeText(context, "Microphone ready — hold to record", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "Microphone permission is required for voice messages", Toast.LENGTH_SHORT).show()
    }
}
```

With:
```kotlin
val micPermissionRequest = rememberPermissionRequest(
    permission = Manifest.permission.RECORD_AUDIO,
) { granted ->
    if (granted) {
        showTransientMessage("Microphone ready — hold to record")
    } else {
        showTransientMessage("Microphone permission is required for voice messages")
    }
}
```

**Note on `Manifest.permission.RECORD_AUDIO`:** The string `"android.permission.RECORD_AUDIO"` can be referenced directly as a string literal `"android.permission.RECORD_AUDIO"` in commonMain, since it's a constant. On desktop the permission check always returns true, so the string is never used. This avoids importing `android.Manifest` in commonMain.

### Step 8: Shim 6 — `rememberDecodeImageBitmap` (expect/actual)

**Used in:**
- FlashImageGrid.kt lines 396–410: `produceState` + `Dispatchers.IO` + `BitmapFactory.decodeStream`/`decodeFile` → `asImageBitmap()`
- FlashMediaViewer.kt lines 419–456: `produceState` + `Dispatchers.IO` + `BitmapFactory.decodeStream`/`decodeFile` with `inSampleSize` → `asImageBitmap()`

**Design — one composable `expect`/`actual`:** `rememberDecodeImageBitmap` is `@Composable` so the Android `actual` can read `LocalContext.current`. Each platform `actual` calls a private, non-composable platform helper (`decodeImageBitmap`) that holds the platform decode logic.

**commonMain — `FlashDecodeImageBitmap.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes an image from [uri] into a Compose [ImageBitmap], or null on failure.
 *
 * Android: [BitmapFactory.decodeStream] / [BitmapFactory.decodeFile] → [asImageBitmap].
 * Desktop: Skia [org.jetbrains.skia.Image.makeFromEncoded] → [toComposeImageBitmap].
 *
 * @param uri The URI string (content://, file://, or absolute path).
 * @param maxSampleLongEdge When > 0, the platform may decode a smaller version so the
 *        long edge stays ≤ this value. On Android this uses [BitmapFactory.Options.inSampleSize].
 */
@Composable
expect fun rememberDecodeImageBitmap(
    uri: String,
    maxSampleLongEdge: Int = 0,
): ImageBitmap?
```

**androidMain — `FlashDecodeImageBitmap.android.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberDecodeImageBitmap(
    uri: String,
    maxSampleLongEdge: Int,
): ImageBitmap? {
    val context = LocalContext.current
    return remember(uri, maxSampleLongEdge) {
        decodeImageBitmap(context = context, uri = uri, maxSampleLongEdge = maxSampleLongEdge)
    }
}

/**
 * Android decode: [ContentResolver] stream for content:// / file:// URIs,
 * direct file decode for absolute paths, optional downsampling via [BitmapFactory.Options.inSampleSize].
 */
private fun decodeImageBitmap(
    context: Context,
    uri: String,
    maxSampleLongEdge: Int,
): ImageBitmap? {
    return when {
        uri.startsWith("content://") || uri.startsWith("file://") -> {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                if (maxSampleLongEdge > 0) {
                    // First pass: decode bounds only.
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    if (options.outWidth <= 0 || options.outHeight <= 0) return@use null
                    options.inSampleSize = computeSampleSize(options.outWidth, options.outHeight, maxSampleLongEdge)
                    options.inJustDecodeBounds = false
                    // Second pass: decode with sampling (fresh stream — first pass consumed it).
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { retryStream ->
                        BitmapFactory.decodeStream(retryStream, null, options)?.asImageBitmap()
                    }
                } else {
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        }
        File(uri).exists() -> {
            if (maxSampleLongEdge > 0) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(uri, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return null
                options.inSampleSize = computeSampleSize(options.outWidth, options.outHeight, maxSampleLongEdge)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(uri, options)?.asImageBitmap()
            } else {
                BitmapFactory.decodeFile(uri)?.asImageBitmap()
            }
        }
        else -> null
    }
}

/** Power-of-two sample size so the decoded long edge stays ≤ [maxLongEdge]. */
private fun computeSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
    var sample = 1
    var longEdge = maxOf(width, height)
    while (longEdge > maxLongEdge) {
        sample *= 2
        longEdge /= 2
    }
    return sample
}
```

**desktopMain — `FlashDecodeImageBitmap.desktop.kt`:**

```kotlin
package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File
import java.io.FileInputStream
import java.net.URI

@Composable
actual fun rememberDecodeImageBitmap(
    uri: String,
    maxSampleLongEdge: Int,
): ImageBitmap? {
    return remember(uri, maxSampleLongEdge) {
        decodeImageBitmap(uri = uri, maxSampleLongEdge = maxSampleLongEdge)
    }
}

/**
 * Desktop decode: read file bytes, decode via Skia [Image.makeFromEncoded],
 * convert via [toComposeImageBitmap].
 *
 * @param maxSampleLongEdge Currently ignored on desktop (full-resolution decoded).
 *                          Future: downscale via a Skia [Surface] draw when needed.
 */
private fun decodeImageBitmap(uri: String, maxSampleLongEdge: Int): ImageBitmap? {
    return try {
        val file = when {
            uri.startsWith("content://") -> return null  // Android-only content scheme
            uri.startsWith("file://") -> File(URI.create(uri))
            else -> File(uri)
        }
        if (!file.exists()) return null
        val bytes = FileInputStream(file).use { it.readBytes() }
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        println("Flash: decodeImageBitmap failed for $uri: ${e.message}")
        null
    }
}
```

**Migration impact on FlashImageGrid.kt and FlashMediaViewer.kt:**

Both files currently wrap `BitmapFactory` in `produceState` + `Dispatchers.IO` (FlashImageGrid.kt lines 396–410, FlashMediaViewer.kt lines 419–456). The shim is a plain `@Composable` (synchronous) function, so the migration keeps the off-main-thread behavior by calling it inside the existing `produceState` block:

```kotlin
val bitmapState = produceState<ImageBitmap?>(initialValue = null, key1 = image.uri, key2 = image.thumbUri) {
    value = withContext(Dispatchers.IO) {
        rememberDecodeImageBitmap(uri = uriStr, maxSampleLongEdge = 0)  // composable call inside produceState producer
    }
}
```

> **Compose note:** `produceState`'s producer lambda IS a composable context (`produceState` is `@Composable` and its `producer` is a `suspend` composable lambda — it can call other composables). This is the standard CMP pattern for image loading, so `rememberDecodeImageBitmap` can be invoked there. Verify with the CMP image-loading pattern before committing. If a non-composable decode is preferred instead, the platform helpers (`decodeImageBitmap`) are private per platform — expose one as `expect fun decodeImageBitmap(uri, maxSampleLongEdge): ImageBitmap?` and drop the composable wrapper; both call sites are inside `produceState` so the plain `expect fun` works identically.

For FlashMediaViewer.kt, pass `FlashMediaViewerMath.MAX_DECODE_LONG_EDGE` as `maxSampleLongEdge` so Android keeps its `inSampleSize` downsampling (the current code computes it from `options.outWidth/outHeight` + `computeInSampleSize`).

### Step 9: Shim 7 — `FlashAudioPlayer` (expect class)

**Used in:** FlashVoiceMessageCard.kt line 185: `val player = remember { FlashAudioPlayer(context, uri) }`

**commonMain — `FlashAudioPlayer.kt` (mirrors the real API verbatim — see Appendix A2):**

```kotlin
package com.transfer.flash.ui.shims.player

/**
 * Platform-agnostic audio player for voice messages.
 *
 * Android: delegates to [android.media.MediaPlayer] (lazy prepare on first play).
 * Desktop: no-op stub (returns 0 / false, does nothing).
 *
 * API mirrors the current `FlashAudioPlayer` in `ui:chat` exactly so the
 * call site in `FlashVoiceMessageCard` changes only its constructor.
 */
expect class FlashAudioPlayer(uri: String) {
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun positionMs(): Long
    fun isPlaying(): Boolean
    fun release()
}
```

**Context problem:** The Android `actual` needs a `Context` for `MediaPlayer.setDataSource(context, uri)`, but `expect class` constructors can't read `LocalContext`. Resolution: keep the `expect class` API constructor `(uri: String)` in commonMain, and add a **composable factory** per platform:

```kotlin
// commonMain — FlashAudioPlayer.kt (same file)
@Composable
expect fun rememberFlashAudioPlayer(uri: String): FlashAudioPlayer
```

**androidMain — `FlashAudioPlayer.android.kt`:**

```kotlin
package com.transfer.flash.ui.shims.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

actual class FlashAudioPlayer(uri: String) {
    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSpeed = 1.0f

    // Injected by rememberFlashAudioPlayer; set before first use.
    internal var context: Context? = null

    private fun ensurePrepared(): MediaPlayer? {
        player?.let { return it }
        val ctx = context ?: return null
        return try {
            MediaPlayer().apply {
                setDataSource(ctx, Uri.parse(uri))
                prepare() // local file → cheap synchronous prepare
                prepared = true
                player = this
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Voice playback failed to prepare", t)
            runCatching { player?.release() }
            player = null
            prepared = false
            null
        }
    }

    actual fun play() {
        val p = ensurePrepared() ?: return
        runCatching {
            setSpeed(pendingSpeed)
            if (!p.isPlaying) p.start()
        }
    }

    actual fun pause() {
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
    }

    actual fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms.toInt()) }
    }

    actual fun setSpeed(speed: Float) {
        pendingSpeed = speed
        val p = player ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val wasPlaying = p.isPlaying
                p.playbackParams = p.playbackParams.setSpeed(speed)
                if (!wasPlaying && p.isPlaying) p.pause()
            }
        }
    }

    actual fun positionMs(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    actual fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    actual fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }

    private companion object { const val TAG = "FlashAudioPlayer" }
}

@Composable
actual fun rememberFlashAudioPlayer(uri: String): FlashAudioPlayer =
    remember(uri) {
        FlashAudioPlayer(uri).also { it.context = LocalContext.current }
    }
```

> **Fidelity note:** The `actual` body above is the current `FlashAudioPlayer` implementation (verified 2026-08-26) with only two mechanical changes: the constructor takes `uri: String` (Context injected via `internal var`), and the API members are `actual`. This preserves Android behaviour exactly.

**desktopMain — `FlashAudioPlayer.desktop.kt`:**

```kotlin
package com.transfer.flash.ui.shims.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class FlashAudioPlayer(uri: String) {
    init { println("FlashAudioPlayer: created (desktop stub) for $uri") }
    actual fun play() { println("FlashAudioPlayer: play (stub)") }
    actual fun pause() { println("FlashAudioPlayer: pause (stub)") }
    actual fun seekTo(ms: Long) { println("FlashAudioPlayer: seekTo $ms (stub)") }
    actual fun setSpeed(speed: Float) { println("FlashAudioPlayer: setSpeed $speed (stub)") }
    actual fun positionMs(): Long = 0L
    actual fun isPlaying(): Boolean = false
    actual fun release() { println("FlashAudioPlayer: release (stub)") }
}

@Composable
actual fun rememberFlashAudioPlayer(uri: String): FlashAudioPlayer =
    remember(uri) { FlashAudioPlayer(uri) }
```

> **Do NOT import `android.util.Log` in desktopMain.** Use `println` (or kotlin-logging) only.

**Migration impact on FlashVoiceMessageCard.kt (lines 182–189):**

Current:
```kotlin
val context = androidx.compose.ui.platform.LocalContext.current
val hasAudio = attachment.uri != null && attachment.transferStatus == FlashFileTransferStatus.Downloaded
val audioPlayer = remember(attachment.id, attachment.uri, hasAudio) {
    if (hasAudio) FlashAudioPlayer(context, attachment.uri!!) else null
}
DisposableEffect(audioPlayer) {
    onDispose { audioPlayer?.release() }
}
```

New (note: a `@Composable` factory cannot be called inside a `remember { }` lambda, so the conditional moves out):
```kotlin
val hasAudio = attachment.uri != null && attachment.transferStatus == FlashFileTransferStatus.Downloaded
val audioPlayer = if (hasAudio) {
    rememberFlashAudioPlayer(attachment.uri!!)   // composable factory; keys handled internally
} else {
    null
}
DisposableEffect(audioPlayer) {
    onDispose { audioPlayer?.release() }
}
```

- The `context` line is removed (no longer needed).
- `FlashAudioPlayer(context, uri)` → `rememberFlashAudioPlayer(uri)` (import `com.transfer.flash.ui.shims.player.rememberFlashAudioPlayer`).
- The `DisposableEffect` release is preserved unchanged. `rememberFlashAudioPlayer` keys on `uri` internally (its `remember(uri)`), which matches the previous key on `attachment.uri`.

### Step 10: Shim 8 — `FlashVoiceRecorder` (expect class)

**Used in:** FlashConversationScreen.kt line 136: `val voiceRecorder = remember { FlashVoiceRecorder(context) }`

**commonMain — `FlashVoiceRecorder.kt` (mirrors the real API verbatim — see Appendix A3):**

```kotlin
package com.transfer.flash.ui.shims.player

/**
 * Platform-agnostic voice recorder for voice messages.
 *
 * Android: delegates to [android.media.MediaRecorder] → m4a in cacheDir,
 * amplitude scaled 0..100.
 * Desktop: no-op stub (never records; start() returns false, stop() returns null).
 *
 * API mirrors the current `FlashVoiceRecorder` in `ui:chat` exactly so the
 * call sites in `FlashConversationScreen` change only their construction.
 */
expect class FlashVoiceRecorder {
    fun start(): Boolean
    val isRecording: Boolean
    fun maxAmplitude(): Int
    fun stop(): String?
    fun cancel()
}
```

**Context problem (same as FlashAudioPlayer):** the Android `actual` needs `Context` for `cacheDir`. Add a composable factory:

```kotlin
// commonMain — FlashVoiceRecorder.kt (same file)
@Composable
expect fun rememberFlashVoiceRecorder(): FlashVoiceRecorder
```

**androidMain — `FlashVoiceRecorder.android.kt`:**

```kotlin
package com.transfer.flash.ui.shims.player

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

actual class FlashVoiceRecorder {
    internal var context: Context? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    actual val isRecording: Boolean get() = recorder != null

    actual fun start(): Boolean {
        stopQuietly()
        val ctx = context ?: return false
        val file = File(ctx.cacheDir, "flash-voice-${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(ctx)
        } else {
            MediaRecorder()
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Voice capture failed to start", t)
            runCatching { rec.release() }
            file.delete()
            recorder = null
            outputFile = null
            false
        }
    }

    actual fun maxAmplitude(): Int {
        val rec = recorder ?: return 0
        return try {
            ((rec.maxAmplitude / 32767f) * 100f).toInt().coerceIn(0, 100)
        } catch (t: Throwable) {
            0
        }
    }

    actual fun stop(): String? {
        val rec = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (rec == null) return null
        return try {
            rec.stop()
            rec.release()
            if (file != null && file.exists() && file.length() > 0L) {
                Uri.fromFile(file).toString()
            } else {
                file?.delete()
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Voice capture stop failed", t)
            runCatching { rec.release() }
            file?.delete()
            null
        }
    }

    actual fun cancel() {
        val file = outputFile
        stopQuietly()
        file?.delete()
    }

    private fun stopQuietly() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    private companion object { const val TAG = "FlashVoiceRecorder" }
}

@Composable
actual fun rememberFlashVoiceRecorder(): FlashVoiceRecorder =
    remember {
        FlashVoiceRecorder().also { it.context = LocalContext.current }
    }
```

> **Fidelity note:** The `actual` body above is the current `FlashVoiceRecorder` implementation (verified 2026-08-26) with only mechanical changes: constructor no longer takes `Context` (injected via `internal var`), and members are `actual`. `stop()` returns a `file://` URI string, matching the existing call site `onSendVoiceMessage(path, ...)`.

**desktopMain — `FlashVoiceRecorder.desktop.kt`:**

```kotlin
package com.transfer.flash.ui.shims.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class FlashVoiceRecorder {
    actual val isRecording: Boolean get() = false
    actual fun start(): Boolean {
        println("FlashVoiceRecorder: start (stub)")
        return false // Voice recording not supported on desktop
    }
    actual fun maxAmplitude(): Int = 0
    actual fun stop(): String? {
        println("FlashVoiceRecorder: stop (stub)")
        return null
    }
    actual fun cancel() { println("FlashVoiceRecorder: cancel (stub)") }
}

@Composable
actual fun rememberFlashVoiceRecorder(): FlashVoiceRecorder = remember { FlashVoiceRecorder() }
```

**Migration impact on FlashConversationScreen.kt:**
- Current (line 136): `val voiceRecorder = remember { FlashVoiceRecorder(context) }`
- New: `val voiceRecorder = rememberFlashVoiceRecorder()`
- All other call sites (`voiceRecorder.start()` line 408, `.stop()` line 392, `.maxAmplitude()` line 412, `.cancel()` line 411/138) keep their existing shapes. The desktop stub's `start()` → false and `stop()` → null are already handled: the composer treats `start() == false` as "recording did not start", and `stop() == null` skips `onSendVoiceMessage` (line 393–394 guard).

### Step 11: `resolveFileMetadata` — folded into the picker shim (not a standalone shim)

**Used in:** FlashConversationScreen.kt lines 654–672 (private function, only referenced by the file-picker callback at lines 117–132).

**Design decision:** In **both** D7b branches the metadata helper is encapsulated inside the `rememberFlashFilePickerLauncher` actual (Step 6), **not** exposed as a separate shim:

- **If D7b chooses FileKit** — `FileKit.OpenDocument` returns `UriWrapper`; metadata comes from the picker actual, which queries it from the returned URI. No `resolveFileMetadata` function survives in `ui:chat`.
- **If D7b chooses hand-rolled** — the Android `rememberFlashFilePickerLauncher` actual (Step 6) already includes the `resolveFileMetadata(context, uri)` private helper. It stays private to that file.

The original `resolveFileMetadata` is **deleted from `ui:chat`** in both cases (its logic moves into the picker actual), so there is no `expect`/`actual` pair for it and no signature to keep consistent.

**Reference — Android metadata helper (lives inside the picker actual, Step 6):**

```kotlin
// Inside FlashFilePicker.android.kt (Step 6) — private to the file.
private fun resolveFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    return name to size
}

// Desktop analog (inside FlashFilePicker.desktop.kt, Step 6):
//   private fun resolveFileMetadata(uri: String): Pair<String, Long> =
//       runCatching { File(URI.create(uri)).let { it.name to it.length() } }.getOrDefault("file" to 0L)
```

### Step 12: Update `ui:chat/build.gradle.kts` — add `ui:platform-shims` dependency

Add to `ui:chat/build.gradle.kts` dependencies:

```kotlin
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:messaging"))
    implementation(project(":ui:theme"))
    implementation(project(":ui:platform-shims"))  // NEW
    // ... rest unchanged
}
```

**This is the ONLY change to `ui:chat/build.gradle.kts` in Phase 19.** The module remains an Android library — it is converted to KMP in PHASE-20.

### Step 13: Update `settings.gradle.kts` — include `:ui:platform-shims`

Add to the `settings.gradle.kts` `include` block:

```kotlin
include(":ui:platform-shims")
```

### Step 14: Add FileKit dependency to `libs.versions.toml` (if D7b chooses FileKit)

If D7b chooses FileKit, add to `gradle/libs.versions.toml`:

```toml
[versions]
filekit = "0.15.0"

[libraries]
filekit = { module = "com.vinceglb:filekit-compose", version.ref = "filekit" }
```

FileKit 0.15.0 is current as of 2026-08-25. Verify before adding.

---

## Verification gate

| Check | Command / Action | Expected result |
|---|---|---|
| Module compiles (Android) | `./gradlew :ui:platform-shims:assembleDebug` | BUILD SUCCESSFUL |
| Module compiles (Desktop) | `./gradlew :ui:platform-shims:compileKotlinDesktop` | BUILD SUCCESSFUL |
| ui:chat still compiles | `./gradlew :ui:chat:assembleDebug` | BUILD SUCCESSFUL |
| No Android imports in commonMain | `grep -r "import android\." ui/platform-shims/src/commonMain/` | No matches |
| No Android imports in desktopMain | `grep -r "import android\." ui/platform-shims/src/desktopMain/` | No matches (except explicit `android.util.Log` which must NOT be there) |
| Desktop shims compile | `./gradlew :ui:platform-shims:compileKotlinDesktop` | BUILD SUCCESSFUL |
| All 8 shims present | Check file count per source set | commonMain: 8 files, androidMain: 7, desktopMain: 7 |
| `ui:chat` depends on `:ui:platform-shims` | `grep "platform-shims" ui/chat/build.gradle.kts` | Match found |
| `settings.gradle.kts` includes platform-shims | `grep "platform-shims" settings.gradle.kts` | Match found |
| No `Toast` references remain in `ui:chat` | `grep -r "Toast" ui/chat/src/main/java/` | No matches (if D7a chose Snackbar) |
| No `BackHandler` from `androidx.activity` in `ui:chat` | `grep "androidx.activity.compose.BackHandler" ui/chat/src/main/java/` | No matches |
| No `BitmapFactory` in `ui:chat` | `grep "BitmapFactory" ui/chat/src/main/java/` | No matches |
| No `Context.getSystemService(CLIPBOARD_SERVICE)` | `grep "CLIPBOARD_SERVICE" ui/chat/src/main/java/` | No matches |
| All 31 test files still compile | `./gradlew :ui:chat:testDebugUnitTest` | BUILD SUCCESSFUL, tests pass |

---

## Do NOT

- **Do NOT** convert `ui:chat` to a KMP module in this phase. That is Phase 20.
- **Do NOT** remove the `android` block from `ui:chat/build.gradle.kts` — it stays an Android library.
- **Do NOT** add `import android.util.Log` in desktopMain source files. Use `println` or `kotlin.logging`.
- **Do NOT** change the behavior of `FlashAudioPlayer` or `FlashVoiceRecorder` on Android — only extract them into `expect`/`actual`.
- **Do NOT** silently replace `Toast` with `Snackbar` if D7a is still `_pending_`. If D7a chooses Snackbar, the migration must be done as a careful find-and-replace with SnackbarHostState plumbing.
- **Do NOT** add dependencies to `ui:chat` that belong in `ui:platform-shims` only.
- **Do NOT** delete the `context` variable from FlashConversationScreen.kt — it is still used for `LocalContext.current` (image decode, file picker, voice recorder on Android).
- **Do NOT** change the `package` of existing `ui:chat` files — only change their imports.
- **Do NOT** version-bump FileKit or any other dependency without checking CONVENTIONS.md R10.

---

## Rollback

If Phase 19 causes build failures that cannot be quickly resolved:

1. `git checkout dev -- ui/platform-shims/` — delete the entire new module.
2. `git checkout dev -- ui/chat/build.gradle.kts` — revert the dependency addition.
3. `git checkout dev -- settings.gradle.kts` — revert the include addition.
4. `git checkout dev -- gradle/libs.versions.toml` — revert any FileKit version addition.
5. Revert the 7 `ui:chat` source files to their pre-Phase-19 state by checking out each file individually.

If only a subset of shims introduced bugs, revert only those shim files and their corresponding `ui:chat` import changes.

---

## Log entry for `logs/migration.md`

```markdown
## 2026-08-26 — PHASE-19: UI Platform Shims (D7-gated)

### Status
AUTHORED (not executed)

### D7 status
ANSWER: _pending_ — DO NOT EXECUTE until D7 is resolved.

### What was created
- `ui/platform-shims/` — new KMP module with 8 shims:
  1. `FlashBackHandler` — expect/actual (Activity → no-op)
  2. `FlashClipboard` — pure common (uses `LocalClipboard`)
  3. `showTransientMessage` — expect/actual (Toast → println)
  4. `rememberFlashFilePickerLauncher` — expect/actual (OpenDocument → JFileChooser); includes `resolveFileMetadata` helper (Step 11)
  5. `rememberPermissionHelper` + `rememberPermissionRequest` — expect/actual (RequestPermission → always granted)
  6. `rememberDecodeImageBitmap` — expect/actual (BitmapFactory → Skia)
  7. `FlashAudioPlayer` — expect class (MediaPlayer → stub)
  8. `FlashVoiceRecorder` — expect class (MediaRecorder → stub)

### What was changed
- `ui/chat/build.gradle.kts` — added `:ui:platform-shims` dependency
- `settings.gradle.kts` — added `include(":ui:platform-shims")`
- `gradle/libs.versions.toml` — added FileKit entry (if D7b chooses FileKit)
- 7 `ui:chat` source files — replaced Android imports with shim imports

### Verification
NOT YET VERIFIED (blocked by D7 answer)

### Remaining before Phase 20
- [ ] D7 answer recorded in DECISIONS.md
- [ ] Build verification on Android
- [ ] Build verification on Desktop (JVM)
- [ ] Unit tests pass
```

---

## Appendix: Per-file Android-pin detail

### A1. `FlashConversationScreen.kt` (686 lines)

| Line(s) | Android import | Usage | Seam |
|---|---|---|---|
| 117–132 | `ActivityResultContracts.OpenDocument`, `Uri`, `Intent` | File picker + `takePersistableUriPermission` | `rememberFlashFilePickerLauncher` |
| 136 | `Context` | `FlashVoiceRecorder(context)` | `FlashVoiceRecorder()` (no-arg ctor) |
| 142–150 | `ActivityResultContracts.RequestPermission`, `Manifest` | Mic permission request | `rememberPermissionRequest` |
| 223, 226, 229 | `androidx.activity.compose.BackHandler` | Back press handling | `FlashBackHandler` |
| 643–648 | `ClipboardManager`, `ClipData`, `Context` | `copyToClipboard` | `FlashCopyToClipboard` + `showTransientMessage` |
| 654–672 | `OpenableColumns`, `Uri`, `Context` | `resolveFileMetadata` | folded into `rememberFlashFilePickerLauncher` actual (Step 11) |
| 13× scattered | `Toast` | Transient status messages | `showTransientMessage` or `SnackbarHost` |

### A2. `FlashAudioPlayer.kt` (85 lines)

| Line(s) | Android import | Usage | Seam |
|---|---|---|---|
| 1 | `android.content.Context` | Constructor parameter | Resolved via composable factory |
| 2 | `android.media.MediaPlayer` | Core playback engine | `expect class FlashAudioPlayer` |
| 3 | `android.net.Uri` | Parse URI for `setDataSource` | String → Uri conversion in actual |
| 4 | `android.os.Build` | SDK version check for `playbackParams` | `actual` only |
| 5 | `android.util.Log` | Error logging | `println` in desktop stub |

### A3. `FlashVoiceRecorder.kt` (119 lines)

| Line(s) | Android import | Usage | Seam |
|---|---|---|---|
| 1 | `android.content.Context` | `cacheDir` for output file | Resolved via composable factory |
| 2 | `android.media.MediaRecorder` | Core recording engine | `expect class FlashVoiceRecorder` |
| 3 | `android.net.Uri` | (Used in `stop()` for return value) | Not needed — return `String?` |
| 4 | `android.os.Build` | SDK version check | `actual` only |
| 5 | `android.util.Log` | Error logging | `println` in desktop stub |

### A4. `FlashImageGrid.kt` (396–410)

| Line(s) | Android import | Usage | Seam |
|---|---|---|---|
| 396–410 | `BitmapFactory`, `Uri` | `BitmapFactory.decodeStream`/`decodeFile` → `asImageBitmap()` | `rememberDecodeImageBitmap` |

### A5. `FlashMediaViewer.kt` (419–456, 259)

| Line(s) | Android import | Usage | Seam |
|---|---|---|---|
| 419–456 | `BitmapFactory`, `Uri` | `BitmapFactory.decodeStream` with `inSampleSize` → `asImageBitmap()` | `rememberDecodeImageBitmap(uri, maxSampleLongEdge)` |
| 259 | `androidx.activity.compose.BackHandler` | Back press to dismiss viewer | `FlashBackHandler` |

### A6. `FlashMessageContextMenu.kt` (88)

| Line(s) | Android import | Usage | Seam |
|---|---|---|---|
| 88 | `androidx.activity.compose.BackHandler` | Back press to dismiss context menu | `FlashBackHandler` |

### A7. `FlashPairingFlow.kt` (152)

| Line(s) | Android import | Usage | Seam |
|---|---|---|---|
| 152 | `androidx.activity.compose.BackHandler` | Back press to dismiss pairing flow | `FlashBackHandler` |

### A8. `FlashMediaViewerMath` (159–168) — pure Kotlin, no change needed

```kotlin
fun computeInSampleSize(width: Int, height: Int, maxLongEdge: Int = MAX_DECODE_LONG_EDGE): Int {
    var sample = 1
    var longEdge = maxOf(width, height)
    while (longEdge > maxLongEdge) {
        sample *= 2
        longEdge /= 2
    }
    return sample
}
```

This is a pure Kotlin function in `FlashMediaViewer.kt` companion object. It moves to the shim's `computeSampleSize` helper in androidMain but is not an `expect`/`actual` — it's a private utility duplicated in the platform actual.

### A9. `FlashPairingMath` — pure Kotlin, no change needed

The `FlashPairingMath` object in `FlashPairingFlow.kt` is pure Kotlin math. No Android imports. No change needed.

---

## Summary of file changes

### New files created

```
ui/platform-shims/build.gradle.kts                                          (KMP module build)
ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/
    FlashBackHandler.kt                                                     (expect fun)
    FlashClipboard.kt                                                       (common fun — uses LocalClipboard)
    FlashTransientMessage.kt                                                (expect fun — D7a-gated)
    FlashFilePicker.kt                                                      (expect fun — D7b-gated)
    FlashPermission.kt                                                      (expect fun — D7c)
    FlashDecodeImageBitmap.kt                                               (expect fun)
    player/
        FlashAudioPlayer.kt                                                 (expect class)
        FlashVoiceRecorder.kt                                               (expect class)
ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/
    FlashBackHandler.android.kt                                             (actual — BackHandler)
    FlashTransientMessage.android.kt                                        (actual — Toast)
    FlashFilePicker.android.kt                                              (actual — OpenDocument + takePersistableUriPermission + resolveFileMetadata helper)
    FlashPermission.android.kt                                              (actual — RequestPermission)
    FlashDecodeImageBitmap.android.kt                                       (actual — BitmapFactory)
    player/
        FlashAudioPlayer.android.kt                                         (actual — MediaPlayer)
        FlashVoiceRecorder.android.kt                                       (actual — MediaRecorder)
ui/platform-shims/src/desktopMain/kotlin/com/transfer/flash/ui/shims/
    FlashBackHandler.desktop.kt                                             (actual — no-op)
    FlashTransientMessage.desktop.kt                                        (actual — println)
    FlashFilePicker.desktop.kt                                              (actual — JFileChooser + resolveFileMetadata helper)
    FlashPermission.desktop.kt                                              (actual — always true)
    FlashDecodeImageBitmap.desktop.kt                                       (actual — Skia)
    player/
        FlashAudioPlayer.desktop.kt                                         (actual — stub)
        FlashVoiceRecorder.desktop.kt                                       (actual — stub)
```

> `FlashClipboard` (Shim 2) is pure common — no platform actuals. `resolveFileMetadata` (Step 11) is folded into the picker actuals — no standalone file.

### Files modified

| File | Change |
|---|---|
| `ui/chat/build.gradle.kts` | Add `implementation(project(":ui:platform-shims"))` |
| `settings.gradle.kts` | Add `include(":ui:platform-shims")` |
| `gradle/libs.versions.toml` | Add FileKit version + library (if D7b chooses FileKit) |
| `FlashConversationScreen.kt` | Replace 5 import groups + 13 Toast calls + 3 BackHandlers + file picker + clipboard + mic permission + resolveFileMetadata |
| `FlashAudioPlayer.kt` | **Deleted from `ui:chat`** — implementation moved verbatim into `ui:platform-shims` androidMain as `actual class` (API preserved) |
| `FlashVoiceRecorder.kt` | **Deleted from `ui:chat`** — implementation moved verbatim into `ui:platform-shims` androidMain as `actual class` (API preserved) |
| `FlashImageGrid.kt` | Replace `BitmapFactory`/`Uri` imports + `produceState` block with `rememberDecodeImageBitmap` |
| `FlashMediaViewer.kt` | Replace `BitmapFactory`/`Uri`/`BackHandler` imports + decode block + BackHandler with shims |
| `FlashMessageContextMenu.kt` | Replace `BackHandler` import + call |
| `FlashPairingFlow.kt` | Replace `BackHandler` import + call |
| `FlashVoiceMessageCard.kt` | Replace `FlashAudioPlayer(context, uri)` with `rememberFlashAudioPlayer(uri)` |