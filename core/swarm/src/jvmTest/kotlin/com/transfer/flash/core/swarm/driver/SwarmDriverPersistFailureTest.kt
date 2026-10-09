package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PartialHandle
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SourceHandle
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmStateStore
import com.transfer.flash.core.swarm.model.SwarmTombstone
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/** ERROR (audit 2026-10-08): one failed persist write used to kill the driver's persistence worker for good. */
class SwarmDriverPersistFailureTest {

    private class ThrowingStore : SwarmStateStore {
        val attempts = CopyOnWriteArrayList<String>()
        val stored = CopyOnWriteArrayList<String>()

        override suspend fun loadAll(): List<SwarmContentRecord> = emptyList()
        override suspend fun upsert(record: SwarmContentRecord) {
            attempts += record.messageId
            // First item: the disk is full.
            if (record.messageId == "m1") throw java.io.IOException("No space left on device")
            stored += record.messageId
        }
        override suspend fun setBits(root: ContentRoot, groupId: String, bits: ByteArray, bytesDone: Long) {}
        override suspend fun putTombstone(tombstone: SwarmTombstone) {}
        override suspend fun tombstones(): List<SwarmTombstone> = emptyList()
        override suspend fun delete(root: ContentRoot, groupId: String) {}
        override suspend fun purgeExpired(nowMs: Long) {}
    }

    private class NoStorage : PieceStorage {
        override suspend fun openPartial(key: String, size: Long): PartialHandle? = null
        override suspend fun openSource(uri: String): SourceHandle? = null
        override suspend fun freeBytesFor(key: String): Long = 10_000_000_000L
        override suspend fun deletePartial(key: String) {}
        override suspend fun finalize(key: String, fileName: String, mime: String, expectedSha256: ByteArray): StorageFinalizeResult =
            StorageFinalizeResult(false, null, null, emptyList(), null)
        override suspend fun purgeOrphanedPartials(activeKeys: Set<String>) {}
    }

    private val allowAll = object : SwarmGroupContext {
        override suspend fun isPeerAllowed(groupId: String, peerId: String): Boolean = true
        override suspend fun isLocalActiveMember(groupId: String): Boolean = true
        override suspend fun signStatement(groupId: String, statement: ByteArray): ByteArray? = null
        override fun verifyStatement(authorKey: String, statement: ByteArray, signature: ByteArray): Boolean = true
        override suspend fun authorKey(groupId: String, authorId: String): String? = null
        override val membershipChanges: Flow<String> get() = emptyFlow()
    }

    private fun announce(driver: SwarmDriver, messageId: String, rootByte: String) = runBlocking {
        driver.announceContent(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = messageId,
                originId = "origin",
                originKey = "",
                root = ContentRoot(rootByte.repeat(32)),
                totalSize = 262_144L,
                pieceSize = 65_536,
                fileName = "$messageId.bin",
                mime = "application/octet-stream",
                sentAtMs = 1_000L,
                expiresAtMs = 10_000_000_000L,
                isOrigin = false,
                nowMs = 1_000L,
            )
        )
    }

    @Test
    fun `a persist write that throws does not stop later writes`() = runBlocking<Unit> {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> uncaught += t })
        val store = ThrowingStore()
        val driver = SwarmDriver(
            config = FlashSwarmConfig(),
            localDeviceId = "local",
            transport = object : SwarmTransport {
                override val connectedPeers: kotlinx.coroutines.flow.StateFlow<Map<String, Set<String>>> =
                    kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
                override suspend fun send(peerId: String, frame: ByteArray): Boolean = true
                override fun requestSession(peerId: String) {}
            },
            groupContext = allowAll,
            storage = NoStorage(),
            stateStore = store,
            scope = scope,
        )
        try {
            announce(driver, "m1", "01")
            withTimeout(5_000) { while ("m1" !in store.attempts) delay(20) }
            announce(driver, "m2", "02")
            withTimeout(5_000) { while ("m2" !in store.stored) delay(20) }
            assertTrue("m2" in store.stored, "the write after the failed one must still be persisted")
        } finally {
            scope.cancel()
        }
    }
}
