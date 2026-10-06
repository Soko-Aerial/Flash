package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SourceReason
import com.transfer.flash.core.swarm.codec.SourceState
import com.transfer.flash.core.swarm.codec.SwarmContentState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmWaitReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies INV-9 (Table 8.1): every [SwarmWaitReason] has detection and a deterministic recovery event.
 */
class SwarmWaitReasonRecoveryTest {

    private fun createManifest(pieceCount: Int = 4, pieceSize: Int = 65536): Pair<SwarmManifest, ByteArray> {
        val totalSize = pieceCount.toLong() * pieceSize
        val pieceHashes = List(pieceCount) { idx ->
            Sha256.digest(byteArrayOf(idx.toByte()))
        }
        val fileSha = Sha256.digest(ByteArray(totalSize.toInt()) { 1 })
        val unsignedManifest = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = totalSize,
            fileSha256 = fileSha,
            pieceHashes = pieceHashes,
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        val canonicalBytes = ManifestCodec.encodeCanonical(unsignedManifest)
        val root = ManifestCodec.computeRoot(canonicalBytes)
        val manifest = unsignedManifest.copy(root = root)
        return manifest to canonicalBytes
    }

    private fun setupActiveReceiver(
        manifest: SwarmManifest,
        originId: String = "origin",
        groupId: String = "g1",
        engine: SwarmEngine,
    ): Pair<String, ContentRoot> {
        val key = groupId to manifest.root
        engine.handle(
            SwarmEvent.Announced(
                groupId = groupId,
                messageId = "m1",
                originId = originId,
                originKey = "origKey",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "test.bin",
                mime = "application/octet-stream",
                sentAtMs = 1000L,
                expiresAtMs = 10_000_000L,
                isOrigin = false,
                manifest = manifest,
                nowMs = 1000L,
            )
        )
        engine.handle(SwarmEvent.Accepted(groupId, manifest.root, nowMs = 1000L))
        return key
    }

    @Test
    fun `WAITING_FOR_SENDER recovery on origin peer up`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(config = SwarmConfig(), localDeviceId = "rx", seed = 42L)
        val key = setupActiveReceiver(manifest, originId = "origin", engine = engine)

        // Origin is offline -> WAITING_FOR_SENDER
        assertEquals(SwarmWaitReason.WAITING_FOR_SENDER, engine.snapshot().contents[key]?.waitReason)

