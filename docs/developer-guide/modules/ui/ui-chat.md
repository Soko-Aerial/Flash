# UI Chat Module (`:ui:chat`)

The `:ui:chat` module contains the complete messaging user interface for Flash. Built with Jetpack Compose Multiplatform, it renders conversation lists, message threads, custom message bubbles, interactive composers, voice messaging visualizers, and adaptive layouts (from smartphones to foldables, tablets, and desktop workstations).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:ui-chat:v2.1.0-beta")
}
```

---

## 2. Key Screen and Component Architecture

The screens are **stateless**: each takes a UI-state object from `:core:messaging` plus callbacks, and the host (the Android app or the desktop shell) owns the state and routes the callbacks to the engine. They do not take a `FlashChatRepository`. Everything is under `com.transfer.flash.ui.*` in `ui/chat/src/commonMain`.

* **`FlashChatListScreen`** (`ui.chat`): `state: FlashChatListUiState`, `onConversationClick`, optional `onSearchClick` / new-group handlers (a `null` handler hides the icon instead of drawing a dead button). Presence dots, unread badges, multi-selection, archive, pinned and muted rows.
* **`FlashConversationScreen`** (`ui.chat`): `state: FlashConversationUiState`, `onBack`, `onSendText`, `onSendReply`, `onPersistDraft`, `onStartCall` / `onStartVideoCall`, `onCancelTransfer`, peer-trust and fingerprint hooks, and many more optional callbacks with inert defaults so previews work. It composes:
  * the header (`FlashChatHeader`, `FlashGroupHeader`; presence, encryption indicators, call buttons),
  * `FlashMessageList` with `FlashMessageBubble`, reactions (`FlashReactionsRow`, `FlashReactionsDock`), quoted replies, pinned banner, jump-to-bottom,
  * `FlashFileMessageCard` (progress, speed, ETA, pause / resume / cancel; swarm availability lines),
  * `FlashComposer` (custom multi-line input, voice recording with slide-to-cancel and lock, attachment sheet and pre-send staging tray, Enter sends / Shift+Enter newline),
  * sheets for message actions and info, peer details, group members, settings and invites, and the media viewer.
* **Other screens:** `FlashNearbyScreen` (+ `FlashManualConnectDialog`), `FlashTransfersScreen`, `FlashSettingsScreen`, `FlashBottomNav` (`ui.shell`), `FlashPairingFlow`.
* **Adaptive layout (`ui.adaptive`, `FlashAdaptiveMath`):** width classes at **600 dp (Medium)** and **840 dp (Expanded)**; the two-pane list/detail layout (`FlashAdaptiveTwoPane`) and the navigation rail (`FlashNavigationRail`) are available from Medium up. The list pane is clamped to 320 to 480 dp (the detail pane needs at least 480 dp) and a reading bubble is capped at 580 dp. There is no draggable splitter yet and no fold-posture support (`docs/migration/ADAPTIVE-UI-PLAN.md`). Not device-verified.

The module depends on `:core:messaging` (for the state types), `:core:transfer`, `:core:security`, `:ui:theme` and `:ui:platform-shims`; it does not depend on `:core:calling`, so the call buttons are plain callbacks.

---

## 3. Practical Code Example

```kotlin
import androidx.compose.runtime.*
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.ui.chat.FlashConversationScreen

@Composable
fun ConversationRoute(
    chatRepository: FlashChatRepository,
    conversationId: String,
    onBack: () -> Unit,
    onPlaceVoiceCall: () -> Unit,
    onPlaceVideoCall: () -> Unit,
) {
    LaunchedEffect(conversationId) { chatRepository.openConversation(conversationId) }
    DisposableEffect(Unit) { onDispose { chatRepository.closeConversation() } }

    val state by chatRepository.conversationState.collectAsState()

    FlashConversationScreen(
        state = state,
        onBack = onBack,
        onSendText = chatRepository::sendText,
        onSendReply = chatRepository::sendReply,
        onPersistDraft = chatRepository::saveDraft,
        onStartCall = onPlaceVoiceCall,
        onStartVideoCall = onPlaceVideoCall,
    )
}
```

Wrap it in `FlashTheme { ... }` ([ui-theme](ui-theme.md)).
