package com.transfer.flash.core.security.group

import com.transfer.flash.core.security.crypto.constantTimeBytesEqual
import com.transfer.flash.core.security.crypto.hmacSha256
import com.transfer.flash.core.security.crypto.secureRandomBytes

/**
 * Sans-IO mutual proof of group secret knowledge bound to live TLS identities (ADR-073, protocol "The proof").
 *
 * Prevents man-in-the-middle (relay), reflection, and replay attacks across sessions.
 */
public object GroupProofTranscript {
    public const val TAG: String = "flash-gsp-v1"
    public const val ROLE_INITIATOR: Byte = 0x49.toByte() // ASCII 'I'
    public const val ROLE_RESPONDER: Byte = 0x52.toByte() // ASCII 'R'
    public const val NONCE_BYTES: Int = 16
    public const val FP_BYTES: Int = 32
    public const val MAC_BYTES: Int = 32

    /** Generates a fresh cryptographically secure 16-byte nonce for group proof transcripts. */
    public fun randomNonce(): ByteArray = secureRandomBytes(NONCE_BYTES)

    /** Generates a fresh cryptographically secure 32-byte MAC for dummy responses. */
    public fun randomMac(): ByteArray = secureRandomBytes(MAC_BYTES)

    /**
     * Builds T(role) = "flash-gsp-v1" ‖ role ‖ lp(fpI) ‖ lp(fpR) ‖ lp(groupId) ‖ u32 epoch ‖ nonceI(16) ‖ nonceR(16)
     */
    public fun build(
        role: Byte,
        fpInitiator: ByteArray,
        fpResponder: ByteArray,
        groupId: String,
        epoch: Long,
        nonceInitiator: ByteArray,
        nonceResponder: ByteArray,
    ): ByteArray {
        require(role == ROLE_INITIATOR || role == ROLE_RESPONDER) {
            "Invalid role: $role. Expected 'I' (0x49) or 'R' (0x52)"
        }
        require(fpInitiator.size == FP_BYTES) { "fpInitiator must be $FP_BYTES bytes, was ${fpInitiator.size}" }
        require(fpResponder.size == FP_BYTES) { "fpResponder must be $FP_BYTES bytes, was ${fpResponder.size}" }
        require(groupId.isNotEmpty()) { "groupId must not be empty" }
        require(epoch in 1L..0xFFFF_FFFFL) { "epoch must be in 1..4294967295 (u32), was $epoch" }
        require(nonceInitiator.size == NONCE_BYTES) { "nonceInitiator must be $NONCE_BYTES bytes, was ${nonceInitiator.size}" }
        require(nonceResponder.size == NONCE_BYTES) { "nonceResponder must be $NONCE_BYTES bytes, was ${nonceResponder.size}" }

        val tagBytes = TAG.encodeToByteArray()
        val groupBytes = groupId.encodeToByteArray()
        require(groupBytes.size <= 0xFFFF) { "groupId length exceeds u16 max" }

        val totalLen = tagBytes.size + 1 + (2 + fpInitiator.size) + (2 + fpResponder.size) + (2 + groupBytes.size) + 4 + NONCE_BYTES + NONCE_BYTES
        val out = ByteArray(totalLen)
        var offset = 0

        tagBytes.copyInto(out, destinationOffset = offset)
        offset += tagBytes.size

        out[offset++] = role

        // lp(fpI)
        out[offset++] = ((fpInitiator.size ushr 8) and 0xFF).toByte()
        out[offset++] = (fpInitiator.size and 0xFF).toByte()
        fpInitiator.copyInto(out, destinationOffset = offset)
        offset += fpInitiator.size

        // lp(fpR)
        out[offset++] = ((fpResponder.size ushr 8) and 0xFF).toByte()
        out[offset++] = (fpResponder.size and 0xFF).toByte()
        fpResponder.copyInto(out, destinationOffset = offset)
        offset += fpResponder.size

        // lp(groupId)
        out[offset++] = ((groupBytes.size ushr 8) and 0xFF).toByte()
        out[offset++] = (groupBytes.size and 0xFF).toByte()
        groupBytes.copyInto(out, destinationOffset = offset)
        offset += groupBytes.size

        // u32 epoch
        out[offset++] = ((epoch ushr 24) and 0xFF).toByte()
        out[offset++] = ((epoch ushr 16) and 0xFF).toByte()
        out[offset++] = ((epoch ushr 8) and 0xFF).toByte()
        out[offset++] = (epoch and 0xFF).toByte()

        // nonceI
        nonceInitiator.copyInto(out, destinationOffset = offset)
        offset += NONCE_BYTES

        // nonceR
        nonceResponder.copyInto(out, destinationOffset = offset)

        return out
    }
}

