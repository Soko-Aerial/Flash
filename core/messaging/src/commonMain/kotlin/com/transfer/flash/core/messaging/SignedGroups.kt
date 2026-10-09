@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.logging.FlashLog
import kotlinx.coroutines.sync.withLock
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupCharter
import com.transfer.flash.core.messaging.protocol.GroupCrypto
import com.transfer.flash.core.messaging.protocol.GroupHistoryCeiling
import com.transfer.flash.core.messaging.protocol.GroupMembershipVersion
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.isSwarmRootHex
import com.transfer.flash.core.messaging.protocol.GroupSignatureRules
import com.transfer.flash.core.messaging.protocol.GroupSigning
import com.transfer.flash.core.messaging.protocol.GroupVouchVerdict
import com.transfer.flash.core.messaging.protocol.GroupVouching
import com.transfer.flash.core.messaging.group.GroupSecretSource
import com.transfer.flash.core.messaging.group.GroupSecretStore
import com.transfer.flash.core.messaging.group.StoredGroupSecret
import com.transfer.flash.core.messaging.group.toEntity
import com.transfer.flash.core.messaging.group.toSettings
import com.transfer.flash.core.messaging.protocol.GroupRotation
import com.transfer.flash.core.messaging.protocol.GroupSettings
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MemberCert
import com.transfer.flash.core.messaging.protocol.VerifyBudget
import com.transfer.flash.core.messaging.protocol.membershipUpdateWins
import com.transfer.flash.core.messaging.protocol.settingsWins
import com.transfer.flash.core.messaging.protocol.toEntity
import com.transfer.flash.core.messaging.protocol.toRotation
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.persistence.db.dao.GroupRotationDao
import com.transfer.flash.core.persistence.db.dao.GroupSettingsDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.security.group.GroupSecret
import com.transfer.flash.core.security.group.GroupSecretCommit

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
    private val hasInvite: (String) -> Boolean = { false },
    private val groupRotationDao: GroupRotationDao? = null,
    private val groupSecretStore: GroupSecretStore? = null,
    private val newRotationId: () -> String = {
        GroupCanonical.hex(crypto.sha256(newId().encodeToByteArray()).copyOf(16))
    },
    private val groupSettingsDao: GroupSettingsDao? = null,
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

    private val isPairedWith = isPaired
    private val rules = GroupSignatureRules(crypto, localDeviceId, isPaired, pinnedFingerprint, vouching, hasInvite)
    private val signing = GroupSigning(crypto)

    /**
     * Serialises [updateSettings]: it reads the stored version, adds one and writes. Two overlapping calls (a fast
     * double tap in the settings sheet) used to read the same version and the second silently replaced the first.
     */
    private val settingsUpdateLock = kotlinx.coroutines.sync.Mutex()
    private val pinnedFingerprintOf = pinnedFingerprint

    /** What became of a received bundle. */
    sealed interface BundleOutcome {
        /** The bundle was verified and merged. [joined] is true when it created the group here. */
        data class Applied(
            val groupId: String,
            val joined: Boolean,
            val rotation: GroupRotation? = null,
            val needsSecret: Boolean = false,
            val reRotated: GroupRotation? = null,
            val winningRotation: GroupRotation? = null,
        ) : BundleOutcome

        /** Nothing changed. [reason] is short and stable so it can be logged and asserted. */
        data class Ignored(val reason: String) : BundleOutcome
    }

    /** What became of a received or processed group rotation. */
    sealed interface RotationOutcome {
        data class Applied(
            val rotation: GroupRotation,
            val needsSecret: Boolean,
            val reRotated: GroupRotation? = null,
        ) : RotationOutcome
        data class WonConcurrently(val winningRotation: GroupRotation) : RotationOutcome
        data class Ignored(val reason: String) : RotationOutcome
    }

    /** A freshly created group: the id plus the bundle to hand to every invitee. */
    class CreatedGroup(val groupId: String, val bundle: GroupWireFrame.Bundle)

    /** What the owner sends after adding members: the changed certs, and the full set for newcomers. */
    class AddedMembers(val changed: GroupWireFrame.Bundle, val full: GroupWireFrame.Bundle)

    /** The owner tombstone for a removed member; the removed device is told too, so it stops sending. */
    class RemovedMember(val bundle: GroupWireFrame.Bundle)

    /**
     * GM-9: Returns the current [GroupSettings] for [groupId], or defaults if none stored (ADR-074).
     */
    suspend fun currentSettings(groupId: String): GroupSettings {
        return groupSettingsDao?.getByGroupId(groupId)?.toSettings() ?: GroupSettings.defaults(groupId)
    }

    /**
     * GM-9: Signs and applies updated [GroupSettings] for [groupId].
     * Can only be called by an active owner or admin.
     * Returns the full updated roster bundle on success, or null on error.
     */
    suspend fun updateSettings(
        groupId: String,
        joinPolicy: String? = null,
        inviteSharers: String? = null,
        maxMembers: Int? = null,
        swarmServing: Boolean? = null,
        membersMayAdd: Boolean? = null,
        historyCeiling: GroupHistoryCeiling? = null,
    ): GroupWireFrame.Bundle? = settingsUpdateLock.withLock {
        updateSettingsLocked(groupId, joinPolicy, inviteSharers, maxMembers, swarmServing, membersMayAdd, historyCeiling)
    }

    private suspend fun updateSettingsLocked(
        groupId: String,
        joinPolicy: String?,
        inviteSharers: String?,
        maxMembers: Int?,
        swarmServing: Boolean?,
        membersMayAdd: Boolean?,
        historyCeiling: GroupHistoryCeiling?,
    ): GroupWireFrame.Bundle? {
        val settingsDao = groupSettingsDao ?: return null
        val charter = storedCharter(groupId) ?: return null
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive } ?: return null
        val isOwner = charter.ownerId == localDeviceId
        val isAdmin = self.role == MemberCert.ROLE_ADMIN
        if (!isOwner && !isAdmin) return null

        val current = settingsDao.getByGroupId(groupId)?.toSettings() ?: GroupSettings.defaults(groupId)
        val nextVersion = current.version + 1L
        val nextJoinPolicy = joinPolicy ?: current.joinPolicy
        val nextInviteSharers = inviteSharers ?: current.inviteSharers
        val nextMaxMembers = (maxMembers ?: current.maxMembers).coerceIn(2, GroupPolicy.MAX_MEMBERS_V2)
        val nextSwarmServing = swarmServing ?: current.swarmServing
        val nextMembersMayAdd = membersMayAdd ?: current.membersMayAdd
        val nextHistoryCeiling = historyCeiling ?: current.historyCeiling
        val opId = newId()

        val signed = signing.signSettings(
            groupId = groupId,
            version = nextVersion,
            joinPolicy = nextJoinPolicy,
            inviteSharers = nextInviteSharers,
            maxMembers = nextMaxMembers,
            swarmServing = nextSwarmServing,
            membersMayAdd = nextMembersMayAdd,
            opId = opId,
            signerId = localDeviceId,
            historyCeiling = nextHistoryCeiling,
        )

        settingsDao.upsert(signed.toEntity())
        FlashLog.i("CHAT", "GM-9: Updated settings for group=$groupId version=$nextVersion opId=$opId")
        return bundleFor(groupId)
    }

    suspend fun isV2(groupId: String): Boolean = conversationDao.get(groupId)?.groupProto == GroupPolicy.V2_PROTOCOL

    fun checkCharter(charter: GroupCharter): String? = rules.checkCharter(charter)

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
        val settings = groupSettingsDao?.let { dao ->
            val initial = signing.signSettings(
                groupId = groupId,
                version = 1L,
                joinPolicy = GroupSettings.POLICY_APPROVE,
                inviteSharers = GroupSettings.SHARERS_ALL,
                maxMembers = GroupPolicy.MAX_MEMBERS_V2,
                swarmServing = true,
                membersMayAdd = false,
                opId = newId(),
                signerId = localDeviceId,
            )
            dao.upsert(initial.toEntity())
            initial
        }
        return CreatedGroup(groupId, GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, certs, settings = settings))
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
        val settings = currentSettings(groupId)
        val canAdd = charter.ownerId == localDeviceId || (self?.isActive == true && (self.role == MemberCert.ROLE_ADMIN || settings.membersMayAdd))
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
     * Removal = tombstone + rotation in one transaction (GINV-4).
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

        // GINV-4: Removal = tombstone + rotation in one transaction
        val rotation = if (groupSecretStore != null && groupRotationDao != null) {
            rotateGroupSecret(groupId, GroupRotation.REASON_REMOVAL, listOf(subjectId))
        } else {
            null
        }

        return RemovedMember(GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, listOf(cert), rotation = rotation))
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
        val rotation = groupRotationDao?.getLatestForGroup(groupId)?.toRotation()
        val settings = groupSettingsDao?.getByGroupId(groupId)?.toSettings()
        return GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, capped, rotation = rotation, settings = settings)
    }

    /**
     * What a member hands a device it knows the owner or admin removed, in case that device was offline for the removal: the
     * charter and that device's authorized tombstone.
     */
    suspend fun removalNoticeFor(groupId: String, subjectId: String): GroupWireFrame.Bundle? {
        val charter = storedCharter(groupId) ?: return null
        val row = members.member(groupId, subjectId)?.takeIf { !it.isActive } ?: return null
        val issuerId = row.issuerId ?: return null
        val isOwnerIssuer = issuerId == charter.ownerId
        val isAdminIssuer = members.member(groupId, issuerId)?.let { it.isActive && it.role == MemberCert.ROLE_ADMIN } == true
        if ((!isOwnerIssuer && !isAdminIssuer) || issuerId == subjectId) return null
        val cert = row.toCert() ?: return null
        val rotation = groupRotationDao?.getLatestForGroup(groupId)?.toRotation()
        return GroupWireFrame.Bundle(groupId, localDeviceId, newId(), charter, listOf(cert), rotation = rotation)
    }

    /**
     * GM-6: Rotates the secret for [groupId] with the specified [reason] and [removedIds].
     * Can only be called by an active owner or admin.
     * Computes the new epoch, generates a fresh 32-byte secret, derives SHA-256 commit,
     * signs canonical "flash-grot-v1" rotation statement, and persists both in a single step.
     */
    suspend fun rotateGroupSecret(
        groupId: String,
        reason: String,
        removedIds: List<String> = emptyList(),
    ): GroupRotation? {
        val secretStore = groupSecretStore ?: return null
        val rotationDao = groupRotationDao ?: return null
        val charter = storedCharter(groupId) ?: return null
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive } ?: return null
        val isOwner = charter.ownerId == localDeviceId
        val isAdmin = self.role == MemberCert.ROLE_ADMIN
        if (!isOwner && !isAdmin) return null

        val currentSecret = secretStore.current(groupId)
        val latestRotation = rotationDao.getLatestForGroup(groupId)
        val currentEpoch = maxOf(currentSecret?.epoch ?: 1L, latestRotation?.newEpoch ?: 1L)
        val newEpoch = currentEpoch + 1L
        val prevEpoch = currentSecret?.epoch ?: (newEpoch - 1L)

        val newSecret = GroupSecret.generate()
        val commit = GroupSecretCommit.ofHex(groupId, newEpoch, newSecret)
        val rotId = newRotationId()

        val rotation = signing.issueRotation(
            groupId = groupId,
            newEpoch = newEpoch,
            prevEpoch = prevEpoch,
            commitHex = commit,
            reason = reason,
            adminId = localDeviceId,
            rotationId = rotId,
            removedIds = removedIds,
        )

        val now = nowMs()
        secretStore.put(
            StoredGroupSecret(
                groupId = groupId,
                epoch = newEpoch,
                secret = newSecret,
                commit = commit,
                source = GroupSecretSource.ROTATED,
                receivedAtMs = now,
            ),
        )
        rotationDao.upsert(rotation.toEntity(now))
        FlashLog.i("CHAT", "Rotated secret for group=$groupId newEpoch=$newEpoch reason=$reason rotId=$rotId")
        return rotation
    }

    /**
     * GM-6: Validates and processes an inbound rotation notice from [senderId].
     * Checks signature against admin/owner keys, executes tie-breaking for concurrent rotations,
     * re-rotates if local rotation lost, and updates database.
     */
    suspend fun handleIncomingRotation(
        groupId: String,
        rotation: GroupRotation,
        senderId: String,
    ): RotationOutcome {
        if (rotation.groupId != groupId) return RotationOutcome.Ignored("group-id-mismatch")
        val charter = storedCharter(groupId) ?: return RotationOutcome.Ignored("unknown-group")
        val adminRow = members.member(groupId, rotation.adminId)
        val isOwner = rotation.adminId == charter.ownerId
        val isAdmin = adminRow != null && adminRow.isActive && adminRow.role == MemberCert.ROLE_ADMIN
        if (!isOwner && !isAdmin) return RotationOutcome.Ignored("issuer-not-admin")

        val adminKey = if (isOwner) {
            GroupCanonical.decode(charter.ownerKey)
        } else {
            adminRow?.subjectKey?.let { GroupCanonical.decode(it) }
        } ?: return RotationOutcome.Ignored("no-admin-key")

        val errorReason = rules.checkRotation(charter, rotation) { id ->
            if (id == rotation.adminId) adminKey else null
        }
        if (errorReason != null) {
            FlashLog.w("CHAT", "SECURITY: group rotation rejected: group=$groupId admin=${rotation.adminId} reason=$errorReason")
            return RotationOutcome.Ignored(errorReason)
        }

        val rotationDao = groupRotationDao ?: return RotationOutcome.Ignored("no-dao")
        val currentLatest = rotationDao.getLatestForGroup(groupId)

        if (currentLatest != null) {
            if (rotation.newEpoch < currentLatest.newEpoch) {
                return RotationOutcome.Ignored("stale-epoch")
            }
            if (rotation.newEpoch == currentLatest.newEpoch) {
                if (rotation.rotationId == currentLatest.rotationId) {
                    return RotationOutcome.Ignored("duplicate")
                }
                // Concurrency rule: smaller rotationId wins
                if (rotation.rotationId > currentLatest.rotationId) {
                    return RotationOutcome.WonConcurrently(currentLatest.toRotation())
                }
            }
        }

        // Incoming rotation wins (or has higher epoch)
        val lostOurRotation = currentLatest != null &&
            currentLatest.adminId == localDeviceId &&
            currentLatest.newEpoch == rotation.newEpoch &&
            rotation.rotationId < currentLatest.rotationId

        val now = nowMs()
        rotationDao.upsert(rotation.toEntity(now))

        val reRotated = if (lostOurRotation) {
            FlashLog.w("CHAT", "Local rotation for group=$groupId epoch=${currentLatest.newEpoch} lost tie-breaker (rotId=${currentLatest.rotationId} > ${rotation.rotationId}); re-rotating at epoch ${rotation.newEpoch + 1}")
            val ourRemovals = if (currentLatest.removedIds.isBlank()) emptyList() else currentLatest.removedIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            rotateGroupSecret(groupId, currentLatest.reason, ourRemovals)
        } else {
            null
        }

        val currentSecret = groupSecretStore?.current(groupId)
        val needsSecret = currentSecret == null ||
            currentSecret.epoch < rotation.newEpoch ||
            (currentSecret.epoch == rotation.newEpoch && currentSecret.commit != rotation.commit)
        return RotationOutcome.Applied(rotation, needsSecret, reRotated)
    }

    /**
     * GM-7: Upgrades an existing v2 group that has no rotation notice (O-11).
     * When an admin device holds a v2 group with no rotation notice, creates epoch 1
     * with a notice (reason = UPGRADE, prevEpoch = 0).
     *
     * Returns the created [GroupRotation], or null if this device is not an active owner/admin,
     * the group is not v2, or a rotation notice already exists.
     */
    suspend fun upgradeGroupSecret(groupId: String): GroupRotation? {
        if (!GroupPolicy.isV2GroupId(groupId)) return null
        val secretStore = groupSecretStore ?: return null
        val rotationDao = groupRotationDao ?: return null
        val charter = storedCharter(groupId) ?: return null
        val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive } ?: return null
        val isOwner = charter.ownerId == localDeviceId
        val isAdmin = self.role == MemberCert.ROLE_ADMIN
        if (!isOwner && !isAdmin) return null

        if (rotationDao.getLatestForGroup(groupId) != null) return null

        val currentSecret = secretStore.current(groupId)
        val epoch = 1L
        val prevEpoch = 0L
        val secret = currentSecret?.secret ?: GroupSecret.generate()
        val commit = GroupSecretCommit.ofHex(groupId, epoch, secret)
        val rotId = newRotationId()

        val rotation = signing.issueRotation(
            groupId = groupId,
            newEpoch = epoch,
            prevEpoch = prevEpoch,
            commitHex = commit,
            reason = GroupRotation.REASON_UPGRADE,
            adminId = localDeviceId,
            rotationId = rotId,
            removedIds = emptyList(),
        )

        val now = nowMs()
        if (currentSecret == null) {
            secretStore.put(
                StoredGroupSecret(
                    groupId = groupId,
                    epoch = epoch,
                    secret = secret,
                    commit = commit,
                    source = GroupSecretSource.ROTATED,
                    receivedAtMs = now,
                ),
            )
        }
        rotationDao.upsert(rotation.toEntity(now))
        FlashLog.i("CHAT", "GM-7: Upgraded v2 group=$groupId with epoch=1 secret rotId=$rotId")
        return rotation
    }

    /**
     * GM-7: On startup/event, upgrades all existing v2 groups where this device is active owner
     * or admin and which have no rotation notice (O-11).
     */
    suspend fun upgradeExistingV2Groups(): List<GroupRotation> {
        val groupIds = members.activeGroupIdsFor(localDeviceId)
        val generated = ArrayList<GroupRotation>()
        for (groupId in groupIds) {
            upgradeGroupSecret(groupId)?.let { generated += it }
        }
        return generated
    }

    /**
     * GM-6 Task 2: Crash recovery (GINV-4).
     * On startup, inspects all groups where local device is active owner or admin.
     * If any tombstone signed by local device is not covered by a rotation signed by local device,
     * triggers rotation to ensure the removed device's secret access is revoked.
     */
    suspend fun recoverUnrotatedTombstones(): List<GroupRotation> {
        val rotationDao = groupRotationDao ?: return emptyList()
        val generated = ArrayList<GroupRotation>()
        val groupIds = members.activeGroupIdsFor(localDeviceId)
        for (groupId in groupIds) {
            val charter = storedCharter(groupId) ?: continue
            val self = members.member(groupId, localDeviceId)?.takeIf { it.isActive } ?: continue
            val isOwner = charter.ownerId == localDeviceId
            val isAdmin = self.role == MemberCert.ROLE_ADMIN
            if (!isOwner && !isAdmin) continue

            val tombstonedByUs = members.allMembers(groupId)
                .filter { !it.isActive && it.issuerId == localDeviceId && it.deviceId != localDeviceId }
                .map { it.deviceId }
                .toSet()
            if (tombstonedByUs.isEmpty()) continue

            val ourRotations = rotationDao.getAllForGroup(groupId).filter { it.adminId == localDeviceId }
            val coveredRemovals = ourRotations.flatMap { rot ->
                if (rot.removedIds.isBlank()) emptyList() else rot.removedIds.split(',').map { it.trim() }
            }.toSet()

            val unrotated = tombstonedByUs - coveredRemovals
            if (unrotated.isNotEmpty()) {
                FlashLog.w("CHAT", "Startup recovery: found ${unrotated.size} unrotated tombstones in group=$groupId; rotating secret")
                rotateGroupSecret(groupId, GroupRotation.REASON_REMOVAL, unrotated.toList())?.let {
                    generated += it
                }
            }
        }
        return generated
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

    /** Base64 signature over the canonical bytes of a swarm announcement authored by this device in [groupId]. */
    fun signSwarmAnnouncement(
        groupId: String,
        messageId: String,
        root: String,
        sizeBytes: Long,
        fileName: String,
        mimeType: String,
        sentAt: Long,
    ): String? {
        val statement = GroupCanonical.swarmAnnounceBytes(
            groupId = groupId,
            messageId = messageId,
            originId = localDeviceId,
            rootHex = root,
            sizeBytes = sizeBytes,
            fileName = fileName,
            mimeType = mimeType,
            sentAt = sentAt,
        )
        return try {
            GroupCanonical.encode(crypto.sign(statement))
        } catch (_: Throwable) {
            null
        }
    }

    /** Verifies a swarm announcement root signature against the signed roster member key. */
    suspend fun verifySwarmAnnouncement(
        groupId: String,
        authorId: String,
        messageId: String,
        root: String,
        sizeBytes: Long,
        fileName: String,
        mimeType: String,
        sentAt: Long,
        rootSig: String,
    ): Boolean {
        // A member can sign any string; the root must still be a real content root before it reaches the swarm host.
        if (!isSwarmRootHex(root)) return false
        val member = members.member(groupId, authorId) ?: return false
        if (!member.isActive) return false
        val memberKey = member.subjectKey ?: return false
        val subjectKey = GroupCanonical.decode(memberKey) ?: return false
        val sig = GroupCanonical.decode(rootSig) ?: return false
        val statement = GroupCanonical.swarmAnnounceBytes(
            groupId = groupId,
            messageId = messageId,
            originId = authorId,
            rootHex = root,
            sizeBytes = sizeBytes,
            fileName = fileName,
            mimeType = mimeType,
            sentAt = sentAt,
        )
        return try {
            crypto.verify(sig, statement, subjectKey)
        } catch (_: Throwable) {
            false
        }
    }

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

        val storedSettings = groupSettingsDao?.getByGroupId(groupId)?.toSettings()
        val memberKeys = rows.values
            .filter { it.isActive }
            .mapNotNull { row -> row.subjectKey?.let { k -> GroupCanonical.decode(k)?.let { row.deviceId to it } } }
            .toMap().toMutableMap()

        // `membersMayAdd` decides whether a plain member's cert counts, so it is read only from settings whose
        // signature checks out against the owner or a known admin, never from the raw frame. A group this device
        // is only now joining has no stored settings, so the frame's own (verified) settings are what apply.
        //
        // R-14 (sweep 2026-10-09): the answer depends only on which admin keys are known, so it is computed again only when
        // that set changed (a verified admin cert in this same bundle). It used to verify the settings signature once per
        // certificate per pass, outside the budget that charges a candidate cert.
        var adminKeysVersion = 0
        var memberMayAddAt = -1
        var memberMayAddValue = false
        fun membersMayAddNow(): Boolean {
            if (memberMayAddAt != adminKeysVersion) {
                val incoming = frame.settings?.takeIf { s -> rules.checkSettings(charter, s) { id -> adminKeys[id] } == null }
                val effective = if (incoming != null && settingsWins(incoming, storedSettings)) incoming else storedSettings
                memberMayAddValue = effective?.membersMayAdd ?: false
                memberMayAddAt = adminKeysVersion
            }
            return memberMayAddValue
        }

        // A member-issued cert is only checkable once its issuer's own cert is known, and that cert usually travels
        // in the same bundle (a device being added to a group it has never seen gets the whole roster). So verify in
        // passes: certs that fail only for lack of an issuer wait for the next pass, and every cert that verifies
        // makes its subject's key available as an issuer. Anything still unresolved when a pass adds nothing is dropped.
        val verified = ArrayList<MemberCert>()
        val waiting = ArrayList(candidates)
        var progressed = true
        while (waiting.isNotEmpty() && progressed) {
            progressed = false
            val iterator = waiting.iterator()
            while (iterator.hasNext()) {
                val cert = iterator.next()
                val reason = rules.checkCert(
                    charter = charter,
                    cert = cert,
                    knownKey = rows[cert.subjectId]?.subjectKey,
                    adminLookup = { adminId -> adminKeys[adminId] },
                    membersMayAdd = membersMayAddNow(),
                    memberLookup = { memberId -> memberKeys[memberId] },
                )
                if (reason == "issuer") continue
                iterator.remove()
                if (reason == null) {
                    verified += cert
                    progressed = true
                    if (cert.active) GroupCanonical.decode(cert.subjectKey)?.let { memberKeys[cert.subjectId] = it }
                    if (cert.role == MemberCert.ROLE_ADMIN && cert.active) {
                        GroupCanonical.decode(cert.subjectKey)?.let {
                            adminKeys[cert.subjectId] = it
                            adminKeysVersion++
                        }
                    } else if (!cert.active || cert.role != MemberCert.ROLE_ADMIN) {
                        if (adminKeys.remove(cert.subjectId) != null) adminKeysVersion++
                    }
                } else {
                    FlashLog.w("CHAT", "Group cert dropped: group=$groupId subject=${cert.subjectId} from=$peerId reason=$reason")
                }
            }
        }
        waiting.forEach { cert ->
            FlashLog.w("CHAT", "Group cert dropped: group=$groupId subject=${cert.subjectId} issuer=${cert.issuerId} from=$peerId reason=issuer")
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
            val rotOutcome = frame.rotation?.let { handleIncomingRotation(groupId, it, peerId) }
            frame.settings?.let { handleIncomingSettings(charter, it, peerId) }
            val rot = (rotOutcome as? RotationOutcome.Applied)?.rotation
            val needsSecret = (rotOutcome as? RotationOutcome.Applied)?.needsSecret ?: false
            val reRotated = (rotOutcome as? RotationOutcome.Applied)?.reRotated
            val winningRot = (rotOutcome as? RotationOutcome.WonConcurrently)?.winningRotation
            return BundleOutcome.Applied(
                groupId = groupId,
                joined = false,
                rotation = rot,
                needsSecret = needsSecret,
                reRotated = reRotated,
                winningRotation = winningRot,
            )
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

        val rotOutcome = frame.rotation?.let { handleIncomingRotation(groupId, it, peerId) }
        frame.settings?.let { handleIncomingSettings(charter, it, peerId) }
        val rot = (rotOutcome as? RotationOutcome.Applied)?.rotation
        val needsSecret = (rotOutcome as? RotationOutcome.Applied)?.needsSecret ?: false
        val reRotated = (rotOutcome as? RotationOutcome.Applied)?.reRotated
        val winningRot = (rotOutcome as? RotationOutcome.WonConcurrently)?.winningRotation
        return BundleOutcome.Applied(
            groupId = groupId,
            joined = !known,
            rotation = rot,
            needsSecret = needsSecret,
            reRotated = reRotated,
            winningRotation = winningRot,
        )
    }

    private suspend fun handleIncomingSettings(
        charter: GroupCharter,
        incoming: GroupSettings,
        peerId: String,
    ) {
        val settingsDao = groupSettingsDao ?: return
        val groupId = charter.groupId
        if (incoming.groupId != groupId) return

        val adminRow = members.member(groupId, incoming.signerId)
        val isOwner = incoming.signerId == charter.ownerId
        val isAdmin = adminRow != null && adminRow.isActive && adminRow.role == MemberCert.ROLE_ADMIN
        if (!isOwner && !isAdmin) {
            FlashLog.w("CHAT", "SECURITY: group settings rejected: group=$groupId signer=${incoming.signerId} from=$peerId reason=signer-not-admin")
            return
        }

        val adminKey = if (isOwner) {
            GroupCanonical.decode(charter.ownerKey)
        } else {
            adminRow?.subjectKey?.let { GroupCanonical.decode(it) }
        } ?: return

        val reason = rules.checkSettings(charter, incoming) { id ->
            if (id == incoming.signerId) adminKey else null
        }
        if (reason != null) {
            FlashLog.w("CHAT", "SECURITY: group settings rejected: group=$groupId signer=${incoming.signerId} from=$peerId reason=$reason")
            return
        }

        val current = settingsDao.getByGroupId(groupId)?.toSettings()
        if (settingsWins(incoming, current)) {
            settingsDao.upsert(incoming.toEntity())
            FlashLog.i("CHAT", "GM-9: Applied incoming settings for group=$groupId version=${incoming.version} opId=${incoming.opId} from=$peerId")
        }
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
                cert.subjectId == localDeviceId -> true
                cert.subjectId == charter.ownerId && isPairedWith(charter.ownerId) -> true
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
            if (!row.isActive || row.deviceId == localDeviceId) continue
            if (row.deviceId == charter.ownerId && isPairedWith(charter.ownerId)) continue
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
