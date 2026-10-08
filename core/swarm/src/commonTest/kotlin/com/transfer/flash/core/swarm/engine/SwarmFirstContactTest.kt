package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ERROR-104: the origin's first Summary reaches a member before the chat message that announces the content (the swarm
 * channel is faster than the chat path), so the member drops it as "unknown root". Nothing used to repeat it, which left the
 * receiver without the origin's piece map and so without any request to make.
 */
class SwarmFirstContactTest {

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

    private fun announce(m: SwarmManifest, origin: Boolean, withManifest: Boolean) = SwarmEvent.Announced(
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
        manifest = if (withManifest) m else null,
        nowMs = 1_000L,
    )

    private fun sentTo(cmds: List<SwarmCommand>, peer: String): List<SwarmFrame> =
        cmds.filterIsInstance<SwarmCommand.Send>().filter { it.peerId == peer }.map { it.frame }

    @Test
    fun `receiver learns the origin's pieces although the first Summary came before the announcement`() {
        val m = manifest()
        val origin = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "origin", seed = 1L)
        val rx = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "rx", seed = 2L)
        origin.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 900L))
        rx.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 900L))

        // 1. The origin registers the content and tells its peers; the member does not know the content yet.
        val originSummary = sentTo(origin.handle(announce(m, origin = true, withManifest = true)), "rx")
            .filterIsInstance<SwarmFrame.Summary>().single()
        rx.handle(SwarmEvent.SummaryArrived("origin", originSummary, 1_000L))

        // 2. The chat message arrives: the member announces, and tells the origin what it has.
        val rxAnnounceCmds = rx.handle(announce(m, origin = false, withManifest = true))
        val rxSummary = sentTo(rxAnnounceCmds, "origin").filterIsInstance<SwarmFrame.Summary>().single()
        assertTrue(sentTo(rxAnnounceCmds, "origin").none { it is SwarmFrame.Request }, "no piece map yet, so no request")

        // 3. The origin hears about the member's content and must answer with its own state.
        val originReply = sentTo(origin.handle(SwarmEvent.SummaryArrived("rx", rxSummary, 1_100L)), "rx")
            .filterIsInstance<SwarmFrame.Summary>()
        assertEquals(1, originReply.size, "origin must answer a Summary that shows a root it holds and the peer was unknown for")

        // 4. With the origin's piece map the member can ask for pieces.
        val rxCmds = rx.handle(SwarmEvent.SummaryArrived("origin", originReply.single(), 1_200L))
        assertTrue(sentTo(rxCmds, "origin").any { it is SwarmFrame.Request }, "member requests pieces once it knows the origin has them")
    }

    @Test
    fun `an answered Summary does not start a Summary ping-pong`() {
        val m = manifest()
        val origin = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "origin", seed = 1L)
        val rx = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "rx", seed = 2L)
        origin.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 900L))
        rx.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 900L))
        origin.handle(announce(m, origin = true, withManifest = true))
        val rxSummary = sentTo(rx.handle(announce(m, origin = false, withManifest = true)), "origin")
            .filterIsInstance<SwarmFrame.Summary>().single()

        val first = sentTo(origin.handle(SwarmEvent.SummaryArrived("rx", rxSummary, 1_100L)), "rx")
        val again = sentTo(origin.handle(SwarmEvent.SummaryArrived("rx", rxSummary, 1_200L)), "rx")
        assertEquals(1, first.filterIsInstance<SwarmFrame.Summary>().size)
        assertTrue(again.none { it is SwarmFrame.Summary }, "a peer already known for the root gets no second reply")

        // The receiver's answer to the origin's reply is not another Summary either.
        val reply = first.filterIsInstance<SwarmFrame.Summary>().single()
        val rxBack = sentTo(rx.handle(SwarmEvent.SummaryArrived("origin", reply, 1_300L)), "origin")
        assertTrue(rxBack.none { it is SwarmFrame.Summary })
    }

    @Test
    fun `a holder still answers a late member after a Tick has made its state for that peer`() {
        // ERROR-121: flushPendingHaves creates per-peer state for every content on every Tick, so by the time the late
        // member's Summary arrived the holder believed the member already knew its state and stayed silent.
        val m = manifest()
        val origin = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "origin", seed = 1L)
        val rx = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "rx", seed = 2L)
        origin.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 900L))
        rx.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 900L))
        val originSummary = sentTo(origin.handle(announce(m, origin = true, withManifest = true)), "rx")
            .filterIsInstance<SwarmFrame.Summary>().single()
        rx.handle(SwarmEvent.SummaryArrived("origin", originSummary, 1_000L)) // dropped: root unknown here
        origin.handle(SwarmEvent.Tick(2_000L))
        origin.handle(SwarmEvent.Tick(3_000L))

        val rxSummary = sentTo(rx.handle(announce(m, origin = false, withManifest = true)), "origin")
            .filterIsInstance<SwarmFrame.Summary>().single()
        val reply = sentTo(origin.handle(SwarmEvent.SummaryArrived("rx", rxSummary, 4_000L)), "rx")
            .filterIsInstance<SwarmFrame.Summary>()
        assertEquals(1, reply.size, "the holder must tell the late member what it holds")

        val rxCmds = rx.handle(SwarmEvent.SummaryArrived("origin", reply.single(), 4_100L))
        assertTrue(sentTo(rxCmds, "origin").any { it is SwarmFrame.Request })
    }

    @Test
    fun `a reconnect lets a holder answer again`() {
        val m = manifest()
        val origin = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "origin", seed = 1L)
        val rx = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "rx", seed = 2L)
        origin.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 900L))
        origin.handle(announce(m, origin = true, withManifest = true))
        rx.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 900L))
        val rxSummary = sentTo(rx.handle(announce(m, origin = false, withManifest = true)), "origin")
            .filterIsInstance<SwarmFrame.Summary>().single()
        assertEquals(1, sentTo(origin.handle(SwarmEvent.SummaryArrived("rx", rxSummary, 1_100L)), "rx").size)

        origin.handle(SwarmEvent.PeerDown("rx", 2_000L))
        origin.handle(SwarmEvent.PeerUp("rx", setOf("sw1"), 2_100L))
        val again = sentTo(origin.handle(SwarmEvent.SummaryArrived("rx", rxSummary, 2_200L)), "rx")
        assertEquals(1, again.filterIsInstance<SwarmFrame.Summary>().size, "a new session starts the exchange afresh")
    }
}
