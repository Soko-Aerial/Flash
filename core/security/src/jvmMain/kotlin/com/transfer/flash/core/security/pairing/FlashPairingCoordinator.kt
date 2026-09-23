@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.trust.FlashTrustStore
import java.util.concurrent.ConcurrentHashMap
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

/**
 * A paired peer as the hosts display it.
 *
 * A `core`-owned type on purpose: this class is shared, and the desktop's Nearby row model
 * (`NearbyTrustedPeerUi`, a Compose-era UI type) must not leak into a published security module.
 * Hosts map this at their edge.
 */
public data class FlashTrustedPeer(
    val id: String,
    val name: String,
    /** False for a v1 pairing whose code could have been forced (ADR-042); shown as "Not verified". */
    val verified: Boolean = true,
)

/**
 * The JVM-side pairing driver: the SAME [DefaultFlashPairingProtocol] and the same `FLASH_PAIR`
 * wire framing the phone runs, so a desktop pairs with a phone with no phone-side change.
 *
 * ## Why this lives here (moved 2026-09-14)
 *
 * It was `:desktop`'s `DesktopPairingCoordinator`. It moved into `:core:security`'s `jvmMain` — next
 * to the protocol it drives — for two reasons:
 *
 * 1. **The interop harness must exercise THIS code, not a copy.** The harness is the CLI gate for
 *    the hardware ladder; if it drove its own responder, "pairing passes in the harness" would prove
 *    nothing about the desktop app the human actually tests. That is the same "a gate that cannot
 *    see what the product sees is not a gate" rule that put the multicast transport into the harness
 *    the same day.
 * 2. **It was about to become the third copy of the same wire codec.** The app keeps its own
 *    (`:app`'s `PairingFraming` is `internal`, so `:desktop` could not import it); a harness copy
 *    would have been the third. One codec, in one place, is what makes a mismatch impossible.
 *
 * `jvmMain` rather than `commonMain` because both consumers are JVM (the desktop app and the
 * harness) and this file uses `ConcurrentHashMap`. Hoisting a `commonMain` version so the Android
 * host can drop its twin is a follow-up, not a prerequisite.
 *
 * ## Behaviour the hosts rely on
 *
 * - [onSessionUp] must be called on **every** session-up edge: the hello it sends carries our
 *   identity fingerprint, and the peer cannot derive the numeric-comparison code without it.
 * - [beginPair] is invoked from a UI tap and must never block on a socket write: [sendToPeer] is
 *   non-blocking by contract and returns **false when there is no live session**, which is the only
 *   thing that distinguishes "we never reached the peer" from "the peer never answered"
 *   (`Couldn't reach` vs `Still can't reach`).
 * - The begin-flow race (a tap that lands before the peer's hello arrives) is handled by
 *   [pendingPair] plus a bounded fingerprint poll, exactly as the app twin does.
 */
