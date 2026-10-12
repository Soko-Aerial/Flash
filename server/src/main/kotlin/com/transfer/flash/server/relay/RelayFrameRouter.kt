@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.server.auth.RevocationList
import com.transfer.flash.server.directory.OrganizationDirectory
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import org.slf4j.LoggerFactory

/**
 * Seam interface for persistent offline mailbox storage.
 *
 * Implemented by the mailbox subsystem to persist and drain encrypted frames
 * when peers connect or disconnect from the hub.
 */
public interface MailboxQueueSeam {
    /**
     * Enqueues an offline envelope for durable store-and-forward delivery.
     *
     * @return true if successfully persisted, false otherwise.
     */
    public suspend fun enqueue(recipientId: String, envelope: FlashEnvelope): Boolean

    /**
     * Optional raw frame enqueue fallback.
     */
    public suspend fun enqueueRaw(recipientId: String, rawFrame: String): Boolean = false

    /**
     * Fetches pending stored envelopes for [targetDeviceId].
     */
    public suspend fun fetchPendingEnvelopes(targetDeviceId: String, limit: Int = 100): List<FlashEnvelope> = emptyList()

    /**
     * Marks envelopes as delivered for [targetDeviceId] by their message IDs.
     */
    public suspend fun markEnvelopesDelivered(targetDeviceId: String, msgIds: List<String>): Int = 0
}

/**
 * Default no-op implementation of [MailboxQueueSeam] used when mailbox persistence is inactive.
 */
public object NoOpMailboxQueueSeam : MailboxQueueSeam {
    override suspend fun enqueue(recipientId: String, envelope: FlashEnvelope): Boolean = false
    override suspend fun enqueueRaw(recipientId: String, rawFrame: String): Boolean = false
    override suspend fun fetchPendingEnvelopes(targetDeviceId: String, limit: Int): List<FlashEnvelope> = emptyList()
    override suspend fun markEnvelopesDelivered(targetDeviceId: String, msgIds: List<String>): Int = 0
}

/**
 * Router and dispatcher for incoming WebSocket frames on `/flash-relay`.
 *
 * Coordinates between active sessions in [RelayConnectionPool] and durable offline
 * storage in [MailboxQueueSeam]. Parses frames encoded with [FlashTextFraming] or JSON envelopes,
 * handles delivery acknowledgements (`FLASH_ACK`), and maintains connection liveness.
 */
