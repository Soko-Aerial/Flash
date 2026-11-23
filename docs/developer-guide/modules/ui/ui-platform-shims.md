# UI Platform Shims Module (`:ui:platform-shims`)

The `:ui:platform-shims` module decouples Jetpack Compose UI code from platform-specific APIs. It encapsulates nine platform capabilities behind unified multiplatform abstractions (`expect` / `actual`), allowing `:ui:chat` to run identically on Android and Desktop JVM.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:ui-platform-shims:v2.3.0-beta.1")
}
```

---

## 2. Platform Capabilities Abstracted

Each capability is a small common interface (or `expect` composable) with an Android and a JVM implementation; callers use the `remember...` composable. Package `com.transfer.flash.ui.shims`.

| Capability | Common API | Android | JVM desktop |
|---|---|---|---|
| **Audio playback** | `FlashAudioPlayer` (`play`, `pause`, `seekTo(ms)`, `setSpeed`, `positionMs()`, `isPlaying()`, `release()`), `rememberFlashAudioPlayer(uri): FlashAudioPlayer?` | `MediaPlayer` | `javax.sound.sampled` `Clip`; AAC voice notes decoded with JCodec; pitch-preserving WSOLA time-scaling (`timeScalePcm`) for 1x / 1.5x / 2x playback |
| **Voice capture** | `FlashVoiceRecorder` (`start`, `setPaused(paused)`, `maxAmplitude`, `stop`, `cancel`), `rememberFlashVoiceRecorder()` | `MediaRecorder` (AAC in MPEG-4, into the app cache) | `TargetDataLine` recorder |
| **Image decoding** | `FlashImageDecoder` (`decode`, long-edge constants `TILE_LONG_EDGE_ULTRA_LOW_PX = 160`, `VIEWER_LONG_EDGE_ULTRA_LOW_PX = 480`, `TILE_LONG_EDGE_MINIMAL_PX = 480`, `TILE_LONG_EDGE_PX = 720`), `rememberFlashImageDecoder()` | `BitmapFactory` + EXIF (`RGB_565` when `lowColorDepth = true`) | AWT `ImageIO` |
| **File picking** | `FlashPickedFile`, `FlashFilePickerLauncher`, `rememberFlashFilePickerLauncher(...)` | Storage Access Framework | Swing `JFileChooser` |
| **Camera capture** | `FlashCameraCaptureLauncher`, `rememberFlashCameraCaptureLauncher(...)` | system camera intent | `JFileChooser` stand-in (no camera capture on desktop) |
| **Clipboard** | `FlashClipboard.copy(text)`, `rememberFlashClipboard()` | Compose `LocalClipboardManager` | same (common code) |
| **System back** | `FlashBackHandler(enabled, onBack)` | AndroidX back handling | Intentional no-op (`enabled` and `onBack` unused; keyboard/window dismissal belongs to the desktop shell) |
| **Permissions** | `FlashPermission` (`Microphone`, `Camera`), `FlashPermissionRequester`, `rememberFlashPermissionRequester()` | runtime permissions | always granted |
| **Video surface** | `FlashVideoSurface(uri, isPlaying, isMuted = false, seekToMs = null, onPlaybackStateChanged, onError, onSeekHandled, modifier, onFirstFrame, onBuffering, onHostPaused)` | platform player surface (`MediaPlayer` + `SurfaceView`) | Windows Media Foundation JNI bridge (`WindowsVideoPlayer`, ADR-364); Linux/macOS report unavailable so the viewer offers "Open in system player" |

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
