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
import com.transfer.flash.core.messaging.protocol.HubFileFrame
import com.transfer.flash.core.messaging.protocol.HubFileFrameCodec
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.enterprise.HubPeerStanding
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.security.enterprise.GroupRelaySealing
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

    /**
     * A file sealed on the hub, offered by [from] (ADR-216). The bridge has checked it arrived sealed and that its author
     * is [from]. False means this device does not take hub files (rejected and acknowledged); throwing means "not stored".
     */
    public suspend fun hubFile(frame: HubFileFrame, from: String): Boolean = false
}

/** Whether a file may go to one colleague through the hub right now (ADR-216). */
@FlashInternalApi
public enum class HubFileRoute {
    /** The relay is usable, the colleague is an organisation member and the ticket can be sealed for them. */
    READY,

    /** No hub path at all (not enrolled, the relay is down, or not an organisation member): the LAN path decides. */
    UNAVAILABLE,

    /**
     * A hub path exists but the ticket cannot be sealed for this colleague (no published key, or a key change the user has
     * not confirmed). A file key never travels readable, whatever the readable-content setting says.
     */
    NEEDS_SEALING,
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

    /**
     * The directory serves a different identity key than the one saved when this device paired with the colleague
     * directly. Nothing may be sent through the hub, and the user cannot confirm it away (ADR-164b).
     */
    IDENTITY_MISMATCH,
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

    /**
     * True when a null from [open] for [senderDeviceId] may be a passing condition (this device has no sealing key loaded yet,
     * or the sender's key is not in the directory right now) rather than a verdict on the message. The bridge then keeps the
     * message for the next delivery instead of acknowledging it, which would make the hub delete it (RVW22 F1).
     */
    public suspend fun openFailureMayClear(senderDeviceId: String): Boolean = false

    /** Verified X.509 SPKI identity public key of [deviceId], or null when unavailable or mismatched with a pairing pin. */
    public suspend fun identityPublicKeyEncoded(deviceId: String): ByteArray? = null
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

    /** The hub's directory contradicts the key saved when this device paired with the colleague directly (ADR-164b). */
    IDENTITY_MISMATCH,
}

/** A sentence the UI shows under a message the hub is carrying. [failed] means it will not arrive through the hub. */
@FlashInternalApi
public data class HubDeliveryNote(public val failed: Boolean, public val text: String)

/**
 * How old a hub delivery may be before the recipient refuses it (SEC-D15-02).
 *
 * The relay wire has no counter, so replay protection rested entirely on the recipient's own `msgId` dedupe: once a chat
 * is cleared, a rogue hub (or a replayed capture) could re-deliver an old envelope and the client would accept it as new.
 * A sender's `sentAt` is authenticated (`HubSealing` binds it into the AAD, so a hub can not move it without the
 * envelope failing to open) and the hub says how long it keeps undelivered mail ([RelayLimits.mailboxTtlMs]), so anything
 * older than that retention plus a grace cannot still be legitimately queued.
 *
 * Limits of the rule, on purpose:
 * - it only bounds the PAST. A sender whose clock runs behind is accepted for as long as the window allows; a message
 *   that is genuinely fresh is refused only if the sender's clock is days off.
 * - `sentAt` of 0 or less (a very old peer) is not judged at all.
 * - `replay` is NOT used: the hub sets it, so only the authenticated timestamp is worth reading.
 */
@FlashInternalApi
public object HubFreshness {

    /** The largest age accepted from a hub that announced [announcedTtlMs] (0 = it announced nothing). */
    public fun limitMs(announcedTtlMs: Long): Long =
        (if (announcedTtlMs > 0L) announcedTtlMs else HubContract.DEFAULT_MAILBOX_TTL_MS) + HubContract.MESSAGE_AGE_GRACE_MS

