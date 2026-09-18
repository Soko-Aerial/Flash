package com.transfer.flash.core.network.tls

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * JVM-platform certificate and KeyManager generator for TLS 1.3 peer connections (C4.1).
 *
 * Generates self-signed X.509 v3 certificates wrapping EC P-256 public keys and initializes
 * in-memory PKCS#12 [KeyManager]s to serve peer handshakes.
 */
public object FlashCertMaker {

    /** Generates a self-signed X.509 certificate for [publicKey] signed by [signingKey]. */
    public fun selfSigned(
        cn: String,
        publicKey: PublicKey,
        signingKey: PrivateKey,
        validityDays: Long = 365L * 25,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val dn = if (cn.trim().uppercase().startsWith("CN=")) cn.trim() else "CN=$cn"
        val issuer = org.bouncycastle.asn1.x500.X500Name(dn)
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now).or(BigInteger.ONE),
            Date(now - 24L * 60 * 60 * 1000), // 1 day backdate for clock skew tolerance
            Date(now + validityDays * 24L * 60 * 60 * 1000),
            issuer,
            publicKey,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(signingKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /** Builds an array of [KeyManager]s wrapping [keyPair] and a self-signed certificate. */
    public fun createKeyManagers(
        keyPair: KeyPair,
        cn: String = "CN=Flash Node",
        alias: String = "flash-identity",
    ): Array<KeyManager> {
        val cert = selfSigned(cn, keyPair.public, keyPair.private)
        val password = "flash-node-tls".toCharArray()
        val ks = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(alias, keyPair.private, password, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)
        return kmf.keyManagers
    }

    /** Generates a fresh EC P-256 keypair (`secp256r1`). */
    public fun newEcKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
}
