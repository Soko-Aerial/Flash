@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashTextFraming

/** Codec for [PttSessionFrame] voice-session control frames (`FLASH_PTSS action=…`). */
public object PttSessionCodec {
    public const val PREFIX: String = "FLASH_PTSS"
    public const val START_ACTION: String = "start"
    public const val STOP_ACTION: String = "stop"
    public const val LEAVE_ACTION: String = "leave"
    public const val HEARTBEAT_ACTION: String = "hb"
    public const val HEARTBEAT_ACK_ACTION: String = "hb-ack"

    /** Capture rates a receiver is allowed to accept (anything else = fail-closed null). */
    public val ALLOWED_RATES: Set<Int> = setOf(8000, 16000)

    /** Audio payload durations a receiver is allowed to accept. */
    public val ALLOWED_PACKET_MS: Set<Int> = setOf(20, 40, 60)

    public fun encode(frame: PttSessionFrame): String {
        val fields = when (frame) {
            is PttSessionFrame.Start -> listOf(
                "action" to START_ACTION,
                "sessionId" to frame.sessionId,
                "from" to frame.from,
                "name" to frame.senderName,
                "sentAt" to frame.sentAt.toString(),
                "rate" to frame.sampleRateHz.toString(),
                "pms" to frame.packetMs.toString(),
            )
            is PttSessionFrame.Stop -> listOf(
                "action" to STOP_ACTION,
                "sessionId" to frame.sessionId,
                "from" to frame.from,
                "sentAt" to frame.sentAt.toString(),
            )
            is PttSessionFrame.Leave -> listOf(
                "action" to LEAVE_ACTION,
                "sessionId" to frame.sessionId,
                "from" to frame.from,
                "sentAt" to frame.sentAt.toString(),
            )
            is PttSessionFrame.Heartbeat -> listOf(
                "action" to HEARTBEAT_ACTION,
                "sessionId" to frame.sessionId,
                "from" to frame.from,
                "seq" to frame.seq.toString(),
                "sentAt" to frame.sentAt.toString(),
            ) + (frame.rttMs?.let { listOf("rtt" to it.toString()) } ?: emptyList())
            is PttSessionFrame.HeartbeatAck -> listOf(
                "action" to HEARTBEAT_ACK_ACTION,
                "sessionId" to frame.sessionId,
                "from" to frame.from,
                "seq" to frame.seq.toString(),
                "sentAt" to frame.sentAt.toString(),
            )
        }
        return FlashTextFraming.encodeFields(PREFIX, fields)
    }

    /** Returns null for non-session text, malformed fields, out-of-range audio params, or unknown actions. */
    public fun decode(text: String): PttSessionFrame? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        val sessionId = fields.required("sessionId") ?: return null
        val from = fields.required("from") ?: return null
        val sentAt = fields.long("sentAt")?.takeIf { it > 0 } ?: return null
        return when (fields["action"]) {
            START_ACTION -> {
                val rate = fields.int("rate")?.takeIf { it in ALLOWED_RATES } ?: return null
                val pms = fields.int("pms")?.takeIf { it in ALLOWED_PACKET_MS } ?: return null
                PttSessionFrame.Start(
                    sessionId = sessionId,
                    from = from,
                    senderName = fields["name"] ?: "Peer",
                    sentAt = sentAt,
                    sampleRateHz = rate,
                    packetMs = pms,
                )
            }
            STOP_ACTION -> PttSessionFrame.Stop(sessionId, from, sentAt)
            LEAVE_ACTION -> PttSessionFrame.Leave(sessionId, from, sentAt)
            HEARTBEAT_ACTION -> PttSessionFrame.Heartbeat(
                sessionId = sessionId,
                from = from,
                seq = fields.long("seq")?.takeIf { it >= 0 } ?: return null,
                sentAt = sentAt,
                rttMs = fields.long("rtt")?.takeIf { it >= 0 },
            )
            HEARTBEAT_ACK_ACTION -> PttSessionFrame.HeartbeatAck(
                sessionId = sessionId,
                from = from,
                seq = fields.long("seq")?.takeIf { it >= 0 } ?: return null,
                sentAt = sentAt,
            )
            else -> null
        }
    }

    private fun Map<String, String>.required(key: String): String? = this[key]?.takeIf { it.isNotBlank() }
    private fun Map<String, String>.long(key: String): Long? = this[key]?.toLongOrNull()
    private fun Map<String, String>.int(key: String): Int? = this[key]?.toIntOrNull()
}
