@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.security.identity.IdentityKeyVault
import com.transfer.flash.core.security.identity.SecretKeyStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Linux plan L1: which vault each OS gets, and that a missing keyring is a fallback rather than a crash. */
class DesktopVaultsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class MemoryKeyring : SecretKeyStore {
        var value: ByteArray? = null
        override fun lookup() = value?.copyOf()
        override fun store(key: ByteArray) {
            value = key.copyOf()
        }
    }

    private val secret = ByteArray(64) { it.toByte() }

    @Test
    fun `windows gets DPAPI and never builds a keyring`() {
        var built = false
        val vault = DesktopVaults.forCurrentOs(tmp.root, "Windows 11") { built = true; MemoryKeyring() }

        assertSame(IdentityKeyVault.Dpapi, vault)
        assertFalse(built)
    }

    @Test
    fun `linux uses the keyring when it works`() {
        val keyring = MemoryKeyring()
        val vault = DesktopVaults.forCurrentOs(tmp.root, "Linux") { keyring }

        val blob = vault.protect(secret)

        assertContentEquals(secret, vault.unprotect(blob))
        assertEquals(32, keyring.value?.size)
        assertFalse(File(tmp.root, "identity/vault.key").exists())
    }

    @Test
    fun `linux falls back to the key file when the keyring cannot be built`() {
        val vault = DesktopVaults.forCurrentOs(tmp.root, "Linux") { error("no session bus") }

        val blob = vault.protect(secret)

        assertContentEquals(secret, vault.unprotect(blob))
        assertTrue(File(tmp.root, "identity/vault.key").isFile)
    }

    @Test
    fun `other systems get the key file vault`() {
        val vault = DesktopVaults.forCurrentOs(tmp.root, "Mac OS X") { error("never used") }

        assertNotSame(IdentityKeyVault.Dpapi, vault)
        assertContentEquals(secret, vault.unprotect(vault.protect(secret)))
        assertTrue(File(tmp.root, "identity/vault.key").isFile)
    }
}
