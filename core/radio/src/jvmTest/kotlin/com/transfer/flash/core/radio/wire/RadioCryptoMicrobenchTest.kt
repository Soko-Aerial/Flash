package com.transfer.flash.core.radio.wire

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Throughput measurements for the radio crypto wrappers (`OPT-O26-07`).
 *
 * A **measurement harness**, not a performance target: it prints `PERF-R` lines so a forced run
 * (`--rerun-tasks --no-build-cache`) gives the before/after figure recorded as EXP-132. The assertions are
 * sanity only (the operations must still produce the right answer), so CPU contention cannot flake it.
 */
class RadioCryptoMicrobenchTest {

    private val crypto = JdkRadioCrypto()
    private val key = ByteArray(32) { (it * 3 + 1).toByte() }
    private val nonce = ByteArray(12) { (it * 5 + 2).toByte() }
    private val aad = ByteArray(25) { (it * 11 + 3).toByte() }
    private val plaintext = ByteArray(188) { (it * 13 + 4).toByte() }

    private inline fun time(name: String, iterations: Int, block: (Int) -> Unit) {
        repeat(minOf(iterations / 10 + 1, 5_000)) { block(it) } // warm up
        val t0 = System.nanoTime()
        repeat(iterations) { block(it) }
        val nsPerOp = (System.nanoTime() - t0).toDouble() / iterations
        val opsPerSecond = if (nsPerOp <= 0.0) Double.MAX_VALUE else 1_000_000_000.0 / nsPerOp
        println("PERF-R O26-07 $name iterations=$iterations nsPerOp=${"%.1f".format(nsPerOp)} opsPerSecond=${"%.0f".format(opsPerSecond)}")
    }

    @Test
    fun aeadSealAndOpenThroughput() {
        var sealedBytes = 0
        time("aeadSeal(188B)", 20_000) { sealedBytes = crypto.aeadSeal(key, nonce, aad, plaintext).size }
        assertTrue(sealedBytes == 188 + RadioWire.TAG_SIZE, "GCM adds a 16-byte tag: $sealedBytes")

        val sealed = crypto.aeadSeal(key, nonce, aad, plaintext)
        var opened = 0
        time("aeadOpen(188B)", 20_000) { opened = crypto.aeadOpen(key, nonce, aad, sealed)?.size ?: -1 }
        assertTrue(opened == 188)
    }

    @Test
    fun hmacAndDigestThroughput() {
        time("hmacSha256(17B)", 20_000) { crypto.hmacSha256(key, byteArrayOf(1, 2, 3)) }
        time("sha256(91B)", 20_000) { crypto.sha256(plaintext) }
    }

    @Test
    fun providerLookupCostWithAndWithoutTheRememberedProvider() {
        // OPT-O26-07's actual mechanism, measured in ONE process: the same two ways of getting an instance, so CPU
        // contention hits both readings alike and the ratio is meaningful even on a loaded machine.
        val cipherProvider = Cipher.getInstance("AES/GCM/NoPadding").provider
        time("Cipher.getInstance(scan)", 20_000) { Cipher.getInstance("AES/GCM/NoPadding") }
        time("Cipher.getInstance(provider)", 20_000) { Cipher.getInstance("AES/GCM/NoPadding", cipherProvider) }

        val macProvider = Mac.getInstance("HmacSHA256").provider
        time("Mac.getInstance(scan)", 20_000) { Mac.getInstance("HmacSHA256") }
        time("Mac.getInstance(provider)", 20_000) { Mac.getInstance("HmacSHA256", macProvider) }

        val digestProvider = MessageDigest.getInstance("SHA-256").provider
        time("MessageDigest.getInstance(scan)", 20_000) { MessageDigest.getInstance("SHA-256") }
        time("MessageDigest.getInstance(provider)", 20_000) { MessageDigest.getInstance("SHA-256", digestProvider) }

        val keyFactoryProvider = KeyFactory.getInstance("EC").provider
        time("KeyFactory.getInstance(scan)", 20_000) { KeyFactory.getInstance("EC") }
        time("KeyFactory.getInstance(provider)", 20_000) { KeyFactory.getInstance("EC", keyFactoryProvider) }

        val signatureProvider = Signature.getInstance("SHA256withECDSA").provider
        time("Signature.getInstance(scan)", 20_000) { Signature.getInstance("SHA256withECDSA") }
        time("Signature.getInstance(provider)", 20_000) { Signature.getInstance("SHA256withECDSA", signatureProvider) }
    }

    @Test
    fun ecdsaVerifyThroughput() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = kpg.generateKeyPair()
        val scheme = JdkRadioSignatureScheme(pair.private)
        val publicKey = pair.public.encoded
        val data = ByteArray(64) { (it * 17 + 5).toByte() }
        val signature = scheme.sign(data)
        val verifier = JdkRadioSignatureScheme()
        assertTrue(verifier.verify(publicKey, data, signature), "a good signature verifies")
        time("ecdsaVerify(P-256, key cached)", 400) { verifier.verify(publicKey, data, signature) }
        assertTrue(!verifier.verify(publicKey, data, signature.reversedArray()), "a bad signature is refused")
        assertNotNull(verifier)
    }

    @Test
    fun theCostOfReParsingTheSenderKeyOnEveryFrame() {
        // OPT-O26-07's second half, measured both ways in one process: the same verify with the parsed key remembered
        // (a long-lived scheme) and with a scheme that has never seen the key (what every frame used to do).
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = kpg.generateKeyPair()
        val publicKey = pair.public.encoded
        val data = ByteArray(64) { (it * 23 + 1).toByte() }
        val signature = JdkRadioSignatureScheme(pair.private).sign(data)

        val cached = JdkRadioSignatureScheme().apply { verify(publicKey, data, signature) }
        time("ecdsaVerify(SPKI cached)", 400) { cached.verify(publicKey, data, signature) }
        time("ecdsaVerify(SPKI re-parsed)", 400) {
            JdkRadioSignatureScheme().verify(publicKey, data, signature)
        }

        // What the parse alone costs, so the saving above can be attributed.
        val factory = KeyFactory.getInstance("EC")
        val spec = X509EncodedKeySpec(publicKey)
        time("KeyFactory.generatePublic(SPKI)", 2_000) { factory.generatePublic(spec) }
    }
}
