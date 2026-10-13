@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.core.security.enterprise.HubSealingKeyStore
import com.transfer.flash.core.security.enterprise.MemoryHubSealingKeyStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-126: the publish / retry / 409 / 429 / offline state machine of the device's sealing key, against a fake hub. */
class HubSealingKeyManagerTest {

    /** A scripted hub: answers are consumed in order, then [default] repeats. Records exactly what was published. */
    private class FakeSealingApi(var default: SealingPublishResult = SealingPublishResult.PUBLISHED) : HubSealingKeyApi {
        val script = ArrayDeque<SealingPublishResult>()
        val published = mutableListOf<Pair<String, String>>()
        var removed = 0

        override suspend fun publish(hubUrl: String, sealingPublicKey: String, signature: String): SealingPublishResult {
            published += sealingPublicKey to signature
            return script.removeFirstOrNull() ?: default
        }

        override suspend fun remove(hubUrl: String): Boolean {
            removed++
            return true
        }
    }

    private val crypto = TestCrypto()
    private val store = MemoryHubSealingKeyStore()
    private val api = FakeSealingApi()
    private var url: String? = "https://hub.example.test"

    private fun manager(s: HubSealingKeyStore = store) = HubSealingKeyManager("dev-1", crypto, s, api, { url })

    @Test
    fun theFirstRoundMakesStoresAndPublishesAKeyWithAValidBinding() = runTest {
        val m = manager()
        val outcome = m.ensurePublished()

        assertTrue(outcome.done)
        assertEquals(HubSealingKeyState.PUBLISHED, m.state.value)
        val (pub, sig) = api.published.single()
        val stored = assertNotNull(store.load(), "the key is stored")
        assertEquals(pub, Base64.encode(stored.publicKeyEncoded))
        assertTrue(
            HubSealingKeyPair.verifyBinding(crypto.identityPublicKeyEncoded, "dev-1", Base64.decode(pub), sig),
            "the hub must be able to verify the identity binding",
        )
        assertNotNull(m.publishedFingerprint.value)
    }

    @Test
    fun aRepeatedRoundRepublishesTheSameKeyAndNeverMakesANewOne() = runTest {
        val m = manager()
        m.ensurePublished()
        val first = store.load()!!.publicKeyEncoded
        m.ensurePublished()
        manager().ensurePublished() // a restart: a new manager over the same store
        assertEquals(3, api.published.size)
        assertEquals(1, api.published.map { it.first }.toSet().size, "the published key never changes without a reason")
        assertContentEquals(first, store.load()!!.publicKeyEncoded)
    }

    @Test
    fun theKeyIsStoredBeforeItIsPublishedAndAKeyThatCannotBeStoredIsNeverPublished() = runTest {
        val broken = MemoryHubSealingKeyStore().also { it.failSaves = true }
        val m = manager(broken)
        val outcome = m.ensurePublished()

        assertFalse(outcome.done)
        assertEquals(HubSealingKeyState.STORAGE_FAILED, m.state.value)
        assertTrue(api.published.isEmpty(), "publishing a key that cannot be opened after a restart would lose every message sealed to it")
        assertNull(broken.load())
    }

    @Test
    fun aKeyTheHubRetiredIsReplacedByANewOneAndNeverSentAgain() = runTest {
        val m = manager()
        m.ensurePublished()
        val old = Base64.encode(store.load()!!.publicKeyEncoded)
        api.script += SealingPublishResult.KEY_RETIRED

        val outcome = m.ensurePublished()

        assertTrue(outcome.done)
        val now = Base64.encode(store.load()!!.publicKeyEncoded)
        assertTrue(now != old, "a new key must replace the retired one")
        assertEquals(listOf(old, old, now), api.published.map { it.first }, "after the 409 only the NEW key is published")
        assertEquals(HubSealingKeyState.PUBLISHED, m.state.value)
    }

    @Test
    fun aHubThatKeepsAnsweringRetiredStopsAfterAFewNewKeysAndRetriesLater() = runTest {
        api.default = SealingPublishResult.KEY_RETIRED
        val m = manager()
        val outcome = m.ensurePublished()
        assertFalse(outcome.done)
        assertEquals(3, api.published.size, "bounded: not an endless loop of key generation")
        assertEquals(HubSealingKeyState.PUBLISHING, m.state.value)
        assertTrue(outcome.retryAfterMs > 0)
    }

