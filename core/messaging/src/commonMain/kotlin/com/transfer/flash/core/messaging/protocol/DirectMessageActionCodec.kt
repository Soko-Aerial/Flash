@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashTextFraming

/** Codec for direct-message control actions that are separate from message delivery frames. */
public object DirectMessageActionCodec {
    public const val PREFIX: String = "FLASH_DACT"
    public const val DELETE_ACTION: String = "delete"

    public fun encode(frame: MessageWireFrame.DeleteForEveryone): String =
        FlashTextFraming.encodeFields(
            PREFIX,
            listOf(
                "action" to DELETE_ACTION,
                "messageId" to frame.messageId,
                "conversationId" to frame.conversationId,
                "from" to frame.from,
            ),
        )

    /** Returns null for non-direct-action text, malformed fields, or unknown future actions. */
    public fun decode(text: String): MessageWireFrame.DeleteForEveryone? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        if (fields["action"] != DELETE_ACTION) return null
        return MessageWireFrame.DeleteForEveryone(
            messageId = fields.required("messageId") ?: return null,
            conversationId = fields.required("conversationId") ?: return null,
            from = fields.required("from") ?: return null,
        )
    }

    private fun Map<String, String>.required(key: String): String? = this[key]?.takeIf { it.isNotBlank() }
}
