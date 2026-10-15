@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.hubprotocol.certs.GroupRosterStatement
import com.transfer.flash.core.hubprotocol.certs.RosterStatus
import com.transfer.flash.core.hubprotocol.rest.GroupRosterDto
import com.transfer.flash.core.persistence.db.entity.HubGroupRosterEntity
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.text.foldForSearch
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.hubprotocol.certs.ChainStatus
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.DepartmentSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectoryMemberSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
import com.transfer.flash.core.persistence.db.entity.OrgDepartmentEntity
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.random.Random

/**
 * Outcome of a directory synchronization round (Plan B, ADR-134).
 */
@FlashInternalApi
public sealed interface OrgSyncOutcome {
    public data class Success(
        val membersUpserted: Int,
        val membersRemoved: Int,
        val departmentsUpserted: Int,
        val departmentsRemoved: Int,
        val fullResync: Boolean,
        val partialFailures: Int = 0,
    ) : OrgSyncOutcome

    public data object NotEnrolled : OrgSyncOutcome
    public data object Offline : OrgSyncOutcome
    public data object HubUnsupported : OrgSyncOutcome
    public data class RateLimited(val retryAfterSeconds: Long?) : OrgSyncOutcome
    public data class HardSecurityError(val reason: String) : OrgSyncOutcome
    public data class AuthFailed(val statusCode: Int, val message: String) : OrgSyncOutcome
    public data class NetworkError(val message: String) : OrgSyncOutcome
    public data class StorageFailed(val message: String) : OrgSyncOutcome
    public data class ServerError(val statusCode: Int, val message: String) : OrgSyncOutcome
}

/**
 * Client-side synchronization engine for the offline Organization Directory snapshot (Plan B §B1.4).
 *
 * Responsibilities:
 * - Multi-page pagination and atomic staging: all pages are fetched and verified in memory before
 *   any database transaction is committed (satisfying failure atomicity).
 * - Cryptographic verification: verifies each member statement against the response's delegation chain
 *   anchored to the enrolled organisation root public key.
 * - Hard security alert: rejects the entire sync if the delegation chain root does not match the pinned
 *   enrolled root key, locking out automatic retries for 1 hour.
 * - Stale serial protection: never allows an incoming row with an older serial to overwrite a newer one.
 * - Conflict resolution (Rule 8): drops both records and logs a conflict if the same device ID presents
 *   two different public keys in the snapshot.
 * - Clock skew detection: compares server timestamp with local device clock and notifies if |skew| > 5 min,
 *   using bounded timestamp math for the snapshot lease.
 * - Concurrency & single-flight: concurrent sync requests join the active in-flight operation.
 * - Exponential backoff: 30s, 2m, 10m, 1h with ±20% jitter, respecting 429 Retry-After headers.
 */
