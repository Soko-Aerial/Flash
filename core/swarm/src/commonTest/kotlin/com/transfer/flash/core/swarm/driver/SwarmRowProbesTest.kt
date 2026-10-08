package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.transfer.model.FlashTransferDirection.Receiving
import com.transfer.flash.core.transfer.model.FlashTransferDirection.Sending
import com.transfer.flash.core.transfer.model.FlashTransferRecipient
import com.transfer.flash.core.transfer.model.FlashTransferState
import kotlin.test.Test
import kotlin.test.assertEquals

class SwarmRowProbesTest {
    private val lines = ArrayList<Pair<String, Map<String, Any?>>>()
    private val probes = SwarmRowProbes { name, fields -> lines.add(name to fields.toMap()) }
    private val names get() = lines.map { it.first }

    @Test
    fun `a receiver logs the offer, the accept delay, the first byte and the finish once each`() {
        probes.onRow("transfer-123456789", Receiving, FlashTransferState.Offered, 0, 1000, emptyList(), 1_000L)
        probes.onRow("transfer-123456789", Receiving, FlashTransferState.Offered, 0, 1000, emptyList(), 1_500L)
        probes.onRow("transfer-123456789", Receiving, FlashTransferState.Queued, 0, 1000, emptyList(), 6_000L)
        probes.onRow("transfer-123456789", Receiving, FlashTransferState.Transferring, 100, 1000, emptyList(), 6_400L)
        probes.onRow("transfer-123456789", Receiving, FlashTransferState.Transferring, 600, 1000, emptyList(), 7_000L)
        probes.onRow("transfer-123456789", Receiving, FlashTransferState.Completed, 1000, 1000, emptyList(), 8_000L)
        probes.onRow("transfer-123456789", Receiving, FlashTransferState.Completed, 1000, 1000, emptyList(), 8_100L)
        assertEquals(listOf("swarm.offer.shown", "swarm.accepted", "swarm.first_byte", "swarm.recv.done"), names)
        assertEquals(5_000L, lines[1].second["afterOfferMs"])
        assertEquals(400L, lines[2].second["sinceAcceptMs"])
        assertEquals(2_000L, lines[3].second["sinceAcceptMs"])
    }

    @Test
    fun `a sender logs when each member first received and when each has the whole file`() {
        fun member(id: String, held: Long, all: Boolean) = FlashTransferRecipient(id, held, 1000, all, true)
        val a0 = member("aaaaaaaaaaaa", 0, false)
        val a1 = member("aaaaaaaaaaaa", 300, false)
        val aAll = member("aaaaaaaaaaaa", 1000, true)
        val b0 = member("bbbbbbbbbbbb", 0, false)
        probes.onRow("t1", Sending, FlashTransferState.Transferring, 1000, 1000, listOf(a0), 1_000L)
        probes.onRow("t1", Sending, FlashTransferState.Transferring, 1000, 1000, listOf(a1), 2_000L)
        probes.onRow("t1", Sending, FlashTransferState.Transferring, 1000, 1000, listOf(aAll, b0), 3_000L)
        probes.onRow("t1", Sending, FlashTransferState.Transferring, 1000, 1000, listOf(aAll, b0), 4_000L)
        // 'a' first received at 2 s (1 s after the first row), then finished at 3 s: member.first once, member.done once.
        assertEquals(listOf("swarm.member.first", "swarm.member.done"), names)
        assertEquals(1_000L, lines[0].second["sinceSendMs"])
        assertEquals(2_000L, lines[1].second["sinceSendMs"])
        assertEquals(1, lines[1].second["done"])
        assertEquals(2, lines[1].second["seen"])
    }

    @Test
    fun `a row restored already finished or already held says nothing`() {
        probes.onRow("t-old", Receiving, FlashTransferState.Completed, 1000, 1000, emptyList(), 1_000L)
        probes.onRow("t-old-send", Sending, FlashTransferState.Transferring, 1000, 1000,
            listOf(FlashTransferRecipient("aaaaaaaaaaaa", 1000, 1000, true, true)), 1_000L)
        assertEquals(emptyList(), names)
        // A member that was not holding at restore is still reported when it finishes.
        probes.onRow("t-old-send", Sending, FlashTransferState.Transferring, 1000, 1000,
            listOf(FlashTransferRecipient("aaaaaaaaaaaa", 1000, 1000, true, true),
                FlashTransferRecipient("bbbbbbbbbbbb", 1000, 1000, true, true)), 2_000L)
        assertEquals(listOf("swarm.member.first", "swarm.member.done"), names)
    }
}
