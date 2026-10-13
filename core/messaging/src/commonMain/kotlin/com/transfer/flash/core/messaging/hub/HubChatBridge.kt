@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.messaging.protocol.ChatTextFrameCodec
import com.transfer.flash.core.messaging.protocol.DirectMessageActionCodec
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.network.enterprise.HubPeerStanding
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.HubSealingPin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where decoded hub traffic goes: the repository's own wire-frame handlers. */
@FlashInternalApi
public interface HubInboundSink {
    /** A 1:1 chat frame from [from]. The bridge has verified its claimed author is [from]. Throwing means "not stored". */
    public suspend fun direct(frame: MessageWireFrame, from: String)

    /** A group chat frame from [from] (message, receipt, read, delete). Throwing means "not stored". */
    public suspend fun group(frame: GroupWireFrame, from: String)
}

/** Whether content for one colleague can be sealed right now (ADR-121, ADR-126). */
@FlashInternalApi
public enum class HubSealingStatus {
    /** A verified sealing key is known and it is the one this device remembered. */
    AVAILABLE,

    /** The colleague has not published a usable sealing key (an older app, or the directory is not reachable). */
    NOT_PUBLISHED,

    /**
     * The directory serves a DIFFERENT key than the one remembered for this colleague, and the user has not confirmed
     * it. Nothing may be sent to them through the hub, sealed or readable, until the user decides.
     */
    KEY_CHANGED,
}

/**
 * Seals chat content end to end before the hub sees it (ADR-121). The hub never holds the keys.
 * Null everywhere means "sealing is not available"; the bridge then follows the content-via-hub setting.
 */
@FlashInternalApi
public interface HubSealingPort {
    /** True when [recipientDeviceId]'s sealing key is known and verified. */
    public suspend fun canSealFor(recipientDeviceId: String): Boolean

    /** The full answer behind [canSealFor], including a changed key waiting for the user (ADR-126). */
    public suspend fun statusFor(recipientDeviceId: String): HubSealingStatus =
        if (canSealFor(recipientDeviceId)) HubSealingStatus.AVAILABLE else HubSealingStatus.NOT_PUBLISHED

    /** The user chose to continue with the new key of [recipientDeviceId]. False when nothing was waiting. */
    public suspend fun confirmKeyChange(recipientDeviceId: String): Boolean = false

    /** Colleagues whose sealing key changed and waits for the user, with the old and the new key (ADR-126). */
    public val pendingKeyChanges: StateFlow<Map<String, HubSealingPin>>
        get() = NO_PENDING_KEY_CHANGES

    /** The sealed payload (relay type [HubSealing.ENVELOPE_TYPE]), or null when it cannot be sealed. */
    public suspend fun seal(recipientDeviceId: String, msgId: String, sentAt: Long, innerType: String, body: String): String?

    /** The opened content, or null when anything about the envelope does not verify. */
    public suspend fun open(senderDeviceId: String, msgId: String, sentAt: Long, payload: String): com.transfer.flash.core.security.enterprise.UnsealedPayload?
}

/** What happened to one hub delivery, for tests and counters. */
@FlashInternalApi
public enum class HubInboundOutcome {
    /** Stored by the sink and acknowledged to the hub. */
    STORED,

    /** Already handled earlier; acknowledged again so the hub stops redelivering. */
    DUPLICATE,

    /** Rejected and acknowledged (so the hub does not redeliver a message that will never be accepted). */
    REJECTED,

    /** NOT acknowledged on purpose: the hub keeps it and redelivers (directory unreachable, or the sink failed). */
    RETRY_LATER,
}

/** Why a message for a colleague is held back from the hub path instead of being sent (ADR-126). */
@FlashInternalApi
public enum class HubWaitReason {
    /** The colleague has not published a sealing key (older app) and readable content through the hub is off. */
    NEEDS_UPDATE,

    /** Readable content through the hub is off and this device cannot seal either (no key of its own yet, or an older hub). */
    SWITCHED_OFF,

    /** The sealing key of the colleague changed and waits for the confirmation of the user. */
    KEY_CHANGED,
}

