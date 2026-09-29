package com.transfer.flash.core.calling

import java.net.InetAddress
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** ERROR-079: which interface WebRTC is told about on a hotspot host. */
class LocalInterfacePickerTest {

    private fun iface(
        name: String,
        vararg ips: String,
        up: Boolean = true,
        loopback: Boolean = false,
        pointToPoint: Boolean = false,
    ) = LocalInterfacePicker.Candidate(
        name = name,
        up = up,
        loopback = loopback,
        pointToPoint = pointToPoint,
        addresses = ips.map { InetAddress.getByName(it) },
    )

    @Test
    fun hotspotInterfaceIsPickedWhenStockDetectorDoesNotKnowIt() {
        val picked = LocalInterfacePicker.pick(
            listOf(
                iface("lo", "127.0.0.1", loopback = true),
                iface("rmnet_data0", "10.45.2.9"),
                iface("ap0", "10.167.108.67", "fe80::1"),
            ),
            known = setOf("rmnet_data0"),
        )
        assertEquals("ap0", picked?.name)
    }

    @Test
    fun interfacesTheStockDetectorReportsAreLeftAlone() {
        // An ordinary Wi-Fi client: wlan0 is a ConnectivityManager network already.
        assertNull(LocalInterfacePicker.pick(listOf(iface("wlan0", "192.168.1.20")), setOf("wlan0")))
    }

    @Test
    fun cellularVpnAndClatInterfacesAreNeverPicked() {
        val picked = LocalInterfacePicker.pick(
            listOf(
                iface("rmnet_data1", "10.1.1.1"),
                iface("ccmni0", "10.2.2.2"),
                iface("v4-wlan0", "192.0.0.4"),
                iface("tun0", "10.8.0.2"),
                iface("dummy0", "10.9.9.9"),
            ),
            known = emptySet(),
        )
        assertNull(picked)
    }

    @Test
    fun downPointToPointAndPublicOnlyInterfacesAreSkipped() {
        val picked = LocalInterfacePicker.pick(
            listOf(
                iface("swlan0", "192.168.43.1", up = false),
                iface("ptp0", "10.3.3.3", pointToPoint = true),
                iface("wlan1", "fe80::2"),
                iface("eth9", "203.0.113.5"),
            ),
            known = emptySet(),
        )
        assertNull(picked)
    }

    @Test
    fun hotspotIsPreferredOverWifiDirectGroup() {
        val picked = LocalInterfacePicker.pick(
            listOf(iface("p2p-wlan0-0", "192.168.49.1"), iface("swlan0", "192.168.43.1")),
            known = setOf("wlan0"),
        )
        assertEquals("swlan0", picked?.name)
    }

    @Test
    fun wifiDirectGroupIsPickedWhenAlone() {
        val picked = LocalInterfacePicker.pick(listOf(iface("p2p-wlan0-0", "192.168.49.1")), emptySet())
        assertEquals("p2p-wlan0-0", picked?.name)
    }
}
