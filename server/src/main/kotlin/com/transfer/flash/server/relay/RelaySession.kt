package com.transfer.flash.server.relay

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents an active, authenticated WebSocket session on the Flash Relay hub.
 *
 * Encapsulates the device identity, transport session, outbound framing channel,
 * and ping/pong heartbeat tracking.
 *
 * @property deviceId Cryptographic device identifier of the connected peer.
 * @property friendlyName User-visible display name of the device.
 * @property role Role inside the organisation realm (e.g. "member", "admin", "device").
 * @property remoteAddress Host address and port of the remote client.
 * @property sessionStartTime Epoch milliseconds when the session was established.
 * @property websocketSession Active Ktor WebSocket session.
 * @property sendChannel Buffered channel for outgoing frames to prevent backpressure blocking.
 * @property lastPingTime Timestamp in epoch ms of the last ping sent or received.
 * @property lastPongTime Timestamp in epoch ms of the last pong received from the client.
 */
public data class RelaySession(
    val deviceId: String,
    val friendlyName: String,
    val role: String = "member",
    val remoteAddress: String = "unknown",
    val sessionStartTime: Long = System.currentTimeMillis(),
    val websocketSession: WebSocketSession,
    val sendChannel: Channel<Frame> = Channel(Channel.BUFFERED),
    val lastPingTime: AtomicLong = AtomicLong(System.currentTimeMillis()),
    val lastPongTime: AtomicLong = AtomicLong(System.currentTimeMillis()),
) {

    /**
     * Records a ping timestamp.
     */
    public fun recordPing(timestamp: Long = System.currentTimeMillis()) {
        lastPingTime.set(timestamp)
    }

    /**
     * Records a pong response timestamp.
     */
    public fun recordPong(timestamp: Long = System.currentTimeMillis()) {
        lastPongTime.set(timestamp)
    }

    /**
     * Evaluates whether the connection is considered healthy given the [timeoutMs] window.
     */
    public fun isAlive(timeoutMs: Long): Boolean {
        val elapsed = System.currentTimeMillis() - lastPongTime.get()
        return elapsed <= timeoutMs
    }

    /**
     * Queues or transmits a WebSocket frame to this connected device.
     */
    public suspend fun send(frame: Frame) {
        websocketSession.send(frame)
    }

    /**
     * Convenience method to send a text frame.
     */
    public suspend fun sendText(text: String) {
        websocketSession.send(Frame.Text(text))
    }

    /**
     * Gracefully closes the session, outbound channel, and underlying WebSocket.
     */
    public suspend fun close(reason: String = "Session terminated") {
        sendChannel.close()
        try {
            websocketSession.close(CloseReason(CloseReason.Codes.NORMAL, reason))
        } catch (_: Throwable) {
            // Already closed or socket disconnected
        }
    }
}
