@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.radio.chat

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.RadioAuthorisationGate
import com.transfer.flash.core.radio.connect.RadioAuthorisationStore
import com.transfer.flash.core.radio.connect.RadioSecurityProfile
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.driver.TxResult
import com.transfer.flash.core.radio.kiss.Ax25Address
import com.transfer.flash.core.radio.kiss.Ax25FrameCodec
import com.transfer.flash.core.radio.kiss.Ax25Role
import com.transfer.flash.core.radio.wire.RadioDrop
import com.transfer.flash.core.radio.wire.RadioEncode
import com.transfer.flash.core.radio.wire.RadioIngest
import com.transfer.flash.core.radio.wire.RadioKind
import com.transfer.flash.core.radio.wire.RadioPairingAdapter
import com.transfer.flash.core.radio.wire.RadioSession
import com.transfer.flash.core.radio.wire.RadioWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Domain engine for radio text messaging, delivery tracking, multi-lane priority pacing,
 * and cryptographic ACKs over hardware KISS TNC (Plan R §7 / Phase R3).
 *
 * Implements Plan R rules:
 * - Rule 3a: Text payloads only, segmented up to 3,008 bytes (16 segments * 188 bytes); longer refused.
 * - Rule 3c: Delivery state 'sent by radio, waiting' when driver confirms transmit;
 *            'delivered' ONLY when cryptographic ACK returns. A retransmitted frame is re-ACKed (ERROR-870).
 * - Rule 3d: Inbound frames authenticated/decrypted, replay-checked, deduplicated, and reassembled;
 *            unknown tags and failed auth are silently dropped and counted in probes (zero response on air).
 * - Rule 3e: Multi-lane priority dispatch: EMERGENCY > TEXT > ACK > LOCATION.
 * - Rule 3f: Gateway off/disconnected: refused with NotConnected ('radio not connected').
 * - Rule 4 (Task 4): Profile M (only ciphertext leaves, gated by authorization confirmation) is the only profile
 *                    chat can use. Profile A (signed clear text) is refused, because a Flash station cannot verify
 *                    it yet and would drop it without an ACK (ERROR-869).
 */
