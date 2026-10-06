package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.engine.interop.DesktopEndpointFixture
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.GroupSwarmAnnouncementListener
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.persistence.db.entity.SwarmContentEntity
import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.swarm.driver.SwarmDriver
import com.transfer.flash.core.swarm.driver.SwarmGroupContext
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.ManifestBuilder
import com.transfer.flash.core.swarm.model.PartialHandle
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SourceHandle
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import com.transfer.flash.core.transfer.model.FlashTransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SwarmHostLifecycleTest {

    private class RecordingPieceStorage : PieceStorage {
        val purgedKeySets = mutableListOf<Set<String>>()

        override suspend fun openPartial(key: String, size: Long): PartialHandle? = null
        override suspend fun openSource(uri: String): SourceHandle? = null
        override suspend fun freeBytesFor(key: String): Long = 10_000_000L
        override suspend fun deletePartial(key: String) {}
        override suspend fun finalize(key: String, fileName: String, mime: String, expectedSha256: ByteArray): StorageFinalizeResult =
            StorageFinalizeResult(true, "/dest/$fileName", null, emptyList(), null)

        override suspend fun purgeOrphanedPartials(activeKeys: Set<String>) {
            purgedKeySets.add(activeKeys)
        }
    }

    private class TestChatRepository : FlashChatRepository {
        private val _chatListState = MutableStateFlow(FlashChatListUiState())
        override val chatListState: StateFlow<FlashChatListUiState> = _chatListState.asStateFlow()

        private val _conversationState = MutableStateFlow(
            FlashConversationUiState(
                header = FlashChatHeaderUiState(
                    title = "Group 1",
                    avatarInitials = "G1",
                    isGroup = true,
                ),
                messages = emptyList(),
            )
        )
        override val conversationState: StateFlow<FlashConversationUiState> = _conversationState.asStateFlow()

        override var swarmAnnouncementListener: GroupSwarmAnnouncementListener? = null
        override var onGroupMessageDeletedForEveryone: ((groupId: String, messageId: String) -> Unit)? = null

        override fun openConversation(conversationId: String) {}
        override fun closeConversation() {}
        override fun sendText(text: String) {}
        override fun openAttachmentPicker() {}
        override fun enterListSelectionMode(conversationId: String) {}
        override fun toggleListSelection(conversationId: String) {}
        override fun clearListSelection() {}
        override fun archiveConversation(conversationId: String) {}
    }

    @Test
    fun `runRetentionCleanup purges expired records from stateStore and purges orphaned partials`() = runBlocking {
        val dao = SwarmInteropTest.FakeSwarmDao()
        val stateStore = RoomSwarmStateStore(dao)
        val storage = RecordingPieceStorage()

        val nowMs = 100_000L

        // Expired completed item (expired at 50_000 ms < nowMs)
        val expiredEntity = SwarmContentEntity(
            root = "0101010101010101010101010101010101010101010101010101010101010101",
            groupId = "g1",
            messageId = "m_exp",
            role = "RECEIVER",
            originId = "orig",
            originKey = "key",
            fileName = "old.bin",
            mime = "application/octet-stream",
            totalSize = 1000L,
            pieceSize = 500,
            manifest = null,
            bits = byteArrayOf(0x03),
            bytesDone = 1000L,
            state = "COMPLETE",
            waitReason = null,
            failReason = null,
            localTransferId = "m_exp",
            sourceUri = null,
            sourcePersistent = false,
            partialKey = "part_old",
            finalPath = "/dest/old.bin",
            identitySize = 0L,
            identityModifiedMs = 0L,
            deliveredTo = "",
            createdAtMs = 10_000L,
            lastProgressAtMs = 50_000L,
            expiresAtMs = 50_000L,
        )

        // Active item (expires at 200_000 ms > nowMs)
        val activeEntity = SwarmContentEntity(
            root = "0202020202020202020202020202020202020202020202020202020202020202",
            groupId = "g1",
            messageId = "m_act",
            role = "RECEIVER",
            originId = "orig",
            originKey = "key",
            fileName = "act.bin",
            mime = "application/octet-stream",
            totalSize = 262_144L,
            pieceSize = 65_536,
            manifest = null,
            bits = byteArrayOf(0x01),
            bytesDone = 65_536L,
            state = "ACTIVE",
            waitReason = null,
            failReason = null,
            localTransferId = "m_act",
            sourceUri = null,
            sourcePersistent = false,
            partialKey = "part_active",
            finalPath = null,
            identitySize = 0L,
            identityModifiedMs = 0L,
            deliveredTo = "",
            createdAtMs = 90_000L,
            lastProgressAtMs = 90_000L,
            expiresAtMs = 200_000L,
        )

        dao.upsertContent(expiredEntity)
        dao.upsertContent(activeEntity)
        assertEquals(2, dao.loadAllContent().size)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val driver = SwarmDriver(
            config = FlashSwarmConfig(),
            localDeviceId = "localNode",
            scope = scope,
            transport = NetworkSwarmTransport(
                sendFrameToPeer = { _, _ -> true },
                requestSessionForPeer = { _ -> },
            ),
            groupContext = object : SwarmGroupContext {
                override suspend fun isPeerAllowed(groupId: String, peerId: String): Boolean = true
                override suspend fun isLocalActiveMember(groupId: String): Boolean = true
                override suspend fun signStatement(groupId: String, statement: ByteArray): ByteArray? = null
                override fun verifyStatement(authorKey: String, statement: ByteArray, signature: ByteArray): Boolean = true
                override suspend fun authorKey(groupId: String, authorId: String): String? = null
                override val membershipChanges: Flow<String> get() = emptyFlow()
            },
            storage = storage,
            stateStore = stateStore,
            timeSource = object : com.transfer.flash.core.common.time.FlashTimeSource {
                override fun nowMs(): Long = nowMs
            },
        )

        driver.runRetentionCleanup()

        // Expired record was purged from DB
        val remaining = dao.loadAllContent()
        assertEquals(1, remaining.size)
        assertEquals("m_act", remaining[0].messageId)

        // Storage was asked to purge orphaned partials. The files on disk are named by partialKeyFor(), NOT by the
        // key stored on the record, so that is the key that must be preserved (the stored key used to be passed,
        // which deleted every real partial download at startup).
        assertTrue("Storage must be asked to purge orphaned partials", storage.purgedKeySets.isNotEmpty())
        val preservedKeys = storage.purgedKeySets.last()
        val activeFileKey = SwarmDriver.partialKeyFor(ContentRoot(activeEntity.root), "g1")
        val expiredFileKey = SwarmDriver.partialKeyFor(ContentRoot(expiredEntity.root), "g1")
        assertTrue(preservedKeys.contains(activeFileKey))
        assertFalse(preservedKeys.contains(expiredFileKey))
        assertFalse("the stored record key is not a file name", preservedKeys.contains("part_active"))

        // R4: the restarted driver also rebuilt the engine, so the unfinished download is a live row again with
        // its persisted progress (1 of 4 pieces = 65536 bytes), not an empty list.
        withTimeout(5000) {
            while (driver.rows.value.none { it.id.value == "m_act" }) delay(25)
        }
        val row = driver.rows.value.first { it.id.value == "m_act" }
        assertEquals(65_536L, row.bytesDone)
        assertEquals(262_144L, row.bytesTotal)
    }

    private fun transferRowNamedBy(title: String?): String = runBlocking {
        val dao = SwarmInteropTest.FakeSwarmDao()
        dao.upsertContent(
            SwarmContentEntity(
                root = "0303030303030303030303030303030303030303030303030303030303030303",
                groupId = "g-family",
                messageId = "m_named",
                role = "RECEIVER",
                originId = "orig",
                originKey = "key",
                fileName = "act.bin",
                mime = "application/octet-stream",
                totalSize = 262_144L,
                pieceSize = 65_536,
                manifest = null,
                bits = byteArrayOf(0x01),
                bytesDone = 65_536L,
                state = "ACTIVE",
                waitReason = null,
                failReason = null,
                localTransferId = "m_named",
                sourceUri = null,
                sourcePersistent = false,
                partialKey = "part_named",
                finalPath = null,
                identitySize = 0L,
                identityModifiedMs = 0L,
                deliveredTo = "",
                createdAtMs = 90_000L,
                lastProgressAtMs = 90_000L,
                expiresAtMs = 200_000L,
            )
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val driver = SwarmDriver(
            config = FlashSwarmConfig(),
            localDeviceId = "localNode",
            scope = scope,
            transport = NetworkSwarmTransport(sendFrameToPeer = { _, _ -> true }, requestSessionForPeer = { _ -> }),
            groupContext = object : SwarmGroupContext {
                override suspend fun isPeerAllowed(groupId: String, peerId: String): Boolean = true
                override suspend fun isLocalActiveMember(groupId: String): Boolean = true
                override suspend fun signStatement(groupId: String, statement: ByteArray): ByteArray? = null
                override fun verifyStatement(authorKey: String, statement: ByteArray, signature: ByteArray): Boolean = true
                override suspend fun authorKey(groupId: String, authorId: String): String? = null
                override suspend fun groupTitle(groupId: String): String? = title
                override val membershipChanges: Flow<String> get() = emptyFlow()
            },
            storage = RecordingPieceStorage(),
            stateStore = RoomSwarmStateStore(dao),
            timeSource = object : com.transfer.flash.core.common.time.FlashTimeSource {
                override fun nowMs(): Long = 100_000L
            },
        )
        try {
            withTimeout(5000) {
                while (driver.rows.value.none { it.id.value == "m_named" }) delay(25)
            }
            driver.rows.value.first { it.id.value == "m_named" }.peerName
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `the transfer row names the group, not its id`() {
        assertEquals("Family", transferRowNamedBy("Family"))
    }

    @Test
    fun `a group without a known name reads Group, never the id`() {
        assertEquals("Group", transferRowNamedBy(null))
    }

    @Test
    fun `deleteMessageForEveryone wires to cancelAsOrigin with DELETED reason and clears on detach`() = runBlocking {
        val testTag = System.currentTimeMillis()
        val stateDir = File(System.getProperty("java.io.tmpdir"), "flash-host-lifecycle-$testTag").apply { mkdirs() }
        val recvDir = File(stateDir, "received").apply { mkdirs() }
        val fixture = DesktopEndpointFixture("host-$testTag", recvDir)
        val chatRepo = TestChatRepository()
        val dao = SwarmInteropTest.FakeSwarmDao()
        val stateStore = RoomSwarmStateStore(dao)
        val originId = fixture.identity.deviceId.value
        val ctx = SwarmInteropTest.TestSwarmGroupContext(originId, setOf(originId), mapOf(originId to fixture.crypto))

        val binding = fixture.attachSwarm(
            groupContext = ctx,
            stateStore = stateStore,
            chatRepository = chatRepo,
        )

        try {
            // Hook must be registered
            assertNotNull(chatRepo.onGroupMessageDeletedForEveryone)

            val payloadFile = File(stateDir, "payload.bin").apply { writeBytes(ByteArray(1024) { 1 }) }
            val builder = ManifestBuilder(65536)
            builder.addBlock(payloadFile.readBytes(), 0, 1024)
            val manifest = builder.build()

            binding.registerOrigin(
                groupId = "g1",
                messageId = "m_del",
                fileName = payloadFile.name,
                mimeType = "application/octet-stream",
                sizeBytes = payloadFile.length(),
                uri = payloadFile.absolutePath,
                manifest = manifest,
            )

            // Wait for origin row to appear
            withTimeout(5000) {
                while (true) {
                    val row = binding.driver.rows.value.firstOrNull { it.id.value == "m_del" }
                    if (row != null) break
                    delay(50)
                }
            }

            // Trigger delete for everyone via repository hook
            chatRepo.onGroupMessageDeletedForEveryone?.invoke("g1", "m_del")

            // Wait for driver to process cancelAsOrigin
            withTimeout(5000) {
                while (true) {
                    val row = binding.driver.rows.value.firstOrNull { it.id.value == "m_del" }
                    if (row?.state == FlashTransferState.Cancelled) {
                        break
                    }
                    delay(50)
                }
            }

            val row = binding.driver.rows.value.firstOrNull { it.id.value == "m_del" }
            assertNotNull(row)
            assertEquals(FlashTransferState.Cancelled, row!!.state)

            // Detach should unregister hooks
            binding.detach()
            assertNull(chatRepo.onGroupMessageDeletedForEveryone)
            assertNull(chatRepo.swarmAnnouncementListener)
        } finally {
            fixture.stop()
            stateDir.deleteRecursively()
        }
    }
}
