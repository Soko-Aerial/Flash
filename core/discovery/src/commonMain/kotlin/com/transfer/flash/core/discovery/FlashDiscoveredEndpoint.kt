package com.transfer.flash.core.discovery

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.model.FlashTransportType

public data class FlashDiscoveredEndpoint(
    val device: FlashDevice,
    val hostAddress: String,
    val port: Int,
    val serviceName: String,
    /**
     * What kind of device the peer declared itself to be, from the `caps` flags it advertised.
     *
     * Part of the endpoint rather than of [device] because it is a *discovery fact*: it is learned
     * from the advertisement and is gone the moment the peer is. [FlashDevice] is the domain model of
     * a peer we can talk to, and it is also what the trust store persists — a field there would
     * either need persisting or would read as stale after a peer changed platform.
     *
     * Defaulted to [FlashDeviceKind.UNKNOWN] so construction sites that predate the field keep
     * compiling and, more importantly, so a peer that advertises nothing is never *assumed* to be a
     * phone.
     */
    val deviceKind: FlashDeviceKind = FlashDeviceKind.UNKNOWN,
) {
    public val deviceId: FlashDeviceId get() = device.id
    public val friendlyName: String get() = device.friendlyName
    public val transportType: FlashTransportType get() = device.transportType
}
