package com.transfer.flash.core.transfer.wslegacy

/**
 * LEGACY relocation (C5.1): local stand-in for the `:app` `DiscoveredDevice`/`TransportType`
 * pair, kept byte-compatible with its shape so the WsTransferManager move stays mechanical.
 *
 * TODO(unification): merge with `FlashDiscoveredEndpoint` (`:core:discovery`) or
 * `WsDiscoveredDevice` (`com.transfer.flash.core.transfer.model`) once the wslegacy engine
 * is retired — see docs/core-upgrade-plan.md C5.2/C5.3.
 */
internal enum class LegacyTransportType {
    LAN,
    WIFI_DIRECT,
}

internal data class LegacyDiscoveredDevice(
    val deviceId: String,
    val friendlyName: String,
    val hostAddress: String,
    val port: Int,
    val serviceName: String,
    val transportType: LegacyTransportType = LegacyTransportType.LAN,
    val protocolVersion: Int = 1,
)
