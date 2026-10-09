package com.transfer.flash.core.network.radio

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** BT-01a: the Profile M radio frame, with real AES-GCM / HMAC from the JDK. No radio involved. */
class RadioSessionTest {
    private val crypto = JdkRadioCrypto()
    private val pairKey = ByteArray(32) { (it * 5 + 1).toByte() }
    private val t0 = 1_790_000_000_000L // 2026-09

    private class Station(val id: String, val replay: InMemoryRadioStore, val counters: InMemoryRadioStore, val session: RadioSession)

    private fun station(
        id: String,
        peer: String,
        key: ByteArray = pairKey,
        replay: InMemoryRadioStore = InMemoryRadioStore(),
        counters: InMemoryRadioStore = InMemoryRadioStore(),
        config: RadioSessionConfig = RadioSessionConfig(),
    ): Station {
        val s = RadioSession(id, crypto, replay, counters, config)
        s.addPeer(peer, key)
        return Station(id, replay, counters, s)
    }

    private fun frames(e: RadioEncode): List<ByteArray> = (e as RadioEncode.Frames).frames

    private fun bytes(n: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(n)

    @Test
    fun textRoundTripBothDirections() {
        val a = station("dev-a", "dev-b")
        val b = station("dev-b", "dev-a")
        val msg = "Base camp set up. All safe.".encodeToByteArray()
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, msg, t0))
        assertEquals(1, f.size)
        val got = b.session.ingest(f[0], t0 + 1000)
        assertIs<RadioIngest.Delivered>(got)
        assertEquals("dev-a", got.peerId)
        assertEquals(RadioKind.TEXT, got.kind)
        assertContentEquals(msg, got.body)
        // and back
        val back = frames(b.session.encode("dev-a", RadioKind.TEXT, "copy".encodeToByteArray(), t0 + 2000))
        val got2 = a.session.ingest(back[0], t0 + 3000) as RadioIngest.Delivered
        assertEquals("copy", got2.body.decodeToString())
    }

    @Test
    fun overheadIs28BytesAndAFullBodyFitsTheBudget() {
        val a = station("dev-a", "dev-b")
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, bytes(192), t0))
        assertEquals(1, f.size)
        assertEquals(220, f[0].size)
        val g = frames(a.session.encode("dev-b", RadioKind.TEXT, bytes(0), t0))
        assertEquals(28, g[0].size)
    }

    @Test
    fun bodyAbove192BytesIsSegmentedAndEveryFrameFitsTheBudget() {
        val a = station("dev-a", "dev-b")
        val b = station("dev-b", "dev-a")
        val msg = bytes(1000, seed = 9)
        val enc = a.session.encode("dev-b", RadioKind.TEXT, msg, t0) as RadioEncode.Frames
        assertEquals(6, enc.frames.size) // ceil(1000 / 188)
        assertTrue(enc.frames.all { it.size <= 220 })
        var delivered: RadioIngest.Delivered? = null
        for ((i, fr) in enc.frames.withIndex()) {
            val r = b.session.ingest(fr, t0 + i)
            if (i < enc.frames.size - 1) assertIs<RadioIngest.Partial>(r) else delivered = r as RadioIngest.Delivered
        }
        assertContentEquals(msg, delivered!!.body)
        assertEquals(enc.firstCounter, delivered.firstCounter)
        assertEquals(6, delivered.segments)
    }

    @Test
    fun segmentsMayArriveOutOfOrderAndDuplicated() {
        val a = station("dev-a", "dev-b")
        val b = station("dev-b", "dev-a")
        val msg = bytes(500, seed = 3)
        val fr = frames(a.session.encode("dev-b", RadioKind.TEXT, msg, t0))
        assertEquals(3, fr.size)
        assertIs<RadioIngest.Partial>(b.session.ingest(fr[2], t0))
        assertIs<RadioIngest.Dropped>(b.session.ingest(fr[2], t0)) // exact duplicate frame: replay window
        assertIs<RadioIngest.Partial>(b.session.ingest(fr[0], t0))
        val done = b.session.ingest(fr[1], t0)
        assertContentEquals(msg, (done as RadioIngest.Delivered).body)
    }

    @Test
    fun aLostSegmentNeverYieldsAMessage() {
        val a = station("dev-a", "dev-b")
        val b = station("dev-b", "dev-a")
        val fr = frames(a.session.encode("dev-b", RadioKind.TEXT, bytes(500), t0))
        assertIs<RadioIngest.Partial>(b.session.ingest(fr[0], t0))
        assertIs<RadioIngest.Partial>(b.session.ingest(fr[2], t0))
        // fr[1] lost; nothing is delivered, and after the timeout the partial state is gone
        val late = b.session.ingest(fr[1], t0 + 11 * 60 * 1000L)
        assertIs<RadioIngest.Partial>(late) // it starts a new reassembly of 1 of 3; the old parts expired
    }

    @Test
    fun tooLargeIsRefusedNotTruncated() {
        val a = station("dev-a", "dev-b")
        val r = a.session.encode("dev-b", RadioKind.TEXT, bytes(16 * 188 + 1), t0)
        assertIs<RadioEncode.Refused>(r)
        assertIs<RadioEncode.Frames>(a.session.encode("dev-b", RadioKind.TEXT, bytes(16 * 188), t0))
        assertIs<RadioEncode.Refused>(a.session.encode("nobody", RadioKind.TEXT, bytes(1), t0))
    }

    @Test
    fun replayedFrameIsRejectedAndSurvivesARestart() {
        val a = station("dev-a", "dev-b")
        val replay = InMemoryRadioStore()
        val b = station("dev-b", "dev-a", replay = replay)
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, "x".encodeToByteArray(), t0))[0]
        assertIs<RadioIngest.Delivered>(b.session.ingest(f, t0))
        val second = b.session.ingest(f, t0 + 5)
        assertEquals(RadioDrop.REPLAY, (second as RadioIngest.Dropped).reason)
        // B restarts with only the persisted replay state
        val b2 = station("dev-b", "dev-a", replay = replay)
        val third = b2.session.ingest(f, t0 + 10_000)
        assertEquals(RadioDrop.REPLAY, (third as RadioIngest.Dropped).reason)
    }

    @Test
    fun anOldCapturedFrameIsRejectedOnceTheWindowMovedOn() {
        val a = station("dev-a", "dev-b")
        val b = station("dev-b", "dev-a")
        val old = frames(a.session.encode("dev-b", RadioKind.TEXT, "old".encodeToByteArray(), t0))[0]
        repeat(70) { i ->
            b.session.ingest(frames(a.session.encode("dev-b", RadioKind.TEXT, "m$i".encodeToByteArray(), t0))[0], t0)
        }
        assertEquals(RadioDrop.TOO_OLD, (b.session.ingest(old, t0) as RadioIngest.Dropped).reason)
    }

    @Test
    fun everyBitFlipIsRejected() {
        val a = station("dev-a", "dev-b")
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, "authentic".encodeToByteArray(), t0))[0]
        for (byteIndex in f.indices) {
            if (byteIndex == 3) continue // TTL is deliberately unauthenticated
            val b = station("dev-b", "dev-a")
            val bad = f.copyOf().also { it[byteIndex] = (it[byteIndex].toInt() xor 0x01).toByte() }
            val r = b.session.ingest(bad, t0)
            assertIs<RadioIngest.Dropped>(r, "flip at byte $byteIndex was accepted")
        }
        // and the untouched frame is fine
        assertIs<RadioIngest.Delivered>(station("dev-b", "dev-a").session.ingest(f, t0))
    }

    @Test
    fun ttlIsEditableByRelaysButClamped() {
        val a = station("dev-a", "dev-b")
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, "hop".encodeToByteArray(), t0))[0]
        val relayed = RadioHeader.withTtl(f, 2)!!
        val b = station("dev-b", "dev-a")
        assertEquals(2, (b.session.ingest(relayed, t0) as RadioIngest.Delivered).ttl)
        val inflated = RadioHeader.withTtl(f, 250)!!
        val b2 = station("dev-b", "dev-a")
        assertEquals(3, (b2.session.ingest(inflated, t0) as RadioIngest.Delivered).ttl)
    }

    @Test
    fun aStrangerWithAnotherKeyIsUnknown() {
        val a = station("dev-a", "dev-b", key = ByteArray(32) { 0x42 })
        val b = station("dev-b", "dev-a")
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, "hi".encodeToByteArray(), t0))[0]
        assertEquals(RadioDrop.UNKNOWN_SENDER, (b.session.ingest(f, t0) as RadioIngest.Dropped).reason)
    }

    @Test
    fun aReflectedFrameIsNotAcceptedByItsAuthor() {
        val a = station("dev-a", "dev-b")
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, "mine".encodeToByteArray(), t0))[0]
        assertIs<RadioIngest.Dropped>(a.session.ingest(f, t0))
    }

    @Test
    fun noPlaintextOnTheAir() {
        val a = station("dev-a", "dev-b")
        val secret = "position 5.6037N 0.1870W".encodeToByteArray()
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, secret, t0))[0]
        val hex = f.joinToString("") { "%02x".format(it) }
        assertFalse(hex.contains(secret.joinToString("") { "%02x".format(it) }.substring(0, 16)))
        assertFalse(String(f, Charsets.ISO_8859_1).contains("5.6037"))
        assertFalse(String(f, Charsets.ISO_8859_1).contains("dev-a"))
    }

    @Test
    fun senderTagRotatesPerEpochAndAcceptsSkewButNotMore() {
        val cfg = RadioSessionConfig(epochMs = 60_000, epochSkew = 1)
        val a = station("dev-a", "dev-b", config = cfg)
        val f0 = frames(a.session.encode("dev-b", RadioKind.TEXT, "1".encodeToByteArray(), t0))[0]
        val f1 = frames(a.session.encode("dev-b", RadioKind.TEXT, "2".encodeToByteArray(), t0 + 60_000))[0]
        assertFalse(f0.copyOfRange(4, 8).contentEquals(f1.copyOfRange(4, 8)), "tag must change with the epoch")
        // receiver clock one epoch off: accepted; three epochs off: not
        assertIs<RadioIngest.Delivered>(station("dev-b", "dev-a", config = cfg).session.ingest(f0, t0 + 60_000))
        assertEquals(
            RadioDrop.UNKNOWN_SENDER,
            (station("dev-b", "dev-a", config = cfg).session.ingest(f0, t0 + 3 * 60_000) as RadioIngest.Dropped).reason,
        )
    }

    @Test
    fun stationLabelIsAValidRotatingNeutralCallsign() {
        val cfg = RadioSessionConfig(epochMs = 60_000)
        val a = station("dev-a", "dev-b", config = cfg)
        val l0 = a.session.stationLabel("dev-b", t0)
        val l1 = a.session.stationLabel("dev-b", t0 + 60_000)
        assertEquals(l0, a.session.stationLabel("dev-b", t0 + 1000))
        assertNotEquals(l0, l1)
        assertEquals(6, l0.callsign.length)
        assertFalse(l0.callsign.contains("DEV"))
    }

    @Test
    fun counterAndNonceNeverRepeat() {
        val a = station("dev-a", "dev-b")
        val seen = HashSet<Long>()
        repeat(500) {
            val f = frames(a.session.encode("dev-b", RadioKind.TEXT, "x".encodeToByteArray(), t0))[0]
            val h = (RadioHeader.parse(f) as RadioHeader.Parsed.Ok).header
            assertTrue(seen.add(h.counter))
        }
    }

    @Test
    fun counterSurvivesRestartWithoutReuse() {
        val counters = InMemoryRadioStore()
        val a1 = station("dev-a", "dev-b", counters = counters)
        val used = (1..10).map {
            ((RadioHeader.parse(frames(a1.session.encode("dev-b", RadioKind.TEXT, ByteArray(1), t0))[0]) as RadioHeader.Parsed.Ok).header.counter)
        }
        val a2 = station("dev-a", "dev-b", counters = counters)
        val next = (RadioHeader.parse(frames(a2.session.encode("dev-b", RadioKind.TEXT, ByteArray(1), t0))[0]) as RadioHeader.Parsed.Ok).header.counter
        assertTrue(next > used.max())
    }

    @Test
    fun pingIsAnsweredWithAckNamingTheCounter() {
        val a = station("dev-a", "dev-b")
        val b = station("dev-b", "dev-a")
        val ping = a.session.encode("dev-b", RadioKind.PING, "p".encodeToByteArray(), t0) as RadioEncode.Frames
        val got = b.session.ingest(ping.frames[0], t0) as RadioIngest.Delivered
        assertEquals(RadioKind.PING, got.kind)
        val ack = frames(b.session.encodeAck("dev-a", got.firstCounter, t0 + 100))[0]
        val back = a.session.ingest(ack, t0 + 200) as RadioIngest.Delivered
        assertEquals(RadioKind.ACK, back.kind)
        val acked = back.body.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
        assertEquals(ping.firstCounter, acked)
    }

    @Test
    fun foreignTrafficIsNotFlash() {
        val b = station("dev-b", "dev-a")
        val aprs = "!4903.50N/07201.75W-Test".encodeToByteArray()
        assertEquals(RadioDrop.NOT_FLASH, (b.session.ingest(aprs, t0) as RadioIngest.Dropped).reason)
        assertEquals(RadioDrop.MALFORMED, (b.session.ingest(ByteArray(0), t0) as RadioIngest.Dropped).reason)
    }

    @Test
    fun hostileBytesNeverThrow() {
        val b = station("dev-b", "dev-a")
        val rnd = Random(99)
        repeat(2000) {
            val n = rnd.nextInt(0, 260)
            val junk = rnd.nextBytes(n)
            if (n > 0 && rnd.nextBoolean()) junk[0] = 0xF1.toByte()
            if (n > 1 && rnd.nextBoolean()) junk[1] = (0x10 or rnd.nextInt(1, 6)).toByte()
            if (n > 2 && rnd.nextBoolean()) junk[2] = (rnd.nextInt(0, 2)).toByte()
            b.session.ingest(junk, t0)
        }
    }

    @Test
    fun revokedPeerIsNoLongerAccepted() {
        val a = station("dev-a", "dev-b")
        val b = station("dev-b", "dev-a")
        val f = frames(a.session.encode("dev-b", RadioKind.TEXT, "x".encodeToByteArray(), t0))[0]
        b.session.removePeer("dev-a")
        assertEquals(RadioDrop.UNKNOWN_SENDER, (b.session.ingest(f, t0) as RadioIngest.Dropped).reason)
    }

    @Test
    fun peersHaveIndependentKeysAndReplayState() {
        val a = RadioSession("hub", crypto, InMemoryRadioStore(), InMemoryRadioStore())
        a.addPeer("p1", ByteArray(32) { 1 })
        a.addPeer("p2", ByteArray(32) { 2 })
        val p1 = station("p1", "hub", key = ByteArray(32) { 1 })
        val p2 = station("p2", "hub", key = ByteArray(32) { 2 })
        val f1 = frames(a.encode("p1", RadioKind.TEXT, "to1".encodeToByteArray(), t0))[0]
        assertIs<RadioIngest.Delivered>(p1.session.ingest(f1, t0))
        assertIs<RadioIngest.Dropped>(p2.session.ingest(f1, t0))
    }

    // --- signed broadcast (Profile A) ----------------------------------------------------------------------------

    @Test
    fun signedBroadcastRoundTripUsesRaw64ByteSignature() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
        val signer = JdkRadioSignatureScheme(kp.private)
        val tx = RadioSession("dev-a", crypto, InMemoryRadioStore(), InMemoryRadioStore(), signer = signer, localPublicKey = kp.public.encoded)
        val rx = RadioSession("dev-b", crypto, InMemoryRadioStore(), InMemoryRadioStore(), signer = JdkRadioSignatureScheme())
        rx.addBroadcastSender("dev-a", kp.public.encoded)
        val f = frames(tx.encodeSigned(RadioKind.TEXT, "all stations".encodeToByteArray(), t0))[0]
        assertEquals(12 + 12 + 64, f.size)
        val got = rx.ingest(f, t0) as RadioIngest.Delivered
        assertTrue(got.signed)
        assertEquals("all stations", got.body.decodeToString())
        // replay and tamper
        assertEquals(RadioDrop.REPLAY, (rx.ingest(f, t0) as RadioIngest.Dropped).reason)
        val bad = f.copyOf().also { it[14] = (it[14].toInt() xor 1).toByte() }
        assertIs<RadioIngest.Dropped>(rx.ingest(bad, t0))
    }

    @Test
    fun signedFrameFromAnUnregisteredKeyIsUnknown() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val tx = RadioSession("dev-a", crypto, InMemoryRadioStore(), InMemoryRadioStore(), signer = JdkRadioSignatureScheme(kp.private), localPublicKey = kp.public.encoded)
        val rx = RadioSession("dev-b", crypto, InMemoryRadioStore(), InMemoryRadioStore(), signer = JdkRadioSignatureScheme())
        rx.addBroadcastSender("dev-x", other.public.encoded)
        val f = frames(tx.encodeSigned(RadioKind.TEXT, "x".encodeToByteArray(), t0))[0]
        assertEquals(RadioDrop.UNKNOWN_SENDER, (rx.ingest(f, t0) as RadioIngest.Dropped).reason)
    }

    @Test
    fun signedFramesAreIgnoredUnlessEnabled() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val tx = RadioSession("dev-a", crypto, InMemoryRadioStore(), InMemoryRadioStore(), signer = JdkRadioSignatureScheme(kp.private), localPublicKey = kp.public.encoded)
        val rx = station("dev-b", "dev-a") // no broadcast senders registered: Profile M refuses signed clear text
        val f = frames(tx.encodeSigned(RadioKind.TEXT, "x".encodeToByteArray(), t0))[0]
        assertEquals(RadioDrop.SIGNED_NOT_ENABLED, (rx.session.ingest(f, t0) as RadioIngest.Dropped).reason)
    }

    @Test
    fun aTtlOutsideTheHeaderRangeIsARefusalNotAnException() { // R7
        val a = station("dev-a", "dev-b")
        for (bad in listOf(-1, 256, 1_000)) {
            val e = a.session.encode("dev-b", RadioKind.TEXT, bytes(10), t0, ttl = bad)
            assertIs<RadioEncode.Refused>(e)
            assertEquals("bad_ttl", e.reason)
        }
        assertIs<RadioEncode.Frames>(a.session.encode("dev-b", RadioKind.TEXT, bytes(10), t0, ttl = 0))
        assertIs<RadioEncode.Frames>(a.session.encode("dev-b", RadioKind.TEXT, bytes(10), t0, ttl = 255))
    }
}
