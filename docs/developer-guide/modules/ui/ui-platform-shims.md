# UI Platform Shims Module (`:ui:platform-shims`)

The `:ui:platform-shims` module decouples Jetpack Compose UI code from platform-specific APIs. It encapsulates seven core platform capabilities behind unified multiplatform abstractions (`expect` / `actual`), allowing `:ui:chat` to run identically on Android and Desktop JVM.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:ui-platform-shims:2.0.0-beta")
}
```

---

## 2. Platform Capabilities Abstracted

| Capability | Interface / Class | Android Implementation | JVM Desktop Implementation |
|---|---|---|---|
| **Audio Playback** | [`FlashAudioPlayer`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.kt) | Android `MediaPlayer` / `AudioTrack` | Java Sound API (`javax.sound.sampled`) |
| **Voice Capture** | [`FlashVoiceRecorder`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.kt) | Android `MediaRecorder` | Java Sound `TargetDataLine` WAV recorder |
| **Image Decoding** | [`FlashImageDecoder`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.kt) | Android `BitmapFactory` + EXIF | AWT `ImageIO` + EXIF orientation + JCodec video frames |
| **File Picker** | [`FlashFilePicker`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashFilePicker.kt) | SAF `ActivityResultLauncher` | Swing `JFileChooser` (multi-file enabled) |
| **Clipboard** | `FlashClipboard` | Android `ClipboardManager` | AWT `Toolkit.getDefaultToolkit().systemClipboard` |
| **System Back** | `FlashBackHandler` | AndroidX `BackHandler` | Keyboard `Escape` and navigation stack pops |
| **Permissions** | `FlashPermissionManager` | Android runtime permissions | No-op grant (desktop has OS filesystem access) |

---

## 3. Practical Code Example

```kotlin
import com.transfer.flash.ui.shims.rememberFlashAudioPlayer
import androidx.compose.runtime.Composable
import androidx.compose.material3.Button
import androidx.compose.material3.Text

@Composable
fun AudioMessagePreview(audioUri: String) {
    val player = rememberFlashAudioPlayer()

    Button(onClick = {
        if (player.isPlaying.value) {
            player.pause()
        } else {
            player.play(audioUri)
        }
    }) {
        Text(if (player.isPlaying.value) "Pause" else "Play Voice Message")
    }
}
```
