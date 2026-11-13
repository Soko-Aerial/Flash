@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.GroupEnvelopeMeta
import com.transfer.flash.core.security.enterprise.GroupEpochKey
import com.transfer.flash.core.security.enterprise.GroupEpochKeyStore
import com.transfer.flash.core.security.enterprise.GroupKeyDistPayload
import com.transfer.flash.core.security.enterprise.GroupRelaySealing
import com.transfer.flash.core.security.enterprise.MemoryHubGroupEpochKeyStore
import com.transfer.flash.core.security.enterprise.UnsealedGroupPayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Honest compatibility and security status of a Hub-linked group's E2EE encryption (HR10). */
@FlashInternalApi
public enum class GroupE2eeCompatStatus {
    ENCRYPTED,
    LEGACY_MEMBERS_PRESENT,
    BLOCKED_KEY_CHANGED,
    BLOCKED_IDENTITY_MISMATCH,
    NOT_ENROLLED_OR_UNAVAILABLE,
}

/** Snapshot of E2EE encryption state and any non-compliant peers for UI disclosures and fail-closed guards. */
@FlashInternalApi
public data class GroupE2eeGroupState(
    public val status: GroupE2eeCompatStatus,
    public val currentEpoch: Long,
    public val activeMembers: Set<String>,
    public val legacyMembers: List<String> = emptyList(),
    public val blockedMembers: List<String> = emptyList(),
) {
    public val canSendEncrypted: Boolean get() = status == GroupE2eeCompatStatus.ENCRYPTED

    public fun userDisclosure(isEncryptedEnforced: Boolean = true): String = when (status) {
        GroupE2eeCompatStatus.ENCRYPTED ->
            "End-to-end encrypted: Hub group messages are sealed with per-membership epoch keys (epoch $currentEpoch), so your organisation's server cannot read them. Keys rotate when members change."
        GroupE2eeCompatStatus.LEGACY_MEMBERS_PRESENT -> if (isEncryptedEnforced) {
            "End-to-end encryption paused (fail-closed): ${legacyMembers.size} active member(s) have not published encryption keys yet (older app or offline sync). Hub sending is held rather than falling back to readable text."
        } else {
            "Confidentiality disclosure: This group is in legacy unencrypted mode because ${legacyMembers.size} member(s) lack encryption keys."
        }
        GroupE2eeCompatStatus.BLOCKED_KEY_CHANGED ->
            "End-to-end encryption paused: a group member's encryption key changed and needs confirmation before sending through the Hub."
        GroupE2eeCompatStatus.BLOCKED_IDENTITY_MISMATCH ->
            "End-to-end encryption blocked: the Hub directory identity key for a member contradicts your saved pairing key."
        GroupE2eeCompatStatus.NOT_ENROLLED_OR_UNAVAILABLE -> if (isEncryptedEnforced) {
            "End-to-end encryption enabled: waiting for group membership or key directory synchronization before sending through the Hub."
        } else {
            "Confidentiality disclosure: Group messages are not sealed yet, so the hub can read messages it carries while relaying. Messages sent directly between devices on LAN stay private."
        }
    }
}

@FlashInternalApi
public sealed interface GroupSealResult {
    public data class Ready(
        public val epochKey: GroupEpochKey,
        public val sealedEnvelope: String,
        public val keyDistForRecipient: GroupKeyDistPayload?,
    ) : GroupSealResult

    public data class Blocked(
        public val state: GroupE2eeGroupState,
        public val reason: String,
    ) : GroupSealResult
}

@FlashInternalApi
public enum class GroupKeyAcceptOutcome {
    ACCEPTED_CANONICAL,
    ACCEPTED_DECRYPT_ONLY,
    DUPLICATE,
    REJECTED_UNAUTHORIZED,
    REJECTED_STALE_ROSTER,
    REJECTED_CONFLICT,
}

@FlashInternalApi
public sealed interface GroupOpenResult {
    public data class Opened(public val payload: UnsealedGroupPayload) : GroupOpenResult
    public data class MissingEpochKey(public val meta: GroupEnvelopeMeta) : GroupOpenResult
    public data object RetryUnavailableIdentity : GroupOpenResult
    public data class Rejected(public val reason: String) : GroupOpenResult
}