@FlashInternalApi
public class OrgDirectorySync(
    private val directoryClient: DirectorySyncApi,
    private val orgDirectoryDao: OrgDirectoryDao,
    private val credentialsProvider: () -> EnrollmentCredentials?,
    private val verifier: EcdsaVerifier,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
    private val isOnline: () -> Boolean = { true },
    private val revocationsProvider: () -> RevocationList? = { null },
    private val onClockSkewDetected: ((skewMs: Long) -> Unit)? = null,
    private val onHardSecurityError: ((reason: String) -> Unit)? = null,
) {
    private val singleFlightMutex = Mutex()
    private var inFlightSync: Deferred<OrgSyncOutcome>? = null

    private val _isSyncing = MutableStateFlow(false)
    public val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastOutcome = MutableStateFlow<OrgSyncOutcome?>(null)
    public val lastOutcome: StateFlow<OrgSyncOutcome?> = _lastOutcome.asStateFlow()

    private val _clockSkewDetected = MutableStateFlow(false)
    public val clockSkewDetected: StateFlow<Boolean> = _clockSkewDetected.asStateFlow()

    private val _clockSkewMs = MutableStateFlow(0L)
    public val clockSkewMs: StateFlow<Long> = _clockSkewMs.asStateFlow()

    private val _lastSuccessfulSyncAtMs = MutableStateFlow(0L)
    public val lastSuccessfulSyncAtMs: StateFlow<Long> = _lastSuccessfulSyncAtMs.asStateFlow()

    @kotlin.concurrent.Volatile
    private var failureAttemptCount: Int = 0
    @kotlin.concurrent.Volatile
    private var nextAllowedAttemptMs: Long = 0L
    @kotlin.concurrent.Volatile
    private var hardErrorUntilMs: Long = 0L

    public val isBackedOff: Boolean get() = nextAllowedAttemptMs > clock()


    private data class StagedMember(
        val entity: OrgDirectoryMemberEntity,
        val subjectPublicKey: String,
        val isConflict: Boolean = false,
    )

    /**
     * Executes one synchronization round. Concurrent callers join the in-flight execution.
     *
     * @param force when true, bypasses failure backoff (but never bypasses a hard security lockout).
     */
    public suspend fun syncOnce(force: Boolean = false): OrgSyncOutcome {
        val job = singleFlightMutex.withLock {
            inFlightSync?.let { return@withLock it }
            lateinit var deferred: Deferred<OrgSyncOutcome>
            deferred = scope.async {
                _isSyncing.value = true
                try {
                    val outcome = executeSync(force)
                    _lastOutcome.value = outcome
                    outcome
                } finally {
                    _isSyncing.value = false
                    singleFlightMutex.withLock {
                        if (inFlightSync === deferred) {
                            inFlightSync = null
                        }
                    }
                }
            }
            inFlightSync = deferred
            deferred
        }
        return job.await()
    }

    private suspend fun executeSync(force: Boolean): OrgSyncOutcome {
        val creds = credentialsProvider() ?: return OrgSyncOutcome.NotEnrolled
        val now = clock()

        if (hardErrorUntilMs > now) {
            return OrgSyncOutcome.HardSecurityError("This server is not the one you joined")
        }

        if (!force && nextAllowedAttemptMs > now) {
            val remainingSec = ((nextAllowedAttemptMs - now) / 1000L).coerceAtLeast(1L)
            return OrgSyncOutcome.RateLimited(remainingSec)
        }

        if (!isOnline()) {
            return OrgSyncOutcome.Offline
        }

        val localState = orgDirectoryDao.getSyncState()
        val sinceVersion = localState?.lastSyncedVersion ?: 0L
        val currentEpoch = localState?.directoryEpoch

        FlashProbe.emit(
            "org.sync.start",
            "since" to sinceVersion,
            "force" to force,
        )

        var cursor: String? = null
        var isFirstPage = true
        var isFullResync = false
        var serverVersion = 0L
        var serverEpoch = ""
        var serverTimeMs = 0L
        var responseOfflineGraceDays = DirectorySyncDto.DEFAULT_OFFLINE_GRACE_DAYS
        var totalPartialFailures = 0

        val accumulatedMembers = LinkedHashMap<String, StagedMember>()
        val accumulatedRemovedMemberIds = LinkedHashSet<String>()
        val accumulatedDepartments = LinkedHashMap<String, OrgDepartmentEntity>()
        val accumulatedRemovedDeptIds = LinkedHashSet<String>()
        val accumulatedDelegations = LinkedHashMap<Long, DelegationCert>()
        val offeredRosters = ArrayList<GroupRosterDto>()

        while (true) {
            val pageResult = directoryClient.fetchDirectorySync(
                hubUrl = creds.hubUrl,
                since = sinceVersion,
                epoch = currentEpoch,
                cursor = cursor,
                limit = 500,
            )

            when (pageResult) {
                is DirectorySyncPageResult.HubUnsupported -> {
                    FlashProbe.emit("org.sync.fail", "reason" to "hub_unsupported")
                    return OrgSyncOutcome.HubUnsupported
                }
                is DirectorySyncPageResult.RateLimited -> {
                    recordFailure("rate_limited", pageResult.retryAfterSeconds)
                    FlashProbe.emit("org.sync.fail", "reason" to "rate_limited")
                    return OrgSyncOutcome.RateLimited(pageResult.retryAfterSeconds)
                }
                is DirectorySyncPageResult.AuthRefused -> {
                    recordFailure("auth_refused")
                    FlashProbe.emit("org.sync.fail", "reason" to "auth_refused", "status" to pageResult.statusCode)
                    return OrgSyncOutcome.AuthFailed(pageResult.statusCode, pageResult.message)
                }
                is DirectorySyncPageResult.Unreachable -> {
                    recordFailure("unreachable")
                    FlashProbe.emit("org.sync.fail", "reason" to "unreachable")
                    return OrgSyncOutcome.NetworkError(pageResult.message)
                }
                is DirectorySyncPageResult.ServerError -> {
                    recordFailure("server_error")
                    FlashProbe.emit("org.sync.fail", "reason" to "server_error", "status" to pageResult.statusCode)
                    return OrgSyncOutcome.ServerError(pageResult.statusCode, pageResult.message)
                }
                is DirectorySyncPageResult.Success -> {
                    val dto = pageResult.dto
                    if (isFirstPage) {
                        isFirstPage = false
                        isFullResync = dto.fullResync
                        serverVersion = dto.version
                        serverEpoch = dto.directoryEpoch
                        serverTimeMs = dto.serverTimeMs
                        responseOfflineGraceDays = dto.offlineGraceDays

                        val skew = serverTimeMs - now
                        if (abs(skew) > MAX_ALLOWED_CLOCK_SKEW_MS) {
                            _clockSkewDetected.value = true
                            _clockSkewMs.value = skew
                            onClockSkewDetected?.invoke(skew)
                        } else {
                            _clockSkewDetected.value = false
                            _clockSkewMs.value = 0L
                        }
                    }

                    // Accumulate delegation certs, deduplicating by serial across pages
                    for (cert in dto.delegationChain) {
                        accumulatedDelegations[cert.serial] = cert
                    }

                    // Hard security check on delegation chain:
                    // If delegations were sent, verify that none claim a different root key than enrolled
                    if (dto.delegationChain.isNotEmpty()) {
                        val mismatch = dto.delegationChain.any { it.rootPublicKey != creds.rootPublicKey }
                        val anyValid = dto.delegationChain.any { it.verifySignature(verifier) }
                        if (mismatch || !anyValid) {
                            triggerHardSecurityError("This server is not the one you joined")
                            FlashProbe.emit("org.sync.fail", "reason" to "hard_security_error")
                            return OrgSyncOutcome.HardSecurityError("This server is not the one you joined")
                        }
                    }

                    offeredRosters.addAll(dto.groupRosters)

                    // Process departments
                    for (dept in dto.departments) {
                        accumulatedDepartments[dept.id] = OrgDepartmentEntity(
                            id = dept.id,
                            name = dept.name,
                            memberCount = dept.memberCount,
                            headDeviceIdsJson = HubRestCodec.encode(dept.headDeviceIds),
                            hidden = dept.hidden,
                        )
                    }
                    for (deptId in dto.removedDepartmentIds) {
                        accumulatedRemovedDeptIds.add(deptId)
                        accumulatedDepartments.remove(deptId)
                    }

                    // Process members
                    val preVerifiedDelegations = accumulatedDelegations.values.filter {
                        it.rootPublicKey == creds.rootPublicKey && it.verifySignature(verifier)
                    }.toSet()

                    var pageVerifiedCount = 0
                    for (member in dto.members) {
                        val statement = member.statement
                        if (statement.deviceId != member.deviceId) {
                            totalPartialFailures++
                            continue
                        }

                        val (verifyStatus, matchingDelegation) = statement.verifyWithDelegation(
                            delegationChain = accumulatedDelegations.values.toList(),
                            rootPublicKey = creds.rootPublicKey,
                            verifier = verifier,
                            nowMs = now,
                            revocations = revocationsProvider(),
                            preVerifiedDelegations = preVerifiedDelegations,
                        )

                        if (verifyStatus != ChainStatus.OK || matchingDelegation == null) {
                            totalPartialFailures++
                            continue
                        }



                        pageVerifiedCount++
                        val delegationSerial = matchingDelegation.serial

                        val spkiBytes = statement.subjectPublicKeyBytes
                        val fingerprintHex = FlashFingerprint.fingerprint(spkiBytes).joinToString("") { byte ->
                            (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                        }
                        val folded = foldForSearch(member.displayName.ifBlank { member.deviceId })
                        val statementBytes = HubRestCodec.encode(statement).encodeToByteArray()

                        val entity = OrgDirectoryMemberEntity(
                            deviceId = member.deviceId,
                            displayName = member.displayName.ifBlank { member.deviceId },
                            displayNameFolded = folded,
                            departmentId = member.departmentId,
                            role = member.role,
                            subjectPublicKeyFingerprint = fingerprintHex,
                            statementBytes = statementBytes,
                            statementSerial = statement.statementSerial,
                            statementIssuedAtMs = statement.issuedAtMs,
                            statementDelegationSerial = delegationSerial,
                            lastSyncedAtMs = now,
                        )

                        val existing = accumulatedMembers[member.deviceId]
                        if (existing != null) {
                            if (existing.isConflict) {
                                // Rule 8 is sticky: K1, K2, K1 must not clear the flag (verifier defect 6).
                                continue
                            }
                            if (existing.subjectPublicKey != statement.subjectPublicKey) {
                                // Rule 8: Same deviceId, two different keys in snapshot -> drop both, mark conflict
                                accumulatedMembers[member.deviceId] = existing.copy(isConflict = true)
                            } else {
                                if (statement.statementSerial >= existing.entity.statementSerial) {
                                    accumulatedMembers[member.deviceId] = StagedMember(entity, statement.subjectPublicKey)
                                }
                            }
                        } else {
                            accumulatedMembers[member.deviceId] = StagedMember(entity, statement.subjectPublicKey)
                        }
                    }

                    // If members were present on this page but none verified, fail the sync (never mark success when whole page failed)
                    if (dto.members.isNotEmpty() && pageVerifiedCount == 0) {
                        recordFailure()
                        FlashProbe.emit("org.sync.fail", "reason" to "all_members_invalid")
                        return OrgSyncOutcome.ServerError(400, "All member statements on page failed verification")
                    }

                    for (removedId in dto.removedDeviceIds) {
                        accumulatedRemovedMemberIds.add(removedId)
                        accumulatedMembers.remove(removedId)
                    }

                    cursor = dto.nextCursor
                    if (cursor.isNullOrBlank()) {
                        break
                    }
                }
            }
        }

        // All pages fetched & verified successfully in memory -> Atomic apply to database
        val membersToUpsert = accumulatedMembers.values.filterNot { it.isConflict }.map { it.entity }
        val conflictedIds = accumulatedMembers.filterValues { it.isConflict }.keys
        val clampedGraceDays = responseOfflineGraceDays.coerceIn(
            DirectorySyncDto.MIN_OFFLINE_GRACE_DAYS,
            DirectorySyncDto.MAX_OFFLINE_GRACE_DAYS,
        )

        val successMs = if (abs(serverTimeMs - now) > MAX_ALLOWED_CLOCK_SKEW_MS) {
            minOf(now, serverTimeMs + MAX_ALLOWED_CLOCK_SKEW_MS)
        } else {
            now
        }

        val newState = OrgSyncStateEntity(
            id = 1,
            orgRootFingerprint = creds.rootFingerprint,
            directoryEpoch = serverEpoch,
            lastSyncedVersion = serverVersion,
            offlineGraceDays = clampedGraceDays,
            lastSyncSuccessMs = successMs,
            lastSyncAttemptMs = now,
            lastErrorKind = null,
        )

        val rosters = try {
            verifiedRosters(offeredRosters, accumulatedDelegations.values.toList(), creds, now, authoritative = isFullResync)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Failure atomicity (verifier B4 defect 21): a decode or storage error while checking the rosters keeps what is
            // stored; it must never wipe every group roster.
            runCatching { orgDirectoryDao.allGroupRosters() }.getOrDefault(emptyList())
        }

        try {
            orgDirectoryDao.applySyncBatchWithRosters(
                fullResync = isFullResync,
                membersToUpsert = membersToUpsert,
                // A conflicted device is dropped from the snapshot, so a row stored by an earlier sync goes too.
                memberIdsToDelete = (accumulatedRemovedMemberIds + conflictedIds).toList(),
                departmentsToUpsert = accumulatedDepartments.values.toList(),
                departmentIdsToDelete = accumulatedRemovedDeptIds.toList(),
                newState = newState,
                rosters = rosters,
            )
            FlashProbe.emit(
                "org.snapshot.applied",
                "org" to creds.rootFingerprint,
                "version" to serverVersion,
                "membersCount" to membersToUpsert.size,
                "revocationsCount" to accumulatedRemovedMemberIds.size,
                "signer" to "hub-root",
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            recordFailure()
            FlashProbe.emit("org.sync.fail", "reason" to "storage_failed")
            return OrgSyncOutcome.StorageFailed(e.message ?: "Database write failed")
        }

        resetBackoff()
        _lastSuccessfulSyncAtMs.value = successMs

        FlashProbe.emit(
            "org.sync.ok",
            "version" to serverVersion,
            "members" to membersToUpsert.size,
            "fullResync" to isFullResync,
        )

        return OrgSyncOutcome.Success(
            membersUpserted = membersToUpsert.size,
            membersRemoved = accumulatedRemovedMemberIds.size,
            departmentsUpserted = accumulatedDepartments.size,
            departmentsRemoved = accumulatedRemovedDeptIds.size,
            fullResync = isFullResync,
            partialFailures = totalPartialFailures,
        )
    }

    /**
     * Every roster offered by the hub, checked here on its own (root, chain, signature, window, revocations; plan section 10),
     * never trusting the hub's say-so. A group's stored statement is replaced only by one with a version at least as high
     * (a lower version never replaces a higher one), and a group the hub no longer lists is dropped with this commit.
     */
    private suspend fun verifiedRosters(
        offered: List<GroupRosterDto>,
        delegations: List<DelegationCert>,
        creds: EnrollmentCredentials,
        now: Long,
        authoritative: Boolean = false,
    ): List<HubGroupRosterEntity> {
        if (offered.isEmpty()) return emptyList()
        val stored = orgDirectoryDao.allGroupRosters().associateBy { it.groupId }
        val preVerified = delegations.filter { it.rootPublicKey == creds.rootPublicKey && it.verifySignature(verifier) }.toSet()
        val best = LinkedHashMap<String, Pair<GroupRosterStatement, String>>()
        for (dto in offered) {
            val statement = try {
                HubRestCodec.decode<GroupRosterStatement>(dto.statement)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                continue
            }
            if (statement.groupId != dto.groupId) continue
            val status = statement.verify(
                delegationChain = delegations,
                rootPublicKey = creds.rootPublicKey,
                verifier = verifier,
                nowMs = now,
                revocations = revocationsProvider(),
                preVerifiedDelegations = preVerified,
            )
            if (status != RosterStatus.OK) {
                FlashProbe.emit("org.roster.rejected", "group" to dto.groupId, "reason" to status.name)
                continue
            }
            val current = best[statement.groupId]
            if (current == null || statement.version > current.first.version) best[statement.groupId] = statement to dto.statement
        }
        val chainJson = HubRestCodec.encode(preVerified.toList().sortedBy { it.serial })
        return best.values.mapNotNull { (statement, json) ->
            val old = stored[statement.groupId]
            // After a full resync (epoch reset, restored hub database) the hub's versions start over, so its statement wins
            // (verifier B4 defect 19); otherwise a lower version never replaces a higher one.
            if (!authoritative && old != null && old.version > statement.version) old
            else HubGroupRosterEntity(statement.groupId, statement.version, json, chainJson, statement.validUntilMs)
        }
    }

    private fun triggerHardSecurityError(reason: String) {
        hardErrorUntilMs = clock() + HARD_SECURITY_LOCKOUT_MS
        onHardSecurityError?.invoke(reason)
    }

    private suspend fun recordFailure(reason: String? = null, retryAfterSeconds: Long? = null) {
        failureAttemptCount++
        val baseSeconds = when (failureAttemptCount) {
            1 -> 30L
            2 -> 120L
            3 -> 600L
            else -> 3600L
        }
        val effectiveSeconds = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            maxOf(baseSeconds, retryAfterSeconds)
        } else {
            baseSeconds
        }
        val jitter = 0.8 + (Random.nextDouble() * 0.4)
        val backoffMs = (effectiveSeconds * 1000.0 * jitter).toLong()
        nextAllowedAttemptMs = clock() + backoffMs
        if (reason != null) {
            runCatching {
                orgDirectoryDao.getSyncState()?.let { current ->
                    orgDirectoryDao.setSyncState(
                        current.copy(
                            lastSyncAttemptMs = clock(),
                            lastErrorKind = reason,
                        )
                    )
                }
            }
        }
    }

    private fun resetBackoff() {
        failureAttemptCount = 0
        nextAllowedAttemptMs = 0L
    }

    /**
     * Clears all cached organization directory data, reset backoff, and wipes lease state.
     */
    public suspend fun clearSnapshot() {
        orgDirectoryDao.clearAll()
        resetBackoff()
        hardErrorUntilMs = 0L
        _lastSuccessfulSyncAtMs.value = 0L
        _clockSkewDetected.value = false
        _clockSkewMs.value = 0L
        _lastOutcome.value = null
    }

    public companion object {
        public const val MAX_ALLOWED_CLOCK_SKEW_MS: Long = 5L * 60 * 1000 // 5 minutes
        public const val HARD_SECURITY_LOCKOUT_MS: Long = 60L * 60 * 1000 // 1 hour
    }
}
