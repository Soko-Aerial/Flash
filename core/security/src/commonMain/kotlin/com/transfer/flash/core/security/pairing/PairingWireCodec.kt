package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.protocol.FlashTextFraming

/**
 * The ONE text wire codec for `FLASH_PAIR` lines, shared by the Android and desktop coordinators
 * (ADR-042). They used to carry byte-identical private copies, which a protocol change would have
 * had to keep in lock-step by hand.
 *
 * Wire shape: `FLASH_PAIR t=<type> key=value…` via [FlashTextFraming]; byte arrays are standard
 * Base64 (RFC 4648, the same alphabet both old copies used, so v1 lines still decode).
 *
 * v2 additions: `v` (protocol version) on `hello` and `req`, `cm` (commitment) on `req`, and the
 * `nonce`/`reveal` frame types carrying `n`.
 */
@FlashInternalApi
public object PairingWireCodec {

    public const val PREFIX: String = "FLASH_PAIR"

    private const val KEY_TYPE = "t"
    private const val KEY_REQUEST_ID = "rid"
    private const val KEY_DEVICE_ID = "did"
    private const val KEY_NAME = "name"
    private const val KEY_MODEL = "model"
    private const val KEY_FINGERPRINT = "fp"
    private const val KEY_EPHEMERAL_KEY = "epk"
    private const val KEY_CREATED_AT = "ts"
    private const val KEY_CODE_HASH = "ch"
    private const val KEY_HELLO_REQUEST = "hrq"
    private const val KEY_VERSION = "v"
    private const val KEY_COMMIT = "cm"
    private const val KEY_NONCE = "n"

    private const val TYPE_HELLO = "hello"
    private const val TYPE_REQUEST = "req"
    private const val TYPE_NONCE = "nonce"
    private const val TYPE_REVEAL = "reveal"
    private const val TYPE_ACCEPT = "acc"
    private const val TYPE_CONFIRM = "con"
    private const val TYPE_PAIRED = "paired"

    /** The protocol version this build speaks and advertises in its hello. */
    public const val PROTOCOL_VERSION: Int = PairingV2.PROTOCOL_VERSION

    /** A decoded inbound pairing line. */
    public sealed interface Inbound {
        /**
         * The sender's identity fingerprint. [request] asks for our hello back (never echoed, so the
         * exchange terminates). [protocolVersion] is 1 for a peer that does not advertise one.
         */
        public data class Hello(
            val fingerprintHex: String,
            val request: Boolean = false,
            val protocolVersion: Int = 1,
        ) : Inbound

        public data class Frame(val frame: FlashPairingFrame) : Inbound
    }

    public fun encodeHello(fingerprintHex: String, request: Boolean = false): String =
        FlashTextFraming.encodeFields(
            PREFIX,
            buildList {
                add(KEY_TYPE to TYPE_HELLO)
                add(KEY_FINGERPRINT to fingerprintHex)
                add(KEY_VERSION to PROTOCOL_VERSION.toString())
                if (request) add(KEY_HELLO_REQUEST to "1")
            },
        )

