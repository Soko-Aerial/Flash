package com.transfer.flash.server.config

import com.transfer.flash.server.auth.SecureFiles
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.engine.EngineSSLConnectorBuilder
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/**
 * TLS material for `tlsMode=self` (SEC-06): the hub terminates TLS itself with either the keystore the operator supplies
 * (`FLASH_TLS_KEYSTORE` + `FLASH_TLS_KEYSTORE_PASSWORD`) or a self-signed P-256 certificate it generates once under
 * `<dataDir>/tls/` and renews before it expires. The certificate's SHA-256 [fingerprint] is logged at start-up so a
 * client can pin it.
 */
class TlsMaterial(
    val keyStore: KeyStore,
    val alias: String,
    private val password: CharArray,
    val fingerprint: String,
    val generated: Boolean,
    val notAfter: Long,
) {
    fun password(): CharArray = password.copyOf()
}

object TlsSupport {
    private val log = LoggerFactory.getLogger(TlsSupport::class.java)

    private const val ALIAS = "flash-hub"
    private const val VALID_DAYS = 365L
    private const val RENEW_BEFORE_DAYS = 30L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Loads or creates the TLS key material for [config], or returns null when the hub does not terminate TLS. */
    fun loadOrCreate(config: ServerConfig, nowMs: Long = System.currentTimeMillis()): TlsMaterial? {
        if (config.tlsMode != ServerConfig.TLS_SELF) return null
        val supplied = config.tlsKeystoreFile
        if (supplied != null) return loadSupplied(File(supplied), config.tlsKeystorePassword)
        return loadOrGenerate(config, nowMs)
    }

    private fun loadSupplied(file: File, password: String?): TlsMaterial {
        require(file.isFile) { "FLASH_TLS_KEYSTORE '${file.path}' does not exist" }
        require(!password.isNullOrEmpty()) { "FLASH_TLS_KEYSTORE_PASSWORD is required with FLASH_TLS_KEYSTORE" }
        val type = if (file.name.endsWith(".jks", ignoreCase = true)) "JKS" else "PKCS12"
        val store = KeyStore.getInstance(type)
        file.inputStream().use { store.load(it, password.toCharArray()) }
        val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
            ?: throw IllegalArgumentException("The keystore '${file.name}' has no private key entry")
        val cert = store.getCertificate(alias) as X509Certificate
        return TlsMaterial(store, alias, password.toCharArray(), fingerprintOf(cert), generated = false, notAfter = cert.notAfter.time)
    }

    private fun loadOrGenerate(config: ServerConfig, nowMs: Long): TlsMaterial {
        val dir = File(config.dataDir, "tls").also { it.mkdirs() }
        val p12 = File(dir, "hub-tls.p12")
        val passFile = File(dir, "hub-tls.pass")
        if (p12.isFile && passFile.isFile) {
            val password = passFile.readText().trim().toCharArray()
            val existing = runCatching {
                val store = KeyStore.getInstance("PKCS12")
                p12.inputStream().use { store.load(it, password) }
                val cert = store.getCertificate(ALIAS) as X509Certificate
                Triple(store, cert, password)
            }.getOrNull()
            if (existing != null && existing.second.notAfter.time - nowMs > RENEW_BEFORE_DAYS * DAY_MS) {
                return TlsMaterial(existing.first, ALIAS, existing.third, fingerprintOf(existing.second), generated = false, notAfter = existing.second.notAfter.time)
            }
            log.warn("The self-signed TLS certificate is missing, unreadable or expires within {} days: generating a new one.", RENEW_BEFORE_DAYS)
        }

        val password = ByteArray(24).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }.toCharArray()
        val hostNames = hostNamesFor(config)
        val (store, cert) = generateSelfSigned(hostNames, password, nowMs)
        val out = java.io.ByteArrayOutputStream()
        store.store(out, password)
        SecureFiles.writeOwnerOnly(p12.toPath(), out.toByteArray())
        SecureFiles.writeOwnerOnly(passFile.toPath(), String(password).encodeToByteArray())
        return TlsMaterial(store, ALIAS, password, fingerprintOf(cert), generated = true, notAfter = cert.notAfter.time)
    }

    /** Names the self-signed certificate answers for: the configured hosts plus localhost. */
    internal fun hostNamesFor(config: ServerConfig): List<String> {
        val names = linkedSetOf("localhost", "127.0.0.1")
        runCatching { URI(config.externalUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }?.let { names += it }
        if (config.host != "0.0.0.0") names += config.host
        ServerConfig.detectLanIpv4()?.let { names += it }
        return names.toList()
    }

    internal fun generateSelfSigned(hostNames: List<String>, password: CharArray, nowMs: Long): Pair<KeyStore, X509Certificate> {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = generator.generateKeyPair()
        val subject = X500Name("CN=Flash Hub")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
            Date(nowMs - 60_000),
            Date(nowMs + VALID_DAYS * DAY_MS),
            subject,
            pair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val sans = hostNames.map { name ->
            val isIp = name.matches(Regex("^[0-9.]+$")) || name.contains(':')
            GeneralName(if (isIp) GeneralName.iPAddress else GeneralName.dNSName, name)
        }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(ALIAS, pair.private, password, arrayOf(cert))
        return store to cert
    }

    fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /**
     * The listeners of the hub: the public connector on `host:port` and, unless the console is also served on the
     * public port, a management connector on `adminHost:adminPort`. Both use TLS in `self` mode.
     */
    fun connectors(config: ServerConfig, tls: TlsMaterial?): List<EngineConnectorConfig> {
        fun plain(host: String, port: Int): EngineConnectorConfig = EngineConnectorBuilder().apply {
            this.host = host
            this.port = port
        }

        fun secure(host: String, port: Int, material: TlsMaterial): EngineConnectorConfig =
            EngineSSLConnectorBuilder(material.keyStore, material.alias, { material.password() }, { material.password() }).apply {
                this.host = host
                this.port = port
            }

        fun connector(host: String, port: Int) = if (tls != null) secure(host, port, tls) else plain(host, port)
        val list = mutableListOf(connector(config.host, config.port))
        if (config.adminPort != config.port) list += connector(config.adminHost, config.adminPort)
        return list
    }
}
