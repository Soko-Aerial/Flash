# Practical Examples: Full-Stack App Integration

This guide demonstrates how to integrate the complete Flash stack—combining discovery, pairing, encrypted messaging, and chunked file transfer—into an end-to-end Kotlin application.

---

## 1. Complete Integration Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                    FlashEngine Instance                     │
├─────────────────┬─────────────────┬─────────────────────────┤
│ Discovery       │ Security        │ Transfers & Messaging   │
│ - Publishes mDNS│ - Pairing v2    │ - Outbound chunking     │
│ - Listens LAN   │ - AES-256-GCM   │ - Inbound sink writing  │
└────────┬────────┴────────┬────────┴────────────┬────────────┘
         ▼                 ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│                       Compose UI Layer                      │
│ - FlashChatListScreen & FlashConversationScreen (stateless) │
│ - FlashNavigationRail / FlashAdaptiveTwoPane (wide screens) │
└─────────────────────────────────────────────────────────────┘
```

---

The host creates and closes the engine as shown in the [beginner guide](../getting-started/beginner-guide.md).
Pass the live engine to this Compose adapter. The host supplies file routing for the selected conversation.
The consumer needs `core-engine`, `ui-chat`, `ui-theme`, the Compose runtime and the Kotlin Compose compiler plugin.

## 2. Binding chat state to Compose

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.theme.FlashTheme
@Composable
fun FlashChatHost(
    engine: FlashEngine,
    sendPickedFile: (conversationId: String, uri: String, displayName: String, size: Long) -> Unit,
) {
    val chats = engine.chats
    var openId by remember(engine) { mutableStateOf<String?>(null) }
    val conversationId = openId
    FlashTheme {
        if (conversationId == null) {
            val list by chats.chatListState.collectAsState()
            FlashChatListScreen(
                state = list,
                onConversationClick = { id ->
                    chats.openConversation(id)
                    openId = id
                },
            )
        } else {
            val conversation by chats.conversationState.collectAsState()
            FlashConversationScreen(
                state = conversation,
                conversationId = conversationId,
                onBack = {
                    chats.closeConversation()
                    openId = null
                },
                onSendText = { text -> chats.sendTextTo(conversationId, text) },
                onSendReply = chats::sendReply,
                onPersistDraft = { text -> chats.saveDraft(conversationId, text) },
                onAttachmentClick = chats::openAttachmentPicker,
                onSendFile = { uri, name, size -> sendPickedFile(conversationId, uri, name, size) },
            )
        }
    }
}
```

> **Note on `onAttachmentClick` vs `onSendFile`:** `FlashConversationScreen` wires the platform file picker shim (`rememberFlashFilePickerLauncher`) internally and invokes `onSendFile(uri, displayName, size)` once the user picks a file; `RealFlashChatRepository.openAttachmentPicker()` is a no-op host hook (required parameter `onAttachmentClick`).

For a direct chat, resolve the paired peer and call
`engine.transfers.sendFile(targetDevice = endpoint.device, fileUri, displayName, fileSize)` as shown in
the [beginner guide](../getting-started/beginner-guide.md). On success, call `engine.chats.sendAttachment`
with the returned transfer ID to display the local attachment bubble. For a group, use the host's
group file sender so the announcement and per-recipient transfer stay linked.
The host owns the pairing consent UI, notifications, foreground service and call integration. To add pairing,
take `engine.pairing`, call `beginPair(peerId, peerName)`, collect the comparison code and pass the user's
decision back with `acceptLocal()` or `declineLocal()`. `Flash.create` handles the wire exchange and trust
writes. See the [pairing guide](../guides/pairing-and-trust.md) and the
[Android platform notes](../../android-platform-notes.md) for permissions and service requirements.

**Desktop.** The JVM engine, `DesktopEngine`, and the window shell live in the `:desktop` application module, not a published library, so a desktop host today
is that module (or a JVM project that places `:desktop`'s classes on its classpath — or registers its own factory via `FlashDesktop.registerFactory` — calls `FlashDesktop.create(FlashConfig(...))`, and suspends on `engine.awaitReady()`).
