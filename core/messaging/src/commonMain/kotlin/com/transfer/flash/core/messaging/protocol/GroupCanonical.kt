package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.Base64

/**
 * The exact bytes that v2 group signatures cover (ADR-044 V1, plan D3).
 *
 * Every byte string is a tag followed by fields, and **every field, the tag included, is a 4-byte
 * big-endian length followed by its bytes**. Text is UTF-8, a `long` is 8 bytes big-endian, a
 * boolean is one byte. The order is fixed and there are no optional fields (an absent optional
 * string is the empty string), so two implementations cannot disagree about what was signed.
 *
 * The layouts are pinned by golden vectors in `GroupCanonicalTest` and quoted in
 * `docs/protocol.md`. Changing any of them is a wire break: it needs a new tag, never an edit.
 */
internal object GroupCanonical {
    const val CHARTER_TAG: String = "flash-gcharter-v1"
    const val CERT_TAG: String = "flash-gcert-v1"
    const val MESSAGE_TAG: String = "flash-gmsg-v1"
    const val GROUP_ID_TAG: String = "flash-gid-v1"

    /** Length of the hex part of a derived group id (128 bits). */
    private const val GROUP_ID_HEX_CHARS = 32

    /** Null when a base64 field is malformed: a charter that cannot be decoded cannot be valid. */
    fun charterBytes(charter: GroupCharter): ByteArray? {
        val ownerKey = decode(charter.ownerKey) ?: return null
        val nonce = decode(charter.nonce) ?: return null
        return Writer(CHARTER_TAG)
            .text(charter.groupId)
            .text(charter.name)
            .text(charter.ownerId)
            .bytes(ownerKey)
            .long(charter.createdAt)
            .bytes(nonce)
            .long(charter.proto.toLong())
            .build()
    }

    fun certBytes(cert: MemberCert): ByteArray? {
        val subjectKey = decode(cert.subjectKey) ?: return null
        return Writer(CERT_TAG)
            .text(cert.groupId)
            .text(cert.subjectId)
            .bytes(subjectKey)
            .text(cert.label)
            .text(cert.role)
            .long(cert.seq)
            .text(cert.opId)
            .bool(cert.active)
            .text(cert.issuerId)
            .build()
    }

    fun messageBytes(
        groupId: String,
        messageId: String,
        from: String,
        sentAt: Long,
        replyToId: String?,
        replyPreview: String?,
        text: String,
    ): ByteArray = Writer(MESSAGE_TAG)
        .text(groupId)
        .text(messageId)
        .text(from)
        .long(sentAt)
        .text(replyToId.orEmpty())
        .text(replyPreview.orEmpty())
        .text(text)
        .build()

    /** The id a charter with this owner key and nonce must carry (plan D1). */
    fun deriveGroupId(crypto: GroupCrypto, ownerKey: ByteArray, nonce: ByteArray): String {
        val digest = crypto.sha256(Writer(GROUP_ID_TAG).bytes(ownerKey).bytes(nonce).build())
        return GroupPolicy.V2_GROUP_ID_PREFIX + digest.toLowerHex().take(GROUP_ID_HEX_CHARS)
    }

    fun decode(base64: String): ByteArray? =
        try {
            Base64.decode(base64)
        } catch (_: IllegalArgumentException) {
            null
        }

    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    /** Uppercase hex of SHA-256(key): the form the pin stores use for a fingerprint. */
    fun fingerprintHex(crypto: GroupCrypto, publicKey: ByteArray): String =
        crypto.sha256(publicKey).toLowerHex().uppercase()

    private fun ByteArray.toLowerHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(digits[v shr 4]).append(digits[v and 0x0F])
        }
        return out.toString()
    }

    class Writer(tag: String) {
        private val parts = ArrayList<ByteArray>()
        private var total = 0

        init {
            text(tag)
        }

        fun text(value: String): Writer = bytes(value.encodeToByteArray())

        fun bytes(value: ByteArray): Writer {
            add(
                byteArrayOf(
                    (value.size ushr 24).toByte(),
                    (value.size ushr 16).toByte(),
                    (value.size ushr 8).toByte(),
                    value.size.toByte(),
                ),
            )
            add(value)
            return this
        }

        fun long(value: Long): Writer = bytes(ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() })

        fun bool(value: Boolean): Writer = bytes(byteArrayOf(if (value) 1 else 0))

        fun build(): ByteArray {
            val out = ByteArray(total)
            var at = 0
            for (part in parts) {
                part.copyInto(out, at)
                at += part.size
            }
            return out
        }

        private fun add(part: ByteArray) {
            parts += part
            total += part.size
        }
    }
}
