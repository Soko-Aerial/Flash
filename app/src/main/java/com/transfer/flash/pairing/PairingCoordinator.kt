package com.transfer.flash.pairing

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.security.pairing.DefaultFlashPairingProtocol
import com.transfer.flash.core.security.pairing.FlashPairingEvent
import com.transfer.flash.core.security.pairing.FlashPairingFrame
import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.core.security.pairing.PairingSessionState
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.ui.chat.FlashPairingMath
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.ui.chat.FlashPairingRequestUi
import com.transfer.flash.ui.nearby.NearbyTrustedPeerUi
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI snapshot the Nearby pairing dialog binds to; null when no pairing is in flight. */
data class PairingUiModel(
    val request: FlashPairingRequestUi,
    val phase: FlashPairingPhase,
    val secondsLeft: Int,
)

/**
 * App-level glue between the pure pairing protocol ([DefaultFlashPairingProtocol]), the persistent
 * trust store, and the Nearby UI. Lives in `:app` because it bridges `core.security` and `ui.chat`
 * types the way [com.transfer.flash.debug.DiscoveryEngineHolder] bridges the rest of the stack.
 *
 * Owns the protocol instance so it can recreate it after a terminal outcome — the protocol's session
 * machine absorbs all events once terminal and exposes no public reset, so "pair again" (retry after
 * decline/expire, or pair a second device) requires a fresh instance. Recreation just swaps the field
 * and relaunches the two collectors; the 1 Hz ticker always reads the current instance.
 */
