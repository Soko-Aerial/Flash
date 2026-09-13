@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.pairing.DefaultFlashPairingProtocol
import com.transfer.flash.core.security.pairing.FlashPairingEvent
import com.transfer.flash.core.security.pairing.FlashPairingFrame
import com.transfer.flash.core.security.pairing.PairingPhase
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
 * Desktop twin of `:app`'s `PairingCoordinator` — Phase 26-3 second half (ADR-035).
 *
 * Drives the SAME commonMain protocol ([DefaultFlashPairingProtocol]) over the SAME
 * `FLASH_PAIR` wire framing, so a desktop pairs with a phone with no phone-side change: the
 * phone's Nearby screen shows the desktop as a discovered peer, either side taps Pair, both
 * dialogs display the same 6-digit numeric-comparison code, and trust persists on both ends
 * (desktop: `DesktopTrustStore`; the identity key that fingerprints it:
 * `PersistedFlashCrypto` — the P2 point of this phase).
 *
 * Differences from the app twin, all deliberate:
 * - The wire codec lives HERE rather than being shared, because the app's `PairingFraming` is
 *   `:app`-internal (not a published type) and `:desktop` cannot depend on `:app`. Byte-for-
 *   byte the same framing: `FLASH_PAIR` prefix, `t` discriminates hello/req/acc/con/paired,
 *   the ephemeral key rides Base64 in `epk` (same `kotlin.io.encoding` — JVM-safe).
 * - UI model is a plain data snapshot, not Compose: the desktop shell renders it in its
 *   pairing pane. `messages` are console-grade status lines.
 * - The begin-flow race handling (pending-pair + fingerprint poll) is ported as-is: it is
 *   protocol-level behaviour, not Android UI detail.
 */
public class DesktopPairingCoordinator(
    private val localFingerprintHex: String,
    private val localDeviceId: String,
    private val localName: String,
    private val localModel: String,
    private val ephemeralPublicKey: ByteArray,
    private val trustStore: FlashTrustStore,
    private val scope: CoroutineScope,
    private val sendToPeer: (peerId: String, text: String) -> Boolean,
) {

    /** UI snapshot for the pairing pane; null when no pairing is in flight. */
    public data class PairingUi(
        val peerName: String,
        val numericCode: String,
        val phase: PairingPhase,
        val secondsLeft: Int,
    )

    private val _pairing = MutableStateFlow<PairingUi?>(null)
    public val pairing: StateFlow<PairingUi?> = _pairing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = MESSAGE_BUFFER)
    public val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** peerId -> fingerprint hex, learned from FLASH_PAIR hellos (initiator needs it). */
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

    /** Initiator: user picked Pair on [peerId]. Needs the peer's fingerprint (from its hello). */
    public fun beginPair(peerId: String, peerName: String, onNeedRetry: (String) -> Unit = {}) {
        val known = fingerprints[peerId]
        if (known != null) {
            scope.launch { protocol.beginRequest(peerId, peerName, known) }
            return
        }
        synchronized(pendingLock) { pendingPair = peerId to peerName }
        val delivered = sendToPeer(peerId, encodeHello(localFingerprintHex))
        _messages.tryEmit(if (delivered) "Connecting to $peerName…" else "Couldn't reach $peerName.")
        scope.launch {
            val deadline = SystemTimeSource.nowMs() + FINGERPRINT_WAIT_MS
            var arrived: String? = null
            while (SystemTimeSource.nowMs() < deadline) {
                fingerprints[peerId]?.let { arrived = it; break }
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
    }

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
            is FlashPairingEvent.RequestReceived -> Unit // session collector raises the dialog.
        }
    }

    private fun recomputeUi(s: com.transfer.flash.core.security.pairing.PairingSessionState) {
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
        data class Hello(val fingerprintHex: String) : Inbound
        data class Frame(val frame: FlashPairingFrame) : Inbound
    }

    private fun encodeHello(fingerprintHex: String): String = FlashTextFraming.encodeFields(
        PREFIX,
        listOf(KEY_TYPE to TYPE_HELLO, KEY_FINGERPRINT to fingerprintHex),
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
            TYPE_HELLO -> fields[KEY_FINGERPRINT]?.let { Inbound.Hello(it) }
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
        const val TYPE_HELLO = "hello"
        const val TYPE_REQUEST = "req"
        const val TYPE_ACCEPT = "acc"
        const val TYPE_CONFIRM = "con"
        const val TYPE_PAIRED = "paired"
        const val TICK_MS = 1000L
        const val FINGERPRINT_WAIT_MS = 3000L
        const val FINGERPRINT_POLL_MS = 100L
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
