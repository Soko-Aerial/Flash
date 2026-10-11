package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Storage contract for managing enterprise enrollment credentials and root trust pins.
 */
@FlashInternalApi
public interface EnterpriseTrustStore {
    public val credentials: StateFlow<EnrollmentCredentials?>

    public fun getCredentials(): EnrollmentCredentials? = credentials.value

    public suspend fun saveCredentials(credentials: EnrollmentCredentials)

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

    override suspend fun saveCredentials(credentials: EnrollmentCredentials) {
        _credentials.value = credentials
    }

    override suspend fun clearCredentials() {
        _credentials.value = null
        _linkedHubGroups.value = emptySet()
    }

    override suspend fun linkGroupToHub(groupId: String) {
        _linkedHubGroups.value = _linkedHubGroups.value + groupId
    }

    override suspend fun unlinkGroupFromHub(groupId: String) {
        _linkedHubGroups.value = _linkedHubGroups.value - groupId
    }
}