/** Group proof wire message 1: Initiator -> Responder. */
public class GroupProofHello(
    public val groupId: String,
    public val epoch: Long,
    public val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupProofHello) return false
        return groupId == other.groupId && epoch == other.epoch && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int =
        (groupId.hashCode() * 31 + epoch.hashCode()) * 31 + nonce.contentHashCode()

    override fun toString(): String =
        "GroupProofHello(groupId='$groupId', epoch=$epoch, nonce=[${nonce.size}B])"
}

/** Group proof wire message 2: Responder -> Initiator. */
public class GroupProofChallenge(
    public val groupId: String,
    public val epoch: Long,
    public val nonce: ByteArray,
    public val mac: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupProofChallenge) return false
        return groupId == other.groupId && epoch == other.epoch && nonce.contentEquals(other.nonce) && mac.contentEquals(other.mac)
    }

    override fun hashCode(): Int =
        ((groupId.hashCode() * 31 + epoch.hashCode()) * 31 + nonce.contentHashCode()) * 31 + mac.contentHashCode()

    override fun toString(): String =
        "GroupProofChallenge(groupId='$groupId', epoch=$epoch, nonce=[${nonce.size}B], mac=[${mac.size}B])"
}

/** Group proof wire message 3: Initiator -> Responder. */
public class GroupProofMac(
    public val groupId: String,
    public val epoch: Long,
    public val mac: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupProofMac) return false
        return groupId == other.groupId && epoch == other.epoch && mac.contentEquals(other.mac)
    }

    override fun hashCode(): Int =
        (groupId.hashCode() * 31 + epoch.hashCode()) * 31 + mac.contentHashCode()

    override fun toString(): String =
        "GroupProofMac(groupId='$groupId', epoch=$epoch, mac=[${mac.size}B])"
}

/** Exception thrown on illegal state transition or verification failure in [GroupProof]. */
public class GroupProofException(message: String) : RuntimeException(message)

/**
 * Initiator state machine for mutual proof of group secret knowledge.
 *
 * One-shot: once verified or failed, further calls are refused.
 */
public class GroupProofInitiator(
    public val groupId: String,
    public val epoch: Long,
    private val authKey: ByteArray,
    public val localTlsFingerprint: ByteArray,   // fpI
    public val remoteTlsFingerprint: ByteArray,  // fpR
    private val nonceSource: () -> ByteArray = { secureRandomBytes(GroupProofTranscript.NONCE_BYTES) },
) {
    public enum class State { INITIAL, WAITING_CHALLENGE, VERIFIED, FAILED }

    public var state: State = State.INITIAL
        private set

    private var nonceI: ByteArray? = null

    init {
        require(groupId.isNotEmpty()) { "groupId must not be empty" }
        require(epoch in 1L..0xFFFF_FFFFL) { "epoch must be in 1..4294967295, was $epoch" }
        require(authKey.size == GroupProofTranscript.MAC_BYTES) { "authKey must be 32 bytes, was ${authKey.size}" }
        require(localTlsFingerprint.size == GroupProofTranscript.FP_BYTES) { "localTlsFingerprint must be 32 bytes" }
        require(remoteTlsFingerprint.size == GroupProofTranscript.FP_BYTES) { "remoteTlsFingerprint must be 32 bytes" }
    }

    /** Creates the HELLO frame and transitions to WAITING_CHALLENGE. One-shot. */
    public fun createHello(): GroupProofHello {
        check(state == State.INITIAL) { "createHello() called in state $state" }
        val nonce = nonceSource()
        require(nonce.size == GroupProofTranscript.NONCE_BYTES) { "nonce must be 16 bytes" }
        nonceI = nonce.copyOf()
        state = State.WAITING_CHALLENGE
        return GroupProofHello(groupId, epoch, nonce)
    }

    /**
     * Ingests the responder's CHALLENGE.
     * Verifies responder's MAC in constant time.
     * If valid, computes initiator's PROOF MAC, transitions to VERIFIED, and returns GroupProofMac.
     * If invalid, transitions to FAILED and throws [GroupProofException].
     */
    public fun receiveChallenge(challenge: GroupProofChallenge): GroupProofMac {
        check(state == State.WAITING_CHALLENGE) { "receiveChallenge() called in state $state" }
        val nI = nonceI ?: error("nonceI missing")

        if (challenge.groupId != groupId ||
            challenge.epoch != epoch ||
            challenge.nonce.size != GroupProofTranscript.NONCE_BYTES ||
            challenge.mac.size != GroupProofTranscript.MAC_BYTES
        ) {
            state = State.FAILED
            throw GroupProofException("Mismatched challenge parameters or field lengths")
        }

        val expectedTranscriptR = GroupProofTranscript.build(
            role = GroupProofTranscript.ROLE_RESPONDER,
            fpInitiator = localTlsFingerprint,
            fpResponder = remoteTlsFingerprint,
            groupId = groupId,
            epoch = epoch,
            nonceInitiator = nI,
            nonceResponder = challenge.nonce,
        )
        val expectedMacR = hmacSha256(key = authKey, data = expectedTranscriptR)

        if (!constantTimeBytesEqual(expectedMacR, challenge.mac)) {
            state = State.FAILED
            throw GroupProofException("Responder MAC verification failed")
        }

        val transcriptI = GroupProofTranscript.build(
            role = GroupProofTranscript.ROLE_INITIATOR,
            fpInitiator = localTlsFingerprint,
            fpResponder = remoteTlsFingerprint,
            groupId = groupId,
            epoch = epoch,
            nonceInitiator = nI,
            nonceResponder = challenge.nonce,
        )
        val macI = hmacSha256(key = authKey, data = transcriptI)

        state = State.VERIFIED
        return GroupProofMac(groupId, epoch, macI)
    }
}

