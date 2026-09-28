package com.transfer.flash.core.network.presence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresenceCodecTest {

    private val salt = "00112233445566778899aabbccddeeff"

    @Test
    fun helloRoundTrips() {
        val frame = PresenceFrame.Hello(share = true, salt = salt)
        assertEquals(frame, PresenceCodec.decode(PresenceCodec.encode(frame)))
    }

    @Test
    fun ghostHelloCarriesNoSalt() {
        val text = PresenceCodec.encode(PresenceFrame.Hello(share = false, salt = null))
        assertEquals(PresenceFrame.Hello(share = false, salt = null), PresenceCodec.decode(text))
        // A salt on a share=0 hello is ignored: a Ghost device reports nothing, so nothing to ask for.
        assertEquals(
            PresenceFrame.Hello(share = false, salt = null),
            PresenceCodec.decode("FLASH_PRES v=1 t=hello share=0 salt=$salt"),
        )
    }

    @Test
    fun wantRoundTripsAndDropsMalformedHashes() {
        val frame = PresenceFrame.Want(setOf("0123456789abcdef", "fedcba9876543210"))
        assertEquals(frame, PresenceCodec.decode(PresenceCodec.encode(frame)))
        val decoded = PresenceCodec.decode("FLASH_PRES v=1 t=want h=0123456789abcdef,XYZ,0123") as PresenceFrame.Want
        assertEquals(setOf("0123456789abcdef"), decoded.hashes)
    }

    @Test
    fun reportRoundTripsWithAndWithoutEndpoint() {
        val frame = PresenceFrame.Report(
            full = false,
            entries = listOf(
                PresenceEntry("peer-a", 1200, PresenceReportState.Connected, 1, PresenceEndpoint("192.168.1.20", 45822)),
                PresenceEntry("peer-b", 0, PresenceReportState.Seen, 2),
                PresenceEntry("peer-c", 0, PresenceReportState.Gone, 1),
                PresenceEntry("peer-d", 5, PresenceReportState.Seen, 1, PresenceEndpoint("fe80::1%wlan0", 45822)),
            ),
        )
        assertEquals(frame, PresenceCodec.decode(PresenceCodec.encode(frame)))
    }

    @Test
    fun digestDropsWithdrawals() {
        val decoded = PresenceCodec.decode("FLASH_PRES v=1 t=digest e=a|0|x|1|;b|0|s|1|") as PresenceFrame.Report
        assertEquals(listOf("b"), decoded.entries.map { it.deviceId })
    }

    @Test
    fun invalidEntriesAreDroppedOneByOne() {
        val text = "FLASH_PRES v=1 t=delta e=" + listOf(
            "ok|0|c|1|",
            "hops3|0|c|3|", // over the hop limit
            "hops0|0|c|0|",
            "old|999999999|c|1|", // older than anything accepted
            "neg|-1|c|1|",
            "bad/id|0|c|1|",
            "name|0|c|1|example.com:45822", // host names are never dialed
            "loop|0|c|1|127.0.0.1:45822",
            "any|0|c|1|0.0.0.0:45822",
            "mcast|0|c|1|224.0.0.251:5353",
            "bcast|0|c|1|255.255.255.255:1",
            "port|0|c|1|10.0.0.2:70000",
            "v6loop|0|c|1|::1:45822",
            "state|0|q|1|",
            "short|0|c",
        ).joinToString(";")
        val decoded = PresenceCodec.decode(text) as PresenceFrame.Report
        assertEquals(listOf("ok"), decoded.entries.map { it.deviceId })
    }

    @Test
    fun unknownVersionOrTypeDecodesToNullButIsStillAPresenceFrame() {
        for (text in listOf("FLASH_PRES v=2 t=hello share=1", "FLASH_PRES v=1 t=gossip", "FLASH_PRES t=hello share=1", "FLASH_PRES")) {
            assertNull(PresenceCodec.decode(text), text)
            assertTrue(PresenceCodec.isPresenceFrame(text), text)
        }
    }

    @Test
    fun otherPrefixesAreNotPresenceFrames() {
        for (text in listOf("FLASH_PRESENCE v=1", "FLASH_PTT x=1", "FLASH_WS_HELLO version=2", "")) {
            assertFalse(PresenceCodec.isPresenceFrame(text), text)
            assertNull(PresenceCodec.decode(text), text)
        }
    }

    @Test
    fun oversizedListsAreRejectedWhole() {
        val many = (0..PresenceCodec.MAX_ENTRIES).joinToString(";") { "p$it|0|s|1|" }
        assertNull(PresenceCodec.decode("FLASH_PRES v=1 t=digest e=$many"))
        val hashes = (0..PresenceCodec.MAX_WANT).joinToString(",") { "0123456789abcdef" }
        assertNull(PresenceCodec.decode("FLASH_PRES v=1 t=want h=$hashes"))
    }

    @Test
    fun matchHashDependsOnSaltAndId() {
        val h1 = PresenceCodec.matchHash(TestHash::digest, salt, "peer-a")
        assertEquals(PresenceCodec.HASH_BYTES * 2, h1.length)
        assertEquals(h1, PresenceCodec.matchHash(TestHash::digest, salt, "peer-a"))
        assertNotEquals(h1, PresenceCodec.matchHash(TestHash::digest, salt, "peer-b"))
        assertNotEquals(h1, PresenceCodec.matchHash(TestHash::digest, "ffeeddccbbaa99887766554433221100", "peer-a"))
    }
}

/** Deterministic stand-in for SHA-256: `core:network`'s common tests have no crypto provider. */
internal object TestHash {
    fun digest(input: ByteArray): ByteArray {
        var h = 0xcbf29ce484222325uL
        val out = ByteArray(32)
        for (round in 0 until 4) {
            for (b in input) {
                h = h xor (b.toULong() and 0xFFuL)
                h *= 0x100000001b3uL
            }
            h = h xor round.toULong()
            for (i in 0 until 8) out[round * 8 + i] = (h shr (i * 8)).toByte()
        }
        return out
    }
}
