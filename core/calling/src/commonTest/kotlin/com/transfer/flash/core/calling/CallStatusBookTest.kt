package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallReactionKind
import com.transfer.flash.core.calling.model.FlashCallStats
import com.transfer.flash.core.calling.model.FlashLinkQuality
import com.transfer.flash.core.calling.model.linkQuality
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-067: what the peers' controls say, the reaction rules and the link grade. */
class CallStatusBookTest {

    private var now = 1_000_000L
    private val book = CallStatusBook(clock = { now })

    private fun status(
        mic: Boolean? = null,
        cam: Boolean? = null,
        hand: Boolean? = null,
        rv: Boolean? = null,
        reaction: FlashCallReactionKind? = null,
        seq: Long = 0L,
    ) = CallWireFrame.Status("c", "p", micOn = mic, cameraOn = cam, handRaised = hand, receiveVideo = rv, reaction = reaction, reactionSeq = seq)

    @Test
    fun `a peer that never spoke reads as the call looked before status existed`() {
        assertEquals(CallStatusBook.Peer(), book.peer("nobody"))
        assertTrue(book.wantsVideo("nobody"))
    }

    @Test
    fun `a status updates only the fields it states`() {
        book.apply("p", status(mic = false, cam = false))
        assertEquals(CallStatusBook.Peer(micOn = false, cameraOn = false), book.peer("p"))
        book.apply("p", status(hand = true))
        assertEquals(CallStatusBook.Peer(micOn = false, cameraOn = false, handRaised = true), book.peer("p"))
        book.apply("p", status(mic = true, rv = false))
        assertEquals(CallStatusBook.Peer(micOn = true, cameraOn = false, handRaised = true, receiveVideo = false), book.peer("p"))
        assertFalse(book.wantsVideo("p"))
    }

    @Test
    fun `peers do not leak into each other and forget wipes one`() {
        book.apply("a", status(mic = false))
        assertTrue(book.peer("b").micOn)
        book.forget("a")
        assertTrue(book.peer("a").micOn)
    }

    @Test
    fun `a reaction is accepted once per sequence number`() {
        val first = book.apply("p", status(reaction = FlashCallReactionKind.LOVE, seq = 10))
        assertNotNull(first)
        assertEquals(FlashCallReactionKind.LOVE, first.kind)
        assertEquals("p", first.peerId)
        now += 1_000
        assertNull(book.apply("p", status(reaction = FlashCallReactionKind.LOVE, seq = 10)), "a replay is not shown twice")
        assertNull(book.apply("p", status(reaction = FlashCallReactionKind.WOW, seq = 9)), "an older number is not shown")
        assertNotNull(book.apply("p", status(reaction = FlashCallReactionKind.WOW, seq = 11)))
        assertEquals(2, book.activeReactions().size)
    }

    @Test
    fun `a flooding peer is held to one reaction per gap`() {
        assertNotNull(book.apply("p", status(reaction = FlashCallReactionKind.LIKE, seq = 1)))
        now += CallStatusBook.MIN_REACTION_GAP_MS - 1
        assertNull(book.apply("p", status(reaction = FlashCallReactionKind.LIKE, seq = 2)))
        now += 1
        assertNotNull(book.apply("p", status(reaction = FlashCallReactionKind.LIKE, seq = 3)))
    }

    @Test
    fun `a dropped reaction still moves the sequence forward so it is not retried`() {
        assertNotNull(book.apply("p", status(reaction = FlashCallReactionKind.LIKE, seq = 5)))
        now += 10
        assertNull(book.apply("p", status(reaction = FlashCallReactionKind.LIKE, seq = 6)))
        now += 10_000
        assertNull(book.apply("p", status(reaction = FlashCallReactionKind.LIKE, seq = 6)), "the dropped number is spent")
    }

    @Test
    fun `reactions expire and the list is capped`() {
        repeat(12) { i ->
            now += CallStatusBook.MIN_REACTION_GAP_MS
            book.apply("p$i", status(reaction = FlashCallReactionKind.WOW, seq = 1))
        }
        assertEquals(CallStatusBook.MAX_REACTIONS, book.activeReactions().size)
        now += CallStatusBook.REACTION_LIFETIME_MS + 1
        assertTrue(book.activeReactions().isEmpty())
    }

    @Test
    fun `reaction ids are unique within a call`() {
        now += 1
        val a = book.addLocal("me", FlashCallReactionKind.LIKE)
        now += 1
        val b = book.apply("p", status(reaction = FlashCallReactionKind.LIKE, seq = 1))
        assertNotNull(b)
        assertTrue(a.id != b.id)
    }

    @Test
    fun `the local sender is held to the same gap and its numbers only grow`() {
        val first = book.nextLocalSeq()
        assertNotNull(first)
        assertNull(book.nextLocalSeq(), "a held-down button sends one")
        now += CallStatusBook.MIN_REACTION_GAP_MS
        val second = book.nextLocalSeq()
        assertNotNull(second)
        assertTrue(second > first)
        // A clock that stepped backwards still yields a larger number.
        now -= 100_000
        now += CallStatusBook.MIN_REACTION_GAP_MS * 2
        val third = book.nextLocalSeq()
        assertNotNull(third)
        assertTrue(third > second)
    }

    // ---- link quality

    @Test
    fun `no measurement is unknown, not good`() {
        assertEquals(FlashLinkQuality.UNKNOWN, FlashCallStats().linkQuality())
    }

    @Test
    fun `a quiet lan link is good, a slow one fair, a bad one poor`() {
        assertEquals(FlashLinkQuality.GOOD, FlashCallStats(rttMs = 8, packetLoss = 0.0).linkQuality())
        assertEquals(FlashLinkQuality.FAIR, FlashCallStats(rttMs = 120).linkQuality())
        assertEquals(FlashLinkQuality.FAIR, FlashCallStats(rttMs = 10, packetLoss = 0.04).linkQuality())
        assertEquals(FlashLinkQuality.POOR, FlashCallStats(rttMs = 250).linkQuality())
        assertEquals(FlashLinkQuality.POOR, FlashCallStats(rttMs = 10, packetLoss = 0.2).linkQuality())
    }

    @Test
    fun `a single number is enough to grade`() {
        assertEquals(FlashLinkQuality.GOOD, FlashCallStats(rttMs = 5).linkQuality())
        assertEquals(FlashLinkQuality.POOR, FlashCallStats(packetLoss = 0.5).linkQuality())
    }

    // ---- health monitor data saver

    @Test
    fun `data saver receives no video and says nothing about heat`() {
        val m = CallHealthMonitor()
        m.dataSaver = true
        val v = m.verdict()
        assertEquals(0, v.receiveCap)
        assertNull(v.warning)
        assertFalse(v.showingFewer)
        m.dataSaver = false
        assertNull(m.verdict().receiveCap)
    }

    @Test
    fun `a hot device still refuses new watchers under data saver`() {
        val m = CallHealthMonitor()
        m.dataSaver = true
        val v = m.update(0, com.transfer.flash.core.common.perf.FlashThermalStatus.SEVERE, null, false)
        assertFalse(v.acceptNew, "sending is still protected from a hot phone")
    }
}
