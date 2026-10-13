package com.transfer.flash.core.enterprise.relay

/**
 * State machine states for the outbound enterprise relay connection.
 */
public enum class RelaySessionState {
    /** Disconnected from the relay hub; idle or awaiting connection attempt. */
    DISCONNECTED,

    /** Opening WebSocket connection to the hub URL. */
    CONNECTING,

    /** Performing cryptographic identity verification / welcome handshake. */
    AUTHENTICATING,

    /** Authenticated and ready for bidirectional envelope routing and metadata. */
    CONNECTED,

    /** Temporarily disconnected or network suspended; scheduled for automatic backoff reconnect. */
    SUSPENDED,

    /** Terminal state: hub rejected device certificate as revoked or invalid. Reconnect blocked. */
    REVOKED;

    public val isOnline: Boolean get() = this == CONNECTED
    public val canSend: Boolean get() = this == CONNECTED
}
