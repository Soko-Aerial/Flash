package com.transfer.flash.ui.nearby

import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for UI-048 nearby-page pure helpers. */
class FlashNearbyLogicTest {

    @Test
    fun `status line reflects scanning and count`() {
        assertEquals("Scanning…", FlashNearbyMath.statusLine(isScanning = true, peerCount = 0))
        assertEquals("1 device nearby", FlashNearbyMath.statusLine(isScanning = true, peerCount = 1))
        assertEquals("3 devices nearby", FlashNearbyMath.statusLine(isScanning = false, peerCount = 3))
        assertEquals("Scan paused", FlashNearbyMath.statusLine(isScanning = false, peerCount = 0))
    }

    @Test
    fun `peers sort alphabetically with id tiebreak`() {
        val sorted = FlashNearbyMath.sortedPeers(
            listOf(
                NearbyPeerUi("b2", "ravi", transport = transport()),
                NearbyPeerUi("a1", "Ravi", transport = transport()),
                NearbyPeerUi("c3", "Amir", transport = transport()),
            ),
        )
        assertEquals(listOf("c3", "a1", "b2"), sorted.map { it.id })
    }

    @Test
    fun `identity subtitle truncates id to eight chars`() {
        val subtitle = FlashNearbyMath.identitySubtitle(
            NearbyIdentityUi("Pixel", "abcdef123456", 4747),
        )
        assertEquals("id abcdef12 · port 4747", subtitle)
    }

    private fun transport() = FlashNetworkTransport.Lan
}
