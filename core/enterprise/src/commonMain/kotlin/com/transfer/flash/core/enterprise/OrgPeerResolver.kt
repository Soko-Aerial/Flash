@file:OptIn(
    com.transfer.flash.core.common.annotation.FlashInternalApi::class,
    kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class,
)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.security.trust.PinSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Reason an organization offline lease has expired.
 */
public enum class ExpiredReason {
    /** The offline grace period has elapsed. */
    TIME,

    /** The device clock jumped backwards beyond skew tolerance (potential clock rollback attack). */
    CLOCK,
}

/**
 * State of offline organization trust lease (Plan B, ADR-134).
 */
public sealed class OrgLease {
    /** Lease is active until [untilMs] epoch timestamp. */
    public data class Valid(val untilMs: Long) : OrgLease()

    /** Lease is expired since [sinceMs] due to [reason]. */
    public data class Expired(val sinceMs: Long, val reason: ExpiredReason) : OrgLease()

    /** No organization sync has ever completed successfully. */
    public data object None : OrgLease()
}

/**
 * Resolved organization peer metadata and live session cryptographic binding verdict.
 */
public data class OrgPeer(
    val deviceId: String,
    val displayName: String,
    val departmentId: String,
    val role: String,
    val keyFingerprint: String,
    /** True when the live session's authenticated TLS certificate public-key SPKI fingerprint matches the statement. */
    val bindingOk: Boolean,
)

/**
 * Cheap, in-memory, O(1) resolver for organization peer membership and lease validity (Plan B, ADR-134).
 * Frame ingestion and gate checking must never block on SQLite database queries.
 */
public interface OrgPeerResolver {
    /**
     * Synchronous-safe lookup of an in-memory index kept in sync with the directory snapshot.
     * Returns null if the device is not in the snapshot or if the lease is expired.
     */
    public fun resolve(deviceId: String): OrgPeer?

    /**
     * Evaluates whether [deviceId] is an active, verified organization peer under the current lease.
     */
    public fun isOrgPeer(deviceId: String): Boolean = resolve(deviceId)?.bindingOk == true

    /**
     * Checks if [deviceId] exists in the snapshot regardless of whether live TLS key binding is complete.
     */
    public fun isKnownColleague(deviceId: String): Boolean

    /**
     * Returns the cached friendly display name for [deviceId] from the snapshot, or null if unknown.
     */
    public fun displayName(deviceId: String): String? = null

    /** A cached device-id or delegation revocation, independent of the peer's manual trust pin. */
    public fun isRevokedColleague(deviceId: String): Boolean = false

    /**
     * Searches known colleague device IDs in the snapshot by query string (case-insensitive name/dept/role).
     * Returns empty set if query is blank or if the lease is expired.
     */
    public fun searchColleagueIds(query: String, limit: Int = 20): Set<String> = emptySet()

    /**
     * Current state of the organization lease.
     */
    public val lease: StateFlow<OrgLease>

    /**
     * Whether the local device's identity key has diverged from the certified statement in the snapshot.
     */
    public val identityMismatch: StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Reloads in-memory index from the persisted directory snapshot.
     */
    public suspend fun refreshFromSnapshot()

    /**
     * Clears in-memory index immediately on un-enrollment or organization wipe.
     */
    public suspend fun clear()
}

/**
 * Production implementation of [OrgPeerResolver] backed by [OrgDirectoryDao] and [FlashTrustStore].
 */
