@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.security.enterprise.FlashCryptoEcdsaVerifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubRevocationCacheTest {
    @Test
    fun highestVerifiedListSurvivesRestartAndScopeChange() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val org = TestOrg()
            val other = TestOrg()
            var creds = org.credentialsFor("b", TestCrypto())
            fun cache() = HubRevocationCache(dir.path, { creds }, FlashCryptoEcdsaVerifier(org.rootCrypto), { TestOrg.T0 })
            var cache = cache()
            assertFalse(cache.permitsOrg())
            assertTrue(cache.load())
            assertTrue(cache.permitsOrg())
            assertEquals(HubRevocationCache.Acceptance.APPLIED, cache.accept(org.hashedRevocations(ids = setOf("c"), version = 5), org.fingerprint))
            assertEquals(HubRevocationCache.Acceptance.STALE, cache.accept(org.revocations(version = 5), org.fingerprint))
            assertEquals(HubRevocationCache.Acceptance.STALE, cache.accept(org.revocations(version = 3), org.fingerprint))
            val tampered = org.revocations(version = 6).copy(version = 7)
            assertEquals(HubRevocationCache.Acceptance.INVALID, cache.accept(tampered, org.fingerprint))
            assertEquals(HubRevocationCache.Acceptance.INVALID, cache.accept(other.revocations(version = 7), org.fingerprint))
            assertEquals(HubRevocationCache.Acceptance.INVALID, cache.accept(org.hashedRevocations(version = 7, audience = other.fingerprint), org.fingerprint))
            cache = cache()
            assertTrue(cache.load())
            assertTrue(cache.current()!!.revokesDevice("c"))
            creds = other.credentialsFor("b", TestCrypto())
            assertNull(cache.current())
            assertFalse(cache.permitsOrg())
            assertTrue(cache.load())
            assertNull(cache.current())
            creds = org.credentialsFor("b", TestCrypto())
            assertTrue(cache.load())
            assertEquals(5L, cache.current()?.version)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun expiredDelegationCannotPublishOrClearRootRevocationButVerifiedHistoryStillLoads() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val org = TestOrg()
            var creds = org.credentialsFor("b", TestCrypto())
            var now = TestOrg.T0
            fun cache() = HubRevocationCache(dir.path, { creds }, FlashCryptoEcdsaVerifier(org.rootCrypto), { now })
            val delegateList = org.revocations(ids = setOf("c"), version = 2).copy(issuerPublicKey = org.hubKey)
            val signed = delegateList.copy(signature = Base64.encode(org.hubCrypto.sign(delegateList.canonicalBytes())))
            assertEquals(HubRevocationCache.Acceptance.APPLIED, cache().accept(signed, org.fingerprint))
            now += 500 * TestOrg.DAY
            val restored = cache()
            assertTrue(restored.load())
            assertTrue(restored.current()!!.revokesDevice("c"))
            val next = signed.copy(version = 3)
            val signedNext = next.copy(signature = Base64.encode(org.hubCrypto.sign(next.canonicalBytes())))
            assertEquals(HubRevocationCache.Acceptance.INVALID, restored.accept(signedNext, org.fingerprint))
            now = TestOrg.T0
            val revoked = org.revocations(version = 3).copy(revokedDelegationSerials = setOf(org.delegation().serial))
            val signedRevoked = revoked.copy(signature = Base64.encode(org.rootCrypto.sign(revoked.canonicalBytes())))
            assertEquals(HubRevocationCache.Acceptance.APPLIED, restored.accept(signedRevoked, org.fingerprint))
            assertFalse(restored.permitsOrg())
            val successor = signed.copy(version = 4)
            val signedSuccessor = successor.copy(signature = Base64.encode(org.hubCrypto.sign(successor.canonicalBytes())))
            assertEquals(HubRevocationCache.Acceptance.INVALID, restored.accept(signedSuccessor, org.fingerprint))
            assertEquals(HubRevocationCache.Acceptance.INVALID, restored.accept(org.revocations(version = 4), org.fingerprint))
            creds = creds.copy(delegationChain = emptyList())
            assertEquals(3L, cache().also { assertTrue(it.load()) }.current()?.version)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unavailableStorageDoesNotPublishAndCorruptStorageFailsClosed() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val org = TestOrg()
            val creds = org.credentialsFor("b", TestCrypto())
            val verifier = FlashCryptoEcdsaVerifier(org.rootCrypto)
            val blocked = dir.resolve("blocked").apply { writeText("regular file") }
            val unusable = HubRevocationCache(blocked.path, { creds }, verifier, { TestOrg.T0 })
            assertFalse(unusable.load())
            assertEquals(HubRevocationCache.Acceptance.STORAGE_FAILED, unusable.accept(org.revocations(), org.fingerprint))
            assertNull(unusable.current())
            assertFalse(unusable.permitsOrg())
            val saved = dir.resolve("${RevocationList.audienceOf(org.rootKey)}.list")
            saved.writeText("unreadable list")
            val corrupt = HubRevocationCache(dir.path, { creds }, verifier, { TestOrg.T0 })
            assertFalse(corrupt.load())
            assertFalse(corrupt.permitsOrg())
            assertEquals(HubRevocationCache.Acceptance.STORAGE_FAILED, corrupt.accept(org.revocations(), org.fingerprint))
            assertEquals("unreadable list", saved.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inFlightHubFetchCannotOverwriteGossipOrCrossOrganisations() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val org = TestOrg()
            var creds = org.credentialsFor("b", TestCrypto())
            val cache = HubRevocationCache(dir.path, { creds }, FlashCryptoEcdsaVerifier(org.rootCrypto), { TestOrg.T0 })
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val job = async {
                cache.refresh {
                    started.complete(Unit)
                    release.await()
                    org.revocations(version = 7)
                }
            }
            started.await()
            assertEquals(HubRevocationCache.Acceptance.APPLIED, cache.accept(org.revocations(version = 8), org.fingerprint))
            creds = TestOrg().credentialsFor("b", TestCrypto())
            release.complete(Unit)
            job.await()
            assertTrue(cache.load())
            assertNull(cache.current())
            creds = org.credentialsFor("b", TestCrypto())
            assertTrue(cache.load())
            assertEquals(8L, cache.current()?.version)
        } finally {
            dir.deleteRecursively()
        }
    }
}
