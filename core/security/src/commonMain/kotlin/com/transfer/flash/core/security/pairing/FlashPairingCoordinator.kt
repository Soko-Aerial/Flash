@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.trust.FlashTrustStore
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
 * The pairing surface an app embeds, with no UI attached (Flash Watch, a headless node, a custom screen).
 *
 * Reach it through `FlashEngine.pairing`. It carries the whole user-visible flow as data:
 *
 * 1. [beginPair] on a discovered device the engine already has a session with.
 * 2. [pairing] shows the comparison code ([FlashPairingCoordinator.PairingUi.numericCode]) and the phase. Both
 *    devices show the same six digits; the user checks they match.
 * 3. The responder calls [acceptLocal] (or [declineLocal]); the initiator's confirm is sent by the engine.
 * 4. [trustedPeers] changes when the pairing completes, and [messages] carries short status lines
 *    ("Connecting to X…", "Paired with X.").
 *
 * The engine owns the wire side (`FLASH_PAIR` frames and the hello on every session-up edge); a host never feeds
 * frames in.
 */
public interface FlashPairing {
    /** This device's identity fingerprint, as it is shown next to a peer's when verifying by eye. */
    public val localFingerprintHex: String

    /** The pairing in flight, or null when idle. */
    public val pairing: StateFlow<FlashPairingCoordinator.PairingUi?>

    /** Paired peers; emits again whenever trust changes. */
    public val trustedPeers: StateFlow<List<FlashTrustedPeer>>

    /** Short, user-facing status lines. A collector that is not running misses them. */
    public val messages: SharedFlow<String>

    /**
     * Initiator: the user chose to pair with [peerId]. Never blocks on the network. [onNeedRetry] is called with
     * a sentence when the peer never answered.
     */
    public fun beginPair(peerId: String, peerName: String, onNeedRetry: (String) -> Unit = {})

    /** Responder: the displayed code matches. */
    public fun acceptLocal()

    /** Decline an active request, or dismiss a finished one. */
    public fun declineLocal()

    /** Forget a paired peer. */
    public fun revoke(peerId: String)
}

/**
 * The pairing driver shared by every host: the SAME [DefaultFlashPairingProtocol] and the same `FLASH_PAIR`
 * wire framing on the phone, the watch and the desktop, so each pairs with the others with no change on either side.
 *
 * ## Why this lives here
 *
 * It was `:desktop`'s `DesktopPairingCoordinator`, then `:core:security`'s `jvmMain` (2026-09-14) so the interop
 * harness exercised the code the desktop app runs rather than a copy. It is now `commonMain` so
 * `Flash.create()` on Android can drive pairing too: before, an app built on the published library could discover
 * and connect to a device but could not pair, because only the Flash app itself carried a private pairing
 * driver (`FLASH_PAIR` frames fell through the engine's router). The Flash Android app still keeps its own twin
 * (`:app`'s `PairingCoordinator`, which maps to its Compose UI types); it is not touched by this move.
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
    override val localFingerprintHex: String,
    private val localDeviceId: String,
    localName: String,
    private val localModel: String,
    private val ephemeralPublicKey: ByteArray,
    private val trustStore: FlashTrustStore,
    private val scope: CoroutineScope,
    private val sendToPeer: (peerId: String, text: String) -> Boolean,
    private val crypto: FlashCrypto? = null,
    private val ephemeralKeyPair: FlashEcKeyPair? = null,
) : FlashPairing {

    /** UI snapshot for a pairing pane; null when no pairing is in flight. */
    public data class PairingUi(
        val peerName: String,
        val numericCode: String,
        val phase: PairingPhase,
        val secondsLeft: Int,
    )

    private val _pairing = MutableStateFlow<PairingUi?>(null)
    override val pairing: StateFlow<PairingUi?> = _pairing.asStateFlow()

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
    override val trustedPeers: StateFlow<List<FlashTrustedPeer>> = _trustedPeers.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = MESSAGE_BUFFER)
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** peerId -> fingerprint hex, learned from FLASH_PAIR hellos (an initiator needs it). */
    private val fingerprints = SyncMap<String, String>()

    /** peerId -> pairing protocol version from its hello (1 when it advertises none). */
    private val peerVersions = SyncMap<String, Int>()

    /** peerId -> name of a pair the user asked for before that peer's hello arrived. Removal is the atomic claim. */
    private val pendingPairs = SyncMap<String, String>()

    /** The name a pairing request carries. Changed only by [updateLocalName]; read when a protocol is built. */
    @Volatile
    private var localName: String = localName

    @Volatile
    private var protocol: DefaultFlashPairingProtocol = newProtocol()
    private var collectorJob: Job = launchCollectors()

    /**
     * This device was renamed: the next pairing request carries [newName].
     *
     * The protocol stamps the name when it is built, and every pairing ending rebuilds it, so a pairing already
     * on screen keeps the name it started with and the next one picks the new name up. An idle protocol is
     * rebuilt before this returns (not from a launched coroutine), so a `beginPair` issued right after a rename
     * is already under the new name.
     */
    public fun updateLocalName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == localName) return
        localName = trimmed
        if (protocol.session.value.phase == PairingPhase.Idle) resetProtocol()
    }

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
                val pendingName = pendingPairs.remove(peerId)
                if (pendingName != null) {
                    scope.launch { beginIfPeerSupportsV2(peerId, pendingName, inbound.fingerprintHex) }
                }
            }
            is PairingWireCodec.Inbound.Frame -> scope.launch { protocol.onFrame(inbound.frame) }
            null -> Unit
        }
    }

    /** Initiator: the user picked Pair on [peerId]. Needs the peer's fingerprint (from its hello). */
    override fun beginPair(peerId: String, peerName: String, onNeedRetry: (String) -> Unit) {
        val known = fingerprints[peerId]
        if (known != null) {
            scope.launch { beginIfPeerSupportsV2(peerId, peerName, known) }
            return
        }
        pendingPairs[peerId] = peerName
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
            val claimed = pendingPairs.remove(peerId) != null
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
    override fun acceptLocal() {
        scope.launch { protocol.respondAccept() }
    }

    /** Decline an active request, or dismiss a terminal dialog — back to a clean slate. */
    override fun declineLocal() {
        scope.launch {
            protocol.respondDecline()
            resetProtocol()
        }
    }

    /** One-tap pairing for a peer whose key a directory already verified; records the same local trust. */
    public fun pairVerifiedColleague(peerId: String, name: String): Boolean {
        val r = trustStore.trustPeer(FlashDeviceId(peerId), name)
        if (r !is com.transfer.flash.core.common.result.FlashResult.Success) return false
        trustStore.markVerified(FlashDeviceId(peerId))
        _trustedPeers.value = loadTrusted()
        return true
    }

    /** Forget a trusted peer (persisted via the trust store). */
    override fun revoke(peerId: String) {
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
                _messages.tryEmit(detail ?: "Pairing ended (${event::class.simpleName}).")
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
