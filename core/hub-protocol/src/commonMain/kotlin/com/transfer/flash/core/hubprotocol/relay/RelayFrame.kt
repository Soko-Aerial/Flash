package com.transfer.flash.core.hubprotocol.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The frames of "Hub relay wire v2" (ADR-109, `docs/protocol.md`). One JSON object per WebSocket text frame, the
 * frame type in the `"t"` member. The direction of each frame is part of its contract:
 *
 * ```
 * server -> client   FLASH_HUB_CHALLENGE   immediately on accept
 * client -> server   FLASH_HUB_HELLO       within HANDSHAKE_TIMEOUT_MS
 * server -> client   FLASH_HUB_WELCOME  |  FLASH_ERROR + close
 * client -> server   FLASH_RELAY           an outgoing message (NO sender field: the hub stamps it)
 * server -> client   FLASH_DELIVER         an incoming message (sender stamped by the hub)
 * client -> server   FLASH_RECV_ACK        "I have durably accepted <msgId>"
 * server -> client   FLASH_ACK             hub receipt for a FLASH_RELAY (delivered / queued / refused)
 * server -> client   FLASH_QUEUE_STATE     a queued message changed state (delivered / expired / dropped)
 * both               FLASH_META            ephemeral typing/presence, padded to 256 / 512 bytes
 * both               FLASH_PING / FLASH_PONG
 * server -> client   FLASH_REVOKED         this device's certificate was revoked (connection closes)
 * server -> client   FLASH_KICK            the hub is dropping this connection (superseded, shutdown, ...)
 * server -> client   FLASH_ERROR           a refusal; carries a [com.transfer.flash.core.hubprotocol.HubErrorCodes] code
 * ```
 *
 * Nothing in a frame is trusted for identity except the handshake: after WELCOME the hub ignores every client-supplied
 * identity field (there is none on [Send]; [Meta.from] is overwritten) and stamps the authenticated device id itself
 * (SEC-02).
 */
@FlashInternalApi
@Serializable
public sealed interface RelayFrame {

    /** `FLASH_HUB_CHALLENGE`, server -> client, first frame on every connection. */
    @Serializable
    @SerialName("FLASH_HUB_CHALLENGE")
    public data class Challenge(
        public val v: Int = HubContract.RELAY_WIRE_VERSION,
        /** Realm audience: upper-case hex SHA-256 of the organisation root key. The client MUST compare it to its pinned value. */
        public val serverId: String,
        /** Single-use random nonce, Base64URL, valid for CHALLENGE_TTL_SECONDS. */
        public val serverNonce: String,
        public val issuedAt: Long,
    ) : RelayFrame

    /** `FLASH_HUB_HELLO`, client -> server. [signature] covers [com.transfer.flash.core.hubprotocol.certs.HubAuth.authSigningBytes]. */
    @Serializable
    @SerialName("FLASH_HUB_HELLO")
    public data class Hello(
        public val v: Int = HubContract.RELAY_WIRE_VERSION,
        public val deviceId: String,
        public val clientNonce: String,
        public val name: String = "",
        public val deviceCert: DeviceCert,
        public val delegationChain: List<DelegationCert> = emptyList(),
        /** Base64 ECDSA P-256 signature by the device key over (serverId, serverNonce, clientNonce, deviceId, certSerial). */
        public val signature: String,
    ) : RelayFrame

    /** `FLASH_HUB_WELCOME`, server -> client: the handshake succeeded; the connection is now [principal]. */
    @Serializable
    @SerialName("FLASH_HUB_WELCOME")
    public data class Welcome(
        public val v: Int = HubContract.RELAY_WIRE_VERSION,
        public val principal: HubPrincipal,
        public val serverTime: Long,
        public val limits: RelayLimits = RelayLimits(),
        /** Reserved for session resumption; not issued by this version. */
        public val resumeToken: String? = null,
    ) : RelayFrame

    /** `FLASH_ERROR`, server -> client. [message] is a fixed human sentence, never exception text (SEC-16). */
    @Serializable
    @SerialName("FLASH_ERROR")
    public data class Error(
        public val code: String,
        public val message: String = "",
    ) : RelayFrame

    /**
     * `FLASH_RELAY`, client -> server: send [payload] to [to]. There is deliberately NO `from`: a sender can not name
     * itself. [id] is the client message id and is the dedup key (a re-send of the same id is `DUPLICATE_QUEUED`, not a second copy).
     */
    @Serializable
    @SerialName("FLASH_RELAY")
    public data class Send(
        public val to: String = "",
        public val id: String,
        public val type: String = DEFAULT_TYPE,
        public val sentAt: Long,
        public val payload: String,
        /**
         * Group fan-out (REL-14): when set, the hub delivers to every member of this group except the sender and
         * [to] is ignored (leave it blank). The sender must be a member (`ERR_NOT_MEMBER` otherwise).
         */
        public val group: String? = null,
    ) : RelayFrame {
        public companion object {
            public const val DEFAULT_TYPE: String = "MSG_TEXT"

            public fun fromEnvelope(to: String, envelope: FlashEnvelope): Send = Send(
                to = to,
                id = envelope.id,
                type = envelope.type,
                sentAt = envelope.sentAt,
                payload = envelope.payloadJson,
            )
        }
    }

    /** `FLASH_DELIVER`, server -> client: an incoming message. [from] is the hub-stamped authenticated sender. */
    @Serializable
    @SerialName("FLASH_DELIVER")
    public data class Deliver(
        public val from: String,
        public val id: String,
        public val type: String = Send.DEFAULT_TYPE,
        public val sentAt: Long,
        public val payload: String,
        /** True when this frame comes out of the offline mailbox rather than live routing. Clients dedupe by [id]. */
        public val replay: Boolean = false,
        /** The group this message was fanned out to (REL-14), or null for a direct message. */
        public val group: String? = null,
    ) : RelayFrame {
        public fun toEnvelope(): FlashEnvelope = FlashEnvelope(
            id = id,
            type = type,
            payloadJson = payload,
            senderId = from,
            sentAt = sentAt,
        )
    }

