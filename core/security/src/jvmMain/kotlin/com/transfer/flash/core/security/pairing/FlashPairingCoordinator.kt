@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashTextFraming
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
public data class FlashTrustedPeer(val id: String, val name: String)

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

    private val pendingLock = Any()
    private var pendingPair: Pair<String, String>? = null

    @Volatile
    private var protocol: DefaultFlashPairingProtocol = newProtocol()
    private var collectorJob: Job = launchCollectors()

    /** A WS session to [peerId] came up — announce our fingerprint so the peer can derive the code. */
    public fun onSessionUp(peerId: String) {
        sendToPeer(peerId, encodeHello(localFingerprintHex))
    }

    /** Feed a decoded `FLASH_PAIR` line: hello updates the cache, frames drive the protocol. */
    public fun onInbound(peerId: String, text: String) {
        when (val inbound = decodeInbound(text)) {
            is Inbound.Hello -> {
                fingerprints[peerId] = inbound.fingerprintHex
                // Answer, and answer with a PLAIN hello: the request flag is never echoed, so this
                // cannot ping-pong. This is the half that did not exist before — a responder only
                // ever announced itself once, on its session-up edge, so a hello that was missed (or
                // that raced the initiator's own session) left the initiator permanently unable to
                // pair, with `Still can't reach … then Pair again` as its only feedback, forever.
                if (inbound.request) sendToPeer(peerId, encodeHello(localFingerprintHex))
                val pending = synchronized(pendingLock) {
                    pendingPair?.takeIf { it.first == peerId }?.also { pendingPair = null }
                }
                if (pending != null) {
                    scope.launch { protocol.beginRequest(pending.first, pending.second, inbound.fingerprintHex) }
                }
            }
            is Inbound.Frame -> scope.launch { protocol.onFrame(inbound.frame) }
            null -> Unit
        }
    }

    /** Initiator: the user picked Pair on [peerId]. Needs the peer's fingerprint (from its hello). */
    public fun beginPair(peerId: String, peerName: String, onNeedRetry: (String) -> Unit = {}) {
        val known = fingerprints[peerId]
        if (known != null) {
            scope.launch { protocol.beginRequest(peerId, peerName, known) }
            return
        }
        synchronized(pendingLock) { pendingPair = peerId to peerName }
        val delivered = sendToPeer(peerId, encodeHello(localFingerprintHex, request = true))
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
                    sendToPeer(peerId, encodeHello(localFingerprintHex, request = true))
                }
                delay(FINGERPRINT_POLL_MS)
            }
            val claimed = synchronized(pendingLock) {
                (pendingPair?.first == peerId).also { if (it) pendingPair = null }
            }
            if (!claimed) return@launch
            val fp = arrived ?: fingerprints[peerId]
            if (fp != null) protocol.beginRequest(peerId, peerName, fp)
            else onNeedRetry("Still can't reach $peerName. Check that it is nearby, then Pair again.")
        }
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

    private fun loadTrusted(): List<FlashTrustedPeer> =
        trustStore.getTrustedPeers()
            .map { (id, name) -> FlashTrustedPeer(id = id.value, name = name) }
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
                    sendToPeer(peerId, encodeFrame(FlashPairingFrame.PairConfirm(event.requestId, hash)))
                }
            }
            is FlashPairingEvent.Confirmed -> {
                val s = protocol.session.value
                s.peerDeviceId?.let { peerId ->
                    val name = s.peerName?.ifBlank { null } ?: peerId.take(SHORT_ID)
                    trustStore.trustPeer(FlashDeviceId(peerId), name)
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
                val reason = event.javaClass.simpleName.removePrefix("FlashPairingEvent.")
                _messages.tryEmit("Pairing ended ($reason).")
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
        sendFrame = { frame ->
            protocol.session.value.peerDeviceId?.let { peerId ->
                sendToPeer(peerId, encodeFrame(frame))
            }
            Unit
        },
        timeSource = SystemTimeSource,
    )

    // ------------------------------------------------------------------ wire codec

    private sealed interface Inbound {
        /**
         * [request] asks the receiver to answer with ITS hello. Only a REQUEST is ever answered,
         * and an answer never asks — that is what makes the exchange terminate by construction:
         * a request produces at most one answer, an answer produces none.
         */
        data class Hello(val fingerprintHex: String, val request: Boolean = false) : Inbound
        data class Frame(val frame: FlashPairingFrame) : Inbound
    }

    private fun encodeHello(fingerprintHex: String, request: Boolean = false): String =
        FlashTextFraming.encodeFields(
            PREFIX,
            buildList {
                add(KEY_TYPE to TYPE_HELLO)
                add(KEY_FINGERPRINT to fingerprintHex)
                if (request) add(KEY_HELLO_REQUEST to "1")
            },
        )

    private fun encodeFrame(frame: FlashPairingFrame): String = when (frame) {
        is FlashPairingFrame.PairRequest ->
            FlashTextFraming.encodeFields(
                PREFIX,
                listOf(
                    KEY_TYPE to TYPE_REQUEST,
                    KEY_REQUEST_ID to frame.requestId,
                    KEY_DEVICE_ID to frame.senderDeviceId,
                    KEY_NAME to frame.senderName,
                    KEY_MODEL to frame.senderModel,
                    KEY_FINGERPRINT to frame.senderFingerprintHex,
                    KEY_EPHEMERAL_KEY to Base64Url.encode(frame.senderEphemeralPublicKey),
                    KEY_CREATED_AT to frame.createdAt.toString(),
                ),
            )
        is FlashPairingFrame.PairAccept ->
            FlashTextFraming.encodeFields(
                PREFIX,
                listOf(KEY_TYPE to TYPE_ACCEPT, KEY_REQUEST_ID to frame.requestId),
            )
        is FlashPairingFrame.PairConfirm ->
            FlashTextFraming.encodeFields(
                PREFIX,
                listOf(
                    KEY_TYPE to TYPE_CONFIRM,
                    KEY_REQUEST_ID to frame.requestId,
                    KEY_CODE_HASH to frame.codeHashHex,
                ),
            )
        is FlashPairingFrame.Paired ->
            FlashTextFraming.encodeFields(
                PREFIX,
                listOf(
                    KEY_TYPE to TYPE_PAIRED,
                    KEY_REQUEST_ID to frame.requestId,
                    KEY_FINGERPRINT to frame.peerFingerprintHex,
                    KEY_EPHEMERAL_KEY to Base64Url.encode(frame.peerEphemeralPublicKey),
                ),
            )
    }

    private fun decodeInbound(text: String): Inbound? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        return when (fields[KEY_TYPE]) {
            TYPE_HELLO -> fields[KEY_FINGERPRINT]?.let {
                Inbound.Hello(it, request = fields[KEY_HELLO_REQUEST] == "1")
            }
            TYPE_REQUEST -> {
                val rid = fields[KEY_REQUEST_ID] ?: return null
                val did = fields[KEY_DEVICE_ID] ?: return null
                val name = fields[KEY_NAME] ?: return null
                val model = fields[KEY_MODEL] ?: return null
                val fp = fields[KEY_FINGERPRINT] ?: return null
                val epk = fields[KEY_EPHEMERAL_KEY]?.let { runCatching { Base64Url.decode(it) }.getOrNull() }
                    ?: return null
                val ts = fields[KEY_CREATED_AT]?.toLongOrNull() ?: return null
                Inbound.Frame(
                    FlashPairingFrame.PairRequest(
                        requestId = rid,
                        senderDeviceId = did,
                        senderName = name,
                        senderModel = model,
                        senderFingerprintHex = fp,
                        senderEphemeralPublicKey = epk,
                        createdAt = ts,
                    ),
                )
            }
            TYPE_ACCEPT -> fields[KEY_REQUEST_ID]?.let {
                Inbound.Frame(FlashPairingFrame.PairAccept(it))
            }
            TYPE_CONFIRM -> {
                val rid = fields[KEY_REQUEST_ID] ?: return null
                val ch = fields[KEY_CODE_HASH] ?: return null
                Inbound.Frame(FlashPairingFrame.PairConfirm(rid, ch))
            }
            TYPE_PAIRED -> {
                val rid = fields[KEY_REQUEST_ID] ?: return null
                val fp = fields[KEY_FINGERPRINT] ?: return null
                val epk = fields[KEY_EPHEMERAL_KEY]?.let { runCatching { Base64Url.decode(it) }.getOrNull() }
                    ?: return null
                Inbound.Frame(FlashPairingFrame.Paired(rid, fp, epk))
            }
            else -> null
        }
    }

    private companion object {
        const val PREFIX = "FLASH_PAIR"
        const val KEY_TYPE = "t"
        const val KEY_REQUEST_ID = "rid"
        const val KEY_DEVICE_ID = "did"
        const val KEY_NAME = "name"
        const val KEY_MODEL = "model"
        const val KEY_FINGERPRINT = "fp"
        const val KEY_EPHEMERAL_KEY = "epk"
        const val KEY_CREATED_AT = "ts"
        const val KEY_CODE_HASH = "ch"
        /** Present-and-"1" on a hello that asks for the peer's hello back. Absent on answers and
         *  on the session-up announcement, which is what keeps the exchange acyclic. */
        const val KEY_HELLO_REQUEST = "hrq"
        const val TYPE_HELLO = "hello"
        const val TYPE_REQUEST = "req"
        const val TYPE_ACCEPT = "acc"
        const val TYPE_CONFIRM = "con"
        const val TYPE_PAIRED = "paired"
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

/** kotlin.io.encoding Base64, standard variant — byte-identical to the app twin's wire codec. */
private object Base64Url {
    private val b64 = kotlin.io.encoding.Base64
    fun encode(bytes: ByteArray): String = b64.encode(bytes)
    fun decode(text: String): ByteArray = b64.decode(text)
}
