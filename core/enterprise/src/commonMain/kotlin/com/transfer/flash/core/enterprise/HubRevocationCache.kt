@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.enterprise.files.HubFileDisk
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The durable, per-root version floor shared by HTTP sync, LAN gossip and offline ORG policy. */
@FlashInternalApi
public class HubRevocationCache(
    private val directory: String,
    private val credentials: () -> EnrollmentCredentials?,
    private val verifier: EcdsaVerifier,
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
) {
    public enum class Acceptance { APPLIED, STALE, INVALID, OUT_OF_SCOPE, STORAGE_FAILED }

    private data class Loaded(val rootKey: String, val list: RevocationList?)

    private val lock = Mutex()

    @kotlin.concurrent.Volatile
    private var loaded: Loaded? = null

    public fun current(): RevocationList? = loaded?.takeIf { it.rootKey == credentials()?.rootPublicKey }?.list

    /** No offline ORG access while the active root's disk state is loading or unreadable. */
    public fun permitsOrg(): Boolean {
        val creds = credentials() ?: return false
        val snapshot = loaded?.takeIf { it.rootKey == creds.rootPublicKey } ?: return false
        val list = snapshot.list ?: return true
        val cert = creds.deviceCert
        return !list.isRevoked(cert) && creds.delegationChain.none {
            it.delegatePublicKey == cert.issuerPublicKey && list.isDelegationRevoked(it.serial)
        }
    }

    public fun scopeId(): String? = credentials()
        ?.takeIf { it.rootPublicKey == loaded?.rootKey }
        ?.rootFingerprint?.uppercase()

    public suspend fun load(): Boolean = lock.withLock {
        val creds = credentials() ?: return false
        loadLocked(creds) && sameRoot(creds)
    }

    /** HTTP can be slow. Keep the lock free while a colleague might deliver a newer list. */
    public suspend fun refresh(fetch: suspend () -> RevocationList?) {
        val root = credentials()?.rootFingerprint ?: return
        if (!load()) return
        val fresh = runSuspendCatching { fetch() }.getOrNull() ?: return
        accept(fresh, root)
    }

    public suspend fun accept(list: RevocationList, root: String): Acceptance = lock.withLock {
        val creds = credentials()?.takeIf { it.rootFingerprint.equals(root, ignoreCase = true) }
            ?: return@withLock Acceptance.OUT_OF_SCOPE
        val canRead = loadLocked(creds)
        if (!sameRoot(creds)) return@withLock Acceptance.OUT_OF_SCOPE
        if (!canRead) return@withLock Acceptance.STORAGE_FAILED
        val old = loaded?.list
        if (!list.supersedes(old)) return@withLock Acceptance.STALE
        if (old != null && !list.revokedDelegationSerials.containsAll(old.revokedDelegationSerials)) {
            // Delegation cutoffs are permanent on the Hub. A newer document cannot erase a known cutoff.
            return@withLock Acceptance.INVALID
        }
        val acceptedAt = clock()
        val delegation = creds.delegationChain.firstOrNull { it.delegatePublicKey == list.issuerPublicKey }
        if (!valid(list, creds.rootPublicKey, acceptedAt, delegation)) return@withLock Acceptance.INVALID
        val json = HubRestCodec.encode(list)
        if (RelayFrameCodec.utf8Size(json) > HubContract.MAX_REST_BODY_BYTES) return@withLock Acceptance.INVALID
        val encodedDelegation = delegation?.let { HubRestCodec.encode(it) } ?: "-"
        val data = "$acceptedAt\n$encodedDelegation\n$json".encodeToByteArray()
        if (data.size > MAX_FILE_BYTES) return@withLock Acceptance.INVALID
        withContext(Dispatchers.IO + NonCancellable) {
            if (!sameRoot(creds)) return@withContext Acceptance.OUT_OF_SCOPE
            val stored = runSuspendCatching {
                HubFileDisk.ensureDirectory(directory)
                HubFileDisk.replaceAtomically(path(creds), data, sync = true)
            }
            if (stored.isFailure) {
                FlashProbe.emit("org.revocation.storage", "operation" to "write")
                return@withContext Acceptance.STORAGE_FAILED
            }
            loaded = Loaded(creds.rootPublicKey, list)
            FlashProbe.emit("org.revocation.applied", "version" to list.version)
            Acceptance.APPLIED
        }
    }

    private suspend fun loadLocked(creds: EnrollmentCredentials): Boolean {
        if (loaded?.rootKey == creds.rootPublicKey) return true
        val stored = withContext(Dispatchers.IO) {
            runSuspendCatching {
                HubFileDisk.ensureDirectory(directory)
                val file = path(creds)
                if (HubFileDisk.length(file) < 0) return@runSuspendCatching null
                val bytes = checkNotNull(HubFileDisk.readAll(file, MAX_FILE_BYTES))
                val fields = bytes.decodeToString(throwOnInvalidSequence = true).split('\n', limit = 3)
                check(fields.size == 3 && !jsonTooDeep(fields[1]) && !jsonTooDeep(fields[2]))
                val acceptedAt = fields[0].toLong()
                val delegation = fields[1].takeUnless { it == "-" }?.let { HubRestCodec.decode<DelegationCert>(it) }
                val list = HubRestCodec.decode<RevocationList>(fields[2])
                check(valid(list, creds.rootPublicKey, acceptedAt, delegation))
                list
            }
        }
        if (stored.isFailure) {
            FlashProbe.emit("org.revocation.storage", "operation" to "load")
            return false
        }
        if (!sameRoot(creds)) return false
        loaded = Loaded(creds.rootPublicKey, stored.getOrNull())
        return true
    }

    private fun path(creds: EnrollmentCredentials): String = "$directory/${RevocationList.audienceOf(creds.rootPublicKey)}.list"

    private fun sameRoot(creds: EnrollmentCredentials): Boolean = credentials()?.rootPublicKey == creds.rootPublicKey

    private fun valid(list: RevocationList, rootKey: String, at: Long, delegation: DelegationCert?): Boolean =
        list.version > 0 && list.verifyTrusted(rootKey, verifier, at, delegation)

    private companion object {
        const val MAX_FILE_BYTES = HubContract.MAX_REST_BODY_BYTES + 4096
    }
}
