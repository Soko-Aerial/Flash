package com.transfer.flash.core.network.radio

import com.transfer.flash.core.network.kiss.Ax25Address
import com.transfer.flash.core.network.kiss.Ax25FrameCodec
import com.transfer.flash.core.network.kiss.KissFrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The complete on-air byte string for fixed inputs, so a second implementation (Rust, Linux client) can check itself against
 * `docs/network/RADIO-WIRE-FORMAT.md` section 9. If this test changes, the wire format changed: bump the version nibble.
 */
class RadioGoldenFrameTest {
    private val crypto = JdkRadioCrypto()
    private val pairKey = ByteArray(32) { it.toByte() } // 00 01 ... 1F
    private val now = 1_800_000_000_000L

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun makeSession(local: String, peer: String): RadioSession {
        val s = RadioSession(local, crypto, InMemoryRadioStore(), InMemoryRadioStore())
        s.addPeer(peer, pairKey)
        return s
    }

    @Test
    fun goldenFrameForFixedInputs() {
        val alice = makeSession("alice", "bob")
        val enc = alice.encode("bob", RadioKind.TEXT, "hello".encodeToByteArray(), now) as RadioEncode.Frames
        val info = enc.frames.single()
        val label = alice.stationLabel("bob", now)
        val ax25 = Ax25FrameCodec.encodeUi(Ax25Address("FLASH"), label, info)
        val kiss = KissFrameCodec.encodeData(ax25)
        assertEquals(32_774_400L, enc.firstCounter, "counter = seconds since 2026-01-01 at the fixed time")
        assertEquals("6M2CAB-13", label.toString())
        assertEquals("F1110003E9EB857001F41900D0B7F21FF8707E318AC64FFFA3E190158160077F50", hex(info))
        assertEquals("8C9882A69040E06C9A648682847B03F0F1110003E9EB857001F41900D0B7F21FF8707E318AC64FFFA3E190158160077F50", hex(ax25))
        assertEquals("C0008C9882A69040E06C9A648682847B03F0F1110003E9EB857001F41900D0B7F21FF8707E318AC64FFFA3E190158160077F50C0", hex(kiss))
        // The receiving side must accept exactly these bytes.
        val bob = makeSession("bob", "alice")
        val got = bob.ingest(info, now + 5_000)
        assertEquals("hello", (got as RadioIngest.Delivered).body.decodeToString())
        assertEquals(12 + 5 + 16, info.size)
    }
}
