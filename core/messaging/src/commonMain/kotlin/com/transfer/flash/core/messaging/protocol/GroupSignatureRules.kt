package com.transfer.flash.core.messaging.protocol

/**
 * Receiver side of v2 groups (ADR-044 V1, plan D2 and D5): decides whether a charter, a cert or a
 * message is genuine. Every check returns `null` when the object is valid and a short reason
 * otherwise, so the caller can log a security event that says why.
 *
 * @param isPaired whether this device completed pairing with a peer
 * @param pinnedFingerprint the identity-key fingerprint pinned for a device id (hex of SHA-256
 *   over the SPKI, any case, colons allowed), or null when nothing is pinned
 * @param vouching the trust store's view of vouched pins (ADR-044 V2). When present, an owner-signed
 *   active cert is enough to accept a member this device never paired with, unless the trust store
 *   refuses the vouch. Null keeps the V1 rule: every member must be paired.
 */
internal class GroupSignatureRules(
    private val crypto: GroupCrypto,
    private val localDeviceId: String,
    private val isPaired: (String) -> Boolean,
    private val pinnedFingerprint: (String) -> String?,
    private val vouching: GroupVouching? = null,
) {

    fun checkCharter(charter: GroupCharter): String? {
        if (charter.proto != GroupPolicy.V2_PROTOCOL) return "proto"
        if (!GroupPolicy.isV2GroupId(charter.groupId)) return "id-namespace"
        if (GroupPolicy.normalizedName(charter.name) != charter.name) return "name"
        if (charter.ownerId.isBlank()) return "owner-id"
        val ownerKey = GroupCanonical.decode(charter.ownerKey)?.takeIf { it.isNotEmpty() } ?: return "owner-key"
        val nonce = GroupCanonical.decode(charter.nonce) ?: return "nonce"
        if (nonce.size != GroupPolicy.CHARTER_NONCE_BYTES) return "nonce"
        if (charter.groupId != GroupCanonical.deriveGroupId(crypto, ownerKey, nonce)) return "id-derivation"
        if (charter.ownerId == localDeviceId) {
            if (!ownerKey.contentEquals(crypto.publicKey)) return "owner-key-local"
        } else {
            if (!isPaired(charter.ownerId)) return "owner-not-paired"
            if (!keyMatchesPin(charter.ownerId, ownerKey)) return "owner-key-pin"
        }
        val signature = GroupCanonical.decode(charter.sig)?.takeIf { it.isNotEmpty() } ?: return "signature"
        val bytes = GroupCanonical.charterBytes(charter) ?: return "signature"
        return if (crypto.verify(signature, bytes, ownerKey)) null else "signature"
    }

    /**
     * @param charter an already validated charter of the same group
     * @param knownKey the key this device already holds for the subject (the stored roster row),
     *   or null; a subject's own leave is only believed when it is signed by that key
     */
    fun checkCert(charter: GroupCharter, cert: MemberCert, knownKey: String?): String? {
        if (cert.groupId != charter.groupId) return "group"
        if (cert.subjectId.isBlank()) return "subject"
        if (cert.opId.isBlank()) return "op-id"
        if (cert.seq < 1L) return "seq"
        if (cert.label.isBlank() || cert.label.length > GroupPolicy.MAX_LABEL_LENGTH) return "label"
        val isOwnerSubject = cert.subjectId == charter.ownerId
        if (cert.role != (if (isOwnerSubject) MemberCert.ROLE_OWNER else MemberCert.ROLE_MEMBER)) return "role"
        val ownerIssued = cert.issuerId == charter.ownerId
        val selfIssuedLeave = cert.issuerId == cert.subjectId && !cert.active
        if (!ownerIssued && !selfIssuedLeave) return "issuer"
        val subjectKey = GroupCanonical.decode(cert.subjectKey)?.takeIf { it.isNotEmpty() } ?: return "subject-key"
        // The key a signature is checked against. An owner-issued cert is the owner's word (the
        // charter fixed the owner key); a self-issued leave is only as good as our knowledge of the
        // subject's key, otherwise anyone could "sign" a leave for a member with a key they made up.
        val issuerKey: ByteArray = if (ownerIssued) {
            GroupCanonical.decode(charter.ownerKey) ?: return "owner-key"
        } else {
            subjectKey
        }
        // An owner tombstone needs no key for the subject; everything else must be bound to the
        // subject's real key (a pin, our own key, the owner key, or the key we already recorded).
        if (cert.active || !ownerIssued) {
            // An owner-issued active cert is a vouch; a self-issued leave never is.
            val vouchable = ownerIssued && cert.active
            if (!subjectKeyIsBound(charter, cert.subjectId, subjectKey, knownKey, vouchable)) return "subject-key-binding"
        }
        val signature = GroupCanonical.decode(cert.sig)?.takeIf { it.isNotEmpty() } ?: return "signature"
        val bytes = GroupCanonical.certBytes(cert) ?: return "signature"
        return if (crypto.verify(signature, bytes, issuerKey)) null else "signature"
    }

    /** True when [signature] is [authorKey]'s signature over the message's canonical bytes. */
    fun verifyMessage(
        authorKey: String,
        groupId: String,
        messageId: String,
        from: String,
        sentAt: Long,
        replyToId: String?,
        replyPreview: String?,
        text: String,
        signature: String?,
    ): Boolean {
        val key = GroupCanonical.decode(authorKey)?.takeIf { it.isNotEmpty() } ?: return false
        val sig = signature?.let { GroupCanonical.decode(it) }?.takeIf { it.isNotEmpty() } ?: return false
        return crypto.verify(
            sig,
            GroupCanonical.messageBytes(groupId, messageId, from, sentAt, replyToId, replyPreview, text),
            key,
        )
    }

    private fun subjectKeyIsBound(
        charter: GroupCharter,
        subjectId: String,
        subjectKey: ByteArray,
        knownKey: String?,
        vouchable: Boolean,
    ): Boolean = when (subjectId) {
        localDeviceId -> subjectKey.contentEquals(crypto.publicKey)
        // The owner's key was bound to a pin (or to ours) when the charter was accepted.
        charter.ownerId -> GroupCanonical.decode(charter.ownerKey)?.contentEquals(subjectKey) == true
        else -> {
            val known = knownKey?.let { GroupCanonical.decode(it) }
            (known != null && known.contentEquals(subjectKey)) ||
                (isPaired(subjectId) && keyMatchesPin(subjectId, subjectKey)) ||
                // ADR-044 V2: the owner (the trust root, paired with us) names this key. The trust store
                // decides whether it clashes with a pairing or with another owner's vouch.
                (vouchable && vouching?.verdict(subjectId, GroupCanonical.fingerprintHex(crypto, subjectKey), charter.groupId) == GroupVouchVerdict.ACCEPT)
        }
    }

    private fun keyMatchesPin(deviceId: String, publicKey: ByteArray): Boolean {
        val pin = pinnedFingerprint(deviceId)?.let(::normalizeFingerprint) ?: return false
        return pin == GroupCanonical.fingerprintHex(crypto, publicKey)
    }

    private fun normalizeFingerprint(raw: String): String = raw.replace(":", "").replace(" ", "").uppercase()
}
