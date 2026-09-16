package com.transfer.flash.core.persistence.db

import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.TrustedPeerEntity
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Proves the public open seam (Phase 2, slice 1) opens a REAL encrypted file database —
 * not just that the internal driver does.
 *
 * [JdbcCipherSQLiteDriverTest] covers the driver in depth (header bytes, wrong-key
 * semantics, empty-key rejection). What it cannot cover is the path `:desktop` will
 * actually call: [openEncryptedFlashDatabase], which hides the driver entirely. A seam
 * that silently dropped the key, opened `:memory:`, or bypassed the cipher driver would
 * still compile and would pass every driver-level test — so this suite re-proves the
 * three load-bearing properties THROUGH the seam:
 *
 * 1. a row round-trips across close/reopen (the file is a real store, not memory);
 * 2. the file on disk is not plaintext SQLite (the seam did not lose the encryption);
 * 3. a wrong key fails loudly instead of reading as an empty database.
 */
class FlashDatabaseOpenSeamTest {

    private val tempDirs = mutableListOf<File>()
    private var open: FlashDatabase? = null

    @AfterTest
    fun tearDown() {
        runCatching { open?.close() }
        open = null
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun tempDatabaseFile(): File {
        val dir = Files.createTempDirectory("flash-open-seam").toFile()
        tempDirs += dir
        return File(dir, "chat").let { nested ->
            nested.mkdirs()
            File(nested, "flash.db")
        }
    }

    @Test
    fun `a row round-trips across reopen through the public seam`() = runBlocking {
        val file = tempDatabaseFile()
        open = openEncryptedFlashDatabase(file, KEY)
        open!!.trustedPeerDao().insert(peer())
        open!!.close()
        open = null

        assertTrue(file.exists(), "the seam must write a real file, not an in-memory database")

        open = openEncryptedFlashDatabase(file, KEY)
        val stored = open!!.trustedPeerDao().observeAll().first().single()
        assertEquals(DEVICE_ID, stored.deviceId)
        assertEquals(FINGERPRINT, stored.fingerprintHex)
    }

    @Test
    fun `the seam-created file is not plaintext SQLite`() = runBlocking {
        val file = tempDatabaseFile()
        open = openEncryptedFlashDatabase(file, KEY)
        open!!.trustedPeerDao().insert(peer())
        open!!.close()
        open = null

        val prefix = file.inputStream().use { it.readNBytes(1024) }
        assertFalse(
            String(prefix, Charsets.ISO_8859_1).contains("SQLite format 3"),
            "plaintext SQLite magic in a seam-created file — the seam bypassed the cipher driver",
        )
    }

    @Test
    fun `the seam rejects an empty key instead of opening plaintext`() {
        val file = tempDatabaseFile()
        assertFailsWith<IllegalArgumentException> {
            openEncryptedFlashDatabase(file, "")
        }
        Unit
    }

    @Test
    fun `a duplicate conversation upsert absorbs instead of throwing`() = runBlocking {
        // Live 2026-09-15: three chat coroutines upserted the same conversation row at once
        // (`openConversation`, `sendText`, inbound handler). Room's upsert rescue catches
        // `androidx.sqlite.SQLiteException` — the raw xerial type slipped past it and crashed
        // every sender. The driver now translates, so the duplicate becomes an update.
        val file = tempDatabaseFile()
        open = openEncryptedFlashDatabase(file, KEY)
        val dao = open!!.conversationDao()
        dao.upsert(conversation("c1", "One"))
        dao.upsert(conversation("c1", "Two"))
        assertEquals("Two", dao.get("c1")!!.title)
    }

    private fun conversation(id: String, title: String) = ConversationEntity(
        id = id,
        title = title,
        isGroup = false,
    )

    private fun peer() = TrustedPeerEntity(
        deviceId = DEVICE_ID,
        name = "peer-$DEVICE_ID",
        fingerprintHex = FINGERPRINT,
        trustedAt = TRUSTED_AT,
    )

    private companion object {
        const val KEY = "correct horse battery staple"
        const val DEVICE_ID = "seam-peer-1"
        const val FINGERPRINT = "0A1B2C3D4E5F"
        const val TRUSTED_AT = 1_700_000_000_000L
    }
}