class PairingCoordinator(
    private val localFingerprintHex: String,
    private val localDeviceId: String,
    private val localName: String,
    private val localModel: String,
    private val ephemeralPublicKey: ByteArray,
    private val trustStore: FlashTrustStore,
    private val scope: CoroutineScope,
    private val sendToPeer: (peerId: String, text: String) -> Boolean,
    private val timeSource: FlashTimeSource = SystemTimeSource,
) {
    private val _pairing = MutableStateFlow<PairingUiModel?>(null)
    val pairing: StateFlow<PairingUiModel?> = _pairing.asStateFlow()

    private val _trustedPeers = MutableStateFlow(loadTrusted())
    val trustedPeers: StateFlow<List<NearbyTrustedPeerUi>> = _trustedPeers.asStateFlow()

    /**
     * Transient, user-facing status lines (shown as a toast by the shell). The pairing handshake is
     * otherwise silent when it cannot start — e.g. no session, or the peer's hello hasn't arrived —
     * which reads to the user as "Pair did nothing". Emitting here closes that feedback gap.
     */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** peerId -> identity fingerprint hex, learned from FLASH_PAIR hello frames (initiator needs it). */
    private val fingerprints = HashMap<String, String>()

    /**
     * A pair the user tapped "Pair" on before the peer's fingerprint was known. The hello handler
     * completes it the instant the fingerprint arrives (removes the ≤3s race that made Pair look
     * dead). Guarded by [pendingLock]; whoever nulls it wins the right to call `beginRequest`.
     */
    private val pendingLock = Any()
    private var pendingPair: Pair<String, String>? = null

    @Volatile
    private var protocol: DefaultFlashPairingProtocol = newProtocol()
    private var collectorJob: Job = launchCollectors()

    init {
        // 1 Hz tick drives request/decision expiry and refreshes the countdown while active.
        scope.launch {
            while (isActive) {
                val current = protocol
                if (current.session.value.phase != PairingPhase.Idle) {
                    current.onTick(timeSource.nowMs())
                    recomputeUi(current.session.value)
                }
                delay(TICK_MS)
            }
        }
    }

    /** A WebSocket session to [peerId] came up — announce our fingerprint so it can derive the code. */
    fun onSessionUp(peerId: String) {
        sendToPeer(peerId, PairingFraming.encodeHello(localFingerprintHex))
    }

    /** Feed a decoded `FLASH_PAIR` line from [peerId]: hello updates the cache, frames drive the protocol. */
    fun onInbound(peerId: String, text: String) {
        when (val inbound = PairingFraming.decode(text)) {
            is PairingFraming.Inbound.Hello -> {
                synchronized(fingerprints) { fingerprints[peerId] = inbound.fingerprintHex }
                // If the user already tapped Pair for this peer, the fingerprint just arrived — fire
                // the request now instead of leaving them staring at a dead button.
                val pending = synchronized(pendingLock) {
                    pendingPair?.takeIf { it.first == peerId }?.also { pendingPair = null }
                }
                if (pending != null) {
                    scope.launch { protocol.beginRequest(pending.first, pending.second, inbound.fingerprintHex) }
                }
            }
            is PairingFraming.Inbound.Frame ->
                scope.launch { protocol.onFrame(inbound.frame) }
            null -> Unit
        }
    }

    /** Initiator: tap Pair. Needs the peer's fingerprint (from its hello) to derive the shared code. */
    suspend fun beginPair(peerId: String, peerName: String) {
        synchronized(fingerprints) { fingerprints[peerId] }?.let { fingerprint ->
            protocol.beginRequest(peerId, peerName, fingerprint)
            return
        }
        // No fingerprint yet: the session/hello may still be in flight. Record the intent (the hello
        // handler completes it), re-announce our fingerprint to prompt theirs, and tell the user —
        // never fail silently. `sendToPeer` returning false means there is no live session at all.
        synchronized(pendingLock) { pendingPair = peerId to peerName }
        val delivered = sendToPeer(peerId, PairingFraming.encodeHello(localFingerprintHex))
        emitMessage(
            if (delivered) "Connecting to $peerName…"
            else "Couldn't reach $peerName. Make sure both devices are on the same network, then try again.",
        )
        val fingerprint = awaitFingerprint(peerId)
        // Claim the pending intent: if the hello handler already fired it, this is a no-op.
        val claimed = synchronized(pendingLock) {
            (pendingPair?.first == peerId).also { if (it) pendingPair = null }
        }
        if (!claimed) return
        if (fingerprint != null) {
            protocol.beginRequest(peerId, peerName, fingerprint)
        } else {
            emitMessage("Still can't reach $peerName. Check that it's nearby and try Pair again.")
        }
    }

    /** Responder accepted the displayed code match. */
    fun acceptLocal() {
        scope.launch { protocol.respondAccept() }
    }

    /** Decline an active request, or dismiss a terminal dialog — either way, back to a clean slate. */
    fun declineLocal() {
        scope.launch {
            protocol.respondDecline()
            resetProtocol()
        }
    }

    /** Forget a trusted peer (persisted). */
    fun revoke(peerId: String) {
        trustStore.revokeTrust(FlashDeviceId(peerId))
        _trustedPeers.value = loadTrusted()
    }

    private suspend fun awaitFingerprint(peerId: String): String? {
        val deadline = timeSource.nowMs() + FINGERPRINT_WAIT_MS
        while (timeSource.nowMs() < deadline) {
            synchronized(fingerprints) { fingerprints[peerId] }?.let { return it }
            delay(FINGERPRINT_POLL_MS)
        }
        return synchronized(fingerprints) { fingerprints[peerId] }
    }

    private fun handleEvent(event: FlashPairingEvent) {
        when (event) {
            is FlashPairingEvent.PeerAccepted -> {
                // Initiator half: onFrame(PairAccept) reports acceptance but does NOT send PAIR_CONFIRM —
                // the confirm (proof both sides derived the same code) is ours to send.
                val s = protocol.session.value
                val peerId = s.peerDeviceId
                val hash = s.expectedCodeHashHex
                if (peerId != null && hash != null) {
                    sendToPeer(peerId, PairingFraming.encode(FlashPairingFrame.PairConfirm(event.requestId, hash)))
                }
            }
            is FlashPairingEvent.Confirmed -> {
                val s = protocol.session.value
                s.peerDeviceId?.let { peerId ->
                    val name = s.peerName?.ifBlank { null } ?: peerId.take(SHORT_ID)
                    trustStore.trustPeer(FlashDeviceId(peerId), name)
                    _trustedPeers.value = loadTrusted()
                }
                scope.launch {
                    delay(PAIRED_LINGER_MS) // let "Paired" show, then auto-close.
                    resetProtocol()
                }
            }
            is FlashPairingEvent.PeerDeclined,
            is FlashPairingEvent.Expired,
            is FlashPairingEvent.Failed ->
                scope.launch {
                    delay(TERMINAL_LINGER_MS) // let the terminal card show, then allow retry.
                    resetProtocol()
                }
            is FlashPairingEvent.RequestReceived -> Unit // the session collector already raises the dialog.
        }
    }

    private fun recomputeUi(s: PairingSessionState) {
        val uiPhase = PairingUiMapper.corePhaseToUi(s.phase)
        if (uiPhase == FlashPairingPhase.Idle) {
            _pairing.value = null
            return
        }
        val peerName = s.peerName?.ifBlank { null } ?: s.peerDeviceId?.take(SHORT_ID) ?: "Device"
        _pairing.value = PairingUiModel(
            request = FlashPairingRequestUi(
                peerName = peerName,
                peerInitials = FlashPairingMath.initialsFor(peerName),
                numericCode = s.code6 ?: "",
                transport = FlashNetworkTransport.Lan,
                expiresInSeconds = REQUEST_WINDOW_SECONDS,
            ),
            phase = uiPhase,
            secondsLeft = PairingUiMapper.secondsLeft(s.expiresAtMs, timeSource.nowMs()),
        )
    }

    private fun resetProtocol() {
        collectorJob.cancel()
        protocol = newProtocol()
        collectorJob = launchCollectors()
        _pairing.value = null
    }

    private fun launchCollectors(): Job {
        val p = protocol
        return scope.launch {
            launch { p.session.collect { recomputeUi(it) } }
            launch { p.events.collect { handleEvent(it) } }
        }
    }

    private fun newProtocol(): DefaultFlashPairingProtocol = DefaultFlashPairingProtocol(
        localFingerprintHex = localFingerprintHex,
        localDeviceId = localDeviceId,
        localName = localName,
        localModel = localModel,
        ephemeralPublicKeyProvider = { ephemeralPublicKey },
        // The session holds a single peer at a time; route by its current peerDeviceId (set before
        // any frame is sent, since the reducer runs before sendFrame in the protocol).
        sendFrame = { frame ->
            protocol.session.value.peerDeviceId?.let { peerId ->
                sendToPeer(peerId, PairingFraming.encode(frame))
            }
            Unit
        },
        timeSource = timeSource,
    )

    private fun loadTrusted(): List<NearbyTrustedPeerUi> =
        trustStore.getTrustedPeers()
            .map { (id, name) -> NearbyTrustedPeerUi(id = id.value, name = name) }
            .sortedBy { it.name.lowercase() }

    private fun emitMessage(text: String) {
        _messages.tryEmit(text)
    }

    private companion object {
        const val TICK_MS = 1000L
        const val FINGERPRINT_WAIT_MS = 3000L
        const val FINGERPRINT_POLL_MS = 100L
        const val PAIRED_LINGER_MS = 1800L
        const val TERMINAL_LINGER_MS = 2500L
        const val REQUEST_WINDOW_SECONDS = 30
        const val SHORT_ID = 8
        const val MESSAGE_BUFFER = 8
    }
}
