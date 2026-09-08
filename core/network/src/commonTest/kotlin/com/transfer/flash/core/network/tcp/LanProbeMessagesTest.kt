@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Moved to `commonTest` in Phase 15-1. [LanProbeMessages] is `commonMain` (its `TxtCodec`-style
 * escaping is pure string work), and the JUnit 4 → `kotlin.test` import swap was the only edit: the
 * four `assertEquals` calls whose first argument is a string literal are comparing *values*, not
 * carrying messages, so nothing was reordered.
 */
class LanProbeMessagesTest {
    @Test
    fun helloRoundTripPreservesEscapedFields() {
        val line = LanProbeMessages.hello(
            protocolVersion = 1,
            deviceId = "device=one",
            friendlyName = "Flash Phone 100%",
        )

        val parsed = LanProbeMessages.parseHello(line)

        assertNotNull(parsed)
        assertEquals(1, parsed?.protocolVersion)
        assertEquals("device=one", parsed?.deviceId)
        assertEquals("Flash Phone 100%", parsed?.friendlyName)
    }

    @Test
    fun okRoundTripPreservesEscapedFields() {
        val line = LanProbeMessages.ok(
            protocolVersion = 1,
            deviceId = "dev-123",
            friendlyName = "Receiver Phone",
        )

        val parsed = LanProbeMessages.parseOk(line)
        assertNotNull(parsed)
        assertEquals(1, parsed?.protocolVersion)
        assertEquals("dev-123", parsed?.deviceId)
        assertEquals("Receiver Phone", parsed?.friendlyName)
    }

    @Test
    fun malformedHelloIsRejected() {
        assertNull(LanProbeMessages.parseHello("FLASH_HELLO version=1"))
    }
}
