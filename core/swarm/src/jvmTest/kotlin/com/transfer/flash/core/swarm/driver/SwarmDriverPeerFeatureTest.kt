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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop log 2026-10-20: the phone's first session lacked `sw1` and was superseded by a richer one under the same device id.
 * The peer key never left the map, so the driver never sent PeerUp, the engine never learned the origin was connected, and a
 * group image offer that had been accepted sat there for good.
 */
class SwarmDriverPeerFeatureTest {

    private class MemoryStore : SwarmStateStore {
        override suspend fun loadAll(): List<SwarmContentRecord> = emptyList()
        override suspend fun upsert(record: SwarmContentRecord) {}
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

    private class RecordingTransport : SwarmTransport {
        val peers = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
        val sentTo = CopyOnWriteArrayList<String>()
        override val connectedPeers: StateFlow<Map<String, Set<String>>> get() = peers
        override suspend fun send(peerId: String, frame: ByteArray): Boolean {
            sentTo += peerId
            return true
        }
        override fun requestSession(peerId: String) {}
    }

    private fun newDriver(transport: SwarmTransport, scope: CoroutineScope) = SwarmDriver(
        config = FlashSwarmConfig(),
        localDeviceId = "local",
        transport = transport,
        groupContext = allowAll,
        storage = NoStorage(),
        stateStore = MemoryStore(),
        scope = scope,
    )

    private fun announceFromOrigin(driver: SwarmDriver) = runBlocking {
        driver.announceContent(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "origin",
                originKey = "",
                root = ContentRoot("01".repeat(32)),
                totalSize = 87_394L,
                pieceSize = 65_536,
                fileName = "photo.jpg",
                mime = "image/jpeg",
                sentAtMs = 1_000L,
                expiresAtMs = 10_000_000_000L,
                isOrigin = false,
                nowMs = 1_000L,
            )
        )
    }

    @Test
    fun `a connected peer that gains sw1 under the same device id is brought up`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport()
        val driver = newDriver(transport, scope)
        try {
            announceFromOrigin(driver)
            // First session: no sw1, so the swarm has nothing to say to it.
            transport.peers.value = mapOf("origin" to setOf("cv1", "gs1", "hrs1"))
            delay(300)
            assertTrue(transport.sentTo.isEmpty(), "a peer without sw1 gets no swarm frame")

            // Superseded by a richer session under the same device id: the key set does not change.
            transport.peers.value = mapOf("origin" to setOf("cv1", "gs1", "hrs1", "sw1"))
            withTimeout(5_000) { while (transport.sentTo.isEmpty()) delay(20) }
            assertEquals("origin", transport.sentTo.first(), "the swarm must greet the origin once it can speak FSW1")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a peer that already had sw1 is not greeted again by an unrelated feature change`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport()
        val driver = newDriver(transport, scope)
        try {
            announceFromOrigin(driver)
            transport.peers.value = mapOf("origin" to setOf("gs1", "sw1"))
            withTimeout(5_000) { while (transport.sentTo.isEmpty()) delay(20) }
            val greetings = transport.sentTo.size
            transport.peers.value = mapOf("origin" to setOf("gs1", "sw1", "cv1"))
            delay(500)
            assertEquals(greetings, transport.sentTo.size, "an unrelated feature must not restart the exchange")
        } finally {
            scope.cancel()
        }
    }
}
