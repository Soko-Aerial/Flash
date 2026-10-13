@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.QueueEvent
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.network.relay.FlashRelayClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * What the chat repository needs from the organisation hub's relay, and nothing else (ADR-122).
 *
 * This is the cut line for a later `:core:enterprise` extraction: the repository and [HubChatBridge] know only this
 * interface; [FlashRelayHubPort] is the one place that touches the network module's relay client.
 */
@FlashInternalApi
public interface HubRelayPort {

    /** True when this device is enrolled and the relay session can send right now. */
    public val usable: Boolean

    /** Mailbox deliveries. One collector (the bridge). A delivery must be answered with [recvAck] once stored. */
    public val deliveries: Flow<HubInbound>

    /** The hub's verdicts on messages this device sent, and later queue changes, already reduced to [HubAckKind]. */
    public val acks: Flow<HubAckEvent>

    /** Ephemeral typing signals (never queued by the hub). */
    public val typing: Flow<HubTypingEvent>

    /** Hands one message to the hub. True means the frame was written to the hub socket, not that anyone got it. */
    public suspend fun send(to: String, id: String, type: String, sentAt: Long, payload: String): Boolean

    /**
     * Tells the hub the delivery [id] is durably stored, so it may delete its mailbox copy. [from] names the sender of that delivery so the hub
     * deletes only that sender's copy when two senders reused one message id (REV-08); null keeps the older "oldest copy" behaviour.
     */
    public suspend fun recvAck(id: String, from: String? = null): Boolean

    /** Sends a typing signal for [conversationId] to [to]. */
    public suspend fun sendTyping(conversationId: String, to: String): Boolean
}

/** One delivery from the hub. [from] is stamped by the hub from the authenticated sender, never taken from [payload]. */
@FlashInternalApi
public data class HubInbound(
    public val from: String,
    public val id: String,
    public val type: String,
    public val sentAt: Long,
    public val payload: String,
    public val group: String? = null,
)

/** The hub's verdict on one message this device sent. [to] is the recipient the verdict is about. */
@FlashInternalApi
public data class HubAckEvent(
    public val id: String,
    public val to: String,
    public val kind: HubAckKind,
    public val reason: String? = null,
)

@FlashInternalApi
public enum class HubAckKind {
    /** The recipient is connected and the hub pushed the message to it. */
    DELIVERED,

    /** The hub keeps the message until the recipient connects. */
    HELD,

    /** The hub cannot deliver or hold it (recipient unknown to the hub). */
    UNDELIVERABLE,

    /** The recipient's queue on the hub is full. */
    QUOTA,

    /** The hub refused the message (bad frame, not allowed). */
    REFUSED,

    /** The hub held it past its limit and removed it. */
    EXPIRED,

    /** The hub dropped it from the queue. */
    DROPPED,
    ;

    /** True for the outcomes that end the message's life as undelivered. */
    public val isFailure: Boolean get() = this != DELIVERED && this != HELD
}

@FlashInternalApi
public data class HubTypingEvent(
    public val from: String,
    public val conversationId: String,
    public val timestampMs: Long,
)

/** [HubRelayPort] over the network module's [FlashRelayClient]; [enrolled] says whether credentials exist. */
@FlashInternalApi
public class FlashRelayHubPort(
    private val client: FlashRelayClient,
    private val enrolled: () -> Boolean,
) : HubRelayPort {

    override val usable: Boolean get() = enrolled() && client.state.value.canSend

    override val deliveries: Flow<HubInbound> = client.incomingDeliveries.mapNotNull {
        HubInbound(it.from, it.id, it.type, it.sentAt, it.payload, it.group)
    }

    override val acks: Flow<HubAckEvent> = kotlinx.coroutines.flow.merge(
        client.deliveryAcks.mapNotNull { ack ->
            val kind = when (ack.status) {
                AckStatus.DELIVERED -> HubAckKind.DELIVERED
                AckStatus.QUEUED, AckStatus.DUPLICATE_QUEUED -> HubAckKind.HELD
                AckStatus.OFFLINE_UNDELIVERABLE -> HubAckKind.UNDELIVERABLE
                AckStatus.QUOTA_EXCEEDED -> HubAckKind.QUOTA
                AckStatus.REJECTED, AckStatus.ERROR -> HubAckKind.REFUSED
            }
            HubAckEvent(ack.id, ack.to, kind, ack.reason)
        },
        client.queueStates.mapNotNull { state ->
            val kind = when (state.state) {
                QueueEvent.DELIVERED -> HubAckKind.DELIVERED
                QueueEvent.EXPIRED -> HubAckKind.EXPIRED
                QueueEvent.DROPPED -> HubAckKind.DROPPED
                QueueEvent.UNKNOWN -> null
            }
            kind?.let { HubAckEvent(state.id, state.to, it) }
        },
    )

    override val typing: Flow<HubTypingEvent> = client.metaEvents.mapNotNull { meta ->
        if (meta.action != RelayFrame.Meta.ACTION_TYPING) return@mapNotNull null
        val from = meta.from?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        HubTypingEvent(from, meta.conversationId, meta.ts)
    }

    override suspend fun send(to: String, id: String, type: String, sentAt: Long, payload: String): Boolean =
        client.sendEnvelope(to, FlashEnvelope(id = id, type = type, payloadJson = payload, senderId = client.deviceId, sentAt = sentAt))

    override suspend fun recvAck(id: String, from: String?): Boolean = client.acknowledgeReceipt(id, from)

    override suspend fun sendTyping(conversationId: String, to: String): Boolean = client.sendTyping(conversationId, to)
}
