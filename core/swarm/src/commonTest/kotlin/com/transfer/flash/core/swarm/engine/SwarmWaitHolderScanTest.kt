package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmWaitReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O25-08: the wait classifier used to compute the full holder statistics (O(pieces × peers)) and discard them, then
 * walk every piece twice more looking for a holder of a missing piece. It now answers "does this peer hold anything I
 * am missing?" a word at a time.
 *
 * The word-wise answer must equal the per-piece answer for every shape, so that is compared against a reference loop
 * over random bitfields, and the three classifications it feeds are pinned by example.
 */
class SwarmWaitHolderScanTest {

    @Test
    fun `word-wise hasBitOutside agrees with a per-piece scan for every shape`() {
        val random = SeededRandom(2024L)
        for (size in listOf(0, 1, 7, 63, 64, 65, 127, 128, 129, 1000, 4096)) {
            for (round in 0 until 40) {
                val mine = Bitfield(size)
                val theirs = Bitfield(size)
                for (p in 0 until size) {
                    if (random.nextInt(4) == 0) mine.set(p, true)
                    if (random.nextInt(4) == 0) theirs.set(p, true)
                }
                // Sometimes make "theirs" a strict superset (no missing bits at all).
                if (round % 7 == 0) {
                    for (p in 0 until size) if (mine.get(p)) theirs.set(p, true)
                }
                var expected = false
                for (p in 0 until size) {
                    if (theirs.get(p) && !mine.get(p)) {
                        expected = true
                        break
                    }
                }
                assertEquals(expected, theirs.hasBitOutside(mine), "size=$size round=$round")
            }
        }
    }

    @Test
    fun `a connected holder of a missing piece means no wait reason`() {
        val (manifest, _) = manifest(4)
        val engine = receiver(manifest)
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1_000L))
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", manifest.root, manifest, true, 1_000L))
        // We hold piece 0; peerA announces piece 1.
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 0, 1_000L))
        engine.handle(SwarmEvent.HaveArrived("peerA", SwarmFrame.Have("g1", manifest.root, ranges(1, 1)), 1_000L))
        assertEquals(null, engine.snapshot().contents.getValue("g1" to manifest.root).waitReason)
    }

    @Test
    fun `a connected peer that only holds what we already have leaves us waiting for a session`() {
        val (manifest, _) = manifest(4)
        val engine = receiver(manifest, originId = "originDevice")
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1_000L))
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", manifest.root, manifest, true, 1_000L))
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 0, 1_000L))
        engine.handle(SwarmEvent.PieceStored("g1", manifest.root, 1, 1_000L))
        // peerA holds exactly pieces 0 and 1, which we already have.
        engine.handle(SwarmEvent.HaveArrived("peerA", SwarmFrame.Have("g1", manifest.root, ranges(0, 2)), 1_000L))
        // peerB is known but offline and holds piece 2.
        engine.handle(SwarmEvent.PeerUp("peerB", setOf("sw1"), 1_000L))
        engine.handle(SwarmEvent.HaveArrived("peerB", SwarmFrame.Have("g1", manifest.root, ranges(2, 1)), 1_000L))
        engine.handle(SwarmEvent.PeerDown("peerB", 2_000L))

        val snapshot = engine.snapshot().contents.getValue("g1" to manifest.root)
        assertEquals(
            SwarmWaitReason.WAITING_FOR_SESSION,
            snapshot.waitReason,
            "a disconnected holder of a missing piece is a session wait, not a holder wait",
        )
        assertEquals(1, snapshot.holdersOnline)
    }

    @Test
    fun `distributed copies counts the least-held piece, not every holder`() {
        val (manifest, _) = manifest(4)
        val origin = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "originDevice",
            seed = 5L,
        )
        origin.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "originDevice",
                originKey = "keyOrig",
                root = manifest.root,
                totalSize = manifest.totalSize,
                pieceSize = manifest.pieceSize,
                fileName = "f.bin",
                mime = "application/octet-stream",
                sentAtMs = 1_000L,
                expiresAtMs = 10_000_000L,
                isOrigin = true,
                manifest = manifest,
                nowMs = 1_000L,
            )
        )
        origin.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1_000L))
        origin.handle(SwarmEvent.PeerUp("peerB", setOf("sw1"), 1_000L))
        // peerA holds pieces 0..2, peerB holds 0..3: the least-held piece (3) has one holder.
        origin.handle(SwarmEvent.HaveArrived("peerA", SwarmFrame.Have("g1", manifest.root, ranges(0, 3)), 1_100L))
        origin.handle(SwarmEvent.HaveArrived("peerB", SwarmFrame.Have("g1", manifest.root, ranges(0, 4)), 1_100L))

        val snapshot = origin.snapshot().contents.getValue("g1" to manifest.root)
        assertEquals(2, snapshot.holdersOnline)
        assertEquals(1, snapshot.distributedCopies, "piece 3 has a single holder")
        assertTrue(snapshot.canGoOffline, "every piece has at least one holder")

        // peerB goes away: piece 3 now has no holder at all.
        origin.handle(SwarmEvent.PeerDown("peerB", 2_000L))
        val after = origin.snapshot().contents.getValue("g1" to manifest.root)
        assertEquals(0, after.distributedCopies)
        assertTrue(!after.canGoOffline)
    }

    private fun receiver(manifest: SwarmManifest, originId: String = "peerA"): SwarmEngine {
        val engine = SwarmEngine(
            config = SwarmConfig(autoAcceptIncoming = true),
            localDeviceId = "localDevice",
            seed = 7L,
        )
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = originId,
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
        return engine
    }

    private fun ranges(start: Int, count: Int) =
        listOf(com.transfer.flash.core.swarm.model.PieceRange(start, count))

    private fun manifest(pieceCount: Int): Pair<SwarmManifest, ByteArray> {
        val pieceSize = 65_536
        val totalSize = pieceCount.toLong() * pieceSize
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = totalSize,
            fileSha256 = Sha256.digest(ByteArray(pieceCount) { it.toByte() }),
            pieceHashes = List(pieceCount) { Sha256.digest(ByteArray(pieceSize) { b -> (it + b).toByte() }) },
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        val canonical = ManifestCodec.encodeCanonical(unsigned)
        return unsigned.copy(root = ManifestCodec.computeRoot(canonical)) to canonical
    }
}
