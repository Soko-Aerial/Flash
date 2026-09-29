package com.transfer.flash.core.network.sweep

import com.transfer.flash.core.discovery.net.VirtualAdapters
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The desktop's source of [LocalSubnet]s (DR3): every IPv4 address on an interface that is up, not loopback,
 * and not a virtual or tunnel adapter ([VirtualAdapters]).
 *
 * Virtual adapters are skipped so a Hyper-V or Docker switch is not swept as if peers lived on it. There is no
 * cellular interface to exclude on a desktop; [SubnetSweepPlan] still refuses anything outside RFC 1918.
 */
public object JvmLocalSubnets {

    /** @param includeVirtual keep virtual and tunnel adapters (DR5's setting), for a network that really lives on one. */
    public fun lanSubnets(includeVirtual: Boolean = false): List<LocalSubnet> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter { nic ->
                runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false) &&
                    (includeVirtual || !VirtualAdapters.isVirtual(nic))
            }
            .flatMap { nic ->
                nic.interfaceAddresses.asSequence().mapNotNull { entry ->
                    val ip = entry.address as? Inet4Address ?: return@mapNotNull null
                    if (ip.isLinkLocalAddress || ip.isAnyLocalAddress) return@mapNotNull null
                    val host = ip.hostAddress ?: return@mapNotNull null
                    LocalSubnet(nic.name, host, entry.networkPrefixLength.toInt())
                }
            }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())
}
