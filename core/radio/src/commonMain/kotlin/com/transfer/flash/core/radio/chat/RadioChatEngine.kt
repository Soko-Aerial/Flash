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
 *            'delivered' ONLY when cryptographic ACK returns.
 * - Rule 3d: Inbound frames authenticated/decrypted, replay-checked, deduplicated, and reassembled;
 *            unknown tags and failed auth are silently dropped and counted in probes (zero response on air).
 * - Rule 3e: Multi-lane priority dispatch: EMERGENCY > TEXT > ACK > LOCATION.
 * - Rule 3f: Gateway off/disconnected: refused with NotConnected ('radio not connected').
 * - Rule 4 (Task 4): Profile M (only ciphertext leaves, gated by authorization confirmation) vs
 *                    Profile A (signed clear text with operator callsign in AX.25 address).
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
        if (session.hasPeer(peerId)) return true
        if (pairingAdapter != null && pairingAdapter.isPairedForRadio(peerId)) {
            return pairingAdapter.registerPeer(session, peerId)
        }
        return false
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

        val textBytes = text.encodeToByteArray()
        val maxSegmentBytes = RadioWire.maxBodyPerSegment(infoBudget) * 16
        if (textBytes.size > maxSegmentBytes) {
            return RadioChatSendOutcome.RefusedTooLong("Message is too long to send by radio (maximum $maxSegmentBytes bytes)")
        }

        val nowMs = timeSource.nowMs()
        val destAddr: Ax25Address
        val srcAddr: Ax25Address
        val encodeResult: RadioEncode

        sessionMutex.withLock {
            when (profile) {
                RadioSecurityProfile.PROFILE_M_ENCRYPTED -> {
                    encodeResult = session.encode(recipientId, RadioKind.TEXT, textBytes, nowMs)
                    destAddr = Ax25Address.parseOrNull("FLASH") ?: Ax25Address("FLASH", 0)
                    srcAddr = session.stationLabel(recipientId, nowMs)
                }
                RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED -> {
                    encodeResult = session.encodeSigned(RadioKind.TEXT, textBytes, nowMs)
                    destAddr = Ax25Address.parseOrNull("FLASH") ?: Ax25Address("FLASH", 0)
                    srcAddr = auth?.operatorCallsign?.let { Ax25Address.parseOrNull(it) } ?: Ax25Address("NOCALL", 0)
                }
            }
        }

        when (encodeResult) {
            is RadioEncode.Refused -> {
                return RadioChatSendOutcome.Failed("Radio encoding refused: ${encodeResult.reason}")
            }
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

                val priority = if (emergency) RadioChatPriority.EMERGENCY else RadioChatPriority.TEXT
                val deferred = CompletableDeferred<TxResult>()

                val task = PriorityTask(
                    priority = priority,
                    ax25Frames = ax25Frames,
                    label = if (emergency) "emg-$messageId" else "txt-$messageId",
                    firstCounter = encodeResult.firstCounter,
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
                            pendingAcks[encodeResult.firstCounter] = PendingOutbound(messageId, recipientId, sentAtMs)
                        }
                        RadioChatSendOutcome.SentWaitingAck(encodeResult.firstCounter)
                    }
                    is TxResult.LinkDown -> {
                        RadioChatSendOutcome.NotConnected("Radio link went down during transmit")
                    }
                    is TxResult.QueueFull -> {
                        RadioChatSendOutcome.Failed("Radio driver transmit queue full")
                    }
                }
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
                onCompleted = {},
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
}
