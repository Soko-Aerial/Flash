# Core Persistence Module (`:core:persistence`)

The `:core:persistence` module manages relational database storage, encrypted tables, and transactional history across Android and Compose Desktop. It is backed by Android Room and encrypted SQLite (via SQLCipher on Android and `sqlite-jdbc-crypt` on JVM desktop).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-persistence:2.0.0-beta")
}
```

---

## 2. Architecture and Database Schema

The core database entry point is [`FlashDatabase`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabase.kt). It provides persistent tables for:

* **Conversations (`ConversationEntity`):** Conversation IDs, thread types (Direct / Group), titles, draft text, pinned status, unread counts, and last activity timestamps.
* **Messages (`MessageEntity`):** Unique message IDs, conversation parent, sender device ID, delivery states (`Sending`, `Sent`, `Delivered`, `Read`, `Failed`), text content, timestamp, tombstoning flags, and reply associations.
* **Reactions (`ReactionEntity`):** Unicode emoji reactions keyed by message ID and authoring peer.
* **Transfers (`TransferEntity`):** Active and completed file transfers, wire IDs, file paths, total bytes, transferred bytes, transfer direction (Inbound / Outbound), pause state, and terminal status.
* **Transfer Chunks (`TransferChunkEntity`):** Bitmap indexes of chunks transferred during multi-part file streaming to support seamless pause and resume across process restarts.
* **Peers / Trust (`DeviceTrustEntity`):** Fingerprint mappings, mutual pairing confirmation state, public keys, and derived pairwise symmetric keys.

---

## 3. Key Public Interfaces & DAOs

DAOs provide reactive Kotlin `Flow` queries and suspendable mutations:

* **[`MessageDao`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt):**
  * `observeMessages(conversationId: String): Flow<List<MessageEntity>>`
  * `insertMessage(message: MessageEntity)`
  * `updateDeliveryStatus(messageId: String, status: MessageDeliveryStatus)`
  * `searchConversationMessages(conversationId: String, query: String): Flow<List<MessageEntity>>`
  * `markConversationAsRead(conversationId: String, readTimestamp: Long)`
* **[`TransferDao`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferDao.kt):**
  * `observeTransfer(transferId: String): Flow<TransferEntity?>`
  * `observeActiveTransfers(): Flow<List<TransferEntity>>`
  * `updateProgress(transferId: String, bytesTransferred: Long, speed: Double)`
* **[`TransferChunkDao`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/TransferChunkDao.kt):**
  * `recordChunkReceived(transferId: String, chunkIndex: Int)`
  * `getReceivedChunkIndexes(transferId: String): List<Int>`

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.MessageDeliveryStatus
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// Observing in-conversation messages reactively
fun monitorConversation(db: FlashDatabase, conversationId: String) {
    scope.launch {
        db.messageDao().observeMessages(conversationId).collect { messages ->
            println("Thread $conversationId updated (${messages.size} messages):")
            messages.forEach { msg ->
                println(" [${msg.deliveryStatus}] ${msg.senderId}: ${msg.content}")
            }
        }
    }
}
```
