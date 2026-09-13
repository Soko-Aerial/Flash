@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 26 (ADR-035): the DPAPI vault round-trip, run for real on Windows (the desktop
 * target). Assumption-guarded so a Linux CI run of `jvmTest` skips rather than fails —
 * DPAPI does not exist there, which is the reason the vault is a seam.
 */
class IdentityKeyVaultTest {

    @Test
    fun `dpapi round-trips and binds to the current user`() {
        org.junit.Assume.assumeTrue(
            "DPAPI is Windows-only",
            System.getProperty("os.name")?.startsWith("Win") == true,
        )
        val vault = IdentityKeyVault.Dpapi
        val secret = ByteArray(97) { (it * 7 + 3).toByte() } // odd size: no block-padding luck

        val protected = vault.protect(secret)
        // DPAPI actually transformed the bytes — the "vault" is not a pass-through in disguise.
        assertTrue(!protected.contentEquals(secret))
        assertArrayEquals(secret, vault.unprotect(protected))
    }

    @Test
    fun `dpapi rejects tampered blobs by throwing - never returning garbage`() {
        org.junit.Assume.assumeTrue(
            "DPAPI is Windows-only",
            System.getProperty("os.name")?.startsWith("Win") == true,
        )
        val vault = IdentityKeyVault.Dpapi
        val protected = vault.protect("identity material".toByteArray())
        protected[protected.size / 2] = (protected[protected.size / 2].toInt() xor 0x5A).toByte()
        val threw = try {
            vault.unprotect(protected)
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("tampered blob must throw, not decrypt to junk", threw)
    }

    @Test
    fun `pass-through vault is honest about being one`() {
        val vault = IdentityKeyVault.PassThrough
        val secret = byteArrayOf(1, 2, 3)
        assertArrayEquals(secret, vault.protect(secret))
        assertEquals(3, vault.unprotect(secret).size)
    }
}
