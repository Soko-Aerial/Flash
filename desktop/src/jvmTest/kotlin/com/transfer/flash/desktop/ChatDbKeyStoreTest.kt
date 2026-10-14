@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.security.identity.IdentityKeyVault
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR-132: the desktop chat database passphrase is stored under the identity vault, and the legacy plain-text file is upgraded. */
class ChatDbKeyStoreTest {

    private val dirs = mutableListOf<File>()

    @After
    fun tearDown() = dirs.forEach { runCatching { it.deleteRecursively() } }

    private fun newDir(): File = Files.createTempDirectory("flash-dbkey").toFile().also { dirs.add(it) }

    /** Reversible and authenticating enough for the test: a marker byte plus XOR, and unprotect throws on a foreign blob. */
    private fun vault(secret: Int = 0x5A) = IdentityKeyVault(
        protectFn = { plain -> byteArrayOf(0x7F) + ByteArray(plain.size) { (plain[it].toInt() xor secret).toByte() } },
        unprotectFn = { blob ->
            require(blob.isNotEmpty() && blob[0] == 0x7F.toByte()) { "not produced by this vault" }
            ByteArray(blob.size - 1) { (blob[it + 1].toInt() xor secret).toByte() }
        },
    )

    private val hex64 = Regex("[0-9a-f]{64}")

    @Test
    fun aFreshKeyIsRandomHexAndIsNotWrittenAsPlainText() {
        val dir = newDir()
        val key = ChatDbKeyStore(dir, vault()).passphrase()
        assertTrue(hex64.matches(key), key)
        val onDisk = File(dir, "db-key.bin").readBytes()
        assertEquals(ChatDbKeyStore.HEADER, onDisk[0])
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains(key), "the passphrase must not appear in the file")
    }

    @Test
    fun theSameKeyComesBackOnTheNextStart() {
        val dir = newDir()
        val first = ChatDbKeyStore(dir, vault()).passphrase()
        assertEquals(first, ChatDbKeyStore(dir, vault()).passphrase())
    }

    @Test
    fun twoFreshKeysDiffer() {
        assertTrue(ChatDbKeyStore(newDir(), vault()).passphrase() != ChatDbKeyStore(newDir(), vault()).passphrase())
    }

    @Test
    fun aLegacyPlainTextKeyKeepsItsValueAndIsRewrittenProtected() {
        val dir = newDir()
        val legacy = "ab".repeat(32)
        File(dir, "db-key.bin").writeText(legacy)
        val messages = mutableListOf<String>()
        val key = ChatDbKeyStore(dir, vault(), log = messages::add).passphrase()
        assertEquals(legacy, key, "the passphrase is unchanged, so the database needs no re-encryption")
        val onDisk = File(dir, "db-key.bin").readBytes()
        assertEquals(ChatDbKeyStore.HEADER, onDisk[0])
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains(legacy))
        assertTrue(messages.single().contains("re-stored"))
        assertEquals(legacy, ChatDbKeyStore(dir, vault()).passphrase())
    }

    @Test
    fun aLegacyKeyWithATrailingNewlineIsStillRecognised() {
        val dir = newDir()
        val legacy = "0f".repeat(32)
        File(dir, "db-key.bin").writeText(legacy + "\n")
        assertEquals(legacy, ChatDbKeyStore(dir, vault()).passphrase())
    }

    @Test
    fun aLegacyKeyIsKeptWorkingWhenTheVaultCannotProtectIt() {
        val dir = newDir()
        val legacy = "cd".repeat(32)
        File(dir, "db-key.bin").writeText(legacy)
        val broken = IdentityKeyVault(protectFn = { error("keyring unavailable") }, unprotectFn = { it })
        assertEquals(legacy, ChatDbKeyStore(dir, broken).passphrase())
        assertEquals(legacy, File(dir, "db-key.bin").readText(), "left exactly as it was")
    }

    @Test
    fun aProtectedKeyThatCannotBeUnprotectedIsNeverReplaced() {
        val dir = newDir()
        ChatDbKeyStore(dir, vault(secret = 0x11)).passphrase()
        val before = File(dir, "db-key.bin").readBytes()
        // Another user / restored copy: the vault refuses it.
        val other = IdentityKeyVault(protectFn = { it }, unprotectFn = { error("wrong user") })
        assertFailsWith<IllegalStateException> { ChatDbKeyStore(dir, other).passphrase() }
        assertContentEquals(before, File(dir, "db-key.bin").readBytes(), "the only copy of the key must survive")
    }

    @Test
    fun aVaultThatCannotProtectAFreshKeyFailsInsteadOfHandingOutAKeyThatIsNotStored() {
        val broken = IdentityKeyVault(protectFn = { error("keyring unavailable") }, unprotectFn = { it })
        assertFailsWith<IllegalStateException> { ChatDbKeyStore(newDir(), broken).passphrase() }
    }

    @Test
    fun anEmptyFileGetsAFreshKey() {
        val dir = newDir()
        File(dir, "db-key.bin").writeText("")
        assertTrue(hex64.matches(ChatDbKeyStore(dir, vault()).passphrase()))
    }

    @Test
    fun windowsDpapiRoundTripsTheKey() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true))
        val dir = newDir()
        val first = ChatDbKeyStore(dir, IdentityKeyVault.Dpapi).passphrase()
        assertFalse(String(File(dir, "db-key.bin").readBytes(), Charsets.ISO_8859_1).contains(first))
        assertEquals(first, ChatDbKeyStore(dir, IdentityKeyVault.Dpapi).passphrase())
    }
}
