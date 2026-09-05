package com.transfer.flash.core.network.tls

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * TEST-SCOPE ONLY (src/test): software self-signed certificates for plain-JVM
 * TLS handshake tests. Built on BouncyCastle — a testImplementation dependency,
 * so it never ships in the AAR/APK.
 *
 * Production cert creation is entirely different: the Android platform
 * keystore generates the self-signed cert at identity-key creation time
 * (see docs/security.md §2 and :core:security KeystoreFlashCrypto).
 *
 * Decision record: the original hand-rolled DER/TBS encoder in this file
 * produced malformed certificates ("Too short" from the JDK parser); rather
 * than debug ASN.1 byte-by-byte, tests use BC per AGENTS.md documented-
 * dependency rule (zero production footprint).
 */
object SoftwareCertMaker {

    /** Fresh EC P-256 keypair. */
    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    /** Self-signed X509 cert binding [publicKey] to [cn], valid now for 1 day.
     * Accepts bare names ("flash-a") or full RDNs ("CN=flash-a"). */
    fun selfSigned(cn: String, publicKey: java.security.PublicKey, signingKey: java.security.PrivateKey): X509Certificate {
        val now = System.currentTimeMillis()
        val dn = if (cn.trim().uppercase().startsWith("CN=")) cn.trim() else "CN=$cn"
        val issuer = org.bouncycastle.asn1.x500.X500Name(dn)
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now).or(BigInteger.ONE),
            Date(now - 60_000L),
            Date(now + 24L * 60 * 60 * 1000),
            issuer,
            publicKey,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .build(signingKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /** Convenience bundle: keypair + matching self-signed cert + ready KeyManagers. */
    class TestIdentity(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        val keyManagers: Array<javax.net.ssl.KeyManager>,
    )

    /** Fresh identity: EC P-256 keypair + self-signed cert + REAL KeyManagers
     * (a PKCS12 keystore holding the key/cert) so an SSLServerSocket built from
     * them can actually present the certificate during handshakes. */
    fun newIdentity(cn: String = "CN=flash-test"): TestIdentity {
        val kp = newKeyPair()
        val cert = selfSigned(cn, kp.public, kp.private)
        val password = "flash-test".toCharArray()
        val ks = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("test-key", kp.private, password, arrayOf(cert))
        }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm(),
        )
        kmf.init(ks, password)
        return TestIdentity(kp, cert, kmf.keyManagers)
    }

    /** Deterministic-ish entropy source for callers that want one. */
    fun secureRandom(): SecureRandom = SecureRandom()
}
