package com.transfer.flash.core.common.model

/**
 * Represents a discovered or connected peer device in the Flash network.
 *
 * @property id The unique identifier of the device.
 * @property friendlyName The human-readable name of the peer device.
 * @property transportType The active transport medium for this device.
 * @property presence The current presence/connection state of the peer.
 * @property protocolVersion The highest supported Flash protocol version of the peer.
 * @property groupProtocol The group protocol level the peer advertised in its HELLO (`gv`), 1 when it
 *   sent none. A v2 group only admits a device whose level is at least 2 (ADR-044 V1).
 */
public data class FlashDevice(
    val id: FlashDeviceId,
    val friendlyName: String,
    val transportType: FlashTransportType,
    val presence: FlashPeerPresence = FlashPeerPresence.Online,
    val protocolVersion: Int = 1,
    val groupProtocol: Int = 1,
)
