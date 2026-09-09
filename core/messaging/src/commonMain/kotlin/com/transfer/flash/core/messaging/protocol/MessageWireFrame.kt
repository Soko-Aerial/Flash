package com.transfer.flash.core.messaging.protocol

/** A text frame carried by the chat transport. */
public sealed interface ChatWireFrame

/**
 * Protocol frames exchanged over Flash networking sessions for direct messaging (C6.0).
 */
public sealed interface MessageWireFrame : ChatWireFrame {

    /**
     * Outbox text message payload.
     *
     * @property localId client-generated UUID for deduplication and idempotency.
     * @property conversationId target conversation identifier.
     * @property senderId unique device ID of the author.
     * @property senderName display name of the author.
     * @property text message content string.
     * @property sentAt client timestamp in ms.
     */
    public data class TextMessage(
        val localId: String,
        val conversationId: String,
        val senderId: String,
        val senderName: String?,
        val text: String,
        val sentAt: Long,
        /** Reply/quote: the quoted message's localId, or null for a normal message. */
        val replyToId: String? = null,
        /** Short snapshot of the quoted message's text, carried so the peer renders the quote. */
        val replyToPreview: String? = null,
    ) : MessageWireFrame

    /**
     * Delivery receipt confirming that a message was received and committed to storage.
     */
    public data class DeliveryReceipt(
        val messageId: String,
        val conversationId: String,
        val memberId: String,
        val deliveredAt: Long,
    ) : MessageWireFrame

    /**
     * Read cursor receipt confirming that messages up to [upToMessageId] have been seen.
     */
    public data class ReadReceipt(
        val conversationId: String,
        val memberId: String,
        val upToMessageId: String,
        val readAt: Long,
    ) : MessageWireFrame

    /**
     * Ephemeral typing state frame.
     * TTL-governed, never persisted to disk.
     */
    public data class TypingFrame(
        val conversationId: String,
        val memberId: String,
        val memberName: String,
        val isTyping: Boolean,
        val timestampMs: Long,
    ) : MessageWireFrame

    /** Author-requested tombstone for one direct message. */
    public data class DeleteForEveryone(
        val messageId: String,
        val conversationId: String,
        val from: String,
    ) : MessageWireFrame

    /**
     * Reaction update frame.
     */
    public data class ReactionFrame(
        val messageId: String,
        val conversationId: String,
        val memberId: String,
        val emoji: String,
        val isAdded: Boolean,
    ) : MessageWireFrame
}
