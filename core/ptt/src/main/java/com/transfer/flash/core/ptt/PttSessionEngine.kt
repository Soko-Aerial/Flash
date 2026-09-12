package com.transfer.flash.core.ptt

import android.util.Log
import com.transfer.flash.core.messaging.protocol.PttFrameCodec
import com.transfer.flash.core.messaging.protocol.PttPingFrame
import com.transfer.flash.core.messaging.protocol.PttAudioFrame
import com.transfer.flash.core.messaging.protocol.PttSessionCodec
import com.transfer.flash.core.messaging.protocol.PttSessionFrame
import com.transfer.flash.core.messaging.ptt.PttAudioLevel
import com.transfer.flash.core.messaging.ptt.PttEndReason
import com.transfer.flash.core.messaging.ptt.PttFloorEffect
import com.transfer.flash.core.messaging.ptt.PttFloorEvent
import com.transfer.flash.core.messaging.ptt.PttFloorMachine
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.core.messaging.ptt.PttTransition
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Live PTT voice-session driver (ADR-032, Phase 1).
 *
 * Owns one [PttFloorMachine] state plus everything the pure machine cannot hold:
 * capture/playout instances, member snapshot, heartbeat/RTT bookkeeping, session loops,
 * and transport fan-out. The machine stays the single decider — every state change goes
 * through [processLocked], and every returned effect is executed here.
 *
 * Threading: entry points are safe from ANY thread (main, net readers, audio threads).
 * Reductions and lifecycle effects serialize on [commandMutex]; audio bytes take a lock-free
 * state-snapshot + drop-oldest-channel path; capture/playout run on their own threads. Session
 * loops (tick, heartbeat, audio sender) are children of [sessionJobs], launched on leaving Idle
 * and cancelled on return — never a perpetual timer (EXP-015).
 *
 * Phase 1 executes audio + wire effects fully; later phases expose [notices], the
 * role-specific foreground service, and the state-driven overlay.
 */