    /**
     * `FLASH_RECV_ACK`, client -> server: the client has durably accepted message [msgId] (written to its own store).
     * Only this frame lets the hub delete a mailbox row (REL-02).
     */
    @Serializable
    @SerialName("FLASH_RECV_ACK")
    public data class RecvAck(
        public val msgId: String,
        /**
         * The device that sent [msgId] (the `from` of the delivered frame). Optional and additive (REV-08): two senders may use the
         * same message id, and without it the hub can only acknowledge the oldest stored copy, never both.
         */
        public val from: String? = null,
    ) : RelayFrame

    /**
     * `FLASH_ACK`, server -> client: the hub's receipt for the [Send] with id [id].
     * [to] is the recipient the ORIGINAL SEND was addressed to (it is never the acknowledging peer: there is no
     * peer-forwarded ack on this wire, an application-level read receipt is an ordinary [Send] with its own `type`).
     */
    @Serializable
    @SerialName("FLASH_ACK")
    public data class Ack(
        public val id: String,
        public val to: String = "",
        public val status: AckStatus = AckStatus.ERROR,
        public val ts: Long = 0L,
        public val reason: String? = null,
        /**
         * Group sends only (REL-14): [to] is the group id and these count the members handed the message live
         * ([delivered]), stored for later ([queued]) and refused/failed ([failed]). Zero for a direct send.
         */
        public val delivered: Int = 0,
        public val queued: Int = 0,
        public val failed: Int = 0,
    ) : RelayFrame

    /** `FLASH_QUEUE_STATE`, server -> client: a message earlier acknowledged as QUEUED changed state. */
    @Serializable
    @SerialName("FLASH_QUEUE_STATE")
    public data class QueueState(
        public val id: String,
        public val to: String = "",
        public val state: QueueEvent = QueueEvent.UNKNOWN,
        public val ts: Long = 0L,
    ) : RelayFrame

    /**
     * `FLASH_META`: typing / presence. Client -> server carries [to] and no [from]; server -> client carries [from]
     * (hub-stamped). [pad] is filler that makes every frame 256 or 512 bytes on the wire (P-REF-1).
     */
    @Serializable
    @SerialName("FLASH_META")
    public data class Meta(
        public val action: String,
        public val conversationId: String = "",
        public val to: String? = null,
        public val from: String? = null,
        public val ts: Long = 0L,
        public val pad: String = "",
    ) : RelayFrame {
        public companion object {
            public const val ACTION_TYPING: String = "TYPING"
            public const val ACTION_ONLINE: String = "ONLINE"
            public const val ACTION_OFFLINE: String = "OFFLINE"
        }
    }

    @Serializable
    @SerialName("FLASH_PING")
    public data class Ping(public val ts: Long = 0L) : RelayFrame

    @Serializable
    @SerialName("FLASH_PONG")
    public data class Pong(public val ts: Long = 0L) : RelayFrame

    /** `FLASH_REVOKED`, server -> client: the device's certificate (or delegation) is revoked. Terminal; the hub closes next. */
    @Serializable
    @SerialName("FLASH_REVOKED")
    public data class Revoked(
        public val reason: String = "",
        public val serial: Long? = null,
    ) : RelayFrame

    /** `FLASH_KICK`, server -> client: the hub is closing this connection ([reason] is a short code such as `superseded`). */
    @Serializable
    @SerialName("FLASH_KICK")
    public data class Kick(
        public val reason: String = "",
    ) : RelayFrame
}

/** The authenticated identity a connection is bound to after WELCOME. */
@FlashInternalApi
@Serializable
public data class HubPrincipal(
    public val deviceId: String,
    public val role: String,
    public val departmentId: String? = null,
    public val certSerial: Long = 0L,
)

/** Limits the hub enforces on this connection, announced so a client can size its work (and refuse early). */
@FlashInternalApi
@Serializable
public data class RelayLimits(
    public val maxFrameBytes: Int = HubContract.MAX_FRAME_BYTES,
    public val maxPayloadBytes: Int = HubContract.MAX_PAYLOAD_BYTES,
)

/**
 * What the hub says happened to a `FLASH_RELAY`. [ERROR] is also the value an unknown (newer) status decodes to.
 */
@FlashInternalApi
@Serializable
public enum class AckStatus {
    /** Written to the recipient's live connection. NOT proof the recipient stored it: that is the recipient's own receipt. */
    DELIVERED,

    /** Recipient offline: stored in the hub mailbox until the recipient comes back. */
    QUEUED,

    /** Recipient offline and the hub holds no mailbox for it; the sender should retry later. */
    OFFLINE_UNDELIVERABLE,

    /** The same message id was already queued; no second copy was stored. */
    DUPLICATE_QUEUED,

    /** The recipient's mailbox is full. */
    QUOTA_EXCEEDED,

    /** Refused by policy (not a member, unknown recipient, ...); [RelayFrame.Ack.reason] holds the error code. */
    REJECTED,

    ERROR,
    ;

    /** True when the hub accepted responsibility for delivery. */
    public val isAccepted: Boolean get() = this == DELIVERED || this == QUEUED || this == DUPLICATE_QUEUED
}

/** What happened to a queued message ([RelayFrame.QueueState.state]). [UNKNOWN] is the value an unknown (newer) state decodes to. */
@FlashInternalApi
@Serializable
public enum class QueueEvent { DELIVERED, EXPIRED, DROPPED, UNKNOWN }