public class RealOrgPeerResolver(
    private val dao: OrgDirectoryDao,
    private val trustStore: FlashTrustStore,
    private val isEnrolled: () -> Boolean = { true },
    private val localDeviceId: String? = null,
    private val ownFingerprintProvider: (() -> String?)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val revocations: () -> RevocationList? = { null },
    private val enrolledRoot: (() -> String?)? = null,
    private val permitsOrg: () -> Boolean = { true },
) : OrgPeerResolver {

    private val _identityMismatch = MutableStateFlow(false)
    override val identityMismatch: StateFlow<Boolean> = _identityMismatch.asStateFlow()

    private data class SnapshotPeer(
        val deviceId: String,
        val displayName: String,
        val departmentId: String,
        val role: String,
        val keyFingerprint: String,
        val delegationSerial: Long,
    )

    private val lock = Any()
    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var snapshotIndex: Map<String, SnapshotPeer> = emptyMap()
    private var snapshotRoot: String? = null
    @Volatile
    private var lastSyncSuccessMs: Long = 0L
    @Volatile
    private var graceDays: Int = 30

    private val _lease = MutableStateFlow<OrgLease>(OrgLease.None)
    override val lease: StateFlow<OrgLease> = object : StateFlow<OrgLease> {
        override val replayCache: List<OrgLease> get() = listOf(value)
        override val value: OrgLease
            get() = synchronized(lock) { calculateLeaseLocked(clock()) }

        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<OrgLease>): Nothing {
            _lease.collect(collector)
        }
    }

    public companion object {
        public const val SKEW_TOLERANCE_MS: Long = 5 * 60 * 1000L // 5 minutes
        public const val DEFAULT_GRACE_DAYS: Int = 30
        public const val TAG: String = "ORG"
    }

    override suspend fun refreshFromSnapshot(): Unit = withContext(dispatcher) {
        val currentGen = synchronized(lock) { ++generation }
        val syncState = dao.getSyncState()
        // Use projection query to omit large statementBytes BLOBs (OPT-O02-03, OPT-O06-08)
        val members = dao.listMemberSummaries()

        val newIndex = HashMap<String, SnapshotPeer>(members.size)
        for (m in members) {
            newIndex[m.deviceId] = SnapshotPeer(
                deviceId = m.deviceId,
                displayName = m.displayName,
                departmentId = m.departmentId,
                role = m.role,
                keyFingerprint = m.subjectPublicKeyFingerprint,
                delegationSerial = m.statementDelegationSerial,
            )
        }

        val ownPeer = if (localDeviceId != null) newIndex[localDeviceId] else null
        val mismatch = if (ownPeer != null && ownFingerprintProvider != null) {
            val ownFp = ownFingerprintProvider.invoke()
            ownFp != null && !ownPeer.keyFingerprint.equals(ownFp, ignoreCase = true)
        } else {
            false
        }

        synchronized(lock) {
            if (currentGen == generation) {
                snapshotIndex = newIndex
                snapshotRoot = syncState?.orgRootFingerprint
                _identityMismatch.value = mismatch
                if (syncState != null) {
                    lastSyncSuccessMs = syncState.lastSyncSuccessMs
                    graceDays = syncState.offlineGraceDays.coerceIn(1, 365)
                } else {
                    lastSyncSuccessMs = 0L
                    graceDays = DEFAULT_GRACE_DAYS
                }
                updateLeaseLocked(clock())
            }
        }
    }

    override suspend fun clear(): Unit = withContext(dispatcher) {
        synchronized(lock) {
            ++generation
            snapshotIndex = emptyMap()
            snapshotRoot = null
            lastSyncSuccessMs = 0L
            graceDays = DEFAULT_GRACE_DAYS
            _identityMismatch.value = false
            val oldLease = _lease.value
            _lease.value = OrgLease.None
            if (oldLease !is OrgLease.None) {
                FlashProbe.emit("org.lease.state", "state" to "NONE")
            }
            if (oldLease is OrgLease.Valid) {
                FlashProbe.emit("org.lease.expired", "reason" to "CLEARED")
            }
        }
    }

    override fun isKnownColleague(deviceId: String): Boolean {
        return activePeer(deviceId) != null
    }

    override fun searchColleagueIds(query: String, limit: Int): Set<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || !isEnrolled() || _identityMismatch.value) return emptySet()
        val index = synchronized(lock) {
            if (!hasActiveSnapshotLocked()) return emptySet()
            snapshotIndex
        }
        val list = revocations()
        return index.values.asSequence()
            .filter { peer ->
                !isRevoked(peer, list) && (peer.displayName.lowercase().contains(q) ||
                    peer.departmentId.lowercase().contains(q) ||
                    peer.role.lowercase().contains(q))
            }
            .take(limit)
            .map { it.deviceId }
            .toSet()
    }

    override fun displayName(deviceId: String): String? {
        if (!isEnrolled() || !permitsOrg()) return null
        return synchronized(lock) {
            if (!hasSnapshotRootLocked()) return null
            snapshotIndex[deviceId]?.takeUnless { isRevoked(it, revocations()) }?.displayName
        }
    }

    override fun isRevokedColleague(deviceId: String): Boolean = synchronized(lock) {
        val list = revocations() ?: return false
        if (list.revokesDevice(deviceId)) return true
        val peer = snapshotIndex[deviceId] ?: return false
        hasSnapshotRootLocked() && list.isDelegationRevoked(peer.delegationSerial)
    }

    /** Revocation messages may cross a cutoff using the previously certified TLS binding. This grants no ORG access. */
    public fun authenticatesRevocationPeer(deviceId: String, root: String, tlsFingerprint: String): Boolean = synchronized(lock) {
        !_identityMismatch.value && snapshotRoot.equals(root, ignoreCase = true) &&
            hasSnapshotRootLocked() && calculateLeaseLocked(clock(), requireOrg = false) is OrgLease.Valid &&
            snapshotIndex[deviceId]?.keyFingerprint.equals(tlsFingerprint, ignoreCase = true)
    }

    private fun activePeer(deviceId: String): SnapshotPeer? {
        if (!isEnrolled() || _identityMismatch.value) return null
        return synchronized(lock) {
            if (!hasActiveSnapshotLocked()) return null
            snapshotIndex[deviceId]?.takeUnless { isRevoked(it, revocations()) }
        }
    }

    private fun hasActiveSnapshotLocked(): Boolean = hasSnapshotRootLocked() && calculateLeaseLocked(clock()) is OrgLease.Valid

    private fun hasSnapshotRootLocked(): Boolean =
        enrolledRoot == null || enrolledRoot.invoke()?.equals(snapshotRoot, ignoreCase = true) == true

    /** Publish a changed lease to observers when the local credential is revoked by a new list. */
    public fun onRevocationsChanged(): Unit = synchronized(lock) {
        updateLeaseLocked(clock())
    }

    private fun isRevoked(peer: SnapshotPeer, list: RevocationList?): Boolean =
        list != null && (list.revokesDevice(peer.deviceId) || list.isDelegationRevoked(peer.delegationSerial))

    override fun resolve(deviceId: String): OrgPeer? {
        val peer = activePeer(deviceId) ?: return null
        val storedPin = trustStore.getPin(deviceId)

        val bindingOk = if (storedPin == null) {
            false
        } else if (storedPin.equals(peer.keyFingerprint, ignoreCase = true)) {
            true
        } else {
            // Mismatch: inspect pin source per Plan B Rule 3.2
            val source = trustStore.pinSource(FlashDeviceId(deviceId))
            if (source == PinSource.TOFU) {
                // Rule 2: replace TOFU pin with verified statement fingerprint
                trustStore.savePin(deviceId, peer.keyFingerprint)
                FlashLog.i(TAG, "Replaced TOFU pin for peer ${FlashProbe.short(deviceId)} with statement fingerprint")
                FlashProbe.emit("org.pin.replaced", "peer" to FlashProbe.short(deviceId))
                // Rule 1: Mismatch -> not ORG for the live session that proved the mismatched key.
                // TrustStore pin is now replaced so the attacker's next handshake fails and the real colleague connects.
                false
            } else {
                // Rule 3: PAIRED or VOUCHED pins are never replaced
                false
            }
        }

        return OrgPeer(
            deviceId = peer.deviceId,
            displayName = peer.displayName,
            departmentId = peer.departmentId,
            role = peer.role,
            keyFingerprint = peer.keyFingerprint,
            bindingOk = bindingOk,
        )
    }

    private fun calculateLeaseLocked(now: Long, requireOrg: Boolean = true): OrgLease {
        if (!isEnrolled() || _identityMismatch.value || !hasSnapshotRootLocked() || (requireOrg && !permitsOrg())) return OrgLease.None
        if (lastSyncSuccessMs <= 0L) {
            return OrgLease.None
        }
        val graceMs = graceDays * 86_400_000L
        return when {
            now < lastSyncSuccessMs - SKEW_TOLERANCE_MS -> {
                OrgLease.Expired(sinceMs = now, reason = ExpiredReason.CLOCK)
            }
            now > lastSyncSuccessMs + graceMs -> {
                OrgLease.Expired(sinceMs = now, reason = ExpiredReason.TIME)
            }
            else -> {
                OrgLease.Valid(untilMs = lastSyncSuccessMs + graceMs)
            }
        }
    }

    private fun updateLeaseLocked(now: Long): OrgLease {
        val newLease = calculateLeaseLocked(now)
        val oldLease = _lease.value
        if (oldLease != newLease) {
            _lease.value = newLease
            val stateName = when (newLease) {
                is OrgLease.Valid -> "VALID"
                is OrgLease.Expired -> if (newLease.reason == ExpiredReason.CLOCK) "CLOCK_WRONG" else "EXPIRED"
                is OrgLease.None -> "NONE"
            }
            FlashProbe.emit("org.lease.state", "state" to stateName)
            if (oldLease is OrgLease.Valid && newLease is OrgLease.Expired) {
                FlashProbe.emit("org.lease.expired", "reason" to newLease.reason.name)
            }
        }
        return newLease
    }
}
