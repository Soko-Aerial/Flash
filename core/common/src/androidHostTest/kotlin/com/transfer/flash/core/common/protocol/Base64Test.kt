package com.transfer.flash.core.common.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Base64Test {

    @Test
    fun encode_decode_empty() {
        assertArrayEquals(ByteArray(0), Base64.decode(""))
    }

    @Test
    fun encode_decode_hello() {
        val input = "Hello, World!"
        val encoded = Base64.encodeUtf8(input)
        assertEquals("SGVsbG8sIFdvcmxkIQ==", encoded)
        assertEquals(input, Base64.decodeUtf8(encoded))
    }

    @Test
    fun encode_decode_binary() {
        val input = byteArrayOf(0x00, 0x01, 0x02, 0x7F, -1, -128)
        val encoded = Base64.encode(input)
        val decoded = Base64.decode(encoded)
        assertArrayEquals(input, decoded)
    }

    @Test
    fun encode_decode_sdp() {
        val sdp = buildString {
            appendLine("v=0")
            appendLine("o=- 1234567890 2 IN IP4 192.168.1.42")
            appendLine("s=-")
            appendLine("t=0 0")
            appendLine("a=group:BUNDLE 0 1")
            appendLine("m=audio 9 UDP/TLS/RTP/SAVPF 111")
            appendLine("a=rtpmap:111 opus/48000/2")
            appendLine("a=fmtp:111 minptime=10;useinbandfec=1")
            appendLine("m=video 9 UDP/TLS/RTP/SAVPF 96")
            appendLine("a=rtpmap:96 H264/90000")
            appendLine("a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f")
        }
        val encoded = Base64.encodeUtf8(sdp)
        val decoded = Base64.decodeUtf8(encoded)
        assertEquals(sdp, decoded)
    }

    @Test
    fun decode_invalid_char_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            Base64.decode("SGVsbG8!!!")
        }
    }

    @Test
    fun decode_non_ascii_char_throws_illegal_argument() {
        assertThrows(IllegalArgumentException::class.java) {
            Base64.decode("SGVsbG8€")
        }
    }

    @Test
    fun decode_bad_padding_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            Base64.decode("SGVsbA=")
        }
    }

    @Test
    fun padded_round_trips() {
        // 1 byte → 2 padding chars
        assertEquals("Zg==", Base64.encodeUtf8("f"))
        assertEquals("f", Base64.decodeUtf8("Zg=="))
        // 2 bytes → 1 padding char
        assertEquals("Zm8=", Base64.encodeUtf8("fo"))
        assertEquals("fo", Base64.decodeUtf8("Zm8="))
        // 3 bytes → 0 padding chars
        assertEquals("Zm9v", Base64.encodeUtf8("foo"))
        assertEquals("foo", Base64.decodeUtf8("Zm9v"))
    }
}