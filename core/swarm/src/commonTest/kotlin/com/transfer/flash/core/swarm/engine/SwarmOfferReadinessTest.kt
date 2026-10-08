package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ERROR-119: an offer waits for a person to tap Accept. The manifest and the origin's piece map do not need the person, so a
 * receiver gets them while the offer is on screen, and the first member to accept is served at once, without waiting for
 * the others to accept.
 */
class SwarmOfferReadinessTest {

    private fun manifest(pieceCount: Int = 6, pieceSize: Int = 65536): SwarmManifest {
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

    private fun announce(m: SwarmManifest, origin: Boolean) = SwarmEvent.Announced(
        groupId = "g1", messageId = "m1", originId = "origin", originKey = "k", root = m.root,
        totalSize = m.totalSize, pieceSize = m.pieceSize, fileName = "f.bin", mime = "application/octet-stream",
        sentAtMs = 1_000L, expiresAtMs = 10_000_000L, isOrigin = origin, autoAccept = false,
        manifest = if (origin) m else null, nowMs = 1_000L,
    )

    private fun sentTo(cmds: List<SwarmCommand>, peer: String): List<SwarmFrame> =
        cmds.filterIsInstance<SwarmCommand.Send>().filter { it.peerId == peer }.map { it.frame }

    private fun rx(id: String, seed: Long) = SwarmEngine(SwarmConfig(autoAcceptIncoming = false), id, seed).also {
        it.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 900L))
    }

    @Test
    fun `an offer fetches the manifest and tells the origin before anyone taps Accept`() {
        val m = manifest()
        val e = rx("a", 2L)
        val cmds = e.handle(announce(m, origin = false))
        val toOrigin = sentTo(cmds, "origin")
        assertTrue(toOrigin.any { it is SwarmFrame.ManifestGet }, "the manifest is fetched during the offer")
        assertTrue(toOrigin.any { it is SwarmFrame.Summary }, "the origin hears about this member during the offer")
        assertTrue(toOrigin.none { it is SwarmFrame.Request }, "no file data is requested before consent")
        assertEquals(SwarmLifecycleState.OFFERED, e.snapshot().contents["g1" to m.root]!!.state)
    }

    @Test
    fun `Accept requests the first piece at once and the sender serves it while the others have not accepted`() {
        val m = manifest()
        val origin = SwarmEngine(SwarmConfig(autoAcceptIncoming = false), "origin", seed = 1L)
        val members = listOf("a", "b", "c")
        members.forEach { origin.handle(SwarmEvent.PeerUp(it, setOf("sw1"), 900L)) }
        origin.handle(announce(m, origin = true))

        val engines = members.mapIndexed { i, id -> id to rx(id, 10L + i) }.toMap()
        // Every member's offer arrives; each answers with a Summary and the origin replies with its piece map.
        for ((id, e) in engines) {
            val summary = sentTo(e.handle(announce(m, origin = false)), "origin").filterIsInstance<SwarmFrame.Summary>().single()
            val reply = sentTo(origin.handle(SwarmEvent.SummaryArrived(id, summary, 1_100L)), id)
                .filterIsInstance<SwarmFrame.Summary>().single()
            e.handle(SwarmEvent.SummaryArrived("origin", reply, 1_200L))
            e.handle(SwarmEvent.ManifestComplete("origin", "g1", m.root, m, valid = true, nowMs = 1_300L))
            assertEquals(SwarmLifecycleState.OFFERED, e.snapshot().contents["g1" to m.root]!!.state, "still waiting for a person")
        }

        // Only "b" accepts. Its first Request goes out in the Accept itself.
        val acceptCmds = engines.getValue("b").handle(SwarmEvent.Accepted("g1", m.root, 2_000L))
        val request = sentTo(acceptCmds, "origin").filterIsInstance<SwarmFrame.Request>()
        assertTrue(request.isNotEmpty(), "Accept must request pieces without another round trip")

        // The origin serves it although "a" and "c" have not accepted.
        val served = origin.handle(SwarmEvent.RequestArrived("b", request.first(), allowed = true, nowMs = 2_050L))
        assertTrue(served.any { it is SwarmCommand.ReadPiece && it.forPeerId == "b" }, "the first member to accept is served immediately")
        assertTrue(served.none { it is SwarmCommand.Send && it.frame is SwarmFrame.Reject }, "and is not turned away")
    }
}