    @Test
    fun aRateLimitWaitsLongEnoughAndKeepsTheSameKey() = runTest {
        val m = manager()
        api.script += SealingPublishResult.RATE_LIMITED
        val outcome = m.ensurePublished()
        assertFalse(outcome.done)
        assertEquals(HubSealingKeyManager.RATE_LIMIT_WAIT_MS, outcome.retryAfterMs)
        assertTrue(outcome.retryAfterMs >= 30_000L, "the hub allows one publish per 30 s after the burst")
        val before = store.load()!!.publicKeyEncoded
        assertTrue(m.ensurePublished().done)
        assertContentEquals(before, store.load()!!.publicKeyEncoded, "a 429 never rotates the key")
    }

    @Test
    fun offlineBacksOffAndThenSucceedsWithoutLosingTheKey() = runTest {
        val m = manager()
        api.default = SealingPublishResult.UNREACHABLE
        val first = m.ensurePublished()
        val second = m.ensurePublished()
        assertFalse(first.done)
        assertTrue(second.retryAfterMs > first.retryAfterMs, "backoff grows while the hub stays unreachable")
        assertEquals(HubSealingKeyState.PUBLISHING, m.state.value)

        api.default = SealingPublishResult.PUBLISHED
        assertTrue(m.ensurePublished().done)
        assertEquals(1, api.published.map { it.first }.toSet().size, "the same key was retried, not replaced")
        val again = manager().also { api.default = SealingPublishResult.UNREACHABLE }.ensurePublished()
        assertEquals(HubSealingKeyManager.FIRST_RETRY_MS, again.retryAfterMs, "a success resets the backoff")
    }

    @Test
    fun theBackoffNeverExceedsItsCeiling() = runTest {
        api.default = SealingPublishResult.SERVER_ERROR
        val m = manager()
        var last = 0L
        repeat(20) { last = m.ensurePublished().retryAfterMs }
        assertEquals(HubSealingKeyManager.MAX_BACKOFF_MS, last)
    }

    @Test
    fun anOldHubIsRecognisedAndRetriedSlowly() = runTest {
        api.default = SealingPublishResult.HUB_TOO_OLD
        val m = manager()
        val outcome = m.ensurePublished()
        assertFalse(outcome.done)
        assertEquals(HubSealingKeyState.HUB_TOO_OLD, m.state.value)
        assertEquals(HubSealingKeyManager.SLOW_RETRY_MS, outcome.retryAfterMs)
    }

    @Test
    fun aRefusalIsShownAndNotHammered() = runTest {
        api.default = SealingPublishResult.REFUSED
        val m = manager()
        val outcome = m.ensurePublished()
        assertEquals(HubSealingKeyState.REFUSED, m.state.value)
        assertEquals(HubSealingKeyManager.SLOW_RETRY_MS, outcome.retryAfterMs)
    }

    @Test
    fun withoutAnEnrolmentNothingIsCreatedOrPublished() = runTest {
        url = null
        val m = manager()
        assertTrue(m.ensurePublished().done)
        assertTrue(api.published.isEmpty())
        assertNull(store.load(), "no key is made for a device that is not in an organisation")
    }

    @Test
    fun aKeyThatWentMissingFromTheStoreIsReplacedAndRepublished() = runTest {
        manager().ensurePublished()
        store.clear() // the keystore was reset: the stored copy is gone
        val m = manager()
        assertTrue(m.ensurePublished().done)
        assertEquals(2, api.published.map { it.first }.toSet().size, "the lost key is rotated, not pretended")
        assertNotNull(store.load())
    }

    @Test
    fun forgetDeletesTheLocalKeyAndOptionallyTellsTheHub() = runTest {
        val m = manager()
        m.ensurePublished()
        assertTrue(m.forget(tellHub = true))
        assertEquals(1, api.removed)
        assertNull(store.load())
        assertNull(m.key())
        assertNull(m.publishedFingerprint.value)
        assertEquals(HubSealingKeyState.IDLE, m.state.value)

        // A later enrolment makes a different key.
        m.ensurePublished()
        assertNotNull(m.key())
        m.forget(tellHub = false)
        assertEquals(1, api.removed, "revoke / org change must not call the hub")
        assertNull(store.load())
    }

    @Test
    fun theKeyForOpeningSealedMessagesIsLoadedFromTheStoreOnDemand() = runTest {
        val existing = HubSealingKeyPair.generate()
        val m = manager(MemoryHubSealingKeyStore(existing))
        assertContentEquals(existing.publicKeyEncoded, assertNotNull(m.key()).publicKeyEncoded)
    }
}
