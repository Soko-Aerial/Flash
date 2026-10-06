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
    private val hasInvite: (String) -> Boolean = { false },
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
            val paired = isPaired(charter.ownerId)
            val inviteAccepted = hasInvite(charter.groupId)
            if (!paired && !inviteAccepted) return "owner-not-paired"
            if (paired && !keyMatchesPin(charter.ownerId, ownerKey)) return "owner-key-pin"
        }
        val signature = GroupCanonical.decode(charter.sig)?.takeIf { it.isNotEmpty() } ?: return "signature"
        val bytes = GroupCanonical.charterBytes(charter) ?: return "signature"
        return if (crypto.verify(signature, bytes, ownerKey)) null else "signature"
    }

    /**
     * @param charter an already validated charter of the same group
     * @param knownKey the key this device already holds for the subject (the stored roster row),
     *   or null; a subject's own leave is only believed when it is signed by that key
     * @param adminLookup optional lookup for an active admin's verified public key by deviceId (ADR-063)
     */
    fun checkCert(
        charter: GroupCharter,
        cert: MemberCert,
        knownKey: String?,
        adminLookup: ((String) -> ByteArray?)? = null,
        membersMayAdd: Boolean = false,
        memberLookup: ((String) -> ByteArray?)? = null,
    ): String? {
        if (cert.groupId != charter.groupId) return "group"
        if (cert.subjectId.isBlank()) return "subject"
        if (cert.opId.isBlank()) return "op-id"
        if (cert.seq < 1L) return "seq"
        if (cert.label.isBlank() || cert.label.length > GroupPolicy.MAX_LABEL_LENGTH) return "label"
        val isOwnerSubject = cert.subjectId == charter.ownerId
        val validRole = if (isOwnerSubject) {
            cert.role == MemberCert.ROLE_OWNER
        } else {
            cert.role == MemberCert.ROLE_MEMBER || cert.role == MemberCert.ROLE_ADMIN
        }
        if (!validRole) return "role"

        val ownerIssued = cert.issuerId == charter.ownerId
        val selfIssuedLeave = cert.issuerId == cert.subjectId && !cert.active
        val adminKey = if (!ownerIssued && !selfIssuedLeave && adminLookup != null) adminLookup(cert.issuerId) else null
        val adminIssued = adminKey != null
        val memberKey = if (!ownerIssued && !selfIssuedLeave && !adminIssued && membersMayAdd && memberLookup != null) memberLookup(cert.issuerId) else null
        val memberIssued = memberKey != null

        if (!ownerIssued && !selfIssuedLeave && !adminIssued && !memberIssued) return "issuer"

        // An admin cannot promote anyone to admin, cannot remove the founder, and cannot remove other admins
        if (adminIssued) {
            if (cert.role == MemberCert.ROLE_ADMIN) return "issuer-privilege"
            if (!cert.active) {
                if (cert.subjectId == charter.ownerId) return "issuer-privilege"
                if (adminLookup != null && adminLookup(cert.subjectId) != null) return "issuer-privilege"
            }
        }

        // A regular member can only add an active non-admin member
        if (memberIssued) {
            if (cert.role != MemberCert.ROLE_MEMBER || !cert.active) return "issuer-privilege"
        }

        val subjectKey = GroupCanonical.decode(cert.subjectKey)?.takeIf { it.isNotEmpty() } ?: return "subject-key"
        val issuerKey: ByteArray = when {
            ownerIssued -> GroupCanonical.decode(charter.ownerKey) ?: return "owner-key"
            adminIssued -> adminKey!!
            memberIssued -> memberKey!!
            else -> subjectKey
        }

        // An owner/admin tombstone needs no key binding check; everything else must be bound
        if (cert.active || (!ownerIssued && !adminIssued)) {
            val vouchable = (ownerIssued || adminIssued || memberIssued) && cert.active
            if (!subjectKeyIsBound(charter, cert.subjectId, subjectKey, knownKey, vouchable)) return "subject-key-binding"
        }
        val signature = GroupCanonical.decode(cert.sig)?.takeIf { it.isNotEmpty() } ?: return "signature"
        val bytes = GroupCanonical.certBytes(cert) ?: return "signature"
        return if (crypto.verify(signature, bytes, issuerKey)) null else "signature"
    }

    /**
     * Verifies a [GroupRotation] notice against the group charter and admin set (ADR-044, ADR-073, GM-6).
     * Returns null when valid, or a stable error reason string when rejected.
     */
    fun checkRotation(
        charter: GroupCharter,
        rotation: GroupRotation,
        adminLookup: ((String) -> ByteArray?)? = null,
    ): String? {
        if (rotation.groupId != charter.groupId) return "group-id"
        if (rotation.newEpoch <= rotation.prevEpoch) return "epoch-order"
        if (rotation.prevEpoch < 0) return "prev-epoch"
        if (rotation.commit.length != 64) return "commit"
        if (rotation.rotationId.length != 32) return "rotation-id"
        if (rotation.removedIds.size > GroupPolicy.MAX_REMOVED_IDS_PER_ROTATION) return "removed-ids"

        val ownerIssued = rotation.adminId == charter.ownerId
        val adminKey = if (!ownerIssued) adminLookup?.invoke(rotation.adminId) else null
        val adminIssued = adminKey != null
        if (!ownerIssued && !adminIssued) return "issuer-not-admin"

        val issuerKey = if (ownerIssued) {
            GroupCanonical.decode(charter.ownerKey) ?: return "owner-key"
        } else {
            adminKey!!
        }

        val signature = GroupCanonical.decode(rotation.sig)?.takeIf { it.isNotEmpty() } ?: return "signature"
        val bytes = GroupCanonical.rotationBytes(
            groupId = rotation.groupId,
            newEpoch = rotation.newEpoch,
            prevEpoch = rotation.prevEpoch,
            commitHex = rotation.commit,
            reason = rotation.reason,
            adminId = rotation.adminId,
            rotationId = rotation.rotationId,
            removedIds = rotation.removedIds,
        )
        return if (crypto.verify(signature, bytes, issuerKey)) null else "signature"
    }

    /**
     * Verifies a [GroupSettings] object against the group charter and admin set (ADR-074, GM-9).
     * Returns null when valid, or a stable error reason string when rejected.
     */
    fun checkSettings(
        charter: GroupCharter,
        settings: GroupSettings,
        adminLookup: ((String) -> ByteArray?)? = null,
    ): String? {
        if (settings.groupId != charter.groupId) return "group-id"
        if (settings.version <= 0L) return "version"
        if (settings.joinPolicy != GroupSettings.POLICY_APPROVE && settings.joinPolicy != GroupSettings.POLICY_OPEN) return "join-policy"
        if (settings.inviteSharers != GroupSettings.SHARERS_ALL && settings.inviteSharers != GroupSettings.SHARERS_ADMINS) return "invite-sharers"
        if (settings.maxMembers !in 2..GroupPolicy.MAX_MEMBERS_V2) return "max-members"
        if (settings.opId.isBlank() || settings.opId.length > 64) return "op-id"
        if (settings.signerId.isBlank() || settings.signerId.length > 128) return "signer-id"

        val ownerIssued = settings.signerId == charter.ownerId
        val adminKey = if (!ownerIssued) adminLookup?.invoke(settings.signerId) else null
        val adminIssued = adminKey != null
        if (!ownerIssued && !adminIssued) return "signer-not-admin"

        val issuerKey = if (ownerIssued) {
            GroupCanonical.decode(charter.ownerKey) ?: return "owner-key"
        } else {
            adminKey!!
        }

        val signature = GroupCanonical.decode(settings.sig)?.takeIf { it.isNotEmpty() } ?: return "signature"
        val bytes = GroupCanonical.settingsBytes(settings)
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
