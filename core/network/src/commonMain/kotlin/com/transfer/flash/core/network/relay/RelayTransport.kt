package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Transport contract for the outbound WebSocket connection to an enterprise relay hub.
 * Decouples socket management from the high-level [FlashRelayClient] state machine and watchdog.
 *
 * The frames are the shared `:core:hub-protocol` model ([RelayFrame]); the transport owns the authenticated handshake
 * of "Hub relay wire v2" (`docs/protocol.md`), so [incomingFrames] only ever carries frames received AFTER the hub's
 * WELCOME.
 */
@FlashInternalApi
public interface RelayTransport {

    /** Current lifecycle state of the transport. */
    public val state: StateFlow<RelaySessionState>

    /** Inbound stream of parsed frames, from the hub's WELCOME onwards (the WELCOME itself is the first one). */
    public val incomingFrames: Flow<RelayFrame>

    /**
     * Mailbox deliveries (`FLASH_DELIVER`) as a RELIABLE stream (CLI-08): frames that arrive before anyone collects are
     * buffered, not lost, and a slow collector never stalls the socket reader (so keep-alives keep flowing). Meant for
     * ONE collector, the [FlashRelayClient]. The default derives them from [incomingFrames], which is enough for fakes.
     */
    public val deliveries: Flow<RelayFrame.Deliver> get() = incomingFrames.filterIsInstance()

    /** The most recent failure (null while healthy or before the first attempt). Cleared at the start of each attempt. */
    public val failure: StateFlow<RelayFailure?> get() = NO_FAILURE

    /**
     * Opens the WebSocket connection to [hubUrl] and runs the authenticated handshake (CHALLENGE, HELLO, WELCOME)
     * with [identity]. No identity travels in the URL.
     *
     * @param hubUrl `ws(s)://host:port[/flash-relay]` or an `http(s)://` equivalent.
     * @return true if the hub accepted the handshake, false otherwise (including a handshake that ran out of time).
     */
    public suspend fun connect(hubUrl: String, identity: RelayIdentity): Boolean

    /** Sends an encoded [frame] to the connected hub. */
    public suspend fun sendFrame(frame: RelayFrame): Boolean

    /** Sends raw text directly to the connected hub. */
    public suspend fun sendRaw(rawText: String): Boolean

    /** Disconnects the current WebSocket session. */
    public suspend fun disconnect(reason: String = "Normal disconnect")

    public companion object {
        private val NO_FAILURE: StateFlow<RelayFailure?> = MutableStateFlow(null)
    }
}
