@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashTextFraming

/**
 * Text codec for the direct-chat frame family: `FLASH_MSG` / `FLASH_RCPT` / `FLASH_READ` /
 * `FLASH_REACT` / `FLASH_TYPING` (Phase 2, slice 3).
 *
 * ## Why this exists
 *
 * Both Android chat hosts (`DiscoveryEngineHolder` in `:app`, `Flash.kt` in `:core:engine`)
 * carried this logic inline and identically: a `when` over [MessageWireFrame] building field
 * lists for send, and five `parseFields` blocks mapping fields back for receive. Two copies
 * of wire logic is how the app/desktop pairing codecs drifted apart before — and the
 * desktop engine (slice 4) needs the same mapping without copying it a third time.
 * `DeleteForEveryone` is deliberately NOT here: it already has [DirectMessageActionCodec].
 *
 * ## Decode contract — read carefully
 *
 * [decode] returns `null` when [text] is none of the five families (the caller keeps
 * walking its prefix chain). A recognized prefix with a missing key field returns
 * [DecodeResult.RecognizedButInvalid`, and the caller must DROP the frame — this preserves
 * both holders' `?: return` behavior. Falling through instead would misroute: a key-less
 * `FLASH_MSG` line would slide past the receipt/read/react/typing checks into whatever
 * family comes next, rather than being discarded as both hosts do today.
 *
 * ## Time and routing stay with the caller
 *
 * Common code cannot read a clock, so timestamp defaults come in as [nowMs]. The direct
 * typing fallback (`conversationId` defaults to the transport peer) comes in as the
 * required [transportPeerId] — it is routing knowledge the caller always has, and the
 * codec only applies it, exactly where both holders did.
 */
public object ChatTextFrameCodec {

    public const val MSG_PREFIX: String = "FLASH_MSG"
    public const val RECEIPT_PREFIX: String = "FLASH_RCPT"
    public const val READ_PREFIX: String = "FLASH_READ"
    public const val REACT_PREFIX: String = "FLASH_REACT"
    public const val TYPING_PREFIX: String = "FLASH_TYPING"

    public sealed interface DecodeResult {
        public data class Frame(val frame: MessageWireFrame) : DecodeResult

        /** A known prefix whose key fields are missing — the caller must drop the frame. */
        public object RecognizedButInvalid : DecodeResult
    }

    /**
     * Encodes the five direct-chat frame types. Returns `null` for anything else
     * (`DeleteForEveryone` belongs to [DirectMessageActionCodec]); callers keep their
     * own arm for it so the `when` stays exhaustive when new subtypes appear.
     */
    public fun encode(frame: MessageWireFrame): String? = when (frame) {
        is MessageWireFrame.TextMessage -> FlashTextFraming.encodeFields(
            MSG_PREFIX,
            listOf(
                "localId" to frame.localId,
                "conversationId" to frame.conversationId,
                "senderId" to frame.senderId,
                "senderName" to (frame.senderName ?: "Peer"),
                "sentAt" to frame.sentAt.toString(),
                "text" to frame.text,
                // Reply/quote metadata (#8); empty string when this is not a reply.
                "replyToId" to (frame.replyToId ?: ""),
                "replyToPreview" to (frame.replyToPreview ?: ""),
            ),
        )
        is MessageWireFrame.DeliveryReceipt -> FlashTextFraming.encodeFields(
            RECEIPT_PREFIX,
            listOf(
                "messageId" to frame.messageId,
                "conversationId" to frame.conversationId,
                "memberId" to frame.memberId,
                "deliveredAt" to frame.deliveredAt.toString(),
            ),
        )
        is MessageWireFrame.ReadReceipt -> FlashTextFraming.encodeFields(
            READ_PREFIX,
            listOf(
                "conversationId" to frame.conversationId,
                "memberId" to frame.memberId,
                "upToMessageId" to frame.upToMessageId,
                "readAt" to frame.readAt.toString(),
            ),
        )
        is MessageWireFrame.ReactionFrame -> FlashTextFraming.encodeFields(
            REACT_PREFIX,
            listOf(
                "messageId" to frame.messageId,
                "conversationId" to frame.conversationId,
                "memberId" to frame.memberId,
                "emoji" to frame.emoji,
                "isAdded" to frame.isAdded.toString(),
            ),
        )
        is MessageWireFrame.TypingFrame -> FlashTextFraming.encodeFields(
            TYPING_PREFIX,
            listOf(
                "conversationId" to frame.conversationId,
                "memberId" to frame.memberId,
                "memberName" to frame.memberName,
                "isTyping" to frame.isTyping.toString(),
                "timestampMs" to frame.timestampMs.toString(),
            ),
        )
        else -> null
    }

