@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.security.identity.IdentityKeyVault
import java.io.File

/**
 * A desktop `FlashCrypto` whose identity keypair is GENERATED ONCE and PERSISTED — the P2
 * answer (ADR-035), replacing the restart-amnesia of [SoftwareFlashCrypto] for shipped
 * desktop builds. The ephemeral-ECDH + HKDF session-key path is the same shared code
 * ([EcP256Ops]); ONLY identity handling differs.
 *
 * ## At-rest format (`<stateDir>/identity/id-key.bin`)
 *
 * ```text
 * byte  0     : format version — currently 0x01
 * bytes 1..   : [IdentityKeyVault].protect(inner)
 *               where inner = 4-byte big-endian private-key length || PKCS#8 private || X.509 public
 * ```
 *
 * The version byte is the migration contract: a future macOS Keychain / Linux keyring vault
 * (or a DPAPI→Keychain migration) bumps it and migrates; an unknown version is REJECTED, not
 * best-effort parsed. The public key travels inside the protected blob (it is not secret, but
 * binding the pair atomically means a truncated or mismatched write can never yield a private
 * key whose public half disagrees).
 *
 * ## Failure behaviour (the load-bearing choice)
 *
 * If the file exists but cannot be read back — wrong user, corrupted blob, unknown version —
 * this class **degrades to an in-memory identity and logs LOUDLY, and does NOT overwrite the
 * file.** Overwriting would silently destroy the one artifact the user's TOFU trust hangs
 * from; a fresh in-memory key is honest about being new, and the preserved file may still be
 * recoverable on the user's real OS session.
 *
 * ## Security tier (ADR-035, repeated here on purpose)
 *
 * Better than restart-amnesia, weaker than Android's non-exportable hardware key: same-user
 * malware can unprotect the blob via DPAPI. Stated in the desktop README when this ships.
 *
 * `public` + [FlashInternalApi] (UuidIdGenerator precedent): constructed by `:desktop`.
 */
@FlashInternalApi
public class PersistedFlashCrypto(
    private val stateDir: File,
    private val vault: IdentityKeyVault = IdentityKeyVault.Dpapi,
    private val log: (String) -> Unit = { FlashLog.w(TAG, it) },
) : FlashCrypto {

    /**
     * The persisted identity keypair, or a fresh in-memory pair if the vault was unreadable.
     * `lazy` because construction happens on the engine-composition thread and the file I/O
     * is one short read; every later access is memory-only.
     */
    private val identity: FlashEcKeyPair by lazy { loadOrGenerate() }

    override val identityPublicKeyEncoded: ByteArray
        get() = identity.publicKeyEncoded

    override fun sign(data: ByteArray): ByteArray = EcP256Ops.sign(identity.privateKey, data)

    override fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean =
        EcP256Ops.verify(peerPublicKey, data, signature)

    override fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair = EcP256Ops.ephemeralKeyPair()

    override fun ecdhSessionKey(
        selfEphemeral: FlashEcKeyPair,
        peerEphemeralPublicKey: ByteArray,
    ): ByteArray =
        EcP256Ops.sessionKeyFromSharedSecret(
            EcP256Ops.agreedSecret(selfEphemeral.privateKey, peerEphemeralPublicKey)
        )

    // ------------------------------------------------------------------ persistence

    private fun loadOrGenerate(): FlashEcKeyPair {
        val file = keyFile(stateDir)
        if (!file.isFile) {
            val generated = EcP256Ops.ephemeralKeyPair() // same generator SoftwareFlashCrypto uses
            persist(file, generated)
            FlashLog.i(TAG, "identity keypair generated and persisted to ${file.path}")
            return generated
        }
        return runCatching { load(file) }.getOrElse { failure ->
            log(
                "IDENTITY DEGRADED: persisted identity key at ${file.path} could not be read " +
                    "(${failure.message}). Falling back to a FRESH IN-MEMORY identity — existing " +
                    "TOFU trust will not match. The file was NOT overwritten and may recover on " +
                    "the user's regular OS session.",
            )
            EcP256Ops.ephemeralKeyPair()
        }
    }

    private fun load(file: File): FlashEcKeyPair {
        val bytes = file.readBytes()
        require(bytes.isNotEmpty() && bytes[0] == FORMAT_VERSION) {
            "unknown identity-key format version ${bytes.getOrElse(0) { -1 }}"
        }
        val inner = vault.unprotect(bytes.copyOfRange(1, bytes.size))
        require(inner.size > 4) { "identity blob too short" }
        val privateLen = ((inner[0].toInt() and 0xFF) shl 24) or
            ((inner[1].toInt() and 0xFF) shl 16) or
            ((inner[2].toInt() and 0xFF) shl 8) or
            (inner[3].toInt() and 0xFF)
        require(privateLen in 1..inner.size - 4) { "corrupt identity blob: private length $privateLen" }
        val privateBytes = inner.copyOfRange(4, 4 + privateLen)
        val publicBytes = inner.copyOfRange(4 + privateLen, inner.size)
        val privateKey = ecP256ParsePrivateKeyPkcs8(privateBytes)
        // Zeroize the intermediates now that the Key object holds the material (best-effort —
        // the JVM does not guarantee collection, but copying into a zeroed buffer is the
        // documented mitigation).
        privateBytes.fill(0)
        inner.fill(0)
        return FlashEcKeyPair(publicKeyEncoded = publicBytes, privateKey = privateKey)
    }

    private fun persist(file: File, keyPair: FlashEcKeyPair) {
        val privateBytes = ecP256ExportPrivateKeyPkcs8(keyPair.privateKey)
        val publicBytes = keyPair.publicKeyEncoded
        val inner = ByteArray(4 + privateBytes.size + publicBytes.size)
        inner[0] = (privateBytes.size ushr 24).toByte()
        inner[1] = (privateBytes.size ushr 16).toByte()
        inner[2] = (privateBytes.size ushr 8).toByte()
        inner[3] = privateBytes.size.toByte()
        privateBytes.copyInto(inner, 4)
        publicBytes.copyInto(inner, 4 + privateBytes.size)
        privateBytes.fill(0)
        // The vault gets its OWN copy: a vault may keep or alias its input (the pass-through
        // test vault returns it as-is), so zeroing `inner` must happen on a buffer the vault
        // does not reference. DPAPI copies internally on encrypt either way.
        val blob = vault.protect(inner.copyOf())
        inner.fill(0)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(FORMAT_VERSION) + blob)
        // Atomic-ish swap: a half-written id-key.bin would be indistinguishable from corruption
        // and would trip the loud-degradation path on next start.
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeBytes(byteArrayOf(FORMAT_VERSION) + blob) // last resort on rename failure
        }
    }

    public companion object {
        /** Format version of the at-rest file. Bump on any layout change; never re-read silently. */
        public const val FORMAT_VERSION: Byte = 0x01

        internal fun keyFile(stateDir: File): File = File(File(stateDir, "identity"), "id-key.bin")

        private const val TAG = "SECURITY"
    }
}