public class FlashPairingCoordinator(
    public val localFingerprintHex: String,
    private val localDeviceId: String,
    private val localName: String,
    private val localModel: String,
    private val ephemeralPublicKey: ByteArray,
    private val trustStore: FlashTrustStore,
    private val scope: CoroutineScope,
    private val sendToPeer: (peerId: String, text: String) -> Boolean,
    private val crypto: FlashCrypto? = null,
    private val ephemeralKeyPair: FlashEcKeyPair? = null,
) {

    /** UI snapshot for a pairing pane; null when no pairing is in flight. */
    public data class PairingUi(
        val peerName: String,
        val numericCode: String,
        val phase: PairingPhase,
        val secondsLeft: Int,
    )

    private val _pairing = MutableStateFlow<PairingUi?>(null)
    public val pairing: StateFlow<PairingUi?> = _pairing.asStateFlow()

    /**
     * Trusted peers, as an observable flow.
     *
     * Published from here because a plain trust store is not observable: a host that derives its
     * peer list with `derivedStateOf { trust.getTrustedPeers() }` reads nothing reactive, computes
     * once, and never invalidates — which left the desktop's Nearby showing a just-paired peer as
     * **Pair** (and a revoked peer as **Chat**) forever, breaking ladder steps L2(e) and L7. This
     * coordinator is the only place that knows when trust changed.
     */
    private val _trustedPeers = MutableStateFlow(loadTrusted())
    public val trustedPeers: StateFlow<List<FlashTrustedPeer>> = _trustedPeers.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = MESSAGE_BUFFER)
    public val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** peerId -> fingerprint hex, learned from FLASH_PAIR hellos (an initiator needs it). */
    private val fingerprints = ConcurrentHashMap<String, String>()

    /** peerId -> pairing protocol version from its hello (1 when it advertises none). */
    private val peerVersions = ConcurrentHashMap<String, Int>()

    private val pendingLock = Any()
    private var pendingPair: Pair<String, String>? = null

    @Volatile
    private var protocol: DefaultFlashPairingProtocol = newProtocol()
    private var collectorJob: Job = launchCollectors()

    /** A WS session to [peerId] came up — announce our fingerprint so the peer can derive the code. */
    public fun onSessionUp(peerId: String) {
        sendToPeer(peerId, PairingWireCodec.encodeHello(localFingerprintHex))
    }

    /** Feed a decoded `FLASH_PAIR` line: hello updates the cache, frames drive the protocol. */
    public fun onInbound(peerId: String, text: String) {
        when (val inbound = PairingWireCodec.decode(text)) {
            is PairingWireCodec.Inbound.Hello -> {
                fingerprints[peerId] = inbound.fingerprintHex
                peerVersions[peerId] = inbound.protocolVersion
                // Answer, and answer with a PLAIN hello: the request flag is never echoed, so this
                // cannot ping-pong. This is the half that did not exist before — a responder only
                // ever announced itself once, on its session-up edge, so a hello that was missed (or
                // that raced the initiator's own session) left the initiator permanently unable to
                // pair, with `Still can't reach … then Pair again` as its only feedback, forever.
                if (inbound.request) sendToPeer(peerId, PairingWireCodec.encodeHello(localFingerprintHex))
                val pending = synchronized(pendingLock) {
                    pendingPair?.takeIf { it.first == peerId }?.also { pendingPair = null }
                }
                if (pending != null) {
                    scope.launch { beginIfPeerSupportsV2(pending.first, pending.second, inbound.fingerprintHex) }
                }
            }
            is PairingWireCodec.Inbound.Frame -> scope.launch { protocol.onFrame(inbound.frame) }
            null -> Unit
        }
    }

    /** Initiator: the user picked Pair on [peerId]. Needs the peer's fingerprint (from its hello). */
    public fun beginPair(peerId: String, peerName: String, onNeedRetry: (String) -> Unit = {}) {
        val known = fingerprints[peerId]
        if (known != null) {
            scope.launch { beginIfPeerSupportsV2(peerId, peerName, known) }
            return
        }
        synchronized(pendingLock) { pendingPair = peerId to peerName }
        val delivered = sendToPeer(peerId, PairingWireCodec.encodeHello(localFingerprintHex, request = true))
        _messages.tryEmit(if (delivered) "Connecting to $peerName…" else "Couldn't reach $peerName.")
        scope.launch {
            val deadline = SystemTimeSource.nowMs() + FINGERPRINT_WAIT_MS
            var arrived: String? = null
            var lastAskMs = SystemTimeSource.nowMs()
            while (SystemTimeSource.nowMs() < deadline) {
                fingerprints[peerId]?.let { arrived = it; break }
                // Re-ask rather than merely re-wait. One hello sent once is a single point of failure:
                // a session that comes up in a different order than the peer's announcement assumes
                // loses it, and nothing retries. Bounded by the window (≤ 8 asks at 400 ms), and each
                // ask draws at most one answer.
                val now = SystemTimeSource.nowMs()
                if (now - lastAskMs >= HELLO_RESEND_MS) {
                    lastAskMs = now
                    sendToPeer(peerId, PairingWireCodec.encodeHello(localFingerprintHex, request = true))
                }
                delay(FINGERPRINT_POLL_MS)
            }
            val claimed = synchronized(pendingLock) {
                (pendingPair?.first == peerId).also { if (it) pendingPair = null }
            }
            if (!claimed) return@launch
            val fp = arrived ?: fingerprints[peerId]
            if (fp != null) beginIfPeerSupportsV2(peerId, peerName, fp)
            else onNeedRetry("Still can't reach $peerName. Check that it is nearby, then Pair again.")
        }
    }

    /**
     * Refuses to pair with a peer that only speaks v1 (ADR-042, owner decision 2026-09-23): its code
     * could be forced by a man-in-the-middle, so no new pairing may be made with it.
     */
    private fun beginIfPeerSupportsV2(peerId: String, peerName: String, fingerprint: String) {
        if ((peerVersions[peerId] ?: 1) < PairingWireCodec.PROTOCOL_VERSION) {
            _messages.tryEmit("$peerName is running an older Flash. Update Flash on that device, then pair again.")
            return
        }
        protocol.beginRequest(peerId, peerName, fingerprint)
    }

    /** Responder accepted the displayed code match. */
    public fun acceptLocal() {
        scope.launch { protocol.respondAccept() }
    }

    /** Decline an active request, or dismiss a terminal dialog — back to a clean slate. */
    public fun declineLocal() {
        scope.launch {
            protocol.respondDecline()
            resetProtocol()
        }
    }

    /** Forget a trusted peer (persisted via the trust store). */
    public fun revoke(peerId: String) {
        trustStore.revokeTrust(FlashDeviceId(peerId))
        _trustedPeers.value = loadTrusted()
    }

    /** Retrieve the cached peer identity fingerprint (hex) if known. */
    public fun getPeerFingerprint(peerId: String): String? = fingerprints[peerId]

    /** User-facing line for a failed pairing; null when the generic line will do. */
    private fun failureMessage(reason: String): String? = when (reason) {
        "peer-update-required" -> "The other device is running an older Flash. Update it, then pair again."
        "identity-mismatch", "commit-mismatch", "paired-material-mismatch", "code-hash-mismatch" ->
            "Pairing stopped: the security check failed. If this keeps happening, something may be " +
                "interfering with the connection."
        else -> null
    }

    private fun loadTrusted(): List<FlashTrustedPeer> =
        trustStore.getTrustedPeers()
            .map { (id, name) -> FlashTrustedPeer(id = id.value, name = name, verified = trustStore.isVerified(id)) }
            .sortedBy { it.name.lowercase() }

    // ------------------------------------------------------------------ internals

    private fun handleEvent(event: FlashPairingEvent) {
        when (event) {
            is FlashPairingEvent.PeerAccepted -> {
                // Initiator half: onFrame(PairAccept) reports acceptance but does NOT send
                // PAIR_CONFIRM — the confirm is ours to send (same as the app twin).
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
                _messages.tryEmit("Paired with ${s.peerName ?: s.peerDeviceId?.take(SHORT_ID) ?: "peer"}.")
                scope.launch {
                    delay(PAIRED_LINGER_MS)
                    resetProtocol()
                }
            }
            is FlashPairingEvent.PeerDeclined,
            is FlashPairingEvent.Expired,
            is FlashPairingEvent.Failed -> {
                val detail = (event as? FlashPairingEvent.Failed)?.reason?.let(::failureMessage)
                _messages.tryEmit(detail ?: "Pairing ended (${event.javaClass.simpleName}).")
                scope.launch {
                    delay(TERMINAL_LINGER_MS)
                    resetProtocol()
                }
            }
            is FlashPairingEvent.RequestReceived -> Unit // a host raises its dialog off `pairing`.
        }
    }

    private fun recomputeUi(s: PairingSessionState) {
        if (s.phase == PairingPhase.Idle) {
            _pairing.value = null
            return
        }
        val peerName = s.peerName?.ifBlank { null } ?: s.peerDeviceId?.take(SHORT_ID) ?: "Device"
        _pairing.value = PairingUi(
            peerName = peerName,
            numericCode = s.code6 ?: "",
            phase = s.phase,
            secondsLeft = s.expiresAtMs
                ?.let { ((it - SystemTimeSource.nowMs()).coerceAtLeast(0L) / 1000L).toInt() }
                ?: 0,
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

    private suspend fun tickWhileInFlight(p: DefaultFlashPairingProtocol) {
        p.session
            .map { it.phase != PairingPhase.Idle }
            .distinctUntilChanged()
            .collectLatest { inFlight ->
                if (!inFlight) return@collectLatest
                while (true) {
                    delay(TICK_MS)
                    p.onTick(SystemTimeSource.nowMs())
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
        sendFrame = { frame ->
            protocol.session.value.peerDeviceId?.let { peerId ->
                sendToPeer(peerId, PairingWireCodec.encode(frame))
            }
            Unit
        },
        timeSource = SystemTimeSource,
    )

    private companion object {
        const val TICK_MS = 1000L
        const val FINGERPRINT_WAIT_MS = 3000L
        const val FINGERPRINT_POLL_MS = 100L
        /** Re-ask cadence inside [FINGERPRINT_WAIT_MS]: ≤ 8 asks, each answered at most once. */
        const val HELLO_RESEND_MS = 400L
        const val PAIRED_LINGER_MS = 1800L
        const val TERMINAL_LINGER_MS = 2500L
        const val SHORT_ID = 8
        const val MESSAGE_BUFFER = 8
    }
}
