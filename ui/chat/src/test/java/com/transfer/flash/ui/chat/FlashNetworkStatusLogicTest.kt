package com.transfer.flash.ui.chat

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.icons.FlashIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashNetworkStatusLogicTest {

    // --- resolveHealth branch table ---

    @Test
    fun `resolveHealth covers every rule branch`() {
        val cases = listOf(
            // (transport, presence, peerCount) -> expected health
            Triple(FlashNetworkTransport.Unknown, FlashPeerPresence.Offline, 0), // no link, no peers
            Triple(FlashNetworkTransport.Lan, FlashPeerPresence.Connecting, 1),
            Triple(FlashNetworkTransport.WifiDirect, FlashPeerPresence.Connecting, 3),
            Triple(FlashNetworkTransport.Lan, FlashPeerPresence.Online, 1),
            Triple(FlashNetworkTransport.WifiDirect, FlashPeerPresence.Online, 2),
            Triple(FlashNetworkTransport.Relay, FlashPeerPresence.Online, 1),
            Triple(FlashNetworkTransport.Lan, FlashPeerPresence.Offline, 1),
            Triple(FlashNetworkTransport.Relay, FlashPeerPresence.Offline, 1),
            Triple(FlashNetworkTransport.Lan, FlashPeerPresence.Online, 0),
        )
        val expected = listOf(
            FlashConnectionHealth.Offline,
            FlashConnectionHealth.Connecting,
            FlashConnectionHealth.Connecting,
            FlashConnectionHealth.Connected,
            FlashConnectionHealth.Connected,
            FlashConnectionHealth.Degraded,
            FlashConnectionHealth.Offline,
            FlashConnectionHealth.Offline,
            FlashConnectionHealth.Offline,
        )
        assertEquals(expected.size, cases.size)
        cases.zip(expected) { (transport, presence, peerCount), health ->
            assertEquals(
                "transport=$transport presence=$presence peerCount=$peerCount",
                health,
                FlashNetworkStatusMath.resolveHealth(transport, presence, peerCount),
            )
        }
    }

    @Test
    fun `connecting presence outranks direct transport online`() {
        // Handshake in flight is Connecting even though transport is usable.
        assertEquals(
            FlashConnectionHealth.Connecting,
            FlashNetworkStatusMath.resolveHealth(FlashNetworkTransport.Lan, FlashPeerPresence.Connecting, peerCount = 1),
        )
    }

    @Test
    fun `connecting presence outranks an unnamed transport`() {
        // ERROR-031: while a dropped session is being re-established the repository reports
        // Connecting with no transport to name. That is the reconnect window, not idle searching —
        // the banner must agree with the "Connecting…" the header is already showing.
        assertEquals(
            FlashConnectionHealth.Connecting,
            FlashNetworkStatusMath.resolveHealth(
                FlashNetworkTransport.Unknown,
                FlashPeerPresence.Connecting,
                peerCount = 1,
            ),
        )
        // Sending is never blocked mid-reconnect: the message queues in the outbox and drains when
        // the session returns, instead of the composer going dead.
        assertFalse(FlashNetworkStatusMath.isBlockingState(FlashConnectionHealth.Connecting))
    }

    @Test
    fun `fallback branch resolves to degraded`() {
        // Relay + typing peers: alive but not direct → Degraded (else-branch).
        assertEquals(
            FlashConnectionHealth.Degraded,
            FlashNetworkStatusMath.resolveHealth(FlashNetworkTransport.Relay, FlashPeerPresence.Typing, peerCount = 2),
        )
    }

    // --- Labels ---

    @Test
    fun `health labels match copy spec`() {
        val cases = mapOf(
            FlashConnectionHealth.Connected to "Connected",
            FlashConnectionHealth.Degraded to "Degraded connection",
            FlashConnectionHealth.Connecting to "Connecting…",
            FlashConnectionHealth.Offline to "Searching for devices…",
        )
        cases.forEach { (health, label) ->
            assertEquals(label, FlashNetworkStatusMath.healthLabel(health))
        }
    }

    @Test
    fun `connected label qualifies direct transports`() {
        assertEquals(
            "Connected · LAN",
            FlashNetworkStatusMath.healthLabel(FlashConnectionHealth.Connected, FlashNetworkTransport.Lan),
        )
        assertEquals(
            "Connected · Wi-Fi Direct",
            FlashNetworkStatusMath.healthLabel(FlashConnectionHealth.Connected, FlashNetworkTransport.WifiDirect),
        )
        assertEquals(
            "Connected",
            FlashNetworkStatusMath.healthLabel(FlashConnectionHealth.Connected, FlashNetworkTransport.Relay),
        )
    }

    @Test
    fun `degraded with relay reads as relayed`() {
        assertEquals(
            "Relayed",
            FlashNetworkStatusMath.healthLabel(FlashConnectionHealth.Degraded, FlashNetworkTransport.Relay),
        )
    }

    @Test
    fun `transport badge labels are short and jargon-free`() {
        assertEquals("LAN", FlashNetworkStatusMath.transportLabel(FlashNetworkTransport.Lan))
        assertEquals("Wi-Fi Direct", FlashNetworkStatusMath.transportLabel(FlashNetworkTransport.WifiDirect))
        assertEquals("Relay", FlashNetworkStatusMath.transportLabel(FlashNetworkTransport.Relay))
        assertEquals("No link", FlashNetworkStatusMath.transportLabel(FlashNetworkTransport.Unknown))
    }

    // --- Blocking + severity mapping ---

    @Test
    fun `only offline blocks sending`() {
        assertFalse(FlashNetworkStatusMath.isBlockingState(FlashConnectionHealth.Connected))
        assertFalse(FlashNetworkStatusMath.isBlockingState(FlashConnectionHealth.Connecting))
        assertFalse(FlashNetworkStatusMath.isBlockingState(FlashConnectionHealth.Degraded))
        assertTrue(FlashNetworkStatusMath.isBlockingState(FlashConnectionHealth.Offline))
    }

    @Test
    fun `banner severity is calm for environmental states attention only for offline`() {
        val cases = mapOf(
            FlashConnectionHealth.Connected to FlashNetworkBannerSeverity.Calm,
            FlashConnectionHealth.Connecting to FlashNetworkBannerSeverity.Calm,
            FlashConnectionHealth.Degraded to FlashNetworkBannerSeverity.Calm,
            FlashConnectionHealth.Offline to FlashNetworkBannerSeverity.Attention,
        )
        cases.forEach { (health, severity) ->
            assertEquals(severity, FlashNetworkStatusMath.bannerSeverity(health))
        }
    }

    // --- Icon mapping ---

    @Test
    fun `transport icon specs map every transport including unknown fallback`() {
        assertEquals(FlashIcons.Wifi, FlashNetworkStatusMath.transportIconSpec(FlashNetworkTransport.Lan))
        assertEquals(FlashIcons.WifiDirect, FlashNetworkStatusMath.transportIconSpec(FlashNetworkTransport.WifiDirect))
        assertEquals(FlashIcons.Relay, FlashNetworkStatusMath.transportIconSpec(FlashNetworkTransport.Relay))
        // Unknown never hides the chip — generic Device icon instead of null.
        val unknownIcon = FlashNetworkStatusMath.transportIconSpec(FlashNetworkTransport.Unknown)
        assertNotNull(unknownIcon)
        assertEquals(FlashIcons.Device, unknownIcon)
        FlashNetworkTransport.entries.forEach { transport ->
            assertNotNull("icon missing for $transport", FlashNetworkStatusMath.transportIconSpec(transport))
        }
    }
}
