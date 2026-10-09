# Core Persistence Module (`:core:persistence`)

The `:core:persistence` module manages relational database storage, encrypted tables, and transactional history across Android and Compose Desktop. It is backed by Room (KMP) and encrypted SQLite (via SQLCipher on Android and `sqlite-jdbc-crypt` on JVM desktop).

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-persistence:v2.1.0-beta")
}
```

---

## 2. Architecture and Database Schema

The database is [`FlashDatabase`](../../../../core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabase.kt)
(Room, **schema version 13**, schemas exported to `core/persistence/schemas/`). Opening it is platform code:
`FlashDatabaseOpener` (Android, SQLCipher) and `JvmFlashDatabaseOpener` (desktop, `sqlite-jdbc-crypt` through `JdbcCipherSQLiteDriver`);
migrations are `FlashMigrations` / `FlashJvmMigrations`, built from the shared `FlashSchemaSteps`. The key is supplied by the host
(on Android it is wrapped by the Keystore); a database that can no longer be opened fails loudly rather than being recreated.

25 entities, grouped:

| Area | Entities |
|---|---|
| Chat | `MessageEntity` (text, status string, sent/edited/deleted times, attachment columns, reply preview, group signature, swarm root columns), `ConversationEntity`, `ReceiptEntity`, `ReactionEntity`, `DraftEntity`, `ReadCursorEntity`, `MessagePinEntity`, `RecentSearchEntity`, `OutboxEntity` (the durable outbox) |
| Transfers | `TransferEntity` (`transferId`, `totalBytes`, `bytesDone`, `status`, ...), `TransferChunkEntity` (per-chunk `done` flag for resume) |
| Trust | `TrustedPeerEntity` (`deviceId`, `name`, `fingerprintHex`, `trustedAt`; session keys are not stored here) |
| Discovery | `RememberedEndpointEntity` (remembered routes, DR1) |
| Groups | `GroupMemberEntity`, `GroupDeliveryEntity`, `GroupSecretEntity`, `GroupInviteEntity`, `GroupJoinRequestEntity`, `GroupRotationEntity`, `GroupSettingsEntity`, `GroupPreferencesEntity`, `GroupHistoryStateEntity`, `GroupSyncWatermarkEntity` (history sync, ADR-100) |
| Swarm | `SwarmContentEntity`, `SwarmTombstoneEntity` |

`RetentionPolicy` (commonMain) holds the purge rules. The app's backup rules exclude the database files (Auto Backup and data extraction).

---

## 3. Key Public Interfaces & DAOs

One DAO per area, reached from `FlashDatabase` (`messageDao()`, `conversationDao()`, `transferDao()`, `transferChunkDao()`,
`trustedPeerDao()`, `swarmDao()`, `groupSecretDao()`, ...). Reactive queries return `Flow`; mutations are `suspend`. A few of the
most used (all in `...core.persistence.db.dao`):

* **`MessageDao`:** `insert(message): Long`, `observeConversation(conversationId): Flow<List<MessageEntity>>`, `historyBefore(...)` / `historyAfter(...)` (paging), `getByLocalId`, `updateStatus(localId, status: String)`, `markReadUpTo(conversationId, selfId, upToMessageId)`, `observeUnreadCounts(selfId)`, `observeLatestPreviews()`, `markEdited`, `markDeleted`, `searchMessages(query, limit)`, `searchConversationMessages(...)`.
* **`TransferDao`:** `insert(transfer)`, `observe(transferId): Flow<TransferEntity?>`, `setBytesDone(transferId, bytesDone)`, `setStatus(transferId, status)`. There is no `observeActiveTransfers`; the engine builds the active list in memory.
* **`TransferChunkDao`:** `insertAll`, `markChunkDone(transferId, chunkIndex)`, `doneChunks(transferId): List<Int>`, `allDoneChunks()`, `resetStuck`, `deleteChunks`, `purgeFinishedChunks()`.

Delivery status, transfer status and the like are stored as **strings**, not Kotlin enums; the repositories map them.
Most consumers never touch the DAOs: `core-messaging` and `core-transfer` define persistence *ports* and `core-engine` supplies the Room adapters (ADR-024).

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.persistence.db.FlashDatabase

// Observing in-conversation messages reactively
fun monitorConversation(db: FlashDatabase, conversationId: String) {
    scope.launch {
        db.messageDao().observeConversation(conversationId).collect { messages ->
            println("Thread $conversationId updated (${messages.size} messages):")
            messages.forEach { msg ->
                println(" [${msg.status}] ${msg.senderId}: ${msg.text}")
            }
        }
    }
}
```
