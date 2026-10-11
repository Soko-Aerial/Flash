package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport contract for the outbound WebSocket connection to an enterprise relay hub.
 * Decouples socket management from the high-level [FlashRelayClient] state machine and watchdog.
 */
@FlashInternalApi
public interface RelayTransport {

    /** Current lifecycle state of the transport. */
    public val state: StateFlow<RelaySessionState>

    /** Inbound stream of parsed [RelayFrame] items. */
    public val incomingFrames: Flow<RelayFrame>

    /**
     * Opens the WebSocket connection to [hubUrl] and initiates the initial HELLO handshake.
     *
     * @param hubUrl Full WebSocket URL (e.g. `ws://192.168.1.50:8443/flash-relay` or `wss://...`)
     * @param deviceId Local client device ID
     * @param friendlyName Local client display name
     * @param role Organization role (default `"member"`)
     * @return true if connected and welcomed, false otherwise.
     */
    public suspend fun connect(
        hubUrl: String,
        deviceId: String,
        friendlyName: String,
        role: String = "member",
    ): Boolean

    /**
     * Sends an encoded [frame] to the connected hub.
     */
    public suspend fun sendFrame(frame: RelayFrame): Boolean

    /**
     * Sends raw text directly to the connected hub.
     */
    public suspend fun sendRaw(rawText: String): Boolean

    /**
     * Disconnects the current WebSocket session.
     */
    public suspend fun disconnect(reason: String = "Normal disconnect")
}
