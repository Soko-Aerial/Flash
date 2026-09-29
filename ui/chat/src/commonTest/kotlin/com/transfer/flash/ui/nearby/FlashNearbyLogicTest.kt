package com.transfer.flash.ui.nearby

import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `trusted row takes its device kind from the peer's current advertisement`() {
        // Trust is persisted as id + name only, so the PC/Phone badge on a trusted row is a JOIN
        // against what the peer is advertising right now — not a stored field that could go stale.
        val rows = FlashNearbyMath.withDeviceKinds(
            trusted = listOf(NearbyTrustedPeerUi("pc-1", "Flash Desktop")),
            discovered = listOf(NearbyPeerUi("pc-1", "Flash Desktop", transport(), deviceKind = FlashDeviceKind.DESKTOP)),
        )
        assertEquals(FlashDeviceKind.DESKTOP, rows.single().deviceKind)
    }

    @Test
    fun `a trusted peer that is not currently discovered gets no kind`() {
        // The restart case: trust survived, the phone has not been re-found yet. The row still
        // renders and still offers Chat — it simply shows no badge, because nothing has told us
        // what the device is since it went away.
        val rows = FlashNearbyMath.withDeviceKinds(
            trusted = listOf(NearbyTrustedPeerUi("phone-1", "Flash V760")),
            discovered = emptyList(),
        )
        assertEquals(FlashDeviceKind.UNKNOWN, rows.single().deviceKind)
        assertEquals("Flash V760", rows.single().name)
    }

    @Test
    fun `the join does not confuse two peers`() {
        // Both sections key on device id; a peer must never inherit the other's kind.
        val rows = FlashNearbyMath.withDeviceKinds(
            trusted = listOf(NearbyTrustedPeerUi("phone-1", "Flash V760")),
            discovered = listOf(
                NearbyPeerUi("pc-1", "Flash Desktop", transport(), deviceKind = FlashDeviceKind.DESKTOP),
                NearbyPeerUi("phone-1", "Flash V760", transport(), deviceKind = FlashDeviceKind.PHONE),
            ),
        )
        assertEquals(FlashDeviceKind.PHONE, rows.single().deviceKind)
    }

    /** DR3: the "Scan network" caption says what happened without naming hosts, ports or subnets. */
    @Test
    fun `scan caption covers every state`() {
        assertNull(FlashNearbyMath.scanCaption(NearbyNetworkScan.Idle))
        assertEquals("Scanning this network… 42%", FlashNearbyMath.scanCaption(NearbyNetworkScan.Running(42)))
        assertEquals("Scanning this network… 100%", FlashNearbyMath.scanCaption(NearbyNetworkScan.Running(140)))
        assertEquals("Scanning this network… 0%", FlashNearbyMath.scanCaption(NearbyNetworkScan.Running(-3)))
        assertEquals("Found 1 device to connect to.", FlashNearbyMath.scanCaption(NearbyNetworkScan.Done(1, false)))
        assertEquals("Found 2 devices to connect to.", FlashNearbyMath.scanCaption(NearbyNetworkScan.Done(2, false)))
        assertEquals("Scan finished. No devices answered.", FlashNearbyMath.scanCaption(NearbyNetworkScan.Done(0, false)))
        assertEquals(
            "Scan finished. No devices answered. Only this device's part of a large network was checked.",
            FlashNearbyMath.scanCaption(NearbyNetworkScan.Done(0, true)),
        )
        assertEquals(
            "Not connected to a local network.",
            FlashNearbyMath.scanCaption(NearbyNetworkScan.Unavailable(NearbyScanBlock.NO_NETWORK)),
        )
        assertEquals(
            "This isn't a home or office network, so Flash won't scan it.",
            FlashNearbyMath.scanCaption(NearbyNetworkScan.Unavailable(NearbyScanBlock.NOT_LOCAL)),
        )
        assertEquals(
            "There is nobody else on this link.",
            FlashNearbyMath.scanCaption(NearbyNetworkScan.Unavailable(NearbyScanBlock.TOO_SMALL)),
        )
        assertEquals(
            "Scanned a moment ago. Try again shortly.",
            FlashNearbyMath.scanCaption(NearbyNetworkScan.Unavailable(NearbyScanBlock.TOO_SOON)),
        )
    }

    /** DR5: the quiet-network flag needs paired peers, a running scan, and neither a discovered peer nor a live session. */
    @Test
    fun `quiet network needs paired peers and nothing reachable`() {
        assertTrue(FlashNearbyMath.discoveryQuiet(pairedPeers = 2, discoveredPeers = 0, liveSessions = 0, isDiscovering = true))
        // Nobody to look for: an empty network is normal, not suspicious.
        assertFalse(FlashNearbyMath.discoveryQuiet(pairedPeers = 0, discoveredPeers = 0, liveSessions = 0, isDiscovering = true))
        // Something is discovered: discovery works.
        assertFalse(FlashNearbyMath.discoveryQuiet(pairedPeers = 2, discoveredPeers = 1, liveSessions = 0, isDiscovering = true))
        // A live session with a peer discovery cannot see: reachable, so the hint would be false.
        assertFalse(FlashNearbyMath.discoveryQuiet(pairedPeers = 2, discoveredPeers = 0, liveSessions = 1, isDiscovering = true))
        // Discovery is not running (radios off, stack booting): that is a different message.
        assertFalse(FlashNearbyMath.discoveryQuiet(pairedPeers = 2, discoveredPeers = 0, liveSessions = 0, isDiscovering = false))
    }

    @Test
    fun `quiet hint waits thirty seconds and names no protocol`() {
        assertEquals(30_000L, FlashNearbyMath.QUIET_HINT_DELAY_MS)
        val copy = (FlashNearbyMath.QUIET_HINT_TITLE + " " + FlashNearbyMath.QUIET_HINT_BODY).lowercase()
        for (jargon in listOf("multicast", "mdns", "port", "tcp", "subnet", "broadcast", "45822")) {
            assertFalse(jargon in copy, jargon)
        }
    }

    private fun transport() = FlashNetworkTransport.Lan
}
