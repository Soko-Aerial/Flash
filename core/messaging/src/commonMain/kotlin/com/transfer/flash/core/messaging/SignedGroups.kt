@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupCharter
import com.transfer.flash.core.messaging.protocol.GroupCrypto
import com.transfer.flash.core.messaging.protocol.GroupMembershipVersion
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupSignatureRules
import com.transfer.flash.core.messaging.protocol.GroupSigning
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MemberCert
import com.transfer.flash.core.messaging.protocol.VerifyBudget
import com.transfer.flash.core.messaging.protocol.membershipUpdateWins
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity

/**
 * The v2 group engine (ADR-044 V1, `docs/group/v1-signed-membership-plan.md`): creates, changes and
 * receives owner-rooted signed groups on top of the protocol objects in `messaging/protocol`.
 *
 * It is the only writer of a v2 group's conversation and member rows. Everything it stores has
 * been verified, so the repository can keep using the stored roster as the source of truth for
 * "who is an active member" (receipts, sync, deletes) without re-checking signatures on each frame.
 *
 * A member row is a stored cert: `membershipVersion` = `seq`, `operationId` = `opId`,
 * `displayName` = the signed label, plus `subjectKey`, `certSig`, `issuerId`. That is what lets a
 * row be relayed later as a cert another device can verify, so nothing here ever edits a v2 row
 * except by installing a newer verified cert.
 */
