# UI Chat Module (`:ui:chat`)

The `:ui:chat` module contains the complete messaging user interface for Flash. Built with Jetpack Compose Multiplatform, it renders conversation lists, message threads, custom message bubbles, interactive composers, voice messaging visualizers, and adaptive layouts (from smartphones to foldables, tablets, and desktop workstations).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:ui-chat:2.0.0-beta")
}
```

---

## 2. Key Screen and Component Architecture

* **[`FlashChatListScreen`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListScreen.kt):** Lists active threads, peer presence indicators (Online / Offline / Typing dots), unread badges, multi-selection mode, archive actions, and live search.
* **[`FlashConversationScreen`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt):** Active conversation screen rendering:
  * **Header:** Peer status, encryption lock indicators, security code sheet trigger, and voice/video calling buttons.
  * **Message List (`FlashMessageList`):** Inverted virtualized `LazyColumn` rendering incoming/outgoing bubbles with delivery ticks, reactions, and jump-to-bottom pill.
  * **File Cards (`FlashFileMessageCard`):** Interactive transfer cards showing real-time progress circles, speed, ETA, and in-bubble Pause/Resume/Cancel controls.
  * **Composer (`FlashComposer`):** Custom multi-line text input, voice recording slide-to-cancel pill, emoji picker, attachment action sheet, and hardware keyboard shortcuts (Enter sends, Shift+Enter newlines).
* **Adaptive Dual-Pane Layouts ([`FlashNavigationRail`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/adaptive/FlashNavigationRail.kt) & [`FlashAdaptiveLayouts`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/adaptive/FlashAdaptiveLayouts.kt)):**
  * Automatically transforms to a 68dp slim Navigation Rail and two-pane layout when display width $\ge 840$dp (Desktop and Tablets/Foldables).
  * Clamps list pane width (320–480dp) and caps reading bubble width (`580.dp`) for optimal wide-screen ergonomics.

---

## 3. Practical Code Example

```kotlin
import androidx.compose.runtime.Composable
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.ui.chat.FlashConversationScreen

@Composable
fun ConversationRoute(
    chatRepository: FlashChatRepository,
    conversationId: String,
    onBack: () -> Unit
) {
    FlashConversationScreen(
        conversationId = conversationId,
        repository = chatRepository,
        onBack = onBack,
        onPlaceVoiceCall = { peerId -> /* trigger calling */ },
        onPlaceVideoCall = { peerId -> /* trigger calling */ }
    )
}
```