/** A sentence the UI shows under a message the hub is carrying. [failed] means it will not arrive through the hub. */
@FlashInternalApi
public data class HubDeliveryNote(public val failed: Boolean, public val text: String)

/** Friendly sentences for the hub's verdicts (no protocol words, AGENTS.md section 22). */
@FlashInternalApi
public object HubDeliveryText {
    public fun note(kind: HubAckKind, recipientName: String?): HubDeliveryNote {
        val who = recipientName?.takeIf { it.isNotBlank() } ?: "the recipient"
        return when (kind) {
            HubAckKind.DELIVERED -> HubDeliveryNote(false, "Sent through your organisation's server.")
            HubAckKind.HELD -> HubDeliveryNote(false, "Held by your organisation's server until $who is online.")
            HubAckKind.UNDELIVERABLE -> HubDeliveryNote(true, "Your organisation's server could not deliver this to $who.")
            HubAckKind.QUOTA -> HubDeliveryNote(true, "The server's queue for $who is full, so this was not sent. Try again later.")
            HubAckKind.REFUSED -> HubDeliveryNote(true, "Your organisation's server refused this message.")
            HubAckKind.EXPIRED -> HubDeliveryNote(true, "The server held this message for too long and removed it before $who came online.")
            HubAckKind.DROPPED -> HubDeliveryNote(true, "The server dropped this message from its queue.")
        }
    }

    public const val SEALING_UNAVAILABLE: String =
        "Waiting for a direct connection: sending through your organisation's server is switched off in Settings."
}

/**
 * Everything the chat repository does with the hub, in one testable class (ADR-122): sending chat frames as relay
 * messages, taking deliveries in (dedupe, sender trust, durable-store-then-acknowledge), tracking the hub's receipts,
 * and the content policy (sealing, or an explicit user opt-in to plaintext).
 *
 * @param standing the hub-directory standing of a device, used for senders that are not paired.
 * @param isPaired true for a device this one has paired with.
 * @param groupLinked true when a group is linked to the hub (hub traffic is accepted only for those).
 * @param contentViaHub the user's opt-in (default OFF) to let the hub carry readable chat content.
 */
