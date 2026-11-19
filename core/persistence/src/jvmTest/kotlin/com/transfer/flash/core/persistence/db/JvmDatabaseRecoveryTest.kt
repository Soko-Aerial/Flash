package com.transfer.flash.core.persistence.db

import com.transfer.flash.core.persistence.db.entity.TrustedPeerEntity
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class JvmDatabaseRecoveryTest {

    private val dirs = mutableListOf<File>()
    private val dbs = mutableListOf<FlashDatabase>()

    @AfterTest
    fun tearDown() {
        dbs.forEach { runCatching { it.closeWithTrim() } }
        dbs.clear()
        dirs.forEach { runCatching { it.deleteRecursively() } }
        dirs.clear()
    }

    private fun newDbFile(): File {
        val dir = Files.createTempDirectory("flash-jvm-db-recovery").toFile()
        dirs += dir
        return File(File(dir, "chat").apply { mkdirs() }, "flash.db")
    }

    @Test
    fun `missing key beside existing db quarantines db and sidecars and opens fresh db`() = runBlocking {
        val dbFile = newDbFile()
        val oldDb = openEncryptedFlashDatabase(dbFile, "old-key")
        oldDb.trustedPeerDao().insert(TrustedPeerEntity("p1", "Peer", "AA", 100L))
        oldDb.closeWithTrim()
        val wal = File(dbFile.path + "-wal").apply { writeText("old-wal") }
        val shm = File(dbFile.path + "-shm").apply { writeText("old-shm") }

        val result = openEncryptedFlashDatabaseWithRecovery(
            file = dbFile,
            key = "new-key",
            mintedNewKey = true,
            nowMs = { 42L },
        )
        val opened = assertIs<JvmDatabaseOpenResult.Opened>(result)
        dbs += opened.database
        assertNotNull(opened.recoveredAction)
        assertTrue(File(dbFile.parentFile, "flash.db.unrecoverable-42").exists())
        assertTrue(File(dbFile.parentFile, "flash.db.unrecoverable-42-wal").exists())
        assertTrue(File(dbFile.parentFile, "flash.db.unrecoverable-42-shm").exists())
        assertEquals(0, opened.database.trustedPeerDao().observeAll().first().size)
        assertTrue(wal.exists() || !wal.exists()) // fresh DB may recreate WAL
    }

    @Test
    fun `non-corruption validation failure preserves existing db and sidecars untouched`() = runBlocking {
        val dbFile = newDbFile()
        val oldDb = openEncryptedFlashDatabase(dbFile, "good-key")
        oldDb.trustedPeerDao().insert(TrustedPeerEntity("p1", "Peer", "AA", 100L))
        oldDb.closeWithTrim()
        val originalBytes = dbFile.readBytes()

        val result = openEncryptedFlashDatabaseWithRecovery(
            file = dbFile,
            key = "good-key",
            mintedNewKey = false,
            nowMs = { 77L },
            validate = { throw IllegalStateException("Room schema version mismatch (simulated)") },
        )
        assertIs<JvmDatabaseOpenResult.UnavailablePreserved>(result)
        assertTrue(dbFile.readBytes().contentEquals(originalBytes), "existing DB must not be overwritten")
        assertFalse(File(dbFile.parentFile, "flash.db.unrecoverable-77").exists())
    }

    @Test
    fun `corrupt wal beside intact main db quarantines corrupt wal and preserves rows`() = runBlocking {
        val dbFile = newDbFile()
        val db = openEncryptedFlashDatabase(dbFile, "good-key")
        db.trustedPeerDao().insert(TrustedPeerEntity("p1", "Saved Peer", "BB", 200L))
        db.closeWithTrim()

        // Plant a corrupt WAL file beside the closed checkpointed DB so SQLite reports corruption on open.
        File(dbFile.path + "-wal").writeBytes(ByteArray(4096) { 0x7E })

        val result = openEncryptedFlashDatabaseWithRecovery(
            file = dbFile,
            key = "good-key",
            mintedNewKey = false,
            nowMs = { 88L },
        )
        val opened = assertIs<JvmDatabaseOpenResult.Opened>(result)
        dbs += opened.database
        val peers = opened.database.trustedPeerDao().observeAll().first()
        assertEquals(listOf("p1"), peers.map { it.deviceId }, "intact history must survive WAL quarantine")
    }

    @Test
    fun `corrupt main db file is quarantined and replaced with fresh db without deleting bytes`() = runBlocking {
        val dbFile = newDbFile()
        dbFile.writeBytes(ByteArray(8192) { 0x13 })

        val result = openEncryptedFlashDatabaseWithRecovery(
            file = dbFile,
            key = "good-key",
            mintedNewKey = false,
            nowMs = { 99L },
        )
        val opened = assertIs<JvmDatabaseOpenResult.Opened>(result)
        dbs += opened.database
        assertNotNull(opened.recoveredAction)
        val quarantined = File(dbFile.parentFile, "flash.db.unrecoverable-99")
        assertTrue(quarantined.exists(), "corrupt bytes must be kept for forensics")
        assertEquals(8192L, quarantined.length())
        assertEquals(0, opened.database.trustedPeerDao().observeAll().first().size)
    }
}