    /**
     * Decodes one direct-chat text frame.
     *
     * Field mapping and defaults are verbatim from both holders (verified identical before
     * extraction): missing free-form fields default (`"Peer"`, `""`, `nowMs`, `false`,
     * `true`), missing KEY fields ([TextMessage.localId], receipt `messageId`, read
     * `memberId`/`upToMessageId`, reaction `messageId`/`memberId`/`emoji`, typing
     * `memberId`) invalidate the frame.
     */
    public fun decode(text: String, nowMs: Long, transportPeerId: String): DecodeResult? {
        FlashTextFraming.parseFields(text, MSG_PREFIX)?.let { f ->
            return DecodeResult.Frame(
                MessageWireFrame.TextMessage(
                    localId = f["localId"] ?: return DecodeResult.RecognizedButInvalid,
                    conversationId = f["conversationId"] ?: "",
                    senderId = f["senderId"] ?: "",
                    senderName = f["senderName"] ?: "Peer",
                    sentAt = f["sentAt"]?.toLongOrNull() ?: nowMs,
                    text = f["text"] ?: "",
                    // Reply/quote metadata (#8); blank fields (non-reply / legacy peer) → null.
                    replyToId = f["replyToId"]?.ifBlank { null },
                    replyToPreview = f["replyToPreview"]?.ifBlank { null },
                ),
            )
        }
        FlashTextFraming.parseFields(text, RECEIPT_PREFIX)?.let { f ->
            return DecodeResult.Frame(
                MessageWireFrame.DeliveryReceipt(
                    messageId = f["messageId"] ?: return DecodeResult.RecognizedButInvalid,
                    conversationId = f["conversationId"] ?: "",
                    memberId = f["memberId"] ?: "",
                    deliveredAt = f["deliveredAt"]?.toLongOrNull() ?: nowMs,
                ),
            )
        }
        FlashTextFraming.parseFields(text, READ_PREFIX)?.let { f ->
            return DecodeResult.Frame(
                MessageWireFrame.ReadReceipt(
                    conversationId = f["conversationId"] ?: "",
                    memberId = f["memberId"] ?: return DecodeResult.RecognizedButInvalid,
                    upToMessageId = f["upToMessageId"] ?: return DecodeResult.RecognizedButInvalid,
                    readAt = f["readAt"]?.toLongOrNull() ?: nowMs,
                ),
            )
        }
        FlashTextFraming.parseFields(text, REACT_PREFIX)?.let { f ->
            return DecodeResult.Frame(
                MessageWireFrame.ReactionFrame(
                    messageId = f["messageId"] ?: return DecodeResult.RecognizedButInvalid,
                    conversationId = f["conversationId"] ?: "",
                    memberId = f["memberId"] ?: return DecodeResult.RecognizedButInvalid,
                    emoji = f["emoji"] ?: return DecodeResult.RecognizedButInvalid,
                    isAdded = f["isAdded"]?.toBooleanStrictOrNull() ?: true,
                ),
            )
        }
        FlashTextFraming.parseFields(text, TYPING_PREFIX)?.let { f ->
            return DecodeResult.Frame(
                MessageWireFrame.TypingFrame(
                    // Preserve the wire-carried conversation id: direct senders encode the
                    // receiver id, while group fan-out encodes the group id. Falls back to the
                    // transport peer, as both holders did.
                    conversationId = f["conversationId"] ?: transportPeerId,
                    memberId = f["memberId"] ?: return DecodeResult.RecognizedButInvalid,
                    memberName = f["memberName"] ?: "Peer",
                    isTyping = f["isTyping"]?.toBooleanStrictOrNull() ?: false,
                    timestampMs = f["timestampMs"]?.toLongOrNull() ?: nowMs,
                ),
            )
        }
        return null
    }
}