    /** True when [sentAt] is further in the past than [limitMs] allows at [nowMs]. */
    public fun isStale(sentAt: Long, nowMs: Long, limitMs: Long): Boolean = sentAt > 0L && nowMs - sentAt > limitMs
}

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
    private val groupE2ee: HubGroupE2eeCoordinator? = null,
    private val groupMembershipProvider: suspend (String) -> Pair<Set<String>, Long>? = { null },
    private val groupMemberAllowed: suspend (groupId: String, deviceId: String) -> Boolean = { _, deviceId ->
        standing(deviceId) == HubPeerStanding.MEMBER || isPaired(deviceId)
    },
) {
    private val seenLock = Mutex()
    // Keyed by (sender, id): ids are chosen by the sender, so two senders may use the same one (ERROR-396, REV-08 on the hub side).
    private val seen = LinkedHashSet<Pair<String, String>>()

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

    private val _droppedStale = MutableStateFlow(0)

    /**
     * Deliveries refused as too old for the hub's announced retention (SEC-D15-02): a replayed envelope whose message id
     * was no longer known. Shown as a count in the connection details, like [droppedUnverified].
     */
    public val droppedStale: StateFlow<Int> = _droppedStale.asStateFlow()

    private val _waiting = MutableStateFlow<Map<String, HubWaitReason>>(emptyMap())
    /** Per colleague: why content for them is held back from the hub right now. Updated by [canCarry]. */
    public val waiting: StateFlow<Map<String, HubWaitReason>> = _waiting.asStateFlow()

    private val _groupE2eeStates = MutableStateFlow<Map<String, GroupE2eeGroupState>>(emptyMap())
    /** Per groupId: live E2EE status and compatibility/blocking state for UI and fail-closed controls (HR10). */
    public val groupE2eeStates: StateFlow<Map<String, GroupE2eeGroupState>> = _groupE2eeStates.asStateFlow()

    public val isGroupE2eeEnabled: Boolean get() = groupE2ee != null

    public suspend fun evaluateGroupE2ee(groupId: String): GroupE2eeGroupState? {
        val e2ee = groupE2ee ?: return null
        val members = groupMembershipProvider(groupId)?.first.orEmpty()
        val state = e2ee.evaluateGroup(groupId, members, sealing)
        _groupE2eeStates.update { it + (groupId to state) }
        return state
    }

    private fun waitReasonForStatus(status: HubSealingStatus): HubWaitReason = when (status) {
        HubSealingStatus.KEY_CHANGED -> HubWaitReason.KEY_CHANGED
        HubSealingStatus.IDENTITY_MISMATCH -> HubWaitReason.IDENTITY_MISMATCH
        HubSealingStatus.NOT_PUBLISHED, HubSealingStatus.AVAILABLE ->
            if (sealing != null) HubWaitReason.NEEDS_UPDATE else HubWaitReason.SWITCHED_OFF
    }

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
        val legacyGroupPlain = group && groupE2ee == null
        val status = if (legacyGroupPlain || sealing == null) HubSealingStatus.NOT_PUBLISHED else sealing.statusFor(recipientDeviceId)
        return when {
            status == HubSealingStatus.KEY_CHANGED -> {
                setWaiting(recipientDeviceId, HubWaitReason.KEY_CHANGED)
                false
            }
            status == HubSealingStatus.IDENTITY_MISMATCH -> {
                setWaiting(recipientDeviceId, HubWaitReason.IDENTITY_MISMATCH)
                false
            }
            status == HubSealingStatus.AVAILABLE -> {
                setWaiting(recipientDeviceId, null)
                true
            }
            (!group || legacyGroupPlain) && contentViaHub() -> {
                setWaiting(recipientDeviceId, null)
                true
            }
            else -> {
                if (!legacyGroupPlain) {
                    setWaiting(recipientDeviceId, if (sealing != null) HubWaitReason.NEEDS_UPDATE else HubWaitReason.SWITCHED_OFF)
                }
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

    /**
     * Whether a file may go to [recipientDeviceId] through the hub. Unlike [canCarry], the readable-content opt-in does
     * not count: the ticket holds the file's key, so it is sealed or it does not go.
     */
    public suspend fun fileRoute(recipientDeviceId: String): HubFileRoute {
        if (!canSignal(recipientDeviceId)) return HubFileRoute.UNAVAILABLE
        val status = sealing?.statusFor(recipientDeviceId) ?: HubSealingStatus.NOT_PUBLISHED
        return if (status == HubSealingStatus.AVAILABLE) HubFileRoute.READY else HubFileRoute.NEEDS_SEALING
    }

    /**
     * Whether a file of group [groupId] may go through the hub (ADR-217): the hub is usable, the device can seal, and the hub
     * knows the group's roster (a linked group). Each member's ticket is sealed on its own, so a member without a published
     * key only waits; it never makes the route unavailable for the others.
     */
    public fun groupFileRoute(groupId: String): HubFileRoute =
        if (port.usable && sealing != null && groupLinked(groupId)) HubFileRoute.READY else HubFileRoute.UNAVAILABLE

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
        return sendPayload(to, id, sendTime(sentAt), CHAT_FRAME, body, seal = true)
    }

    /**
     * The `sentAt` this device should stamp a hub message with (SEC-D15-02). A recipient refuses a delivery older than
     * the hub's retention, so a sender whose clock is days off would have every message refused. When the handshake
     * measured a difference of more than [HubContract.MESSAGE_AGE_GRACE_MS], the reading is moved onto the hub's clock;
     * smaller differences are left alone (they are inside the grace anyway and the local time stays the sender's).
     */
    private fun sendTime(sentAt: Long): Long {
        val offset = port.serverTimeOffsetMs ?: return sentAt
        if (offset > -HubContract.MESSAGE_AGE_GRACE_MS && offset < HubContract.MESSAGE_AGE_GRACE_MS) return sentAt
        return sentAt + offset
    }

    /**
     * Sends a group chat frame to one member through the hub. When [groupE2ee] is configured (HR10), sends ONLY
     * inside authenticated `GROUP_SEALED_V1` ciphertexts with pairwise `SEALED_V1` key distribution and fails closed
     * if any active member lacks verified keys. Without [groupE2ee], legacy readable group mode requires [contentViaHub].
     */
    public suspend fun sendGroup(to: String, id: String, sentAt: Long, frame: GroupWireFrame): Boolean {
        val e2ee = groupE2ee
        val body = GroupFrameCodec.encode(frame)
        val stamp = sendTime(sentAt)
        if (e2ee == null) {
            return sendPayload(to, id, stamp, GROUP_FRAME, body, seal = false)
        }
        if (!port.usable) return false
        val membership = groupMembershipProvider(frame.groupId) ?: return false
        val (activeMembers, rosterVersion) = membership
        return when (val result = e2ee.prepareGroupSend(
            groupId = frame.groupId,
            activeMembers = activeMembers,
            rosterVersion = rosterVersion,
            recipientDeviceId = to,
            msgId = id,
            sentAt = stamp,
            innerType = GROUP_FRAME,
            body = body,
            sealing = sealing,
        )) {
            is GroupSealResult.Blocked -> {
                _groupE2eeStates.update { it + (frame.groupId to result.state) }
                sealing?.let { setWaiting(to, waitReasonForStatus(it.statusFor(to))) }
                FlashLog.i("HUB", "group E2EE send blocked (fail-closed) group=${frame.groupId} reason=${result.reason}")
                false
            }
            is GroupSealResult.Ready -> {
                val state = GroupE2eeGroupState(
                    status = GroupE2eeCompatStatus.ENCRYPTED,
                    currentEpoch = result.epochKey.epoch,
                    activeMembers = activeMembers,
                )
                _groupE2eeStates.update { it + (frame.groupId to state) }
                val keyDist = result.keyDistForRecipient
                if (keyDist != null) {
                    val sealPort = sealing ?: return false
                    val keyDistId = "gk-${frame.groupId}-${result.epochKey.epoch}-${result.epochKey.keyId.take(10)}-$to"
                    val sealedKeyDist = sealPort.seal(
                        recipientDeviceId = to,
                        msgId = keyDistId,
                        sentAt = stamp,
                        innerType = GroupRelaySealing.KEY_DIST_INNER_TYPE,
                        body = GroupRelaySealing.encodeKeyDist(keyDist),
                    ) ?: return false
                    if (!port.send(to, keyDistId, HubSealing.ENVELOPE_TYPE, stamp, sealedKeyDist)) {
                        return false
                    }
                    e2ee.markKeyDistributed(frame.groupId, result.epochKey.epoch, result.epochKey.keyId, to)
                }
                port.send(to, id, GroupRelaySealing.ENVELOPE_TYPE, stamp, result.sealedEnvelope)
            }
        }
    }

    /**
     * Sends a hub file's ticket to [to], sealed (inner type [HUB_FILE]). Never readable: false when the ticket cannot be
     * sealed for them right now, and the caller keeps it queued.
     */
    public suspend fun sendHubFile(to: String, id: String, sentAt: Long, frame: HubFileFrame): Boolean {
        if (!port.usable || sealing == null) return false
        if (sealing.statusFor(to) != HubSealingStatus.AVAILABLE) {
            FlashLog.i("HUB", "file ticket not sealable now, kept id=${id.take(8)}")
            return false
        }
        // The stamped value must be the one the envelope is sealed over, or opening fails.
        val stamp = sendTime(sentAt)
        val sealed = sealing.seal(to, id, stamp, HUB_FILE, HubFileFrameCodec.encode(frame)) ?: return false
        return port.send(to, id, HubSealing.ENVELOPE_TYPE, stamp, sealed)
    }

    private suspend fun sendPayload(to: String, id: String, sentAt: Long, innerType: String, body: String, seal: Boolean): Boolean {
        if (!port.usable) return false
        val status = if (seal && sealing != null) sealing.statusFor(to) else HubSealingStatus.NOT_PUBLISHED
        if (status == HubSealingStatus.KEY_CHANGED) {
            // Not sealed and not readable either: the user has to decide about the new key first.
            FlashLog.i("HUB", "key change not confirmed, not sending id=$id")
            setWaiting(to, HubWaitReason.KEY_CHANGED)
            return false
        }
        if (status == HubSealingStatus.IDENTITY_MISMATCH) {
            FlashLog.w("HUB", "directory identity key contradicts the paired key, not sending id=$id")
            setWaiting(to, HubWaitReason.IDENTITY_MISMATCH)
            return false
        }
        if (status == HubSealingStatus.AVAILABLE) {
            // F65 of the 2026-10-22 review: the directory key verified its binding signature but could not produce an
            // envelope (unusable SPKI bytes, or signing failed). That is "cannot seal now", NOT "the peer has no
            // sealing key": the readable-content opt-in below is about a peer that never published one, so falling
            // through to it would put a message this user chose to seal on the hub in plaintext. Keep it queued.
            val sealed = sealing?.seal(to, id, sentAt, innerType, body)
            if (sealed == null) {
                FlashLog.w("HUB", "sealing key unusable, not sending readable id=${id.take(8)}")
                return false
            }
            return port.send(to, id, HubSealing.ENVELOPE_TYPE, sentAt, sealed)
        }
        return when {
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
        if (seenLock.withLock { (d.from to d.id) in seen }) {
            port.recvAck(d.id, d.from)
            return HubInboundOutcome.DUPLICATE
        }
        val outcome = process(d, sink)
        when (outcome) {
            HubInboundOutcome.STORED -> {
                remember(d.from, d.id)
                port.recvAck(d.id, d.from)
            }
            HubInboundOutcome.REJECTED -> port.recvAck(d.id, d.from)
            HubInboundOutcome.DUPLICATE, HubInboundOutcome.RETRY_LATER -> Unit
        }
        return outcome
    }

    private suspend fun remember(from: String, id: String) = seenLock.withLock {
        seen += from to id
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

        // 2. Freshness (SEC-D15-02). The dedupe above only knows ids this device still remembers: once a chat is
        // cleared, a rogue hub can replay an old envelope and it would look new. The sender's `sentAt` is inside the
        // sealed AAD, so the hub cannot move it without the envelope failing to open, and nothing the hub may still
        // hold is older than its own announced retention. This bound therefore holds for sealed deliveries; a
        // READABLE one (the user's explicit opt-in, and group frames) has no AAD binding its `sentAt`, so against a
        // lying hub it only bounds what the hub is willing to claim — the trust model for readable content already
        // accepts that the hub can read and alter it.
        if (HubFreshness.isStale(d.sentAt, timeSource.nowMs(), HubFreshness.limitMs(port.mailboxTtlMs))) {
            _droppedStale.update { it + 1 }
            return reject(d, "delivery is older than the hub's retention")
        }

        // 3. Content: pairwise sealed, group-epoch sealed, or readable only when the user opted in.
        val innerType: String
        val body: String
        var expectedGroupId: String? = null
        val sealed: Boolean
        when (d.type) {
            HubSealing.ENVELOPE_TYPE -> {
                sealed = true
                val opened = sealing?.open(d.from, d.id, d.sentAt, d.payload)
                if (opened == null) {
                    // A sender the directory cannot be asked about right now is not a forgery: keep it for the next delivery.
                    if (sealing != null && (standing(d.from) == HubPeerStanding.UNAVAILABLE || sealing.openFailureMayClear(d.from))) {
                        return HubInboundOutcome.RETRY_LATER
                    }
                    _droppedUnverified.update { it + 1 }
                    return reject(d, "sealed message did not verify")
                }
                innerType = opened.innerType
                body = opened.body
            }
            GroupRelaySealing.ENVELOPE_TYPE -> {
                sealed = true
                val e2ee = groupE2ee ?: return reject(d, "group E2EE not configured")
                val meta = GroupRelaySealing.peekMetadata(d.payload)
                    ?: run {
                        _droppedUnverified.update { it + 1 }
                        return reject(d, "malformed group E2EE envelope")
                    }
                if (!groupLinked(meta.groupId)) return reject(d, "group is not linked to the hub")
                val senderMember = groupMemberAllowed(meta.groupId, d.from)
                val localMember = groupMemberAllowed(meta.groupId, localDeviceId)
                when (val res = e2ee.openSealedGroup(
                    senderDeviceId = d.from,
                    expectedGroupId = meta.groupId,
                    msgId = d.id,
                    sentAt = d.sentAt,
                    payload = d.payload,
                    sealing = sealing,
                    isSenderActiveMember = senderMember,
                    isLocalActiveMember = localMember,
                )) {
                    is GroupOpenResult.Opened -> {
                        innerType = res.payload.innerType
                        body = res.payload.body
                        expectedGroupId = res.payload.groupId
                    }
                    is GroupOpenResult.MissingEpochKey -> {
                        return if (senderMember && localMember) {
                            HubInboundOutcome.RETRY_LATER
                        } else {
                            _droppedUnverified.update { it + 1 }
                            reject(d, "missing group key for non-active member")
                        }
                    }
                    GroupOpenResult.RetryUnavailableIdentity -> return HubInboundOutcome.RETRY_LATER
                    is GroupOpenResult.Rejected -> {
                        _droppedUnverified.update { it + 1 }
                        return reject(d, res.reason)
                    }
                }
            }
            else -> {
                sealed = false
                if (!contentViaHub()) {
                    _droppedPlain.update { it + 1 }
                    return reject(d, "readable hub content is switched off")
                }
                innerType = d.type
                body = d.payload
            }
        }

        // 4. Decode and route. The author named inside the frame must be the authenticated sender.
        return runCatching { route(d, innerType, body, sealed, expectedGroupId, sink) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            FlashLog.w("HUB", "inbound id=${d.id} not stored: ${e::class.simpleName}")
            HubInboundOutcome.RETRY_LATER
        }
    }

    private suspend fun route(
        d: HubInbound,
        innerType: String,
        body: String,
        sealed: Boolean,
        expectedGroupId: String?,
        sink: HubInboundSink,
    ): HubInboundOutcome {
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
            GroupRelaySealing.KEY_DIST_INNER_TYPE -> {
                val e2ee = groupE2ee
                if (!sealed || e2ee == null) return reject(d, "group key distribution must arrive sealed")
                val dist = GroupRelaySealing.decodeKeyDist(body)
                    ?: return reject(d, "malformed group key distribution payload")
                if (!groupLinked(dist.groupId)) return reject(d, "group is not linked to the hub")
                val senderAllowed = groupMemberAllowed(dist.groupId, d.from)
                val currentMembership = groupMembershipProvider(dist.groupId)
                return when (val outcome = e2ee.acceptKeyDist(
                    fromDeviceId = d.from,
                    dist = dist,
                    senderAllowedInGroup = senderAllowed,
                    activeMembersNow = currentMembership?.first,
                    currentRosterVersion = currentMembership?.second,
                )) {
                    GroupKeyAcceptOutcome.ACCEPTED_CANONICAL,
                    GroupKeyAcceptOutcome.ACCEPTED_DECRYPT_ONLY,
                    GroupKeyAcceptOutcome.DUPLICATE -> HubInboundOutcome.STORED
                    GroupKeyAcceptOutcome.REJECTED_UNAUTHORIZED,
                    GroupKeyAcceptOutcome.REJECTED_STALE_ROSTER,
                    GroupKeyAcceptOutcome.REJECTED_CONFLICT -> reject(d, "group key dist refused ($outcome)")
                }
            }
            GROUP_FRAME, LEGACY_GROUP -> {
                if (groupE2ee != null && !sealed) {
                    _droppedPlain.update { it + 1 }
                    return reject(d, "unencrypted group frame rejected on E2EE channel")
                }
                val frame = GroupFrameCodec.decode(body) ?: return reject(d, "unreadable group frame")
                val allowed = frame is GroupWireFrame.Message || frame is GroupWireFrame.Receipt ||
                    frame is GroupWireFrame.Read || frame is GroupWireFrame.DeleteForEveryone
                if (!allowed) return reject(d, "group frame kind is not allowed through the hub")
                if (frame.from != d.from) return reject(d, "frame author is not the sender")
                if (expectedGroupId != null && frame.groupId != expectedGroupId) {
                    return reject(d, "inner group id does not match outer sealed envelope")
                }
                if (!groupLinked(frame.groupId)) return reject(d, "group is not linked to the hub")
                sink.group(frame, d.from)
                return HubInboundOutcome.STORED
            }
            HUB_FILE -> {
                // The ticket holds the file's key: a readable one is refused even with readable content switched on.
                if (!sealed) return reject(d, "a file ticket must arrive sealed")
                val frame = when (val r = HubFileFrameCodec.decode(body)) {
                    is HubFileFrameCodec.Result.Frame -> r.frame
                    is HubFileFrameCodec.Result.Invalid -> return reject(d, "file ticket field ${r.reason} is invalid")
                    null -> return reject(d, "unreadable file ticket")
                }
                if (frame.senderId != d.from) return reject(d, "frame author is not the sender")
                if (!sink.hubFile(frame, d.from)) return reject(d, "hub files are not taken on this device")
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

        /** Inner type of a sealed hub file ticket ([HubFileFrame]); never a relay envelope type of its own. */
        public const val HUB_FILE: String = "HUB_FILE"

        /** Accepted inbound only: the pre-hub-v2 plain text type. */
        public const val LEGACY_TEXT: String = "MSG_TEXT"

        /** Accepted inbound only: the pre-hub-v2 group type (now requires a decodable [GroupWireFrame]). */
        public const val LEGACY_GROUP: String = "GROUP_MSG"

        public const val MAX_SEEN: Int = 2048
        public const val MAX_TRACKED: Int = 256
    }
}

private val NO_PENDING_KEY_CHANGES: StateFlow<Map<String, HubSealingPin>> = MutableStateFlow(emptyMap())
