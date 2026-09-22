# Practical Examples: Full-Stack App Integration

This guide demonstrates how to integrate the complete Flash stack—combining discovery, pairing, encrypted messaging, and chunked file transfer—into an end-to-end Kotlin application.

---

## 1. Complete Integration Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                    FlashEngine Instance                     │
├─────────────────┬─────────────────┬─────────────────────────┤
│ Discovery       │ Security        │ Transfers & Messaging   │
│ - Publishes mDNS│ - Pairwise ECDH │ - Outbound chunking     │
│ - Listens LAN   │ - AES-256-GCM   │ - Inbound sink writing  │
└────────┬────────┴────────┬────────┴────────────┬────────────┘
         ▼                 ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│                       Compose UI Layer                      │
│ - FlashNavigationRail                                       │
│ - FlashChatListScreen & FlashConversationScreen             │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. End-to-End Kotlin Code Sample

```kotlin
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.desktop.DesktopEngine
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 1. Initialize the unified engine
    val engine: FlashEngine = DesktopEngine(
        scope = appScope,
        peerId = "peer_station_1",
        displayName = "Desktop-Station"
    )
    engine.start()

    // 2. Render Compose Multiplatform UI
    androidx.compose.ui.window.singleWindowApplication(title = "Flash Station") {
        FlashTheme {
            var selectedConversationId by remember { mutableStateOf<String?>(null) }

            Surface(modifier = Modifier.fillMaxSize()) {
                val currentPeer = selectedConversationId
                if (currentPeer == null) {
                    // Chat list view
                    FlashChatListScreen(
                        repository = engine.chats,
                        onConversationClick = { conversationId ->
                            selectedConversationId = conversationId
                        }
                    )
                } else {
                    // Active conversation view
                    FlashConversationScreen(
                        conversationId = currentPeer,
                        repository = engine.chats,
                        onBack = { selectedConversationId = null },
                        onSendFile = { peerId, uri, name, size ->
                            appScope.launch {
                                engine.transfers.sendFile(peerId, uri, name, size)
                            }
                        }
                    )
                }
            }
        }
    }
}
```
