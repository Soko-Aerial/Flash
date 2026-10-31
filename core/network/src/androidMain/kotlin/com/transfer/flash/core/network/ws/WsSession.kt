@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.FrameAck
import com.transfer.flash.core.network.FrameAckStage
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FlashSession implementation backed by an active RFC 6455 [WsConnection].
 *
 * Supports:
 * - Direct UTF-8 text messages (JSON chat frames / control signaling)
 * - Raw binary messages (file chunk frames)
 * - Keepalive handled by [WsConnection] (periodic pings + read-timeout dead detection)
 * - Immediate disconnect event forwarding
 *
 * ## Delivery guarantees (fix for silent-frame-drop stalls)
 *
 * Inbound frames are delivered through bounded [Channel]s whose senders BLOCK when full
 * (`trySendBlocking`). Because sends happen on the WS read-loop thread, a slow consumer
 * (e.g. disk-bound chunk writes) blocks the read loop, which applies TCP backpressure to
 * the peer — chunks are never silently discarded (the previous `DROP_OLDEST` SharedFlow
 * dropped CHUNK frames that were never ACKed, permanently stalling transfers).
 */
public class WsSession(
    public val connection: WsConnection,
    override val peer: FlashDevice,
    /**
     * True when this session came from OUR outbound dial ([WsFlashNetwork.connectManual]);
     * false when it was accepted as an inbound connection. Used by the deterministic
     * connect-glare tiebreaker (ERROR-023) so both peers converge on the same socket.
     */
    public val isOutbound: Boolean = false,
    private val logTag: String = TAG,
    private val onDisconnected: (WsSession, String) -> Unit = { _, _ -> },
) : FlashSession {

    override val peerDeviceId: FlashDeviceId = peer.id
    override val transportType: FlashTransportType = FlashTransportType.LAN

    private val _connectionState = MutableStateFlow(FlashConnectionState.Connected)
    override val connectionState: StateFlow<FlashConnectionState> = _connectionState.asStateFlow()

    private val _frameAcks = MutableSharedFlow<FrameAck>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frameAcks: SharedFlow<FrameAck> = _frameAcks.asSharedFlow()

    private val textChannel = Channel<String>(capacity = TEXT_BUFFER_FRAMES)
    private val binaryChannel = Channel<ByteArray>(capacity = BINARY_BUFFER_FRAMES)

    /** Single-consumer flows: exactly one collector per session is expected (engine wiring). */
    public val incomingText: Flow<String> = textChannel.consumeAsFlow()
    public val incomingBinary: Flow<ByteArray> = binaryChannel.consumeAsFlow()

    /**
     * Pulls the next inbound binary frame directly (manual receive loop). Used by hosts that
     * need to gate intake (e.g. pause-via-backpressure) between frames; throws
     * ClosedReceiveChannelException once the connection closed and buffers drained.
     */
    public suspend fun awaitBinaryFrame(): ByteArray = binaryChannel.receive()

    /** Pulls the next inbound text frame directly; see [awaitBinaryFrame]. */
    public suspend fun awaitTextFrame(): String = textChannel.receive()

    override suspend fun send(message: ByteArray): FlashResult<Unit> {
        val ok = connection.sendBinary(message)
        return if (ok) {
            if (_frameAcks.subscriptionCount.value > 0) {
                _frameAcks.tryEmit(FrameAck(UUID.randomUUID().toString(), FrameAckStage.SocketWritten, System.currentTimeMillis()))
            }
            FlashResult.Success(Unit)
        } else {
            FlashResult.Failure(FlashError.TransferFailed(peerDeviceId.value, "WebSocket sendBinary failed"))
        }
    }

    override suspend fun sendText(text: String): FlashResult<Unit> {
        val ok = connection.sendText(text)
        return if (ok) {
            if (_frameAcks.subscriptionCount.value > 0) {
                _frameAcks.tryEmit(FrameAck(UUID.randomUUID().toString(), FrameAckStage.SocketWritten, System.currentTimeMillis()))
            }
            FlashResult.Success(Unit)
        } else {
            FlashResult.Failure(FlashError.TransferFailed(peerDeviceId.value, "WebSocket sendText failed"))
        }
    }

    override fun disconnect(reason: String) {
        _connectionState.value = FlashConnectionState.Disconnected
        connection.close(reason)
        closeChannels()
        onDisconnected(this, reason)
    }

    public suspend fun onTextReceived(text: String) {
        if (_connectionState.value == FlashConnectionState.Disconnected) return
        try {
            textChannel.sendKeepingLiveness(text)
        } catch (_: ClosedSendChannelException) {
            // Disconnected
        }
    }

    public suspend fun onBinaryReceived(data: ByteArray) {
        if (_connectionState.value == FlashConnectionState.Disconnected) return
        try {
            binaryChannel.sendKeepingLiveness(data)
        } catch (_: ClosedSendChannelException) {
            // Disconnected
        }
    }

    /**
     * Non-blocking delivery of a frame that arrived BEFORE its session was registered.
     *
     * These two MUST NOT park. The network layer calls them from inside its session-registry
     * `synchronized` block (and while finishing a glare hand-off), where this session's own
     * consumer can need that same lock — waiting for room there is a deadlock, and a suspend
     * hand-off is impossible from inside `synchronized`. A full buffer therefore drops the frame;
     * that is logged rather than silent, because the frames on this path are identifying ones
     * (HELLO and its reply) whose loss costs the whole connection. The window is bounded by
     * MAX_PENDING_PEER_FRAMES and opens only before a consumer starts draining, so a drop here is
     * a real defect report, not routine backpressure.
     */
    public fun tryEnqueueEarlyText(text: String): Boolean {
        if (_connectionState.value == FlashConnectionState.Disconnected) return false
        if (textChannel.trySend(text).isSuccess) return true
        FlashLog.w(logTag, "Early text frame DROPPED: inbound text buffer full before delivery")
        return false
    }

    /** See [tryEnqueueEarlyText] for why this is non-blocking and why a drop is logged. */
    public fun tryEnqueueEarlyBinary(data: ByteArray): Boolean {
        if (_connectionState.value == FlashConnectionState.Disconnected) return false
        if (binaryChannel.trySend(data).isSuccess) return true
        FlashLog.w(logTag, "Early binary frame DROPPED: inbound binary buffer full before delivery")
        return false
    }

    public fun onClosed(reason: String) {
        _connectionState.value = FlashConnectionState.Disconnected
        closeChannels()
        onDisconnected(this, reason)
    }

    /**
     * Hands one inbound frame to a bounded channel without losing the BUG-03 liveness protection.
     *
     * These channels are bounded on purpose: a slow consumer (a disk-bound chunk write) parks this
     * call, which stops draining the socket and applies TCP backpressure to the peer instead of
     * dropping frames. But the frames that would prove the *peer* alive are exactly the ones still
     * unread behind a parked read loop, so with no stamp on [WsConnection.touchLiveness] a consumer
     * 25 s behind looks like a silent peer and the watchdog closes a healthy connection. The read
     * loop used to poll these channels every 25 ms and stamp liveness on each failed attempt
     * (BUG-03); this is the same guarantee in suspend form - while the consumer is behind, refresh
     * the connection's inbound/user-traffic stamps once per [BACKPRESSURE_TOUCH_INTERVAL_MS].
     *
     * Ordering is unchanged: this is still the read loop's single path, one frame at a time.
     * A closed channel still throws [ClosedSendChannelException] and cancellation still propagates.
     */
    private suspend fun <T> Channel<T>.sendKeepingLiveness(value: T) {
        while (true) {
            val sent = withTimeoutOrNull(BACKPRESSURE_TOUCH_INTERVAL_MS) { send(value) }
            if (sent != null) return
            connection.touchLiveness()
        }
    }

    private fun closeChannels() {
        textChannel.close()
        binaryChannel.close()
    }

    public companion object {
        private const val TAG = "WS"

        /**
         * Binary buffer sized so a burst of chunk frames survives a brief consumer hiccup
         * while still bounding memory (~128 x 64 KB = 8 MB at default chunk size).
         */
        public const val BINARY_BUFFER_FRAMES: Int = 128
        public const val TEXT_BUFFER_FRAMES: Int = 512

        /**
         * How often [sendKeepingLiveness] refreshes the connection's liveness stamps while a
         * consumer is behind. Comfortably below [WsConnection.DEFAULT_LIVENESS_TIMEOUT_MS] (25 s)
         * so backpressure can never be mistaken for peer silence, and coarse enough that the
         * timeout machinery costs nothing while frames are flowing normally (the first stamp only
         * happens a full interval into a stall).
         */
        private const val BACKPRESSURE_TOUCH_INTERVAL_MS: Long = 1_000L
    }
}
