@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.identity

import com.transfer.flash.core.security.crypto.PersistedFlashCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Linux plan L0 / C4: the first run on a machine without DPAPI used to crash in `PersistedFlashCrypto.persist`.
 * These run on every OS (AES-GCM and the temp folder do not care), so the Windows developer machine exercises the
 * same code the Linux build will use.
 */
class KeyFileVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun vault(dir: File = tmp.root) = IdentityKeyVault.forKeyFile(File(dir, "identity/vault.key"))

    @Test
    fun `round-trips and does not leave the plain bytes in the blob`() {
        val secret = ByteArray(97) { (it * 7 + 3).toByte() }
        val v = vault()

        val blob = v.protect(secret)

        assertFalse("sealed blob must differ from the secret", blob.contentEquals(secret))
        assertArrayEquals(secret, v.unprotect(blob))
    }

    @Test
    fun `a second instance over the same key file opens the blob (restart)`() {
        val secret = "identity material".toByteArray()
        val blob = vault().protect(secret)

        assertArrayEquals(secret, vault().unprotect(blob))
    }

    @Test
    fun `two seals of the same bytes differ (fresh nonce each time)`() {
        val v = vault()
        val secret = ByteArray(32) { it.toByte() }

        assertFalse(v.protect(secret).contentEquals(v.protect(secret)))
    }

    @Test
    fun `a tampered blob throws and never returns junk`() {
        val v = vault()
        val blob = v.protect("identity material".toByteArray())
        blob[blob.size / 2] = (blob[blob.size / 2].toInt() xor 0x5A).toByte()

        try {
            v.unprotect(blob)
            fail("tampered blob must throw")
        } catch (_: Exception) {
            // expected: GCM authentication failure
        }
    }

    @Test
    fun `a blob from another master key does not open`() {
        val blob = vault(tmp.newFolder("a")).protect("identity material".toByteArray())

        try {
            vault(tmp.newFolder("b")).unprotect(blob)
            fail("a different master key must not open the blob")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `unprotect with a missing key file throws and does not create one`() {
        val keyFile = File(tmp.root, "identity/vault.key")
        val blob = vault().protect("x".toByteArray().copyOf(40))
        assertTrue(keyFile.delete())

        try {
            vault().unprotect(blob)
            fail("must throw when the master key is gone")
        } catch (_: Exception) {
            // expected
        }
        assertFalse("unprotect must not mint a key", keyFile.exists())
    }

    @Test
    fun `a too short blob throws`() {
        try {
            vault().unprotect(ByteArray(5))
            fail("short blob must throw")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `master key file is owner-only on a POSIX file system and holds 32 bytes`() {
        assumeTrue(
            "needs POSIX permissions",
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val keyFile = File(tmp.root, "identity/vault.key")
        vault().protect(ByteArray(48))

        assertEquals(32L, keyFile.length())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(keyFile.toPath()),
        )
        assertTrue("no temp files left behind", keyFile.parentFile.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `defaultForCurrentOs uses DPAPI only on Windows`() {
        assertSame(IdentityKeyVault.Dpapi, IdentityKeyVault.defaultForCurrentOs(tmp.root, "Windows 11"))
        val linux = IdentityKeyVault.defaultForCurrentOs(tmp.root, "Linux")
        assertNotSame(IdentityKeyVault.Dpapi, linux)
        assertNotSame(IdentityKeyVault.PassThrough, linux)
        val mac = IdentityKeyVault.defaultForCurrentOs(tmp.root, "Mac OS X")
        assertNotSame(IdentityKeyVault.Dpapi, mac)
    }

    @Test
    fun `first run on Linux generates and persists the identity and a restart loads the same key`() {
        // The C4 crash: loadOrGenerate -> persist -> vault.protect threw on a host without DPAPI.
        val dir = tmp.newFolder("state")
        val linuxVault = IdentityKeyVault.defaultForCurrentOs(dir, "Linux")

        val first = PersistedFlashCrypto(dir, linuxVault)
        val publicKey = first.identityPublicKeyEncoded
        val second = PersistedFlashCrypto(dir, IdentityKeyVault.defaultForCurrentOs(dir, "Linux"))

        assertArrayEquals("the restart must load the persisted identity, not make a new one", publicKey, second.identityPublicKeyEncoded)
        val onDisk = File(dir, "identity/id-key.bin").readBytes()
        assertTrue("identity file written", onDisk.isNotEmpty())
        assertTrue("identity file is sealed, not the raw PKCS#8 key", onDisk.size > 1 + 12 + 16)
    }
}
