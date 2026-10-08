@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Linux plan L1: keyring first, owner-only key file second, each blob opened from the place that sealed it. */
class KeyringVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyring(var reachable: Boolean = true, var keeps: Boolean = true) : SecretKeyStore {
        var value: ByteArray? = null
        var lookups = 0
        override fun lookup(): ByteArray? {
            lookups++
            check(reachable) { "no session bus" }
            return value?.copyOf()
        }

        override fun store(key: ByteArray) {
            check(reachable) { "no session bus" }
            if (keeps) value = key.copyOf()
        }
    }

    private fun keyFile() = File(tmp.root, "identity/vault.key")

    private val secret = ByteArray(80) { (it * 3 + 1).toByte() }

    @Test
    fun `a reachable keyring holds the master key and no key file is written`() {
        val keyring = FakeKeyring()
        val vault = IdentityKeyVault.withKeyring(keyring, keyFile())

        val blob = vault.protect(secret)

        assertEquals(KeyringMasterKey.TAG, blob[0])
        assertEquals(SealedVault.KEY_BYTES, keyring.value?.size)
        assertFalse("the key must stay out of the data folder", keyFile().exists())
        assertArrayEquals(secret, vault.unprotect(blob))
    }

    @Test
    fun `an unreachable keyring falls back to the key file`() {
        val vault = IdentityKeyVault.withKeyring(FakeKeyring(reachable = false), keyFile())

        val blob = vault.protect(secret)

        assertEquals(FileMasterKey.TAG, blob[0])
        assertTrue(keyFile().isFile)
        assertArrayEquals(secret, vault.unprotect(blob))
    }

    @Test
    fun `a keyring that does not keep the key falls back to the key file`() {
        val vault = IdentityKeyVault.withKeyring(FakeKeyring(keeps = false), keyFile())

        val blob = vault.protect(secret)

        assertEquals(FileMasterKey.TAG, blob[0])
        assertArrayEquals(secret, vault.unprotect(blob))
    }

    @Test
    fun `a restart reuses the keyring key`() {
        val keyring = FakeKeyring()
        val blob = IdentityKeyVault.withKeyring(keyring, keyFile()).protect(secret)

        assertArrayEquals(secret, IdentityKeyVault.withKeyring(keyring, keyFile()).unprotect(blob))
    }

    @Test
    fun `a blob sealed under the file still opens after a keyring appears`() {
        val fileBlob = IdentityKeyVault.withKeyring(FakeKeyring(reachable = false), keyFile()).protect(secret)

        val later = IdentityKeyVault.withKeyring(FakeKeyring(), keyFile())

        assertArrayEquals(secret, later.unprotect(fileBlob))
    }

    @Test
    fun `a keyring blob cannot be opened when the keyring is gone, and never mints a new key`() {
        val keyring = FakeKeyring()
        val blob = IdentityKeyVault.withKeyring(keyring, keyFile()).protect(secret)
        keyring.reachable = false

        try {
            IdentityKeyVault.withKeyring(keyring, keyFile()).unprotect(blob)
            fail("must throw so PersistedFlashCrypto degrades loudly")
        } catch (_: Exception) {
            // expected
        }
        assertFalse(keyFile().exists())
    }

    @Test
    fun `a keyring blob cannot be opened when the keyring lost the key`() {
        val keyring = FakeKeyring()
        val blob = IdentityKeyVault.withKeyring(keyring, keyFile()).protect(secret)
        keyring.value = null

        try {
            IdentityKeyVault.withKeyring(keyring, keyFile()).unprotect(blob)
            fail("must throw when the keyring no longer has the key")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `a wrong-sized keyring value is refused rather than used`() {
        val keyring = FakeKeyring().also { it.value = ByteArray(7) }
        val vault = IdentityKeyVault.withKeyring(keyring, keyFile())

        // The bad keyring entry fails the keyring tier; the file tier takes over.
        assertEquals(FileMasterKey.TAG, vault.protect(secret)[0])
    }

    @Test
    fun `an unknown tag throws`() {
        val vault = IdentityKeyVault.forKeyFile(keyFile())
        val blob = vault.protect(secret)
        blob[0] = 0x7F

        try {
            vault.unprotect(blob)
            fail("unknown key source must throw")
        } catch (_: Exception) {
            // expected
        }
    }
}
