package com.transfer.flash.core.radio.wire

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The JCA providers that serve this module's primitives, resolved **once** per process (OPT-O26-07).
 *
 * `Cipher.getInstance("AES/GCM/NoPadding")` walks the provider table on every call, and that table is guarded by a
 * global security lock on Android. Which provider answers a transformation does not change while the process lives, so
 * it is learned from one real instance and then passed explicitly.
 *
 * Only the **provider** is remembered. Every operation still gets a fresh `Cipher`/`Mac`/`Signature`, initialised with
 * its own key, so no key material outlives the call - deliberately not the cached keyed `Cipher`/`Mac` ThreadLocals that
 * were considered and rejected in the kernel stream (see `logs/streams/kernel/INTEGRATOR-REVIEW.md`).
 *
 * A byte-identical copy lives in `androidMain`, next to the class it serves.
 */
private object RadioJca {
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val HMAC = "HmacSHA256"
    private const val SHA_256 = "SHA-256"
    private const val ECDSA = "SHA256withECDSA"
    private const val EC = "EC"

    private val aesGcm: Provider? by lazy { runCatching { Cipher.getInstance(AES_GCM).provider }.getOrNull() }
    private val hmac: Provider? by lazy { runCatching { Mac.getInstance(HMAC).provider }.getOrNull() }
    private val sha256: Provider? by lazy { runCatching { MessageDigest.getInstance(SHA_256).provider }.getOrNull() }
    private val ecdsa: Provider? by lazy { runCatching { Signature.getInstance(ECDSA).provider }.getOrNull() }
    private val ecKeys: Provider? by lazy { runCatching { KeyFactory.getInstance(EC).provider }.getOrNull() }

    /**
     * Prefers a fresh instance from the remembered provider and falls back to the plain lookup, so a provider removed
     * at runtime degrades to the old behaviour instead of failing.
     */
    private inline fun <T> pick(provider: Provider?, fromProvider: (Provider) -> T, plain: () -> T): T {
        if (provider != null) {
            try {
                return fromProvider(provider)
            } catch (_: GeneralSecurityException) {
                // fall through to the unqualified lookup
            }
        }
        return plain()
    }

    fun cipher(): Cipher = pick(aesGcm, { Cipher.getInstance(AES_GCM, it) }, { Cipher.getInstance(AES_GCM) })

    fun mac(): Mac = pick(hmac, { Mac.getInstance(HMAC, it) }, { Mac.getInstance(HMAC) })

    fun digest(): MessageDigest = pick(sha256, { MessageDigest.getInstance(SHA_256, it) }, { MessageDigest.getInstance(SHA_256) })

    fun signature(): Signature = pick(ecdsa, { Signature.getInstance(ECDSA, it) }, { Signature.getInstance(ECDSA) })

    fun keyFactory(): KeyFactory = pick(ecKeys, { KeyFactory.getInstance(EC, it) }, { KeyFactory.getInstance(EC) })
}

/**
 * [RadioCrypto] on `javax.crypto`: HMAC-SHA-256, SHA-256, AES-256-GCM (128-bit tag), SecureRandom. Same algorithms as
 * `:core:security`; kept in this module because those primitives are `internal` there (see [RadioCrypto]).
 * A byte-identical copy lives in `jvmMain`.
 */
public class JdkRadioCrypto(private val random: SecureRandom = SecureRandom()) : RadioCrypto {
    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = RadioJca.mac()
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun sha256(data: ByteArray): ByteArray = RadioJca.digest().digest(data)

    override fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = RadioJca.cipher()
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    override fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? =
        try {
            val c = RadioJca.cipher()
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            c.updateAAD(aad)
            c.doFinal(ciphertextAndTag)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}

/**
 * [RadioSignatureScheme] on `java.security` (`SHA256withECDSA`, P-256) with the DER to raw `r||s` conversion.
 * [privateKey] may be null for a verify-only instance. A byte-identical copy lives in `jvmMain`.
 *
 * OPT-O26-07: a verified sender's SPKI used to be re-parsed into a `PublicKey` on **every** frame (`KeyFactory` lookup,
 * ASN.1 decode, key-material checks). The parsed keys are now remembered for the few senders a station talks to, keyed
 * by the SPKI bytes rather than by peer id, so a caller cannot present bytes that do not match the cached key.
 */
public class JdkRadioSignatureScheme(private val privateKey: PrivateKey? = null) : RadioSignatureScheme {
    /** SPKI bytes as a map key: content equality, private copy. */
    private class SpkiKey(bytes: ByteArray) {
        private val copy: ByteArray = bytes.copyOf()
        private val hash: Int = copy.contentHashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is SpkiKey && other.copy.contentEquals(copy)
    }

    private val parsedKeys = LinkedHashMap<SpkiKey, PublicKey>()

    override fun sign(data: ByteArray): ByteArray {
        val key = privateKey ?: error("verify-only instance")
        val s = RadioJca.signature()
        s.initSign(key)
        s.update(data)
        return EcdsaRawSignature.derToRaw(s.sign()) ?: error("JCA produced a malformed DER signature")
    }

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        try {
            val der = EcdsaRawSignature.rawToDer(signature) ?: return false
            val pub = publicKeyFor(publicKey) ?: return false
            val s = RadioJca.signature()
            s.initVerify(pub)
            s.update(data)
            s.verify(der)
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    /** The parsed key for [spki], parsed once. Null when the bytes are not a usable EC public key. */
    private fun publicKeyFor(spki: ByteArray): PublicKey? {
        val key = SpkiKey(spki)
        synchronized(parsedKeys) { parsedKeys[key] }?.let { return it }
        val parsed = try {
            RadioJca.keyFactory().generatePublic(X509EncodedKeySpec(spki))
        } catch (_: GeneralSecurityException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        synchronized(parsedKeys) {
            parsedKeys[key] = parsed
            // `removeEldestEntry` is the obvious tool here, but an anonymous LinkedHashMap subclass trips a Kotlin
            // 2.x K2 backend bug ("No override for IR_EXTERNAL_JAVA_DECLARATION_STUB name:get"), so it is done by hand.
            while (parsedKeys.size > MAX_PARSED_KEYS) {
                val eldest = parsedKeys.keys.firstOrNull() ?: break
                parsedKeys.remove(eldest)
            }
        }
        return parsed
    }

    private companion object {
        /** Senders whose key is kept parsed: the peers a station actually hears signed frames from. */
        const val MAX_PARSED_KEYS: Int = 16
    }
}
