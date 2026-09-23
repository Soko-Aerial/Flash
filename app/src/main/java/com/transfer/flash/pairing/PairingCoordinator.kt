// FlashLog is @FlashInternalApi — library-internal, opted into here for the same reason
// DiscoveryEngineHolder and the desktop tier do: the pairing narrative is diagnostics, not API.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.pairing

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.pairing.DefaultFlashPairingProtocol
import com.transfer.flash.core.security.pairing.FlashPairingEvent
import com.transfer.flash.core.security.pairing.FlashPairingFrame
import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.core.security.pairing.PairingSessionState
import com.transfer.flash.core.security.pairing.PairingWireCodec
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** UI snapshot the Nearby pairing dialog binds to; null when no pairing is in flight. */
data class PairingUiModel(
    val request: FlashPairingRequestUi,
    val phase: FlashPairingPhase,
    val secondsLeft: Int,
)

/**
 * Android coordinator for the Flash pairing flow (ADR-028, Phase 25/Phase 26-2).
 *
 * Sits between:
 * - the pure multiplatform state machine ([DefaultFlashPairingProtocol], `core/security`),
 * - the Nearby UI ([PairingUiModel], mapped via [PairingUiMapper]), and
 * - the live WebSocket connection ([sendToPeer] lambda).
 *
 * Responsibilities:
 * - Exposes [pairing] as a [StateFlow] so the Nearby tab and consent dialog react to lifecycle
 *   transitions without holding the protocol instance directly.
 * - Handles the "announce hello when session comes up" edge so a peer learns our fingerprint and can
 *   derive the comparison code before the user taps "Pair".
 * - Translates [FlashPairingEvent] into user-visible side effects (e.g. saves trusted peer to
 *   [FlashTrustStore] on [FlashPairingEvent.Confirmed]).
 * - Enforces the single-flight rule: once in a non-Idle state, subsequent requests are dropped or
 *   rejected until the active pairing completes, is declined, or expires.
 *
 * ## Lifecycle and expiry (ADR-028 §4)
 *
 * Resets back to [PairingPhase.Idle] via [resetProtocol] on any terminal state:
 * - [FlashPairingEvent.Confirmed]: after [PAIRED_LINGER_MS] so the user sees the "Paired" checkmark.
 * - [FlashPairingEvent.Expired] / [FlashPairingEvent.PeerDeclined] / [FlashPairingEvent.Failed]: after
 *   [TERMINAL_LINGER_MS] so the user sees the terminal banner.
 *
 * [resetProtocol] tears down the active protocol and creates a fresh instance. The underlying state
 * machine absorbs all events once terminal and exposes no public reset, so "pair again" (retry after
 * decline/expire, or pair a second device) requires a fresh instance. Recreation swaps the field and
 * relaunches the collectors, the 1 Hz expiry ticker among them: it is a child of the same job, bound
 * to the instance it was launched for, so a stale instance can never keep ticking.
 */
