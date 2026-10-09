package com.transfer.flash.core.network.kiss

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden vectors derived by hand from AX.25 v2.2 section 3 (address field: ASCII shifted left one bit, SSID byte
 * `C RR SSID E`, reserved bits 1; control 0x03 = UI; PID 0xF0 = no layer 3). BT-02.
 */
class Ax25FrameCodecTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun uiFrameGoldenVector() {
        val frame = Ax25FrameCodec.encodeUi(
            destination = Ax25Address("APRS"),
            source = Ax25Address("N0CALL", 1),
            info = "TEST".encodeToByteArray(),
        )
        // APRS  : 82 A0 A4 A6 40 40, SSID byte E0 (C=1, RR=11, SSID 0, E=0)
        // N0CALL-1: 9C 60 86 82 98 98, SSID byte 63 (C=0, RR=11, SSID 1, E=1)
        assertContentEquals(
            bytes(
                0x82, 0xA0, 0xA4, 0xA6, 0x40, 0x40, 0xE0,
                0x9C, 0x60, 0x86, 0x82, 0x98, 0x98, 0x63,
                0x03, 0xF0, 0x54, 0x45, 0x53, 0x54,
            ),
            frame,
        )
    }

    @Test
    fun uiFrameWithDigipeaterGoldenVector() {
        val frame = Ax25FrameCodec.encodeUi(
            destination = Ax25Address("APRS"),
            source = Ax25Address("N0CALL", 1),
            digipeaters = listOf(Ax25Address("WIDE1", 1)),
            info = bytes(0x21),
        )
        assertContentEquals(
            bytes(
                0x82, 0xA0, 0xA4, 0xA6, 0x40, 0x40, 0xE0,
                0x9C, 0x60, 0x86, 0x82, 0x98, 0x98, 0x62, // source no longer last: E bit clear
                0xAE, 0x92, 0x88, 0x8A, 0x62, 0x40, 0x63, // WIDE1-1, H=0, E=1
                0x03, 0xF0, 0x21,
            ),
            frame,
        )
    }

    @Test
    fun repeatedDigipeaterSetsHBit() {
        val frame = Ax25FrameCodec.encodeUi(
            Ax25Address("APRS"), Ax25Address("N0CALL"), bytes(), digipeaters = listOf(Ax25Address("WIDE1", 1, flagBit = true)),
        )
        assertEquals(0xE3, frame[20].toInt() and 0xFF) // H=1, RR=11, SSID 1, E=1
    }

    @Test
    fun responseRoleSwapsCBits() {
        val f = Ax25FrameCodec.encodeUi(Ax25Address("AAA"), Ax25Address("BBB"), bytes(), role = Ax25Role.RESPONSE)
        assertEquals(0x60, f[6].toInt() and 0xFF) // destination C = 0
        assertEquals(0xE1, f[13].toInt() and 0xFF) // source C = 1, E = 1
    }

    @Test
    fun decodeRoundTrip() {
        val info = ByteArray(220) { (it * 7).toByte() }
        val wire = Ax25FrameCodec.encodeUi(
            Ax25Address("K1ABC", 3), Ax25Address("W2XYZ", 15), info,
            digipeaters = listOf(Ax25Address("WIDE1", 1), Ax25Address("WIDE2", 2)),
        )
        val r = Ax25FrameCodec.decode(wire)
        assertIs<Ax25DecodeResult.Ok>(r)
        val f = r.frame
        assertEquals("K1ABC-3", f.destination.toString())
        assertEquals("W2XYZ-15", f.source.toString())
        assertEquals(listOf("WIDE1-1", "WIDE2-2"), f.digipeaters.map { it.toString() })
        assertTrue(f.isUi)
        assertEquals(0xF0, f.pid)
        assertEquals(Ax25Role.COMMAND, f.role)
        assertContentEquals(info, f.info)
    }

    @Test
    fun decodeKnownAprsFrame() {
        val wire = bytes(
            0x82, 0xA0, 0xA4, 0xA6, 0x40, 0x40, 0x60,
            0x9C, 0x60, 0x86, 0x82, 0x98, 0x98, 0x63,
            0x03, 0xF0, 0x3E, 0x68, 0x69,
        )
        val r = Ax25FrameCodec.decode(wire) as Ax25DecodeResult.Ok
        assertEquals("APRS", r.frame.destination.toString())
        assertEquals("N0CALL-1", r.frame.source.toString())
        assertEquals(Ax25Role.LEGACY, r.frame.role) // both C bits 0
        assertContentEquals(">hi".encodeToByteArray(), r.frame.info)
    }

    @Test
    fun pidNeverArpaIp() {
        assertFailsWith<IllegalArgumentException> {
            Ax25FrameCodec.encodeUi(Ax25Address("AAA"), Ax25Address("BBB"), bytes(), pid = 0xCC)
        }
        assertFailsWith<IllegalArgumentException> {
            Ax25FrameCodec.encodeUi(Ax25Address("AAA"), Ax25Address("BBB"), bytes(), pid = 0xCD)
        }
        val f = Ax25FrameCodec.encodeUi(Ax25Address("AAA"), Ax25Address("BBB"), bytes())
        assertEquals(0xF0, f[15].toInt() and 0xFF)
    }

    @Test
    fun nonUiFramesAreParsedWithoutPidWhenTheyHaveNone() {
        // SABM (0x2F) has no PID and no info
        val wire = bytes(
            0x82, 0xA0, 0xA4, 0xA6, 0x40, 0x40, 0xE0,
            0x9C, 0x60, 0x86, 0x82, 0x98, 0x98, 0x63,
            0x2F,
        )
        val f = (Ax25FrameCodec.decode(wire) as Ax25DecodeResult.Ok).frame
        assertNull(f.pid)
        assertTrue(!f.isUi)
        assertEquals(0, f.info.size)
    }

    @Test
    fun malformedFramesAreErrorsNotExceptions() {
        assertIs<Ax25DecodeResult.Error>(Ax25FrameCodec.decode(ByteArray(0)))
        assertIs<Ax25DecodeResult.Error>(Ax25FrameCodec.decode(ByteArray(14)))
        // address field never terminated
        val noEnd = ByteArray(100) { 0x40 }
        assertEquals(Ax25DecodeError.TOO_MANY_DIGIPEATERS, (Ax25FrameCodec.decode(noEnd) as Ax25DecodeResult.Error).reason)
        // garbage that looks terminated but is not a callsign
        val badCall = ByteArray(16) { 0x01 }
        assertNotNull(Ax25FrameCodec.decode(badCall))
        assertIs<Ax25DecodeResult.Error>(Ax25FrameCodec.decode(badCall))
    }

    @Test
    fun addressValidationAndParsing() {
        assertFailsWith<IllegalArgumentException> { Ax25Address("TOOLONGCALL") }
        assertFailsWith<IllegalArgumentException> { Ax25Address("AB-C") }
        assertFailsWith<IllegalArgumentException> { Ax25Address("ABC", 16) }
        assertEquals("N0CALL-5", Ax25Address.parseOrNull("n0call-5")?.toString())
        assertEquals("N0CALL", Ax25Address.parseOrNull("N0CALL")?.toString())
        assertNull(Ax25Address.parseOrNull("N0CALL-x"))
        assertNull(Ax25Address.parseOrNull(""))
    }

    @Test
    fun throughKissAndBack() {
        val ax = Ax25FrameCodec.encodeUi(Ax25Address("FLASH"), Ax25Address("K7ZZZ", 2), bytes(0xC0, 0xDB, 1))
        val kiss = KissFrameCodec.encodeData(ax)
        val f = KissStreamDecoder().feed(kiss).single()
        val back = (Ax25FrameCodec.decode(f.data) as Ax25DecodeResult.Ok).frame
        assertContentEquals(bytes(0xC0, 0xDB, 1), back.info)
    }
}
