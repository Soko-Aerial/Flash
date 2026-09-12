package com.transfer.flash.core.messaging.protocol

/**
 * Push-to-talk voice-session control frames (ADR-032, Phase 0).
 *
 * One floor holder transmits; every other member only receives (strict half-duplex), so
 * receivers never capture and the transmitter never plays. Audio itself travels as binary
 * frames (Phase 1); these text frames carry session lifecycle + heartbeat only.
 *
 * Standalone on purpose like [PttPingFrame]: NOT a [MessageWireFrame] subtype, so both
 * hosts' exhaustive `when` expressions over [MessageWireFrame] keep compiling untouched.
 * Every frame carries [sessionId] + [from] so receivers can bind it to the authenticated
 * transport peer and ignore stale/retired sessions.
 */
public sealed interface PttSessionFrame {
    public val sessionId: String
    public val from: String
    public val sentAt: Long

    /**
     * Floor claim: broadcaster → all paired+online peers.
     *
     * @property sampleRateHz capture rate, one of [PttSessionCodec.ALLOWED_RATES].
     * @property packetMs audio payload per binary frame, one of [PttSessionCodec.ALLOWED_PACKET_MS].
     */
    public data class Start(
        override val sessionId: String,
        override val from: String,
        val senderName: String,
        override val sentAt: Long,
        val sampleRateHz: Int,
        val packetMs: Int,
    ) : PttSessionFrame

    /** Floor release: only the holder's own stop ends a session (toggle-press, cap, deny). */
    public data class Stop(
        override val sessionId: String,
        override val from: String,
        override val sentAt: Long,
    ) : PttSessionFrame

    /** Receiver-side cancel hint: informational only, never tears the session down. */
    public data class Leave(
        override val sessionId: String,
        override val from: String,
        override val sentAt: Long,
    ) : PttSessionFrame

    /**
     * 1 Hz liveness from the holder while TALKING. [rttMs] is the broadcaster-measured
     * round-trip echoed back for receiver badges; null until the first ack arrives.
     */
    public data class Heartbeat(
        override val sessionId: String,
        override val from: String,
        val seq: Long,
        override val sentAt: Long,
        val rttMs: Long?,
    ) : PttSessionFrame

    /** Receiver echo answering [Heartbeat.seq]; the holder timestamps these for RTT. */
    public data class HeartbeatAck(
        override val sessionId: String,
        override val from: String,
        val seq: Long,
        override val sentAt: Long,
    ) : PttSessionFrame
}
