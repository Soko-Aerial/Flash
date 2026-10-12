package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

@FlashInternalApi
public class JvmFileEnterpriseTrustStore(
    private val stateDir: File,
) : EnterpriseTrustStore {
    private val file = File(stateDir, "enterprise_trust.properties")
    private val lock = Any()

    private val _credentials = MutableStateFlow<EnrollmentCredentials?>(null)
    override val credentials: StateFlow<EnrollmentCredentials?> = _credentials.asStateFlow()

    private val _linkedHubGroups = MutableStateFlow<Set<String>>(emptySet())
    override val linkedHubGroups: StateFlow<Set<String>> = _linkedHubGroups.asStateFlow()

    init {
        stateDir.mkdirs()
        load()
    }

    private fun load() = synchronized(lock) {
        if (!file.isFile) return@synchronized
        runCatching {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val json = props.getProperty("credentials")
            if (!json.isNullOrBlank()) {
                _credentials.value = EnterpriseJsonHelper.parseEnrollmentCredentials(json)
            }
            val groups = props.getProperty("linked_groups").orEmpty()
                .split(",")
                .filter { it.isNotBlank() }
                .toSet()
            _linkedHubGroups.value = groups
        }
    }

    private fun persist() = synchronized(lock) {
        runCatching {
            val props = Properties()
            _credentials.value?.let {
                props.setProperty("credentials", EnterpriseJsonHelper.serializeCredentials(it))
            }
            props.setProperty("linked_groups", _linkedHubGroups.value.joinToString(","))
            file.outputStream().use { props.store(it, "Flash Enterprise Trust Store") }
        }
    }

    override suspend fun saveCredentials(credentials: EnrollmentCredentials) {
        _credentials.value = credentials
        persist()
    }

    override suspend fun clearCredentials() {
        _credentials.value = null
        _linkedHubGroups.value = emptySet()
        synchronized(lock) {
            file.delete()
        }
    }

    override suspend fun linkGroupToHub(groupId: String) {
        _linkedHubGroups.value = _linkedHubGroups.value + groupId
        persist()
    }

    override suspend fun unlinkGroupFromHub(groupId: String) {
        _linkedHubGroups.value = _linkedHubGroups.value - groupId
        persist()
    }
}
