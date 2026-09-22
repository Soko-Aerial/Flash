# Core Messaging Module (`:core:messaging`)

The `:core:messaging` module implements the peer-to-peer chat repository, message delivery tracking, typing indicator timeouts, read receipts, and unicode reactions.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-messaging:2.0.0-beta")
}
```

---

## 2. Wire Protocol Framing for Messaging

The chat repository communicates over the network using light, line-delimited key-value text frames:

* `FLASH_MSG id=<id> body=<text> [replyTo=<id>] [replyPreview=<text>]`
* `FLASH_RCPT id=<id> status=delivered`
* `FLASH_READ id=<id> readAt=<timestamp>`
* `FLASH_REACT id=<id> emoji=<unicode> [remove=true]`
* `FLASH_TYPING isTyping=true|false`
* `FLASH_ACTION action=delete|deleteForEveryone id=<id>`

When paired with end-to-end encryption keys ([`:core:security`](core-security.md)), these frames are encapsulated inside `FLASH_SEC` AES-256-GCM payloads automatically.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashChatRepository`
Located in [`com.transfer.flash.core.messaging.FlashChatRepository`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/FlashChatRepository.kt):

```kotlin
public interface FlashChatRepository {
    /** Reactive state of the active chat list and unread counts. */
    public val chatListState: StateFlow<FlashChatListState>

    /** Observes a specific conversation thread with real-time updates. */
    public fun observeConversation(conversationId: String): Flow<FlashConversationState>

    /** Sends a plain text message. */
    public suspend fun sendText(conversationId: String, text: String): FlashResult<String>

    /** Sends a threaded reply to an existing message. */
    public suspend fun sendReply(conversationId: String, text: String, replyToId: String, replyToPreview: String): FlashResult<String>

    /** Sends an inline file attachment message linked to a transfer. */
    public suspend fun sendAttachment(conversationId: String, transferId: String, fileName: String, fileSize: Long, mimeType: String): FlashResult<String>

    /** Adds or removes an emoji reaction on a message. */
    public suspend fun toggleReaction(messageId: String, emoji: String)

    /** Broadcasts local typing indicator state with automatic 6-second inactivity TTL. */
    public suspend fun setTyping(isTyping: Boolean)

    /** Searches message bodies within a conversation or globally across all conversations. */
    public fun searchConversationMessages(conversationId: String, query: String): Flow<List<FlashMessageUi>>
}
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.messaging.FlashChatRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun openChatThread(repository: FlashChatRepository, peerId: String) {
    scope.launch {
        // Observe live messages
        repository.observeConversation(peerId).collect { conversation ->
            println("Active Chat with: ${conversation.header.peerName} (${conversation.header.presence})")
            conversation.messages.forEach { msg ->
                println(" [${msg.timestamp}] ${msg.senderName}: ${msg.content}")
            }
        }
    }

    // Send a reply
    scope.launch {
        repository.sendText(peerId, "Hello! I am ready for the file transfer.")
    }
}
```
