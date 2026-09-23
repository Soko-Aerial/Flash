package com.transfer.flash.core.security.testutil

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.security.trust.SecretSealer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Host-test stand-in for `KeystoreSecretSealer`: the same AES-256-GCM construction with an in-memory key,
 * since AndroidKeyStore does not exist on the JVM. [loseKey] simulates a keystore wipe or a restore onto
 * another device; [failSealing] simulates a keystore that refuses to seal.
 */
class SoftwareSecretSealer : SecretSealer {
    private var key: SecretKey? = newKey()
    var failSealing: Boolean = false

    fun loseKey() {
        key = newKey()
    }

    override fun seal(plain: ByteArray): String {
        check(!failSealing) { "keystore unavailable" }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encode(iv + cipher.doFinal(plain))
    }

    override fun open(sealed: String): ByteArray? = runCatching {
        val raw = Base64.decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        cipher.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
