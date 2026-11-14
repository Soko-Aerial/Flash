@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.security.identity.IdentityKeyVault
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileHubGroupEpochKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun vault(dir: File = tmp.root) = IdentityKeyVault.forKeyFile(File(dir, "identity/vault.key"))

    @Test
    fun roundTripSealsAtRestAndRejectsWrongVault() = runTest {
        val file = File(tmp.root, "hub/group-keys.vault")
        val primary = FileHubGroupEpochKeyStore(file, vault())
        val rawSecretKey = ByteArray(32) { i -> (0xA0 + i).toByte() }
        val key = GroupEpochKey(
            groupId = "group-top-secret-42",
            epoch = 3L,
            keyId = "k-epoch-3",
            keyBytes = rawSecretKey,
            rosterVersion = 5L,
            memberDeviceIds = setOf("alice", "bob", "carol"),
            creatorDeviceId = "alice",
            createdAtMs = 1_700_000_000_000L,
            canSend = true,
        )

        primary.saveAll(listOf(key))
        assertTrue(file.isFile)

        // Vault output on disk must NOT leak plaintext group id, keyId, or raw key material
        val rawDiskBytes = file.readBytes()
        val diskText = rawDiskBytes.decodeToString()
        assertFalse("group-top-secret-42" in diskText)
        assertFalse("k-epoch-3" in diskText)
        assertFalse("FLASH_GROUP_KEYS_V1" in diskText)

        // Reloading with the matching vault recovers the exact key record
        val reloaded = FileHubGroupEpochKeyStore(file, vault()).loadAll()
        assertEquals(1, reloaded.size)
        val restored = reloaded.single()
        assertEquals(key.groupId, restored.groupId)
        assertEquals(key.epoch, restored.epoch)
        assertEquals(key.keyId, restored.keyId)
        assertEquals(key.rosterVersion, restored.rosterVersion)
        assertEquals(key.memberDeviceIds, restored.memberDeviceIds)
        assertEquals(key.creatorDeviceId, restored.creatorDeviceId)
        assertEquals(key.canSend, restored.canSend)
        assertTrue(rawSecretKey.contentEquals(restored.keyBytes))

        // Different vault key cannot unprotect the file — must fail loudly and preserve sealed bytes on disk
        val wrongVault = FileHubGroupEpochKeyStore(file, vault(File(tmp.root, "other")))
        var threw = false
        try {
            wrongVault.loadAll()
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Expected wrong vault key to fail loudly with IllegalStateException", threw)
        assertTrue("Sealed file must remain intact after failed unlock", file.isFile)
        assertEquals(1, primary.loadAll().size)

        // Corrupt bytes fail loudly rather than silently returning emptyList
        val backup = file.readBytes()
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        var corruptThrew = false
        try {
            primary.loadAll()
        } catch (_: IllegalStateException) {
            corruptThrew = true
        }
        assertTrue("Expected corrupt vault file to fail loudly with IllegalStateException", corruptThrew)
        file.writeBytes(backup)

        // Clear removes file on disk
        primary.clear()
        assertFalse(file.exists())
        assertTrue(primary.loadAll().isEmpty())
    }
}