public class PttSessionEngine(
    private val localId: () -> String?,
    private val localName: () -> String?,
    private val isTrustedPeer: (String) -> Boolean,
    private val snapshotMembers: () -> List<String>,
    private val sendControl: (peerId: String, text: String) -> Boolean,
    private val sendAudio: (peerId: String, bytes: ByteArray) -> Unit,
    private val hasMicPermission: () -> Boolean,
    private val isCallActive: () -> Boolean,
    private val audioRateHz: () -> Int,
) : FlashPtt {
    private data class OutPacket(val sessionId: String, val pcm: ByteArray, val captureTsMs: Long)

    private data class SessionJobKey(val sessionId: String, val role: PttRole)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()

    @Volatile
    private var floor: PttFloorState = PttFloorState.Idle

    private val _state = MutableStateFlow<PttFloorState>(PttFloorState.Idle)
    override public val state: StateFlow<PttFloorState> = _state.asStateFlow()

    private val _stats = MutableStateFlow<PttSessionStats?>(null)
    override public val stats: StateFlow<PttSessionStats?> = _stats.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override public val notices: SharedFlow<String> = _notices.asSharedFlow()

    /**
     * Engine-owned so a host cannot inject a second, divergent ping pipeline (the previous
     * constructor-injected `MutableSharedFlow` seam invited exactly that: the app host kept its own
     * decode/dedup/fan-out next to this one). [onInboundText] is the only writer.
     */
    private val _pings = MutableSharedFlow<PttPingEvent>(extraBufferCapacity = 16)
    override public val pings: Flow<PttPingEvent> = _pings.asSharedFlow()

    private val seenPingEventIds =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var capture: PttCapture? = null

    @Volatile
    private var playout: PttPlayout? = null

    // Session fields below are written on engine-scope coroutines and read from session
    // loops, audio threads and UI observers. Correctness-bearing floor changes stay in the
    // command lane; volatile transport/telemetry snapshots may lag by one tick only.
    @Volatile
    private var members: List<String> = emptyList()

    @Volatile
    private var holderId: String? = null

    @Volatile
    private var sessionRateHz: Int = 16000

    @Volatile
    private var sessionPacketMs: Int = 20

    @Volatile
    private var sessionJobs: Job? = null

    @Volatile
    private var sessionJobKey: SessionJobKey? = null
    private val hbLock = Any()
    private var hbSeq: Long = 0L
    private val sentHb = java.util.concurrent.ConcurrentHashMap<Pair<Long, String>, Long>()

    @Volatile
    private var lastRttMs: Long? = null

    @Volatile
    private var lastHbRttMs: Long? = null

    @Volatile
    private var lastCaptureAmp: Float = 0f

    @Volatile
    private var voiceNoteLeaseId: String? = null

    @Volatile
    private var pttStartPending: Boolean = false
    private val voiceNoteGate = Any()

    private val audioOut = Channel<OutPacket>(64, BufferOverflow.DROP_OLDEST)

    /**
     * Hardware-press / adb entry. Main-safe: slow-path denials answer synchronously,
     * the toggle itself runs async. Debouncing lives with the caller.
     */
    override public fun onPttButton(): PttPressOutcome {
        if (isCallActive()) {
            Log.w(TAG, "PTT press refused: call active")
            return PttPressOutcome.CALL_ACTIVE
        }
        if (voiceNoteLeaseId != null) {
            Log.w(TAG, "PTT press refused: voice note active")
            scope.launch { _notices.emit("Voice recording in progress — PTT unavailable") }
            return PttPressOutcome.VOICE_NOTE_ACTIVE
        }
        if (floor is PttFloorState.Idle) {
            if (snapshotMembers().isEmpty()) return PttPressOutcome.NO_PEERS
            if (!hasMicPermission()) {
                Log.w(TAG, "PTT press refused: no mic permission")
                scope.launch { _notices.emit("Microphone permission is needed to talk") }
                return PttPressOutcome.NO_MIC
            }
            synchronized(voiceNoteGate) {
                if (voiceNoteLeaseId != null) return PttPressOutcome.VOICE_NOTE_ACTIVE
                if (pttStartPending) return PttPressOutcome.ACCEPTED
                pttStartPending = true
            }
        }
        scope.launch {
            try {
                dispatchIo(
                    PttFloorEvent.LocalPress(
                        nowMs = nowMs(),
                        sessionId = UUID.randomUUID().toString(),
                        holderId = localId().orEmpty(),
                    ),
                )
            } finally {
                pttStartPending = false
            }
        }
        return PttPressOutcome.ACCEPTED
    }

    override public fun sendPing(): Boolean {
        val self = localId() ?: return false
        val recipients = snapshotMembers().filter { it != self && isTrustedPeer(it) }
        if (recipients.isEmpty()) return false
        val ping = PttFrameCodec.encode(
            PttPingFrame(
                eventId = UUID.randomUUID().toString(),
                from = self,
                senderName = localName() ?: "Peer",
                sentAt = System.currentTimeMillis(),
            ),
        )
        // Every recipient gets the frame — `any {}` would stop at the first successful write and
        // silently leave the rest of the channel un-notified. One press is still one event id, so
        // a receiver that gets it twice dedups it. Returns false only when no write got through.
        var delivered = false
        recipients.forEach { peerId -> if (sendControl(peerId, ping)) delivered = true }
        return delivered
    }

    /**
     * Decodes and consumes the two PTT text families. A recognized frame returns true even when
     * authentication rejects it, preventing it from falling through into unrelated protocols.
     */
    override public fun onInboundText(peerId: String, text: String): Boolean {
        PttFrameCodec.decode(text)?.let { frame ->
            if (frame.from != peerId || !isTrustedPeer(peerId)) {
                Log.w(TAG, "PTT ping dropped: claimed from=${frame.from} transport=$peerId trusted=${isTrustedPeer(peerId)}")
                return true
            }
            if (seenPingEventIds.add(frame.eventId)) {
                if (seenPingEventIds.size > SEEN_PING_CAP) seenPingEventIds.clear()
                val event = frame.toEvent()
                if (!_pings.tryEmit(event)) scope.launch { _pings.emit(event) }
            }
            return true
        }
        PttSessionCodec.decode(text)?.let { frame ->
            onControlFrame(peerId, frame)
            return true
        }
        return false
    }

    /** Inbound session control frame (already decoded). Safe from any thread. */
    public fun onControlFrame(peerId: String, frame: PttSessionFrame) {
        scope.launch {
            if (frame.from != peerId) {
                Log.w(TAG, "PTT session frame dropped: claimed from=${frame.from} != transport peer=$peerId")
                return@launch
            }
            if (!isTrustedPeer(peerId)) {
                Log.w(TAG, "PTT session frame dropped: untrusted peer=$peerId")
                return@launch
            }
            val now = nowMs()
            val self = localId()
            when (frame) {
                is PttSessionFrame.Start -> {
                    if (isCallActive() || voiceNoteLeaseId != null) {
                        Log.i(TAG, "PTT start ignored while local audio path is busy peer=$peerId")
                        return@launch
                    }
                    dispatchIo(
                        PttFloorEvent.RemoteStart(
                            sessionId = frame.sessionId,
                            holderId = peerId,
                            holderName = frame.senderName,
                            nowMs = now,
                            sampleRateHz = frame.sampleRateHz,
                            packetMs = frame.packetMs,
                        ),
                    )
                }
                is PttSessionFrame.Stop -> dispatchIo(
                    PttFloorEvent.RemoteStop(frame.sessionId, peerId),
                )
                is PttSessionFrame.Leave -> dispatchIo(
                    PttFloorEvent.RemoteLeave(frame.sessionId, peerId),
                )
                is PttSessionFrame.Heartbeat -> {
                    dispatchIo(PttFloorEvent.RemoteActivity(frame.sessionId, peerId, now))
                    val current = state.value as? PttFloorState.Listening
                    if (
                        current != null && current.sessionId == frame.sessionId &&
                        current.holderId == peerId && self != null
                    ) {
                        lastHbRttMs = frame.rttMs
                        withContext(Dispatchers.IO) {
                            sendControl(
                                peerId,
                                PttSessionCodec.encode(
                                    PttSessionFrame.HeartbeatAck(
                                        frame.sessionId,
                                        self,
                                        frame.seq,
                                        now,
                                    ),
                                ),
                            )
                        }
                        refreshPttSessionStats()
                    }
                }
                is PttSessionFrame.HeartbeatAck -> {
                    val current = state.value as? PttFloorState.Talking
                    if (
                        current != null && current.sessionId == frame.sessionId &&
                        peerId in members
                    ) {
                        sentHb.remove(frame.seq to peerId)?.let { sentAt ->
                            val sample = (now - sentAt).coerceAtLeast(0L)
                            lastRttMs = lastRttMs?.let { (it + sample) / 2 } ?: sample
                            refreshPttSessionStats()
                        }
                    }
                }
            }
        }
    }

    /**
     * Inbound audio bytes. Hot path (up to 50/s): lock-free snapshot checks only, never
     * suspends, never logs per packet. Corrupt/foreign/stale audio drops silently — the
     * next packet arrives in ~20 ms and the jitter buffer counts the gap.
     */
    public fun onAudioBytes(peerId: String?, data: ByteArray) {
        if (peerId == null || !PttAudioFrame.isPttAudio(data)) return
        val listening = state.value as? PttFloorState.Listening ?: return
        val expectedBytes = PttAudioFrame.expectedPcmBytes(
            listening.sampleRateHz,
            listening.packetMs,
        ) ?: return
        val frame = PttAudioFrame.decodeExpectedSize(data, expectedBytes) ?: return
        if (frame.sessionId != listening.sessionId || peerId != listening.holderId) return
        val accepted = playout?.offer(frame.seq, frame.captureTsMs, frame.pcm) == true
        if (accepted) {
            scope.launch {
                dispatch(
                    PttFloorEvent.RemoteActivity(
                        sessionId = frame.sessionId,
                        peerId = peerId,
                        nowMs = nowMs(),
                    ),
                )
            }
        }
    }

    override public fun onInboundBinary(peerId: String?, data: ByteArray): Boolean {
        if (!PttAudioFrame.isPttAudio(data)) return false
        onAudioBytes(peerId, data)
        return true
    }

    /** A phone call went active: mic exclusivity + ADR-026 quiet tear any session down. */
    override public fun onCallStarted() {
        scope.launch { dispatchIo(PttFloorEvent.CallStarted) }
    }

    /**
     * Voice-message capture gate. A successful acquisition returns a lease that only its owner
     * can release, so disposal of an unrelated conversation cannot clear another recorder's gate.
     */
    override public fun acquireVoiceNoteLease(): String? = synchronized(voiceNoteGate) {
        if (
            voiceNoteLeaseId != null || pttStartPending ||
            floor !is PttFloorState.Idle || isCallActive()
        ) {
            Log.w(TAG, "Voice note refused: PTT, call, or another voice note active")
            return@synchronized null
        }
        val leaseId = UUID.randomUUID().toString()
        voiceNoteLeaseId = leaseId
        Log.i(TAG, "Voice note capture gate acquired")
        leaseId
    }

    /** Releases only the matching lease id returned by [acquireVoiceNoteLease]. */
    override public fun releaseVoiceNoteLease(leaseId: String) {
        synchronized(voiceNoteGate) {
            if (voiceNoteLeaseId != leaseId) return
            voiceNoteLeaseId = null
            Log.i(TAG, "Voice note capture gate released")
        }
    }

    /** Local stop entry for notification actions / UI (Phase 2+). Safe from any thread. */
    override public fun stopLocal() {
        scope.launch { dispatchIo(PttFloorEvent.LocalStopPress) }
    }

    /** One-shot user notice (Phase 3 toasts). Safe from any thread. */
    override public fun postNotice(text: String) {
        scope.launch { _notices.emit(text) }
    }

    override public fun shutdown() {
        sessionJobs?.cancel()
        sessionJobs = null
        sessionJobKey = null
        runCatching { capture?.stop() }
        runCatching { playout?.stop() }
        capture = null
        playout = null
        holderId = null
        members = emptyList()
        voiceNoteLeaseId = null
        pttStartPending = false
        // Teardown, not a transition: no wire sends on the way out (orphaned receivers
        // are covered by their heartbeat timeout). Resetting the flows lets the session
        // service observe Idle and stop itself.
        floor = PttFloorState.Idle
        _state.value = PttFloorState.Idle
        _stats.value = null
        scope.cancel()
    }

    private suspend fun dispatch(event: PttFloorEvent) {
        commandMutex.withLock { processLocked(event) }
    }

    private suspend fun dispatchIo(event: PttFloorEvent) {
        withContext(Dispatchers.IO) { dispatch(event) }
    }

    /** commandMutex must be held; internal effects recurse here without trying to re-lock it. */
    private suspend fun processLocked(event: PttFloorEvent) {
        val transition = PttFloorMachine.reduce(floor, event)
        floor = transition.state
        _state.value = transition.state
        refreshPttSessionStats()
        executeLocked(transition)
        // An internal failure may have recursively reduced to a newer state. Only the innermost,
        // still-current transition owns the loop set; never restart jobs for an abandoned state.
        if (floor == transition.state) syncJobs(transition.state)
    }

    private suspend fun executeLocked(transition: PttTransition) {
        for (effect in transition.effects) {
            when (effect) {
                is PttFloorEffect.StartCapture -> startTalk(effect.sessionId)
                is PttFloorEffect.EnableCapturePackets -> enableCapturePackets(effect.sessionId)
                is PttFloorEffect.StopCapture -> stopTalk()
                is PttFloorEffect.StartPlayout -> startListen(effect)
                is PttFloorEffect.StopPlayout -> stopListen()
                is PttFloorEffect.SendStop -> sendStop(effect.sessionId)
                is PttFloorEffect.SendLeave -> sendLeave(effect.sessionId)
                is PttFloorEffect.RemoveMember -> removeMember(effect.sessionId, effect.peerId)
                is PttFloorEffect.NotifyBusy ->
                    _notices.emit("Channel busy — ${effect.holderName ?: "someone"} is talking")
                is PttFloorEffect.NotifyBurstWarning ->
                    _notices.emit("Wrap up: ${effect.remainingMs / 1000}s of talk time left")
                is PttFloorEffect.NotifyEnded -> _notices.emit(
                    when (effect.reason) {
                        PttEndReason.REMOTE_STOP -> "${effect.holderName ?: "Peer"} stopped talking"
                        PttEndReason.TIMEOUT -> "Lost ${effect.holderName ?: "peer"} — listening stopped"
                        PttEndReason.BURST_CAP -> "Talk time cap reached"
                        PttEndReason.CALL_STARTED -> "Session ended — call started"
                        PttEndReason.MIC_DENIED -> "Microphone unavailable — session ended"
                        PttEndReason.LOCAL_STOP -> "Session ended"
                    },
                )
            }
        }
    }

    private suspend fun startTalk(sessionId: String) {
        val freshMembers = snapshotMembers()
        if (freshMembers.isEmpty()) {
            // Lost a membership race between press and execution: unwind quietly.
            processLocked(PttFloorEvent.LocalStopPress)
            _notices.emit("No paired devices online")
            return
        }
        members = freshMembers
        val rate = audioRateHz()
        val packetMs = if (rate <= 8000) 60 else 20
        val slot = arrayOfNulls<PttCapture>(1)
        val instance = PttCapture(
            requestedRateHz = rate,
            packetMs = packetMs,
            onPacket = { pcm, captureTs ->
                lastCaptureAmp = PttAudioLevel.rms01(pcm)
                audioOut.trySend(OutPacket(sessionId, pcm, captureTs))
            },
            onCaptureLost = {
                scope.launch {
                    if (capture === slot[0]) dispatchIo(PttFloorEvent.MicDenied)
                }
            },
        )
        slot[0] = instance
        capture = instance
        when (val started = instance.start()) {
            is PttCapture.StartResult.Started -> {
                sessionRateHz = started.actualRateHz
                sessionPacketMs = started.actualPacketMs
                val self = localId()
                if (self == null) {
                    Log.w(TAG, "Talking with no local id — ending")
                    processLocked(PttFloorEvent.LocalStopPress)
                    return
                }
                val start = PttSessionCodec.encode(
                    PttSessionFrame.Start(
                        sessionId = sessionId,
                        from = self,
                        senderName = localName() ?: "Peer",
                        sentAt = System.currentTimeMillis(),
                        sampleRateHz = sessionRateHz,
                        packetMs = sessionPacketMs,
                    ),
                )
                val announcedMembers = members.filter { sendControl(it, start) }
                members = announcedMembers
                if (announcedMembers.isEmpty()) {
                    Log.w(TAG, "PTT start reached no members — ending session=$sessionId")
                    processLocked(PttFloorEvent.LocalStopPress)
                    _notices.emit("No paired devices online")
                    return
                }
                capture = instance
                processLocked(PttFloorEvent.StartAnnounced(sessionId))
                sentHb.clear()
                lastRttMs = null
                Log.i(TAG, "Talking session=$sessionId members=${members.size} rate=$sessionRateHz")
            }
            is PttCapture.StartResult.Failed -> {
                if (capture === instance) capture = null
                processLocked(PttFloorEvent.MicDenied)
            }
        }
    }

    private fun enableCapturePackets(sessionId: String) {
        val talking = floor as? PttFloorState.Talking
        if (talking?.sessionId == sessionId) capture?.enablePackets()
    }

    private fun stopTalk() {
        runCatching { capture?.stop() }
        capture = null
        sentHb.clear()
    }

    private suspend fun startListen(effect: PttFloorEffect.StartPlayout) {
        val listening = floor as? PttFloorState.Listening
        val holder = listening?.holderId
        if (listening?.sessionId != effect.sessionId || holder == null) {
            Log.w(TAG, "Listen without accepted holder — unwinding session=${effect.sessionId}")
            processLocked(PttFloorEvent.LocalStopPress)
            return
        }
        holderId = holder
        sessionRateHz = effect.sampleRateHz
        sessionPacketMs = effect.packetMs
        // Drain stale audio from a previous session before the new one flows.
        while (audioOut.tryReceive().getOrNull() != null) Unit
        val slot = arrayOfNulls<PttPlayout>(1)
        val instance = PttPlayout(
            sampleRateHz = sessionRateHz,
            packetMs = sessionPacketMs,
            onAmplitude = { refreshPttSessionStats() },
            onPlayoutLost = {
                scope.launch {
                    if (playout === slot[0]) {
                        dispatchIo(PttFloorEvent.LocalStopPress)
                        _notices.emit("Listening stopped (audio fault)")
                    }
                }
            },
        )
        slot[0] = instance
        playout = instance
        if (!instance.start()) {
            if (playout === instance) playout = null
            processLocked(PttFloorEvent.LocalStopPress)
            _notices.emit("Could not start listening")
            return
        }
        Log.i(TAG, "Listening session=${effect.sessionId} holder=$holder rate=$sessionRateHz")
    }

    private fun stopListen() {
        runCatching { playout?.stop() }
        playout = null
        // holderId deliberately SURVIVES here: the machine emits [StopPlayout, SendLeave]
        // in that order, and sendLeave reads it after this runs — clearing it here was
        // ERROR-046 (Leave never transmitted). Overwritten on every startListen; cleared
        // in shutdown().
        lastHbRttMs = null
    }

    private fun sendStop(sessionId: String) {
        val self = localId() ?: return
        val stop = PttSessionCodec.encode(
            PttSessionFrame.Stop(sessionId, self, System.currentTimeMillis()),
        )
        members.forEach { sendControl(it, stop) }
        members = emptyList()
    }

    private fun sendLeave(sessionId: String) {
        val self = localId()
        val holder = holderId
        if (self == null || holder == null) {
            Log.w(TAG, "Leave not sent session=$sessionId (self=$self holder=$holder)")
            return
        }
        sendControl(
            holder,
            PttSessionCodec.encode(PttSessionFrame.Leave(sessionId, self, System.currentTimeMillis())),
        )
        Log.i(TAG, "Leave sent session=$sessionId to holder=$holder")
    }

    private fun removeMember(sessionId: String, peerId: String) {
        if (!members.contains(peerId)) return
        members = members - peerId
        refreshPttSessionStats()
        Log.i(TAG, "Listener left session=$sessionId peer=$peerId remaining=${members.size}")
    }

    private fun syncJobs(current: PttFloorState) {
        val nextKey = when (current) {
            is PttFloorState.Idle -> null
            is PttFloorState.Talking -> SessionJobKey(current.sessionId, PttRole.TALKER)
            is PttFloorState.Listening -> SessionJobKey(current.sessionId, PttRole.LISTENER)
        }
        if (nextKey == sessionJobKey && sessionJobs?.isActive == true) return
        sessionJobs?.cancel()
        sessionJobs = null
        sessionJobKey = nextKey
        if (nextKey == null) return
        sessionJobs = scope.launch {
            launch { tickLoop(nextKey) }
            if (nextKey.role == PttRole.TALKER) {
                launch { heartbeatLoop(nextKey.sessionId) }
                launch(Dispatchers.IO) { senderLoop(nextKey.sessionId) }
            }
        }
    }

    private suspend fun CoroutineScope.tickLoop(key: SessionJobKey) {
        while (isActive && sessionJobKey == key) {
            delay(TICK_MS)
            dispatchIo(PttFloorEvent.Tick(nowMs()))
        }
    }

    private suspend fun CoroutineScope.heartbeatLoop(sessionId: String) {
        while (isActive) {
            delay(HEARTBEAT_MS)
            val talking = floor as? PttFloorState.Talking
            if (talking == null || talking.sessionId != sessionId) break
            val self = localId() ?: break
            val seq = synchronized(hbLock) { ++hbSeq }
            val now = nowMs()
            val recipients = members
            recipients.forEach { peerId -> sentHb[seq to peerId] = now }
            if (sentHb.size > MAX_TRACKED_HB) {
                sentHb.entries.sortedBy { it.value }
                    .take(sentHb.size - MAX_TRACKED_HB)
                    .forEach { sentHb.remove(it.key) }
            }
            val heartbeat = PttSessionCodec.encode(
                PttSessionFrame.Heartbeat(sessionId, self, seq, now, lastRttMs),
            )
            withContext(Dispatchers.IO) {
                recipients.forEach { sendControl(it, heartbeat) }
            }
        }
    }

    private suspend fun CoroutineScope.senderLoop(sessionId: String) {
        var seq = 0L
        // Drain anything a previous session left behind.
        while (audioOut.tryReceive().getOrNull() != null) Unit
        for (packet in audioOut) {
            if (!isActive) break
            val talking = floor as? PttFloorState.Talking
            if (talking == null || talking.sessionId != sessionId) break
            if (packet.sessionId != sessionId) continue
            val frame = PttAudioFrame.encode(
                PttAudioFrame(sessionId, seq++, packet.captureTsMs, packet.pcm),
            )
            members.forEach { sendAudio(it, frame) }
        }
    }

    private fun refreshPttSessionStats() {
        val now = nowMs()
        _stats.value = when (val current = floor) {
            is PttFloorState.Idle -> null
            is PttFloorState.Talking -> PttSessionStats(
                sessionId = current.sessionId,
                role = PttRole.TALKER,
                elapsedMs = now - current.startedAtMs,
                rttMs = lastRttMs,
                lossPercent = 0f,
                depthMs = 0L,
                amplitude01 = lastCaptureAmp,
                members = members.size,
            )
            is PttFloorState.Listening -> {
                val snapshot = playout?.snapshot()
                val ready = snapshot?.readyTotal ?: 0L
                val concealed = snapshot?.concealedTotal ?: 0L
                PttSessionStats(
                    sessionId = current.sessionId,
                    role = PttRole.LISTENER,
                    elapsedMs = now - current.startedAtMs,
                    rttMs = lastHbRttMs,
                    lossPercent = if (ready + concealed == 0L) {
                        0f
                    } else {
                        concealed.toFloat() / (ready + concealed).toFloat()
                    },
                    depthMs = (snapshot?.depthPackets ?: 0) * sessionPacketMs.toLong(),
                    amplitude01 = snapshot?.amplitude01 ?: 0f,
                    members = 1,
                )
            }
        }
    }

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    public companion object {
        private const val TAG = "PTT_SESS"
        private const val TICK_MS = 500L
        private const val HEARTBEAT_MS = 1000L
        private const val MAX_TRACKED_HB = 120

        /**
         * Cap for the inbound ping dedup set. PTT pings are fire-and-forget with no outbox, so
         * the set only guards a duplicated frame on the wire; clearing it wholesale at the cap
         * costs at most one replayed notification.
         */
        private const val SEEN_PING_CAP = 1000

        /**
         * Intent extra carried when a hardware press surfaces the app (Phase 3): the
         * shell consumes it into a pending press (permission prompt included) instead of
         * starting capture from the background, which API 34+ forbids.
         */
        public const val EXTRA_PTT_PRESS: String = "com.transfer.flash.ptt.EXTRA_PTT_PRESS"
    }
}
