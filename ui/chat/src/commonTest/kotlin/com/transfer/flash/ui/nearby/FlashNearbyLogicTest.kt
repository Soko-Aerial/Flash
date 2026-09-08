package com.transfer.flash.ui.nearby

import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM tests for UI-048 nearby-page pure helpers. */
class FlashNearbyLogicTest {

    @Test
    fun `status line reflects scanning and count`() {
        assertEquals("Scanning…", FlashNearbyMath.statusLine(isScanning = true, peerCount = 0))
        assertEquals("1 device nearby", FlashNearbyMath.statusLine(isScanning = true, peerCount = 1))
        assertEquals("3 devices nearby", FlashNearbyMath.statusLine(isScanning = false, peerCount = 3))
        assertEquals("Scan paused", FlashNearbyMath.statusLine(isScanning = false, peerCount = 0))
    }

    /**
     * ERROR-034: before the discovery stack boots `isScanning` is false, which used to read as
     * "Scan paused" — a pause the user never asked for, of a scan that had not begun.
     */
    @Test
    fun `status line separates not-started from paused`() {
        assertEquals(
            "Starting…",
            FlashNearbyMath.statusLine(isScanning = false, peerCount = 0, isLoading = true),
        )
        assertEquals(
            "Scan paused",
            FlashNearbyMath.statusLine(isScanning = false, peerCount = 0, isLoading = false),
        )
        // A real peer count outranks loading: if we found a device during boot, say so.
        assertEquals(
            "2 devices nearby",
            FlashNearbyMath.statusLine(isScanning = false, peerCount = 2, isLoading = true),
        )
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
