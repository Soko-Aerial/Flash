package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SourceReason
import com.transfer.flash.core.swarm.codec.SourceState
import com.transfer.flash.core.swarm.codec.SwarmContentState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceReadStatus
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ERROR-109: an origin whose file was deleted or changed used to go silent. It cleared its own bit and sent nothing, so the
 * requester waited on a request that was never answered and kept asking; no `SourceStatus` was ever produced, and a restart
 * made the origin claim the whole file again.
 */
class SwarmOriginSourceLostTest {

    private fun manifest(pieceCount: Int = 4, pieceSize: Int = 65536): SwarmManifest {
        val total = pieceCount.toLong() * pieceSize
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = total,
            fileSha256 = Sha256.digest(ByteArray(total.toInt()) { 1 }),
            pieceHashes = List(pieceCount) { Sha256.digest(byteArrayOf(it.toByte())) },
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        return unsigned.copy(root = ManifestCodec.computeRoot(ManifestCodec.encodeCanonical(unsigned)))
    }

    private fun announce(m: SwarmManifest, origin: Boolean) = SwarmEvent.Announced(
        groupId = "g1",
        messageId = "m1",
        originId = "origin",
        originKey = "keyOrig",
        root = m.root,
        totalSize = m.totalSize,
        pieceSize = m.pieceSize,
        fileName = "f.bin",
        mime = "application/octet-stream",
        sentAtMs = 1_000L,
        expiresAtMs = 10_000_000L,
        isOrigin = origin,
        autoAccept = true,
        manifest = m,
        nowMs = 1_000L,
    )

    private fun sentTo(cmds: List<SwarmCommand>, peer: String): List<SwarmFrame> =
        cmds.filterIsInstance<SwarmCommand.Send>().filter { it.peerId == peer }.map { it.frame }

