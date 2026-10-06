package com.transfer.flash.core.common.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class Base64UrlTest {

    @Test
    fun rfc4648_test_vectors() {
        assertEquals("", Base64Url.encode("".encodeToByteArray()))
        assertEquals("Zg", Base64Url.encode("f".encodeToByteArray()))
        assertEquals("Zm8", Base64Url.encode("fo".encodeToByteArray()))
        assertEquals("Zm9v", Base64Url.encode("foo".encodeToByteArray()))
        assertEquals("Zm9vYg", Base64Url.encode("foob".encodeToByteArray()))
        assertEquals("Zm9vYmE", Base64Url.encode("fooba".encodeToByteArray()))
        assertEquals("Zm9vYmFy", Base64Url.encode("foobar".encodeToByteArray()))

        assertEquals("", Base64Url.decode("").decodeToString())
        assertEquals("f", Base64Url.decode("Zg").decodeToString())
        assertEquals("fo", Base64Url.decode("Zm8").decodeToString())
        assertEquals("foo", Base64Url.decode("Zm9v").decodeToString())
        assertEquals("foob", Base64Url.decode("Zm9vYg").decodeToString())
        assertEquals("fooba", Base64Url.decode("Zm9vYmE").decodeToString())
        assertEquals("foobar", Base64Url.decode("Zm9vYmFy").decodeToString())
    }

    @Test
    fun url_safe_alphabet_chars() {
        // Values 62 (-) and 63 (_)
        // Byte 0xfb (0b11111011) -> 62 (0b111110 = -), 48 (0b110000 = w)
        // Byte 0xff, 0xff -> 0b11111111 0b11111111 -> 0b111111 (63 = _), 0b111111 (63 = _), 0b110000 (48 = w)
        val testBytes = byteArrayOf(-5, -1, -1) // 0xfb, 0xff, 0xff
        val encoded = Base64Url.encode(testBytes)
        // Check that + and / are not used, but - and _ are
        assertEquals("----", Base64Url.encode(byteArrayOf(-5, -17, -66)))
        assertEquals("____", Base64Url.encode(byteArrayOf(-1, -1, -1)))

        assertArrayEquals(byteArrayOf(-5, -17, -66), Base64Url.decode("----"))
        assertArrayEquals(byteArrayOf(-1, -1, -1), Base64Url.decode("____"))
    }

    @Test
    fun roundtrip_arbitrary_binary() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = Base64Url.encode(bytes)
        val decoded = Base64Url.decode(encoded)
        assertArrayEquals(bytes, decoded)
    }

    @Test
    fun decode_invalid_mod4_remainder_1_returns_null() {
        assertNull(Base64Url.decodeOrNull("A"))
        assertNull(Base64Url.decodeOrNull("ABCDE"))
        assertThrows(IllegalArgumentException::class.java) {
            Base64Url.decode("A")
        }
    }

    @Test
    fun decode_invalid_characters_returns_null() {
        assertNull(Base64Url.decodeOrNull("AB+D")) // '+' is standard base64, not base64url
        assertNull(Base64Url.decodeOrNull("AB/D")) // '/' is standard base64, not base64url
        assertNull(Base64Url.decodeOrNull("AB=D")) // '=' padding not allowed
        assertNull(Base64Url.decodeOrNull("AB!D"))
        assertThrows(IllegalArgumentException::class.java) {
            Base64Url.decode("AB+D")
        }
    }
}