/** Coordinates Hub group E2EE epoch keys, rotations, distribution, fail-closed gating, and decryption. */
@FlashInternalApi
public class HubGroupE2eeCoordinator(
    private val localDeviceId: String,
    private val crypto: FlashCrypto,
    private val keyStore: GroupEpochKeyStore = MemoryHubGroupEpochKeyStore(),
    private val timeSource: FlashTimeSource = SystemTimeSource,
) {
    private val lock = Mutex()
    private var loaded: Boolean = false
    private val keysByGroup = LinkedHashMap<String, MutableList<GroupEpochKey>>()
    private val distributedTo = LinkedHashMap<String, MutableSet<String>>()

    public suspend fun load(): Unit = lock.withLock {
        ensureLoadedLocked()
    }

    public suspend fun clear(): Unit = lock.withLock {
        keysByGroup.clear()
        distributedTo.clear()
        loaded = true
        keyStore.clear()
    }

    public suspend fun currentEpochFor(groupId: String): Long = lock.withLock {
        ensureLoadedLocked()
        keysByGroup[groupId]?.maxOfOrNull { it.epoch } ?: 0L
    }

    public suspend fun evaluateGroup(
        groupId: String,
        activeMembers: Set<String>,
        sealing: HubSealingPort?,
    ): GroupE2eeGroupState {
        val currentEpoch = currentEpochFor(groupId)
        val remoteMembers = activeMembers.filter { it != localDeviceId && it.isNotBlank() }.sorted()
        if (sealing == null || localDeviceId !in activeMembers || remoteMembers.isEmpty()) {
            return GroupE2eeGroupState(
                status = GroupE2eeCompatStatus.NOT_ENROLLED_OR_UNAVAILABLE,
                currentEpoch = currentEpoch,
                activeMembers = activeMembers,
            )
        }
        val legacy = ArrayList<String>()
        val keyChanged = ArrayList<String>()
        val identityMismatch = ArrayList<String>()
        for (member in remoteMembers) {
            when (sealing.statusFor(member)) {
                HubSealingStatus.AVAILABLE -> Unit
                HubSealingStatus.NOT_PUBLISHED -> legacy += member
                HubSealingStatus.KEY_CHANGED -> keyChanged += member
                HubSealingStatus.IDENTITY_MISMATCH -> identityMismatch += member
            }
        }
        return when {
            identityMismatch.isNotEmpty() -> GroupE2eeGroupState(
                status = GroupE2eeCompatStatus.BLOCKED_IDENTITY_MISMATCH,
                currentEpoch = currentEpoch,
                activeMembers = activeMembers,
                blockedMembers = identityMismatch,
            )
            keyChanged.isNotEmpty() -> GroupE2eeGroupState(
                status = GroupE2eeCompatStatus.BLOCKED_KEY_CHANGED,
                currentEpoch = currentEpoch,
                activeMembers = activeMembers,
                blockedMembers = keyChanged,
            )
            legacy.isNotEmpty() -> GroupE2eeGroupState(
                status = GroupE2eeCompatStatus.LEGACY_MEMBERS_PRESENT,
                currentEpoch = currentEpoch,
                activeMembers = activeMembers,
                legacyMembers = legacy,
            )
            else -> GroupE2eeGroupState(
                status = GroupE2eeCompatStatus.ENCRYPTED,
                currentEpoch = currentEpoch,
                activeMembers = activeMembers,
            )
        }
    }

    /**
     * Updates key send-eligibility when a group's membership changes (member add, remove, leave, or rejoin).
     * Previously held keys remain decrypt-only (`canSend = false`) for historical/in-flight epochs that this
     * device belonged to, while future sends rotate to a fresh epoch.
     */
    public suspend fun onMembershipChanged(
        groupId: String,
        activeMembers: Set<String>,
        rosterVersion: Long,
    ): Unit = lock.withLock {
        ensureLoadedLocked()
        val list = keysByGroup[groupId] ?: return
        var dirty = false
        val localInGroup = localDeviceId in activeMembers
        for (i in list.indices) {
            val k = list[i]
            val obsolete = !localInGroup || k.memberDeviceIds != activeMembers || k.rosterVersion < rosterVersion
            if (obsolete && k.canSend) {
                list[i] = k.copy(canSend = false)
                dirty = true
            }
        }
        if (dirty) persistLocked()
    }

    /** Prepares an encrypted `GROUP_SEALED_V1` envelope and (if needed) an unconfirmed `GROUP_KEY_DIST_V1` payload. */
    public suspend fun prepareGroupSend(
        groupId: String,
        activeMembers: Set<String>,
        rosterVersion: Long,
        recipientDeviceId: String,
        msgId: String,
        sentAt: Long,
        innerType: String,
        body: String,
        sealing: HubSealingPort?,
    ): GroupSealResult {
        val state = evaluateGroup(groupId, activeMembers, sealing)
        if (!state.canSendEncrypted) {
            return GroupSealResult.Blocked(state, "group E2EE cannot send (${state.status})")
        }
        if (recipientDeviceId !in activeMembers || recipientDeviceId == localDeviceId) {
            return GroupSealResult.Blocked(state, "recipient $recipientDeviceId is not an active member")
        }
        return lock.withLock {
            ensureLoadedLocked()
            val list = keysByGroup.getOrPut(groupId) { ArrayList() }
            var canonical = list.firstOrNull {
                it.canSend && it.memberDeviceIds == activeMembers && it.rosterVersion >= rosterVersion
            }
            if (canonical == null) {
                for (i in list.indices) {
                    if (list[i].canSend) list[i] = list[i].copy(canSend = false)
                }
                val nextEpoch = (list.maxOfOrNull { it.epoch } ?: 0L) + 1L
                canonical = GroupEpochKey(
                    groupId = groupId,
                    epoch = nextEpoch,
                    keyId = GroupRelaySealing.generateKeyId(),
                    keyBytes = GroupRelaySealing.generateEpochKeyBytes(),
                    rosterVersion = rosterVersion,
                    memberDeviceIds = activeMembers,
                    creatorDeviceId = localDeviceId,
                    createdAtMs = timeSource.nowMs(),
                    canSend = true,
                )
                list += canonical
                persistLocked()
            }
            val sealed = GroupRelaySealing.seal(
                crypto = crypto,
                epochKey = canonical,
                senderDeviceId = localDeviceId,
                msgId = msgId,
                sentAt = sentAt,
                innerType = innerType,
                body = body,
            ) ?: return@withLock GroupSealResult.Blocked(state, "failed to seal under canonical group epoch key")

            val distKey = distMapKey(groupId, canonical.epoch, canonical.keyId)
            val alreadySent = distributedTo[distKey]?.contains(recipientDeviceId) == true
            val canDistribute = canonical.creatorDeviceId == localDeviceId
            val keyDist = if (alreadySent || !canDistribute) {
                null
            } else {
                GroupKeyDistPayload(
                    version = GroupRelaySealing.FORMAT_VERSION,
                    groupId = groupId,
                    epoch = canonical.epoch,
                    keyId = canonical.keyId,
                    keyBytes = canonical.keyBytes,
                    rosterVersion = canonical.rosterVersion,
                    memberDeviceIds = canonical.memberDeviceIds,
                    creatorDeviceId = canonical.creatorDeviceId,
                    createdAtMs = canonical.createdAtMs,
                )
            }
            GroupSealResult.Ready(canonical, sealed, keyDist)
        }
    }

    public suspend fun markKeyDistributed(
        groupId: String,
        epoch: Long,
        keyId: String,
        recipientDeviceId: String,
    ): Unit = lock.withLock {
        distributedTo.getOrPut(distMapKey(groupId, epoch, keyId)) { HashSet() } += recipientDeviceId
    }

    /**
     * Validates and stores an inbound [GroupKeyDistPayload] that already passed pairwise [HubSealing] authentication.
     */
    public suspend fun acceptKeyDist(
        fromDeviceId: String,
        dist: GroupKeyDistPayload,
        senderAllowedInGroup: Boolean,
        activeMembersNow: Set<String>?,
        currentRosterVersion: Long?,
    ): GroupKeyAcceptOutcome = lock.withLock {
        ensureLoadedLocked()
        if (!senderAllowedInGroup) return@withLock GroupKeyAcceptOutcome.REJECTED_UNAUTHORIZED
        if (dist.creatorDeviceId != fromDeviceId) return@withLock GroupKeyAcceptOutcome.REJECTED_UNAUTHORIZED
        if (localDeviceId !in dist.memberDeviceIds || fromDeviceId !in dist.memberDeviceIds) {
            return@withLock GroupKeyAcceptOutcome.REJECTED_UNAUTHORIZED
        }
        if (dist.keyBytes.size != GroupRelaySealing.KEY_BYTES || dist.epoch < 1L) {
            return@withLock GroupKeyAcceptOutcome.REJECTED_UNAUTHORIZED
        }

        val list = keysByGroup.getOrPut(dist.groupId) { ArrayList() }
        val existing = list.firstOrNull { it.epoch == dist.epoch && it.keyId == dist.keyId }
        if (existing != null) {
            return@withLock if (existing.keyBytes.contentEquals(dist.keyBytes) &&
                existing.memberDeviceIds == dist.memberDeviceIds
            ) {
                GroupKeyAcceptOutcome.DUPLICATE
            } else {
                GroupKeyAcceptOutcome.REJECTED_CONFLICT
            }
        }

        val rosterIsBehind = currentRosterVersion != null && dist.rosterVersion < currentRosterVersion
        val membershipDiffersNow = activeMembersNow != null && dist.memberDeviceIds != activeMembersNow
        if (rosterIsBehind && membershipDiffersNow) {
            // An old key distribution from before a known membership change cannot become current,
            // nor should an excluded-from-present key overwrite active keys. Reject if stale.
            return@withLock GroupKeyAcceptOutcome.REJECTED_STALE_ROSTER
        }

        val currentCanonical = list.firstOrNull { it.canSend }
        val newOutranksCanonical = currentCanonical == null || compareCanonical(
            dist.epoch,
            dist.rosterVersion,
            dist.keyId,
            currentCanonical,
        ) > 0
        val promoteSend = !membershipDiffersNow && !rosterIsBehind && newOutranksCanonical

        if (promoteSend) {
            for (i in list.indices) {
                if (list[i].canSend) list[i] = list[i].copy(canSend = false)
            }
        }
        list += GroupEpochKey(
            groupId = dist.groupId,
            epoch = dist.epoch,
            keyId = dist.keyId,
            keyBytes = dist.keyBytes,
            rosterVersion = dist.rosterVersion,
            memberDeviceIds = dist.memberDeviceIds,
            creatorDeviceId = dist.creatorDeviceId,
            createdAtMs = dist.createdAtMs,
            canSend = promoteSend,
        )
        persistLocked()
        if (promoteSend) GroupKeyAcceptOutcome.ACCEPTED_CANONICAL else GroupKeyAcceptOutcome.ACCEPTED_DECRYPT_ONLY
    }

    /** Decrypts and authenticates an inbound `GROUP_SEALED_V1` envelope against stored epoch keys and sender identity. */
    public suspend fun openSealedGroup(
        senderDeviceId: String,
        expectedGroupId: String,
        msgId: String,
        sentAt: Long,
        payload: String,
        sealing: HubSealingPort?,
        isSenderActiveMember: Boolean,
        isLocalActiveMember: Boolean,
    ): GroupOpenResult {
        if (!isLocalActiveMember || !isSenderActiveMember) {
            return GroupOpenResult.Rejected("sender or local device is not an active group member")
        }
        val meta = GroupRelaySealing.peekMetadata(payload)
            ?: return GroupOpenResult.Rejected("malformed group encrypted envelope")
        if (meta.senderId != senderDeviceId || meta.groupId != expectedGroupId) {
            return GroupOpenResult.Rejected("group envelope metadata does not match envelope transport identity")
        }
        val key = lock.withLock {
            ensureLoadedLocked()
            keysByGroup[expectedGroupId]?.firstOrNull { it.epoch == meta.epoch && it.keyId == meta.keyId }?.copy()
        } ?: return GroupOpenResult.MissingEpochKey(meta)

        if (senderDeviceId !in key.memberDeviceIds || localDeviceId !in key.memberDeviceIds) {
            return GroupOpenResult.Rejected("member excluded from epoch ${meta.epoch}")
        }
        val senderIdentity = sealing?.identityPublicKeyEncoded(senderDeviceId)
            ?: return GroupOpenResult.RetryUnavailableIdentity

        val opened = GroupRelaySealing.open(
            crypto = crypto,
            epochKey = key,
            senderIdentityPublicKey = senderIdentity,
            senderDeviceId = senderDeviceId,
            expectedGroupId = expectedGroupId,
            msgId = msgId,
            sentAt = sentAt,
            payload = payload,
        ) ?: return GroupOpenResult.Rejected("group ciphertext authentication failed")

        return GroupOpenResult.Opened(opened)
    }

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val restored = keyStore.loadAll()
        keysByGroup.clear()
        for (k in restored) {
            keysByGroup.getOrPut(k.groupId) { ArrayList() } += k
        }
        loaded = true
    }

    private suspend fun persistLocked() {
        val all = keysByGroup.values.flatten()
        keyStore.saveAll(all)
    }

    private fun compareCanonical(
        epoch: Long,
        rosterVersion: Long,
        keyId: String,
        other: GroupEpochKey,
    ): Int {
        if (epoch != other.epoch) return epoch.compareTo(other.epoch)
        if (rosterVersion != other.rosterVersion) return rosterVersion.compareTo(other.rosterVersion)
        return keyId.compareTo(other.keyId)
    }

    private fun distMapKey(groupId: String, epoch: Long, keyId: String): String = "$groupId|$epoch|$keyId"
}
