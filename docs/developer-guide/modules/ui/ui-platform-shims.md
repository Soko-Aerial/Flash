# UI Platform Shims Module (`:ui:platform-shims`)

The `:ui:platform-shims` module decouples Jetpack Compose UI code from platform-specific APIs. It encapsulates nine platform capabilities behind unified multiplatform abstractions (`expect` / `actual`), allowing `:ui:chat` to run identically on Android and Desktop JVM.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:ui-platform-shims:v2.1.0-beta")
}
```

---

## 2. Platform Capabilities Abstracted

Each capability is a small common interface (or `expect` composable) with an Android and a JVM implementation; callers use the `remember...` composable. Package `com.transfer.flash.ui.shims`.

| Capability | Common API | Android | JVM desktop |
|---|---|---|---|
| **Audio playback** | `FlashAudioPlayer` (`play`, `pause`, `seekTo(ms)`, `setSpeed`, `positionMs()`, `isPlaying()`, `release()`), `rememberFlashAudioPlayer(uri): FlashAudioPlayer?` | `MediaPlayer` | `javax.sound.sampled` `Clip`; AAC voice notes are decoded with JCodec |
| **Voice capture** | `FlashVoiceRecorder`, `rememberFlashVoiceRecorder()` | `MediaRecorder` (AAC in MPEG-4, into the app cache) | `TargetDataLine` recorder |
| **Image decoding** | `FlashImageDecoder`, `rememberFlashImageDecoder()` | `BitmapFactory` + EXIF | AWT `ImageIO` |
| **File picking** | `FlashPickedFile`, `FlashFilePickerLauncher`, `rememberFlashFilePickerLauncher(...)` | Storage Access Framework | Swing `JFileChooser` |
| **Camera capture** | `FlashCameraCaptureLauncher`, `rememberFlashCameraCaptureLauncher(...)` | system camera intent | `JFileChooser` stand-in (no camera capture on desktop) |
| **Clipboard** | `FlashClipboard.copy(text)`, `rememberFlashClipboard()` | Compose `LocalClipboardManager` | same (common code) |
| **System back** | `FlashBackHandler(enabled, onBack)` | AndroidX back handling | Escape key and navigation pops |
| **Permissions** | `FlashPermission` (`Microphone`, `Camera`), `FlashPermissionRequester`, `rememberFlashPermissionRequester()` | runtime permissions | always granted |
| **Video surface** | `FlashVideoSurface(uri, isPlaying, isMuted, seekToMs, onPlaybackStateChanged, onError, modifier)` | platform player surface | desktop player surface |

---

## 3. Practical Code Example

```kotlin
import com.transfer.flash.ui.shims.rememberFlashAudioPlayer
import androidx.compose.runtime.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text

@Composable
fun AudioMessagePreview(audioUri: String) {
    val player = rememberFlashAudioPlayer(audioUri) ?: return   // null when the file cannot be opened
    var playing by remember { mutableStateOf(false) }

    Button(onClick = {
        if (player.isPlaying()) player.pause() else player.play()
        playing = player.isPlaying()
    }) {
        Text(if (playing) "Pause" else "Play Voice Message")
    }
}
```
