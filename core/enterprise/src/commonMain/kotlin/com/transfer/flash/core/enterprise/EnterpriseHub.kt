@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.enterprise.relay.FlashRelayClient
import com.transfer.flash.core.enterprise.relay.RelayFailureKind
import com.transfer.flash.core.enterprise.relay.RelayIdentity
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.HubSealingKeyStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How an unenrol ended on this device. The device is always signed out locally unless it is [STORAGE_FAILED]. */
@FlashInternalApi
public enum class UnenrolResult {
    /** The hub removed the device and the local settings are cleared. */
    DONE,

    /** Cleared here; the hub could not be reached and will be told as soon as it can (see [HubStatus.pendingUnenrol]). */
    DONE_HUB_NOT_TOLD,

    /** Cleared here; the hub refused to confirm (the certificate was already invalid there). Nothing will be retried. */
    DONE_HUB_REFUSED,

    /** The local organisation settings could not be cleared, so the device is still enrolled. */
    STORAGE_FAILED,
}

/**
 * The one object both hosts use for everything about the organisation hub (CLI-01, -07, -08, -09, -12, -13).
 *
 * It owns the pieces that used to be created ad hoc per tap and per host:
 * - one shared [HttpClient] (closed by [shutdown]),
 * - the authenticated [session] (bearer, renewal, unenrol, revocations),
 * - the REST [directory] client and the [peers] cache that decides whether a hub sender is a known colleague,
 * - the [relay] client, started and stopped from the stored credentials and re-pointed when the hub address changes,
 * - the live [status] the Settings screen shows.
 *
 * Revocation: a `FLASH_REVOKED` frame alone never wipes anything. The hub's signed list ([HubSession.fetchRevocations],
 * verified against the root key from enrolment) must list this certificate; then the credentials are cleared, the
 * linked groups are remembered for a later re-enrolment and [HubStatus.state] stays [HubLinkState.REVOKED] until the
 * user dismisses it ([dismissRevokedNotice]) or enrols again. An unconfirmed revoke keeps the credentials, stops the
 * retrying and waits for [retryNow].
 */
