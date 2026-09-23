@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.store

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit B7: an existing DB that cannot be opened with a freshly minted passphrase must be moved aside
 * (never deleted, never left in place to crash every launch). The passphrase side needs AndroidKeyStore
 * and is device-only; this pins the file decision.
 */
class EncryptedDatabaseRecoveryTest {

    private val dir = Files.createTempDirectory("flash-db-recovery").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun db(): File = File(dir, "flash.db").apply { writeText("ciphertext") }

    @Test
    fun `lost key with an existing database moves the database and its sidecars aside`() {
        val dbFile = db()
        File(dir, "flash.db-wal").writeText("wal")
        File(dir, "flash.db-shm").writeText("shm")

        val moved = EncryptedDatabaseRecovery.quarantineIfUnreadable(dbFile, mintedNewPassphrase = true, stamp = 42L)

        assertEquals(
            setOf("flash.db.unrecoverable-42", "flash.db.unrecoverable-42-wal", "flash.db.unrecoverable-42-shm"),
            moved.map { it.name }.toSet(),
        )
        assertFalse("the unreadable DB must not stay where SQLCipher will open it", dbFile.exists())
        assertEquals("bytes are kept, not deleted", "ciphertext", File(dir, "flash.db.unrecoverable-42").readText())
    }

    @Test
    fun `a passphrase that unwrapped normally never touches the database`() {
        val dbFile = db()
        assertTrue(EncryptedDatabaseRecovery.quarantineIfUnreadable(dbFile, mintedNewPassphrase = false, stamp = 1L).isEmpty())
        assertTrue(dbFile.exists())
    }

    @Test
    fun `first run mints a passphrase but has nothing to move`() {
        val dbFile = File(dir, "flash.db")
        assertTrue(EncryptedDatabaseRecovery.quarantineIfUnreadable(dbFile, mintedNewPassphrase = true, stamp = 1L).isEmpty())
    }
}