public class RelayFrameRouter(
    public val connectionPool: RelayConnectionPool,
    public var mailboxQueue: MailboxQueueSeam = NoOpMailboxQueueSeam,
    public val directory: OrganizationDirectory? = null,
    public val revocationListProvider: (() -> RevocationList?)? = null,
) {

    private val logger = LoggerFactory.getLogger(RelayFrameRouter::class.java)

    /**
     * Dispatches an inbound raw [frameText] from [originSession].
     */
    public suspend fun processFrame(originSession: RelaySession, frameText: String) {
        val trimmed = frameText.trim()
        if (trimmed.isEmpty()) return

        // 1. Check for Heartbeat Ping/Pong
        if (trimmed.startsWith("FLASH_PING")) {
            originSession.recordPing()
            val pong = FlashTextFraming.encodeFields(
                "FLASH_PONG",
                "timestamp" to System.currentTimeMillis().toString(),
            )
            originSession.sendText(pong)
            return
        }
        if (trimmed.startsWith("FLASH_PONG")) {
            originSession.recordPong()
            return
        }

        // 2. Check for Inbound Delivery ACK (peer-to-peer forwarded ACK)
        val ackFields = FlashTextFraming.parseFields(trimmed, "FLASH_ACK")
        if (ackFields != null) {
            handleInboundAck(originSession, ackFields, trimmed)
            return
        }

        // 3. Check for Ephemeral Metadata (typing / presence: P-REF-1 / P-REF-4)
        val metaFields = FlashTextFraming.parseFields(trimmed, "FLASH_META")
        if (metaFields != null) {
            handleInboundMeta(originSession, metaFields, trimmed)
            return
        }

        // 4. Parse Envelope (FlashTextFraming or JSON)
        val parsed = parseRelayMessage(trimmed)
        if (parsed == null) {
            logger.warn("Unrecognized or malformed frame from device '{}': {}", originSession.deviceId, trimmed.take(100))
            return
        }

        val (recipientId, envelope) = parsed
        dispatchEnvelope(originSession, recipientId, envelope, trimmed)
    }

    /**
     * Dispatches the parsed [envelope] to [recipientId].
     *
     * If online in [connectionPool], delivers immediately and sends FLASH_ACK(status=DELIVERED).
     * If offline, enqueues in [mailboxQueue] and sends FLASH_ACK(status=QUEUED).
     */
    public suspend fun dispatchEnvelope(
        originSession: RelaySession,
        recipientId: String,
        envelope: FlashEnvelope,
        rawFrame: String,
    ) {
        val targetSession = connectionPool.get(recipientId)
        if (targetSession != null) {
            try {
                // Ensure the frame sent to target carries full relay framing
                val wireFrame = if (rawFrame.startsWith("FLASH_RELAY") || rawFrame.startsWith("FLASH_DATA") || rawFrame.startsWith("{")) {
                    rawFrame
                } else {
                    encodeRelayFrame(recipientId, envelope)
                }
                targetSession.sendText(wireFrame)
                logger.debug(
                    "Directly routed envelope '{}' (type: {}) from '{}' to '{}'",
                    envelope.id,
                    envelope.type,
                    originSession.deviceId,
                    recipientId,
                )

                // Acknowledge immediate delivery to sender
                val ack = encodeAck(
                    id = envelope.id,
                    recipientId = recipientId,
                    status = STATUS_DELIVERED,
                )
                originSession.sendText(ack)
                return
            } catch (error: Throwable) {
                logger.warn("Failed sending directly to connected peer '{}', falling back to mailbox: {}", recipientId, error.message)
                connectionPool.unregister(recipientId)
            }
        }

        // Peer is offline: delegate to durable mailbox queue
        val enqueued = mailboxQueue.enqueue(recipientId, envelope)
        if (enqueued) {
            logger.debug(
                "Enqueued envelope '{}' (type: {}) for offline recipient '{}'",
                envelope.id,
                envelope.type,
                recipientId,
            )
            val ack = encodeAck(
                id = envelope.id,
                recipientId = recipientId,
                status = STATUS_QUEUED,
            )
            originSession.sendText(ack)
        } else {
            logger.warn(
                "Recipient '{}' is offline and mailbox could not persist envelope '{}'",
                recipientId,
                envelope.id,
            )
            val ack = encodeAck(
                id = envelope.id,
                recipientId = recipientId,
                status = STATUS_UNDELIVERABLE,
            )
            originSession.sendText(ack)
        }
    }

    private suspend fun handleInboundAck(
        originSession: RelaySession,
        fields: Map<String, String>,
        rawText: String,
    ) {
        val targetRecipient = fields["recipient"] ?: fields["target"] ?: fields["sender"]
        if (targetRecipient != null) {
            val target = connectionPool.get(targetRecipient)
            if (target != null) {
                target.sendText(rawText)
                logger.debug("Forwarded FLASH_ACK from '{}' to '{}'", originSession.deviceId, targetRecipient)
            }
        }
    }

    private suspend fun handleInboundMeta(
        originSession: RelaySession,
        fields: Map<String, String>,
        rawText: String,
    ) {
        val directRecipient = fields["recipient"] ?: fields["to"] ?: fields["target"] ?: fields["targetDeviceId"]
        if (directRecipient != null) {
            val target = connectionPool.get(directRecipient)
            if (target != null) {
                target.sendText(rawText)
                logger.trace("Forwarded direct FLASH_META from '{}' to '{}'", originSession.deviceId, directRecipient)
            }
            return
        }

        val groupId = fields["group"] ?: fields["groupId"]
        if (groupId != null && directory != null) {
            val group = directory.getGroup(groupId)
            if (group != null) {
                for (memberId in group.memberDeviceIds) {
                    if (memberId != originSession.deviceId) {
                        connectionPool.get(memberId)?.sendText(rawText)
                    }
                }
                logger.trace("Broadcast FLASH_META for group '{}' from '{}'", groupId, originSession.deviceId)
                return
            }
        }

        val convId = fields["conversationId"]
        if (convId != null) {
            val group = directory?.getGroup(convId)
            if (group != null) {
                for (memberId in group.memberDeviceIds) {
                    if (memberId != originSession.deviceId) {
                        connectionPool.get(memberId)?.sendText(rawText)
                    }
                }
                logger.trace("Broadcast FLASH_META for group conv '{}' from '{}'", convId, originSession.deviceId)
                return
            }
            val peer = connectionPool.get(convId)
            if (peer != null) {
                peer.sendText(rawText)
                logger.trace("Forwarded FLASH_META for convId peer '{}' from '{}'", convId, originSession.deviceId)
            }
        }
    }

    /**
     * Parses a relay message into `(recipientId, FlashEnvelope)`.
     *
     * Accepts:
     * - `FLASH_RELAY recipient=... id=... type=... sender=... sentAt=... payload=...`
     * - `FLASH_DATA recipient=... id=... type=... sender=... sentAt=... payload=...`
     * - `FLASH_ENVELOPE recipient=... id=... type=... sender=... sentAt=... payload=...`
     * - JSON envelope with `recipientId`, `id`, `type`, `senderId`, `sentAt`, `payloadJson`
     */
    public fun parseRelayMessage(text: String): Pair<String, FlashEnvelope>? {
        // Try line-based prefixes first
        for (prefix in listOf("FLASH_RELAY", "FLASH_DATA", "FLASH_ENVELOPE", "FLASH_MSG")) {
            val fields = FlashTextFraming.parseFields(text, prefix)
            if (fields != null) {
                val recipientId = fields["recipient"] ?: fields["to"] ?: fields["targetDeviceId"] ?: return null
                val id = fields["id"] ?: fields["msgId"] ?: fields["envelopeId"] ?: return null
                val type = fields["type"] ?: "MSG_TEXT"
                val senderId = fields["sender"] ?: fields["from"] ?: fields["senderId"] ?: return null
                val sentAt = fields["sentAt"]?.toLongOrNull() ?: System.currentTimeMillis()
                val payloadJson = fields["payload"] ?: fields["payloadJson"] ?: "{}"

                return try {
                    recipientId to FlashEnvelope(
                        id = id,
                        type = type,
                        payloadJson = payloadJson,
                        senderId = senderId,
                        sentAt = sentAt,
                    )
                } catch (_: Throwable) {
                    null
                }
            }
        }

        // Try JSON format
        if (text.startsWith("{") && text.endsWith("}")) {
            return parseJsonEnvelope(text)
        }

        return null
    }

    private fun parseJsonEnvelope(json: String): Pair<String, FlashEnvelope>? {
        fun extractString(key: String): String? {
            val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return pattern.find(json)?.groupValues?.getOrNull(1)
        }
        fun extractLong(key: String): Long? {
            val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
            return pattern.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }

        val recipientId = extractString("recipientId") ?: extractString("recipient") ?: extractString("to") ?: return null
        val id = extractString("id") ?: extractString("msgId") ?: return null
        val type = extractString("type") ?: "MSG_TEXT"
        val senderId = extractString("senderId") ?: extractString("sender") ?: return null
        val sentAt = extractLong("sentAt") ?: System.currentTimeMillis()

        // Extract payloadJson: could be string or nested object
        val payloadJson = extractString("payloadJson")
            ?: extractString("payload")
            ?: "{}"

        return try {
            recipientId to FlashEnvelope(
                id = id,
                type = type,
                payloadJson = payloadJson,
                senderId = senderId,
                sentAt = sentAt,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Formats a [FlashEnvelope] into standard `FLASH_RELAY` wire text.
     */
    public fun encodeRelayFrame(recipientId: String, envelope: FlashEnvelope): String {
        return FlashTextFraming.encodeFields(
            "FLASH_RELAY",
            "recipient" to recipientId,
            "id" to envelope.id,
            "type" to envelope.type,
            "sender" to envelope.senderId,
            "sentAt" to envelope.sentAt.toString(),
            "payload" to envelope.payloadJson,
        )
    }

    /**
     * Formats a delivery acknowledgement frame.
     */
    public fun encodeAck(
        id: String,
        recipientId: String,
        status: String,
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        return FlashTextFraming.encodeFields(
            "FLASH_ACK",
            "id" to id,
            "recipient" to recipientId,
            "status" to status,
            "timestamp" to timestamp.toString(),
        )
    }

    /**
     * Installs the `/flash-relay` WebSocket endpoint onto the Ktor routing hierarchy.
     */
    /**
     * Drains pending offline envelopes for [session] from [mailboxQueue] and forwards them.
     *
     * @return count of successfully delivered envelopes.
     */
    public suspend fun drainMailboxForDevice(session: RelaySession, limit: Int = 100): Int {
        val pending = mailboxQueue.fetchPendingEnvelopes(session.deviceId, limit)
        if (pending.isEmpty()) return 0

        logger.info("Draining {} pending mailbox envelopes for reconnected device '{}'", pending.size, session.deviceId)
        val deliveredIds = mutableListOf<String>()

        for (envelope in pending) {
            try {
                val wireFrame = encodeRelayFrame(session.deviceId, envelope)
                session.sendText(wireFrame)
                deliveredIds.add(envelope.id)
            } catch (e: Throwable) {
                logger.warn("Failed delivering drained envelope '{}' to device '{}': {}", envelope.id, session.deviceId, e.message)
                break
            }
        }

        if (deliveredIds.isNotEmpty()) {
            mailboxQueue.markEnvelopesDelivered(session.deviceId, deliveredIds)
        }

        return deliveredIds.size
    }

    /**
     * Installs the `/flash-relay` WebSocket endpoint onto the Ktor routing hierarchy.
     */
    public fun registerRoute(route: Route) {
        route.webSocket("/flash-relay") {
            handleSession(this, call)
        }
    }

    /**
     * Handles the full lifecycle of a connected WebSocket peer.
     */
    public suspend fun handleSession(session: WebSocketSession, call: ApplicationCall) {
        val queryParams = call.request.queryParameters
        var deviceId = queryParams["deviceId"] ?: queryParams["id"]
        var friendlyName = queryParams["name"] ?: queryParams["friendlyName"] ?: deviceId ?: "Device"
        var role = queryParams["role"] ?: "member"
        val remoteAddress = "${call.request.origin.remoteAddress}:${call.request.origin.remotePort}"

        // If deviceId wasn't passed in query params, inspect first frame for handshake
        if (deviceId == null) {
            val firstFrame = session.incoming.receiveCatching().getOrNull()
            if (firstFrame is Frame.Text) {
                val handshakeText = firstFrame.readText().trim()
                val helloFields = FlashTextFraming.parseFields(handshakeText, "FLASH_HUB_HELLO")
                    ?: FlashTextFraming.parseFields(handshakeText, "FLASH_HELLO")
                if (helloFields != null) {
                    deviceId = helloFields["deviceId"] ?: helloFields["id"]
                    friendlyName = helloFields["name"] ?: friendlyName
                    role = helloFields["role"] ?: role
                }
            }
        }

        if (deviceId.isNullOrBlank()) {
            logger.warn("Rejecting unauthenticated connection from {}", remoteAddress)
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing deviceId"))
            return
        }

        // Check if device is revoked
        val revList = revocationListProvider?.invoke()
        if (revList != null && revList.isRevoked(-1L, deviceId)) {
            logger.warn("Rejecting connection from revoked device '{}' from {}", deviceId, remoteAddress)
            val errFrame = FlashTextFraming.encodeFields(
                "FLASH_ERROR",
                "code" to "ERR_REVOKED",
                "message" to "Device $deviceId is revoked",
            )
            session.send(Frame.Text(errFrame))
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device revoked"))
            return
        }

        val relaySession = RelaySession(
            deviceId = deviceId,
            friendlyName = friendlyName,
            role = role,
            remoteAddress = remoteAddress,
            websocketSession = session,
        )

        connectionPool.register(relaySession)

        try {
            // Acknowledge connection ready
            val welcome = FlashTextFraming.encodeFields(
                "FLASH_HUB_WELCOME",
                "deviceId" to deviceId,
                "status" to "CONNECTED",
                "timestamp" to System.currentTimeMillis().toString(),
            )
            relaySession.sendText(welcome)

            // Drain any pending offline envelopes queued for this reconnected device
            drainMailboxForDevice(relaySession)

            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    processFrame(relaySession, frame.readText())
                }
            }
        } catch (error: Throwable) {
            logger.debug("Session error for device '{}': {}", deviceId, error.message)
        } finally {
            connectionPool.unregister(deviceId)
        }
    }

    public companion object {
        public const val STATUS_DELIVERED: String = "DELIVERED"
        public const val STATUS_QUEUED: String = "QUEUED"
        public const val STATUS_UNDELIVERABLE: String = "OFFLINE_UNDELIVERABLE"
    }
}