        // Recovery event: origin connects
        engine.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), nowMs = 2000L))

        // Wait reason cleared
        assertNull(engine.snapshot().contents[key]?.waitReason)
    }

    @Test
    fun `WAITING_FOR_HOLDERS recovery on origin source restored`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(config = SwarmConfig(), localDeviceId = "rx", seed = 42L)
        engine.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), nowMs = 1000L))
        val key = setupActiveReceiver(manifest, originId = "origin", engine = engine)

        assertNull(engine.snapshot().contents[key]?.waitReason)

        // Origin reports source lost
        engine.handle(
            SwarmEvent.SourceStatusArrived(
                peerId = "origin",
                frame = SwarmFrame.SourceStatus(
                    groupId = "g1",
                    root = manifest.root,
                    originId = "origin",
                    messageId = "m1",
                    status = SourceState.LOST,
                    reason = SourceReason.DELETED,
                    atMs = 2000L,
                    signature = ByteArray(64) { 1 },
                ),
                signatureValid = true,
                nowMs = 2000L,
            )
        )
        assertEquals(SwarmWaitReason.WAITING_FOR_HOLDERS, engine.snapshot().contents[key]?.waitReason)

        // Recovery event: origin restores source
        engine.handle(
            SwarmEvent.SourceStatusArrived(
                peerId = "origin",
                frame = SwarmFrame.SourceStatus(
                    groupId = "g1",
                    root = manifest.root,
                    originId = "origin",
                    messageId = "m1",
                    status = SourceState.RESTORED,
                    reason = SourceReason.NONE,
                    atMs = 2100L,
                    signature = ByteArray(64) { 1 },
                ),
                signatureValid = true,
                nowMs = 2100L,
            )
        )
        assertNull(engine.snapshot().contents[key]?.waitReason)
    }

    @Test
    fun `WAITING_FOR_NETWORK recovery on network up`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(config = SwarmConfig(), localDeviceId = "rx", seed = 42L)
        engine.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), nowMs = 1000L))
        val key = setupActiveReceiver(manifest, originId = "origin", engine = engine)

        // Network goes down
        engine.handle(SwarmEvent.NetworkDown(nowMs = 2000L))
        assertEquals(SwarmWaitReason.WAITING_FOR_NETWORK, engine.snapshot().contents[key]?.waitReason)

        // Recovery event: network restored
        engine.handle(SwarmEvent.NetworkUp(nowMs = 2100L))
        assertNull(engine.snapshot().contents[key]?.waitReason)
    }

    @Test
    fun `WAITING_FOR_SPACE recovery on free space change`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(config = SwarmConfig(), localDeviceId = "rx", seed = 42L)
        engine.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), nowMs = 1000L))
        val key = setupActiveReceiver(manifest, originId = "origin", engine = engine)

        // Disk fills below required size
        engine.handle(SwarmEvent.SpaceChanged(freeBytes = 10L, nowMs = 2000L))
        assertEquals(SwarmWaitReason.WAITING_FOR_SPACE, engine.snapshot().contents[key]?.waitReason)

        // Recovery event: free space restored
        engine.handle(SwarmEvent.SpaceChanged(freeBytes = 10_000_000L, nowMs = 2100L))
        assertNull(engine.snapshot().contents[key]?.waitReason)
    }

    @Test
    fun `WAITING_FOR_STORAGE recovery on local resume or retry`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(config = SwarmConfig(), localDeviceId = "rx", seed = 42L)
        engine.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), nowMs = 1000L))
        val key = setupActiveReceiver(manifest, originId = "origin", engine = engine)

        // Storage write fails
        engine.handle(
            SwarmEvent.PieceStoreFailed(
                groupId = "g1",
                root = manifest.root,
                index = 0,
                error = "I/O failure",
                nowMs = 2000L,
            )
        )
        assertEquals(SwarmWaitReason.WAITING_FOR_STORAGE, engine.snapshot().contents[key]?.waitReason)

        // Recovery event: user retries / selects location via LocalResume
        engine.handle(SwarmEvent.LocalResume("g1", manifest.root, nowMs = 2100L))
        assertNull(engine.snapshot().contents[key]?.waitReason)
    }

    @Test
    fun `WAITING_FOR_SYSTEM recovery on system resume`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(config = SwarmConfig(), localDeviceId = "rx", seed = 42L)
        engine.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), nowMs = 1000L))
        val key = setupActiveReceiver(manifest, originId = "origin", engine = engine)

        // OS suspends transfer
        engine.handle(SwarmEvent.SystemSuspend(nowMs = 2000L))
        assertEquals(SwarmWaitReason.WAITING_FOR_SYSTEM, engine.snapshot().contents[key]?.waitReason)

        // Recovery event: service start / wake triggers SystemResume
        engine.handle(SwarmEvent.SystemResume(nowMs = 2100L))
        assertNull(engine.snapshot().contents[key]?.waitReason)
    }

    @Test
    fun `WAITING_FOR_SESSION recovery on holder peer up`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(config = SwarmConfig(), localDeviceId = "rx", seed = 42L)
        val key = setupActiveReceiver(manifest, originId = "origin", engine = engine)

        // Peer holder connects and advertises piece 0
        engine.handle(SwarmEvent.PeerUp("holder1", setOf("sw1"), nowMs = 1100L))
        engine.handle(
            SwarmEvent.HaveArrived(
                peerId = "holder1",
                frame = SwarmFrame.Have("g1", manifest.root, ranges = listOf(PieceRange(0, 1))),
                nowMs = 1200L,
            )
        )
        // holder1 disconnects (e.g. session ceiling exceeded)
        engine.handle(SwarmEvent.PeerDown("holder1", nowMs = 1300L))

        // Offline holder is known, but no connected holders and origin is offline -> WAITING_FOR_SESSION
        assertEquals(SwarmWaitReason.WAITING_FOR_SESSION, engine.snapshot().contents[key]?.waitReason)

        // Recovery event: session to holder1 is re-established
        engine.handle(SwarmEvent.PeerUp("holder1", setOf("sw1"), nowMs = 2000L))
        assertNull(engine.snapshot().contents[key]?.waitReason)
    }
}
