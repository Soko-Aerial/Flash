package com.transfer.flash.core.security.pairing

/**
 * Wire frames for the Flash pairing handshake (C2.6).
 *
 * Handshake shape, protocol v2 (ADR-042; initiator = I, responder = R):
 *
 * ```text
 * I -> R : PAIR_REQUEST (requestId, identity, fingerprint, ephemeral pubkey, v=2, commit to N_I)
 * R -> I : PAIR_NONCE   (requestId, R's fingerprint, R's ephemeral pubkey, N_R)
 * I -> R : PAIR_REVEAL  (requestId, N_I)             — R verifies the commitment
 *          both sides now derive and display the code (see [PairingV2])
 * R -> I : PAIR_ACCEPT  (requestId)                  — R's user saw the codes match
 * I -> R : PAIR_CONFIRM (requestId, codeHashHex — proof I derived the same code)
 * R -> I : PAIRED       (requestId, R's fingerprint, R's ephemeral pubkey)
 * ```
 *
 * v1 (no version, no commitment, code from the fingerprints alone) is refused: its code could be
 * forced by a man-in-the-middle (audit 2026-09-23, S2).
 *
 * Both devices display the same 6-digit numeric comparison code (see
 * [NumericComparisonCode]) while the user confirms on each screen; the local
 * accept/decline action doubles as "the codes matched". `PAIR_CONFIRM` carries a
 * hash of the displayed code so the responder cryptographically verifies that both
 * sides derived the same value before completing.
 *
 * NOTE: these are plain Kotlin transport types only. Binary/JSON wire encoding is
 * deliberately deferred to C4/C6 (frame codec). Fields are kept codec-friendly:
 * opaque hex strings for fingerprints/hashes, raw [ByteArray] for public keys.
 *
 * Equality note: [ByteArray] fields participate in equals/hashCode via content,
 * not identity.
 */
public sealed interface FlashPairingFrame {

    /** Unique identifier shared by all frames of one pairing attempt. */
    public val requestId: String

    /** Initiator announces itself and offers its key material. */
    public data class PairRequest(
        override val requestId: String,
        val senderDeviceId: String,
        val senderName: String,
        val senderModel: String,
        val senderFingerprintHex: String,
        val senderEphemeralPublicKey: ByteArray,
        val createdAt: Long,
        /** Absent (1) on a v1 request, which a v2 responder refuses. */
        val protocolVersion: Int = 1,
        /** v2: `PairingV2.commitHex(senderFingerprint, senderEphemeralKey, N_I)`; null on v1. */
        val commitHex: String? = null,
    ) : FlashPairingFrame {
        override fun equals(other: Any?): Boolean =
            other is PairRequest && other.requestId == requestId &&
                other.senderDeviceId == senderDeviceId &&
                other.senderName == senderName &&
                other.senderModel == senderModel &&
                other.senderFingerprintHex == senderFingerprintHex &&
                other.senderEphemeralPublicKey.contentEquals(senderEphemeralPublicKey) &&
                other.createdAt == createdAt &&
                other.protocolVersion == protocolVersion &&
                other.commitHex == commitHex

        override fun hashCode(): Int = listOf(
            requestId, senderDeviceId, senderName, senderModel, senderFingerprintHex, createdAt,
            protocolVersion, commitHex,
        ).hashCode() * 31 + senderEphemeralPublicKey.contentHashCode()
    }

    /** v2, responder → initiator: R's identity and ephemeral key, and R's nonce (R reveals first). */
    public data class PairNonce(
        override val requestId: String,
        val responderFingerprintHex: String,
        val responderEphemeralPublicKey: ByteArray,
        val nonce: ByteArray,
    ) : FlashPairingFrame {
        override fun equals(other: Any?): Boolean =
            other is PairNonce && other.requestId == requestId &&
                other.responderFingerprintHex == responderFingerprintHex &&
                other.responderEphemeralPublicKey.contentEquals(responderEphemeralPublicKey) &&
                other.nonce.contentEquals(nonce)

        override fun hashCode(): Int =
            ((requestId.hashCode() * 31 + responderFingerprintHex.hashCode()) * 31 +
                responderEphemeralPublicKey.contentHashCode()) * 31 + nonce.contentHashCode()
    }

    /** v2, initiator → responder: opens the commitment sent in [PairRequest]. */
    public data class PairReveal(
        override val requestId: String,
        val nonce: ByteArray,
    ) : FlashPairingFrame {
        override fun equals(other: Any?): Boolean =
            other is PairReveal && other.requestId == requestId && other.nonce.contentEquals(nonce)

        override fun hashCode(): Int = requestId.hashCode() * 31 + nonce.contentHashCode()
    }

    /** Responder agrees to pair. */
    public data class PairAccept(
        override val requestId: String,
    ) : FlashPairingFrame

    /**
     * Proof that the sender derived (and visually confirmed) the same numeric
     * comparison code: SHA-256 hex of the code, compared constant-time by the receiver.
     */
    public data class PairConfirm(
        override val requestId: String,
        val codeHashHex: String,
    ) : FlashPairingFrame

    /** Completion frame; carries the sender's pinned identity material. */
    public data class Paired(
        override val requestId: String,
        val peerFingerprintHex: String,
        val peerEphemeralPublicKey: ByteArray,
    ) : FlashPairingFrame {
        override fun equals(other: Any?): Boolean =
            other is Paired && other.requestId == requestId &&
                other.peerFingerprintHex == peerFingerprintHex &&
                other.peerEphemeralPublicKey.contentEquals(peerEphemeralPublicKey)

        override fun hashCode(): Int =
            (requestId.hashCode() * 31 + peerFingerprintHex.hashCode()) * 31 +
                peerEphemeralPublicKey.contentHashCode()
    }
}
