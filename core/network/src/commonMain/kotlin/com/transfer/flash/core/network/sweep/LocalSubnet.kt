package com.transfer.flash.core.network.sweep

/**
 * One IPv4 address this device holds on a LAN-capable interface (Wi-Fi, Ethernet or its own hotspot).
 *
 * The platform source decides what counts as LAN-capable and never lists a cellular interface, so a
 * sweep cannot be started from mobile data. [SubnetSweepPlan] then applies the size and address-space
 * limits, so a source that is too generous still cannot cause a sweep it should not.
 *
 * @property interfaceName the OS interface name, for logs only ("wlan0", "ap0", "Wi-Fi").
 * @property address dotted-quad address of this device on the subnet.
 * @property prefixLength the subnet's prefix length as the interface reports it.
 */
public data class LocalSubnet(
    val interfaceName: String,
    val address: String,
    val prefixLength: Int,
)
