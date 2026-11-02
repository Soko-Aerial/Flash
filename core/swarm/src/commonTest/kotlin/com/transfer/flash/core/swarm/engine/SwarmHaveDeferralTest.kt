package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O08-07: in a full group mesh every stored piece is announced to every member, so `HaveArrived` runs tens of
 * times per second per node. It used to reclassify the wait reason, republish the row and rerun the whole scheduler
 * even when the request window was already full and no request could be issued.
 *
 * A HAVE while the window is full must now do exactly one thing: update the peer's bitfield. The row and the schedule
 * are answered at the next tick.
 */
class SwarmHaveDeferralTest {

    private val pieceSize = 1_048_576

    /** 300 pieces of 1 MiB: four peers with a four-piece window each fill a MEDIUM profile's 16 MiB budget exactly. */
    private fun manifest(pieceCount: Int = 300): SwarmManifest {
        val totalSize = pieceCount.toLong() * pieceSize
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = totalSize,
            fileSha256 = Sha256.digest(ByteArray(pieceCount) { it.toByte() }),
            pieceHashes = List(pieceCount) { Sha256.digest(ByteArray(64) { b -> (it + b).toByte() }) },
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        val canonical = ManifestCodec.encodeCanonical(unsigned)
        return unsigned.copy(root = ManifestCodec.computeRoot(canonical))
    }

    private val peers = listOf("peerA", "peerB", "peerC", "peerD")

    private fun receiverWithFilledWindow(manifest: SwarmManifest): SwarmEngine {
        val engine = SwarmEngine(
            config = SwarmConfig(profile = SwarmProfile.MEDIUM, autoAcceptIncoming = true),
            localDeviceId = "local",
            seed = 3L,
        )
        for (peer in peers) engine.handle(SwarmEvent.PeerUp(peer, setOf("sw1"), 1_000L))
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "peerA",
                originKey = "keyA",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "f.bin",
                mime = "application/octet-stream",
                sentAtMs = 1_000L,
                expiresAtMs = 10_000_000L,
                isOrigin = false,
                autoAccept = true,
                nowMs = 1_000L,
            )
        )
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", manifest.root, manifest, true, 1_000L))
        // Every peer announces everything; four peers x a four-piece window fill the 16 MiB in-flight budget.
        for (peer in peers) {
            engine.handle(SwarmEvent.HaveAllArrived(peer, SwarmFrame.HaveAll("g1", manifest.root), 1_100L))
        }
        return engine
    }

    @Test
    fun `a have storm on a full window publishes and schedules nothing until the tick`() {
        val manifest = manifest()
        val engine = receiverWithFilledWindow(manifest)

        // The window is full: prove it by asking the engine for one more slot's worth of work.
        val fill = engine.handle(SwarmEvent.Tick(1_200L))
        val requestsDuringFill = fill.filterIsInstance<SwarmCommand.Send>()
            .count { it.frame is SwarmFrame.Request }
        assertTrue(requestsDuringFill == 0, "the window must already be full, got $requestsDuringFill new requests")

        var published = 0
        var scheduled = 0
        for (i in 0 until 100) {
            val cmds = engine.handle(
                SwarmEvent.HaveArrived(
                    "peerA",
                    SwarmFrame.Have("g1", manifest.root, listOf(PieceRange(0, 300))),
                    1_300L + i,
                )
            )
            published += cmds.count { it is SwarmCommand.PublishRow }
            scheduled += cmds.filterIsInstance<SwarmCommand.Send>().count { it.frame is SwarmFrame.Request }
        }
        assertEquals(0, scheduled, "no request can be issued while the window is full")
        assertEquals(0, published, "100 HAVE frames must not republish the row 100 times")

        // The tick is where the deferred answer comes from — and it comes once.
        val tick = engine.handle(SwarmEvent.Tick(2_000L))
        assertEquals(
            1,
            tick.count { it is SwarmCommand.PublishRow },
            "the deferred HAVE is answered by exactly one row publish on the next tick",
        )
    }

    @Test
    fun `a have with room in the window still schedules at once`() {
        val manifest = manifest(pieceCount = 4)
        val engine = SwarmEngine(
            config = SwarmConfig(profile = SwarmProfile.MEDIUM, autoAcceptIncoming = true),
            localDeviceId = "local",
            seed = 3L,
        )
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1_000L))
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "peerA",
                originKey = "keyA",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "f.bin",
                mime = "application/octet-stream",
                sentAtMs = 1_000L,
                expiresAtMs = 10_000_000L,
                isOrigin = false,
                autoAccept = true,
                nowMs = 1_000L,
            )
        )
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", manifest.root, manifest, true, 1_000L))
        val cmds = engine.handle(
            SwarmEvent.HaveArrived("peerA", SwarmFrame.Have("g1", manifest.root, listOf(PieceRange(0, 4))), 1_100L)
        )
        assertTrue(
            cmds.filterIsInstance<SwarmCommand.Send>().any { it.frame is SwarmFrame.Request },
            "with an empty window the first HAVE must still start the transfer immediately",
        )
        assertTrue(cmds.any { it is SwarmCommand.PublishRow })
    }
}
