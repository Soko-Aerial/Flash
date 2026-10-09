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

> **Verified against the code 2026-10-09.** The earlier sample constructed `DesktopEngine(scope, peerId, displayName)`, called
> `engine.start()` and passed a repository into the screens; none of that exists. The screens take **state and callbacks**, and the
> supported factory for a third-party host is `Flash.create` (Android) or `FlashDesktop.create` (JVM, see below). The sample below is an
> illustration (not compiled by CI); the real hosts are `app/.../MainActivity.kt` with `DiscoveryEngineHolder`, and `desktop/.../DesktopMain.kt` with `DesktopEngine`.

## 2. End-to-End Kotlin Code Sample (Android, Compose)

```kotlin
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.transfer.flash.core.engine.Flash
import com.transfer.flash.core.engine.FlashConfig
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var engine: FlashEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 1. Build the engine off the main thread (it opens the encrypted database).
            var ready by remember { mutableStateOf<FlashEngine?>(null) }
            LaunchedEffect(Unit) {
                ready = withContext(Dispatchers.IO) {
                    Flash.create(applicationContext, FlashConfig(displayName = "Pixel-Alpha")).also { engine = it }
                }
            }
            val e = ready ?: return@setContent

            // 2. Render the stateless screens from the engine's state.
            FlashTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var openId by remember { mutableStateOf<String?>(null) }
                    val chats = e.chats

                    if (openId == null) {
                        val list by chats.chatListState.collectAsState()
                        FlashChatListScreen(
                            state = list,
                            onConversationClick = { id -> chats.openConversation(id); openId = id },
                        )
                    } else {
                        val conversation by chats.conversationState.collectAsState()
                        FlashConversationScreen(
                            state = conversation,
                            onBack = { chats.closeConversation(); openId = null },
                            onSendText = chats::sendText,
                            onSendReply = chats::sendReply,
                            onPersistDraft = chats::saveDraft,
                            onAttachmentClick = { chats.openAttachmentPicker() },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        engine?.close()          // cancels the shared scope and releases NSD / sockets / database
        super.onDestroy()
    }
}
```

Sending a file to a *discovered* device is a repository call, not a screen callback:
`engine.transfers.sendFile(targetDevice = endpoint.device, fileUri, displayName, fileSize)` (see the [beginner guide](../getting-started/beginner-guide.md)).
Pairing, notifications, the foreground service and call handling are host work that this sample leaves out; a shipping host also needs the
manifest entries and runtime permissions in `docs/android-platform-notes.md`.

**Desktop.** The JVM engine, `DesktopEngine`, and the window shell are the `:desktop` application module, not libraries, so a desktop host today
is that module (or a project that puts its classes on the classpath and calls `FlashDesktop.create(FlashConfig(...))`, then `engine.awaitReady()`).