internal class SignedGroups(
    private val localDeviceId: String,
    private val localDisplayName: String,
    private val crypto: GroupCrypto,
    private val conversationDao: ConversationDao,
    private val members: GroupMemberDao,
    isPaired: (String) -> Boolean,
    pinnedFingerprint: (String) -> String?,
    private val nowMs: () -> Long,
    private val newId: () -> String,
    private val budget: VerifyBudget = VerifyBudget(),
    private val newNonce: () -> ByteArray = {
        crypto.sha256(newId().encodeToByteArray()).copyOf(GroupPolicy.CHARTER_NONCE_BYTES)
    },
) {
    private val rules = GroupSignatureRules(crypto, localDeviceId, isPaired, pinnedFingerprint)
    private val signing = GroupSigning(crypto)
    private val pinnedFingerprintOf = pinnedFingerprint

    /** What became of a received bundle. */
    sealed interface BundleOutcome {
        /** The bundle was verified and merged. [joined] is true when it created the group here. */
        data class Applied(val groupId: String, val joined: Boolean) : BundleOutcome

        /** Nothing changed. [reason] is short and stable so it can be logged and asserted. */
        data class Ignored(val reason: String) : BundleOutcome
    }

    /** A freshly created group: the id plus the bundle to hand to every invitee. */
    class CreatedGroup(val groupId: String, val bundle: GroupWireFrame.Bundle)

    /** What the owner sends after adding members: the changed certs, and the full set for newcomers. */
    class AddedMembers(val changed: GroupWireFrame.Bundle, val full: GroupWireFrame.Bundle)

    suspend fun isV2(groupId: String): Boolean = conversationDao.get(groupId)?.groupProto == GroupPolicy.V2_PROTOCOL

    /**
     * True when [key] is [deviceId]'s key according to the trust store's pin, i.e. the pin is the
     * SHA-256 of exactly these bytes. An owner refuses to certify a key the pin does not vouch for.
     */
    fun keyMatchesPin(deviceId: String, key: ByteArray): Boolean {
        val pin = pinnedFingerprintOf(deviceId)?.replace(":", "")?.replace(" ", "")?.uppercase() ?: return false
        return pin == GroupCanonical.fingerprintHex(crypto, key)
    }

    // ---------------------------------------------------------------- sending

    /** Creates a v2 group owned by this device. [invitees] maps each invited device to its verified key. */
    suspend fun create(name: String, invitees: Map<String, ByteArray>, labelOf: (String) -> String): CreatedGroup {
        val now = nowMs()
        val charter = signing.newCharter(name, localDeviceId, now, newNonce())
        val groupId = charter.groupId
        val subjects = listOf(localDeviceId to crypto.publicKey) + invitees.map { it.key to it.value }
        val certs = subjects.map { (subjectId, key) ->
            signing.issueCert(
                groupId = groupId,
                subjectId = subjectId,
                subjectKey = key,
                label = if (subjectId == localDeviceId) label(localDisplayName, subjectId) else label(labelOf(subjectId), subjectId),
                role = if (subjectId == localDeviceId) MemberCert.ROLE_OWNER else MemberCert.ROLE_MEMBER,
                seq = 1L,
                opId = newId(),
                active = true,
                issuerId = localDeviceId,
            )
        }
        conversationDao.upsert(
            ConversationEntity(
                id = groupId,
                title = charter.name,
                isGroup = true,
                sortOrder = now,
                groupCreatedBy = localDeviceId,
                groupCreatedAt = now,
                groupProto = GroupPolicy.V2_PROTOCOL,
                groupOwnerKey = charter.ownerKey,
                groupNonce = charter.nonce,
                groupCharterSig = charter.sig,
            ),
        )
        certs.forEach { members.upsert(it.toRow(joinedAt = now)) }
        return CreatedGroup(groupId, GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, certs))
    }

    /**
     * Owner-only: certifies [invitees] (device to verified key). A device that is already an active member is
     * skipped, and null means nothing changed. The caller has already checked ownership and capacity.
     */
    suspend fun addMembers(
        groupId: String,
        invitees: Map<String, ByteArray>,
        labelOf: (String) -> String,
    ): AddedMembers? {
        val charter = storedCharter(groupId) ?: return null
        check(charter.ownerId == localDeviceId) { "only the owner adds members" }
        val rows = members.allMembers(groupId).associateBy { it.deviceId }
        val changed = ArrayList<MemberCert>()
        for ((subjectId, key) in invitees) {
            val current = rows[subjectId]
            if (current?.isActive == true) continue
            val cert = signing.issueCert(
                groupId = groupId,
                subjectId = subjectId,
                subjectKey = key,
                label = label(labelOf(subjectId), subjectId),
                role = MemberCert.ROLE_MEMBER,
                seq = (current?.membershipVersion ?: 0L) + 1L,
                opId = newId(),
                active = true,
                issuerId = localDeviceId,
            )
            members.upsert(cert.toRow(joinedAt = nowMs()))
            changed += cert
        }
        if (changed.isEmpty()) return null
        return AddedMembers(
            changed = GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, changed),
            full = bundleFor(groupId) ?: return null,
        )
    }

    /** This device's own leave: a self-signed tombstone with the next `seq`. Null when it is not an active member. */
    suspend fun leave(groupId: String): GroupWireFrame.Bundle? {
        val charter = storedCharter(groupId) ?: return null
        val mine = members.member(groupId, localDeviceId)?.takeIf { it.isActive } ?: return null
        val cert = signing.issueCert(
            groupId = groupId,
            subjectId = localDeviceId,
            subjectKey = crypto.publicKey,
            label = mine.displayName,
            role = mine.role,
            seq = mine.membershipVersion + 1L,
            opId = newId(),
            active = false,
            issuerId = localDeviceId,
        )
        members.upsert(cert.toRow(joinedAt = mine.joinedAt))
        return GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, listOf(cert))
    }

    /**
     * The whole roster as this device holds it: the charter plus every stored cert, newest tombstones
     * first once the tombstone cap is reached. This is what a reconcile on session-up sends.
     */
    suspend fun bundleFor(groupId: String): GroupWireFrame.Bundle? {
        val charter = storedCharter(groupId) ?: return null
        val certs = members.allMembers(groupId).mapNotNull { it.toCert() }
        val (active, tombstones) = certs.partition { it.active }
        val capped = active + tombstones.sortedByDescending { it.seq }.take(GroupPolicy.MAX_BUNDLE_TOMBSTONES)
        if (capped.isEmpty()) return null
        return GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, capped)
    }

    /** Base64 signature over the canonical bytes of a message this device authors in [groupId]. */
    fun signMessage(
        groupId: String,
        messageId: String,
        sentAt: Long,
        replyToId: String?,
        replyPreview: String?,
        text: String,
    ): String = signing.signMessage(groupId, messageId, localDeviceId, sentAt, replyToId, replyPreview, text)

    // -------------------------------------------------------------- receiving

    /**
     * Verifies and merges a bundle from the already-authenticated, paired transport peer [peerId]
     * (plan D5). Never throws for hostile input: every rejection is an [BundleOutcome.Ignored] with a reason.
     */
    suspend fun onBundle(peerId: String, frame: GroupWireFrame.Bundle): BundleOutcome {
        val groupId = frame.groupId
        if (!GroupPolicy.isV2GroupId(groupId)) return ignored(groupId, peerId, "id-namespace")
        val charter = frame.charter
        val stored = conversationDao.get(groupId)
        val known = stored != null
        if (stored != null) {
            if (stored.groupProto != GroupPolicy.V2_PROTOCOL) return ignored(groupId, peerId, "not-v2")
            if (!sameCharter(stored, charter)) return ignored(groupId, peerId, "charter-mismatch")
        }

        val rows = members.allMembers(groupId).associateBy { it.deviceId }
        // Only a cert that would replace what we hold needs a signature check; a stale or repeated
        // one is skipped for free, so replaying old bundles costs the receiver nothing.
        val candidates = frame.certs.filter { cert ->
            val current = rows[cert.subjectId]
            membershipUpdateWins(
                GroupMembershipVersion(cert.seq, cert.opId),
                current?.let { GroupMembershipVersion(it.membershipVersion, it.operationId) },
            )
        }
        val cost = candidates.size + if (known) 0 else 1
        if (!budget.tryConsume(peerId, cost, nowMs())) return ignored(groupId, peerId, "budget")

        if (!known) {
            rules.checkCharter(charter)?.let { return ignored(groupId, peerId, "charter:$it") }
        }
        val accepted = ArrayList<MemberCert>()
        for (cert in candidates) {
            val reason = rules.checkCert(charter, cert, rows[cert.subjectId]?.subjectKey)
            if (reason == null) {
                accepted += cert
            } else {
                FlashLog.w("CHAT", "Group cert dropped: group=$groupId subject=${cert.subjectId} from=$peerId reason=$reason")
            }
        }

        if (!known) {
            // A bundle that does not carry our own valid, active cert is not an invitation to us.
            if (accepted.none { it.subjectId == localDeviceId && it.active }) return ignored(groupId, peerId, "no-own-cert")
        } else {
            // A relay is fine, but only from someone who is (or is being proven to be) a member.
            val senderIsMember = rows[peerId]?.isActive == true || accepted.any { it.subjectId == peerId && it.active }
            if (!senderIsMember) return ignored(groupId, peerId, "sender-not-member")
        }

        val activeAfter = (rows.keys + accepted.map { it.subjectId }).count { id ->
            accepted.firstOrNull { it.subjectId == id }?.active ?: rows[id]?.isActive ?: false
        }
        if (activeAfter > GroupPolicy.MAX_MEMBERS_V2) return ignored(groupId, peerId, "too-many-members")
        if (accepted.isEmpty() && known) return BundleOutcome.Applied(groupId, joined = false)

        val now = nowMs()
        if (!known) {
            conversationDao.upsert(
                ConversationEntity(
                    id = groupId,
                    title = charter.name,
                    isGroup = true,
                    // Arrival time, not the owner-signed clock: a future `createdAt` must not pin a group to the top.
                    sortOrder = now,
                    groupCreatedBy = charter.ownerId,
                    groupCreatedAt = charter.createdAt,
                    groupProto = GroupPolicy.V2_PROTOCOL,
                    groupOwnerKey = charter.ownerKey,
                    groupNonce = charter.nonce,
                    groupCharterSig = charter.sig,
                ),
            )
        }
        accepted.forEach { members.upsert(it.toRow(joinedAt = rows[it.subjectId]?.joinedAt ?: now)) }
        return BundleOutcome.Applied(groupId, joined = !known)
    }

    /**
     * The signed label of [authorId] when [signature] is a valid signature by that member's key over
     * this message, otherwise null. The author must be an active member of the stored, verified roster.
     */
    suspend fun verifiedAuthorLabel(
        groupId: String,
        authorId: String,
        messageId: String,
        sentAt: Long,
        replyToId: String?,
        replyPreview: String?,
        text: String,
        signature: String?,
    ): String? {
        val row = members.member(groupId, authorId)?.takeIf { it.isActive } ?: return null
        val key = row.subjectKey ?: return null
        val ok = rules.verifyMessage(key, groupId, messageId, authorId, sentAt, replyToId, replyPreview, text, signature)
        return if (ok) row.displayName else null
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun storedCharter(groupId: String): GroupCharter? {
        val conversation = conversationDao.get(groupId) ?: return null
        if (conversation.groupProto != GroupPolicy.V2_PROTOCOL) return null
        return GroupCharter(
            groupId = groupId,
            name = conversation.title,
            ownerId = conversation.groupCreatedBy ?: return null,
            ownerKey = conversation.groupOwnerKey ?: return null,
            createdAt = conversation.groupCreatedAt ?: return null,
            nonce = conversation.groupNonce ?: return null,
            proto = conversation.groupProto,
            sig = conversation.groupCharterSig ?: return null,
        )
    }

    private fun sameCharter(stored: ConversationEntity, charter: GroupCharter): Boolean =
        stored.title == charter.name &&
            stored.groupCreatedBy == charter.ownerId &&
            stored.groupOwnerKey == charter.ownerKey &&
            stored.groupCreatedAt == charter.createdAt &&
            stored.groupNonce == charter.nonce &&
            stored.groupCharterSig == charter.sig &&
            stored.groupProto == charter.proto

    private fun ignored(groupId: String, peerId: String, reason: String): BundleOutcome.Ignored {
        FlashLog.w("CHAT", "Group bundle ignored: group=$groupId from=$peerId reason=$reason")
        return BundleOutcome.Ignored(reason)
    }

    /** A label the cert rules accept: never blank, never over the cap. */
    private fun label(preferred: String, fallback: String): String =
        preferred.trim().take(GroupPolicy.MAX_LABEL_LENGTH).ifBlank { fallback.take(GroupPolicy.MAX_LABEL_LENGTH) }

    private fun MemberCert.toRow(joinedAt: Long): GroupMemberEntity = GroupMemberEntity(
        groupId = groupId,
        deviceId = subjectId,
        displayName = label,
        role = role,
        joinedAt = joinedAt,
        membershipVersion = seq,
        operationId = opId,
        isActive = active,
        subjectKey = subjectKey,
        certSig = sig,
        issuerId = issuerId,
    )

    /** The cert a stored row was made from, or null for a row without one (never in a v2 group). */
    private fun GroupMemberEntity.toCert(): MemberCert? {
        val key = subjectKey ?: return null
        val issuer = issuerId ?: return null
        val signature = certSig ?: return null
        return MemberCert(
            groupId = groupId,
            subjectId = deviceId,
            subjectKey = key,
            label = displayName,
            role = role,
            seq = membershipVersion,
            opId = operationId,
            active = isActive,
            issuerId = issuer,
            sig = signature,
        )
    }
}
