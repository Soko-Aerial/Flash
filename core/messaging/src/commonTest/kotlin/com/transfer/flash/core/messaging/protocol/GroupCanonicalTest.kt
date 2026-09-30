package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Golden vectors for the v2 group signature layouts (plan D3). The expected hex was produced by an
 * independent reference (a 12-line Python script: 4-byte big-endian length before every field,
 * tag first), not by this code, so the test pins the layout instead of echoing the implementation.
 * A failure here is a wire break: change the tag, never the expectation.
 */
class GroupCanonicalTest {
    private val gid = "g2-6f01129f28cff84f8ef67cb9d1970499"
    private val ownerKey = "AAECAwQFBgcICQoLDA0ODw=="
    private val subjectKey = "ICEiIyQlJicoKSorLC0uLw=="
    private val nonce = "oKGio6SlpqeoqaqrrK2urw=="

    @Test
    fun charterBytesMatchTheReference() {
        val charter = GroupCharter(gid, "Café Crew", "owner-1", ownerKey, 1_700_000_000_000L, nonce, 2, sig = "")
        assertEquals(
            "00000011666c6173682d67636861727465722d76310000002367322d3666303131323966323863666638346638656636" +
                "376362396431393730343939000000" + "0a436166c3a92043726577000000076f776e65722d3100000010000102030405" +
                "060708090a0b0c0d0e0f000000080000018bcfe5680000000010a0a1a2a3a4a5a6a7a8a9aaabacadaeaf" +
                "000000080000000000000002",
            GroupCanonical.charterBytes(charter)!!.toHex(),
        )
    }

    @Test
    fun certBytesMatchTheReference() {
        val cert = MemberCert(gid, "dev-b", subjectKey, "Béa", "member", 3L, "op-7", true, "owner-1", sig = "")
        assertEquals(
            "0000000e666c6173682d67636572742d76310000002367322d3666303131323966323863666638346638656636" +
                "376362396431393730343939000000056465762d6200000010202122232425262728292a2b2c2d2e2f" +
                "0000000442c3a961000000066d656d626572000000080000000000000003000000046f702d3700000001" +
                "01000000076f776e65722d31",
            GroupCanonical.certBytes(cert)!!.toHex(),
        )
    }

    @Test
    fun messageBytesMatchTheReferenceWithAndWithoutAReply() {
        assertEquals(
            "0000000d666c6173682d676d73672d76310000002367322d3666303131323966323863666638346638656636" +
                "376362396431393730343939000000036d2d31000000056465762d62000000080000018bcfe5687b0000" +
                "0000000000000000000668c3a96c6c6f",
            GroupCanonical.messageBytes(gid, "m-1", "dev-b", 1_700_000_000_123L, null, null, "héllo").toHex(),
        )
        assertEquals(
            "0000000d666c6173682d676d73672d76310000002367322d3666303131323966323863666638346638656636" +
                "376362396431393730343939000000036d2d32000000056465762d62000000080000018bcfe569c8" +
                "000000036d2d310000000668c3a96c6c6f0000000f72653a2031303025203d2066696e65",
            GroupCanonical.messageBytes(
                gid, "m-2", "dev-b", 1_700_000_000_456L, "m-1", "héllo", "re: 100% = fine",
            ).toHex(),
        )
    }

    @Test
    fun anAbsentReplyIsTheEmptyString() {
        val absent = GroupCanonical.messageBytes(gid, "m", "a", 1L, null, null, "t")
        val empty = GroupCanonical.messageBytes(gid, "m", "a", 1L, "", "", "t")
        assertEquals(absent.toHex(), empty.toHex())
    }

    @Test
    fun lengthPrefixingKeepsFieldBoundariesUnambiguous() {
        // ("ab","c") and ("a","bc") would collide under plain concatenation.
        val one = GroupCanonical.messageBytes(gid, "ab", "c", 1L, null, null, "t")
        val two = GroupCanonical.messageBytes(gid, "a", "bc", 1L, null, null, "t")
        assert(one.toHex() != two.toHex())
    }

    @Test
    fun malformedBase64MakesTheObjectUnsignable() {
        val bad = GroupCharter(gid, "n", "o", "***", 1L, nonce, 2, sig = "")
        assertNull(GroupCanonical.charterBytes(bad))
        val badCert = MemberCert(gid, "s", "***", "l", "member", 1L, "op", true, "o", sig = "")
        assertNull(GroupCanonical.certBytes(badCert))
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder()
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(digits[v shr 4]).append(digits[v and 0x0F])
        }
        return out.toString()
    }
}