public class RadioChatEngine(
    public val localDeviceId: String,
    private val scope: CoroutineScope,
    private val driver: KissTncDriver,
    private val session: RadioSession,
    private val pairingAdapter: RadioPairingAdapter? = null,
    private val authorisationStore: RadioAuthorisationStore? = null,
    private val authorisationGate: RadioAuthorisationGate = RadioAuthorisationGate(),
    private val timeSource: FlashTimeSource = SystemTimeSource,
    private val infoBudget: Int = RadioWire.DEFAULT_INFO_BUDGET,
) : AutoCloseable {

    private val sessionMutex = Mutex()
    private val dedupCache = RadioDedupCache(maxSize = 1000)

    private val _isEnabled = MutableStateFlow(true)
    public val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentProfile = MutableStateFlow(RadioSecurityProfile.PROFILE_M_ENCRYPTED)
    public val currentProfile: StateFlow<RadioSecurityProfile> = _currentProfile.asStateFlow()

    private val _inboundMessages = MutableSharedFlow<RadioChatInbound>(extraBufferCapacity = 64)
    public val inboundMessages: SharedFlow<RadioChatInbound> = _inboundMessages.asSharedFlow()

    private val _deliveryReceipts = MutableSharedFlow<RadioChatReceipt>(extraBufferCapacity = 64)
    public val deliveryReceipts: SharedFlow<RadioChatReceipt> = _deliveryReceipts.asSharedFlow()

    public val isConnected: Boolean get() = driver.state.value is TncLinkState.Connected

    public val status: StateFlow<RadioChatStatus> = combine(_isEnabled, driver.state) { enabled, linkState ->
        if (!enabled) {
            RadioChatStatus.OFF
        } else {
            when (linkState) {
                is TncLinkState.Connected -> RadioChatStatus.CONNECTED
                is TncLinkState.Connecting -> RadioChatStatus.CONNECTING
                is TncLinkState.Waiting -> RadioChatStatus.DISCONNECTED
                is TncLinkState.Stopped -> RadioChatStatus.OFF
                is TncLinkState.Idle -> RadioChatStatus.DISCONNECTED
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, RadioChatStatus.DISCONNECTED)

    private class PendingOutbound(
        val messageId: String,
        val recipientId: String,
        val sentAtMs: Long,
    )

    private val pendingAcks = HashMap<Long, PendingOutbound>()
    private val pendingMutex = Mutex()

    /**
     * One transmit's encoded frames, kept so a retry puts the SAME counters back on the air.
     *
     * ERROR-869 (2026-10-28, two radios): the receiving station names an inbound text `rad-<first counter>`,
     * so re-encoding a retry (a fresh counter) made the same message a NEW row on the other station — one
     * text arrived as three bubbles. A retry therefore re-sends the identical frame.
     *
     * That only helps if the receiver still answers it: the replay window sees the counter again, so
     * `RadioSession` reports `RadioIngest.Duplicate` instead of dropping it silently, and this engine re-ACKs
     * the message (ERROR-870). Without that, a lost ACK left the message "sent by radio, waiting" until the
     * outbox gave up, while the peer had it all along.
     */
    private class SentFrames(
        val frames: List<ByteArray>,
        val firstCounter: Long,
        val bytes: Int,
        val atMs: Long,
    )

    private val lastSentFrames = LinkedHashMap<String, SentFrames>()

    private class PriorityTask(
        val priority: RadioChatPriority,
        val ax25Frames: List<ByteArray>,
        val label: String,
        val firstCounter: Long?,
        val onCompleted: (TxResult) -> Unit,
    )

    // Four priority queues (Plan R §7 rule 3e)
    private val laneEmergency = Channel<PriorityTask>(Channel.UNLIMITED)
    private val laneText = Channel<PriorityTask>(Channel.UNLIMITED)
    private val laneAck = Channel<PriorityTask>(Channel.UNLIMITED)
    private val laneLocation = Channel<PriorityTask>(Channel.UNLIMITED)

    private var pumpJob: Job? = null
    private var rxCollectorJob: Job? = null

    init {
        startWorkers()
    }

    private fun startWorkers() {
        pumpJob = scope.launch {
            priorityPumpLoop()
        }
        rxCollectorJob = scope.launch {
            driver.received.collect { tncRx ->
                handleInboundFrame(tncRx)
            }
        }
    }

    public fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    public fun setProfile(profile: RadioSecurityProfile) {
        _currentProfile.value = profile
    }

    /**
     * Checks if [peerId] is known and radio-reachable (trusted with a 32-byte session key).
     */
    public fun isPeerRadioReachable(peerId: String): Boolean {
        if (pairingAdapter == null) return session.hasPeer(peerId)
        // A peer whose pairing was revoked is dropped from the session, not kept for the rest of the connection.
        if (!pairingAdapter.isPairedForRadio(peerId)) {
            session.removePeer(peerId)
            return false
        }
        return session.hasPeer(peerId) || pairingAdapter.registerPeer(session, peerId)
    }

    /**
     * Peer ids this device can seal to right now: a Flash pairing that holds a 32-byte session key,
     * which is what a radio frame's directional keys derive from (ADR-228).
     *
     * Deliberately ignores the gateway: the caller decides whether a link is up. Presentation only —
     * the header's "Radio only" state reads it, and nothing on the send path depends on it.
     */
    public fun radioPairedPeerIds(): Set<String> {
        val adapter = pairingAdapter ?: return emptySet()
        return adapter.trustedPeerIds().filterTo(HashSet()) { adapter.isPairedForRadio(it) }
    }

    private var lastPeerSyncMs: Long = Long.MIN_VALUE
    private val syncedPeers = HashSet<String>()

    /**
     * Registers every paired peer in the session so frames from a peer we have not sent to yet are accepted
     * (ERROR-703), and drops peers whose pairing is gone. At most once per [PEER_SYNC_INTERVAL_MS]; call under
     * [sessionMutex].
     */
    private fun syncPeersIfDue(nowMs: Long) {
        val adapter = pairingAdapter ?: return
        if (lastPeerSyncMs != Long.MIN_VALUE && nowMs - lastPeerSyncMs < PEER_SYNC_INTERVAL_MS) return
        lastPeerSyncMs = nowMs
        val current = HashSet<String>()
        for (id in adapter.trustedPeerIds()) if (adapter.registerPeer(session, id)) current += id
        for (gone in syncedPeers - current) session.removePeer(gone)
        syncedPeers.clear()
        syncedPeers.addAll(current)
    }

    /**
     * Submits a text message for radio transmission.
     * Segments per RADIO-WIRE-FORMAT.md up to 3,008 bytes; refuses longer.
     * Enforces Profile M authorization gate.
     */
    public suspend fun sendText(
        recipientId: String,
        messageId: String,
        text: String,
        sentAtMs: Long,
        emergency: Boolean = false,
    ): RadioChatSendOutcome {
        if (!_isEnabled.value || !isConnected) {
            return RadioChatSendOutcome.NotConnected("radio not connected")
        }

        if (!isPeerRadioReachable(recipientId)) {
            return RadioChatSendOutcome.RefusedUnreachable("Peer $recipientId is not paired for radio")
        }

        val profile = _currentProfile.value
        val auth = authorisationStore?.load()
        try {
            authorisationGate.requireProfileAllowed(profile, auth)
        } catch (e: Exception) {
            return RadioChatSendOutcome.RefusedAuthorization("Profile M unconfirmed: ${e.message}")
        }

        // ERROR-869 (2026-10-28, two radios): Profile A is signed CLEAR text, and a Flash station cannot verify
        // it — `RadioSession.ingestSigned` needs the sender's identity public key registered with
        // `addBroadcastSender`, and a Flash pairing stores only that key's fingerprint. The receiver therefore
        // drops such a frame as `SIGNED_NOT_ENABLED`, silently, with no ACK, and the sender's bubble says
        // "waiting for acknowledgement" for ever (that is exactly what the desktop did on 2026-10-28 after the
        // phone restarted back to the default profile). Refuse here, where the operator can read why.
        //
        // Named explicitly because the profile - not the authorisation - is what blocks the send: an operator
        // who taps "Confirm radio use" while the radio sits on Profile A would otherwise loop on this sentence.
        if (profile != RadioSecurityProfile.PROFILE_M_ENCRYPTED) {
            return RadioChatSendOutcome.RefusedAuthorization(
                "This radio is on Profile A (signed clear text), which Flash cannot deliver. " +
                    "Switch the radio to Profile M (encrypted) on the Radio screen, then send again.",
            )
        }

        val textBytes = text.encodeToByteArray()
        val maxSegmentBytes = RadioWire.maxBodyPerSegment(infoBudget) * 16
        if (textBytes.size > maxSegmentBytes) {
            return RadioChatSendOutcome.RefusedTooLong("Message is too long to send by radio (maximum $maxSegmentBytes bytes)")
        }

        val nowMs = timeSource.nowMs()
        // ERROR-869: a retry is the same frame again, not a re-encoded one (see [SentFrames]).
        val repeat = pendingMutex.withLock {
            lastSentFrames[messageId]?.takeIf { it.bytes == textBytes.size && nowMs - it.atMs <= FRAME_REUSE_MS }
        }
        val ax25Frames: List<ByteArray>
        val firstCounter: Long
        if (repeat != null) {
            ax25Frames = repeat.frames
            firstCounter = repeat.firstCounter
        } else {
            val destAddr: Ax25Address
            val srcAddr: Ax25Address
            val encodeResult: RadioEncode

            sessionMutex.withLock {
                // Profile A is refused above, so only the encrypted profile reaches the encoder.
                encodeResult = session.encode(recipientId, RadioKind.TEXT, textBytes, nowMs)
                destAddr = Ax25Address.parseOrNull("FLASH") ?: Ax25Address("FLASH", 0)
                srcAddr = session.stationLabel(recipientId, nowMs)
            }

            when (encodeResult) {
                is RadioEncode.Refused -> {
                    return RadioChatSendOutcome.Failed("Radio encoding refused: ${encodeResult.reason}")
                }
                is RadioEncode.Frames -> {
                    ax25Frames = encodeResult.frames.map { infoBytes ->
                        Ax25FrameCodec.encodeUi(
                            destination = destAddr,
                            source = srcAddr,
                            info = infoBytes,
                            pid = 0xF0,
                            role = Ax25Role.COMMAND,
                        )
                    }
                    firstCounter = encodeResult.firstCounter
                }
            }
        }

        val priority = if (emergency) RadioChatPriority.EMERGENCY else RadioChatPriority.TEXT
        val deferred = CompletableDeferred<TxResult>()

        val task = PriorityTask(
            priority = priority,
            ax25Frames = ax25Frames,
            label = if (emergency) "emg-$messageId" else "txt-$messageId",
            firstCounter = firstCounter,
            onCompleted = { deferred.complete(it) },
        )

        if (priority == RadioChatPriority.EMERGENCY) {
            laneEmergency.send(task)
        } else {
            laneText.send(task)
        }

        val txOutcome = deferred.await()
        return when (txOutcome) {
            is TxResult.Sent -> {
                pendingMutex.withLock {
                    pendingAcks[firstCounter] = PendingOutbound(messageId, recipientId, sentAtMs)
                    lastSentFrames[messageId] = SentFrames(ax25Frames, firstCounter, textBytes.size, nowMs)
                    while (lastSentFrames.size > MAX_REMEMBERED_FRAMES) {
                        val oldest = lastSentFrames.keys.firstOrNull() ?: break
                        lastSentFrames.remove(oldest)
                    }
                }
                FlashProbe.emit(
                    "radio.tx.text",
                    "peer" to FlashProbe.short(recipientId),
                    "segments" to ax25Frames.size,
                    "bytes" to textBytes.size,
                    "lane" to if (emergency) "emergency" else "text",
                    "retry" to (repeat != null),
                )
                RadioChatSendOutcome.SentWaitingAck(firstCounter)
            }
                    is TxResult.LinkDown -> {
                        FlashProbe.emit(
                            "radio.tx.fail",
                            "peer" to FlashProbe.short(recipientId),
                            "reason" to "link_down",
                        )
                        RadioChatSendOutcome.NotConnected("Radio link went down during transmit")
                    }
                    is TxResult.QueueFull -> {
                        FlashProbe.emit(
                            "radio.tx.fail",
                            "peer" to FlashProbe.short(recipientId),
                            "reason" to "queue_full",
                        )
                        RadioChatSendOutcome.Failed("Radio driver transmit queue full")
                    }
        }
    }

    /**
     * Submits a location beacon in the LOCATION priority lane (Wave 3 reservation).
     */
    public suspend fun sendLocationReport(
        recipientId: String,
        payload: ByteArray,
        emergency: Boolean = false,
    ): RadioChatSendOutcome {
        if (!_isEnabled.value || !isConnected) {
            return RadioChatSendOutcome.NotConnected("radio not connected")
        }

        val nowMs = timeSource.nowMs()
        val encodeResult: RadioEncode
        val destAddr: Ax25Address
        val srcAddr: Ax25Address

        sessionMutex.withLock {
            encodeResult = session.encode(recipientId, RadioKind.BEACON, payload, nowMs)
            destAddr = Ax25Address.parseOrNull("FLASH") ?: Ax25Address("FLASH", 0)
            srcAddr = session.stationLabel(recipientId, nowMs)
        }

        when (encodeResult) {
            is RadioEncode.Refused -> return RadioChatSendOutcome.Failed(encodeResult.reason)
            is RadioEncode.Frames -> {
                val ax25Frames = encodeResult.frames.map { infoBytes ->
                    Ax25FrameCodec.encodeUi(
                        destination = destAddr,
                        source = srcAddr,
                        info = infoBytes,
                        pid = 0xF0,
                        role = Ax25Role.COMMAND,
                    )
                }
                val deferred = CompletableDeferred<TxResult>()
                val priority = if (emergency) RadioChatPriority.EMERGENCY else RadioChatPriority.LOCATION
                val task = PriorityTask(
                    priority = priority,
                    ax25Frames = ax25Frames,
                    label = "loc-$recipientId",
                    firstCounter = encodeResult.firstCounter,
                    onCompleted = { deferred.complete(it) },
                )
                if (emergency) laneEmergency.send(task) else laneLocation.send(task)
                val txOutcome = deferred.await()
                return if (txOutcome is TxResult.Sent) {
                    RadioChatSendOutcome.SentWaitingAck(encodeResult.firstCounter)
                } else {
                    RadioChatSendOutcome.Failed("Location send failed")
                }
            }
        }
    }

    private suspend fun priorityPumpLoop() {
        while (scope.isActive) {
            // Strict priority select order: EMERGENCY > TEXT > ACK > LOCATION (Plan R §7 rule 3e)
            val task = select<PriorityTask> {
                laneEmergency.onReceive { it }
                laneText.onReceive { it }
                laneAck.onReceive { it }
                laneLocation.onReceive { it }
            }

            var lastResult: TxResult = TxResult.LinkDown
            for (frame in task.ax25Frames) {
                lastResult = driver.sendAx25(frame, label = task.label)
                if (lastResult !is TxResult.Sent) {
                    break
                }
            }
            task.onCompleted(lastResult)
        }
    }

    private suspend fun handleInboundFrame(tncRx: com.transfer.flash.core.radio.driver.TncRx) {
        val ax25 = tncRx.ax25 ?: return
        if (ax25.info.isEmpty()) return
        val nowMs = timeSource.nowMs()

        val ingestResult: RadioIngest = sessionMutex.withLock {
            syncPeersIfDue(nowMs)
            session.ingest(ax25.info, nowMs)
        }

        when (ingestResult) {
            is RadioIngest.Dropped -> {
                when (ingestResult.reason) {
                    RadioDrop.NOT_FLASH -> {
                        // Silent: not our traffic
                    }
                    RadioDrop.UNKNOWN_SENDER, RadioDrop.AUTH_FAILED -> {
                        // Rule 3d: dropped silently, no response on air (would give a stranger a signal)
                        FlashProbe.emit("radio.rx.dropped", "reason" to ingestResult.reason.name)
                    }
                    else -> {
                        FlashProbe.emit("radio.rx.dropped", "reason" to ingestResult.reason.name)
                    }
                }
            }
            is RadioIngest.Duplicate -> {
                // ERROR-870: the sender put this frame on the air again, which on this link means its ACK was lost.
                // ACK it again so the message stops retrying (the outbox gives up after 30 minutes otherwise), and
                // store nothing: the message is already here. RadioSession only reports a duplicate segment when
                // the message completed, so an incomplete message is never acknowledged.
                if (ingestResult.kind == RadioKind.TEXT) {
                    queueAckFrame(ingestResult.peerId, ingestResult.firstCounter, nowMs)
                }
                FlashProbe.emit(
                    "radio.rx.retransmit",
                    "peer" to FlashProbe.short(ingestResult.peerId),
                    "acked" to (ingestResult.kind == RadioKind.TEXT),
                    "signed" to ingestResult.signed,
                )
            }
            is RadioIngest.Partial -> {
                // Segment recorded in reassembler; awaiting remaining segments
            }
            is RadioIngest.Delivered -> {
                when (ingestResult.kind) {
                    RadioKind.ACK -> {
                        if (ingestResult.body.size >= 4) {
                            val b = ingestResult.body
                            val ackedCounter = ((b[0].toLong() and 0xFF) shl 24) or
                                ((b[1].toLong() and 0xFF) shl 16) or
                                ((b[2].toLong() and 0xFF) shl 8) or
                                (b[3].toLong() and 0xFF)

                            val pending = pendingMutex.withLock {
                                pendingAcks.remove(ackedCounter)
                            }
                            if (pending != null) {
                                FlashProbe.emit(
                                    "radio.rx.ack",
                                    "peer" to FlashProbe.short(ingestResult.peerId),
                                    "message" to FlashProbe.short(pending.messageId),
                                    "rttMs" to (nowMs - pending.sentAtMs),
                                )
                                _deliveryReceipts.emit(
                                    RadioChatReceipt(
                                        messageId = pending.messageId,
                                        recipientDeviceId = pending.recipientId,
                                        deliveredAtMs = nowMs,
                                    ),
                                )
                            }
                        }
                    }
                    RadioKind.TEXT -> {
                        val dedupKey = "${ingestResult.peerId}:${ingestResult.firstCounter}"
                        val isDuplicate = dedupCache.isDuplicate(dedupKey)

                        // Transmit cryptographic ACK back to peer in the ACK priority lane
                        queueAckFrame(ingestResult.peerId, ingestResult.firstCounter, nowMs)

                        FlashProbe.emit(
                            "radio.rx.text",
                            "peer" to FlashProbe.short(ingestResult.peerId),
                            "bytes" to ingestResult.body.size,
                            "duplicate" to isDuplicate,
                            "signed" to ingestResult.signed,
                        )

                        if (!isDuplicate) {
                            dedupCache.markSeen(dedupKey)
                            val messageText = ingestResult.body.decodeToString()
                            _inboundMessages.emit(
                                RadioChatInbound(
                                    senderDeviceId = ingestResult.peerId,
                                    messageId = "rad-${ingestResult.firstCounter}",
                                    text = messageText,
                                    sentAtMs = nowMs,
                                    senderCallsign = if (ingestResult.signed) ax25.source.toString() else null,
                                ),
                            )
                        }
                    }
                    RadioKind.BEACON -> {
                        // Reserved for Wave 3 location beacon handling
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun queueAckFrame(peerId: String, firstCounter: Long, nowMs: Long) {
        val ackEncode: RadioEncode = sessionMutex.withLock {
            session.encodeAck(peerId, firstCounter, nowMs)
        }
        if (ackEncode is RadioEncode.Frames) {
            val destAddr = Ax25Address.parseOrNull("FLASH") ?: Ax25Address("FLASH", 0)
            val srcAddr = sessionMutex.withLock { session.stationLabel(peerId, nowMs) }
            val ax25Frames = ackEncode.frames.map { infoBytes ->
                Ax25FrameCodec.encodeUi(
                    destination = destAddr,
                    source = srcAddr,
                    info = infoBytes,
                    pid = 0xF0,
                    role = Ax25Role.RESPONSE,
                )
            }
            val task = PriorityTask(
                priority = RadioChatPriority.ACK,
                ax25Frames = ax25Frames,
                label = "ack-$firstCounter",
                firstCounter = null,
                onCompleted = { result ->
                    if (result is TxResult.Sent) {
                        FlashProbe.emit("radio.tx.ack", "peer" to FlashProbe.short(peerId))
                    }
                },
            )
            laneAck.send(task)
        }
    }

    override fun close() {
        pumpJob?.cancel()
        rxCollectorJob?.cancel()
        laneEmergency.close()
        laneText.close()
        laneAck.close()
        laneLocation.close()
    }

    private companion object {
        /** How often the paired-peer set is re-read from the trust store while frames arrive. */
        const val PEER_SYNC_INTERVAL_MS: Long = 10_000L

        /**
         * How long a sent message's encoded frames are reusable for a retry (ERROR-869). The outbox re-arms a
         * radio send after [RADIO_RESEND_MS] (25 s) at most, so five minutes is far more than a retry needs and
         * short enough that a stale station label never matters.
         */
        const val FRAME_REUSE_MS: Long = 5 * 60 * 1000L

        /** Bounded: only the most recent sends keep their frames for a retry. */
        const val MAX_REMEMBERED_FRAMES: Int = 64
    }
}
