@file:OptIn(
    com.transfer.flash.core.common.annotation.FlashInternalApi::class,
    kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class,
)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.model.FlashDeviceId
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
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : OrgPeerResolver {

    private data class SnapshotPeer(
        val deviceId: String,
        val displayName: String,
        val departmentId: String,
        val role: String,
        val keyFingerprint: String,
    )

    private val lock = Any()
    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var snapshotIndex: Map<String, SnapshotPeer> = emptyMap()
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
        val members = dao.listAllMembers()

        val newIndex = HashMap<String, SnapshotPeer>(members.size)
        for (m in members) {
            newIndex[m.deviceId] = SnapshotPeer(
                deviceId = m.deviceId,
                displayName = m.displayName,
                departmentId = m.departmentId,
                role = m.role,
                keyFingerprint = m.subjectPublicKeyFingerprint,
            )
        }

        synchronized(lock) {
            if (currentGen == generation) {
                snapshotIndex = newIndex
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
            lastSyncSuccessMs = 0L
            graceDays = DEFAULT_GRACE_DAYS
            val oldLease = _lease.value
            _lease.value = OrgLease.None
            if (oldLease is OrgLease.Valid) {
                FlashProbe.emit("org.lease.expired", "reason" to "CLEARED")
            }
        }
    }

    override fun isKnownColleague(deviceId: String): Boolean {
        if (!isEnrolled()) return false
        val now = clock()
        val currentLease = synchronized(lock) {
            calculateLeaseLocked(now)
        }
        return currentLease is OrgLease.Valid && snapshotIndex.containsKey(deviceId)
    }

    override fun searchColleagueIds(query: String, limit: Int): Set<String> {
        if (!isEnrolled()) return emptySet()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptySet()
        val now = clock()
        val currentLease = synchronized(lock) {
            calculateLeaseLocked(now)
        }
        if (currentLease !is OrgLease.Valid) return emptySet()
        return snapshotIndex.values
            .filter { peer ->
                peer.displayName.lowercase().contains(q) ||
                    peer.departmentId.lowercase().contains(q) ||
                    peer.role.lowercase().contains(q)
            }
            .take(limit)
            .map { it.deviceId }
            .toSet()
    }

    override fun displayName(deviceId: String): String? {
        if (!isEnrolled()) return null
        return snapshotIndex[deviceId]?.displayName
    }

    override fun resolve(deviceId: String): OrgPeer? {
        if (!isEnrolled()) return null
        val now = clock()
        val currentLease = synchronized(lock) {
            calculateLeaseLocked(now)
        }
        if (currentLease !is OrgLease.Valid) {
            return null
        }

        val peer = snapshotIndex[deviceId] ?: return null
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

    private fun calculateLeaseLocked(now: Long): OrgLease {
        if (!isEnrolled()) return OrgLease.None
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
            if (oldLease is OrgLease.Valid && newLease is OrgLease.Expired) {
                FlashProbe.emit("org.lease.expired", "reason" to newLease.reason.name)
            }
        }
        return newLease
    }
}
