package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import org.slf4j.LoggerFactory

/**
 * The hub's own key material on disk (SEC-04, ADR-111).
 *
 * The server holds ONLY its delegate key pair (`server-identity.pk8` / `.pub`, owner-only) and a pre-signed
 * [DelegationCert] (`delegation.cert`). The organisation root private key never belongs here: it is created and used
 * offline with `keytool init-root` / `keytool issue-delegation`.
 *
 * Loading is fail-closed: a corrupt, incomplete or mismatching key file is an error, never a reason to generate a new
 * key (that would silently invalidate every certificate the hub ever signed).
 */
class HubKeyStore(val dataDir: Path) {
    private val log = LoggerFactory.getLogger(HubKeyStore::class.java)

    val delegatePrivFile: Path = dataDir.resolve("server-identity.pk8")
    val delegatePubFile: Path = dataDir.resolve("server-identity.pub")
    val delegationFile: Path = dataDir.resolve("delegation.cert")
    val legacyRootPrivFile: Path = dataDir.resolve("root-identity.pk8")
    val legacyRootPubFile: Path = dataDir.resolve("root-identity.pub")

    /** Result of [loadOrCreateDelegateKey]. */
    class DelegateKey(val pair: KeyPair, val created: Boolean) {
        val publicKey: String get() = pair.toBase64Spki()
    }

    /**
     * Loads the delegate key or, ONLY when neither file exists, generates one. Throws [KeyFileException] otherwise.
     */
    fun loadOrCreateDelegateKey(): DelegateKey {
        Files.createDirectories(dataDir)
        KeyFiles.loadPair(delegatePrivFile, delegatePubFile)?.let {
            log.info("Delegate key loaded from {} (public key fingerprint {})", delegatePrivFile.fileName, fingerprint(it))
            return DelegateKey(it, created = false)
        }
        val pair = FlashCrypto.generateEcKeyPair()
        KeyFiles.writePair(delegatePrivFile, delegatePubFile, pair)
        log.warn("Generated a NEW delegate key (public key fingerprint {}). It has no authority until the offline root signs a delegation for it.", fingerprint(pair))
        return DelegateKey(pair, created = true)
    }

    /**
     * The production guard: a root private key in the data dir means the previous (insecure) layout is still in use.
     * Returns the file path when found, so [com.transfer.flash.server.Main] can refuse to start without the dev flag.
     */
    fun misplacedRootKey(): Path? = legacyRootPrivFile.takeIf { Files.exists(it) }

    /** Text the operator must run on the machine that holds the root key. */
    fun issueDelegationHint(delegatePublicKey: String): String =
        "java -jar flash-hub.jar keytool issue-delegation --root-key <offline-dir>/root-identity.pk8 " +
            "--server-pub $delegatePublicKey --scopes enrol:member,enrol:dept_admin,revoke:member --max-devices 1000 --valid-days 90 " +
            "--out delegation.cert   (then copy delegation.cert to ${delegationFile.toAbsolutePath()})"

    /**
     * LOCAL DEVELOPMENT ONLY (`--dev-embedded-root`): keeps a root key in the data dir and issues the hub's delegation
     * itself. This is exactly what SEC-04 forbids in production, so it is loud and opt-in.
     */
    fun devEmbeddedRootDelegation(delegate: DelegateKey, nowMs: Long = System.currentTimeMillis(), validDays: Long = 30): DelegationCert {
        log.error("##################################################################################")
        log.error("# DEV EMBEDDED ROOT KEY IN USE ({}). NEVER USE THIS IN PRODUCTION (SEC-04).", legacyRootPrivFile)
        log.error("##################################################################################")
        val root = KeyFiles.loadPair(legacyRootPrivFile, legacyRootPubFile)
            ?: FlashCrypto.generateEcKeyPair().also { KeyFiles.writePair(legacyRootPrivFile, legacyRootPubFile, it) }
        val existing = runCatching { HubRestCodec.decode<DelegationCert>(Files.readString(delegationFile)) }.getOrNull()
        if (existing != null && existing.delegatePublicKey == delegate.publicKey &&
            existing.rootPublicKey == root.toBase64Spki() && existing.validTo - nowMs > 2 * DelegationManager.DAY_MS &&
            DEV_SCOPES.all { it in existing.scopes }
        ) {
            return existing
        }
        val cert = DelegationCert.issue(
            rootPrivateKey = root.private,
            rootPublicKey = root.toBase64Spki(),
            delegatePublicKey = delegate.publicKey,
            scopes = DEV_SCOPES,
            validFrom = nowMs - 10_000,
            validTo = nowMs + validDays * DelegationManager.DAY_MS,
            maxDevices = 1000,
            serial = (existing?.serial ?: 0L) + 1L,
        )
        SecureFiles.writeOwnerOnly(delegationFile, HubRestCodec.encode(cert).encodeToByteArray())
        return cert
    }

    /** Dev delegation scopes: members and department heads (the demo invites a head); never `enrol:admin`. */
    private val DEV_SCOPES = setOf(
        DelegationCert.SCOPE_ENROL_MEMBER,
        DelegationCert.SCOPE_ENROL_DEPT_ADMIN,
        DelegationCert.SCOPE_REVOKE_MEMBER,
    )

    private fun fingerprint(pair: KeyPair): String = FlashCrypto.sha256FingerprintHex(pair.toBase64Spki()).take(16)
}
