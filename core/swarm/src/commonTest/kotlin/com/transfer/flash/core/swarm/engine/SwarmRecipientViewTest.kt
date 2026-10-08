package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sender's per-member view (ERROR-119 follow-up): from each member's own HAVE / HAVE_ALL the origin knows how much of
 * the file that member holds, how fast it is growing, and who has all of it, so the sender can see when it may leave.
 */
class SwarmRecipientViewTest {

    private val pieceSize = 65536

    private fun manifest(pieceCount: Int = 10): SwarmManifest {
        val total = pieceCount.toLong() * pieceSize
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = total,
            fileSha256 = Sha256.digest(ByteArray(8) { 1 }),
            pieceHashes = List(pieceCount) { Sha256.digest(byteArrayOf(it.toByte())) },
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        return unsigned.copy(root = ManifestCodec.computeRoot(ManifestCodec.encodeCanonical(unsigned)))
    }

    private fun origin(m: SwarmManifest, vararg members: String): SwarmEngine {
        val e = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "origin", seed = 1L)
        e.handle(
            SwarmEvent.Announced(
                groupId = "g1", messageId = "m1", originId = "origin", originKey = "k", root = m.root,
                totalSize = m.totalSize, pieceSize = m.pieceSize, fileName = "f.bin", mime = "application/octet-stream",
                sentAtMs = 1_000L, expiresAtMs = 10_000_000L, isOrigin = true, autoAccept = true, manifest = m, nowMs = 1_000L,
            ),
        )
        for (id in members) e.handle(SwarmEvent.PeerUp(id, setOf("sw1"), 1_100L))
        return e
    }

    private fun have(e: SwarmEngine, m: SwarmManifest, peer: String, start: Int, count: Int, at: Long) =
        e.handle(SwarmEvent.HaveArrived(peer, SwarmFrame.Have("g1", m.root, listOf(PieceRange(start, count))), at))

    private fun view(e: SwarmEngine, m: SwarmManifest) = e.snapshot().contents["g1" to m.root]!!.recipients.associateBy { it.peerId }

    @Test
    fun `a connected member that has announced nothing is listed with zero bytes`() {
        val m = manifest()
        val e = origin(m, "a", "b")
        val v = view(e, m)
        assertEquals(setOf("a", "b"), v.keys)
        assertEquals(0L, v.getValue("a").bytesHeld)
        assertFalse(v.getValue("a").hasAll)
    }

    @Test
    fun `bytes held follow the member's HAVE and the rate follows how fast it grows`() {
        val m = manifest()
        val e = origin(m, "a")
        have(e, m, "a", 0, 1, 2_000L) // baseline: no rate yet
        assertEquals(0L, view(e, m).getValue("a").rateBytesPerSec)
        have(e, m, "a", 1, 4, 3_000L) // 4 pieces in 1 s
        val a = view(e, m).getValue("a")
        assertEquals(5L * pieceSize, a.bytesHeld)
        assertEquals(4L * pieceSize, a.rateBytesPerSec)
        assertFalse(a.hasAll)
    }

    @Test
    fun `a member that stops growing shows no rate`() {
        val m = manifest()
        val e = origin(m, "a")
        have(e, m, "a", 0, 1, 2_000L)
        have(e, m, "a", 1, 4, 3_000L)
        e.handle(SwarmEvent.Tick(9_000L)) // 6 s of silence, past the staleness window
        assertEquals(0L, view(e, m).getValue("a").rateBytesPerSec)
        assertEquals(5L * pieceSize, view(e, m).getValue("a").bytesHeld, "what it holds is kept")
    }

    @Test
    fun `HaveAll marks a member as having the file and it stays listed after it goes offline`() {
        val m = manifest()
        val e = origin(m, "a", "b")
        e.handle(SwarmEvent.HaveAllArrived("a", SwarmFrame.HaveAll("g1", m.root), 2_000L))
        var v = view(e, m)
        assertTrue(v.getValue("a").hasAll)
        assertEquals(m.totalSize, v.getValue("a").bytesHeld)
        assertEquals(0L, v.getValue("a").rateBytesPerSec)
        e.handle(SwarmEvent.PeerDown("a", 2_500L))
        e.handle(SwarmEvent.PeerDown("b", 2_500L))
        v = view(e, m)
        assertTrue(v.getValue("a").hasAll && !v.getValue("a").online, "done and gone: still counted")
        assertNull(v["b"], "a member that never had anything and is offline is not listed")
    }

    @Test
    fun `the first member to announce is not slowed by one that has not`() {
        val m = manifest()
        val e = origin(m, "fast", "slow")
        have(e, m, "fast", 0, 2, 2_000L)
        have(e, m, "fast", 2, 6, 3_000L)
        val v = view(e, m)
        assertEquals(8L * pieceSize, v.getValue("fast").bytesHeld)
        assertEquals(0L, v.getValue("slow").bytesHeld)
    }
}
