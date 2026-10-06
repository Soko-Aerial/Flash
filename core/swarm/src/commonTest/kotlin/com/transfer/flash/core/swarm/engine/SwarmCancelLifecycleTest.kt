package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmContentState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwarmCancelLifecycleTest {

    private fun createManifest(pieceCount: Int = 2, pieceSize: Int = 65536): Pair<SwarmManifest, ByteArray> {
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

    @Test
    fun `forged cancel signature records strikes and bans peer after 3 strikes`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "rxNode",
            seed = 42L,
        )

        engine.handle(SwarmEvent.PeerUp("peerBad", setOf("sw1"), 1000L))
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "originNode",
                originKey = "keyOrig",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "test.bin",
                mime = "application/octet-stream",
                sentAtMs = 1000L,
                expiresAtMs = 10_000_000L,
                isOrigin = false,
                nowMs = 1000L,
            )
        )

        val forgedCancel = SwarmFrame.Cancel(
            groupId = "g1",
            root = manifest.root,
            originId = "originNode",
            messageId = "m1",
            reason = SwarmTombstoneReason.USER,
            cancelledAtMs = 1050L,
            signature = byteArrayOf(1, 2, 3),
        )

        // Strike 1
        val cmds1 = engine.handle(SwarmEvent.CancelArrived("peerBad", forgedCancel, signatureValid = false, nowMs = 1100L))
        assertEquals(1, engine.strikeBook.getStrikes("peerBad", manifest.root))
        assertFalse(engine.strikeBook.isBanned("peerBad", manifest.root))
        assertTrue(cmds1.filterIsInstance<SwarmCommand.DeletePartial>().isEmpty())

        // Strike 2
        engine.handle(SwarmEvent.CancelArrived("peerBad", forgedCancel, signatureValid = false, nowMs = 1101L))
        assertEquals(2, engine.strikeBook.getStrikes("peerBad", manifest.root))
        assertFalse(engine.strikeBook.isBanned("peerBad", manifest.root))

        // Strike 3 -> Banned
        engine.handle(SwarmEvent.CancelArrived("peerBad", forgedCancel, signatureValid = false, nowMs = 1102L))
        assertEquals(3, engine.strikeBook.getStrikes("peerBad", manifest.root))
        assertTrue(engine.strikeBook.isBanned("peerBad", manifest.root))

        // Content remains ACTIVE
        val snap = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap)
        assertEquals(SwarmLifecycleState.ACTIVE, snap.state)
    }

    @Test
    fun `origin cancel with USER and DELETED reasons emit corresponding SignTombstone`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(),
            localDeviceId = "originNode",
            seed = 42L,
        )

        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "originNode",
                originKey = "keyOrig",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "test.bin",
                mime = "application/octet-stream",
                sentAtMs = 1000L,
                expiresAtMs = 10_000_000L,
                isOrigin = true,
                nowMs = 1000L,
            )
        )

        // Test USER reason
        val userCmds = engine.handle(
            SwarmEvent.LocalCancel("g1", manifest.root, asOrigin = true, reason = SwarmTombstoneReason.USER, nowMs = 1100L)
        )
        val userSign = userCmds.filterIsInstance<SwarmCommand.SignTombstone>().firstOrNull()
        assertNotNull(userSign)
        assertEquals(SwarmTombstoneReason.USER, userSign.reason)

        // Test DELETED reason
        val deletedCmds = engine.handle(
            SwarmEvent.LocalCancel("g1", manifest.root, asOrigin = true, reason = SwarmTombstoneReason.DELETED, nowMs = 1200L)
        )
        val deletedSign = deletedCmds.filterIsInstance<SwarmCommand.SignTombstone>().firstOrNull()
        assertNotNull(deletedSign)
        assertEquals(SwarmTombstoneReason.DELETED, deletedSign.reason)
    }

    @Test
    fun `completed receiver keeps file on cancel and rejects subsequent piece requests`() {
        val (manifest, canonicalBytes) = createManifest(pieceCount = 2, pieceSize = 65536)
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "rxNode",
            seed = 42L,
        )

        engine.handle(SwarmEvent.PeerUp("peerOrigin", setOf("sw1"), 1000L))
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "peerOrigin",
                originKey = "keyOrig",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "test.bin",
                mime = "application/octet-stream",
                sentAtMs = 1000L,
                expiresAtMs = 10_000_000L,
                isOrigin = false,
                nowMs = 1000L,
            )
        )

        // Provide manifest
        engine.handle(
            SwarmEvent.ManifestPartArrived(
                peerId = "peerOrigin",
                part = SwarmFrame.ManifestPart("g1", manifest.root, 0, 1, canonicalBytes),
                nowMs = 1050L,
            )
        )

        // Receive both pieces to complete
        engine.handle(
            SwarmEvent.PieceArrived("peerOrigin", "g1", manifest.root, 0, ByteArray(65536) { 0 }, verified = true, nowMs = 1100L)
        )
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 0, 1105L))

        engine.handle(
            SwarmEvent.PieceArrived("peerOrigin", "g1", manifest.root, 1, ByteArray(65536) { 1 }, verified = true, nowMs = 1110L)
        )
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 1, 1115L))

        // Complete finalization
        engine.handle(
            SwarmEvent.FinalizeResult(
                groupId = "g1",
                root = manifest.root,
                ok = true,
                finalPath = "/dest/test.bin",
                nowMs = 1120L,
            )
        )

        val snapBeforeCancel = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snapBeforeCancel)
        assertEquals(SwarmLifecycleState.COMPLETE, snapBeforeCancel.state)

        // Tombstone arrives
        val tombstone = SwarmTombstone(
            groupId = "g1",
            root = manifest.root,
            originId = "peerOrigin",
            messageId = "m1",
            reason = SwarmTombstoneReason.USER,
            cancelledAtMs = 1150L,
            signature = ByteArray(64),
        )
        val cancelCmds = engine.handle(
            SwarmEvent.CancelArrived(
                peerId = "peerOrigin",
                frame = SwarmFrame.Cancel(
                    groupId = "g1",
                    root = manifest.root,
                    originId = "peerOrigin",
                    messageId = "m1",
                    reason = SwarmTombstoneReason.USER,
                    cancelledAtMs = 1150L,
                    signature = ByteArray(64),
                ),
                signatureValid = true,
                nowMs = 1160L,
            )
        )

        // Completed receiver must NOT delete partial (file is finalized)
        assertTrue(cancelCmds.filterIsInstance<SwarmCommand.DeletePartial>().isEmpty(), "Must not delete file of completed transfer")

        val snapAfterCancel = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snapAfterCancel)
        assertEquals(SwarmLifecycleState.COMPLETE, snapAfterCancel.state, "Completed receiver retains COMPLETE state")

        // Another peer requests a piece for this tombstoned content
        engine.handle(SwarmEvent.PeerUp("peerOther", setOf("sw1"), 1200L))
        val reqCmds = engine.handle(
            SwarmEvent.RequestArrived(
                peerId = "peerOther",
                frame = SwarmFrame.Request("g1", manifest.root, listOf(0)),
                allowed = true,
                nowMs = 1210L,
            )
        )

        val reject = reqCmds.filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filterIsInstance<SwarmFrame.Reject>()
            .firstOrNull()

        assertNotNull(reject, "Must send Reject frame")
        assertEquals(SwarmRejectReason.CANCELLED, reject.reason, "Must reject with CANCELLED reason")
    }

    @Test
    fun `summary containing tombstone cancels content before issuing any request`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "rxNode",
            seed = 42L,
        )

        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "originNode",
                originKey = "keyOrig",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "test.bin",
                mime = "application/octet-stream",
                sentAtMs = 1000L,
                expiresAtMs = 10_000_000L,
                isOrigin = false,
                nowMs = 1000L,
            )
        )

        val tombstone = SwarmTombstone(
            groupId = "g1",
            root = manifest.root,
            originId = "originNode",
            messageId = "m1",
            reason = SwarmTombstoneReason.USER,
            cancelledAtMs = 1050L,
            signature = ByteArray(64),
        )

        // Peer connects and sends Summary with tombstone
        engine.handle(SwarmEvent.PeerUp("originNode", setOf("sw1"), 1100L))
        val summaryCmds = engine.handle(
            SwarmEvent.SummaryArrived(
                peerId = "originNode",
                frame = SwarmFrame.Summary(
                    groupId = "g1",
                    tombstones = listOf(tombstone),
                    entries = emptyList(),
                ),
                nowMs = 1105L,
            )
        )

        // Content transitioned to CANCELLED
        val snap = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap)
        assertEquals(SwarmLifecycleState.CANCELLED, snap.state)

        // No Request or ManifestGet frames should be emitted
        val requests = summaryCmds.filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filter { it is SwarmFrame.Request || it is SwarmFrame.ManifestGet }

        assertTrue(requests.isEmpty(), "Must not send any Request or ManifestGet for cancelled content")
    }
}
