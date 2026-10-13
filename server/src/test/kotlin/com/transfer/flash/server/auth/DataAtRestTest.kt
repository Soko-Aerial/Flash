package com.transfer.flash.server.auth

import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.update
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** REV-03: what lives on disk. The database is private to the hub's account and the authenticator seeds are sealed. */
class DataAtRestTest {

    private val dirs = ArrayList<File>()
    private val dbs = ArrayList<ServerDatabase>()

    @AfterTest
    fun cleanUp() {
        dbs.forEach { runCatching { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("flash-rest").toFile().also { dirs += it }

    private fun openDb(dir: File): ServerDatabase = ServerDatabase("jdbc:sqlite:${File(dir, "flash-hub.db").path.replace('\\', '/')}", 2).also { dbs += it }

    @Test
    fun theDatabaseAndItsSideFilesAreNeverReadableByOtherAccounts() {
        val dir = tempDir()
        val db = openDb(dir)
        // Force the -wal / -shm files into existence.
        AdminAuthService(printSetupTokenToConsole = false, db = db, passwordIterations = 1000).let { it.beginSetup(it.getActiveSetupTokenForTesting()!!) }
        val files = listOf("flash-hub.db", "flash-hub.db-wal", "flash-hub.db-shm").map { File(dir, it).toPath() }.filter { Files.exists(it) }
        assertTrue(files.isNotEmpty())
        for (f in files) assertTrue(SecureFiles.isPrivateToHubAccount(f), "${f.fileName} must be private to the hub account")
        assertTrue(SecureFiles.isPrivateToHubAccount(dir.toPath()), "the data directory too")
    }

    @Test
    fun anExistingWorldReadableDatabaseIsTightenedAtOpen() {
        val dir = tempDir()
        val file = File(dir, "flash-hub.db")
        file.writeBytes(ByteArray(0))
        val posix = Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java)
        posix?.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        openDb(dir)
        assertTrue(SecureFiles.isPrivateToHubAccount(file.toPath()))
    }

    @Test
    fun theSecretBoxSealsAuthenticatesAndBindsTheValueToItsContext() {
        val box = SecretBox.ofKey(ByteArray(32) { it.toByte() })
        val sealed = box.seal("JBSWY3DPEHPK3PXP", "ctx-a")
        assertTrue(sealed.startsWith("enc1:"))
        assertFalse(sealed.contains("JBSWY3DPEHPK3PXP"))
        assertNotEquals(sealed, box.seal("JBSWY3DPEHPK3PXP", "ctx-a"), "fresh nonce each time")
        assertEquals("JBSWY3DPEHPK3PXP", box.open(sealed, "ctx-a"))
        assertFailsWith<SecurityException> { box.open(sealed, "ctx-b") }
        assertFailsWith<SecurityException> { SecretBox.ofKey(ByteArray(32) { 9 }).open(sealed, "ctx-a") }
        assertEquals("legacy", box.open("legacy", "ctx-a"), "a plaintext of an older data directory still reads")
        assertFailsWith<SecurityException> { SecretBox.None.open(sealed, "ctx-a") }
    }

    @Test
    fun theKeyFileIsCreatedOwnerOnlyReusedAndNeverSilentlyReplaced() {
        val dir = tempDir()
        val file = File(dir, "data.key").toPath()
        val first = SecretBox.loadOrCreate(file)
        assertTrue(SecureFiles.isPrivateToHubAccount(file))
        val sealed = first.seal("x", "c")
        assertEquals("x", SecretBox.loadOrCreate(file).open(sealed, "c"), "the second load uses the same key")
        Files.writeString(file, "AAAA")
        assertFailsWith<KeyFileException> { SecretBox.loadOrCreate(file) }
    }

    @Test
    fun authenticatorSeedsAreSealedInTheDatabaseFileAndLegacyPlaintextIsMigrated() {
        val dir = tempDir()
        val db = openDb(dir)
        val box = SecretBox.loadOrCreate(File(dir, "data.key").toPath())
        val before = AdminAuthService(printSetupTokenToConsole = false, db = db, passwordIterations = 1000)
        // A data directory from before REV-03: the seed is stored in plain text.
        val legacySeed = before.generateTotpSecret()
        before.addAdminDirectlyForTesting(com.transfer.flash.server.admin.AdminUser("legacy-admin", before.hashPassword("CorrectPassword123!"), legacySeed))
        assertTrue(rawSeed(db, "legacy-admin") == legacySeed)

        val svc = AdminAuthService(printSetupTokenToConsole = false, db = db, passwordIterations = 1000, secrets = box)
        assertEquals(1, svc.sealLegacySecrets())
        assertEquals(0, svc.sealLegacySecrets(), "idempotent")
        assertTrue(rawSeed(db, "legacy-admin").startsWith("enc1:"))

        // New admins and a reset are sealed on write and still verify (the service opens them).
        val creds = svc.breakGlassReset("recovered-admin")
        assertTrue(rawSeed(db, "recovered-admin").startsWith("enc1:"))
        assertFalse(rawSeed(db, "recovered-admin").contains(creds.totpSecret))
        val code = svc.generateTotp(creds.totpSecret, System.currentTimeMillis() / 1000L)
        assertTrue(svc.authenticate("recovered-admin", creds.password!!, code, "127.0.0.1") is com.transfer.flash.server.admin.AuthResult.Success)

        // The bytes of the database file hold neither seed.
        db.close()
        val bytes = listOf("flash-hub.db", "flash-hub.db-wal").map { File(dir, it) }.filter { it.exists() }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(bytes.contains(legacySeed), "legacy seed must not appear on disk")
        assertFalse(bytes.contains(creds.totpSecret), "new seed must not appear on disk")
    }

    private fun rawSeed(db: ServerDatabase, username: String): String = db.withConnection { conn ->
        conn.prepareStatement("SELECT totp_secret FROM admin_users WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }
}
