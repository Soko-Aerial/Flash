package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.time.SystemTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one caller of the organisation directory sync (Plan B §B1.4, "Runs:"). Without it [OrgDirectorySync] and the
 * snapshot-backed [OrgPeerResolver] never get any data and the whole ORG tier stays dormant.
 *
 * It decides WHEN to sync and what to do with the snapshot when the enrolment changes; the sync itself, the verification
 * and the storage stay in [OrgDirectorySync].
 *
 * - **Enrolled** (first value, a new enrolment): sync at once, then refresh the in-memory resolver from the stored snapshot.
 * - **Enrolment ends or changes organisation** (credentials become null, or the root fingerprint differs from the one the
 *   snapshot was built for): delete the whole snapshot and clear the resolver BEFORE anything else, so a former
 *   colleague (or the old organisation's members) stop being trusted immediately (plan §B1.3, "bound to the organisation").
 * - **Hub session up** ([relayConnected] turns true), **every [periodMs]** while the app lives, and on demand ([requestSync],
 *   pull-to-refresh, Hub tab opening): sync, but never more often than [minIntervalMs] unless forced.
 *
 * It adds no background wake-up (plan §B1.4, ADR-041): the periodic round runs only while the process is alive.
 * Everything here is serialised by one mutex so a clear can never interleave with a refresh.
 */
@FlashInternalApi
public class OrgSnapshotCoordinator(
    private val credentialRoot: Flow<String?>,
    private val relayConnected: Flow<Boolean>,
    private val syncOnce: suspend (force: Boolean) -> OrgSyncOutcome,
    private val clearSnapshot: suspend () -> Unit,
    private val refreshResolver: suspend () -> Unit,
    private val clearResolver: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
    private val periodMs: Long = SIX_HOURS_MS,
    private val minIntervalMs: Long = 60_000L,
    private val onOutcome: (reason: String, outcome: OrgSyncOutcome) -> Unit = { _, _ -> },
    private val getStoredRootFingerprint: (suspend () -> String?)? = null,
) {
    private val lock = Mutex()
    private var lastRoot: String? = null
    private var lastAttemptMs: Long = Long.MIN_VALUE
    private var jobs: List<Job> = emptyList()

    /** Starts watching the enrolment, the relay and the clock. Safe to call twice. */
    public fun start() {
        if (jobs.any { it.isActive }) return
        val rootJob = scope.launch { credentialRoot.distinctUntilChanged().collect { root -> onEnrolmentChanged(root) } }
        val relayJob = scope.launch {
            relayConnected.distinctUntilChanged().collect { up -> if (up) requestSync("relay") }
        }
        val tickJob = scope.launch {
            while (isActive) {
                delay(periodMs)
                requestSync("periodic")
            }
        }
        jobs = listOf(rootJob, relayJob, tickJob)
    }

    public fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /**
     * The enrolment root changed. [root] is the enrolled organisation's root fingerprint, or null when not enrolled.
     * A change away from a known root (null or another organisation) wipes the snapshot first.
     * A snapshot built for another organisation is discarded at start even when the app was not running at the change.
     */
    public suspend fun onEnrolmentChanged(root: String?) {
        lock.withLock {
            val previous = lastRoot
            val changed = previous != null && !previous.equals(root, ignoreCase = true)
            lastRoot = root
            val storedRoot = if (previous == null) getStoredRootFingerprint?.invoke() else null
            val storedMismatch = storedRoot != null && storedRoot.isNotEmpty() && !storedRoot.equals(root, ignoreCase = true)
            if (root == null || changed || storedMismatch) {
                clearSnapshot()
                clearResolver()
                lastAttemptMs = Long.MIN_VALUE
            }
            if (root == null) return
            // First sight of an enrolment after start also lands here: a stored snapshot from an earlier run is shown
            // at once, then replaced by the first sync.
            if (previous == null && !changed && !storedMismatch) refreshResolver()
            syncLocked("enrol", force = true)
        }
    }

    /** Pull-to-refresh, Hub tab opening, the relay coming up. Honours [minIntervalMs] unless [force]. */
    public suspend fun requestSync(reason: String, force: Boolean = false) {
        lock.withLock {
            if (lastRoot == null) return
            val now = clock()
            if (!force && lastAttemptMs != Long.MIN_VALUE && now - lastAttemptMs in 0 until minIntervalMs) return
            syncLocked(reason, force)
        }
    }

    private suspend fun syncLocked(reason: String, force: Boolean) {
        lastAttemptMs = clock()
        val outcome = syncOnce(force)
        if (outcome is OrgSyncOutcome.Success) refreshResolver()
        onOutcome(reason, outcome)
    }

    public companion object {
        public const val SIX_HOURS_MS: Long = 6L * 60 * 60 * 1000
    }
}
