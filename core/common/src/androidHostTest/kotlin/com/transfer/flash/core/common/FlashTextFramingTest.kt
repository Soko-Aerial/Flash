package com.transfer.flash.core.common

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashTextFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(FlashInternalApi::class)
class FlashTextFramingTest {

    @Test
    fun escape_and_unescape_round_trip() {
        val original = "Pixel 9 Pro / Office = 100% test"
        val escaped = FlashTextFraming.escape(original)
        assertEquals("Pixel%209%20Pro%20/%20Office%20%3D%20100%25%20test", escaped)

        val unescaped = FlashTextFraming.unescape(escaped)
        assertEquals(original, unescaped)
    }

    @Test
    fun encodeFields_and_parseFields_round_trip() {
        val prefix = "FLASH_HELLO"
        val fields = listOf(
            "version" to "1",
            "deviceId" to "device-uuid-1234",
            "name" to "Alice's Phone (5G)"
        )

        val line = FlashTextFraming.encodeFields(prefix, fields)
        assertEquals("FLASH_HELLO version=1 deviceId=device-uuid-1234 name=Alice's%20Phone%20(5G)", line)

        val parsed = FlashTextFraming.parseFields(line, prefix)
        assertEquals(
            mapOf(
                "version" to "1",
                "deviceId" to "device-uuid-1234",
                "name" to "Alice's Phone (5G)"
            ),
            parsed
        )
    }

    @Test
    fun parseFields_returns_null_on_prefix_mismatch() {
        val line = "FLASH_HELLO version=1"
        val parsed = FlashTextFraming.parseFields(line, "FLASH_OK")
        assertNull(parsed)
    }

    @Test
    fun parseFields_ignores_malformed_pairs() {
        val line = "FLASH_TEST valid=1 malformed_entry valid2=2"
        val parsed = FlashTextFraming.parseFields(line, "FLASH_TEST")
        assertEquals(
            mapOf(
                "valid" to "1",
                "valid2" to "2"
            ),
            parsed
        )
    }
}
