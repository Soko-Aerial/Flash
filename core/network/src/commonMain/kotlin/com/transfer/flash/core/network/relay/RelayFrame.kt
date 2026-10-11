package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope

/**
 * Protocol frames exchanged between the Flash client and the Flash Enterprise Relay Hub.
 */
@FlashInternalApi
public sealed interface RelayFrame {

    /**
     * Initial handshake frame sent by the client upon connecting to `/flash-relay`.
     */
    public data class Hello(
        public val deviceId: String,
        public val friendlyName: String,
        public val role: String = "member",
        public val authSignature: String? = null,
        public val clientNonce: String? = null,
    ) : RelayFrame

    /**
     * Server greeting sent upon accepting the WebSocket connection.
     */
    public data class Welcome(
        public val deviceId: String,
        public val status: String = "CONNECTED",
        public val timestamp: Long = 0L,
    ) : RelayFrame

    /**
     * Routed message frame carrying an encrypted [FlashEnvelope].
     */
    public data class Data(
        public val recipientId: String,
        public val id: String,
        public val type: String,
        public val senderId: String,
        public val sentAt: Long,
        public val payload: String,
    ) : RelayFrame {
        public fun toEnvelope(): FlashEnvelope = FlashEnvelope(
            id = id,
            type = type,
            payloadJson = payload,
            senderId = senderId,
            sentAt = sentAt,
        )

        public companion object {
            public fun fromEnvelope(recipientId: String, envelope: FlashEnvelope): Data = Data(
                recipientId = recipientId,
                id = envelope.id,
                type = envelope.type,
                senderId = envelope.senderId,
                sentAt = envelope.sentAt,
                payload = envelope.payloadJson,
            )
        }
    }

    /**
     * Delivery acknowledgment frame (`FLASH_ACK`).
     */
    public data class Ack(
        public val id: String,
        public val recipientId: String,
        public val status: String,
        public val timestamp: Long = 0L,
    ) : RelayFrame {
        public val isDelivered: Boolean get() = status == STATUS_DELIVERED
        public val isQueued: Boolean get() = status == STATUS_QUEUED
        public val isUndeliverable: Boolean get() = status == STATUS_UNDELIVERABLE

        public companion object {
            public const val STATUS_DELIVERED: String = "DELIVERED"
            public const val STATUS_QUEUED: String = "QUEUED"
            public const val STATUS_UNDELIVERABLE: String = "OFFLINE_UNDELIVERABLE"
        }
    }

    /**
     * Keepalive ping frame.
     */
    public data class Ping(
        public val timestamp: Long = 0L,
    ) : RelayFrame

    /**
     * Keepalive pong frame.
     */
    public data class Pong(
        public val timestamp: Long = 0L,
    ) : RelayFrame

    /**
     * Ephemeral metadata frame for typing indicators and presence updates.
     * Padded according to `P-REF-1`.
     */
    public data class Meta(
        public val action: String,
        public val conversationId: String,
        public val senderId: String,
        public val timestamp: Long = 0L,
        public val padLength: Int = 0,
    ) : RelayFrame {
        public companion object {
            public const val ACTION_TYPING: String = "TYPING"
            public const val ACTION_ONLINE: String = "ONLINE"
            public const val ACTION_OFFLINE: String = "OFFLINE"
        }
    }

    /**
     * Error notification frame from hub (`FLASH_ERROR`).
     */
    public data class Error(
        public val code: String,
        public val message: String,
    ) : RelayFrame {
        public companion object {
            public const val ERR_REVOKED: String = "ERR_REVOKED"
            public const val ERR_UNAUTHORIZED: String = "ERR_UNAUTHORIZED"
            public const val ERR_QUOTA_EXCEEDED: String = "ERR_QUOTA_EXCEEDED"
            public const val ERR_BAD_REQUEST: String = "ERR_BAD_REQUEST"
        }
    }
}
