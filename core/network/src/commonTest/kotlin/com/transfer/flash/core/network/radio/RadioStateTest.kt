package com.transfer.flash.core.network.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RadioStateTest {

    // --- ReplayWindow -------------------------------------------------------------------------------------------

    @Test
    fun replayWindowAcceptsEachCounterOnce() {
        val w = ReplayWindow()
        assertEquals(ReplayVerdict.FRESH, w.check(0))
        w.commit(0)
        assertEquals(ReplayVerdict.DUPLICATE, w.check(0))
        assertEquals(ReplayVerdict.FRESH, w.check(1))
    }

    @Test
    fun replayWindowAcceptsOutOfOrderInsideTheWindow() {
        val w = ReplayWindow()
        w.commit(10)
        assertEquals(ReplayVerdict.FRESH, w.check(7))
        w.commit(7)
        assertEquals(ReplayVerdict.DUPLICATE, w.check(7))
        assertEquals(ReplayVerdict.DUPLICATE, w.check(10))
        assertEquals(ReplayVerdict.FRESH, w.check(8))
    }

    @Test
    fun replayWindowRejectsTooOld() {
        val w = ReplayWindow()
        w.commit(100)
        assertEquals(ReplayVerdict.TOO_OLD, w.check(100 - 64))
        assertEquals(ReplayVerdict.FRESH, w.check(100 - 63))
    }

    @Test
    fun replayWindowBigJumpClearsHistory() {
        val w = ReplayWindow()
        w.commit(5)
        w.commit(5000)
        assertEquals(ReplayVerdict.TOO_OLD, w.check(5))
        assertEquals(ReplayVerdict.DUPLICATE, w.check(5000))
    }

    @Test
    fun replayWindowStateRoundTrips() {
        val w = ReplayWindow()
        listOf(3L, 4L, 9L, 6L).forEach { w.commit(it) }
        val copy = ReplayWindow(w.highest, w.bitmap)
        for (c in 0L..12L) assertEquals(w.check(c), copy.check(c), "counter $c")
    }

    // --- RadioSendCounter ---------------------------------------------------------------------------------------

    @Test
    fun sendCounterIsStrictlyIncreasing() {
        val c = RadioSendCounter(InMemoryRadioStore(), reserve = 4, floorOf = { 0 })
        val seq = (1..20).map { c.next("p", 0) }
        assertEquals((0L..19L).toList(), seq)
    }

    @Test
    fun sendCounterNeverReusesAfterACrash() {
        val store = InMemoryRadioStore()
        val first = RadioSendCounter(store, reserve = 10, floorOf = { 0 })
        val used = (1..7).map { first.next("p", 0) }
        // "crash": a new instance sees only what was persisted
        val second = RadioSendCounter(store, reserve = 10, floorOf = { 0 })
        val after = second.next("p", 0)
        assertTrue(after > used.max(), "after=$after used=${used.max()}")
    }

    @Test
    fun sendCounterFloorCoversALostStore() {
        val old = RadioSendCounter(InMemoryRadioStore(), reserve = 4, floorOf = ::secondsSince2026)
        val nowMs = 1_790_000_000_000L // 2026-09
        val a = old.next("p", nowMs)
        // store lost: fresh store, same clock a minute later: must be above everything issued a minute earlier
        val fresh = RadioSendCounter(InMemoryRadioStore(), reserve = 4, floorOf = ::secondsSince2026)
        assertTrue(fresh.next("p", nowMs + 60_000) > a + 50)
    }

    @Test
    fun sendCounterStopsAtUint32() {
        val store = InMemoryRadioStore()
        store.storeCounter("p", RadioSendCounter.MAX)
        val c = RadioSendCounter(store, reserve = 1, floorOf = { 0 })
        assertEquals(RadioSendCounter.MAX, c.next("p", 0))
        assertFailsWith<IllegalStateException> { c.next("p", 0) }
    }

    @Test
    fun secondsSince2026Anchors() {
        assertEquals(0, secondsSince2026(1_767_225_600_000L))
        assertEquals(86_400, secondsSince2026(1_767_225_600_000L + 86_400_000L))
        assertEquals(0, secondsSince2026(0))
    }

    // --- DedupStore ---------------------------------------------------------------------------------------------

    @Test
    fun dedupReportsRepeatsWithinTtl() {
        val d = DedupStore(capacity = 10, ttlMs = 1000)
        assertFalse(d.seenBefore("a/1", 0))
        assertTrue(d.seenBefore("a/1", 500))
        assertFalse(d.seenBefore("a/2", 500))
    }

    @Test
    fun dedupForgetsAfterTtl() {
        val d = DedupStore(capacity = 10, ttlMs = 1000)
        d.seenBefore("a/1", 0)
        assertFalse(d.seenBefore("a/1", 5000))
    }

    @Test
    fun dedupIsBounded() {
        val d = DedupStore(capacity = 3, ttlMs = 1_000_000)
        for (i in 0 until 10) d.seenBefore("k$i", i.toLong())
        assertEquals(3, d.size)
        assertFalse(d.seenBefore("k0", 20)) // evicted
        assertTrue(d.seenBefore("k9", 21))
    }

    // --- SegmentReassembler -------------------------------------------------------------------------------------

    private fun chunk(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun reassemblesOutOfOrder() {
        val r = SegmentReassembler()
        assertIs<SegmentResult.Incomplete>(r.add("p", 7, 2, 3, 12, chunk(5, 6), 0))
        assertIs<SegmentResult.Incomplete>(r.add("p", 7, 0, 3, 10, chunk(1, 2), 0))
        val done = r.add("p", 7, 1, 3, 11, chunk(3, 4), 0)
        assertIs<SegmentResult.Complete>(done)
        assertContentEquals(chunk(1, 2, 3, 4, 5, 6), done.body)
        assertEquals(10, done.firstCounter)
        assertEquals(0, r.pendingCount)
    }

    @Test
    fun duplicateSegmentIsFlagged() {
        val r = SegmentReassembler()
        r.add("p", 1, 0, 2, 0, chunk(1), 0)
        val again = r.add("p", 1, 0, 2, 5, chunk(9), 0)
        assertIs<SegmentResult.Incomplete>(again)
        assertTrue(again.duplicate)
        val done = r.add("p", 1, 1, 2, 1, chunk(2), 0) as SegmentResult.Complete
        assertContentEquals(chunk(1, 2), done.body) // the first copy won
    }

    @Test
    fun contradictoryTotalIsRejected() {
        val r = SegmentReassembler()
        r.add("p", 1, 0, 3, 0, chunk(1), 0)
        assertIs<SegmentResult.Rejected>(r.add("p", 1, 1, 4, 1, chunk(2), 0))
    }

    @Test
    fun badIndicesAreRejected() {
        val r = SegmentReassembler(maxSegments = 4)
        assertIs<SegmentResult.Rejected>(r.add("p", 1, 3, 3, 0, chunk(1), 0))
        assertIs<SegmentResult.Rejected>(r.add("p", 1, 0, 1, 0, chunk(1), 0)) // 1 segment is never segmented
        assertIs<SegmentResult.Rejected>(r.add("p", 1, 0, 5, 0, chunk(1), 0)) // above the limit
    }

    @Test
    fun differentPeersDoNotMix() {
        val r = SegmentReassembler()
        r.add("a", 1, 0, 2, 0, chunk(1), 0)
        assertIs<SegmentResult.Incomplete>(r.add("b", 1, 1, 2, 0, chunk(2), 0))
    }

    @Test
    fun incompleteMessagesExpireAndAreBounded() {
        val r = SegmentReassembler(maxPending = 2, timeoutMs = 1000)
        r.add("p", 1, 0, 2, 0, chunk(1), 0)
        r.add("p", 2, 0, 2, 0, chunk(1), 0)
        r.add("p", 3, 0, 2, 0, chunk(1), 0)
        assertEquals(2, r.pendingCount) // oldest evicted
        r.add("p", 4, 0, 2, 0, chunk(1), 5000)
        assertEquals(1, r.pendingCount) // the others expired
    }

    // --- ECDSA DER <-> raw --------------------------------------------------------------------------------------

    @Test
    fun derToRawHandlesPaddingAndShortIntegers() {
        // r = 0x80 00..00 (needs a leading 00 in DER), s = 1 (one byte)
        val r = ByteArray(32).also { it[0] = 0x80.toByte() }
        val s = ByteArray(32).also { it[31] = 1 }
        val raw = r + s
        val der = EcdsaRawSignature.rawToDer(raw)!!
        assertEquals(0x30, der[0].toInt())
        assertContentEquals(raw, EcdsaRawSignature.derToRaw(der))
        // known DER with r of 32 bytes (no pad) and s of 31 bytes (leading zero byte stripped by DER)
        val s31 = ByteArray(32).also { it[1] = 0x7F }
        val raw2 = ByteArray(32) { 0x11 } + s31
        assertContentEquals(raw2, EcdsaRawSignature.derToRaw(EcdsaRawSignature.rawToDer(raw2)!!))
    }

    @Test
    fun derToRawRejectsGarbage() {
        assertEquals(null, EcdsaRawSignature.derToRaw(ByteArray(0)))
        assertEquals(null, EcdsaRawSignature.derToRaw(byteArrayOf(0x30, 0x02, 0x02, 0x00)))
        assertEquals(null, EcdsaRawSignature.derToRaw(ByteArray(72) { 0x30 }))
        assertEquals(null, EcdsaRawSignature.rawToDer(ByteArray(63)))
    }

    // --- Header codec -------------------------------------------------------------------------------------------

    @Test
    fun headerGoldenVector() {
        val h = RadioHeader(RadioKind.TEXT, RadioWire.FLAG_SEGMENTED, 3, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), 0x01020304L)
        assertContentEquals(
            byteArrayOf(0xF1.toByte(), 0x11, 0x01, 0x03, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 1, 2, 3, 4),
            h.encode(),
        )
        val parsed = RadioHeader.parse(h.encode()) as RadioHeader.Parsed.Ok
        assertEquals(RadioKind.TEXT, parsed.header.kind)
        assertEquals(0x01020304L, parsed.header.counter)
        assertTrue(parsed.header.segmented)
        assertFalse(parsed.header.signed)
    }

    @Test
    fun headerRejectsBadInput() {
        fun bad(b: ByteArray) = (RadioHeader.parse(b) as RadioHeader.Parsed.Bad).problem
        assertEquals(RadioHeader.Problem.TOO_SHORT, bad(ByteArray(5)))
        assertEquals(RadioHeader.Problem.BAD_MAGIC, bad(ByteArray(12)))
        val base = RadioHeader(RadioKind.PING, 0, 3, ByteArray(4), 0).encode()
        assertEquals(RadioHeader.Problem.BAD_VERSION, bad(base.copyOf().also { it[1] = 0x23 }))
        assertEquals(RadioHeader.Problem.UNKNOWN_KIND, bad(base.copyOf().also { it[1] = 0x1F }))
        assertEquals(RadioHeader.Problem.RESERVED_FLAGS, bad(base.copyOf().also { it[2] = 0x08 }))
        assertEquals(RadioHeader.Problem.UNSUPPORTED_AUTH, bad(base.copyOf().also { it[2] = 0x04 }))
    }

    @Test
    fun ttlIsNotPartOfTheAuthenticatedBytes() {
        val h1 = RadioHeader(RadioKind.TEXT, 0, 3, ByteArray(4), 9)
        val h2 = RadioHeader(RadioKind.TEXT, 0, 1, ByteArray(4), 9)
        assertContentEquals(h1.authenticatedBytes(), h2.authenticatedBytes())
        val h3 = RadioHeader(RadioKind.TEXT, 0, 3, ByteArray(4), 10)
        assertFalse(h1.authenticatedBytes().contentEquals(h3.authenticatedBytes()))
    }

    @Test
    fun budgetArithmetic() {
        assertEquals(192, RadioWire.maxBodyUnsegmented(220))
        assertEquals(188, RadioWire.maxBodyPerSegment(220))
    }
}
