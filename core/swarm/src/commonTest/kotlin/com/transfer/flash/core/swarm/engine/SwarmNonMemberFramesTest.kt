package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ERROR-110: a Summary, Have or HaveAll from a device that is not (or no longer) a member of the group was applied like a
 * member's. The origin counted such a HaveAll as a delivery, which feeds "safe to leave", and a stranger's Summary shaped the
 * piece map and the serving state the scheduler works from.
 */
class SwarmNonMemberFramesTest {

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
        groupId = "g1", messageId = "m1", originId = "origin", originKey = "keyOrig", root = m.root,
        totalSize = m.totalSize, pieceSize = m.pieceSize, fileName = "f.bin", mime = "application/octet-stream",
        sentAtMs = 1_000L, expiresAtMs = 10_000_000L, isOrigin = origin, autoAccept = true, manifest = m, nowMs = 1_000L,
    )

    /** An origin with a member and a stranger connected; the stranger is denied for g1. */
    private fun origin(m: SwarmManifest): SwarmEngine {
        val origin = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "origin", seed = 1L)
        origin.handle(announce(m, origin = true))
        origin.handle(SwarmEvent.PeerUp("member", setOf("sw1"), 1_100L))
        origin.handle(SwarmEvent.PeerUp("stranger", setOf("sw1"), 1_100L, deniedGroups = setOf("g1")))
        return origin
    }

    private fun delivered(e: SwarmEngine, m: SwarmManifest) = e.snapshot().contents["g1" to m.root]!!.deliveredTo

    @Test
    fun `a HaveAll from a non-member is not a delivery`() {
        val m = manifest()
        val origin = origin(m)
        origin.handle(SwarmEvent.HaveAllArrived("stranger", SwarmFrame.HaveAll("g1", m.root), 1_200L))
        assertFalse("stranger" in delivered(origin, m), "a stranger is not a member that has the file")
        origin.handle(SwarmEvent.HaveAllArrived("member", SwarmFrame.HaveAll("g1", m.root), 1_300L))
        assertEquals(setOf("member"), delivered(origin, m))
    }

    @Test
    fun `a Summary from a non-member applies nothing, not even a tombstone it carries`() {
        val m = manifest()
        val rx = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "rx", seed = 2L)
        rx.handle(announce(m, origin = false))
        rx.handle(SwarmEvent.PeerUp("stranger", setOf("sw1"), 1_100L, deniedGroups = setOf("g1")))
        val tombstone = SwarmTombstone("g1", m.root, "origin", "m1", SwarmTombstoneReason.USER, 1_150L, ByteArray(64) { 1 })
        val before = rx.snapshot().contents["g1" to m.root]!!.state
        val cmds = rx.handle(SwarmEvent.SummaryArrived("stranger", SwarmFrame.Summary("g1", listOf(tombstone), emptyList()), 1_200L))
        assertEquals(before, rx.snapshot().contents["g1" to m.root]!!.state, "a non-member's Summary is ignored whole")
        assertTrue(cmds.filterIsInstance<SwarmCommand.Send>().none { it.peerId == "stranger" })
    }
}
