package com.transfer.flash.server.mailbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Standard envelope kinds supported by the store-and-forward relay.
 */
object EnvelopeKind {
    const val CHAT = "CHAT"
    const val RECEIPT = "RECEIPT"
    const val SMALL_FILE = "SMALL_FILE"
    const val META = "META"
}

/**
 * Data class representing the unified envelope for store-and-forward routing.
 * Reference: docs/ENTERPRISE-HYBRID-PLAN.md §4.
 *
 * @property version Wire envelope version (default 1).
 * @property originDeviceId Cryptographic device ID of the sender.
 * @property msgId Globally unique message UUID.
 * @property hops Number of hops traversed across mesh/relays (decremented/incremented per relay policy).
 * @property counter Reserved (REL-15 decision, ADR-112): replay protection is the recipient's msgId dedupe, the relay wire has
 *   no counter and the mailbox table no longer stores one. Kept only so the binary format stays readable.
 * @property kind Payload category: "CHAT", "RECEIPT", "SMALL_FILE", or "META".
 * @property recipientDeviceIds List of intended recipient device IDs.
 * @property payload Sealed ciphertext payload or binary frame.
 * @property authTagOrSignature Reserved (REL-15 decision, ADR-112): the hub never sees or checks an AEAD tag (clients seal
 *   the payload end to end) and the wire has no such field, so the mailbox does not store it.
 * @property timestamp Unix epoch timestamp in milliseconds when the envelope was minted.
 */
data class SealedEnvelope(
    val version: Int = 1,
    val originDeviceId: String,
    val msgId: String,
    val hops: Int = 0,
    val counter: Long = 0L,
    val kind: String = EnvelopeKind.CHAT,
    val recipientDeviceIds: List<String> = emptyList(),
    val payload: ByteArray,
    val authTagOrSignature: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {

    companion object {
        const val MAGIC: Int = 0x464C5345 // ASCII 'FLSE' (Flash Sealed Envelope)

        /**
         * Deserializes a canonical binary representation back into a [SealedEnvelope].
         */
        /** Hard caps on what a header may claim, so a corrupt or hostile buffer can not make the parser allocate gigabytes (REL-15). */
        const val MAX_RECIPIENTS: Int = 1_024
        const val MAX_PAYLOAD_BYTES: Int = 4 * 1024 * 1024

        fun fromBinary(bytes: ByteArray): SealedEnvelope {
            DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
                val magic = inp.readInt()
                require(magic == MAGIC) {
                    "Invalid sealed envelope magic header: 0x${magic.toString(16).uppercase()}"
                }
                val version = inp.readInt()
                val originDeviceId = inp.readUTF()
                val msgId = inp.readUTF()
                val hops = inp.readInt()
                val counter = inp.readLong()
                val kind = inp.readUTF()
                val recipientCount = inp.readInt()
                require(recipientCount in 0..MAX_RECIPIENTS) { "Implausible recipient count in envelope: $recipientCount" }
                val recipientDeviceIds = ArrayList<String>(recipientCount)
                for (i in 0 until recipientCount) {
                    recipientDeviceIds.add(inp.readUTF())
                }
                val payloadSize = inp.readInt()
                require(payloadSize in 0..MAX_PAYLOAD_BYTES) { "Implausible payload length in envelope: $payloadSize" }
                require(payloadSize <= bytes.size) { "Payload length $payloadSize exceeds the buffer (${bytes.size} bytes)" }
                val payload = ByteArray(payloadSize)
                inp.readFully(payload)
                val authTagOrSignature = inp.readUTF()
                val timestamp = inp.readLong()

                return SealedEnvelope(
                    version = version,
                    originDeviceId = originDeviceId,
                    msgId = msgId,
                    hops = hops,
                    counter = counter,
                    kind = kind,
                    recipientDeviceIds = recipientDeviceIds,
                    payload = payload,
                    authTagOrSignature = authTagOrSignature,
                    timestamp = timestamp,
                )
            }
        }

        fun fromByteArray(bytes: ByteArray): SealedEnvelope = fromBinary(bytes)
    }

    /**
     * Serializes this envelope into its canonical binary wire format.
     */
    fun toBinary(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(version)
            out.writeUTF(originDeviceId)
            out.writeUTF(msgId)
            out.writeInt(hops)
            out.writeLong(counter)
            out.writeUTF(kind)
            out.writeInt(recipientDeviceIds.size)
            for (recipient in recipientDeviceIds) {
                out.writeUTF(recipient)
            }
            out.writeInt(payload.size)
            out.write(payload)
            out.writeUTF(authTagOrSignature)
            out.writeLong(timestamp)
        }
        return baos.toByteArray()
    }

    fun toByteArray(): ByteArray = toBinary()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SealedEnvelope) return false
        return version == other.version &&
            originDeviceId == other.originDeviceId &&
            msgId == other.msgId &&
            hops == other.hops &&
            counter == other.counter &&
            kind == other.kind &&
            recipientDeviceIds == other.recipientDeviceIds &&
            payload.contentEquals(other.payload) &&
            authTagOrSignature == other.authTagOrSignature &&
            timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + originDeviceId.hashCode()
        result = 31 * result + msgId.hashCode()
        result = 31 * result + hops
        result = 31 * result + counter.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + recipientDeviceIds.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + authTagOrSignature.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }

    override fun toString(): String {
        return "SealedEnvelope(v=$version, origin=$originDeviceId, msgId=$msgId, hops=$hops, " +
            "counter=$counter, kind=$kind, recipients=$recipientDeviceIds, payloadBytes=${payload.size}, " +
            "authTagLen=${authTagOrSignature.length}, ts=$timestamp)"
    }
}
