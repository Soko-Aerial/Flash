package com.transfer.flash.server.auth

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals small secrets that must live in the database (the administrators' authenticator seeds, REV-03) under a key that
 * lives only in an owner-only file next to the database, so a copy of `flash-hub.db` alone (a backup, a stray read) does
 * not reveal them. AES-256-GCM, a fresh 96-bit nonce per value, the context string bound as associated data so a value
 * cannot be moved to another column or row.
 *
 * Stored form: `enc1:` + base64(nonce || ciphertext+tag). A value without the prefix is a legacy plaintext and is returned as is by
 * [open] (the owner of the data re-seals it at startup, see `AdminAuthService.sealLegacySecrets`).
 *
 * The key file MUST be backed up separately from the database (docs/security.md): without it the sealed values are lost, and the
 * administrators re-enrol their authenticator through `admin-recovery`.
 */
class SecretBox private constructor(private val key: SecretKeySpec?, private val random: SecureRandom = SecureRandom()) {

    val isActive: Boolean get() = key != null

    fun seal(plain: String, context: String): String {
        val k = key ?: return plain
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().withoutPadding().encodeToString(nonce + ct)
    }

    /** The plain text of [stored]; a legacy plaintext (no prefix) comes back unchanged. Throws when a sealed value does not authenticate. */
    fun open(stored: String, context: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val k = key ?: throw SecurityException("a sealed value is in the database but no data key is configured")
        val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        require(raw.size > NONCE_BYTES) { "sealed value too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, NONCE_BYTES)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return try {
            String(cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw SecurityException("a sealed value does not authenticate: wrong data key or tampered database", e)
        }
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        const val PREFIX = "enc1:"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        /** No encryption (tests, and a hub that has no data directory). Values are stored as given. */
        val None: SecretBox = SecretBox(null)

        fun ofKey(key32: ByteArray): SecretBox {
            require(key32.size == 32) { "data key must be 32 bytes" }
            return SecretBox(SecretKeySpec(key32, "AES"))
        }

        /**
         * The box of [keyFile]: created (owner-only) on first use, loaded afterwards. A key file of the wrong size is an error, never
         * silently replaced (a replaced key makes every sealed value unreadable).
         */
        fun loadOrCreate(keyFile: Path): SecretBox {
            if (Files.exists(keyFile)) {
                val text = String(Files.readAllBytes(keyFile), Charsets.UTF_8).trim()
                val bytes = try {
                    Base64.getDecoder().decode(text)
                } catch (e: IllegalArgumentException) {
                    throw KeyFileException("data key file $keyFile is not valid base64", e)
                }
                if (bytes.size != 32) throw KeyFileException("data key file $keyFile must hold 32 bytes, has ${bytes.size}")
                if (!SecureFiles.isOwnerOnly(keyFile)) {
                    try {
                        SecureFiles.restrictToOwner(keyFile)
                    } catch (e: IOException) {
                        throw KeyFileException("data key file $keyFile is readable by others and could not be restricted", e)
                    }
                }
                return ofKey(bytes)
            }
            val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
            SecureFiles.writeOwnerOnly(keyFile, Base64.getEncoder().encode(fresh))
            return ofKey(fresh)
        }
    }
}
