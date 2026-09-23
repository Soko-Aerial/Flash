package com.transfer.flash.core.security.trust

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.transfer.flash.core.common.protocol.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (per-peer E2E session keys) before they are written to SharedPreferences
 * (audit 2026-09-23, S4). The store never writes a secret it could not seal.
 */
public interface SecretSealer {
    /** Returns an opaque, printable sealed form of [plain]. Throws when sealing is impossible. */
    public fun seal(plain: ByteArray): String

    /**
     * Opens a value produced by [seal], or returns null when it cannot be opened, e.g. the sealing
     * key no longer exists (restored backup, keystore wiped by a lock-screen change).
     */
    public fun open(sealed: String): ByteArray?
}

/**
 * [SecretSealer] backed by a non-exportable AndroidKeyStore AES-256-GCM key, the same construction
 * `KeystorePassphraseProvider` uses for the SQLCipher passphrase. Sealed form: Base64(IV ‖ ciphertext).
 *
 * The keystore is touched lazily, on the first [seal]/[open], so building a trust store costs nothing
 * and host tests that never store a session key never reach AndroidKeyStore.
 */
public class KeystoreSecretSealer(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretSealer {

    override fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true))
        val ct = cipher.doFinal(plain)
        val iv = cipher.iv
        return Base64.encode(iv + ct)
    }

    override fun open(sealed: String): ByteArray? = runCatching {
        val key = secretKey(createIfMissing = false) ?: return null
        val raw = Base64.decode(sealed)
        if (raw.size <= GCM_IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, GCM_IV_BYTES)))
        cipher.doFinal(raw, GCM_IV_BYTES, raw.size - GCM_IV_BYTES)
    }.getOrNull()

    private fun secretKey(createIfMissing: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!createIfMissing) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    public companion object {
        public const val DEFAULT_KEY_ALIAS: String = "flash_trust_seal_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
