package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * The direct-chat frame family: the frames every engine's `MessageTransportSink` encrypts with the
 * peer's session key (`FLASH_SEC`) whenever one exists.
 *
 * Because the sender always encrypts this family for a keyed peer, a plaintext frame from this family
 * arriving from a keyed peer is a downgrade, not a legitimate message, and hosts drop it (audit S1b).
 * Calls, groups, transfer control and PTT are NOT in this family: they have never carried app-layer
 * encryption and rely on the (now mutually authenticated) TLS session.
 */
@FlashInternalApi
public object DirectChatFamily {

    private val PREFIXES: List<String> = listOf(
        ChatTextFrameCodec.MSG_PREFIX,
        ChatTextFrameCodec.RECEIPT_PREFIX,
        ChatTextFrameCodec.READ_PREFIX,
        ChatTextFrameCodec.REACT_PREFIX,
        ChatTextFrameCodec.TYPING_PREFIX,
        DirectMessageActionCodec.PREFIX,
    )

    /**
     * True when [text] is a plaintext frame of the direct-chat family.
     *
     * Tokenised exactly like `FlashTextFraming.parseFields` (trim, then the first space-separated
     * token), so no whitespace variant can decode as chat downstream yet slip past this check.
     */
    public fun matches(text: String): Boolean =
        text.trim().substringBefore(' ') in PREFIXES
}
