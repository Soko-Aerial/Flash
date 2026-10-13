@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.security.crypto.SoftwareFlashCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-126: the byte form of the stored sealing key, the fingerprint, and the key-change pin rules. */
class HubSealingKeyStoreTest {

    @Test
    fun theCodecRoundTripsAKeyThatStillSealsAndOpens() {
        val key = HubSealingKeyPair.generate()
        val restored = assertNotNull(HubSealingKeyCodec.decode(HubSealingKeyCodec.encode(key)))
        assertContentEquals(key.publicKeyEncoded, restored.publicKeyEncoded)
        // The restored private half really is the same key: something sealed to the public key opens with it.
        val sender = SoftwareFlashCrypto()
        val payload = HubSealing.seal(sender, "a", "b", key.publicKeyEncoded, "m", 1L, "T", "body")
        val opened = assertNotNull(HubSealing.open(restored, sender.identityPublicKeyEncoded, "a", "b", "m", 1L, payload))
        assertEquals("body", opened.body)
    }

    @Test
    fun theCodecRejectsEverythingThatIsNotItsOwnOutput() {
        val good = HubSealingKeyCodec.encode(HubSealingKeyPair.generate())
        assertNull(HubSealingKeyCodec.decode(ByteArray(0)))
        assertNull(HubSealingKeyCodec.decode(byteArrayOf(1, 0, 0)))
        assertNull(HubSealingKeyCodec.decode(good.copyOf().also { it[0] = 2 }), "an unknown version is not guessed at")
        assertNull(HubSealingKeyCodec.decode(good.copyOf(40)), "a truncated key is not returned")
        assertNull(HubSealingKeyCodec.decode(ByteArray(120) { it.toByte() }))
    }

    @Test
    fun theMemoryStoreKeepsOneKeyAndClearRemovesIt() = runTest {
        val store = MemoryHubSealingKeyStore()
        assertNull(store.load())
        val key = HubSealingKeyPair.generate()
        store.save(key)
        assertContentEquals(key.publicKeyEncoded, store.load()!!.publicKeyEncoded)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun theFingerprintIsStableAndDiffersPerKey() {
        val a = HubSealingKeyPair.generate().publicKeyEncoded
        val b = HubSealingKeyPair.generate().publicKeyEncoded
        assertEquals(HubSealingFingerprint.of(a), HubSealingFingerprint.of(a))
        assertNotEquals(HubSealingFingerprint.of(a), HubSealingFingerprint.of(b))
        assertEquals(64, HubSealingFingerprint.of(a).length)
        assertEquals(4 * 4 + 3, HubSealingFingerprint.short(HubSealingFingerprint.of(a)).length)
    }

    // ----- pin rules -----

    @Test
    fun theFirstKeyIsRememberedAndTheSameKeyIsTheSame() {
        val first = HubSealingPinMath.evaluate(null, "aaa")
        assertEquals(HubKeyVerdict.FIRST_USE, first.verdict)
        assertEquals(HubSealingPin("aaa"), first.next)
        assertEquals(HubKeyVerdict.SAME, HubSealingPinMath.evaluate(first.next, "aaa").verdict)
    }

    @Test
    fun aDifferentKeyBlocksAndKeepsTheOldPinUntilConfirmed() {
        val changed = HubSealingPinMath.evaluate(HubSealingPin("aaa"), "bbb")
        assertEquals(HubKeyVerdict.CHANGED, changed.verdict)
        assertEquals("aaa", changed.next.pinned, "the remembered key is not replaced by the unconfirmed one")
        assertEquals("bbb", changed.next.pending)
        assertTrue(changed.next.blocked)
        assertEquals(HubSealingPin("bbb"), HubSealingPinMath.confirm(changed.next))
    }

    @Test
    fun theDirectoryGoingBackToThePinnedKeyClearsTheBlock() {
        val blocked = HubSealingPin("aaa", pending = "bbb")
        val back = HubSealingPinMath.evaluate(blocked, "aaa")
        assertEquals(HubKeyVerdict.SAME, back.verdict)
        assertFalse(back.next.blocked)
    }

    @Test
    fun theCodecOfPinsRoundTripsAndSkipsBrokenLines() {
        val pins = mapOf("dev-1" to HubSealingPin("aa"), "dev-2" to HubSealingPin("bb", "cc"))
        assertEquals(pins, HubSealingPinCodec.decode(HubSealingPinCodec.encode(pins)))
        assertEquals(mapOf("ok" to HubSealingPin("x")), HubSealingPinCodec.decode("garbage\n\nok\tx\n\ty"))
        assertTrue(HubSealingPinCodec.decode(null).isEmpty())
    }

    @Test
    fun pinsPersistAndABlockedPeerStaysBlockedAfterARestart() = runTest {
        val store = MemoryHubSealingPinStore()
        val pins = HubSealingPins(store)
        assertEquals(HubKeyVerdict.FIRST_USE, pins.check("bob", "k1"))
        assertEquals(HubKeyVerdict.CHANGED, pins.check("bob", "k2"))
        assertTrue(pins.blocked.value.containsKey("bob"))

        val afterRestart = HubSealingPins(store)
        afterRestart.load()
        assertTrue(afterRestart.isBlocked("bob"), "a changed key must not be forgotten by a restart")
        assertTrue(afterRestart.blocked.value.containsKey("bob"))

        assertTrue(afterRestart.confirm("bob"))
        assertFalse(afterRestart.isBlocked("bob"))
        assertEquals(HubKeyVerdict.SAME, afterRestart.check("bob", "k2"))
        assertFalse(afterRestart.confirm("bob"), "nothing is waiting any more")
        assertTrue(afterRestart.blocked.value.isEmpty())
    }

    @Test
    fun aFailedPinWriteStillProtectsThisRun() = runTest {
        val store = MemoryHubSealingPinStore().also { it.failSaves = true }
        val pins = HubSealingPins(store)
        pins.check("bob", "k1")
        assertEquals(HubKeyVerdict.CHANGED, pins.check("bob", "k2"))
        assertTrue(pins.isBlocked("bob"))
    }
}