@FlashInternalApi
public class HubChatBridge(
    private val localDeviceId: String,
    private val port: HubRelayPort,
    private val timeSource: FlashTimeSource = SystemTimeSource,
    private val contentViaHub: () -> Boolean = { false },
    private val sealing: HubSealingPort? = null,
    private val standing: suspend (String) -> HubPeerStanding = { HubPeerStanding.UNKNOWN },
    private val isPaired: (String) -> Boolean = { false },
    private val groupLinked: (String) -> Boolean = { false },
) {
    private val seenLock = Mutex()
    private val seen = LinkedHashSet<String>()

    private val _notes = MutableStateFlow<Map<String, HubDeliveryNote>>(emptyMap())

    /** Per message id: what the hub said about it, for the bubble. Bounded. */
    public val notes: StateFlow<Map<String, HubDeliveryNote>> = _notes.asStateFlow()

    private val accepted = LinkedHashSet<String>()
    private val acceptedLock = Mutex()

    private val _droppedPlain = MutableStateFlow(0)

    /** Plaintext hub messages dropped because the user has not switched hub content on. Shown in the connection detail. */
    public val droppedPlain: StateFlow<Int> = _droppedPlain.asStateFlow()

    private val _droppedUnverified = MutableStateFlow(0)

    /**
     * Sealed hub messages that were dropped because they did not verify (changed on the way, wrong recipient, an unknown
     * sender key). Never shown as message content; the connection details show only the count.
     */
    public val droppedUnverified: StateFlow<Int> = _droppedUnverified.asStateFlow()

    private val _waiting = MutableStateFlow<Map<String, HubWaitReason>>(emptyMap())

    /** Per colleague: why content for them is held back from the hub right now. Updated by [canCarry]. */
    public val waiting: StateFlow<Map<String, HubWaitReason>> = _waiting.asStateFlow()

    private fun setWaiting(recipientDeviceId: String, reason: HubWaitReason?) {
        _waiting.update { current ->
            if (current[recipientDeviceId] == reason) current
            else if (reason == null) current - recipientDeviceId
            else current + (recipientDeviceId to reason)
        }
    }

    /** The user confirmed the new key of [recipientDeviceId]. True when a change was waiting; the hold is lifted at once. */
    public suspend fun confirmKeyChange(recipientDeviceId: String): Boolean {
        val confirmed = sealing?.confirmKeyChange(recipientDeviceId) == true
        if (confirmed) setWaiting(recipientDeviceId, null)
        return confirmed
    }

    public val isAvailable: Boolean get() = port.usable

    /** True once the hub has taken [messageId] (delivered or held): the sender then waits instead of resending quickly. */
    public suspend fun isHubAccepted(messageId: String): Boolean = acceptedLock.withLock { messageId in accepted }

    /**
     * True when the hub may carry content to [recipientDeviceId] right now: the relay is usable, the directory says the
     * recipient is an organisation member (a hub send to anyone else could only fail), AND the content can be sealed
     * for them or the user has opted in to readable content. A colleague whose sealing key CHANGED is held back even
     * with the opt-in on (a changed key is not a reason to downgrade to readable text). [group] frames are never sealed
     * (the group design is open, ADR-123), so for them only the opt-in counts. The reason a message is held is kept in
     * [waiting] so the chat can say so.
     */
    public suspend fun canCarry(recipientDeviceId: String, group: Boolean = false): Boolean {
        if (!canSignal(recipientDeviceId)) return false
        val status = if (group || sealing == null) HubSealingStatus.NOT_PUBLISHED else sealing.statusFor(recipientDeviceId)
        return when {
            status == HubSealingStatus.KEY_CHANGED -> {
                setWaiting(recipientDeviceId, HubWaitReason.KEY_CHANGED)
                false
            }
            status == HubSealingStatus.AVAILABLE -> {
                setWaiting(recipientDeviceId, null)
                true
            }
            contentViaHub() -> {
                setWaiting(recipientDeviceId, null)
                true
            }
            else -> {
                if (!group) setWaiting(recipientDeviceId, if (sealing != null) HubWaitReason.NEEDS_UPDATE else HubWaitReason.SWITCHED_OFF)
                false
            }
        }
    }

    /** True when metadata-only signals (typing) may go to [recipientDeviceId] through the hub. */
    public suspend fun canSignal(recipientDeviceId: String): Boolean =
        port.usable && standing(recipientDeviceId) == HubPeerStanding.MEMBER

    /** True when the hub holds [messageId] already, or could hold it for [recipientDeviceId]: the sender may keep waiting. */
    public suspend fun wouldHold(messageId: String, recipientDeviceId: String, group: Boolean = false): Boolean {
        if (isHubAccepted(messageId)) return true
        if (canCarry(recipientDeviceId, group)) return true
        // A message that waits for the user's decision about a changed key is kept, not failed after the short budget.
        return _waiting.value[recipientDeviceId] == HubWaitReason.KEY_CHANGED
    }

    /** Sender trust for ephemeral signals: paired, or an organisation member. */
    public suspend fun isSenderAllowed(deviceId: String): Boolean =
        isPaired(deviceId) || standing(deviceId) == HubPeerStanding.MEMBER

    // ----- outbound -----

    /** Sends a 1:1 chat frame through the hub. [id] is the relay message id the hub will report on. */
    public suspend fun sendDirect(to: String, id: String, sentAt: Long, frame: MessageWireFrame): Boolean {
        val body = when (frame) {
            is MessageWireFrame.DeleteForEveryone -> DirectMessageActionCodec.encode(frame)
            else -> ChatTextFrameCodec.encode(frame) ?: return false
        }
        return sendPayload(to, id, sentAt, CHAT_FRAME, body, seal = true)
    }

    /**
     * Sends a group chat frame to one member through the hub. Group frames are NOT sealed (ADR-126, the group design is
     * open, ADR-123): they travel readable, and only when the user opted in to readable content.
     */
    public suspend fun sendGroup(to: String, id: String, sentAt: Long, frame: GroupWireFrame): Boolean =
        sendPayload(to, id, sentAt, GROUP_FRAME, GroupFrameCodec.encode(frame), seal = false)

    private suspend fun sendPayload(to: String, id: String, sentAt: Long, innerType: String, body: String, seal: Boolean): Boolean {
        if (!port.usable) return false
        val status = if (seal && sealing != null) sealing.statusFor(to) else HubSealingStatus.NOT_PUBLISHED
        if (status == HubSealingStatus.KEY_CHANGED) {
            // Not sealed and not readable either: the user has to decide about the new key first.
            FlashLog.i("HUB", "key change not confirmed, not sending id=$id")
            setWaiting(to, HubWaitReason.KEY_CHANGED)
            return false
        }
        val sealed = if (status == HubSealingStatus.AVAILABLE) sealing?.seal(to, id, sentAt, innerType, body) else null
        return when {
            sealed != null -> port.send(to, id, HubSealing.ENVELOPE_TYPE, sentAt, sealed)
            contentViaHub() -> port.send(to, id, innerType, sentAt, body)
            else -> {
                FlashLog.i("HUB", "readable content not allowed via hub, not sending id=$id")
                false
            }
        }
    }

    public suspend fun sendTyping(conversationId: String, to: String): Boolean =
        port.usable && port.sendTyping(conversationId, to)

    // ----- the hub's receipts -----

    /** Records a hub verdict and returns the note to show. DELIVERED and HELD mark the message as accepted. */
    public suspend fun onAck(event: HubAckEvent, recipientName: String?): HubDeliveryNote {
        if (event.kind == HubAckKind.DELIVERED || event.kind == HubAckKind.HELD) {
            acceptedLock.withLock {
                accepted += event.id
                while (accepted.size > MAX_TRACKED) accepted.remove(accepted.first())
            }
        }
        val note = HubDeliveryText.note(event.kind, recipientName)
        _notes.update { current ->
            val next = LinkedHashMap(current)
            next.remove(event.id)
            next[event.id] = note
            while (next.size > MAX_TRACKED) next.remove(next.keys.first())
            next
        }
        return note
    }

    // ----- inbound -----

    /**
     * One hub delivery, end to end. The hub is told the message is stored (`FLASH_RECV_ACK`) ONLY after [sink] returned
     * normally, so a crash between receiving and storing makes the hub redeliver instead of losing the message.
     */
    public suspend fun handleInbound(d: HubInbound, sink: HubInboundSink): HubInboundOutcome {
        if (seenLock.withLock { d.id in seen }) {
            port.recvAck(d.id, d.from)
            return HubInboundOutcome.DUPLICATE
        }
        val outcome = process(d, sink)
        when (outcome) {
            HubInboundOutcome.STORED -> {
                remember(d.id)
                port.recvAck(d.id, d.from)
            }
            HubInboundOutcome.REJECTED -> port.recvAck(d.id, d.from)
            HubInboundOutcome.DUPLICATE, HubInboundOutcome.RETRY_LATER -> Unit
        }
        return outcome
    }

    private suspend fun remember(id: String) = seenLock.withLock {
        seen += id
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
    }

    private suspend fun process(d: HubInbound, sink: HubInboundSink): HubInboundOutcome {
        if (d.from.isBlank() || d.from == localDeviceId) return reject(d, "bad sender")

        // 1. Sender trust (CLI-14): a paired device, or a member the hub's own directory vouches for.
        if (!isPaired(d.from)) {
            when (standing(d.from)) {
                HubPeerStanding.MEMBER -> Unit
                HubPeerStanding.UNAVAILABLE -> return HubInboundOutcome.RETRY_LATER
                HubPeerStanding.UNKNOWN -> return reject(d, "sender is neither paired nor in the organisation directory")
            }
        }

        // 2. Content: sealed, or readable only when the user opted in.
        val innerType: String
        val body: String
        if (d.type == HubSealing.ENVELOPE_TYPE) {
            val opened = sealing?.open(d.from, d.id, d.sentAt, d.payload)
            if (opened == null) {
                // A sender the directory cannot be asked about right now is not a forgery: keep it for the next delivery.
                if (sealing != null && standing(d.from) == HubPeerStanding.UNAVAILABLE) return HubInboundOutcome.RETRY_LATER
                _droppedUnverified.update { it + 1 }
                return reject(d, "sealed message did not verify")
            }
            innerType = opened.innerType
            body = opened.body
        } else {
            if (!contentViaHub()) {
                _droppedPlain.update { it + 1 }
                return reject(d, "readable hub content is switched off")
            }
            innerType = d.type
            body = d.payload
        }

        // 3. Decode and route. The author named inside the frame must be the authenticated sender.
        return runCatching { route(d, innerType, body, sink) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            FlashLog.w("HUB", "inbound id=${d.id} not stored: ${e::class.simpleName}")
            HubInboundOutcome.RETRY_LATER
        }
    }

    private suspend fun route(d: HubInbound, innerType: String, body: String, sink: HubInboundSink): HubInboundOutcome {
        val now = timeSource.nowMs()
        when (innerType) {
            CHAT_FRAME -> {
                val frame: MessageWireFrame = when (val r = ChatTextFrameCodec.decode(body, now, d.from)) {
                    is ChatTextFrameCodec.DecodeResult.Frame -> r.frame
                    ChatTextFrameCodec.DecodeResult.RecognizedButInvalid -> return reject(d, "malformed chat frame")
                    null -> DirectMessageActionCodec.decode(body) ?: return reject(d, "unknown chat frame")
                }
                if (frame is MessageWireFrame.TypingFrame) return reject(d, "typing never travels as a message")
                if (authorOf(frame) != d.from) return reject(d, "frame author is not the sender")
                sink.direct(frame, d.from)
                return HubInboundOutcome.STORED
            }
            LEGACY_TEXT -> {
                val frame = MessageWireFrame.TextMessage(
                    localId = d.id,
                    conversationId = d.from,
                    senderId = d.from,
                    senderName = null,
                    text = body,
                    sentAt = d.sentAt,
                )
                sink.direct(frame, d.from)
                return HubInboundOutcome.STORED
            }
            GROUP_FRAME, LEGACY_GROUP -> {
                val frame = GroupFrameCodec.decode(body) ?: return reject(d, "unreadable group frame")
                val allowed = frame is GroupWireFrame.Message || frame is GroupWireFrame.Receipt ||
                    frame is GroupWireFrame.Read || frame is GroupWireFrame.DeleteForEveryone
                if (!allowed) return reject(d, "group frame kind is not allowed through the hub")
                if (frame.from != d.from) return reject(d, "frame author is not the sender")
                if (!groupLinked(frame.groupId)) return reject(d, "group is not linked to the hub")
                sink.group(frame, d.from)
                return HubInboundOutcome.STORED
            }
            else -> return reject(d, "unknown message type")
        }
    }

    private fun authorOf(frame: MessageWireFrame): String = when (frame) {
        is MessageWireFrame.TextMessage -> frame.senderId
        is MessageWireFrame.DeliveryReceipt -> frame.memberId
        is MessageWireFrame.ReadReceipt -> frame.memberId
        is MessageWireFrame.ReactionFrame -> frame.memberId
        is MessageWireFrame.TypingFrame -> frame.memberId
        is MessageWireFrame.DeleteForEveryone -> frame.from
    }

    private fun reject(d: HubInbound, why: String): HubInboundOutcome {
        FlashLog.w("HUB", "inbound id=${d.id} from=${d.from} rejected: $why")
        return HubInboundOutcome.REJECTED
    }

    public companion object {
        /** Relay envelope type for a 1:1 chat frame (the text framing of the LAN protocol). */
        public const val CHAT_FRAME: String = "CHAT_FRAME"

        /** Relay envelope type for a group chat frame. */
        public const val GROUP_FRAME: String = "GROUP_FRAME"

        /** Accepted inbound only: the pre-hub-v2 plain text type. */
        public const val LEGACY_TEXT: String = "MSG_TEXT"

        /** Accepted inbound only: the pre-hub-v2 group type (now requires a decodable [GroupWireFrame]). */
        public const val LEGACY_GROUP: String = "GROUP_MSG"

        public const val MAX_SEEN: Int = 2048
        public const val MAX_TRACKED: Int = 256
    }
}

private val NO_PENDING_KEY_CHANGES: StateFlow<Map<String, HubSealingPin>> = MutableStateFlow(emptyMap())
