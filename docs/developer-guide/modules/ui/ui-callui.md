# UI Calling Module (`:ui:callui`)

The `:ui:callui` module provides the user interface for voice and video calling. It renders incoming call alerts, active full-screen video, the group video grid and strip, call controls, participant state overlays, screen-share viewing, and the push-to-talk session card.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:ui-callui:v2.1.0-beta")
}
```

---

## 2. Key Screen and Component Architecture

Package `com.transfer.flash.ui.calling`. Like `:ui:chat`, the screens are **stateless**: the host passes a `FlashCallUiState`, the media handle and callbacks, and wires the callbacks to its `FlashCalling` (it does not pass `FlashCalling` itself). The module `api`-depends on `:core:calling` and `:core:ptt`.

* **`FlashCallScreen`:** the full-screen call UI. Required inputs: `state: FlashCallUiState`, `session: FlashCallMedia?` and the callbacks `onAccept`, `onDecline`, `onHangUp`, `onToggleMute`, `onToggleSpeaker`, `onToggleCamera`, `onSwitchCamera`, `onDismiss`. Optional ones cover video focus and "show fewer videos", "send smaller video", audio route selection (`FlashCallAudioRoutes`), picture-in-picture, hand raise and reactions, data saver, `onUpgradeToVideo` (1:1 voice to video) and a `FlashCallShareHost` for screen sharing.
  * Local and remote video through `FlashCallVideoSurface` (Android / desktop implementations).
  * The control dock (`FlashCallControlDock`), health banner (`FlashCallHealthBanner`), ringing and connecting states, elapsed time, and a speaking ripple on avatars.
  * Group calls: `FlashGroupVideoGrid` / `FlashGroupVideoStrip` (tiles compose a native surface only while they have a picture; the active speaker gets a ring).
* **`PttSessionOverlayContent`:** the shared push-to-talk session card used by both hosts (with `pttPressOutcomeMessage` and `formatPttElapsed`).

---

## 3. Practical Code Example

```kotlin
import androidx.compose.runtime.*
import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.ui.calling.FlashCallScreen

@Composable
fun CallOverlay(calling: FlashCalling, onDismiss: () -> Unit) {
    val call by calling.activeCall.collectAsState()
    val scope = rememberCoroutineScope()
    val state = call ?: return

    FlashCallScreen(
        state = state,
        session = calling.media,
        onAccept = { scope.launch { calling.accept(audioOnly = !state.video) } },
        onDecline = { scope.launch { calling.decline() } },
        onHangUp = { scope.launch { calling.hangUp() } },
        onToggleMute = { calling.toggleMute() },
        onToggleSpeaker = { /* calling.setSpeaker(...) or route through your audio router */ },
        onToggleCamera = { calling.toggleCamera() },
        onSwitchCamera = { scope.launch { calling.switchCamera() } },
        onDismiss = onDismiss,
    )
}
```

The real hosts also run a call foreground service (Android) and an audio-routing helper; see `app/.../calling/FlashCallScreenSupport.kt`.
