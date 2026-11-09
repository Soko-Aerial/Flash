@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.security.enterprise.FlashCryptoEcdsaVerifier
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubRevocationGossipTest {
    @Test
    fun aVerifiedPeerRequestsOneListAndCanRelayItAfterRestart() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val org = TestOrg()
            val verifier = FlashCryptoEcdsaVerifier(org.rootCrypto)
            fun cache(id: String): HubRevocationCache {
                val creds = org.credentialsFor(id, TestCrypto())
                return HubRevocationCache(dir.resolve(id).path, { creds }, verifier, { TestOrg.T0 })
            }
            val a = cache("a")
            val list = org.hashedRevocations(serials = (1_000L..5_999L).toSet(), ids = setOf("c"), version = 1)
            assertEquals(HubRevocationCache.Acceptance.APPLIED, a.accept(list, org.fingerprint))
            val b = cache("b")
            assertTrue(b.load())
            val aFrames = mutableListOf<String>()
            val bFrames = mutableListOf<String>()
            var elapsed = 0L
            val aGossip = HubRevocationGossip(a) { elapsed }
            val bGossip = HubRevocationGossip(b) { elapsed }
            val fromA = bGossip.session("a", true, { true }) { bFrames.add(it) }
            val fromB = aGossip.session("b", true, { true }) { aFrames.add(it) }
            fromB.announce()
            val offer = aFrames.single()
            assertTrue(fromA.receive(offer))
            val request = bFrames.single()
            assertTrue(fromB.receive(request))
            val signedBody = aFrames.last()
            assertTrue(fromA.receive(signedBody))
            assertTrue(b.current()!!.revokesDevice("c"))
            assertEquals(5_000, b.current()?.revokedSerials?.size)
            assertTrue(fromA.receive(signedBody))
            assertTrue(fromB.receive(request))
            assertEquals(2, aFrames.size)
            assertEquals(1, bFrames.size)
            val reconnected = aGossip.session("b", true, { true }) { aFrames.add(it) }
            reconnected.announce()
            assertEquals(2, aFrames.size)
            elapsed += HubRevocationGossip.WINDOW_MS
            reconnected.announce()
            assertEquals(3, aFrames.size)
            val restored = cache("b")
            assertTrue(restored.load())
            assertTrue(restored.current()!!.revokesDevice("c"))
            val nextHop = mutableListOf<String>()
            HubRevocationGossip(restored).session("d", true, { true }) { nextHop.add(it) }.announce()
            assertEquals(offer, nextHop.single())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun wrongRootUnnegotiatedUnauthenticatedOversizeAndUnsolicitedFramesDoNotMutate() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val org = TestOrg()
            val creds = org.credentialsFor("b", TestCrypto())
            val cache = HubRevocationCache(dir.path, { creds }, FlashCryptoEcdsaVerifier(org.rootCrypto), { TestOrg.T0 })
            assertTrue(cache.load())
            val gossip = HubRevocationGossip(cache)
            val sent = mutableListOf<String>()
            fun frame(op: String, root: String = org.fingerprint, vararg fields: Pair<String, String>) =
                FlashTextFraming.encodeFields("FLASH_RVG", listOf("op" to op, "root" to root, "v" to "3") + fields)
            val validOffer = frame("offer")
            gossip.session("a", false, { true }) { sent.add(it) }.receive(validOffer)
            gossip.session("a", true, { false }) { sent.add(it) }.receive(validOffer)
            val session = gossip.session("a", true, { true }) { sent.add(it) }
            session.receive(frame("offer", "A".repeat(64)))
            session.receive(frame("list", org.fingerprint, "body" to "A".repeat(128 * 1024)))
            session.receive(frame("list", org.fingerprint, "body" to "e30="))
            assertTrue(sent.isEmpty())
            assertNull(cache.current())
            assertFalse(session.receive("FLASH_CHAT"))
            val random = Random(9083)
            repeat(1_000) {
                val input = CharArray(random.nextInt(96)) { random.nextInt(0x10000).toChar() }.concatToString()
                assertTrue(session.receive("FLASH_RVG $input"))
            }
            assertTrue(sent.isEmpty())
            session.receive(validOffer)
            assertEquals(1, sent.size)
            session.receive(frame("list", org.fingerprint, "body" to "e30="))
            session.receive(validOffer)
            assertEquals(1, sent.size)
            assertNull(cache.current())
        } finally {
            dir.deleteRecursively()
        }
    }
}
