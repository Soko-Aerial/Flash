@file:OptIn(
    com.transfer.flash.core.common.annotation.FlashInternalApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package com.transfer.flash.pairing

import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.security.pairing.FlashPairingFrame
import kotlin.io.encoding.Base64

/**
 * Text wire codec for the pairing handshake over the WebSocket mesh (bridges C2's pure
 * [FlashPairingFrame] types to the same `FLASH_*` line framing already used for chat/receipts/
 * transfer control in [com.transfer.flash.debug.DiscoveryEngineHolder]).
 *
 * All pairing traffic shares the [PREFIX] line prefix; a `t` field discriminates the subtype
 * (`hello`/`req`/`acc`/`con`/`paired`). The ephemeral public key [ByteArray] rides as Base64 in
 * the `epk` field — [FlashTextFraming.escape] turns the `=` padding into `%3D` so it round-trips,
 * and `+`/`/` are not delimiters. [Base64] is `kotlin.io.encoding` (API-agnostic, JVM-testable);
 * `android.util.Base64` is unavailable under plain JVM unit tests and `java.util.Base64` needs API 26
 * (> minSdk 24).
 *
 * The extra [Hello] frame closes the one design gap: [FlashPairingFrame]s carry key material but the
 * transport advertises no peer fingerprint, and `beginRequest` needs it up front to derive the shared
 * 6-digit code. Peers exchange [Hello] (their identity fingerprint) on every session-up so the
 * initiator has the responder's fingerprint cached before it taps Pair.
 */
object PairingFraming {

    const val PREFIX = "FLASH_PAIR"

    // Field keys (kept short; values are escaped by FlashTextFraming).
    private const val KEY_TYPE = "t"
    private const val KEY_REQUEST_ID = "rid"
    private const val KEY_DEVICE_ID = "did"
    private const val KEY_NAME = "name"
    private const val KEY_MODEL = "model"
    private const val KEY_FINGERPRINT = "fp"
    private const val KEY_EPHEMERAL_KEY = "epk"
    private const val KEY_CREATED_AT = "ts"
    private const val KEY_CODE_HASH = "ch"

    /**
     * Present-and-`"1"` on a hello that **asks** the receiver to answer with its own hello.
     *
     * Absent on answers, and absent on the session-up announcement. That asymmetry is what makes the
     * exchange terminate by construction: a request draws at most one answer, an answer draws none —
     * so there is no set of "already answered" peers to track and no ping-pong to bound.
     *
     * Mirrors `FlashPairingCoordinator`'s flag in `:core:security` (added 2026-09-14). An older peer
     * that does not know this key ignores it and behaves exactly as before, so the field is additive.
     */
    private const val KEY_HELLO_REQUEST = "hrq"

    private const val TYPE_HELLO = "hello"
    private const val TYPE_REQUEST = "req"
    private const val TYPE_ACCEPT = "acc"
    private const val TYPE_CONFIRM = "con"
    private const val TYPE_PAIRED = "paired"

    /** A decoded inbound pairing line: either an identity [Hello] or a handshake [Frame]. */
    sealed interface Inbound {
        /**
         * [request] is true when the sender is asking for our hello back. Only a request is ever
         * answered, and an answer never asks.
         */
        data class Hello(val fingerprintHex: String, val request: Boolean = false) : Inbound
        data class Frame(val frame: FlashPairingFrame) : Inbound
    }

    /**
     * Encodes this device's identity fingerprint as a hello line.
     *
     * Pass `request = true` only where the sender needs the peer's hello *now* and cannot rely on
     * having caught the peer's one session-up announcement — that is [PairingCoordinator.beginPair],
     * and nothing else. The session-up announcement stays a plain hello.
     */
    fun encodeHello(fingerprintHex: String, request: Boolean = false): String =
        FlashTextFraming.encodeFields(
            PREFIX,
            buildList {
                add(KEY_TYPE to TYPE_HELLO)
                add(KEY_FINGERPRINT to fingerprintHex)
                if (request) add(KEY_HELLO_REQUEST to "1")
            },
        )

    /** Encodes a handshake [frame] as a `FLASH_PAIR` line. */
    fun encode(frame: FlashPairingFrame): String = when (frame) {
        is FlashPairingFrame.PairRequest -> FlashTextFraming.encodeFields(
            PREFIX,
            listOf(
                KEY_TYPE to TYPE_REQUEST,
                KEY_REQUEST_ID to frame.requestId,
                KEY_DEVICE_ID to frame.senderDeviceId,
                KEY_NAME to frame.senderName,
                KEY_MODEL to frame.senderModel,
                KEY_FINGERPRINT to frame.senderFingerprintHex,
                KEY_EPHEMERAL_KEY to Base64.encode(frame.senderEphemeralPublicKey),
                KEY_CREATED_AT to frame.createdAt.toString(),
            ),
        )
        is FlashPairingFrame.PairAccept -> FlashTextFraming.encodeFields(
            PREFIX,
            listOf(KEY_TYPE to TYPE_ACCEPT, KEY_REQUEST_ID to frame.requestId),
        )
        is FlashPairingFrame.PairConfirm -> FlashTextFraming.encodeFields(
            PREFIX,
            listOf(
                KEY_TYPE to TYPE_CONFIRM,
                KEY_REQUEST_ID to frame.requestId,
                KEY_CODE_HASH to frame.codeHashHex,
            ),
        )
        is FlashPairingFrame.Paired -> FlashTextFraming.encodeFields(
            PREFIX,
            listOf(
                KEY_TYPE to TYPE_PAIRED,
                KEY_REQUEST_ID to frame.requestId,
                KEY_FINGERPRINT to frame.peerFingerprintHex,
                KEY_EPHEMERAL_KEY to Base64.encode(frame.peerEphemeralPublicKey),
            ),
        )
    }

    /** Parses a `FLASH_PAIR` line; returns null if the prefix/type/required fields are absent. */
    fun decode(text: String): Inbound? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        return when (fields[KEY_TYPE]) {
            TYPE_HELLO -> fields[KEY_FINGERPRINT]?.let {
                Inbound.Hello(it, request = fields[KEY_HELLO_REQUEST] == "1")
            }
            TYPE_REQUEST -> {
                val requestId = fields[KEY_REQUEST_ID] ?: return null
                val deviceId = fields[KEY_DEVICE_ID] ?: return null
                val name = fields[KEY_NAME] ?: return null
                val model = fields[KEY_MODEL] ?: return null
                val fingerprint = fields[KEY_FINGERPRINT] ?: return null
                val epk = fields[KEY_EPHEMERAL_KEY]?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
                val createdAt = fields[KEY_CREATED_AT]?.toLongOrNull() ?: return null
                Inbound.Frame(
                    FlashPairingFrame.PairRequest(
                        requestId = requestId,
                        senderDeviceId = deviceId,
                        senderName = name,
                        senderModel = model,
                        senderFingerprintHex = fingerprint,
                        senderEphemeralPublicKey = epk,
                        createdAt = createdAt,
                    ),
                )
            }
            TYPE_ACCEPT -> fields[KEY_REQUEST_ID]?.let { Inbound.Frame(FlashPairingFrame.PairAccept(it)) }
            TYPE_CONFIRM -> {
                val requestId = fields[KEY_REQUEST_ID] ?: return null
                val codeHash = fields[KEY_CODE_HASH] ?: return null
                Inbound.Frame(FlashPairingFrame.PairConfirm(requestId, codeHash))
            }
            TYPE_PAIRED -> {
                val requestId = fields[KEY_REQUEST_ID] ?: return null
                val fingerprint = fields[KEY_FINGERPRINT] ?: return null
                val epk = fields[KEY_EPHEMERAL_KEY]?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
                Inbound.Frame(FlashPairingFrame.Paired(requestId, fingerprint, epk))
            }
            else -> null
        }
    }
}
