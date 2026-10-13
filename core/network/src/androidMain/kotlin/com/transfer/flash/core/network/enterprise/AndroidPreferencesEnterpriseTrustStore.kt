package com.transfer.flash.core.network.enterprise

import android.content.Context
import android.content.SharedPreferences
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.PendingJoinsCodec
import com.transfer.flash.core.security.enterprise.PreviousHubLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Android enterprise trust store on SharedPreferences.
 *
 * CLI-12: credential changes use `commit()` (synchronous, one atomic batch for everything that changes together) and
 * throw when it reports failure, so the in-memory state only changes after the write stuck. Group link bookkeeping
 * keeps `commit()` too but only reports a failure through [storageError].
 */
@FlashInternalApi
public class AndroidPreferencesEnterpriseTrustStore(
    context: Context,
) : EnterpriseTrustStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _storageError = MutableStateFlow<String?>(null)
    override val storageError: StateFlow<String?> = _storageError.asStateFlow()

    private val _credentials = MutableStateFlow<EnrollmentCredentials?>(loadCredentials())
    override val credentials: StateFlow<EnrollmentCredentials?> = _credentials.asStateFlow()

    private val _linkedHubGroups = MutableStateFlow<Set<String>>(loadLinkedGroups())
    override val linkedHubGroups: StateFlow<Set<String>> = _linkedHubGroups.asStateFlow()

    private val _previousHubLinks = MutableStateFlow<PreviousHubLinks?>(loadPreviousLinks())
    override val previousHubLinks: StateFlow<PreviousHubLinks?> = _previousHubLinks.asStateFlow()

    private val _pendingUnenrol = MutableStateFlow<EnrollmentCredentials?>(loadPending())
    override val pendingUnenrol: StateFlow<EnrollmentCredentials?> = _pendingUnenrol.asStateFlow()

    private val _pendingJoins = MutableStateFlow(PendingJoinsCodec.decode(prefs.getString(KEY_PENDING_JOINS, null)))
    override val pendingJoins: StateFlow<Map<String, String>> = _pendingJoins.asStateFlow()

    override suspend fun setPendingJoin(groupId: String, name: String?): Unit = synchronized(lock) {
        val updated = if (name == null) _pendingJoins.value - groupId else _pendingJoins.value + (groupId to name)
        _pendingJoins.value = updated
        runCatching {
            commitOrThrow { if (updated.isEmpty()) remove(KEY_PENDING_JOINS) else putString(KEY_PENDING_JOINS, PendingJoinsCodec.encode(updated)) }
        }
        Unit
    }

    private fun loadCredentials(): EnrollmentCredentials? {
        val rawJson = prefs.getString(KEY_CREDENTIALS, null) ?: return null
        val parsed = runCatching { EnterpriseCredentialCodec.parse(rawJson) }.getOrNull()
        if (parsed == null) _storageError.value = UNREADABLE
        return parsed
    }

    private fun loadPending(): EnrollmentCredentials? {
        val rawJson = prefs.getString(KEY_PENDING_UNENROL, null) ?: return null
        return runCatching { EnterpriseCredentialCodec.parse(rawJson) }.getOrNull()
    }

    private fun loadLinkedGroups(): Set<String> = prefs.getStringSet(KEY_LINKED_GROUPS, emptySet())?.toSet().orEmpty()

    private fun loadPreviousLinks(): PreviousHubLinks? {
        val root = prefs.getString(KEY_PREVIOUS_ROOT, null).orEmpty()
        val groups = prefs.getStringSet(KEY_PREVIOUS_GROUPS, emptySet())?.toSet().orEmpty()
        return if (root.isNotBlank() && groups.isNotEmpty()) PreviousHubLinks(root, groups) else null
    }

    /** Applies [block] to one editor and commits; throws [IOException] (and records [storageError]) when it fails. */
    private fun commitOrThrow(block: SharedPreferences.Editor.() -> Unit) {
        val ok = runCatching { prefs.edit().apply(block).commit() }.getOrDefault(false)
        if (!ok) {
            _storageError.value = WRITE_FAILED
            throw IOException(WRITE_FAILED)
        }
        _storageError.value = null
    }

    override suspend fun saveCredentials(credentials: EnrollmentCredentials): Unit = synchronized(lock) {
        val json = EnterpriseCredentialCodec.serialize(credentials)
        commitOrThrow { putString(KEY_CREDENTIALS, json) }
        _credentials.value = credentials
    }

    override suspend fun clearCredentials(): Unit = synchronized(lock) {
        val old = _credentials.value
        val linked = _linkedHubGroups.value
        val previous = if (old != null && linked.isNotEmpty()) PreviousHubLinks(old.rootFingerprint, linked) else _previousHubLinks.value
        commitOrThrow {
            remove(KEY_CREDENTIALS).remove(KEY_LINKED_GROUPS)
            if (previous != null) {
                putString(KEY_PREVIOUS_ROOT, previous.rootFingerprint)
                putStringSet(KEY_PREVIOUS_GROUPS, previous.groupIds)
            }
        }
        _credentials.value = null
        _linkedHubGroups.value = emptySet()
        _previousHubLinks.value = previous
    }

    override suspend fun linkGroupToHub(groupId: String): Unit = synchronized(lock) {
        val updated = _linkedHubGroups.value + groupId
        _linkedHubGroups.value = updated
        runCatching { commitOrThrow { putStringSet(KEY_LINKED_GROUPS, updated) } }
        Unit
    }

    override suspend fun unlinkGroupFromHub(groupId: String): Unit = synchronized(lock) {
        val updated = _linkedHubGroups.value - groupId
        _linkedHubGroups.value = updated
        runCatching { commitOrThrow { putStringSet(KEY_LINKED_GROUPS, updated) } }
        Unit
    }

    override suspend fun clearPreviousHubLinks(): Unit = synchronized(lock) {
        commitOrThrow { remove(KEY_PREVIOUS_ROOT).remove(KEY_PREVIOUS_GROUPS) }
        _previousHubLinks.value = null
    }

    override suspend fun savePendingUnenrol(credentials: EnrollmentCredentials?): Unit = synchronized(lock) {
        val json = credentials?.let { EnterpriseCredentialCodec.serialize(it) }
        commitOrThrow { if (json == null) remove(KEY_PENDING_UNENROL) else putString(KEY_PENDING_UNENROL, json) }
        _pendingUnenrol.value = credentials
    }

    public companion object {
        private const val PREFS_NAME = "flash_enterprise_trust"
        private const val KEY_CREDENTIALS = "enterprise_credentials_json"
        private const val KEY_LINKED_GROUPS = "enterprise_linked_groups"
        private const val KEY_PREVIOUS_ROOT = "enterprise_previous_links_root"
        private const val KEY_PREVIOUS_GROUPS = "enterprise_previous_links_groups"
        private const val KEY_PENDING_UNENROL = "enterprise_pending_unenrol_json"
        private const val KEY_PENDING_JOINS = "enterprise_pending_joins"
        private const val UNREADABLE = "The saved organisation settings on this phone could not be read. You can enrol again."
        private const val WRITE_FAILED = "Flash could not save the organisation settings on this phone. Free some storage space and try again."
    }
}
