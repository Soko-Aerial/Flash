package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtCodecTest {

    private val identity = FlashAdvertisedIdentity(
        deviceId = FlashDeviceId("dev-abc-123"),
        friendlyName = "Pixel Flash",
        deviceModel = "Pixel 9",
        protocolVersion = 3,
    )

    @Test
    fun encode_producesContractKeySet() {
        val attrs = TxtCodec.encode(identity)
        assertEquals(
            setOf("device_id", "name", "model", "proto"),
            attrs.keys,
        )
        assertEquals("dev-abc-123", attrs["device_id"])
        assertEquals("Pixel Flash", attrs["name"])
        assertEquals("Pixel 9", attrs["model"])
        assertEquals("3", attrs["proto"])
    }

    @Test
    fun roundtrip_encodeThenDecode_preservesIdentity() {
        val decoded = TxtCodec.decode(TxtCodec.encode(identity))
        assertEquals(identity, decoded)
    }

    @Test
    fun decode_missingDeviceId_returnsNull() {
        assertNull(TxtCodec.decode(mapOf("name" to "x", "proto" to "1")))
    }

    @Test
    fun decode_blankDeviceId_returnsNull() {
        assertNull(TxtCodec.decode(mapOf("device_id" to "   ", "proto" to "1")))
    }

    @Test
    fun decode_missingOrInvalidProto_returnsNullWithoutThrowing() {
        assertNull(TxtCodec.decode(mapOf("device_id" to "d1")))
        assertNull(TxtCodec.decode(mapOf("device_id" to "d1", "proto" to "not-a-number")))
        assertNull(TxtCodec.decode(mapOf("device_id" to "d1", "proto" to "")))
    }

    @Test
    fun decode_trimsWhitespaceAroundValues() {
        val decoded = TxtCodec.decode(
            mapOf(
                "device_id" to " dev-1 ",
                "name" to " My Phone ",
                "model" to " Model X ",
                "proto" to " 2 ",
            ),
        )
        assertEquals("dev-1", decoded?.deviceId?.value)
        assertEquals("My Phone", decoded?.friendlyName)
        assertEquals("Model X", decoded?.deviceModel)
        assertEquals(2, decoded?.protocolVersion)
    }

    @Test
    fun decode_ignoresUnknownKeys_forwardCompatible() {
        val decoded = TxtCodec.decode(
            mapOf(
                "device_id" to "d1",
                "proto" to "1",
                "future_key" to "whatever",
            ),
        )
        assertTrue(decoded != null)
        assertEquals("", decoded?.friendlyName)
        assertEquals("", decoded?.deviceModel)
    }

    // ------------------------------------------------------------------
    // P3.5-A2: caps + fp8
    // ------------------------------------------------------------------

    private val fullIdentity = FlashAdvertisedIdentity(
        deviceId = FlashDeviceId("dev-caps"),
        friendlyName = "Pixel Flash",
        deviceModel = "Pixel 9",
        protocolVersion = 3,
        capabilities = setOf("kiosk", "voice"),
        fingerprintPrefix = "a1b2c3d4",
    )

    @Test
    fun encode_withCapsAndFp8_includesKeys() {
        val attrs = TxtCodec.encode(fullIdentity)
        assertEquals("kiosk,voice", attrs["caps"])
        assertEquals("a1b2c3d4", attrs["fp8"])
    }

    @Test
    fun encode_withoutCapsAndFp8_omitsKeys() {
        // Old-style identities (pre-P3.5 callers) must not gain empty junk keys.
        assertFalse(attrsHasOptionalKeys(TxtCodec.encode(identity)))
    }

    private fun attrsHasOptionalKeys(attrs: Map<String, String>) =
        attrs.containsKey("caps") || attrs.containsKey("fp8")

    @Test
    fun roundtrip_capsAndFp8_preserved() {
        val decoded = TxtCodec.decode(TxtCodec.encode(fullIdentity))
        assertEquals(fullIdentity.capabilities, decoded?.capabilities)
        assertEquals(fullIdentity.fingerprintPrefix, decoded?.fingerprintPrefix)
    }

    @Test
    fun decode_missingCapsAndFp8_defaultsToEmptyAndNull() {
        val decoded = TxtCodec.decode(mapOf("device_id" to "d1", "proto" to "1"))
        assertEquals(emptySet<String>(), decoded?.capabilities)
        assertNull(decoded?.fingerprintPrefix)
    }

    @Test
    fun decode_blankAndMalformedCaps_tolerated() {
        val blank = TxtCodec.decode(mapOf("device_id" to "d1", "proto" to "1", "caps" to " , ,"))
        assertEquals(emptySet<String>(), blank?.capabilities)
        // Hostile input never throws.
        val weird = TxtCodec.decode(mapOf("device_id" to "d1", "proto" to "1", "caps" to "a,,b, c "))
        assertEquals(setOf("a", "b", "c"), weird?.capabilities)
    }

    @Test
    fun truncateFlags_overSizedSet_dropsWholeTrailingFlags_neverMidToken() {
        // Two 60-char flags join to 121 > 120 budget; three join to 182.
        val longA = "a".repeat(60)
        val longB = "b".repeat(60)
        val result = TxtCodec.truncateFlags(setOf(longA, longB, "c".repeat(60)))
        assertEquals(longA, result)

        // Single flag longer than the whole budget cannot fit → empty (omitted).
        assertEquals("", TxtCodec.truncateFlags(setOf("x".repeat(TxtCodec.MAX_CAPS_VALUE_LENGTH + 10))))
    }

    @Test
    fun encode_overSizedCaps_truncatedWithinGuard() {
        val many = (1..50).map { "flag$it" }.toSet()
        val attrs = TxtCodec.encode(
            identity.copy(capabilities = many),
        )
        val caps = attrs["caps"]
        requireNotNull(caps)
        assertTrue(caps.length <= TxtCodec.MAX_CAPS_VALUE_LENGTH)
        // No partial token: every retained flag survives intact.
        caps.split(",").forEach { flag ->
            assertTrue(many.contains(flag))
        }
    }
}
