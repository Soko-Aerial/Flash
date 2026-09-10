@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashTextFraming

/** Codec for [PttPingFrame] push-to-talk pings (`FLASH_PTT action=ping …`). */
public object PttFrameCodec {
    public const val PREFIX: String = "FLASH_PTT"
    public const val PING_ACTION: String = "ping"

    public fun encode(frame: PttPingFrame): String =
        FlashTextFraming.encodeFields(
            PREFIX,
            listOf(
                "action" to PING_ACTION,
                "eventId" to frame.eventId,
                "from" to frame.from,
                "senderName" to frame.senderName,
                "sentAt" to frame.sentAt.toString(),
            ),
        )

    /** Returns null for non-PTT text, malformed fields, or unknown future actions. */
    public fun decode(text: String): PttPingFrame? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        if (fields["action"] != PING_ACTION) return null
        return PttPingFrame(
            eventId = fields.required("eventId") ?: return null,
            from = fields.required("from") ?: return null,
            senderName = fields["senderName"] ?: "Peer",
            sentAt = fields["sentAt"]?.toLongOrNull() ?: return null,
        )
    }

    private fun Map<String, String>.required(key: String): String? = this[key]?.takeIf { it.isNotBlank() }
}
