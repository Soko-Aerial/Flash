@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.HubSealingFingerprint
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.core.security.enterprise.HubSealingKeyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where this device's own sealing key stands, for the connection details (never contains key material). */
@FlashInternalApi
public enum class HubSealingKeyState {
    /** Not enrolled, or nothing tried yet. */
    IDLE,

    /** A key exists locally and the hub has not confirmed it yet (offline, rate limited, retrying). */
    PUBLISHING,

    /** The hub holds the current key: colleagues can seal messages to this device. */
    PUBLISHED,

    /** The hub is too old to publish keys; readable-content opt-in is the only hub path. */
    HUB_TOO_OLD,

    /** The hub refused this device's key as it is now (for example the device is not active). */
    REFUSED,

    /** The key could not be stored on this device, so it was NOT published. */
    STORAGE_FAILED,
}

/** What one [HubSealingKeyManager.ensurePublished] round concluded. */
@FlashInternalApi
public class HubSealingPublishOutcome(
    /** True when nothing more is to be done until the credentials or the key change. */
    public val done: Boolean,
    /** When not [done]: how long to wait before the next attempt. */
    public val retryAfterMs: Long = 0L,
)

/**
 * The lifecycle of this device's end-to-end sealing key (ADR-126): make it once, keep it in [store], publish its public
 * half with the identity-key binding signature, republish after any renewal or re-enrolment, replace it when the hub says
 * it can never take that key back, and delete it when the device leaves the organisation.
 *
 * One lock serialises every operation, so a publish round never races an unenrol or a revoke. No key material is logged:
 * only the short public fingerprint.
 */
