package com.transfer.flash.desktop

import com.transfer.flash.core.common.perf.FlashNetworkBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** G2: the desktop's band from netsh output and its adapters. */
class DesktopNetworkBandTest {

    @Test
    fun `reads the band from netsh output in any language`() {
        val english = """
            There is 1 interface on the system:
                Name                   : Wi-Fi
                State                  : connected
                Radio type             : 802.11ax
                Band                   : 5 GHz
                Channel                : 36
        """.trimIndent()
        assertEquals(FlashNetworkBand.WIFI_5GHZ, DesktopNetworkBand.parseNetshBand(english))
        val german = "    Status                 : Verbunden\n    Frequenzband           : 2,4 GHz\n    Kanal : 6"
        assertEquals(FlashNetworkBand.WIFI_2_4GHZ, DesktopNetworkBand.parseNetshBand(german))
        assertEquals(FlashNetworkBand.WIFI_6GHZ, DesktopNetworkBand.parseNetshBand("Band : 6 GHz"))
    }

    @Test
    fun `no band line or no wifi reads as nothing`() {
        assertNull(DesktopNetworkBand.parseNetshBand("There is 1 interface on the system:\n State : disconnected"))
        assertNull(DesktopNetworkBand.parseNetshBand("The Wireless AutoConfig Service (wlansvc) is not running."))
        assertNull(DesktopNetworkBand.parseNetshBand("Radio type : 802.11ac"))
    }

    @Test
    fun `only physical wired adapters count as ethernet`() {
        assertTrue(DesktopNetworkBand.isWiredAdapter("eth2", "Realtek PCIe GbE Family Controller"))
        assertTrue(DesktopNetworkBand.isWiredAdapter("eth5", "Intel(R) Ethernet Connection (7) I219-V"))
        assertFalse(DesktopNetworkBand.isWiredAdapter("eth9", "Hyper-V Virtual Ethernet Adapter"))
        assertFalse(DesktopNetworkBand.isWiredAdapter("eth3", "Remote NDIS based Internet Sharing Device"))
        assertFalse(DesktopNetworkBand.isWiredAdapter("eth4", "Bluetooth Device (Personal Area Network)"))
        assertFalse(DesktopNetworkBand.isWiredAdapter("wlan1", "Intel(R) Wi-Fi 6 AX201 160MHz"))
    }

    @Test
    fun `a link is limited by its slower known end`() {
        assertEquals(FlashNetworkBand.WIFI_2_4GHZ, FlashNetworkBand.link(FlashNetworkBand.WIFI_5GHZ, FlashNetworkBand.WIFI_2_4GHZ))
        assertEquals(FlashNetworkBand.WIFI_5GHZ, FlashNetworkBand.link(FlashNetworkBand.ETHERNET, FlashNetworkBand.WIFI_5GHZ))
        assertEquals(FlashNetworkBand.WIFI_5GHZ, FlashNetworkBand.link(FlashNetworkBand.UNKNOWN, FlashNetworkBand.WIFI_5GHZ), "a hotspot host defers to the other end")
        assertEquals(FlashNetworkBand.WIFI_2_4GHZ, FlashNetworkBand.link(FlashNetworkBand.WIFI_2_4GHZ, null), "an old client defers too")
        assertEquals(FlashNetworkBand.UNKNOWN, FlashNetworkBand.link(null, FlashNetworkBand.UNKNOWN))
        assertEquals(FlashNetworkBand.WIFI_6GHZ, FlashNetworkBand.fromWifiFrequencyMhz(5_955))
        assertEquals(FlashNetworkBand.WIFI_5GHZ, FlashNetworkBand.fromWifiFrequencyMhz(5_180))
        assertEquals(FlashNetworkBand.WIFI_2_4GHZ, FlashNetworkBand.fromWifiFrequencyMhz(2_437))
        assertEquals(FlashNetworkBand.UNKNOWN, FlashNetworkBand.fromWifiFrequencyMhz(-1))
        assertEquals(FlashNetworkBand.UNKNOWN, FlashNetworkBand.fromWire("zz"))
        assertNull(FlashNetworkBand.fromWire(null))
        for (band in FlashNetworkBand.entries) assertEquals(band, FlashNetworkBand.fromWire(band.wire))
    }
}
