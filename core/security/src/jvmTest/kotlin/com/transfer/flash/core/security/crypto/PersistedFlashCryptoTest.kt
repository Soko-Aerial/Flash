@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import com.transfer.flash.core.security.identity.IdentityKeyVault
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 26 (ADR-035): pins `PersistedFlashCrypto`'s persistence contract —
 * generate-once/restart-survival, the loud in-memory degradation on vault failure, and the
 * version-byte migration contract. Runs against `IdentityKeyVault.PassThrough` so the FILE
 * and FORMAT logic is exercised on any OS; the DPAPI vault itself gets a separate round-trip
 * test that only runs on Windows (`IdentityKeyVaultTest`).
 */
class PersistedFlashCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()

    private fun stateDir(): File = tmp.newFolder("state")

    private fun keyFile(stateDir: File): File = PersistedFlashCrypto.keyFile(stateDir)

    @Test
    fun `generates once and survives a restart - the whole point of P2`() {
        val stateDir = stateDir()
        val first = PersistedFlashCrypto(stateDir, IdentityKeyVault.PassThrough)
        val firstPublic = first.identityPublicKeyEncoded
        val message = "pairing handshake payload".toByteArray()
        val signature = first.sign(message)

        // "Restart": a brand-new instance over the same state dir must load, not re-mint.
        val second = PersistedFlashCrypto(stateDir, IdentityKeyVault.PassThrough)
        assertArrayEquals(firstPublic, second.identityPublicKeyEncoded)
        assertTrue(second.verify(signature, message, firstPublic))
    }

    @Test
    fun `corrupted vault degrades LOUDLY to a fresh in-memory identity and preserves the file`() {
        val stateDir = stateDir()
        val first = PersistedFlashCrypto(stateDir, IdentityKeyVault.PassThrough)
        val originalPublic = first.identityPublicKeyEncoded
        val file = keyFile(stateDir)
        assertTrue(file.isFile)

        // Corrupt the embedded PKCS#8 DER *structure* byte (file byte 5 = the SEQUENCE tag of
        // the private key). Deliberately NOT a mid-payload flip: PKCS#8 carries no checksum,
        // so a random payload byte can corrupt "successfully" into a different-but-valid key —
        // only the DPAPI vault authenticates payload bytes; the file layer's job is to reject
        // structurally impossible data and degrade.
        val bytes = file.readBytes()
        bytes[5] = (bytes[5].toInt() xor 0x5A).toByte()
        file.writeBytes(bytes)

        val logs = mutableListOf<String>()
        val degraded = PersistedFlashCrypto(stateDir, IdentityKeyVault.PassThrough) { logs += it }
        // Fresh in-memory identity — a DIFFERENT public key.
        assertNotEquals(originalPublic.contentToString(), degraded.identityPublicKeyEncoded.contentToString())
        assertFalse(degraded.identityPublicKeyEncoded.contentEquals(originalPublic))
        // LOUD, not silent.
        assertTrue(logs.any { it.contains("IDENTITY DEGRADED") && it.contains("IN-MEMORY") })
        // The corrupted file was NOT overwritten — it may still be recoverable.
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `unknown format version is rejected - the migration contract`() {
        val stateDir = stateDir()
        val file = keyFile(stateDir)
        file.parentFile?.mkdirs()
        val future = byteArrayOf(0x02) + ByteArray(64) { 0x41 }
        file.writeBytes(future)

        val logs = mutableListOf<String>()
        val degraded = PersistedFlashCrypto(stateDir, IdentityKeyVault.PassThrough) { logs += it }
        // Touch the identity first: it is lazy, and the degradation log fires on first access.
        degraded.identityPublicKeyEncoded
        assertTrue(logs.any { it.contains("IDENTITY DEGRADED") })
        // The unknown-version file was left exactly as found.
        assertArrayEquals(future, file.readBytes())
    }

    @Test
    fun `ephemeral session-key path is identical to the shared machinery`() {
        val crypto = PersistedFlashCrypto(stateDir(), IdentityKeyVault.PassThrough)
        val self = crypto.generateEphemeralEcdhKeyPair()
        val peer = crypto.generateEphemeralEcdhKeyPair()
        val ours = crypto.ecdhSessionKey(self, peer.publicKeyEncoded)
        val theirs = crypto.ecdhSessionKey(peer, self.publicKeyEncoded)
        // ECDH symmetry: both sides derive the same 32-byte session key.
        assertEquals(32, ours.size)
        assertArrayEquals(ours, theirs)
    }
}
