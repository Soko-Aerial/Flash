@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enterprise Outbound Relay Client.
 *
 * Coordinates persistent bidirectional WebSocket communication to an enterprise hub:
 * - Automatic connection establishment and hello/welcome handshakes
 * - Exponential backoff reconnection on transport interruption
 * - Application-level heartbeat watchdog (ping/pong every 15s)
 * - Encrypted [FlashEnvelope] send and receive routing
 * - Delivery acknowledgment demuxing (`FLASH_ACK`)
 * - Ephemeral typing/presence debouncing and wire padding (`P-REF-1`, `P-REF-4`)
 */
@FlashInternalApi
public class FlashRelayClient(
    public val deviceId: String,
    public val friendlyName: String,
    public val role: String = "member",
    public var hubUrl: String,
    public val transport: RelayTransport = KtorRelayTransport(),
    public val timeSource: FlashTimeSource = SystemTimeSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
) {

    public val state: StateFlow<RelaySessionState> = transport.state

    public val incomingEnvelopes: Flow<FlashEnvelope> = transport.incomingFrames
        .filterIsInstance<RelayFrame.Data>()
        .map { it.toEnvelope() }

    public val deliveryAcks: Flow<RelayFrame.Ack> = transport.incomingFrames
        .filterIsInstance<RelayFrame.Ack>()

    public val metaEvents: Flow<RelayFrame.Meta> = transport.incomingFrames
        .filterIsInstance<RelayFrame.Meta>()

    private var running = false
    private var clientJob = SupervisorJob()
    private var clientScope = CoroutineScope(clientJob + ioDispatcher)
    private var lastPongReceivedMs: Long = 0L
    private val sendLock = Mutex()

    /**
     * Starts the client and begins connecting to the hub.
     */
    public fun start() {
        if (running) return
        running = true
        if (clientJob.isCancelled) {
            clientJob = SupervisorJob()
            clientScope = CoroutineScope(clientJob + ioDispatcher)
        }
        lastPongReceivedMs = timeSource.nowMs()

        // Launch inbound frame listener to observe pongs and health
        clientScope.launch {
            transport.incomingFrames.collect { frame ->
                when (frame) {
                    is RelayFrame.Pong -> {
                        lastPongReceivedMs = timeSource.nowMs()
                    }
                    is RelayFrame.Welcome -> {
                        lastPongReceivedMs = timeSource.nowMs()
                    }
                    else -> Unit
                }
            }
        }

        startWatchdog()
        startConnectionLoop()
    }

    /**
     * Stops the client and closes any active connection.
     */
    public fun stop() {
        running = false
        clientJob.cancelChildren()

        scope.launch {
            try {
                transport.disconnect("Client stopped")
            } catch (_: Throwable) {
                // Ignore disconnect error
            }
        }
    }

    /**
     * Sends an encrypted [envelope] to [recipientDeviceId] via the relay hub.
     *
     * @return true if successfully delivered to the hub WebSocket, false otherwise.
     */
    public suspend fun sendEnvelope(recipientDeviceId: String, envelope: FlashEnvelope): Boolean {
        if (!state.value.canSend) return false
        val frame = RelayFrame.Data.fromEnvelope(recipientDeviceId, envelope)
        return sendLock.withLock {
            transport.sendFrame(frame)
        }
    }

    /**
     * Sends a delivery acknowledgment frame (`FLASH_ACK`) to [recipientDeviceId].
     */
    public suspend fun sendAck(id: String, recipientDeviceId: String, status: String): Boolean {
        if (!state.value.canSend) return false
        val ack = RelayFrame.Ack(
            id = id,
            recipientId = recipientDeviceId,
            status = status,
            timestamp = timeSource.nowMs(),
        )
        return sendLock.withLock {
            transport.sendFrame(ack)
        }
    }

    /**
     * Sends an ephemeral typing indicator with `P-REF-1` wire padding.
     */
    public suspend fun sendTyping(conversationId: String): Boolean {
        if (!state.value.canSend) return false
        val meta = RelayFrame.Meta(
            action = RelayFrame.Meta.ACTION_TYPING,
            conversationId = conversationId,
            senderId = deviceId,
            timestamp = timeSource.nowMs(),
        )
        return sendLock.withLock {
            transport.sendFrame(meta)
        }
    }

    /**
     * Sends an ephemeral presence status with `P-REF-1` wire padding.
     */
    public suspend fun sendPresence(action: String): Boolean {
        if (!state.value.canSend) return false
        val meta = RelayFrame.Meta(
            action = action,
            conversationId = "",
            senderId = deviceId,
            timestamp = timeSource.nowMs(),
        )
        return sendLock.withLock {
            transport.sendFrame(meta)
        }
    }

    private fun startConnectionLoop() {
        clientScope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive && running) {
                if (state.value == RelaySessionState.REVOKED) {
                    // Device cert was revoked on the hub — stop retrying
                    break
                }

                if (state.value != RelaySessionState.CONNECTED && state.value != RelaySessionState.CONNECTING && state.value != RelaySessionState.AUTHENTICATING) {
                    val currentHub = hubUrl
                    if (currentHub.isNotBlank()) {
                        val connected = transport.connect(
                            hubUrl = currentHub,
                            deviceId = deviceId,
                            friendlyName = friendlyName,
                            role = role,
                        )
                        if (connected) {
                            backoffMs = INITIAL_BACKOFF_MS
                            lastPongReceivedMs = timeSource.nowMs()
                        } else {
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        }
                    } else {
                        delay(5000L)
                    }
                } else {
                    delay(1000L)
                }
            }
        }
    }

    private fun startWatchdog() {
        clientScope.launch {
            while (isActive && running) {
                delay(PING_INTERVAL_MS)
                if (state.value == RelaySessionState.CONNECTED) {
                    val now = timeSource.nowMs()
                    // Check if last pong timed out
                    if (now - lastPongReceivedMs > PONG_TIMEOUT_MS) {
                        // Hub unresponsive; force disconnect to trigger reconnection loop
                        transport.disconnect("Keepalive timeout")
                        continue
                    }

                    // Send periodic ping
                    val ping = RelayFrame.Ping(timestamp = now)
                    transport.sendFrame(ping)
                }
            }
        }
    }

    public companion object {
        public const val PING_INTERVAL_MS: Long = 15_000L
        public const val PONG_TIMEOUT_MS: Long = 30_000L
        public const val INITIAL_BACKOFF_MS: Long = 1_000L
        public const val MAX_BACKOFF_MS: Long = 30_000L
    }
}