@FlashInternalApi
public class EnterpriseHub(
    public val deviceId: String,
    public val friendlyName: String,
    private val crypto: FlashCrypto,
    public val trustStore: EnterpriseTrustStore,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = EnterpriseEnrollmentClient.createDefaultClient(),
    /** True when this object created [httpClient] (or was handed it to manage): [shutdown] closes it. */
    private val ownsHttpClient: Boolean = true,
    /** Debug-only: allow a cleartext hub on a trusted network. Release builds keep the default. */
    private val allowCleartext: () -> Boolean = { false },
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where this device keeps its own end-to-end sealing key (ADR-126). Null (previews, old tests) means the device
     * neither makes nor publishes one, and sealing stays dormant exactly as before.
     */
    sealingKeyStore: HubSealingKeyStore? = null,
    /** Builds the relay client; tests pass one with a fake transport. */
    relayFactory: ((identityProvider: () -> RelayIdentity?) -> FlashRelayClient)? = null,
    /** How often the status clock ticks and maintenance (renewal, pending unenrol) is considered. */
    private val tickMs: Long = 60_000L,
    /** Minimum time between two maintenance rounds. */
    private val maintenanceEveryMs: Long = 5L * 60L * 1000L,
) {
    public val session: HubSession = HubSession(httpClient, { trustStore.credentials.value }, crypto, trustStore, ioDispatcher, clock, allowCleartext)

    public val directory: EnterpriseDirectoryClient = EnterpriseDirectoryClient(
        httpClient = httpClient,
        ioDispatcher = ioDispatcher,
        bearerSource = session,
        pinnedHubUrl = { trustStore.credentials.value?.hubUrl },
        allowCleartext = allowCleartext,
    )

    public val peers: HubPeerDirectory = HubPeerDirectory(directory, { trustStore.credentials.value?.hubUrl }, clock)

    /** The lifecycle of this device's sealing key: made on first enrol, published after enrol and renewal, wiped on leave. */
    public val sealingKeys: HubSealingKeyManager? = sealingKeyStore?.let { store ->
        HubSealingKeyManager(
            deviceId = deviceId,
            crypto = crypto,
            store = store,
            api = directory,
            hubUrl = { trustStore.credentials.value?.hubUrl?.takeIf { HubUrlPolicy.check(it, allowCleartext()) == HubUrlPolicy.Verdict.OK } },
            ioDispatcher = ioDispatcher,
        )
    }

    private val enrollment = EnterpriseEnrollmentClient(httpClient, trustStore, ioDispatcher, allowCleartext, clock)

    private val identityProvider: () -> RelayIdentity? = {
        trustStore.credentials.value
            ?.takeIf { HubUrlPolicy.check(it.hubUrl, allowCleartext()) == HubUrlPolicy.Verdict.OK }
            ?.let { RelayIdentity.from(it, friendlyName, crypto) }
    }

    public val relay: FlashRelayClient = relayFactory?.invoke(identityProvider) ?: FlashRelayClient(
        deviceId = deviceId,
        friendlyName = friendlyName,
        hubUrl = trustStore.credentials.value?.hubUrl.orEmpty(),
        identityProvider = identityProvider,
    )

    private val revokedNotice = MutableStateFlow(false)
    private val addressRefused = MutableStateFlow(false)
    private val nowTick = MutableStateFlow(clock())
    private val maintenanceLock = Mutex()
    private var lastMaintenanceMs: Long = Long.MIN_VALUE
    private var jobs: List<Job> = emptyList()

    private val _status = MutableStateFlow(computeStatus())

    /** Live link state for Settings, banners and the connection detail sheet. */
    public val status: StateFlow<HubStatus> = _status.asStateFlow()

    /** Starts watching the credentials, the relay and the clock. Safe to call twice. */
    public fun start() {
        if (jobs.any { it.isActive }) return
        val credentialJob = scope.launch {
            trustStore.credentials.collect { creds -> applyCredentials(creds) }
        }
        val statusJob = combine(
            listOf(
                trustStore.credentials, relay.state, relay.failure, session.authFailure, session.renewalFailure,
                relay.nextRetryAtMs, relay.lastConnectedAtMs, revokedNotice, trustStore.pendingUnenrol,
                trustStore.storageError, nowTick, addressRefused,
            ),
        ) { }.onEach { _status.value = computeStatus() }.launchIn(scope)
        val revokeJob = scope.launch {
            relay.failure.collect { failure ->
                if (failure?.kind == RelayFailureKind.REVOKED) confirmRevocation()
            }
        }
        val tickJob = scope.launch {
            while (isActive) {
                nowTick.value = clock()
                maintenanceOnce()
                delay(tickMs)
            }
        }
        val sealingJob = sealingKeys?.let { keys -> scope.launch { keepSealingKeyPublished(keys) } }
        jobs = listOfNotNull(credentialJob, statusJob, revokeJob, tickJob, sealingJob)
    }

    /**
     * Publishes the sealing key whenever the device is enrolled and the hub does not hold it yet: after enrolment, after
     * a certificate renewal (new credentials), after a restart, and again when the relay comes online. A failed round
     * waits as long as [HubSealingPublishOutcome.retryAfterMs] says; any new trigger starts a round at once.
     */
    private suspend fun keepSealingKeyPublished(keys: HubSealingKeyManager) {
        var publishedFor: EnrollmentCredentials? = null
        combine(trustStore.credentials, relay.state.map { it.canSend }.distinctUntilChanged()) { creds, online -> creds to online }
            .collectLatest { (creds, _) ->
                if (creds == null) {
                    publishedFor = null
                    return@collectLatest
                }
                if (publishedFor == creds) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    val outcome = runSuspendCatching { keys.ensurePublished() }.getOrNull()
                        ?: HubSealingPublishOutcome(done = false, retryAfterMs = HubSealingKeyManager.STORAGE_RETRY_MS)
                    if (outcome.done) {
                        publishedFor = creds
                        break
                    }
                    delay(outcome.retryAfterMs)
                }
            }
    }

    /** Stops the relay and the watchers and closes the shared HTTP client (when owned). */
    public fun shutdown() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        relay.stop()
        if (ownsHttpClient) runCatching { httpClient.close() }
    }

    private fun applyCredentials(creds: EnrollmentCredentials?) {
        if (creds == null) {
            addressRefused.value = false
            relay.stop()
            return
        }
        if (HubUrlPolicy.check(creds.hubUrl, allowCleartext()) != HubUrlPolicy.Verdict.OK) {
            // A stored address that is not acceptable (an old plaintext enrolment): never dial it.
            addressRefused.value = true
            relay.stop()
            return
        }
        addressRefused.value = false
        relay.hubUrl = creds.hubUrl
        relay.start()
    }

    /**
     * Redeems an invite (the whole verified enrolment, one atomic save). On success the live session state is reset
     * and the relay connects straight away.
     */
    public suspend fun enroll(invite: EnrollmentInvite): EnrollmentOutcome {
        val before = trustStore.credentials.value
        val outcome = enrollment.enroll(invite, deviceId, friendlyName, crypto)
        if (outcome is EnrollmentOutcome.Enrolled) {
            // A key published to one organisation is never carried over to another.
            if (before != null && !before.rootFingerprint.equals(outcome.credentials.rootFingerprint, ignoreCase = true)) {
                sealingKeys?.forget(tellHub = false)
            }
            revokedNotice.value = false
            session.invalidate()
            peers.invalidate()
            applyCredentials(outcome.credentials)
            relay.retryNow()
            nowTick.value = clock()
        }
        return outcome
    }

    /**
     * Signs this device out of the organisation. The hub is told (best effort; offline keeps a pending notice that is
     * retried), then the credentials are cleared in one atomic write and the groups that were linked are remembered
     * ([EnterpriseTrustStore.previousHubLinks]) for a later re-enrolment with the same organisation.
     */
    public suspend fun unenrol(): UnenrolResult {
        val creds = trustStore.credentials.value ?: return UnenrolResult.DONE
        // Ask the hub to forget the sealing key while the sign-in still works (the hub also drops it when it removes the
        // device; this only keeps it from being served in between). Best effort and bounded.
        sealingKeys?.let { keys -> withTimeoutOrNull(WITHDRAW_TIMEOUT_MS) { keys.withdraw() } }
        val told = session.unenroll()
        val result = when (told) {
            UnenrollOutcome.CONFIRMED -> UnenrolResult.DONE
            UnenrollOutcome.UNREACHABLE -> UnenrolResult.DONE_HUB_NOT_TOLD
            UnenrollOutcome.REFUSED -> UnenrolResult.DONE_HUB_REFUSED
        }
        if (told == UnenrollOutcome.UNREACHABLE) {
            // Keep the notice BEFORE clearing, so a crash in between cannot lose the intention.
            runSuspendCatching { trustStore.savePendingUnenrol(creds) }
        }
        val cleared = runSuspendCatching { trustStore.clearCredentials() }
        if (cleared.isFailure) {
            if (told == UnenrollOutcome.UNREACHABLE) runSuspendCatching { trustStore.savePendingUnenrol(null) }
            return UnenrolResult.STORAGE_FAILED
        }
        // A join request that was waiting for approval belongs to the organisation we just left.
        trustStore.pendingJoins.value.keys.forEach { id -> runSuspendCatching { trustStore.setPendingJoin(id, null) } }
        // The local key goes as soon as the device is signed out, whatever the hub answered.
        sealingKeys?.forget(tellHub = false)
        revokedNotice.value = false
        session.invalidate()
        peers.invalidate()
        relay.stop()
        return result
    }

    /** Connects now (after a failure, a revoke that was not confirmed, or a "Retry now" tap) and retries a pending unenrol. */
    public fun retryNow() {
        relay.retryNow()
        scope.launch { maintenanceOnce(force = true) }
    }

    /** Forgets the "your organisation removed this device" notice. */
    public fun dismissRevokedNotice() {
        revokedNotice.value = false
    }

    /** The group ids that were linked to the previous enrolment with THIS organisation, or empty. */
    public fun relinkableGroups(): Set<String> {
        val previous = trustStore.previousHubLinks.value ?: return emptySet()
        val creds = trustStore.credentials.value ?: return emptySet()
        return if (previous.rootFingerprint.equals(creds.rootFingerprint, ignoreCase = true)) previous.groupIds else emptySet()
    }

    /** Links the remembered groups again (same organisation only) and forgets the memory. Returns how many were restored. */
    public suspend fun relinkPreviousGroups(): Int {
        val groups = relinkableGroups()
        groups.forEach { trustStore.linkGroupToHub(it) }
        trustStore.clearPreviousHubLinks()
        return groups.size
    }

    /** Throws the remembered groups away without linking them. */
    public suspend fun forgetPreviousGroups() {
        trustStore.clearPreviousHubLinks()
    }

    /**
     * Renews the certificate when it is inside its renewal window. Returns null when nothing was due, else the
     * outcome (also reflected in [HubStatus.renewalFailed]).
     */
    public suspend fun renewIfDue(): EnrollmentOutcome? {
        val creds = trustStore.credentials.value ?: return null
        if (!creds.needsRenewalAt(clock())) return null
        return session.renew()
    }

    /** One maintenance round: pending unenrol retry and renewal. [force] ignores the minimum interval. */
    public suspend fun maintenanceOnce(force: Boolean = false) {
        maintenanceLock.withLock {
            val now = clock()
            if (!force && lastMaintenanceMs != Long.MIN_VALUE && now - lastMaintenanceMs < maintenanceEveryMs) return
            lastMaintenanceMs = now
            retryPendingUnenrol()
            val renewed = renewIfDue()
            if (renewed is EnrollmentOutcome.Enrolled) {
                // The relay session keeps the old certificate until it reconnects; make the next connect use the new one.
                relay.retryNow()
            }
        }
    }

    private suspend fun retryPendingUnenrol() {
        val pending = trustStore.pendingUnenrol.value ?: return
        if (trustStore.credentials.value != null && trustStore.credentials.value?.rootFingerprint == pending.rootFingerprint) {
            // Enrolled again with the same organisation: the old notice no longer applies.
            runSuspendCatching { trustStore.savePendingUnenrol(null) }
            return
        }
        when (session.unenroll(pending)) {
            UnenrollOutcome.UNREACHABLE -> Unit
            UnenrollOutcome.CONFIRMED, UnenrollOutcome.REFUSED -> runSuspendCatching { trustStore.savePendingUnenrol(null) }
        }
    }

    /**
     * Called when the relay reports REVOKED. Confirms against the hub's signed list before anything is erased
     * (a hub that merely says "revoked" must not be able to sign a device out).
     */
    public suspend fun confirmRevocation(): Boolean {
        val creds = trustStore.credentials.value ?: return false
        val list = session.fetchRevocations()
        if (list == null || !list.isRevoked(creds.deviceCert)) return false
        revokedNotice.value = true
        val cleared = runSuspendCatching { trustStore.clearCredentials() }
        // A removed device keeps nothing it could be sealed to; the hub dropped the public half when it revoked the device.
        sealingKeys?.forget(tellHub = false)
        session.invalidate()
        peers.invalidate()
        return cleared.isSuccess
    }

    private fun computeStatus(): HubStatus {
        val authFailure = session.authFailure.value ?: if (addressRefused.value) HubAuthFailure.ADDRESS_REFUSED else null
        return HubStatusMath.derive(
            HubStatusInputs(
                credentials = trustStore.credentials.value,
                relayState = relay.state.value,
                relayFailure = relay.failure.value,
                authFailure = authFailure,
                renewalFailure = session.renewalFailure.value,
                nextRetryAtMs = relay.nextRetryAtMs.value,
                lastConnectedAtMs = relay.lastConnectedAtMs.value,
                revokedNotice = revokedNotice.value,
                pendingUnenrol = trustStore.pendingUnenrol.value != null,
                storageError = trustStore.storageError.value,
                nowMs = clock(),
            ),
        )
    }
}

private const val WITHDRAW_TIMEOUT_MS: Long = 5_000L
