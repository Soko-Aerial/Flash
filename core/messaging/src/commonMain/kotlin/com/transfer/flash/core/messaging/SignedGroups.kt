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
import com.transfer.flash.core.messaging.protocol.GroupVouchVerdict
import com.transfer.flash.core.messaging.protocol.GroupVouching
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
    localDisplayName: String,
    private val crypto: GroupCrypto,
    private val conversationDao: ConversationDao,
    private val members: GroupMemberDao,
    isPaired: (String) -> Boolean,
    pinnedFingerprint: (String) -> String?,
    /** ADR-044 V2: the trust store vouched pins. Null keeps V1: every member must be paired with this device. */
    private val vouching: GroupVouching? = null,
    private val nowMs: () -> Long,
    private val newId: () -> String,
    private val budget: VerifyBudget = VerifyBudget(),
    private val newNonce: () -> ByteArray = {
        crypto.sha256(newId().encodeToByteArray()).copyOf(GroupPolicy.CHARTER_NONCE_BYTES)
    },
) {
    /**
     * The label this device's own cert carries in a group it creates from now on. A cert already issued keeps the
     * label it was signed with (see the class note), so a rename reaches only groups created after it.
     */
    @kotlin.concurrent.Volatile
    private var localDisplayName: String = localDisplayName

    /** This device was renamed; later groups name it [newName]. */
    fun updateLocalDisplayName(newName: String) {
        localDisplayName = newName
    }

    private val rules = GroupSignatureRules(crypto, localDeviceId, isPaired, pinnedFingerprint, vouching)
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

    /** The owner tombstone for a removed member; the removed device is told too, so it stops sending. */
    class RemovedMember(val bundle: GroupWireFrame.Bundle)

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
        val rows = members.allMembers(groupId).associateBy { it.deviceId }
        val self = rows[localDeviceId]
        val canAdd = charter.ownerId == localDeviceId || (self?.isActive == true && self.role == MemberCert.ROLE_ADMIN)
        check(canAdd) { "only the owner or an admin adds members" }
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

    /** Promotes an active member to [MemberCert.ROLE_ADMIN] (ADR-063). */
    suspend fun promoteAdmin(groupId: String, subjectId: String): GroupWireFrame.Bundle? {
        val charter = storedCharter(groupId) ?: return null
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive } ?: return null
        val isOwner = charter.ownerId == localDeviceId
        val ownerActive = members.member(groupId, charter.ownerId)?.isActive == true
        val canPromote = isOwner || (!ownerActive && self.role == MemberCert.ROLE_ADMIN)
        if (!canPromote) return null
        val current = members.member(groupId, subjectId)?.takeIf { it.isActive } ?: return null
        if (current.role == MemberCert.ROLE_ADMIN || subjectId == charter.ownerId) return null
        val key = current.subjectKey?.let { GroupCanonical.decode(it) } ?: return null
        val cert = signing.issueCert(
            groupId = groupId,
            subjectId = subjectId,
            subjectKey = key,
            label = current.displayName,
            role = MemberCert.ROLE_ADMIN,
            seq = current.membershipVersion + 1L,
            opId = newId(),
            active = true,
            issuerId = localDeviceId,
        )
        members.upsert(cert.toRow(joinedAt = current.joinedAt))
        return GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, listOf(cert))
    }

    /** Demotes an active admin to [MemberCert.ROLE_MEMBER] (ADR-063, owner-only). */
    suspend fun demoteAdmin(groupId: String, subjectId: String): GroupWireFrame.Bundle? {
        val charter = storedCharter(groupId) ?: return null
        if (charter.ownerId != localDeviceId) return null
        val current = members.member(groupId, subjectId)?.takeIf { it.isActive } ?: return null
        if (current.role != MemberCert.ROLE_ADMIN) return null
        val key = current.subjectKey?.let { GroupCanonical.decode(it) } ?: return null
        val cert = signing.issueCert(
            groupId = groupId,
            subjectId = subjectId,
            subjectKey = key,
            label = current.displayName,
            role = MemberCert.ROLE_MEMBER,
            seq = current.membershipVersion + 1L,
            opId = newId(),
            active = true,
            issuerId = localDeviceId,
        )
        members.upsert(cert.toRow(joinedAt = current.joinedAt))
        return GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, listOf(cert))
    }

    /** This device's own leave: a self-signed tombstone with the next `seq`. If owner and [successorId] given, promotes successor first. */
    suspend fun leave(groupId: String, successorId: String? = null): GroupWireFrame.Bundle? {
        val charter = storedCharter(groupId) ?: return null
        val mine = members.member(groupId, localDeviceId)?.takeIf { it.isActive } ?: return null
        val isOwner = charter.ownerId == localDeviceId
        val certs = ArrayList<MemberCert>()

        if (isOwner && successorId != null && successorId != localDeviceId) {
            val successor = members.member(groupId, successorId)?.takeIf { it.isActive }
            val succKey = successor?.subjectKey?.let { GroupCanonical.decode(it) }
            if (successor != null && succKey != null && successor.role != MemberCert.ROLE_ADMIN) {
                val promoCert = signing.issueCert(
                    groupId = groupId,
                    subjectId = successorId,
                    subjectKey = succKey,
                    label = successor.displayName,
                    role = MemberCert.ROLE_ADMIN,
                    seq = successor.membershipVersion + 1L,
                    opId = newId(),
                    active = true,
                    issuerId = localDeviceId,
                )
                members.upsert(promoCert.toRow(joinedAt = successor.joinedAt))
                certs += promoCert
            }
        }

        val leaveCert = signing.issueCert(
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
        members.upsert(leaveCert.toRow(joinedAt = mine.joinedAt))
        certs += leaveCert
        // A member who has left no longer has any reason to hold the pins the group vouched.
        revokeAll(groupId)
        return GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, certs)
    }

    /**
     * Owner or Admin removal (ADR-044 V2, ADR-063): an authorized tombstone with the next `seq`.
     * Admin cannot remove owner or other admins.
     */
    suspend fun removeMember(groupId: String, subjectId: String): RemovedMember? {
        val charter = storedCharter(groupId) ?: return null
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive } ?: return null
        val isOwner = charter.ownerId == localDeviceId
        val isAdmin = self.role == MemberCert.ROLE_ADMIN
        if (!isOwner && !isAdmin) return null
        if (subjectId == localDeviceId || subjectId == charter.ownerId) return null
        val current = members.member(groupId, subjectId)?.takeIf { it.isActive } ?: return null
        if (isAdmin && current.role == MemberCert.ROLE_ADMIN) return null // admin cannot remove another admin
        val key = current.subjectKey?.let { GroupCanonical.decode(it) } ?: return null
        val cert = signing.issueCert(
            groupId = groupId,
            subjectId = subjectId,
            subjectKey = key,
            label = current.displayName,
            role = current.role,
            seq = current.membershipVersion + 1L,
            opId = newId(),
            active = false,
            issuerId = localDeviceId,
        )
        members.upsert(cert.toRow(joinedAt = current.joinedAt))
        vouching?.revoke(subjectId, groupId)
        return RemovedMember(GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, listOf(cert)))
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

    /**
     * What a member hands a device it knows the owner removed, in case that device was offline for the removal: the
     * charter and that device's owner-issued tombstone, nothing else. Null for a member still active, for one who left
     * by their own choice (they know) and for an unknown subject. The removed device checks the owner's signature
     * itself, so any member can relay it, and a device that already knows treats it as a free, stale replay.
     */
    suspend fun removalNoticeFor(groupId: String, subjectId: String): GroupWireFrame.Bundle? {
        val charter = storedCharter(groupId) ?: return null
        val row = members.member(groupId, subjectId)?.takeIf { !it.isActive } ?: return null
        if (row.issuerId != charter.ownerId || row.issuerId == subjectId) return null
        val cert = row.toCert() ?: return null
        return GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, listOf(cert))
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
        val adminKeys = rows.values
            .filter { it.isActive && it.role == MemberCert.ROLE_ADMIN }
            .mapNotNull { row -> row.subjectKey?.let { k -> GroupCanonical.decode(k)?.let { row.deviceId to it } } }
            .toMap().toMutableMap()

        val verified = ArrayList<MemberCert>()
        for (cert in candidates) {
            val reason = rules.checkCert(charter, cert, rows[cert.subjectId]?.subjectKey) { adminId ->
                adminKeys[adminId]
            }
            if (reason == null) {
                verified += cert
                if (cert.role == MemberCert.ROLE_ADMIN && cert.active) {
                    GroupCanonical.decode(cert.subjectKey)?.let { adminKeys[cert.subjectId] = it }
                } else if (!cert.active || cert.role != MemberCert.ROLE_ADMIN) {
                    adminKeys.remove(cert.subjectId)
                }
            } else {
                FlashLog.w("CHAT", "Group cert dropped: group=$groupId subject=${cert.subjectId} from=$peerId reason=$reason")
            }
        }

        if (!known) {
            // A bundle that does not carry our own valid, active cert is not an invitation to us.
            if (verified.none { it.subjectId == localDeviceId && it.active }) return ignored(groupId, peerId, "no-own-cert")
        } else {
            // A relay is fine, but only from someone who is (or is being proven to be) a member.
            val senderIsMember = rows[peerId]?.isActive == true || verified.any { it.subjectId == peerId && it.active }
            if (!senderIsMember) return ignored(groupId, peerId, "sender-not-member")
        }
        // A device that is no longer a member (removed, or it left) takes nothing from a bundle except an invitation
        // back: its own, newer, active cert. Otherwise a removed member would keep re-installing the pins of members
        // it can no longer talk to, and every reconnect would refresh a roster it has no part in.
        val localRow = rows[localDeviceId]
        if (localRow != null && !localRow.isActive && verified.none { it.subjectId == localDeviceId && it.active }) {
            return ignored(groupId, peerId, "local-not-member")
        }

        val activeAfter = (rows.keys + verified.map { it.subjectId }).count { id ->
            verified.firstOrNull { it.subjectId == id }?.active ?: rows[id]?.isActive ?: false
        }
        if (activeAfter > GroupPolicy.MAX_MEMBERS_V2) return ignored(groupId, peerId, "too-many-members")

        // ADR-044 V2: a verified owner-signed cert is a vouch. It becomes a pin before the row is stored, so the
        // real member connects against it, and a tombstone withdraws it. This is the first side effect of the
        // bundle: nothing above may leave a pin behind for a bundle that is then ignored. A vouch the trust store
        // refuses after all (it was checked once already, so only a race) keeps that member out of the roster.
        val accepted = installVouches(groupId, peerId, charter, verified)
        if (accepted.isEmpty() && known) {
            ensureVouches(groupId, charter)
            return BundleOutcome.Applied(groupId, joined = false)
        }

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
        // This device was just told it is out: it no longer has a reason to keep the pins the group vouched (as on leave).
        if (accepted.any { it.subjectId == localDeviceId && !it.active }) revokeAll(groupId)
        ensureVouches(groupId, charter)
        return BundleOutcome.Applied(groupId, joined = !known)
    }

    /**
     * True when [deviceId] is an active member of the stored, verified roster of [groupId] **and** [sessionKey], the
     * identity key its live TLS session presented, is the key its certificate names. This is what makes a vouched
     * member trusted in the group without pairing (plan E3). It does not consult the pin store: an impostor that
     * connected first as this id, before the vouch replaced its pin, still presents a different key and fails here.
     */
    suspend fun isVouchedMember(groupId: String, deviceId: String, sessionKey: ByteArray?): Boolean {
        if (sessionKey == null || sessionKey.isEmpty()) return false
        val row = members.member(groupId, deviceId)?.takeIf { it.isActive } ?: return false
        val key = row.subjectKey?.let { GroupCanonical.decode(it) } ?: return false
        return key.contentEquals(sessionKey)
    }

    /**
     * [isVouchedMember] without the live session (ERROR-088): the stored, verified roster holds an active member of
     * [groupId] whose owner-signed certificate names a key. It says the owner introduced this device to the member,
     * so the member may be dialed and invited; it says nothing about the connection that answers, which
     * [isVouchedMember] still has to match against the certified key before a frame is accepted or sent.
     */
    suspend fun hasVouchedRosterKey(groupId: String, deviceId: String): Boolean {
        val row = members.member(groupId, deviceId)?.takeIf { it.isActive } ?: return false
        return row.subjectKey?.let { GroupCanonical.decode(it) }?.isNotEmpty() == true
    }

    /** Withdraws every vouch [groupId] made (this device left the group). */
    suspend fun revokeAll(groupId: String) {
        val port = vouching ?: return
        members.allMembers(groupId).filter { it.deviceId != localDeviceId }.forEach { port.revoke(it.deviceId, groupId) }
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

    /**
     * Applies the vouch each of [certs] makes, and returns the certs that may be stored. This device and the owner
     * need none (the owner was bound to a pairing by the charter check); a tombstone withdraws the vouch.
     */
    private fun installVouches(groupId: String, peerId: String, charter: GroupCharter, certs: List<MemberCert>): List<MemberCert> {
        val port = vouching ?: return certs
        return certs.filter { cert ->
            when {
                cert.subjectId == localDeviceId || cert.subjectId == charter.ownerId -> true
                !cert.active -> {
                    port.revoke(cert.subjectId, groupId)
                    true
                }
                else -> {
                    val key = GroupCanonical.decode(cert.subjectKey)
                    val verdict = if (key == null) {
                        GroupVouchVerdict.INVALID
                    } else {
                        port.vouch(cert.subjectId, GroupCanonical.fingerprintHex(crypto, key), groupId)
                    }
                    if (verdict != GroupVouchVerdict.ACCEPT) {
                        FlashLog.w("CHAT", "SECURITY: vouch refused: group=$groupId subject=${cert.subjectId} from=$peerId verdict=$verdict")
                    }
                    verdict == GroupVouchVerdict.ACCEPT
                }
            }
        }
    }

    /**
     * Re-installs the vouch of every stored active member whose pin is missing (a trust store that was cleared, or a
     * member who was unpaired since), so the roster and the pins cannot drift apart for long. Idempotent, and a no-op
     * for members whose pin is already vouched.
     */
    private suspend fun ensureVouches(groupId: String, charter: GroupCharter) {
        val port = vouching ?: return
        if (members.member(groupId, localDeviceId)?.isActive != true) return
        for (row in members.allMembers(groupId)) {
            if (!row.isActive || row.deviceId == localDeviceId || row.deviceId == charter.ownerId) continue
            val key = row.subjectKey?.let { GroupCanonical.decode(it) } ?: continue
            val fingerprint = GroupCanonical.fingerprintHex(crypto, key)
            if (!port.isVouched(row.deviceId, fingerprint, groupId)) port.vouch(row.deviceId, fingerprint, groupId)
        }
    }

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
