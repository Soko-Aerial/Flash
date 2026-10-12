package com.transfer.flash.core.network.enterprise

import android.content.Context
import android.content.SharedPreferences
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@FlashInternalApi
public class AndroidPreferencesEnterpriseTrustStore(
    context: Context,
) : EnterpriseTrustStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _credentials = MutableStateFlow<EnrollmentCredentials?>(loadCredentials())
    override val credentials: StateFlow<EnrollmentCredentials?> = _credentials.asStateFlow()

    private val _linkedHubGroups = MutableStateFlow<Set<String>>(loadLinkedGroups())
    override val linkedHubGroups: StateFlow<Set<String>> = _linkedHubGroups.asStateFlow()

    private fun loadCredentials(): EnrollmentCredentials? {
        val rawJson = prefs.getString(KEY_CREDENTIALS, null) ?: return null
        return runCatching { EnterpriseJsonHelper.parseEnrollmentCredentials(rawJson) }.getOrNull()
    }

    private fun loadLinkedGroups(): Set<String> {
        return prefs.getStringSet(KEY_LINKED_GROUPS, emptySet())?.toSet().orEmpty()
    }

    override suspend fun saveCredentials(credentials: EnrollmentCredentials) {
        val json = EnterpriseJsonHelper.serializeCredentials(credentials)
        prefs.edit().putString(KEY_CREDENTIALS, json).apply()
        _credentials.value = credentials
    }

    override suspend fun clearCredentials() {
        prefs.edit().remove(KEY_CREDENTIALS).remove(KEY_LINKED_GROUPS).apply()
        _credentials.value = null
        _linkedHubGroups.value = emptySet()
    }

    override suspend fun linkGroupToHub(groupId: String) {
        val updated = _linkedHubGroups.value + groupId
        prefs.edit().putStringSet(KEY_LINKED_GROUPS, updated).apply()
        _linkedHubGroups.value = updated
    }

    override suspend fun unlinkGroupFromHub(groupId: String) {
        val updated = _linkedHubGroups.value - groupId
        prefs.edit().putStringSet(KEY_LINKED_GROUPS, updated).apply()
        _linkedHubGroups.value = updated
    }

    public companion object {
        private const val PREFS_NAME = "flash_enterprise_trust"
        private const val KEY_CREDENTIALS = "enterprise_credentials_json"
        private const val KEY_LINKED_GROUPS = "enterprise_linked_groups"
    }
}
