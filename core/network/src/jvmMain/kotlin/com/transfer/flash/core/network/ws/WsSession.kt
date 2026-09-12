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
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * DESKTOP DUPLICATE (Phase 15-3) of the `androidMain` original — byte-identical apart from
 * this note. D1 = Option B forbids a shared JVM tier, so JDK-bound plumbing is duplicated
 * per target (CONVENTIONS.md R5 amendment). Do NOT edit one copy without the other.

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
            _frameAcks.tryEmit(FrameAck(UUID.randomUUID().toString(), FrameAckStage.SocketWritten, System.currentTimeMillis()))
            FlashResult.Success(Unit)
        } else {
            FlashResult.Failure(FlashError.TransferFailed(peerDeviceId.value, "WebSocket sendBinary failed"))
        }
    }

    override suspend fun sendText(text: String): FlashResult<Unit> {
        val ok = connection.sendText(text)
        return if (ok) {
            _frameAcks.tryEmit(FrameAck(UUID.randomUUID().toString(), FrameAckStage.SocketWritten, System.currentTimeMillis()))
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

    public fun onTextReceived(text: String) {
        val result = textChannel.trySendBlocking(text)
        if (result.isFailure && !result.isClosed) {
            FlashLog.w(logTag, "WS text frame dropped (buffer full) peer=${peer.friendlyName}")
        }
    }

    public fun onBinaryReceived(data: ByteArray) {
        val result = binaryChannel.trySendBlocking(data)
        if (result.isFailure && !result.isClosed) {
            FlashLog.w(logTag, "WS binary frame dropped (buffer full) peer=${peer.friendlyName}")
        }
    }

    public fun onClosed(reason: String) {
        _connectionState.value = FlashConnectionState.Disconnected
        closeChannels()
        onDisconnected(this, reason)
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
    }
}
