@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.enterprise.OrgPeer
import com.transfer.flash.core.enterprise.OrgPeerResolver
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import com.transfer.flash.core.persistence.db.openEncryptedFlashDatabase
import com.transfer.flash.core.persistence.db.runInWriteTransaction
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Verifier Defect 11 / ORGT-U28:
 * Durable outbox persistence across app restart backed by a REAL SQLite database file on disk,
 * not an in-memory fake.
 */
class RealFlashChatRepositoryRestartRealDbTest {

    private lateinit var tempDir: File
    private lateinit var dbFile: File
    private val encryptionKey = "test-encryption-key-real-db-u28"
    private val colleagueAlice = "colleague-alice-real-db"

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("flash-chat-real-db-u28").toFile()
        dbFile = File(tempDir, "flash.db")
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private class RealDbTestResolver(
        private val colleagueId: String,
    ) : OrgPeerResolver {
        private val _lease = MutableStateFlow<OrgLease>(OrgLease.Valid(untilMs = SystemTimeSource.nowMs() + 10_000_000L))
        override val lease: StateFlow<OrgLease> = _lease.asStateFlow()

        override fun resolve(deviceId: String): OrgPeer? =
            if (deviceId == colleagueId) OrgPeer(colleagueId, "Alice Colleague", "Engineering", "Developer", "FP_ALICE", true) else null

        override fun isKnownColleague(deviceId: String): Boolean = deviceId == colleagueId

        override fun displayName(deviceId: String): String? =
            if (deviceId == colleagueId) "Alice Colleague" else null

        override fun searchColleagueIds(query: String, limit: Int): Set<String> =
            if ("Alice".contains(query, ignoreCase = true)) setOf(colleagueId) else emptySet()

        override suspend fun refreshFromSnapshot() {}
        override suspend fun clear() {
            _lease.value = OrgLease.None
        }
    }

