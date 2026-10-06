package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.common.protocol.FlashTextFraming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class HelloFeaturesTest {

    @Test
    fun `a HELLO without caps gives an empty set`() {
        assertEquals(emptySet(), parseHelloFeatures(null))
        assertEquals(emptySet(), parseHelloFeatures(""))
        assertEquals(emptySet(), parseHelloFeatures("   "))
    }

    @Test
    fun `sw1,ab gives sw1 and ab`() {
        val parsed = parseHelloFeatures("sw1,ab")
        assertEquals(setOf("sw1", "ab"), parsed)
    }

    @Test
    fun `whitespace around tokens is trimmed`() {
        val parsed = parseHelloFeatures(" sw1 , ab ")
        assertEquals(setOf("sw1", "ab"), parsed)
    }

    @Test
    fun `malformed input is ignored`() {
        // Uppercase not allowed, hyphen/underscore/dot not allowed, too long (>16) not allowed
        val parsed = parseHelloFeatures("SW1,valid1,toolongtoken123456789,bad-token,bad.token,ok2")
        assertEquals(setOf("valid1", "ok2"), parsed)
    }

    @Test
    fun `at most 32 tokens are kept`() {
        val manyTokens = (1..50).joinToString(",") { "tok$it" }
        val parsed = parseHelloFeatures(manyTokens)
        assertEquals(32, parsed.size)
    }

    @Test
    fun `formatHelloFeatures returns null for empty or invalid sets`() {
        assertNull(formatHelloFeatures(emptySet()))
        assertNull(formatHelloFeatures(setOf("INVALID", "bad-token")))
    }

    @Test
    fun `formatHelloFeatures sorts valid tokens`() {
        val formatted = formatHelloFeatures(setOf("zeta", "alpha", "beta", "INVALID"))
        assertEquals("alpha,beta,zeta", formatted)
    }

    @Test
    fun `golden test shows HELLO string is identical when features set is empty`() {
        val prefix = "HELLO"
        val expected = FlashTextFraming.encodeFields(
            prefix,
            "version" to "1",
            "deviceId" to "dev-123",
            "name" to "Phone",
            "ping" to "5000",
            "gv" to FlashProtocol.GROUP_PROTOCOL_LEVEL.toString(),
        )

        // Building with empty features
        val localFeatures: Set<String> = emptySet()
        val fields = mutableListOf(
            "version" to "1",
            "deviceId" to "dev-123",
            "name" to "Phone",
            "ping" to "5000",
            "gv" to FlashProtocol.GROUP_PROTOCOL_LEVEL.toString(),
        )
        val caps = formatHelloFeatures(localFeatures)
        if (caps != null) {
            fields.add("caps" to caps)
        }
        val actual = FlashTextFraming.encodeFields(prefix, fields)

        assertEquals(expected, actual)
        assertTrue(!actual.contains("caps"))
    }
}
