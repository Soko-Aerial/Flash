package com.transfer.flash.core.network

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents an active, bidirectional, peer-to-peer communication session.
 */
public interface FlashSession {
    public val peer: FlashDevice
    public val peerDeviceId: FlashDeviceId get() = peer.id
    public val connectionState: StateFlow<FlashConnectionState>
    public val transportType: FlashTransportType

    /**
     * Delivery-ACK hooks (plan C4.8): two distinct transit stages feeding the
     * UI-015 delivery glyphs — socket-written (queued into the OS) and
     * peer-ACK (receiver confirmed insert). Hot flow, live traffic only.
     * Additive: default never-emitting implementation keeps existing
     * implementations compiling (R4).
     */
    public val frameAcks: kotlinx.coroutines.flow.Flow<FrameAck>
        get() = kotlinx.coroutines.flow.emptyFlow()

    public suspend fun send(message: ByteArray): FlashResult<Unit>
    public suspend fun sendText(text: String): FlashResult<Unit> = send(text.toByteArray(Charsets.UTF_8))
    public fun disconnect(reason: String = "Normal disconnect")
}

/** Stage of one frame's transit through the session (C4.8). */
public enum class FrameAckStage { SocketWritten, PeerAcknowledged }

public data class FrameAck(
    val frameId: String,
    val stage: FrameAckStage,
    val atMs: Long,
)
