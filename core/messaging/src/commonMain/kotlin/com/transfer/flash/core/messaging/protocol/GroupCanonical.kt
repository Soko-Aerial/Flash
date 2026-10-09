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
    const val SWARM_ANNOUNCE_TAG: String = "flash-swarm-v1/announce"
    const val JOIN_REQUEST_TAG: String = "flash-gjoin-v1"
    const val JOIN_DECISION_TAG: String = "flash-gdecision-v1"
    const val ROTATION_TAG: String = "flash-grot-v1"
    const val SETTINGS_TAG: String = "flash-gset-v1"

    /**
     * ADR-105 (supersedes the signing part of ADR-100): a non-default history ceiling is signed by its OWN statement under
     * this tag, bound to the settings object it belongs to (group, version, opId, signer). The settings statement itself
     * stays `flash-gset-v1` whatever the ceiling is, so a build that does not know the ceiling still verifies it.
     */
    const val HISTORY_CEILING_TAG: String = "flash-gsethc-v1"

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

    fun swarmAnnounceBytes(
        groupId: String,
        messageId: String,
        originId: String,
        rootHex: String,
        sizeBytes: Long,
        fileName: String,
        mimeType: String,
        sentAt: Long,
    ): ByteArray = Writer(SWARM_ANNOUNCE_TAG)
        .text(groupId)
        .text(messageId)
        .text(originId)
        .text(rootHex)
        .long(sizeBytes)
        .text(fileName)
        .text(mimeType)
        .long(sentAt)
        .build()

    fun joinRequestBytes(
        groupId: String,
        epoch: Long,
        subjectId: String,
        subjectKeyBase64: String,
        label: String,
        requestedAtMs: Long,
    ): ByteArray? {
        val subjectKey = decode(subjectKeyBase64) ?: return null
        return Writer(JOIN_REQUEST_TAG)
            .text(groupId)
            .long(epoch)
            .text(subjectId)
            .bytes(subjectKey)
            .text(label)
            .long(requestedAtMs)
            .build()
    }

    fun joinDecisionBytes(
        groupId: String,
        subjectId: String,
        approved: Boolean,
        reason: String,
        decidedBy: String,
        decidedAtMs: Long,
    ): ByteArray {
        return Writer(JOIN_DECISION_TAG)
            .text(groupId)
            .text(subjectId)
            .bool(approved)
            .text(reason)
            .text(decidedBy)
            .long(decidedAtMs)
            .build()
    }

    fun rotationBytes(
        groupId: String,
        newEpoch: Long,
        prevEpoch: Long,
        commitHex: String,
        reason: String,
        adminId: String,
        rotationId: String,
        removedIds: List<String>,
    ): ByteArray {
        val writer = Writer(ROTATION_TAG)
            .text(groupId)
            .long(newEpoch)
            .long(prevEpoch)
            .text(commitHex)
            .text(reason)
            .text(adminId)
            .text(rotationId)
            .long(removedIds.size.toLong())
        for (id in removedIds) {
            writer.text(id)
        }
        return writer.build()
    }

    /** The v1 settings statement. ADR-105: it never covers the history ceiling, so every build verifies it (see [historyCeilingBytes]). */
    fun settingsBytes(settings: GroupSettings): ByteArray =
        Writer(SETTINGS_TAG)
            .text(settings.groupId)
            .long(settings.version)
            .text(settings.joinPolicy.uppercase())
            .text(settings.inviteSharers.uppercase())
            .long(settings.maxMembers.toLong())
            .bool(settings.swarmServing)
            .bool(settings.membersMayAdd)
            .text(settings.opId)
            .text(settings.signerId)
            .build()

    /**
     * ADR-105: the separate statement that binds a non-default history ceiling to ONE settings object. It names the group,
     * version, opId and signer of that object, so a ceiling signature cannot be moved onto another settings object, and
     * it uses its own tag, so it can never be mistaken for (or replayed as) a settings statement.
     */
    fun historyCeilingBytes(settings: GroupSettings): ByteArray =
        Writer(HISTORY_CEILING_TAG)
            .text(settings.groupId)
            .long(settings.version)
            .text(settings.opId)
            .text(settings.signerId)
            .text(settings.historyCeiling.name)
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

    /** Hex string of bytes. */
    fun hex(bytes: ByteArray): String = bytes.toLowerHex()

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
