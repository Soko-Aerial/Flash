package com.transfer.flash.core.security.crypto

import java.security.KeyPairGenerator
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Sweep R-09 (2026-10-09): a failing probe of the identity key must never lead to its deletion. The real
 * `KeystoreFlashCrypto` needs a device; its decision is [IdentityKeyCheck], exercised here with a fake keystore that injects
 * the failures the finding lists (`KeyStoreException`, `ProviderException`, `UnrecoverableKeyException`).
 */
class IdentityKeyCheckTest {

    private val key: PrivateKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private

    /** A keystore entry whose three calls can each be made to succeed, report something, or throw. */
    private class FakeEntry(
        var key: () -> PrivateKey?,
        var digests: () -> Set<String>,
        var signing: () -> Unit = {},
    ) : IdentityKeyInspector {
        var deleted = false
        override fun privateKey(): PrivateKey? = key()
        override fun digests(key: PrivateKey): Set<String> = digests()
        override fun initNoneSigning(key: PrivateKey) = signing()
    }

    /** What `KeystoreFlashCrypto.loadOrGenerateIdentityKey` does with the verdict, over the fake. */
    private fun loadLike(entry: FakeEntry, pauses: IntArray = IntArray(1)): IdentityKeyAction {
        val action = IdentityKeyCheck.decide(pause = { pauses[0]++ }) { IdentityKeyCheck.inspect(entry) }
        if (action == IdentityKeyAction.REGENERATE) entry.deleted = true
        return action
    }

    @Test
    fun `a key whose digests include NONE is kept`() {
        val entry = FakeEntry({ key }, { setOf("SHA-256", "NONE") })
        assertEquals(IdentityKeyAction.KEEP, loadLike(entry))
        assertEquals(false, entry.deleted)
    }

    @Test
    fun `a key whose digests were read and lack NONE is regenerated`() {
        val entry = FakeEntry({ key }, { setOf("SHA-256") })
        assertEquals(IdentityKeyAction.REGENERATE, loadLike(entry))
        assertTrue(entry.deleted)
    }

    @Test
    fun `an alias with no private key is regenerated, there is no identity to lose`() {
        val entry = FakeEntry({ null }, { error("not reached") })
        assertEquals(IdentityKeyAction.REGENERATE, loadLike(entry))
    }

    @Test
    fun `R-09 a keystore failure while loading the key never deletes it`() {
        for (failure in listOf<Exception>(KeyStoreException("daemon restarting"), UnrecoverableKeyException("boot"), ProviderException("strongbox"))) {
            val entry = FakeEntry({ throw failure }, { error("not reached") })
            val pauses = IntArray(1)
            val thrown = assertFailsWith<IdentityKeyUnverifiableException> { loadLike(entry, pauses) }
            assertSame(failure, thrown.cause)
            assertEquals(false, entry.deleted, "the identity key survives ${failure::class.simpleName}")
            assertEquals(IdentityKeyCheck.PROBE_ATTEMPTS - 1, pauses[0], "retried before giving up")
        }
    }

    @Test
    fun `R-09 a provider failure in both the parameter read and the signing probe never deletes the key`() {
        val entry = FakeEntry({ key }, { throw ProviderException("KeyInfo") }, { throw ProviderException("initSign") })
        assertFailsWith<IdentityKeyUnverifiableException> { loadLike(entry) }
        assertEquals(false, entry.deleted)
    }

    @Test
    fun `a failed parameter read falls back to the signing probe`() {
        val works = FakeEntry({ key }, { throw ProviderException("KeyInfo") }, {})
        assertEquals(IdentityKeyAction.KEEP, loadLike(works))
    }

    @Test
    fun `a transient failure that clears on the second look keeps the key`() {
        var calls = 0
        val entry = FakeEntry({ if (calls++ == 0) throw KeyStoreException("just booted") else key }, { setOf("NONE") })
        val pauses = IntArray(1)
        assertEquals(IdentityKeyAction.KEEP, loadLike(entry, pauses))
        assertEquals(1, pauses[0])
        assertEquals(false, entry.deleted)
    }

    @Test
    fun `a failure that is followed by a definite verdict regenerates only on the definite verdict`() {
        var calls = 0
        val entry = FakeEntry({ if (calls++ == 0) throw KeyStoreException("just booted") else key }, { setOf("SHA-256") })
        assertEquals(IdentityKeyAction.REGENERATE, loadLike(entry))
    }
}
