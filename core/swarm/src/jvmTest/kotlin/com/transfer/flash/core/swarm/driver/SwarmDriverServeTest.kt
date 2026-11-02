package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.swarm.codec.DecodeResult
import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.codec.SwarmFrameCodec
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.FileIdentity
import com.transfer.flash.core.swarm.model.PartialHandle
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SourceHandle
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmStateStore
import com.transfer.flash.core.swarm.model.SwarmTombstone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * OPT-O08-02: serving a piece used to re-hash it from disk (SHA-256 over every outgoing megabyte — 12 GB of hashing to
 * serve a 4 GB file to three members) even though the piece is verified in the content's bitfield.
 *
 * The serve path now trusts that bitfield and checks the *whole-file* identity instead, rate-limited. These tests pin
 * both halves: bytes are served without a per-piece hash, and a source that no longer has the announced size stops
 * serving (which is what the ERROR-109 signed LOST statement needs).
 */
class SwarmDriverServeTest {

    private class MemoryStore : SwarmStateStore {
        override suspend fun loadAll(): List<SwarmContentRecord> = emptyList()
        override suspend fun upsert(record: SwarmContentRecord) {}
        override suspend fun setBits(root: ContentRoot, groupId: String, bits: ByteArray, bytesDone: Long) {}
        override suspend fun putTombstone(tombstone: SwarmTombstone) {}
        override suspend fun tombstones(): List<SwarmTombstone> = emptyList()
        override suspend fun delete(root: ContentRoot, groupId: String) {}
        override suspend fun purgeExpired(nowMs: Long) {}
    }

    /** Serves [bytes] at any offset and reports [reportedSize] as the file's size. */
    private class SourceStorage(
        private val bytes: ByteArray,
        private val reportedSize: Long,
    ) : PieceStorage {
        override suspend fun openSource(uri: String): SourceHandle = object : SourceHandle {
            override suspend fun readAt(offset: Long, into: ByteArray, length: Int): Int {
                if (offset >= bytes.size) return -1
                val n = minOf(length.toLong(), bytes.size - offset).toInt()
                bytes.copyInto(into, 0, offset.toInt(), offset.toInt() + n)
                return n
            }

            override suspend fun identity(): FileIdentity = FileIdentity(sizeBytes = reportedSize, lastModifiedMs = 1L)
            override fun close() {}
        }

        override suspend fun openPartial(key: String, size: Long): PartialHandle? = null
        override suspend fun freeBytesFor(key: String): Long = 10_000_000_000L
        override suspend fun deletePartial(key: String) {}
        override suspend fun finalize(key: String, fileName: String, mime: String, expectedSha256: ByteArray): StorageFinalizeResult =
            StorageFinalizeResult(false)
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
        val peers = MutableStateFlow<Map<String, Set<String>>>(mapOf("peerA" to setOf("sw1")))
        val frames = CopyOnWriteArrayList<SwarmFrame>()
        override val connectedPeers: StateFlow<Map<String, Set<String>>> get() = peers
        override suspend fun send(peerId: String, frame: ByteArray): Boolean {
            (SwarmFrameCodec.decode(frame) as? DecodeResult.Decoded)?.let { frames += it.frame }
            return true
        }
        override fun requestSession(peerId: String) {}
    }

    private val pieceSize = 65_536

    /**
     * A manifest whose piece 0 hash is deliberately wrong: the serving side used to reject the serve because of it. The
     * bytes on "disk" are fine, the receiver would reject them — which is exactly the trade this finding makes.
     */
    private fun manifestWithWrongPieceHash(pieceCount: Int = 2): SwarmManifest {
        val total = pieceCount.toLong() * pieceSize
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = total,
            fileSha256 = ByteArray(32) { 9 },
            pieceHashes = List(pieceCount) { ByteArray(32) { 0 } },
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        return unsigned.copy(root = ManifestCodec.computeRoot(ManifestCodec.encodeCanonical(unsigned)))
    }

    private fun runServe(reportedSize: Long, manifest: SwarmManifest): List<SwarmFrame> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport()
        val storage = SourceStorage(ByteArray(manifest.totalSize.toInt()) { 7 }, reportedSize)
        val driver = SwarmDriver(
            config = FlashSwarmConfig(autoAccept = true),
            localDeviceId = "local",
            transport = transport,
            groupContext = allowAll,
            storage = storage,
            stateStore = MemoryStore(),
            scope = scope,
        )
        try {
            driver.announceContent(
                SwarmEvent.Announced(
                    groupId = "g1",
                    messageId = "m1",
                    originId = "local",
                    originKey = "keyLocal",
                    root = manifest.root,
                    totalSize = manifest.totalSize,
                    pieceSize = manifest.pieceSize,
                    fileName = "f.bin",
                    mime = "application/octet-stream",
                    sentAtMs = 1_000L,
                    expiresAtMs = System.currentTimeMillis() + 3_600_000L,
                    isOrigin = true,
                    localUri = "file:///tmp/f.bin",
                    manifest = manifest,
                    nowMs = System.currentTimeMillis(),
                )
            )
            withTimeout(5_000) {
                // Give the driver's restore coroutine a chance to finish before the request is routed.
                Thread.sleep(300)
                driver.onInboundFrame(
                    "peerA",
                    SwarmFrameCodec.encode(SwarmFrame.Request("g1", manifest.root, listOf(0))),
                )
                val deadline = System.currentTimeMillis() + 5_000
                while (transport.frames.none { it is SwarmFrame.Piece } && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                // A refused serve leaves no frame at all; wait long enough to be sure nothing arrives late.
                Thread.sleep(300)
            }
            transport.frames.toList()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a served piece is not re-hashed against the manifest`() {
        val manifest = manifestWithWrongPieceHash()
        val frames = runServe(reportedSize = manifest.totalSize, manifest = manifest)
        val piece = frames.filterIsInstance<SwarmFrame.Piece>().firstOrNull()
        assertTrue(
            piece != null,
            "the piece must be served: the manifest hash is deliberately wrong, so a per-serve hash would refuse it",
        )
        assertEquals(0, piece.index)
        assertEquals(pieceSize, piece.bytes.size)
    }

    @Test
    fun `a source that no longer has the announced size stops serving`() {
        val manifest = manifestWithWrongPieceHash()
        val frames = runServe(reportedSize = manifest.totalSize + 4096, manifest = manifest)
        assertTrue(
            frames.none { it is SwarmFrame.Piece },
            "a source file whose size no longer matches the announcement must not be served",
        )
    }
}
