package com.transfer.flash.core.discovery.net

import java.net.NetworkInterface

/**
 * Tells a desktop's virtual and tunnel adapters from the ones peers can actually be on.
 *
 * A laptop routinely carries a Hyper-V or WSL switch, VirtualBox/VMware host-only networks, Docker bridges
 * and a VPN, each with a private address. None of them holds a Flash peer, but a service advertised on one
 * hands peers an address they cannot reach, and a sweep of one wastes probes on a VM network (DR3, DR5
 * item 1, `docs/network/DISCOVERY-RESILIENCE-PLAN.md`).
 *
 * The rule is a name match, because Java offers nothing better: `NetworkInterface.isVirtual` is only true for
 * sub-interfaces like `eth0:1`, and Windows reports every adapter as `ethN` or `wlanN` with the driver's
 * description as the display name. A phone in USB-tether mode ("Remote NDIS based Internet Sharing Device") is
 * deliberately **not** listed: it is a real LAN with a phone on it.
 *
 * A false positive hides a real adapter, so callers keep a way to include everything (DR5's setting) and log
 * which interfaces they skipped.
 */
public object VirtualAdapters {

    // Substrings of the Windows driver description, and of Linux/macOS interface names. Lowercase.
    private val DISPLAY_MARKERS = listOf(
        "virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "host-only", "vpn", "tap-windows",
        "wireguard", "tailscale", "zerotier", "wintun", "npcap", "docker", "wsl", "bluetooth", "teredo",
        "isatap", "6to4", "loopback", "pseudo-interface",
    )

    private val NAME_PREFIXES = listOf(
        "docker", "veth", "br-", "virbr", "vboxnet", "vmnet", "tun", "tap", "utun", "wg", "tailscale", "zt", "lo",
    )

    /** True when the adapter is virtual, a tunnel, or otherwise not a network peers share with this device. */
    public fun isVirtual(name: String, displayName: String?, isPointToPoint: Boolean, isSubInterface: Boolean): Boolean {
        if (isPointToPoint || isSubInterface) return true
        val lowerName = name.lowercase()
        if (NAME_PREFIXES.any { lowerName.startsWith(it) }) return true
        val display = displayName.orEmpty().lowercase()
        return DISPLAY_MARKERS.any { it in display }
    }

    /** [isVirtual] for a live interface; an interface that disappears mid-check counts as virtual. */
    public fun isVirtual(nic: NetworkInterface): Boolean = runCatching {
        isVirtual(nic.name, nic.displayName, nic.isPointToPoint, nic.isVirtual)
    }.getOrDefault(true)
}