class PairingCoordinator(
    val localFingerprintHex: String,
    private val localDeviceId: String,
    private val localName: String,
    private val localModel: String,
    private val ephemeralPublicKey: ByteArray,
    private val trustStore: FlashTrustStore,
    private val scope: CoroutineScope,
    private val sendToPeer: (peerId: String, text: String) -> Boolean,
    private val timeSource: FlashTimeSource = SystemTimeSource,
    private val crypto: FlashCrypto? = null,
    private val ephemeralKeyPair: FlashEcKeyPair? = null,
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

    /** peerId -> pairing protocol version from its hello (1 when it advertises none). Guarded by [fingerprints]. */
    private val peerVersions = HashMap<String, Int>()

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

    /** A WebSocket session to [peerId] came up — announce our fingerprint so it can derive the code. */
    fun onSessionUp(peerId: String) {
        sendToPeer(peerId, PairingWireCodec.encodeHello(localFingerprintHex))
    }

    /** Feed a decoded `FLASH_PAIR` line from [peerId]: hello updates the cache, frames drive the protocol. */
    fun onInbound(peerId: String, text: String) {
        when (val inbound = PairingWireCodec.decode(text)) {
            is PairingWireCodec.Inbound.Hello -> {
                synchronized(fingerprints) {
                    fingerprints[peerId] = inbound.fingerprintHex
                    peerVersions[peerId] = inbound.protocolVersion
                }
                // Answer a REQUEST, and answer with a PLAIN hello: the flag is never echoed, so the
                // exchange terminates by construction (a request draws at most one answer; an answer
                // draws none). Before this existed the responder announced itself exactly once, on
                // its session-up edge, so any interleaving that lost that one frame — a session that
                // came up in a different order than the announcement assumed — made pairing
                // permanently impossible on that connection while every other signal looked healthy.
                // The initiator re-asks inside its wait window; this is the half that answers.
                if (inbound.request) sendToPeer(peerId, PairingWireCodec.encodeHello(localFingerprintHex))
                // If the user already tapped Pair for this peer, the fingerprint just arrived — fire
                // the request now instead of leaving them staring at a dead button.
                val pending = synchronized(pendingLock) {
                    pendingPair?.takeIf { it.first == peerId }?.also { pendingPair = null }
                }
                if (pending != null) {
                    scope.launch { beginIfPeerSupportsV2(pending.first, pending.second, inbound.fingerprintHex) }
                }
            }
            is PairingWireCodec.Inbound.Frame ->
                scope.launch { protocol.onFrame(inbound.frame) }
            null -> Unit
        }
    }

    /** Initiator: tap Pair. Needs the peer's fingerprint (from its hello) to derive the shared code. */
    suspend fun beginPair(peerId: String, peerName: String) {
        // Logged on entry as well as on failure: the interesting case is "Pair was tapped and
        // NOTHING was emitted", which is invisible if only the failure path logs.
        FlashLog.i(
            TAG,
            "beginPair peer=$peerId name=$peerName " +
                "fingerprintKnown=${synchronized(fingerprints) { fingerprints.containsKey(peerId) }}",
        )
        synchronized(fingerprints) { fingerprints[peerId] }?.let { fingerprint ->
            beginIfPeerSupportsV2(peerId, peerName, fingerprint)
            return
        }
        // No fingerprint yet: the session/hello may still be in flight. Record the intent (the hello
        // handler completes it), re-announce our fingerprint to prompt theirs, and tell the user —
        // never fail silently. `sendToPeer` returning false means there is no live session at all.
        synchronized(pendingLock) { pendingPair = peerId to peerName }
        val delivered = sendToPeer(peerId, PairingWireCodec.encodeHello(localFingerprintHex, request = true))
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
            beginIfPeerSupportsV2(peerId, peerName, fingerprint)
        } else {
            emitMessage("Still can't reach $peerName. Check that it's nearby and try Pair again.")
        }
    }

    /**
     * Refuses to pair with a peer that only speaks v1 (ADR-042, owner decision 2026-09-23): its code
     * could be forced by a man-in-the-middle, so no new pairing may be made with it.
     */
    private fun beginIfPeerSupportsV2(peerId: String, peerName: String, fingerprint: String) {
        val version = synchronized(fingerprints) { peerVersions[peerId] } ?: 1
        if (version < PairingWireCodec.PROTOCOL_VERSION) {
            emitMessage("$peerName is running an older Flash. Update Flash on that device, then pair again.")
            return
        }
        protocol.beginRequest(peerId, peerName, fingerprint)
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

    /** Retrieve the cached peer identity fingerprint (hex) if known. */
    fun getFingerprint(peerId: String): String? = synchronized(fingerprints) { fingerprints[peerId] }

    /**
     * Waits for the peer's hello, **re-asking** as it goes.
     *
     * Re-asking rather than merely re-waiting is the fix: a hello sent once is a single point of
     * failure, and the peer only ever volunteered one on its session-up edge. The re-ask is bounded
     * by the window (<= 8 asks at 400 ms) and each ask draws at most one answer, so the burst is
     * finite by construction rather than by a counter we would have to keep.
     */
    private suspend fun awaitFingerprint(peerId: String): String? {
        val deadline = timeSource.nowMs() + FINGERPRINT_WAIT_MS
        var lastAskMs = timeSource.nowMs()
        while (timeSource.nowMs() < deadline) {
            synchronized(fingerprints) { fingerprints[peerId] }?.let { return it }
            val now = timeSource.nowMs()
            if (now - lastAskMs >= HELLO_RESEND_MS) {
                lastAskMs = now
                sendToPeer(peerId, PairingWireCodec.encodeHello(localFingerprintHex, request = true))
            }
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
                    sendToPeer(peerId, PairingWireCodec.encode(FlashPairingFrame.PairConfirm(event.requestId, hash)))
                }
            }
            is FlashPairingEvent.Confirmed -> {
                val s = protocol.session.value
                s.peerDeviceId?.let { peerId ->
                    val name = s.peerName?.ifBlank { null } ?: peerId.take(SHORT_ID)
                    trustStore.trustPeer(FlashDeviceId(peerId), name)
                    // v2 completion: the code covered the TLS-pinned identities and the ephemeral keys.
                    trustStore.markVerified(FlashDeviceId(peerId))
                    val peerPubKey = event.ephemeralPubKey.takeIf { it.isNotEmpty() }
                        ?: s.peerEphemeralPublicKey
                    val c = crypto
                    val kp = ephemeralKeyPair
                    if (peerPubKey != null && kp != null && c != null) {
                        runCatching {
                            val sessionKey = c.ecdhSessionKey(kp, peerPubKey)
                            trustStore.saveSessionKey(FlashDeviceId(peerId), sessionKey)
                        }
                    }
                    _trustedPeers.value = loadTrusted()
                }
                scope.launch {
                    delay(PAIRED_LINGER_MS) // let "Paired" show, then auto-close.
                    resetProtocol()
                }
            }
            is FlashPairingEvent.Failed -> {
                failureMessage(event.reason)?.let(::emitMessage)
                scope.launch {
                    delay(TERMINAL_LINGER_MS)
                    resetProtocol()
                }
            }
            is FlashPairingEvent.PeerDeclined,
            is FlashPairingEvent.Expired ->
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
            launch { tickWhileInFlight(p) }
        }
    }

    /**
     * Drives request/decision expiry and refreshes the countdown at 1 Hz — but only while [p] has a
     * pairing in flight. This was an unconditional `while (isActive) { …; delay(1s) }` launched from
     * `init`, i.e. ~86,400 scheduler wake-ups a day to service a phase that is [PairingPhase.Idle]
     * except during the few seconds a user spends pairing. An idle pass was cheap — a wake-up and a
     * `StateFlow.value` read, not a query — but it was also unnecessary: the phase is already a flow,
     * so the same edge that makes the tick necessary can start it.
     *
     * Bound to the instance passed in rather than to the `protocol` field, and launched as a child of
     * [collectorJob] so [resetProtocol]'s existing `cancel()` is the ticker's teardown too. A ticker
     * that outlived its instance would never stop: terminal phases absorb every event and never return
     * to Idle, so its `!= Idle` gate would stay true forever — the very defect this fixes.
     *
     * The gate stays at `!= Idle` rather than narrowing to the three phases the reducer acts on. The
     * narrower form is provably equivalent (the reducer returns early when terminal, and the dialog
     * renders the countdown only while active) but it would duplicate, in `:app`, a rule owned by the
     * state machine — worth less than the two recompositions it would save during the terminal linger.
     *
     * [distinctUntilChanged] is load-bearing: a pairing emits several session states, and without it
     * every one would restart [collectLatest]'s block and thereby restart the `delay`, so a chatty
     * handshake could starve the countdown and postpone expiry indefinitely. The `delay` comes before
     * the work because the session emission that started the ticker has already run [recomputeUi] and
     * expiry is 30 s out — so the first tick only decrements the displayed second, which now happens a
     * full second after the dialog appears instead of wherever it fell on a process-wide 1 Hz grid.
     */
    private suspend fun tickWhileInFlight(p: DefaultFlashPairingProtocol) {
        p.session
            .map { it.phase != PairingPhase.Idle }
            .distinctUntilChanged()
            .collectLatest { inFlight ->
                if (!inFlight) return@collectLatest
                while (true) {
                    delay(TICK_MS)
                    p.onTick(timeSource.nowMs())
                    recomputeUi(p.session.value)
                }
            }
    }

    private fun newProtocol(): DefaultFlashPairingProtocol = DefaultFlashPairingProtocol(
        localFingerprintHex = localFingerprintHex,
        localDeviceId = localDeviceId,
        localName = localName,
        localModel = localModel,
        ephemeralPublicKeyProvider = { ephemeralPublicKey },
        // ADR-042: the pairing must cover the key TLS pinned for this peer on this connection.
        peerIdentityPin = { peerId -> trustStore.getPin(FlashDeviceId(peerId)) },
        // The session holds a single peer at a time; route by its current peerDeviceId (set before
        // any frame is sent, since the reducer runs before sendFrame in the protocol).
        sendFrame = { frame ->
            protocol.session.value.peerDeviceId?.let { peerId ->
                sendToPeer(peerId, PairingWireCodec.encode(frame))
            }
            Unit
        },
        timeSource = timeSource,
    )

    private fun loadTrusted(): List<NearbyTrustedPeerUi> =
        trustStore.getTrustedPeers()
            .map { (id, name) -> NearbyTrustedPeerUi(id = id.value, name = name, verified = trustStore.isVerified(id)) }
            .sortedBy { it.name.lowercase() }

    /** User-facing line for a failed pairing; null when the reason needs no explanation. */
    private fun failureMessage(reason: String): String? = when (reason) {
        "peer-update-required" -> "The other device is running an older Flash. Update it, then pair again."
        "identity-mismatch", "commit-mismatch", "paired-material-mismatch", "code-hash-mismatch" ->
            "Pairing stopped: the security check failed. If this keeps happening, something may be " +
                "interfering with the connection."
        else -> null
    }

    private fun emitMessage(text: String) {
        // Also to logcat. These lines are the only narrative explaining why a pairing attempt did
        // or did not proceed, and the in-app surface for them is transient — a message that
        // scrolls away turns a diagnosable failure into a mystery. `adb logcat -s FLASH_PAIRING`
        // now reads the same story the desktop tier prints to its console.
        FlashLog.i(TAG, text)
        _messages.tryEmit(text)
    }

    private companion object {
        const val TAG = "FLASH_PAIRING"
        const val TICK_MS = 1000L
        const val FINGERPRINT_WAIT_MS = 3000L
        const val FINGERPRINT_POLL_MS = 100L

        /**
         * Re-ask cadence inside [FINGERPRINT_WAIT_MS]: at most 8 asks, each answered at most once.
         * Same value as `FlashPairingCoordinator`'s in `:core:security` — the two must agree or the
         * two hosts behave differently under the same lost frame.
         */
        const val HELLO_RESEND_MS = 400L
        const val PAIRED_LINGER_MS = 1800L
        const val TERMINAL_LINGER_MS = 2500L
        const val REQUEST_WINDOW_SECONDS = 30
        const val SHORT_ID = 8
        const val MESSAGE_BUFFER = 8
    }
}
