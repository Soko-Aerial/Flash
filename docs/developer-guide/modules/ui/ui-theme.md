# UI Theme Module (`:ui:theme`)

The `:ui:theme` module provides the visual design system, color palettes, typography scales, shape tokens, motion policies, and custom vector icons for Flash. It is built purely on Jetpack Compose and Compose Multiplatform without depending on heavyweight stock Material chat templates.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:ui-theme:2.0.0-beta")
}
```

---

## 2. Design System Architecture

Flash uses a custom design system tokenized under [`FlashTheme`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashTheme.kt):

* **Color Tokens ([`FlashColors`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashColors.kt)):** Semantic tokens for `brandPrimary` (Flash Pulse Teal `#2DD4BF`), `backgroundCanvas`, `backgroundSurface`, `textPrimary`, `textSecondary`, `borderSubtle`, `bubbleIncoming`, and `bubbleOutgoing`.
* **Typography ([`FlashTypography`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashTypography.kt)):** Scales for headers, message body text, metadata timestamps, and badges.
* **Shape Tokens ([`FlashShapes`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashShapes.kt)):** Distinct asymmetric message bubble shapes (tapered tail on outgoing vs. incoming), squircles for medallions, and pills for badges.
* **Motion Tokens ([`FlashMotion`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashMotion.kt)):** Physics-based spring animations with automatic reduce-motion overrides for low-performance devices or accessibility settings.
* **Icons ([`FlashIcons`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashIcons.kt)):** Handcrafted vector icons (Bolt, Tray, Send, Attachment, Call, Video, Mic, CheckDouble, Lock, Play, Pause, More).

---

## 3. Practical Code Example

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.FlashColors

@Composable
fun App() {
    FlashTheme(
        darkTheme = true, // Or system-driven
        dynamicColor = false
    ) {
        // Access semantic design tokens anywhere in the Compose hierarchy:
        val colors = FlashTheme.colors
        val typography = FlashTheme.typography
        val shapes = FlashTheme.shapes

        Text(
            text = "Welcome to Flash UI",
            style = typography.titleLarge,
            color = colors.textPrimary
        )
    }
}
```
