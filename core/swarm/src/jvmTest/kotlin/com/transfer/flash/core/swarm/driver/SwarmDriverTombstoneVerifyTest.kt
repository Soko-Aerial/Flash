package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.codec.SwarmFrameCodec
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PartialHandle
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SourceHandle
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmStateStore
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OPT-O08-08: every Summary re-verified each tombstone it carried with Ed25519 (~0.5 ms each), every reconnect, for
 * cancellations this device had already accepted and persisted. A known tombstone is now skipped; the key is only
 * claimed after a successful check, so a forged frame cannot shadow the real cancellation.
 */
class SwarmDriverTombstoneVerifyTest {

    private class MemoryStore : SwarmStateStore {
        val persisted = mutableListOf<SwarmTombstone>()
        override suspend fun loadAll(): List<SwarmContentRecord> = emptyList()
        override suspend fun upsert(record: SwarmContentRecord) {}
        override suspend fun setBits(root: ContentRoot, groupId: String, bits: ByteArray, bytesDone: Long) {}
        override suspend fun putTombstone(tombstone: SwarmTombstone) { persisted += tombstone }
        override suspend fun tombstones(): List<SwarmTombstone> = emptyList()
        override suspend fun delete(root: ContentRoot, groupId: String) {}
        override suspend fun purgeExpired(nowMs: Long) {}
    }

    private class NoStorage : PieceStorage {
        override suspend fun openPartial(key: String, size: Long): PartialHandle? = null
        override suspend fun openSource(uri: String): SourceHandle? = null
        override suspend fun freeBytesFor(key: String): Long = 1_000_000L
        override suspend fun deletePartial(key: String) {}
        override suspend fun finalize(key: String, fileName: String, mime: String, expectedSha256: ByteArray): StorageFinalizeResult =
            StorageFinalizeResult(false)
        override suspend fun purgeOrphanedPartials(activeKeys: Set<String>) {}
    }

    private class CountingGroupContext : SwarmGroupContext {
        val verifications = AtomicInteger()
        override suspend fun isPeerAllowed(groupId: String, peerId: String): Boolean = true
        override suspend fun isLocalActiveMember(groupId: String): Boolean = true
        override suspend fun signStatement(groupId: String, statement: ByteArray): ByteArray? = null
        override fun verifyStatement(authorKey: String, statement: ByteArray, signature: ByteArray): Boolean {
            verifications.incrementAndGet()
            return true
        }
        override suspend fun authorKey(groupId: String, authorId: String): String? = "key-$authorId"
        override val membershipChanges: Flow<String> get() = emptyFlow()
    }

    private class FakeTransport : SwarmTransport {
        override val connectedPeers: StateFlow<Map<String, Set<String>>> =
            MutableStateFlow(mapOf("peerA" to setOf("sw1")))
        override suspend fun send(peerId: String, frame: ByteArray): Boolean = true
        override fun requestSession(peerId: String) {}
    }

    private fun tombstone(index: Int) = SwarmTombstone(
        groupId = "g1",
        root = ContentRoot(index.toString().padStart(64, '0')),
        originId = "origin",
        messageId = "m$index",
        reason = SwarmTombstoneReason.USER,
        cancelledAtMs = 1_000L + index,
        signature = ByteArray(64) { 1 },
    )

    private fun summary(tombstones: List<SwarmTombstone>) = SwarmFrame.Summary(
        groupId = "g1",
        tombstones = tombstones,
        entries = emptyList(),
    )

    @Test
    fun `a tombstone already verified is not verified again`() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val context = CountingGroupContext()
            val driver = SwarmDriver(
                config = FlashSwarmConfig(),
                localDeviceId = "local",
                transport = FakeTransport(),
                groupContext = context,
                storage = NoStorage(),
                stateStore = MemoryStore(),
                scope = scope,
            )
            try {
                val frame = SwarmFrameCodec.encode(summary(listOf(tombstone(1), tombstone(2), tombstone(3))))
                driver.onInboundFrame("peerA", frame)
                assertEquals(3, context.verifications.get(), "the first Summary verifies all three")

                driver.onInboundFrame("peerA", frame)
                assertEquals(3, context.verifications.get(), "the same Summary again verifies nothing")

                val oneNew = SwarmFrameCodec.encode(summary(listOf(tombstone(2), tombstone(3), tombstone(4))))
                driver.onInboundFrame("peerA", oneNew)
                assertEquals(4, context.verifications.get(), "only the tombstone this device had not seen is verified")
            } finally {
                scope.cancel()
            }
        }
    }
}
