package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.PendingJoinsCodec
import com.transfer.flash.core.security.enterprise.PreviousHubLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Desktop enterprise trust store: one properties file, rewritten as a whole.
 *
 * CLI-12: every write goes to `enterprise_trust.properties.tmp`, is flushed and fsync-ed, and is then moved over the
 * real file with an atomic move, so a crash or a full disk leaves either the old file or the new one, never a torn
 * one. A failed write throws for credential changes (nothing in memory changes either) and is reported through
 * [storageError]; the old code swallowed every failure.
 */
@FlashInternalApi
public class JvmFileEnterpriseTrustStore(
    private val stateDir: File,
) : EnterpriseTrustStore {
    private val file = File(stateDir, FILE_NAME)
    private val tmpFile = File(stateDir, "$FILE_NAME.tmp")
    private val lock = Any()

    private val _credentials = MutableStateFlow<EnrollmentCredentials?>(null)
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

    /** What a write would store; built from the CURRENT state plus the one change being made. */
    private data class Snapshot(
        val credentials: EnrollmentCredentials?,
        val linked: Set<String>,
        val previous: PreviousHubLinks?,
        val pending: EnrollmentCredentials?,
        val pendingJoins: Map<String, String> = emptyMap(),
    )

    init {
        stateDir.mkdirs()
        load()
    }

    private fun current() = Snapshot(_credentials.value, _linkedHubGroups.value, _previousHubLinks.value, _pendingUnenrol.value, _pendingJoins.value)

    private fun load() = synchronized(lock) {
        if (!file.isFile) return@synchronized
        try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val json = props.getProperty(KEY_CREDENTIALS)
            if (!json.isNullOrBlank()) {
                val parsed = EnterpriseCredentialCodec.parse(json)
                _credentials.value = parsed
                if (parsed == null) _storageError.value = UNREADABLE
            }
            _linkedHubGroups.value = props.getProperty(KEY_LINKED).orEmpty().split(",").filter { it.isNotBlank() }.toSet()
            val prevRoot = props.getProperty(KEY_PREVIOUS_ROOT).orEmpty()
            val prevGroups = props.getProperty(KEY_PREVIOUS_GROUPS).orEmpty().split(",").filter { it.isNotBlank() }.toSet()
            _previousHubLinks.value = if (prevRoot.isNotBlank() && prevGroups.isNotEmpty()) PreviousHubLinks(prevRoot, prevGroups) else null
            _pendingJoins.value = PendingJoinsCodec.decode(props.getProperty(KEY_PENDING_JOINS))
            props.getProperty(KEY_PENDING_UNENROL)?.takeIf { it.isNotBlank() }?.let {
                _pendingUnenrol.value = EnterpriseCredentialCodec.parse(it)
            }
        } catch (_: Throwable) {
            _storageError.value = UNREADABLE
        }
    }

    /** Writes [snapshot] atomically. Throws [IOException] on any failure, after recording [storageError]. */
    private fun write(snapshot: Snapshot) {
        try {
            val props = Properties()
            snapshot.credentials?.let { props.setProperty(KEY_CREDENTIALS, EnterpriseCredentialCodec.serialize(it)) }
            props.setProperty(KEY_LINKED, snapshot.linked.joinToString(","))
            snapshot.previous?.let {
                props.setProperty(KEY_PREVIOUS_ROOT, it.rootFingerprint)
                props.setProperty(KEY_PREVIOUS_GROUPS, it.groupIds.joinToString(","))
            }
            if (snapshot.pendingJoins.isNotEmpty()) props.setProperty(KEY_PENDING_JOINS, PendingJoinsCodec.encode(snapshot.pendingJoins))
            snapshot.pending?.let { props.setProperty(KEY_PENDING_UNENROL, EnterpriseCredentialCodec.serialize(it)) }

            stateDir.mkdirs()
            FileOutputStream(tmpFile).use { out ->
                props.store(out, "Flash Enterprise Trust Store")
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            _storageError.value = null
        } catch (e: Throwable) {
            runCatching { tmpFile.delete() }
            _storageError.value = WRITE_FAILED
            throw if (e is IOException) e else IOException(WRITE_FAILED, e)
        }
    }

    override suspend fun saveCredentials(credentials: EnrollmentCredentials): Unit = synchronized(lock) {
        write(current().copy(credentials = credentials))
        _credentials.value = credentials
    }

    override suspend fun clearCredentials(): Unit = synchronized(lock) {
        val old = _credentials.value
        val linked = _linkedHubGroups.value
        val previous = if (old != null && linked.isNotEmpty()) PreviousHubLinks(old.rootFingerprint, linked) else _previousHubLinks.value
        write(current().copy(credentials = null, linked = emptySet(), previous = previous))
        _credentials.value = null
        _linkedHubGroups.value = emptySet()
        _previousHubLinks.value = previous
    }

    override suspend fun linkGroupToHub(groupId: String): Unit = synchronized(lock) {
        val updated = _linkedHubGroups.value + groupId
        _linkedHubGroups.value = updated
        runCatching { write(current()) }
        Unit
    }

    override suspend fun unlinkGroupFromHub(groupId: String): Unit = synchronized(lock) {
        val updated = _linkedHubGroups.value - groupId
        _linkedHubGroups.value = updated
        runCatching { write(current()) }
        Unit
    }

    override suspend fun clearPreviousHubLinks(): Unit = synchronized(lock) {
        write(current().copy(previous = null))
        _previousHubLinks.value = null
    }

    override suspend fun savePendingUnenrol(credentials: EnrollmentCredentials?): Unit = synchronized(lock) {
        write(current().copy(pending = credentials))
        _pendingUnenrol.value = credentials
    }

    override suspend fun setPendingJoin(groupId: String, name: String?): Unit = synchronized(lock) {
        _pendingJoins.value = if (name == null) _pendingJoins.value - groupId else _pendingJoins.value + (groupId to name)
        runCatching { write(current()) }
        Unit
    }

    public companion object {
        public const val FILE_NAME: String = "enterprise_trust.properties"
        private const val KEY_CREDENTIALS = "credentials"
        private const val KEY_LINKED = "linked_groups"
        private const val KEY_PREVIOUS_ROOT = "previous_links_root"
        private const val KEY_PREVIOUS_GROUPS = "previous_links_groups"
        private const val KEY_PENDING_UNENROL = "pending_unenrol"
        private const val KEY_PENDING_JOINS = "pending_joins"
        private const val UNREADABLE = "The saved organisation settings on this computer could not be read. You can enrol again."
        private const val WRITE_FAILED = "Flash could not save the organisation settings on this computer. Check the free disk space and try again."
    }
}
