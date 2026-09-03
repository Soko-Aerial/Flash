package com.transfer.flash.core.common.model

/**
 * The network transport medium used for communication and file transfer between Flash peers.
 */
public enum class FlashTransportType {
    LAN,
    WIFI_DIRECT,
    WEBSOCKET,
    RELAY,
    MESH,
    UNKNOWN;

    public companion object {
        public fun fromString(value: String): FlashTransportType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}
