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

    override suspend fun saveCredentials(credentials: EnrollmentCredentials) {
        _credentials.value = credentials
    }

    override suspend fun clearCredentials() {
        _credentials.value = null
    }
}
