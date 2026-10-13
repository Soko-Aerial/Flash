package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The groups that were linked to a hub when the device last unenrolled (CLI-09). Kept so a re-enrolment with the SAME
 * organisation ([rootFingerprint]) can offer to link them again instead of silently losing the user's hub-backed
 * groups. Never applied to a different organisation.
 */
@FlashInternalApi
public data class PreviousHubLinks(
    public val rootFingerprint: String,
    public val groupIds: Set<String>,
)

/**
 * Storage contract for managing enterprise enrollment credentials and root trust pins.
 *
 * Credential mutations ([saveCredentials], [clearCredentials], [savePendingUnenrol]) persist FIRST and only then
 * update the observable state; when the write fails they throw and leave both the old state and the stored copy
 * untouched (CLI-12), so a half-written enrolment can never be mistaken for a valid one. Group link bookkeeping is
 * best effort: it updates the observable state and reports a failed write through [storageError].
 */
@FlashInternalApi
public interface EnterpriseTrustStore {
    public val credentials: StateFlow<EnrollmentCredentials?>

    public fun getCredentials(): EnrollmentCredentials? = credentials.value

    /** Stores [credentials] atomically. Throws when the write fails; nothing is changed in that case. */
    public suspend fun saveCredentials(credentials: EnrollmentCredentials)

    /**
     * Removes the credentials and the group links in one atomic write. The group ids that were linked are kept as
     * [previousHubLinks] (tagged with the organisation's root fingerprint) so a later re-enrolment can restore them.
     */
    public suspend fun clearCredentials()

    public val isEnrolled: Boolean get() = credentials.value != null

    /**
     * Set of group IDs explicitly configured to relay new messages through the enterprise hub (E2c).
     * Pre-existing group migration strictly obeys the Zero Cloud Dump Invariant: past messages
     * stored locally are NEVER uploaded.
     */
    public val linkedHubGroups: StateFlow<Set<String>>

    public fun isGroupLinkedToHub(groupId: String): Boolean = groupId in linkedHubGroups.value

    public fun getHubUrlForGroup(groupId: String): String? =
        if (isGroupLinkedToHub(groupId)) getCredentials()?.hubUrl else null

    public suspend fun linkGroupToHub(groupId: String)

    public suspend fun unlinkGroupFromHub(groupId: String)

    /** The group links of the previous enrolment, or null. See [PreviousHubLinks]. */
    public val previousHubLinks: StateFlow<PreviousHubLinks?>

    /** Forgets [previousHubLinks] (after they were restored or declined). */
    public suspend fun clearPreviousHubLinks()

    /**
     * The credentials of an unenrolment the hub has not confirmed yet (the device was offline): the hub is told the
     * next time it is reachable, then this is cleared. The device is already "not enrolled" for every other purpose.
     */
    public val pendingUnenrol: StateFlow<EnrollmentCredentials?>

    /** Sets (or, with null, clears) [pendingUnenrol]. Throws when the write fails. */
    public suspend fun savePendingUnenrol(credentials: EnrollmentCredentials?)

    /** A plain-language sentence when the last write to the device's storage failed, else null (CLI-12). */
    public val storageError: StateFlow<String?>

    /**
     * Groups this device asked to join that an admin has not decided yet (CLI-04): group id to the group name.
     * Survives a restart so the chat list can keep saying "Waiting for approval".
     */
    public val pendingJoins: StateFlow<Map<String, String>>

    /** Records (or, with a null [name], removes) a pending join. Best effort: a failed write is reported by [storageError]. */
    public suspend fun setPendingJoin(groupId: String, name: String?)
}

/** Text form of [EnterpriseTrustStore.pendingJoins] for the stores' single string value. */
@FlashInternalApi
public object PendingJoinsCodec {
    public fun encode(joins: Map<String, String>): String = joins.entries.joinToString(";") { (id, name) ->
        com.transfer.flash.core.common.protocol.Base64.encode(id.encodeToByteArray()) + ":" +
            com.transfer.flash.core.common.protocol.Base64.encode(name.encodeToByteArray())
    }

    public fun decode(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in text.split(';')) {
            val bits = part.split(':')
            if (bits.size != 2) continue
            val id = runCatching { com.transfer.flash.core.common.protocol.Base64.decode(bits[0]).decodeToString() }.getOrNull() ?: continue
            val name = runCatching { com.transfer.flash.core.common.protocol.Base64.decode(bits[1]).decodeToString() }.getOrNull() ?: continue
            if (id.isNotBlank()) out[id] = name
        }
        return out
    }
}

/**
 * Default in-memory implementation of [EnterpriseTrustStore].
 */
@FlashInternalApi
public class MemoryEnterpriseTrustStore(
    initial: EnrollmentCredentials? = null,
) : EnterpriseTrustStore {
    private val _credentials = MutableStateFlow<EnrollmentCredentials?>(initial)
    override val credentials: StateFlow<EnrollmentCredentials?> = _credentials.asStateFlow()

    private val _linkedHubGroups = MutableStateFlow<Set<String>>(emptySet())
    override val linkedHubGroups: StateFlow<Set<String>> = _linkedHubGroups.asStateFlow()

    private val _previousHubLinks = MutableStateFlow<PreviousHubLinks?>(null)
    override val previousHubLinks: StateFlow<PreviousHubLinks?> = _previousHubLinks.asStateFlow()

    private val _pendingUnenrol = MutableStateFlow<EnrollmentCredentials?>(null)
    override val pendingUnenrol: StateFlow<EnrollmentCredentials?> = _pendingUnenrol.asStateFlow()

    private val _storageError = MutableStateFlow<String?>(null)
    override val storageError: StateFlow<String?> = _storageError.asStateFlow()

    private val _pendingJoins = MutableStateFlow<Map<String, String>>(emptyMap())
    override val pendingJoins: StateFlow<Map<String, String>> = _pendingJoins.asStateFlow()

    override suspend fun setPendingJoin(groupId: String, name: String?) {
        _pendingJoins.value = if (name == null) _pendingJoins.value - groupId else _pendingJoins.value + (groupId to name)
    }

    override suspend fun saveCredentials(credentials: EnrollmentCredentials) {
        _credentials.value = credentials
    }

    override suspend fun clearCredentials() {
        val old = _credentials.value
        val groups = _linkedHubGroups.value
        if (old != null && groups.isNotEmpty()) _previousHubLinks.value = PreviousHubLinks(old.rootFingerprint, groups)
        _credentials.value = null
        _linkedHubGroups.value = emptySet()
    }

    override suspend fun linkGroupToHub(groupId: String) {
        _linkedHubGroups.value = _linkedHubGroups.value + groupId
    }

    override suspend fun unlinkGroupFromHub(groupId: String) {
        _linkedHubGroups.value = _linkedHubGroups.value - groupId
    }

    override suspend fun clearPreviousHubLinks() {
        _previousHubLinks.value = null
    }

    override suspend fun savePendingUnenrol(credentials: EnrollmentCredentials?) {
        _pendingUnenrol.value = credentials
    }
}