    public fun encode(frame: FlashPairingFrame): String = when (frame) {
        is FlashPairingFrame.PairRequest -> FlashTextFraming.encodeFields(
            PREFIX,
            buildList {
                add(KEY_TYPE to TYPE_REQUEST)
                add(KEY_REQUEST_ID to frame.requestId)
                add(KEY_DEVICE_ID to frame.senderDeviceId)
                add(KEY_NAME to frame.senderName)
                add(KEY_MODEL to frame.senderModel)
                add(KEY_FINGERPRINT to frame.senderFingerprintHex)
                add(KEY_EPHEMERAL_KEY to Base64.encode(frame.senderEphemeralPublicKey))
                add(KEY_CREATED_AT to frame.createdAt.toString())
                add(KEY_VERSION to frame.protocolVersion.toString())
                frame.commitHex?.let { add(KEY_COMMIT to it) }
            },
        )
        is FlashPairingFrame.PairNonce -> FlashTextFraming.encodeFields(
            PREFIX,
            listOf(
                KEY_TYPE to TYPE_NONCE,
                KEY_REQUEST_ID to frame.requestId,
                KEY_FINGERPRINT to frame.responderFingerprintHex,
                KEY_EPHEMERAL_KEY to Base64.encode(frame.responderEphemeralPublicKey),
                KEY_NONCE to Base64.encode(frame.nonce),
            ),
        )
        is FlashPairingFrame.PairReveal -> FlashTextFraming.encodeFields(
            PREFIX,
            listOf(KEY_TYPE to TYPE_REVEAL, KEY_REQUEST_ID to frame.requestId, KEY_NONCE to Base64.encode(frame.nonce)),
        )
        is FlashPairingFrame.PairAccept -> FlashTextFraming.encodeFields(
            PREFIX,
            listOf(KEY_TYPE to TYPE_ACCEPT, KEY_REQUEST_ID to frame.requestId),
        )
        is FlashPairingFrame.PairConfirm -> FlashTextFraming.encodeFields(
            PREFIX,
            listOf(KEY_TYPE to TYPE_CONFIRM, KEY_REQUEST_ID to frame.requestId, KEY_CODE_HASH to frame.codeHashHex),
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

    /** Parses a `FLASH_PAIR` line; null when the prefix, type or a required field is missing or malformed. */
    public fun decode(text: String): Inbound? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        fun bytes(key: String): ByteArray? = fields[key]?.let { runCatching { Base64.decode(it) }.getOrNull() }
        val version = fields[KEY_VERSION]?.toIntOrNull() ?: 1
        return when (fields[KEY_TYPE]) {
            TYPE_HELLO -> fields[KEY_FINGERPRINT]?.let {
                Inbound.Hello(it, request = fields[KEY_HELLO_REQUEST] == "1", protocolVersion = version)
            }
            TYPE_REQUEST -> Inbound.Frame(
                FlashPairingFrame.PairRequest(
                    requestId = fields[KEY_REQUEST_ID] ?: return null,
                    senderDeviceId = fields[KEY_DEVICE_ID] ?: return null,
                    senderName = fields[KEY_NAME] ?: return null,
                    senderModel = fields[KEY_MODEL] ?: return null,
                    senderFingerprintHex = fields[KEY_FINGERPRINT] ?: return null,
                    senderEphemeralPublicKey = bytes(KEY_EPHEMERAL_KEY) ?: return null,
                    createdAt = fields[KEY_CREATED_AT]?.toLongOrNull() ?: return null,
                    protocolVersion = version,
                    commitHex = fields[KEY_COMMIT],
                ),
            )
            TYPE_NONCE -> Inbound.Frame(
                FlashPairingFrame.PairNonce(
                    requestId = fields[KEY_REQUEST_ID] ?: return null,
                    responderFingerprintHex = fields[KEY_FINGERPRINT] ?: return null,
                    responderEphemeralPublicKey = bytes(KEY_EPHEMERAL_KEY) ?: return null,
                    nonce = bytes(KEY_NONCE)?.takeIf { it.size == PairingV2.NONCE_BYTES } ?: return null,
                ),
            )
            TYPE_REVEAL -> Inbound.Frame(
                FlashPairingFrame.PairReveal(
                    requestId = fields[KEY_REQUEST_ID] ?: return null,
                    nonce = bytes(KEY_NONCE)?.takeIf { it.size == PairingV2.NONCE_BYTES } ?: return null,
                ),
            )
            TYPE_ACCEPT -> fields[KEY_REQUEST_ID]?.let { Inbound.Frame(FlashPairingFrame.PairAccept(it)) }
            TYPE_CONFIRM -> Inbound.Frame(
                FlashPairingFrame.PairConfirm(
                    requestId = fields[KEY_REQUEST_ID] ?: return null,
                    codeHashHex = fields[KEY_CODE_HASH] ?: return null,
                ),
            )
            TYPE_PAIRED -> Inbound.Frame(
                FlashPairingFrame.Paired(
                    requestId = fields[KEY_REQUEST_ID] ?: return null,
                    peerFingerprintHex = fields[KEY_FINGERPRINT] ?: return null,
                    peerEphemeralPublicKey = bytes(KEY_EPHEMERAL_KEY) ?: return null,
                ),
            )
            else -> null
        }
    }
}