    @Test
    fun ORGT_U28_durableOutbox_survivesRestartWithRealDatabase() = runBlocking {
        // Step 1: Open real database on disk
        val db1 = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val orgDao1 = db1.orgDirectoryDao()

        // Seed sync state and active colleague
        orgDao1.setSyncState(
            OrgSyncStateEntity(
                id = 1,
                orgRootFingerprint = "ROOT_FP_REAL_DB",
                directoryEpoch = "epoch-1",
                lastSyncedVersion = 100L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = SystemTimeSource.nowMs(),
                lastSyncAttemptMs = SystemTimeSource.nowMs(),
            ),
        )
        orgDao1.upsertMembers(
            listOf(
                OrgDirectoryMemberEntity(
                    deviceId = colleagueAlice,
                    displayName = "Alice Colleague",
                    displayNameFolded = "alice colleague",
                    departmentId = "Engineering",
                    role = "Developer",
                    subjectPublicKeyFingerprint = "FP_ALICE",
                    statementBytes = byteArrayOf(1, 2, 3),
                    statementSerial = 10L,
                    statementIssuedAtMs = 1000L,
                    statementDelegationSerial = 1L,
                    lastSyncedAtMs = 1500L,
                ),
            ),
        )

        val resolver = RealDbTestResolver(colleagueAlice)

        // Step 2: Spin up repository session 1 while colleague is unreachable
        val repo1 = RealFlashChatRepository(
            localDeviceId = "self-device",
            localDisplayName = "Self",
            messageDao = db1.messageDao(),
            conversationDao = db1.conversationDao(),
            outboxDao = db1.outboxDao(),
            receiptDao = db1.receiptDao(),
            draftDao = db1.draftDao(),
            recentSearchDao = db1.recentSearchDao(),
            reactionDao = db1.reactionDao(),
            isTrustedPeer = { false },
            isOrgPeer = { it == colleagueAlice },
            orgPeerResolver = resolver,
            transportSink = { _, _ -> false }, // Transport sinks return false (unreachable)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            runInTransaction = { block -> db1.runInWriteTransaction(block) },
        )

        repo1.openConversation(colleagueAlice)
        repo1.sendText("Persistent message for unreachable colleague")

        // Assert message and outbox row exist in the real database (wait for background launch)
        var outboxInDb1 = db1.outboxDao().dueForDelivery(Long.MAX_VALUE, 100)
        var waitCount = 0
        while (outboxInDb1.isEmpty() && waitCount < 100) {
            kotlinx.coroutines.delay(20)
            outboxInDb1 = db1.outboxDao().dueForDelivery(Long.MAX_VALUE, 100)
            waitCount++
        }
        assertEquals(1, outboxInDb1.size, "Outbox table in real DB must contain 1 queued row")
        val messageLocalId = outboxInDb1.first().localId

        val msgInDb1 = db1.messageDao().getByLocalId(messageLocalId)
        assertNotNull(msgInDb1, "Message must be saved to disk")
        assertTrue(msgInDb1.status == "PENDING" || msgInDb1.status == "SENT", "Message status must be pending or sent attempt: ${msgInDb1.status}")
        assertEquals("Persistent message for unreachable colleague", msgInDb1.text)


        // Step 3: Simulate process kill and restart: close repository and close database connection
        repo1.close()
        db1.close()

        // Step 4: Re-open the database from the same file with the same encryption key
        val db2 = openEncryptedFlashDatabase(dbFile, encryptionKey)

        // Directly query disk: verify tables survived process restart
        val outboxInDb2 = db2.outboxDao().dueForDelivery(Long.MAX_VALUE, 100)
        assertEquals(1, outboxInDb2.size, "Outbox on disk must still contain queued row after restart")
        assertEquals(messageLocalId, outboxInDb2.first().localId)

        val msgInDb2 = db2.messageDao().getByLocalId(messageLocalId)
        assertNotNull(msgInDb2, "Message must persist on disk after restart")
        assertEquals("Persistent message for unreachable colleague", msgInDb2.text)

        // Step 5: Spin up repository session 2 with the re-opened database
        val repo2 = RealFlashChatRepository(
            localDeviceId = "self-device",
            localDisplayName = "Self",
            messageDao = db2.messageDao(),
            conversationDao = db2.conversationDao(),
            outboxDao = db2.outboxDao(),
            receiptDao = db2.receiptDao(),
            draftDao = db2.draftDao(),
            recentSearchDao = db2.recentSearchDao(),
            reactionDao = db2.reactionDao(),
            isTrustedPeer = { false },
            isOrgPeer = { it == colleagueAlice },
            orgPeerResolver = resolver,
            transportSink = { _, _ -> false },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            runInTransaction = { block -> db2.runInWriteTransaction(block) },
        )

        repo2.openConversation(colleagueAlice)
        val loadedMessages = db2.messageDao().historyBefore(colleagueAlice, Long.MAX_VALUE, "", 50)
        assertEquals(1, loadedMessages.size, "Loaded messages from reopened DB must have 1 message")
        assertEquals(messageLocalId, loadedMessages.first().localId)

        // Step 6: Inbound delivery receipt arrives: verify outbox row is retired and message becomes DELIVERED
        repo2.onInboundWireFrame(
            MessageWireFrame.DeliveryReceipt(
                messageId = messageLocalId,
                conversationId = colleagueAlice,
                memberId = colleagueAlice,
                deliveredAt = SystemTimeSource.nowMs(),
            ),
            transportPeerId = colleagueAlice,
        )

        val updatedMsg = db2.messageDao().getByLocalId(messageLocalId)
        assertNotNull(updatedMsg)
        assertEquals("DELIVERED", updatedMsg.status, "Message status must be DELIVERED upon receipt")

        val outboxAfterDelivery = db2.outboxDao().dueForDelivery(Long.MAX_VALUE, 100)
        assertTrue(outboxAfterDelivery.isEmpty(), "Outbox on disk must be empty after receipt commits delivery")

        repo2.close()
        db2.close()
    }
}
