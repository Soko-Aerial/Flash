@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import com.transfer.flash.core.common.annotation.FlashInternalApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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
