// FlashLog is @FlashInternalApi: library-internal, opted into here as the sibling radio transports do.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.net

import com.transfer.flash.core.common.logging.FlashLog
import java.net.NetworkInterface

/**
 * What [VirtualAdapters.select] decided.
 *
 * @property kept the candidates to use.
 * @property skipped the candidates that were left out because they look virtual.
 * @property fellBack every candidate looked virtual, so none was skipped: a filter must never leave the host with
 *   no network at all (a Hyper-V guest's only adapter is "Microsoft Hyper-V Network Adapter").
 */
public class AdapterSelection<T>(
    public val kept: List<T>,
    public val skipped: List<T>,
    public val fellBack: Boolean,
)

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
 * description as the display name. Two adapters are deliberately **not** listed although their names say
 * "virtual" or "remote": a phone in USB-tether mode ("Remote NDIS based Internet Sharing Device") and the
 * Windows Mobile Hotspot ("Microsoft Wi-Fi Direct Virtual Adapter"). Both are real LANs with a phone on them.
 *
 * A false positive hides a real adapter, so [select] never empties the list, callers keep a way to include
 * everything (`includeVirtualAdapters` in the desktop settings file), and they log which interfaces they skipped.
 */
public object VirtualAdapters {

    // Substrings of the Windows driver description, and of Linux/macOS interface names. Lowercase.
    private val DISPLAY_MARKERS = listOf(
        "virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "host-only", "vpn", "tap-windows",
        "wireguard", "tailscale", "zerotier", "wintun", "npcap", "docker", "wsl", "bluetooth", "teredo",
        "isatap", "6to4", "loopback", "pseudo-interface",
    )

    // Windows Mobile Hotspot. Matched on the description, before DISPLAY_MARKERS, because it contains "virtual".
    private val REAL_MARKERS = listOf("wi-fi direct")

    private val NAME_PREFIXES = listOf(
        "docker", "veth", "br-", "virbr", "vboxnet", "vmnet", "tun", "tap", "utun", "wg", "tailscale", "zt", "lo",
    )

    /** True when the adapter is virtual, a tunnel, or otherwise not a network peers share with this device. */
    public fun isVirtual(name: String, displayName: String?, isPointToPoint: Boolean, isSubInterface: Boolean): Boolean {
        if (isPointToPoint || isSubInterface) return true
        val lowerName = name.lowercase()
        if (NAME_PREFIXES.any { lowerName.startsWith(it) }) return true
        val display = displayName.orEmpty().lowercase()
        // The adapter Windows creates for its own Mobile Hotspot has "Virtual" in its name, and the phones that join
        // the hotspot live on it: it is the one virtual adapter that does hold peers.
        if (REAL_MARKERS.any { it in display }) return false
        return DISPLAY_MARKERS.any { it in display }
    }

    /**
     * Splits [candidates] into the ones to use and the ones to skip.
     *
     * With [includeVirtual] nothing is skipped. Otherwise the virtual ones are skipped, unless that would leave
     * nothing, in which case all are kept and [AdapterSelection.fellBack] says so.
     */
    public fun <T> select(candidates: List<T>, includeVirtual: Boolean, isVirtual: (T) -> Boolean): AdapterSelection<T> {
        if (includeVirtual) return AdapterSelection(candidates, emptyList(), fellBack = false)
        val (virtual, real) = candidates.partition(isVirtual)
        if (real.isEmpty() && virtual.isNotEmpty()) return AdapterSelection(candidates, emptyList(), fellBack = true)
        return AdapterSelection(real, virtual, fellBack = false)
    }

    /**
     * [select] for live interfaces, with one log line naming what was used and what was skipped, so a field report
     * says which adapters a desktop announced on. [tag] is the caller's log tag.
     */
    public fun selectInterfaces(
        candidates: List<NetworkInterface>,
        includeVirtual: Boolean,
        tag: String,
    ): List<NetworkInterface> {
        val selection = select(candidates, includeVirtual) { isVirtual(it) }
        if (selection.skipped.isNotEmpty() || selection.fellBack) {
            fun describe(list: List<NetworkInterface>) =
                list.joinToString { nic -> runCatching { "${nic.name} (${nic.displayName})" }.getOrDefault("?") }
            val note = if (selection.fellBack) {
                "every adapter looks virtual, using all: ${describe(selection.kept)}"
            } else {
                "using ${describe(selection.kept)}; skipped virtual/tunnel ${describe(selection.skipped)}"
            }
            FlashLog.i(tag, "Network adapters: $note")
        }
        return selection.kept
    }

    /** [isVirtual] for a live interface; an interface that disappears mid-check counts as virtual. */
    public fun isVirtual(nic: NetworkInterface): Boolean = runCatching {
        isVirtual(nic.name, nic.displayName, nic.isPointToPoint, nic.isVirtual)
    }.getOrDefault(true)
}
