# Core Messaging Module (`:core:messaging`)

The `:core:messaging` module implements the peer-to-peer chat repository, message delivery tracking, typing indicators, read receipts, unicode reactions, **signed groups** (membership, roster, vouching, secrets and invites), group history sync, and Hub relay / Hub group E2EE bridging (`HubChatBridge` and `HubGroupE2eeCoordinator`). Push-to-talk wire codecs (`FLASH_PTT`, `FLASH_PTSS`) live in [`:core:ptt`](core-ptt.md), not here.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Soko-Aerial.Flash:core-messaging:v2.3.0-beta.1")
}
```

The messaging artifact exports `core-enterprise` so Hub directory and join results are visible
on the consumer's compile classpath. These Hub integration types can require `@FlashInternalApi`
opt-in. See the [messaging API reference](../../../architecture/public-api.md#6-core-messaging-module-coremessaging)
for the current areas and the linked source declarations for signatures.

---

## 2. Wire Protocol Framing for Messaging

Frames are single text lines: a prefix followed by space-separated `key=value` fields, with `%`, space and `=` escaped as `%25`, `%20`, `%3D` (`FlashTextFraming`). The prefix families, all defined in `protocol/`:

| Family | Prefixes |
|---|---|
| Direct chat (`ChatTextFrameCodec`) | `FLASH_MSG` (fields `localId`, `conversationId`, `senderId`, `senderName`, `sentAt`, `text`, `replyToId`, `replyToPreview`), `FLASH_RCPT` (`messageId`, `conversationId`, `memberId`, `deliveredAt`), `FLASH_READ` (`conversationId`, `memberId`, `upToMessageId`, `readAt`), `FLASH_REACT` (`messageId`, `memberId`, `emoji`, `isAdded`), `FLASH_TYPING` (`conversationId`, `memberId`, `memberName`, `isTyping`, `timestampMs`) |
| Direct delete | `FLASH_DACT` (delete for everyone, `DirectMessageActionCodec`) |
| Groups | `FLASH_GROUP`, `FLASH_GMSG`, `FLASH_GRCPT`, `FLASH_GREAD`, `FLASH_GACT`, `FLASH_GSYNC` (history sync), `FLASH_GMEDIA`, `FLASH_GMEM` (membership); group ids starting `g2-` are signed v2 groups |

(Push-to-talk `FLASH_PTT` and `FLASH_PTSS` frames are defined and decoded in [`:core:ptt`](core-ptt.md).)

### Hub Relay & Group E2EE Bridging (`...core.messaging.hub`)
* **`HubChatBridge`** (`@FlashInternalApi`): connects `RealFlashChatRepository` to `HubRelayPort` for Hub-relayed 1:1 chat (`CHAT_FRAME`), sealed envelopes (`HubSealing.ENVELOPE_TYPE`), sealed Hub file tickets (`HUB_FILE`), and group frames (`GROUP_FRAME`), handling deduplication, sender standing/trust checks, stale-message rejection, and durable-store-before-acknowledge.
* **`HubGroupE2eeCoordinator`** (`@FlashInternalApi`): manages Hub-linked group epoch keys (`GroupEpochKeyStore`), key rotations and distribution (`GroupKeyDistPayload`), fail-closed compatibility status (`GroupE2eeCompatStatus` / `GroupE2eeGroupState`), and sealing/unsealing under `GROUP_E2EE_MODE_REQUIRED` or `GROUP_E2EE_MODE_HUB_INSPECTABLE`.

A frame that carries a recognised prefix but a missing key field is dropped, never passed on to the next parser. When a session has an E2E key the frame is wrapped in a `FLASH_SEC` payload ([`:core:security`](core-security.md)). The authoritative wire spec, including the group wire and membership v1 sections, is `docs/protocol.md`.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashChatRepository`

Located in [`com.transfer.flash.core.messaging.FlashChatRepository`](../../../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/FlashChatRepository.kt).
It is a *UI-state* repository: you **open** one conversation at a time and act on it. Most members have no-op defaults so a small
implementation (`EmptyFlashChatRepository`, `SampleFlashChatRepository`) stays easy; the real one is `RealFlashChatRepository`.

```kotlin
public interface FlashChatRepository {
    public val chatListState: StateFlow<FlashChatListUiState>          // items, archivedItems, selection
    public val conversationState: StateFlow<FlashConversationUiState>  // header, messages, draftText, pins, members, ongoingCall...

    public fun openConversation(conversationId: String)
    public fun closeConversation()
    public fun sendText(text: String)                                  // into the open conversation
    public fun sendTextTo(conversationId: String, text: String)
    public fun sendReply(text: String, replyToId: String, replyToPreview: String)
    public fun sendAttachment(...)
    public fun toggleReaction(messageId: String, emoji: String)
    public fun setTyping(isTyping: Boolean)                            // auto-expires
    public fun saveDraft(text: String)
    public fun retryMessage(localId: String)
    public fun deleteMessage(localId: String)
    public fun deleteMessageForEveryone(localId: String)
    public fun setMessagePinned(messageId: String, pinned: Boolean)
    public suspend fun searchConversationMessages(...)

    // Groups (suspend, FlashResult): createGroup, createGroupForInvite, inviteFor, acceptInvite, approveJoinRequest,
    // refuseJoinRequest, addGroupMembers, leaveGroup, promoteAdmin, demoteAdmin, removeGroupMember, changeGroupCode,
    // getGroupSettings / updateGroupSettings, chooseGroupHistory / skipGroupHistory / loadOlderGroupHistory ...
}
```

Sending returns `Unit`, not a message id or `FlashResult`: the message is written to the durable outbox first and its
`FlashMessageStatus` (`Pending`, `Sent`, `Delivered`, `Read`, `Failed`) is observed in `conversationState.messages`. A conversation id for a
1:1 chat is the peer's device id.

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.messaging.FlashChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun openChatThread(
    repository: FlashChatRepository,
    peerId: String,
    scope: CoroutineScope,
) {
    repository.openConversation(peerId)

    scope.launch {
        repository.conversationState.collect { conversation ->
            println("Active chat with: ${conversation.header.title} (${conversation.header.presence})")
            conversation.messages.forEach { msg ->
                println(" [${msg.timeLabel}] ${msg.senderName}: ${msg.text} (${msg.deliveryStatus})")
            }
        }
    }

    repository.sendText("Hello! I am ready for the file transfer.")
}
```
