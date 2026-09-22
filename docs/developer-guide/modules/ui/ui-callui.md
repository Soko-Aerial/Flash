# UI Calling Module (`:ui:callui`)

The `:ui:callui` module provides the user interface for voice and video calling. It renders incoming call alerts, active full-screen video grids, camera switching controls, microphone mute buttons, and participant state overlays.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:ui-callui:2.0.0-beta")
}
```

---

## 2. Key Screen and Component Architecture

* **[`FlashCallScreen`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/callui):** Full-screen calling experience.
  * **Video Surface Rendering:** Renders local and remote WebRTC `VideoTrack`s using hardware-accelerated video viewports.
  * **Floating Controls Bar:** Translucent pill bar featuring Mute Microphone, Toggle Camera, Switch Camera (front/back), Speakerphone Output, and End Call actions.
  * **Call State Presentation:** Displays connecting spinners, ringing animations, call elapsed time, and network quality warning chips.

---

## 3. Practical Code Example

```kotlin
import androidx.compose.runtime.Composable
import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.ui.callui.FlashCallScreen

@Composable
fun CallOverlay(calling: FlashCalling) {
    FlashCallScreen(
        calling = calling,
        onDismiss = { /* Call finished */ }
    )
}
```