/**
 * Responder state machine for mutual proof of group secret knowledge.
 *
 * One-shot: once verified or failed, further calls are refused.
 */
public class GroupProofResponder(
    public val groupId: String,
    public val epoch: Long,
    private val authKey: ByteArray,
    public val localTlsFingerprint: ByteArray,   // fpR
    public val remoteTlsFingerprint: ByteArray,  // fpI
    private val nonceSource: () -> ByteArray = { secureRandomBytes(GroupProofTranscript.NONCE_BYTES) },
) {
    public enum class State { WAITING_HELLO, WAITING_PROOF, VERIFIED, FAILED }

    public var state: State = State.WAITING_HELLO
        private set

    private var nonceI: ByteArray? = null
    private var nonceR: ByteArray? = null

    init {
        require(groupId.isNotEmpty()) { "groupId must not be empty" }
        require(epoch in 1L..0xFFFF_FFFFL) { "epoch must be in 1..4294967295, was $epoch" }
        require(authKey.size == GroupProofTranscript.MAC_BYTES) { "authKey must be 32 bytes, was ${authKey.size}" }
        require(localTlsFingerprint.size == GroupProofTranscript.FP_BYTES) { "localTlsFingerprint must be 32 bytes" }
        require(remoteTlsFingerprint.size == GroupProofTranscript.FP_BYTES) { "remoteTlsFingerprint must be 32 bytes" }
    }

    /**
     * Ingests initiator's HELLO, generates nonceR and macR, and transitions to WAITING_PROOF.
     */
    public fun receiveHello(hello: GroupProofHello): GroupProofChallenge {
        check(state == State.WAITING_HELLO) { "receiveHello() called in state $state" }

        if (hello.groupId != groupId ||
            hello.epoch != epoch ||
            hello.nonce.size != GroupProofTranscript.NONCE_BYTES
        ) {
            state = State.FAILED
            throw GroupProofException("Mismatched hello parameters or field lengths")
        }

        val nR = nonceSource()
        require(nR.size == GroupProofTranscript.NONCE_BYTES) { "nonce must be 16 bytes" }
        nonceI = hello.nonce.copyOf()
        nonceR = nR.copyOf()

        val transcriptR = GroupProofTranscript.build(
            role = GroupProofTranscript.ROLE_RESPONDER,
            fpInitiator = remoteTlsFingerprint, // fpI
            fpResponder = localTlsFingerprint,  // fpR
            groupId = groupId,
            epoch = epoch,
            nonceInitiator = hello.nonce,
            nonceResponder = nR,
        )
        val macR = hmacSha256(key = authKey, data = transcriptR)

        state = State.WAITING_PROOF
        return GroupProofChallenge(groupId, epoch, nR, macR)
    }

    /**
     * Ingests initiator's PROOF.
     * Verifies initiator's MAC in constant time.
     * Transitions to VERIFIED on match, or FAILED on mismatch.
     */
    public fun receiveProof(proof: GroupProofMac): Boolean {
        check(state == State.WAITING_PROOF) { "receiveProof() called in state $state" }
        val nI = nonceI ?: error("nonceI missing")
        val nR = nonceR ?: error("nonceR missing")

        if (proof.groupId != groupId ||
            proof.epoch != epoch ||
            proof.mac.size != GroupProofTranscript.MAC_BYTES
        ) {
            state = State.FAILED
            return false
        }

        val expectedTranscriptI = GroupProofTranscript.build(
            role = GroupProofTranscript.ROLE_INITIATOR,
            fpInitiator = remoteTlsFingerprint, // fpI
            fpResponder = localTlsFingerprint,  // fpR
            groupId = groupId,
            epoch = epoch,
            nonceInitiator = nI,
            nonceResponder = nR,
        )
        val expectedMacI = hmacSha256(key = authKey, data = expectedTranscriptI)

        val matches = constantTimeBytesEqual(expectedMacI, proof.mac)
        state = if (matches) State.VERIFIED else State.FAILED
        return matches
    }
}
