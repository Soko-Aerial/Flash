package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmContentState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.SwarmFailReason
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.swarm.model.SwarmWaitReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwarmEngineStepTest {

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
            root = ContentRoot.fromBytes(ByteArray(32)), // placeholder
        )
        val canonicalBytes = ManifestCodec.encodeCanonical(unsignedManifest)
        val root = ManifestCodec.computeRoot(canonicalBytes)
        val manifest = unsignedManifest.copy(root = root)
        return manifest to canonicalBytes
    }

    @Test
    fun `step 4a - announcement auto-accept and manifest get flow`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "localRx",
            seed = 42L,
        )

        // 1. PeerUp
        engine.handle(SwarmEvent.PeerUp(peerId = "origin", features = setOf("sw1"), nowMs = 1000L))

        // 2. Announced
        val cmds = engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "origin",
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

        // Receiver in ACTIVE state should request manifest fragment 0
        val manifestGet = cmds.filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filterIsInstance<SwarmFrame.ManifestGet>()
            .firstOrNull()

        assertNotNull(manifestGet, "Engine should send ManifestGet for fragment 0")
        assertEquals(0, manifestGet.fragmentIndex)
        assertEquals(manifest.root, manifestGet.root)
    }

    @Test
    fun `step 4a - corrupt manifest strikes peer and retries elsewhere`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "localRx",
            seed = 42L,
        )
        engine.handle(SwarmEvent.PeerUp("peerBad", setOf("sw1"), 1000L))
        engine.handle(SwarmEvent.PeerUp("peerGood", setOf("sw1"), 1000L))

        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "origin",
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

        // PeerBad sends corrupt manifest (valid = false)
        val cmds = engine.handle(
            SwarmEvent.ManifestComplete(
                peerId = "peerBad",
                groupId = "g1",
                root = manifest.root,
                manifest = manifest,
                valid = false,
                nowMs = 1100L,
            )
        )

        // Engine should retry manifest fetch from another peer
        val retrySend = cmds.filterIsInstance<SwarmCommand.Send>()
            .filter { it.peerId == "peerGood" }
            .map { it.frame }
            .filterIsInstance<SwarmFrame.ManifestGet>()
            .firstOrNull()

        assertNotNull(retrySend, "Should retry manifest fetch with peerGood")
    }

    @Test
    fun `step 4g - have batching flushes only once per second per peer`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "localRx",
            seed = 42L,
        )
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1000L))
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "origin",
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
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", manifest.root, manifest, true, 1000L))

        // Store piece 0 at 1000L and flush it to establish initial baseline
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 0, nowMs = 1000L))
        val initialFlush = engine.handle(SwarmEvent.Tick(nowMs = 1000L))
        assertTrue(initialFlush.filterIsInstance<SwarmCommand.Send>().any { it.frame is SwarmFrame.Have })

        // Store piece 1 at 1200L
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 1, nowMs = 1200L))

        // Tick at +500ms from last flush (1500L): should NOT flush yet
        val cmds1500 = engine.handle(SwarmEvent.Tick(nowMs = 1500L))
        assertTrue(cmds1500.filterIsInstance<SwarmCommand.Send>().none { it.frame is SwarmFrame.Have })

        // Tick at +1100ms from last flush (2100L): SHOULD flush HAVE
        val cmds2100 = engine.handle(SwarmEvent.Tick(nowMs = 2100L))
        val haveFrame = cmds2100.filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filterIsInstance<SwarmFrame.Have>()
            .firstOrNull()

        assertNotNull(haveFrame, "Should flush HAVE at >= 1000ms")
        assertEquals(1, haveFrame.ranges[0].start)
        assertEquals(1, haveFrame.ranges[0].count)
    }

    @Test
    fun `step 4h - finalize success broadcasts HAVE_ALL`() {
        val (manifest, _) = createManifest(pieceCount = 2)
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "localRx",
            seed = 42L,
        )
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1000L))
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "origin",
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
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", manifest.root, manifest, true, 1000L))

        // Store both pieces
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 0, 1000L))
        val finalCmds = engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 1, 1050L))

        assertTrue(finalCmds.any { it is SwarmCommand.Finalize }, "Should trigger finalize when all pieces stored")

        // FinalizeResult(ok = true)
        val finishCmds = engine.handle(SwarmEvent.FinalizeResult("g1", manifest.root, ok = true, nowMs = 1100L))

        val haveAll = finishCmds.filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filterIsInstance<SwarmFrame.HaveAll>()
            .firstOrNull()

        assertNotNull(haveAll, "Should broadcast HaveAll on completion")
        val snap = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap)
        assertEquals(SwarmLifecycleState.COMPLETE, snap.state)
    }

    @Test
    fun `step 4h - finalize failure retries bad pieces once and then fails DAMAGED`() {
        val (manifest, _) = createManifest(pieceCount = 2)
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "localRx",
            seed = 42L,
        )
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1000L))
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "origin",
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
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", manifest.root, manifest, true, 1000L))
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 0, 1000L))
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 1, 1050L))

        // 1st failure: piece 0 damaged
        val retryCmds = engine.handle(
            SwarmEvent.FinalizeResult("g1", manifest.root, ok = false, badPieces = listOf(0), nowMs = 1100L)
        )
        val snap1 = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap1)
        assertEquals(SwarmLifecycleState.ACTIVE, snap1.state, "Should return to ACTIVE to re-fetch bad pieces")

        // 2nd failure
        val failCmds = engine.handle(
            SwarmEvent.FinalizeResult("g1", manifest.root, ok = false, badPieces = listOf(0), nowMs = 1200L)
        )
        val snap2 = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap2)
        assertEquals(SwarmLifecycleState.FAILED, snap2.state)
        val pubRow = failCmds.filterIsInstance<SwarmCommand.PublishRow>().firstOrNull()
        assertNotNull(pubRow)
        assertEquals(SwarmLifecycleState.FAILED, pubRow.state)
    }

    @Test
    fun `step 4j - origin local cancel signs tombstone and broadcasts CANCEL`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(),
            localDeviceId = "originNode",
            seed = 42L,
        )
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1000L))
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

        // Local cancel as origin
        val cancelCmds = engine.handle(SwarmEvent.LocalCancel("g1", manifest.root, asOrigin = true, nowMs = 1100L))
        val signCmd = cancelCmds.filterIsInstance<SwarmCommand.SignTombstone>().firstOrNull()
        assertNotNull(signCmd, "Origin cancel should ask driver to sign tombstone")

        // Driver reports TombstoneSigned
        val tombstone = SwarmTombstone(
            groupId = "g1",
            root = manifest.root,
            originId = "originNode",
            messageId = "m1",
            reason = SwarmTombstoneReason.USER,
            cancelledAtMs = 1100L,
            signature = byteArrayOf(1, 2, 3),
        )
        val broadcastCmds = engine.handle(SwarmEvent.TombstoneSigned(tombstone, nowMs = 1150L))
        val cancelFrame = broadcastCmds.filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filterIsInstance<SwarmFrame.Cancel>()
            .firstOrNull()

        assertNotNull(cancelFrame, "Should broadcast CANCEL to connected peers")
        assertEquals(tombstone.messageId, cancelFrame.messageId)
    }

    @Test
    fun `step 4j - receiver local cancel is local only without wire frames`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "rxNode",
            seed = 42L,
        )
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1000L))
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

        val cmds = engine.handle(SwarmEvent.LocalCancel("g1", manifest.root, asOrigin = false, nowMs = 1100L))
        assertTrue(cmds.filterIsInstance<SwarmCommand.Send>().isEmpty(), "Receiver cancel must NEVER send wire frames (INV-10)")
        assertTrue(cmds.any { it is SwarmCommand.DeletePartial }, "Receiver cancel must delete partial file")
    }

    @Test
    fun `step 4j - forged cancel is ignored and strikes peer`() {
        val (manifest, _) = createManifest()
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "rxNode",
            seed = 42L,
        )
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1000L))
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

        val cancelFrame = SwarmFrame.Cancel(
            groupId = "g1",
            root = manifest.root,
            originId = "originNode",
            messageId = "m1",
            reason = SwarmTombstoneReason.USER,
            cancelledAtMs = 1050L,
            signature = byteArrayOf(9, 9, 9),
        )

        val cmds = engine.handle(
            SwarmEvent.CancelArrived("peerA", cancelFrame, signatureValid = false, nowMs = 1100L)
        )

        val snap = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap)
        assertEquals(SwarmLifecycleState.ACTIVE, snap.state, "Forged cancel must not cancel content")
        assertTrue(cmds.filterIsInstance<SwarmCommand.DeletePartial>().isEmpty())
    }

    @Test
    fun `step 4k - removal drops all content and deletes partials`() {
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

        val cmds = engine.handle(
            SwarmEvent.MembershipChanged(groupId = "g1", allowedPeers = emptySet(), localActive = false, nowMs = 1100L)
        )

        val snap = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap)
        assertEquals(SwarmLifecycleState.FAILED, snap.state)
        assertTrue(cmds.any { it is SwarmCommand.DeletePartial })
    }

    @Test
    fun `step 4m - retention expiry marks transfer FAILED with EXPIRED`() {
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
                expiresAtMs = 5000L,
                isOrigin = false,
                nowMs = 1000L,
            )
        )

        val cmds = engine.handle(SwarmEvent.Tick(nowMs = 5001L))
        val snap = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap)
        assertEquals(SwarmLifecycleState.FAILED, snap.state)
        assertTrue(cmds.any { it is SwarmCommand.DeletePartial })
    }

    @Test
    fun `step 4n - system suspend suspends transfers with no tombstones`() {
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
                expiresAtMs = 100_000L,
                isOrigin = false,
                nowMs = 1000L,
            )
        )

        val cmds = engine.handle(SwarmEvent.SystemSuspend(nowMs = 2000L))
        val snap = engine.snapshot().contents[Pair("g1", manifest.root)]
        assertNotNull(snap)
        assertEquals(SwarmWaitReason.WAITING_FOR_SYSTEM, snap.waitReason)
        assertTrue(cmds.filterIsInstance<SwarmCommand.SignTombstone>().isEmpty(), "SystemSuspend must never sign tombstone (INV-10)")
    }
}
