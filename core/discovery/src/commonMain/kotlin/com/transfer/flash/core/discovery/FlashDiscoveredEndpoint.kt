package com.transfer.flash.core.discovery

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType

public data class FlashDiscoveredEndpoint(
    val device: FlashDevice,
    val hostAddress: String,
    val port: Int,
    val serviceName: String,
) {
    public val deviceId: FlashDeviceId get() = device.id
    public val friendlyName: String get() = device.friendlyName
    public val transportType: FlashTransportType get() = device.transportType
}
