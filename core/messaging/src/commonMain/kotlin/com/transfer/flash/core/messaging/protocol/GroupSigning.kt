package com.transfer.flash.core.messaging.protocol

/**
 * Sender side of v2 groups (ADR-044 V1, plan D6): builds and signs the objects that
 * [GroupSignatureRules] later checks. Pure, given the crypto port; randomness (the charter nonce,
 * cert operation ids) is supplied by the caller so every result is reproducible in tests.
 */
internal class GroupSigning(private val crypto: GroupCrypto) {

    /** A signed charter for a group this device owns. The id is derived, not chosen (plan D1). */
    fun newCharter(name: String, ownerId: String, createdAt: Long, nonce: ByteArray): GroupCharter {
        require(nonce.size == GroupPolicy.CHARTER_NONCE_BYTES) {
            "charter nonce must be ${GroupPolicy.CHARTER_NONCE_BYTES} bytes"
        }
        val ownerKey = crypto.publicKey
        val unsigned = GroupCharter(
            groupId = GroupCanonical.deriveGroupId(crypto, ownerKey, nonce),
            name = name,
            ownerId = ownerId,
            ownerKey = GroupCanonical.encode(ownerKey),
            createdAt = createdAt,
            nonce = GroupCanonical.encode(nonce),
            sig = "",
        )
        return unsigned.copy(sig = sign(GroupCanonical.charterBytes(unsigned)!!))
    }

    /**
     * A cert signed by this device: an owner-issued add/remove/relabel, or (with
     * `issuerId == subjectId` and `active = false`) this device's own leave.
     */
    fun issueCert(
        groupId: String,
        subjectId: String,
        subjectKey: ByteArray,
        label: String,
        role: String,
        seq: Long,
        opId: String,
        active: Boolean,
        issuerId: String,
    ): MemberCert {
        val unsigned = MemberCert(
            groupId = groupId,
            subjectId = subjectId,
            subjectKey = GroupCanonical.encode(subjectKey),
            label = label,
            role = role,
            seq = seq,
            opId = opId,
            active = active,
            issuerId = issuerId,
            sig = "",
        )
        return unsigned.copy(sig = sign(GroupCanonical.certBytes(unsigned)!!))
    }

    /** Base64 signature over the message's canonical bytes. */
    fun signMessage(
        groupId: String,
        messageId: String,
        from: String,
        sentAt: Long,
        replyToId: String?,
        replyPreview: String?,
        text: String,
    ): String = sign(GroupCanonical.messageBytes(groupId, messageId, from, sentAt, replyToId, replyPreview, text))

    fun issueRotation(
        groupId: String,
        newEpoch: Long,
        prevEpoch: Long,
        commitHex: String,
        reason: String,
        adminId: String,
        rotationId: String,
        removedIds: List<String> = emptyList(),
    ): GroupRotation {
        val bytes = GroupCanonical.rotationBytes(
            groupId = groupId,
            newEpoch = newEpoch,
            prevEpoch = prevEpoch,
            commitHex = commitHex,
            reason = reason,
            adminId = adminId,
            rotationId = rotationId,
            removedIds = removedIds,
        )
        return GroupRotation(
            groupId = groupId,
            newEpoch = newEpoch,
            prevEpoch = prevEpoch,
            commit = commitHex,
            reason = reason,
            adminId = adminId,
            rotationId = rotationId,
            removedIds = removedIds,
            sig = sign(bytes),
        )
    }

    fun signSettings(
        groupId: String,
        version: Long,
        joinPolicy: String,
        inviteSharers: String,
        maxMembers: Int,
        swarmServing: Boolean,
        membersMayAdd: Boolean,
        opId: String,
        signerId: String,
        historyCeiling: GroupHistoryCeiling = GroupHistoryCeiling.DEFAULT,
    ): GroupSettings {
        val unsigned = GroupSettings(
            groupId = groupId,
            version = version,
            joinPolicy = joinPolicy,
            inviteSharers = inviteSharers,
            maxMembers = maxMembers,
            swarmServing = swarmServing,
            membersMayAdd = membersMayAdd,
            opId = opId,
            signerId = signerId,
            sig = "",
            historyCeiling = historyCeiling,
        )
        // ADR-105: `sig` is the v1 statement whatever the ceiling is; a non-default ceiling adds its own signature.
        val signed = unsigned.copy(sig = sign(GroupCanonical.settingsBytes(unsigned)))
        return if (historyCeiling == GroupHistoryCeiling.DEFAULT) {
            signed
        } else {
            signed.copy(historyCeilingSig = sign(GroupCanonical.historyCeilingBytes(signed)))
        }
    }

    private fun sign(bytes: ByteArray): String = GroupCanonical.encode(crypto.sign(bytes))
}
