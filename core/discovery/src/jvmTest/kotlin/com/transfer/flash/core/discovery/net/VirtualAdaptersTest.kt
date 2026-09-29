package com.transfer.flash.core.discovery.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualAdaptersTest {

    private fun virtual(name: String, display: String, p2p: Boolean = false, sub: Boolean = false) =
        VirtualAdapters.isVirtual(name, display, p2p, sub)

    @Test
    fun `windows virtual switches, VM networks, VPNs and WSL are virtual`() {
        assertTrue(virtual("eth3", "Hyper-V Virtual Ethernet Adapter"))
        assertTrue(virtual("eth4", "vEthernet (Default Switch)"))
        assertTrue(virtual("eth5", "VMware Virtual Ethernet Adapter for VMnet8"))
        assertTrue(virtual("eth6", "VirtualBox Host-Only Ethernet Adapter"))
        assertTrue(virtual("eth7", "TAP-Windows Adapter V9"))
        assertTrue(virtual("eth8", "Wintun Userspace Tunnel"))
        assertTrue(virtual("wlan5", "Microsoft Wi-Fi Direct Virtual Adapter"))
        assertTrue(virtual("eth9", "Tailscale Tunnel"))
        assertTrue(virtual("eth10", "Cisco AnyConnect Secure Mobility Client Virtual Miniport Adapter"))
        assertTrue(virtual("eth11", "Bluetooth Device (Personal Area Network)"))
    }

    @Test
    fun `linux and macos containers, bridges and tunnels are virtual`() {
        for (name in listOf("docker0", "veth1a2b", "br-9f3c", "virbr0", "vboxnet0", "vmnet8", "tun0", "tap0", "utun3", "wg0", "lo")) {
            assertTrue(virtual(name, name), name)
        }
    }

    @Test
    fun `point-to-point links and sub-interfaces are virtual`() {
        assertTrue(virtual("eth0", "Intel(R) Ethernet", p2p = true))
        assertTrue(virtual("eth0:1", "Intel(R) Ethernet", sub = true))
    }

    @Test
    fun `real wifi and ethernet adapters, and a phone in usb tether mode, are not`() {
        assertFalse(virtual("wlan0", "Intel(R) Wi-Fi 6 AX201 160MHz"))
        assertFalse(virtual("wlan1", "Realtek RTL8852BE WiFi 6 802.11ax PCIe Adapter"))
        assertFalse(virtual("eth0", "Realtek PCIe GbE Family Controller"))
        assertFalse(virtual("eth1", "Intel(R) Ethernet Connection (7) I219-V"))
        assertFalse(virtual("eth2", "Remote NDIS based Internet Sharing Device"))
        assertFalse(virtual("en0", "en0"))
        assertFalse(virtual("wlp3s0", "wlp3s0"))
        assertFalse(virtual("enp5s0", "enp5s0"))
    }
}
