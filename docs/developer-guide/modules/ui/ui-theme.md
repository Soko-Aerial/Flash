# UI Theme Module (`:ui:theme`)

The `:ui:theme` module provides the visual design system, color palettes, typography scales, shape tokens, motion policies, and custom vector icons for Flash. It is built purely on Jetpack Compose and Compose Multiplatform without depending on heavyweight stock Material chat templates.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:ui-theme:v2.1.0-beta")
}
```

---

## 2. Design System Architecture

Flash uses a custom design system, entered through `FlashTheme` (package `com.transfer.flash.ui.theme`, [source](../../../../ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashTheme.kt)):

* **Colors (`FlashColors`):** semantic tokens such as `accentPrimary`, `accentSecondary`, `textPrimary` / `textSecondary` / `textTertiary` / `textOnAccent` / `textLink` / `textError` / `textSuccess`, `backgroundApp`, `backgroundChat`, `backgroundSurface` (plus `Subtle` / `Strong`), `borderSubtle` / `borderDefault` / `borderStrong`, the chat set (`chatBgIncoming`, `chatBgOutgoing`, `chatTextIncoming`, `chatTextOutgoing`, `chatTextTimestamp`, ...), `composer*`, `sheetSurface`, `scrim`, avatar palettes and `statusOnline` / `statusOffline` / `statusTransfer`. `FlashColors.dark()` and `FlashColors.light()` build the two palettes; the accent is the "Pulse" teal family.
* **Typography (`FlashTypography`):** `display`, `headingLarge/Medium/Small`, `bodyDefault/Emphasis`, `captionDefault/Emphasis`, `metadataDefault/Emphasis`, `numericDefault/Emphasis` (all `TextStyle`).
* **Shapes (`FlashShapes`):** radius tokens `radius2` ... `radius24`, `radiusFull`, and `bubbleTailSize`.
* **Motion (`FlashMotion`):** spring and duration tokens; `reduceMotion` collapses durations to 0. `rememberFlashMotion()` derives it from the platform setting; `FlashPerformanceMode.reduceMotion` feeds the same policy on low tiers.
* **Spacing, dimensions, elevation, interaction, feedback:** `FlashSpacing`, `FlashDimensions`, `FlashElevation`, `FlashInteraction`, `FlashFeedback` (haptics) and `FlashSounds`.
* **Icons (`FlashIcons` in `com.transfer.flash.ui.icons`):** a set of drawable-backed `FlashIconSpec`s (`Send`, `Attach`, `Camera`, `Microphone`, `Call`, `VideoCall`, `Delivered`, `Read`, `Encryption`, `ScreenShare`, `WifiDirect`, `Bluetooth`, ... about 60), drawn with `FlashIcon(icon, state = FlashIconState.Default | Active | Disabled | Error)`. (`WifiDirect` and `Bluetooth` are only glyphs; there is no Wi-Fi Direct transport.)
* **Also here:** `FlashAvatar` (`com.transfer.flash.ui.avatar`), the Ink launch splash (`FlashLaunchSplashOverlay`, UI-056 / ADR-080, not device-verified) and the brand animation.

`FlashTheme.colors`, `.typography`, `.motion` and `.minimalChrome` read the current values. There is **no `FlashTheme.shapes`**; use the `FlashShapes` object directly.

---

## 3. Practical Code Example

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import com.transfer.flash.ui.theme.FlashTheme

@Composable
fun App() {
    FlashTheme(
        darkTheme = true,         // default: isSystemInDarkTheme()
        dynamicAccent = false,    // true = take the accent from the platform (Android 12+ Material You)
        hapticsEnabled = true,
        minimalChrome = false,    // true on LOW / MEDIUM performance tiers
    ) {
        Text(
            text = "Welcome to Flash UI",
            style = FlashTheme.typography.headingLarge,
            color = FlashTheme.colors.textPrimary,
        )
    }
}
```
