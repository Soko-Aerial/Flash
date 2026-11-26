# UI Chat Module (`:ui:chat`)

The `:ui:chat` module contains the complete messaging user interface for Flash. Built with Jetpack Compose Multiplatform, it renders conversation lists, message threads, custom message bubbles, interactive composers, voice messaging visualizers, and adaptive layouts (from smartphones to foldables, tablets, and desktop workstations).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:ui-chat:v2.3.0-beta.1")
}
```

---

## 2. Key Screen and Component Architecture

The screens are **stateless**: each takes a UI-state object from `:core:messaging` plus callbacks, and the host (the Android app or the desktop shell) owns the state and routes the callbacks to the engine. They do not take a `FlashChatRepository`. Everything is under `com.transfer.flash.ui.*` in `ui/chat/src/commonMain`.

* **`FlashChatListScreen`** (`ui.chat`): `state: FlashChatListUiState`, `onConversationClick`, optional `onSearchClick` / new-group handlers (a `null` handler hides the icon instead of drawing a dead button). Presence dots, unread badges, multi-selection, archive, pinned and muted rows.
* **`FlashConversationScreen`** (`ui.chat`): required inputs `state: FlashConversationUiState`, `onBack`, `onSendText`, and `onAttachmentClick`, plus optional callbacks (`onSendReply`, `onPersistDraft`, `onStartCall` / `onStartVideoCall`, `onCancelTransfer`, peer-trust and fingerprint hooks, etc.) with inert defaults so previews work. It composes:
  * the header (`FlashChatHeader`, `FlashGroupHeader`; presence, encryption indicators, call buttons),
  * `FlashMessageList` with `FlashMessageBubble`, reactions (`FlashReactionsRow`, `FlashReactionsDock`), quoted replies, pinned banner, jump-to-bottom,
  * `FlashFileMessageCard` (progress, speed, ETA, pause / resume / cancel; swarm availability lines),
  * `FlashComposer` (custom multi-line input, voice recording with slide-to-cancel and lock, attachment sheet and pre-send staging tray, Enter sends / Shift+Enter newline),
  * sheets for message actions and info, peer details, group members, settings and invites, and the media viewer (`FlashMediaViewer` with inline video playback and timeline).
* **Other screens:** `FlashNearbyScreen` (+ `FlashManualConnectDialog`), `FlashTransfersScreen`, `FlashSettingsScreen`, `FlashBottomNav` (`ui.shell`), `FlashPairingFlow`, **Hub screens** (`ui.hub`: `FlashHubScreen`, `FlashHubOrg`, `FlashHubPeople`, `FlashHubNews`, `FlashHubRoute`), and **Radio screens** (`ui.radio`: `FlashRadioGatewayScreen`, `FlashRadioAudioPanel`).
* **Adaptive layout (`ui.adaptive`, `FlashAdaptiveMath`):** width classes at **600 dp (Medium)** and **840 dp (Expanded)**, `FlashNavigationRail` for Medium/Expanded windows, and pane sizing helpers (`listPaneWidthDp`, `clampListPaneWidthDp`, `effectiveListPaneWidthDp`, `SplitterStepDp`). Automatic list-pane width is clamped to 320 to 480 dp (user-dragged width `ListPaneUserMinWidthDp = 280` to `ListPaneUserMaxWidthDp = 720`, detail min `360` dp). The expanded list/detail container with a draggable splitter (`DesktopTwoPane`) lives in the desktop shell (`desktop/.../DesktopAdaptive.kt`), not inside `:ui:chat` (`FlashAdaptiveTwoPane` was removed). Fold-posture support is unimplemented (`docs/migration/ADAPTIVE-UI-PLAN.md`). Not device-verified.

The module uses `:core:messaging` for its state types, along with transfer, security, network, enterprise and theme libraries.
Published runtime dependencies include `:ui:platform-shims` and `:core:radio` for platform and radio UI adapters.
On JVM, the radio dependency also brings in jSerialComm and JNA; the host still owns any [radio integration](../core/core-radio.md).
There is no dependency on `:core:calling`, so the call buttons are plain callbacks.

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
        conversationId = conversationId,
        onBack = onBack,
        onSendText = { text -> chatRepository.sendTextTo(conversationId, text) },
        onSendReply = chatRepository::sendReply,
        onPersistDraft = { text -> chatRepository.saveDraft(conversationId, text) },
        onAttachmentClick = chatRepository::openAttachmentPicker,
        onStartCall = onPlaceVoiceCall,
        onStartVideoCall = onPlaceVideoCall,
    )
}
```

Wrap it in `FlashTheme { ... }` ([ui-theme](ui-theme.md)).

Hub-enabled hosts wire `onConfirmHubKeyChange(peerDeviceId, fingerprint)` to the repository's
suspending `confirmHubKeyChange(peerDeviceId, expectedFingerprint)` in their coroutine scope.
The screen supplies the full fingerprint after the user confirms the displayed comparison code.
