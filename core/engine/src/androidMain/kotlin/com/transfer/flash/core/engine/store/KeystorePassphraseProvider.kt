package com.transfer.flash.core.engine.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.transfer.flash.core.persistence.db.PassphraseProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-wrapped SQLCipher passphrase, the default [PassphraseProvider] used by [com.transfer.flash.core.engine.Flash.create].
 *
 * On first run a 32-byte random passphrase is generated, encrypted with a non-exportable
 * AndroidKeyStore AES-GCM key ([KEY_ALIAS]) and the ciphertext (IV ‖ ct) stored in
 * SharedPreferences. Subsequent runs unwrap it. The plaintext passphrase never touches disk and
 * the wrapping key never leaves the TEE/StrongBox, so the on-disk DB is useless without this
 * device's keystore. AES-GCM keystore keys are available from API 23 (minSdk 24 — always here).
 *
 * The prefs file and key alias intentionally match the app-layer provider so a host that mixes
 * `Flash.create` with the legacy holder wiring unwraps the same passphrase (identical DB).
 */
public class KeystorePassphraseProvider(context: Context) : PassphraseProvider {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True when the most recent [passphrase] call had to mint a NEW passphrase: first run, or the
     * stored one could not be unwrapped (keystore key gone after a restore or a lock-screen change).
     * An existing database can never be opened with a minted passphrase, so callers that open a DB
     * must check this. `EncryptedDatabaseRecovery` does (audit B7).
     */
    @Volatile
    public var mintedNewPassphrase: Boolean = false
        private set

    override fun passphrase(): ByteArray {
        val stored = prefs.getString(KEY_WRAPPED, null)
        if (stored != null) {
            runCatching { return unwrap(stored).also { mintedNewPassphrase = false } }
            // Corrupt/rotated wrapper: fall through and re-seed (the caller must quarantine the DB).
        }
        val fresh = ByteArray(PASSPHRASE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_WRAPPED, wrap(fresh)).apply()
        mintedNewPassphrase = true
        return fresh
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain)
        val iv = cipher.iv
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val ct = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "flash_db_secure"
        const val KEY_WRAPPED = "wrapped_passphrase"
        const val KEY_ALIAS = "flash_db_passphrase_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PASSPHRASE_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
