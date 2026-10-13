@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.security.identity.IdentityKeyVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** ADR-126: the desktop sealing key at rest, with the REAL vault construction (sealed key file) on every OS. */
class FileHubSealingKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun vault(dir: File = tmp.root) = IdentityKeyVault.forKeyFile(File(dir, "identity/vault.key"))
    private fun store(dir: File = tmp.root, v: IdentityKeyVault = vault(dir)) = FileHubSealingKeyStore(File(dir, "hub/sealing.key"), v)

    @Test
    fun aSavedKeyComesBackAfterARestartAndIsTheSameKey() = runBlocking {
        val key = HubSealingKeyPair.generate()
        store().save(key)

        val restored = store().load()!!
        assertArrayEquals(key.publicKeyEncoded, restored.publicKeyEncoded)
        assertArrayEquals(key.exportPrivateKeyPkcs8(), restored.exportPrivateKeyPkcs8())
    }

    @Test
    fun theFileOnDiskDoesNotContainThePrivateKeyOrThePublicKey() = runBlocking {
        val key = HubSealingKeyPair.generate()
        val s = store()
        s.save(key)
        val bytes = File(tmp.root, "hub/sealing.key").readBytes()
        assertFalse("the PKCS#8 bytes must not be readable from the file", indexOf(bytes, key.exportPrivateKeyPkcs8().copyOf(24)) >= 0)
        assertFalse("the plain codec bytes must not be readable from the file", indexOf(bytes, key.publicKeyEncoded.copyOf(24)) >= 0)
    }

    @Test
    fun aTamperedFileReadsAsNoKeyNeverAsJunk() = runBlocking {
        val s = store()
        s.save(HubSealingKeyPair.generate())
        val f = File(tmp.root, "hub/sealing.key")
        val b = f.readBytes()
        b[b.size / 2] = (b[b.size / 2].toInt() xor 0x5A).toByte()
        f.writeBytes(b)
        assertNull(s.load())
    }

    @Test
    fun aFileFromAnotherMachineVaultDoesNotOpen() = runBlocking {
        store(tmp.newFolder("a")).save(HubSealingKeyPair.generate())
        val moved = File(tmp.newFolder("b"), "hub/sealing.key").also { it.parentFile.mkdirs() }
        File(tmp.root, "a/hub/sealing.key").copyTo(moved)
        assertNull(FileHubSealingKeyStore(moved, vault(File(tmp.root, "b"))).load())
    }

    @Test
    fun clearDeletesTheFileAndSavingAgainReplacesTheKey() = runBlocking {
        val s = store()
        val first = HubSealingKeyPair.generate()
        s.save(first)
        val second = HubSealingKeyPair.generate()
        s.save(second)
        assertArrayEquals(second.publicKeyEncoded, s.load()!!.publicKeyEncoded)
        s.clear()
        assertFalse(File(tmp.root, "hub/sealing.key").exists())
        assertNull(s.load())
        s.clear() // clearing nothing is fine
    }

    @Test
    fun noTemporaryFilesAreLeftBehind() = runBlocking {
        val s = store()
        repeat(3) { s.save(HubSealingKeyPair.generate()) }
        assertEquals(listOf("sealing.key"), File(tmp.root, "hub").list()!!.toList())
    }

    @Test
    fun theKeyFileIsOwnerOnlyWhereTheFileSystemHasPosixModes() = runBlocking {
        val s = store()
        s.save(HubSealingKeyPair.generate())
        val path = File(tmp.root, "hub/sealing.key").toPath()
        val posix = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
        assumeTrue("not a POSIX file system", posix != null)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), posix)
    }

    @Test
    fun aKeyThatCannotBeProtectedIsNotWrittenAtAll() = runBlocking {
        val failing = IdentityKeyVault({ error("vault locked") }, { it })
        val s = FileHubSealingKeyStore(File(tmp.root, "hub/sealing.key"), failing)
        val threw = runCatching { s.save(HubSealingKeyPair.generate()) }.isFailure
        assertTrue(threw)
        assertFalse(File(tmp.root, "hub/sealing.key").exists())
    }

    @Test
    fun pinsRoundTripThroughTheFile() = runBlocking {
        val file = File(tmp.root, "hub/pins.txt")
        val pins = mapOf("dev-1" to HubSealingPin("aa", "bb"))
        FileHubSealingPinStore(file).save(pins)
        assertEquals(pins, FileHubSealingPinStore(file).load())
        assertTrue(FileHubSealingPinStore(File(tmp.root, "none.txt")).load().isEmpty())
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