@FlashInternalApi
public class HubSealingKeyManager(
    private val deviceId: String,
    private val crypto: FlashCrypto,
    private val store: HubSealingKeyStore,
    private val api: HubSealingKeyApi,
    private val hubUrl: () -> String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Mutex()
    private var cached: HubSealingKeyPair? = null
    private var cacheLoaded = false
    private var failures = 0

    private val _state = MutableStateFlow(HubSealingKeyState.IDLE)
    public val state: StateFlow<HubSealingKeyState> = _state.asStateFlow()

    /** The fingerprint (short form) of the key the hub currently holds for this device, or null. For the detail dialog. */
    private val _publishedFingerprint = MutableStateFlow<String?>(null)
    public val publishedFingerprint: StateFlow<String?> = _publishedFingerprint.asStateFlow()

    /**
     * This device's key for opening sealed messages, loading it from [store] on first use. Null when there is none yet
     * (not enrolled, or no key could be made). A message that arrives before the key exists cannot be opened.
     */
    public suspend fun key(): HubSealingKeyPair? = lock.withLock { loadLocked() }

    /**
     * Makes sure a key exists and the hub holds it. Safe to call as often as wanted (an unchanged key is an idempotent
     * publish). Returns whether to stop or when to try again.
     */
    public suspend fun ensurePublished(): HubSealingPublishOutcome = lock.withLock {
        val url = hubUrl() ?: run {
            _state.value = HubSealingKeyState.IDLE
            return@withLock HubSealingPublishOutcome(done = true)
        }
        var key = loadLocked() ?: newKeyLocked() ?: return@withLock HubSealingPublishOutcome(false, backoff(STORAGE_RETRY_MS))
        repeat(MAX_ROTATIONS) { round ->
            val signature = withContext(ioDispatcher) { key.bindingSignature(crypto, deviceId) }
            when (api.publish(url, Base64.encode(key.publicKeyEncoded), signature)) {
                SealingPublishResult.PUBLISHED -> {
                    failures = 0
                    _state.value = HubSealingKeyState.PUBLISHED
                    _publishedFingerprint.value = HubSealingFingerprint.short(HubSealingFingerprint.of(key.publicKeyEncoded))
                    FlashLog.i("HUB", "sealing key published fp=${_publishedFingerprint.value}")
                    return@withLock HubSealingPublishOutcome(done = true)
                }
                SealingPublishResult.KEY_RETIRED -> {
                    // The hub remembers this key as retired and will never take it back: this device needs a new one.
                    FlashLog.w("HUB", "sealing key was retired earlier, making a new one (round ${round + 1})")
                    key = newKeyLocked() ?: return@withLock HubSealingPublishOutcome(false, backoff(STORAGE_RETRY_MS))
                }
                SealingPublishResult.RATE_LIMITED -> {
                    _state.value = HubSealingKeyState.PUBLISHING
                    return@withLock HubSealingPublishOutcome(false, RATE_LIMIT_WAIT_MS)
                }
                SealingPublishResult.HUB_TOO_OLD -> {
                    _state.value = HubSealingKeyState.HUB_TOO_OLD
                    return@withLock HubSealingPublishOutcome(false, SLOW_RETRY_MS)
                }
                SealingPublishResult.REFUSED -> {
                    _state.value = HubSealingKeyState.REFUSED
                    return@withLock HubSealingPublishOutcome(false, SLOW_RETRY_MS)
                }
                SealingPublishResult.UNREACHABLE, SealingPublishResult.SERVER_ERROR -> {
                    _state.value = HubSealingKeyState.PUBLISHING
                    return@withLock HubSealingPublishOutcome(false, backoff(FIRST_RETRY_MS))
                }
            }
        }
        _state.value = HubSealingKeyState.PUBLISHING
        HubSealingPublishOutcome(false, backoff(FIRST_RETRY_MS))
    }

    /**
     * Deletes the local key (always) and, when [tellHub], asks the hub to forget it first (best effort; the hub also
     * removes it itself on unenrol and revoke). A failed local delete is reported as false: the key may still be there.
     */
    public suspend fun forget(tellHub: Boolean): Boolean = lock.withLock {
        val url = hubUrl()
        if (tellHub && url != null) runCatching { api.remove(url) }
        cached = null
        cacheLoaded = true
        failures = 0
        _publishedFingerprint.value = null
        _state.value = HubSealingKeyState.IDLE
        runCatching { store.clear() }.isSuccess
    }

    /** Asks the hub to forget this device's sealing key (before unenrolling, while the sign-in still works). Best effort. */
    public suspend fun withdraw(): Boolean = lock.withLock {
        val url = hubUrl() ?: return@withLock false
        runCatching { api.remove(url) }.getOrDefault(false)
    }

    private suspend fun loadLocked(): HubSealingKeyPair? {
        if (!cacheLoaded) {
            cached = runCatching { store.load() }.getOrNull()
            cacheLoaded = true
        }
        return cached
    }

    /** A new key, stored BEFORE it is used; null (and STORAGE_FAILED) when it could not be stored. */
    private suspend fun newKeyLocked(): HubSealingKeyPair? {
        val fresh = withContext(ioDispatcher) { HubSealingKeyPair.generate() }
        val saved = runCatching { store.save(fresh) }
        if (saved.isFailure) {
            FlashLog.w("HUB", "sealing key not stored (${saved.exceptionOrNull()?.let { it::class.simpleName }}), not publishing it")
            _state.value = HubSealingKeyState.STORAGE_FAILED
            return null
        }
        cached = fresh
        cacheLoaded = true
        return fresh
    }

    private fun backoff(base: Long): Long {
        val step = failures.coerceAtMost(MAX_BACKOFF_STEPS)
        failures++
        return (base shl step).coerceAtMost(MAX_BACKOFF_MS)
    }

    public companion object {
        public const val FIRST_RETRY_MS: Long = 15_000L
        public const val STORAGE_RETRY_MS: Long = 60_000L
        public const val RATE_LIMIT_WAIT_MS: Long = 35_000L
        public const val SLOW_RETRY_MS: Long = 30L * 60L * 1000L
        public const val MAX_BACKOFF_MS: Long = 15L * 60L * 1000L
        private const val MAX_BACKOFF_STEPS: Int = 8
        private const val MAX_ROTATIONS: Int = 3
    }
}
