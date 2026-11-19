@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.entity.TrustedPeerEntity
import com.transfer.flash.core.persistence.db.openEncryptedFlashDatabase
import com.transfer.flash.core.security.identity.IdentityKeyVault
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class DesktopDatabaseRecoveryTest {

    private val dirs = mutableListOf<File>()
    private val engines = mutableListOf<DesktopEngine>()

    @AfterTest
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        engines.clear()
        dirs.forEach { runCatching { it.deleteRecursively() } }
        dirs.clear()
    }

    private fun newDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { dirs += it }

    private fun awaitReady(engine: DesktopEngine) {
        val settled = runBlocking {
            withTimeoutOrNull(30_000L) {
                while (!engine.ready.value && engine.startError.value == null) {
                    delay(50L)
                }
                true
            }
        }
        assertTrue(settled == true, "DesktopEngine did not settle")
        assertNull(engine.startError.value)
        assertTrue(engine.ready.value)
    }

    @Test
    fun `locked vault preserves db-key and existing database without overwriting`() {
        val state = newDir("flash-db-locked-state")
        val received = newDir("flash-db-locked-rx")
        val chatDir = File(state, "chat").apply { mkdirs() }
        val dbFile = File(chatDir, FlashDatabase.DATABASE_NAME)

        // Seed a valid protected key + database under working vault.
        val workingVault = IdentityKeyVault(
            protectFn = { plain -> byteArrayOf(0x33) + plain },
            unprotectFn = { blob ->
                require(blob.isNotEmpty() && blob[0] == 0x33.toByte()) { "locked" }
                blob.copyOfRange(1, blob.size)
            },
        )
        val key = ChatDbKeyStore(chatDir, workingVault).passphrase()
        val db = openEncryptedFlashDatabase(dbFile, key)
        runBlocking { db.trustedPeerDao().insert(TrustedPeerEntity("p1", "Peer", "AA", 1L)) }
        db.closeWithTrim()
        val keySnapshot = File(chatDir, ChatDbKeyStore.FILE_NAME).readBytes()
        val dbSnapshot = dbFile.readBytes()

        // Boot with a vault that cannot unprotect the existing 0x33 db-key blob: must not overwrite or quarantine either file.
        val mismatchedVault = IdentityKeyVault(
            protectFn = { plain -> byteArrayOf(0x77) + plain },
            unprotectFn = { blob ->
                require(blob.isNotEmpty() && blob[0] == 0x77.toByte()) { "cannot unprotect db-key" }
                blob.copyOfRange(1, blob.size)
            },
        )
        val engine = DesktopEngine(
            receivedRoot = received,
            stateDir = state,
            identityVault = mismatchedVault,
            pttAudio = FakePttAudio(),
        )
        engines += engine
        engine.start()
        awaitReady(engine)

        assertContentEquals(keySnapshot, File(chatDir, ChatDbKeyStore.FILE_NAME).readBytes(), "key file must survive")
        assertContentEquals(dbSnapshot, dbFile.readBytes(), "database file must survive untouched")
        assertFalse(chatDir.listFiles().orEmpty().any { it.name.contains(".unrecoverable-") })
    }

    @Test
    fun `missing db-key beside existing database quarantines unreadable db and commits fresh key`() {
        val state = newDir("flash-db-nokey-state")
        val received = newDir("flash-db-nokey-rx")
        val chatDir = File(state, "chat").apply { mkdirs() }
        val dbFile = File(chatDir, FlashDatabase.DATABASE_NAME)

        val oldDb = openEncryptedFlashDatabase(dbFile, "lost-passphrase")
        runBlocking { oldDb.trustedPeerDao().insert(TrustedPeerEntity("p1", "Old", "BB", 2L)) }
        oldDb.closeWithTrim()
        assertFalse(File(chatDir, ChatDbKeyStore.FILE_NAME).exists())

        val engine = testDesktopEngine(receivedRoot = received, stateDir = state)
        engines += engine
        engine.start()
        awaitReady(engine)

        assertTrue(File(chatDir, ChatDbKeyStore.FILE_NAME).exists(), "fresh key must be committed after opening fresh db")
        assertTrue(
            chatDir.listFiles().orEmpty().any { it.name.startsWith("${FlashDatabase.DATABASE_NAME}.unrecoverable-") },
            "old unreadable DB must be quarantined rather than overwritten",
        )
    }
}