    /** An origin with two connected members, one of which asked for piece 0 and whose read now comes back [status]. */
    private fun originAfterFailedRead(status: PieceReadStatus): Triple<SwarmEngine, List<SwarmCommand>, SwarmManifest> {
        val m = manifest()
        val origin = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "origin", seed = 1L)
        origin.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 900L))
        origin.handle(SwarmEvent.PeerUp("rx2", setOf("sw1"), 900L))
        origin.handle(announce(m, origin = true))
        val summary = SwarmFrame.Summary("g1", emptyList(), listOf(SwarmFrame.Summary.Entry(m.root, SwarmContentState.NONE, true)))
        origin.handle(SwarmEvent.SummaryArrived("rx", summary, 1_050L))
        val requested = origin.handle(
            SwarmEvent.RequestArrived("rx", SwarmFrame.Request("g1", m.root, listOf(0)), allowed = true, serveAllowed = true, nowMs = 1_100L)
        )
        assertTrue(requested.any { it is SwarmCommand.ReadPiece }, "the origin reads the piece it was asked for")
        val after = origin.handle(SwarmEvent.PieceRead("rx", "g1", m.root, 0, null, status, 1_200L))
        return Triple(origin, after, m)
    }

    @Test
    fun `a requester is told GONE when the source cannot be read`() {
        val (_, cmds, m) = originAfterFailedRead(PieceReadStatus.GONE)
        val reject = sentTo(cmds, "rx").filterIsInstance<SwarmFrame.Reject>().single()
        assertEquals(SwarmRejectReason.GONE, reject.reason)
        assertEquals(m.root, reject.root)
        assertEquals(listOf(0), reject.pieces)
    }

    @Test
    fun `a signed SourceStatus LOST is requested once and then sent to every connected member`() {
        val (origin, cmds, m) = originAfterFailedRead(PieceReadStatus.CHANGED)
        val sign = cmds.filterIsInstance<SwarmCommand.SignSourceStatus>().single()
        assertEquals(SourceState.LOST, sign.status)
        assertEquals(SourceReason.CHANGED, sign.reason)
        assertEquals("origin", sign.originId)
        assertEquals("m1", sign.messageId)

        val frame = SwarmFrame.SourceStatus("g1", m.root, "origin", "m1", sign.status, sign.reason, sign.atMs, ByteArray(64) { 1 })
        val sent = origin.handle(SwarmEvent.SourceStatusSigned(frame, 1_210L))
        assertTrue(sentTo(sent, "rx").any { it is SwarmFrame.SourceStatus })
        assertTrue(sentTo(sent, "rx2").any { it is SwarmFrame.SourceStatus })
    }

    @Test
    fun `a second failed read does not ask for a second signature`() {
        val (origin, _, m) = originAfterFailedRead(PieceReadStatus.GONE)
        origin.handle(
            SwarmEvent.RequestArrived("rx", SwarmFrame.Request("g1", m.root, listOf(1)), allowed = true, serveAllowed = true, nowMs = 1_300L)
        )
        val again = origin.handle(SwarmEvent.PieceRead("rx", "g1", m.root, 1, null, PieceReadStatus.GONE, 1_400L))
        assertTrue(again.none { it is SwarmCommand.SignSourceStatus }, "the first LOST already told everyone")
        assertTrue(sentTo(again, "rx").any { it is SwarmFrame.Reject }, "the requester still gets its answer")
    }

    @Test
    fun `a member that connects later is told the source is lost`() {
        val (origin, cmds, m) = originAfterFailedRead(PieceReadStatus.GONE)
        val sign = cmds.filterIsInstance<SwarmCommand.SignSourceStatus>().single()
        val frame = SwarmFrame.SourceStatus("g1", m.root, "origin", "m1", sign.status, sign.reason, sign.atMs, ByteArray(64) { 1 })
        origin.handle(SwarmEvent.SourceStatusSigned(frame, 1_210L))

        val up = origin.handle(SwarmEvent.PeerUp("late", setOf("sw1"), 2_000L))
        assertTrue(sentTo(up, "late").any { it is SwarmFrame.SourceStatus }, "a member that was away hears it on connect")
        // and the Summary it gets must not claim the piece the origin cannot read
        val summary = sentTo(up, "late").filterIsInstance<SwarmFrame.Summary>().single().entries.single()
        assertTrue(summary.state != SwarmContentState.ALL, "the Summary must not claim a complete file")
    }

    @Test
    fun `a restarted origin still knows its source is lost and signs the status again`() {
        val (_, _, m) = originAfterFailedRead(PieceReadStatus.GONE)
        // what the store holds after the failed read: the cleared bit, persisted
        val record = originRecordWithBits(m, bitsCleared = listOf(0))

        val restarted = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "origin", seed = 5L)
        val cmds = restarted.handle(SwarmEvent.Restored(record, 5_000L))
        assertTrue(cmds.any { it is SwarmCommand.SignSourceStatus }, "the origin re-announces the loss without waiting for a request")
        val up = restarted.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 5_100L))
        val entry = sentTo(up, "rx").filterIsInstance<SwarmFrame.Summary>().single().entries.single()
        assertTrue(entry.state != SwarmContentState.ALL, "no claim of a complete file after restart")
    }

    @Test
    fun `a receiver ignores a SourceStatus from a member that is not the origin of that content`() {
        val m = manifest()
        val rx = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "rx", seed = 2L)
        rx.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 900L))
        rx.handle(announce(m, origin = false))
        val forged = SwarmFrame.SourceStatus("g1", m.root, "someoneElse", "m1", SourceState.LOST, SourceReason.DELETED, 2_000L, ByteArray(64) { 1 })
        val cmds = rx.handle(SwarmEvent.SourceStatusArrived("someoneElse", forged, signatureValid = true, nowMs = 2_000L))
        val row = cmds.filterIsInstance<SwarmCommand.PublishRow>().lastOrNull()
        assertTrue(row == null || row.waitReason != com.transfer.flash.core.swarm.model.SwarmWaitReason.WAITING_FOR_HOLDERS)
    }

    private fun originRecordWithBits(m: SwarmManifest, bitsCleared: List<Int>): com.transfer.flash.core.swarm.model.SwarmContentRecord {
        val bits = com.transfer.flash.core.swarm.model.Bitfield(m.pieceHashes.size)
        for (i in 0 until m.pieceHashes.size) if (i !in bitsCleared) bits.set(i, true)
        return com.transfer.flash.core.swarm.model.SwarmContentRecord(
            root = m.root,
            groupId = "g1",
            messageId = "m1",
            role = com.transfer.flash.core.swarm.model.SwarmRole.ORIGIN,
            originId = "origin",
            originKey = "keyOrig",
            fileName = "f.bin",
            mime = "application/octet-stream",
            totalSize = m.totalSize,
            pieceSize = m.pieceSize,
            manifestBytes = ManifestCodec.encodeCanonical(m),
            bits = bits.toByteArray(),
            bytesDone = m.totalSize,
            state = com.transfer.flash.core.swarm.model.SwarmLifecycleState.ACTIVE,
            waitReason = null,
            failReason = null,
            localTransferId = "m1",
            sourceUri = "content://x",
            sourcePersistent = true,
            partialKey = "part",
            finalPath = null,
            identitySize = m.totalSize,
            identityModifiedMs = 1_000L,
            deliveredTo = emptySet(),
            createdAtMs = 1_000L,
            lastProgressAtMs = 1_000L,
            expiresAtMs = 10_000_000L,
        )
    }
}
