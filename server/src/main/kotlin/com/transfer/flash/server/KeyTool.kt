package com.transfer.flash.server

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.HubScopes
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.DelegationManager
import com.transfer.flash.server.auth.KeyFileException
import com.transfer.flash.server.auth.KeyFiles
import com.transfer.flash.server.auth.RootKeyFile
import com.transfer.flash.server.auth.SecureFiles
import com.transfer.flash.server.auth.Spki
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.sha256FingerprintHex
import com.transfer.flash.server.auth.toBase64Spki
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Where the root key's pass-phrase comes from (a prompt in the CLI, a lambda in tests). Null = no pass-phrase. */
fun interface PassphraseSource {
    fun passphrase(prompt: String, confirm: Boolean): CharArray?
}

/** The operator made a mistake or the input is unsafe: a one-line message, exit code 2. Never carries key material. */
class KeyToolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The OFFLINE root-key tool (SEC-04, ADR-111): `keytool init-root`, `keytool issue-delegation`, `keytool rotate`.
 * Run it on a machine that is not the hub (ideally air-gapped); the hub only ever receives the resulting
 * `delegation.cert`. Nothing here prints a private key.
 *
 * Root directory layout (`--out` of init-root):
 * ```
 * root-identity.pk8       PKCS#8 private key, Base64, or an encrypted envelope (PBKDF2 >= 600k + AES-256-GCM); owner-only
 * root-identity.pub       the public key (SPKI, Base64)
 * delegation-serial.txt   the last delegation serial issued: monotonic, never reused
 * ```
 */
class KeyTool(
    private val out: PrintStream = System.out,
    private val passphrases: PassphraseSource = PassphraseSource { _, _ -> null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val kdfIterations: Int = RootKeyFile.MIN_ITERATIONS,
) {

    class InitResult(val privFile: Path, val pubFile: Path, val fingerprint: String, val encrypted: Boolean)

    class IssueResult(val cert: DelegationCert, val outFile: Path, val rootFingerprint: String)

    /** Creates a new organisation root key in [outDir]. Refuses to touch an existing one. */
    fun initRoot(outDir: Path, encrypt: Boolean = true): InitResult {
        Files.createDirectories(outDir)
        val priv = outDir.resolve(ROOT_PRIV)
        val pub = outDir.resolve(ROOT_PUB)
        if (Files.exists(priv) || Files.exists(pub)) {
            throw KeyToolException("$outDir already contains a root key. init-root never overwrites one; choose an empty directory.")
        }
        val pass = if (encrypt) passphrases.passphrase("Pass-phrase for the new root key (8+ characters)", true) else null
        if (encrypt && pass == null) {
            throw KeyToolException("A pass-phrase is required (FLASH_ROOT_PASSPHRASE or an interactive prompt). Pass --no-passphrase to store the key unencrypted: then the DISK must be encrypted and offline.")
        }
        val pair = FlashCrypto.generateEcKeyPair()
        val pkcs8 = Base64.getEncoder().encode(pair.private.encoded)
        val stored = if (pass != null) {
            try {
                RootKeyFile.encrypt(pkcs8, pass, kdfIterations)
            } catch (e: IllegalArgumentException) {
                throw KeyToolException(e.message ?: "invalid pass-phrase")
            }
        } else {
            pkcs8
        }
        SecureFiles.writeOwnerOnly(priv, stored)
        SecureFiles.writeOwnerOnly(pub, Base64.getEncoder().encode(pair.public.encoded))
        val fingerprint = FlashCrypto.sha256FingerprintHex(pair.toBase64Spki())
        out.println("Root key created in ${outDir.toAbsolutePath()}")
        out.println("  private key : $ROOT_PRIV (${if (pass != null) "encrypted with your pass-phrase" else "NOT encrypted"}, owner-only)")
        out.println("  public key  : $ROOT_PUB")
        out.println("  ROOT FINGERPRINT (SHA-256): $fingerprint")
        out.println("Write the fingerprint down and keep it with the key. Back the directory up OFFLINE; without the private key the")
        out.println("organisation can never issue another delegation. Do NOT copy the private key to the hub or into a container image.")
        return InitResult(priv, pub, fingerprint, encrypted = pass != null)
    }

    /**
     * Signs a delegation for the hub's delegate key. [rootKeyFile] is the private key file; the public key is read from
     * `root-identity.pub` beside it (and must match). The serial is the last issued plus one unless [serial] is given,
     * which must then be higher than the last issued one (no rollback, no reuse).
     */
    fun issueDelegation(
        rootKeyFile: Path,
        serverPub: String,
        scopes: Set<String>,
        maxDevices: Int,
        validDays: Long,
        serial: Long? = null,
        outFile: Path = Paths.get("delegation.cert"),
    ): IssueResult {
        require(scopes.isNotEmpty()) { "--scopes must name at least one scope" }
        val unknown = scopes - ALLOWED_SCOPES
        if (unknown.isNotEmpty()) throw KeyToolException("Unknown scope(s) ${unknown.sorted()}; allowed: ${ALLOWED_SCOPES.sorted()}")
        if (maxDevices !in 1..MAX_DEVICES) throw KeyToolException("--max-devices must be between 1 and $MAX_DEVICES")
        if (validDays !in 1..MAX_VALID_DAYS) throw KeyToolException("--valid-days must be between 1 and $MAX_VALID_DAYS")
        val delegate = resolveServerKey(serverPub)

        val root = loadRoot(rootKeyFile)
        val rootPub = root.toBase64Spki()
        val rootDir = rootKeyFile.toAbsolutePath().parent
        val serialFile = rootDir.resolve(SERIAL_FILE)
        val fromFile = if (Files.exists(serialFile)) {
            Files.readString(serialFile).trim().toLongOrNull() ?: throw KeyToolException("$SERIAL_FILE is corrupt; restore it from backup (it prevents a serial being reused).")
        } else {
            null
        }
        // REV-16: a lost serial file must not restart numbering at 1 (the hub refuses a lower serial, or worse, an old delegation with
        // a free number is accepted somewhere). Whatever delegations this key already issued next to it, or at the output path, count.
        val onDisk = delegationSerialsNear(rootDir, outFile)
        val last = maxOf(fromFile ?: 0L, onDisk.maxOrNull() ?: 0L)
        if (fromFile == null && onDisk.isNotEmpty() && serial == null) {
            out.println("WARNING: $SERIAL_FILE is missing; continuing after the highest serial found in existing delegation files ($last).")
        }
        val next = serial ?: (last + 1)
        if (next <= last) throw KeyToolException("Serial $next is not higher than the last issued serial $last.")

        val now = clock()
        val cert = DelegationCert.issue(
            rootPrivateKey = root.private,
            rootPublicKey = rootPub,
            delegatePublicKey = delegate,
            scopes = scopes,
            validFrom = now - 60_000,
            validTo = now + validDays * DelegationManager.DAY_MS,
            maxDevices = maxDevices,
            serial = next,
        )
        // Record the serial BEFORE the certificate leaves this machine: a crash in between wastes a number, never reuses one.
        try {
            SecureFiles.writeOwnerOnly(serialFile, next.toString().encodeToByteArray())
            Files.createDirectories(outFile.toAbsolutePath().parent)
            Files.write(outFile, HubRestCodec.encode(cert).encodeToByteArray())
        } catch (e: java.io.IOException) {
            throw KeyToolException("Could not write ${e.message?.take(120) ?: "the output"}. Nothing was issued; if the serial file was updated that number is simply skipped.")
        }
        val fingerprint = DelegationManager.rootFingerprint(cert)
        out.println("Delegation issued: ${outFile.toAbsolutePath()}")
        out.println("  serial          : $next")
        out.println("  root fingerprint: $fingerprint")
        out.println("  delegate key    : ${FlashCrypto.sha256FingerprintHex(delegate).take(16)}...")
        out.println("  scopes          : ${scopes.sorted().joinToString(", ")}")
        out.println("  max devices     : $maxDevices")
        out.println("  valid until     : ${java.time.Instant.ofEpochMilli(cert.validTo)} ($validDays days)")
        out.println("Copy this file to the hub as <data-dir>/delegation.cert (or FLASH_DELEGATION_FILE). A running hub picks it up with")
        out.println("'Reload delegation' in the console. Renew it before it expires: the hub warns at 14 and 3 days.")
        return IssueResult(cert, outFile, fingerprint)
    }

    /** Serials of the delegation certificates found beside the root key (`*.cert`) and at [outFile]; unreadable files are ignored. */
    private fun delegationSerialsNear(rootDir: Path, outFile: Path): List<Long> {
        val candidates = LinkedHashSet<Path>()
        runCatching {
            Files.newDirectoryStream(rootDir, "*.cert").use { stream -> stream.forEach { candidates.add(it.toAbsolutePath()) } }
        }
        if (Files.isRegularFile(outFile)) candidates.add(outFile.toAbsolutePath())
        return candidates.mapNotNull { f ->
            runCatching { HubRestCodec.decode<DelegationCert>(Files.readString(f)).serial }.getOrNull()
        }
    }

    class RevokeResult(val list: RevocationList, val outFile: Path)

    /**
     * Signs, with the ROOT key, a revocation list that revokes [delegationSerials] (and optionally device serials / ids), so the hub
     * and every device stop trusting a delegation whose hub key is lost or compromised (REV-04). The list REPLACES the one clients
     * hold, so it must contain everything already revoked: pass the list the hub serves today as [baseFile]
     * (`curl https://<hub>/api/v1/revocations > current.json`); its entries are carried over and its version is raised by one.
     * Issue the replacement delegation FIRST (a higher serial), then revoke the old serial.
     */
    fun revoke(
        rootKeyFile: Path,
        delegationSerials: Set<Long>,
        deviceSerials: Set<Long> = emptySet(),
        deviceIds: Set<String> = emptySet(),
        baseFile: Path? = null,
        version: Long? = null,
        outFile: Path = Paths.get("revocations.signed"),
    ): RevokeResult {
        if (delegationSerials.isEmpty() && deviceSerials.isEmpty() && deviceIds.isEmpty()) {
            throw KeyToolException("Name something to revoke: --delegation-serial, --serial or --device-id.")
        }
        val root = loadRoot(rootKeyFile)
        val rootPub = root.toBase64Spki()
        val base: RevocationList? = baseFile?.let {
            if (!Files.isRegularFile(it)) throw KeyToolException("Base list not found: $it")
            HubRestCodec.decodeOrNull<RevocationList>(Files.readString(it), 16 * 1024 * 1024)
                ?: throw KeyToolException("$it is not a revocation list (save the body of GET /api/v1/revocations).")
        }
        val nextVersion = version ?: ((base?.version ?: 0L) + 1)
        if (base != null && nextVersion <= base.version) throw KeyToolException("--version $nextVersion is not higher than the base list's ${base.version}.")
        val lastIssued = rootKeyFile.toAbsolutePath().parent.resolve(SERIAL_FILE).let { f ->
            if (Files.exists(f)) Files.readString(f).trim().toLongOrNull() else null
        }
        // ADR-129: the list is issued in the hashed format. A plain (v2) base contributes its ids, a hashed one its hashes: the ids
        // named now are hashed for this organisation's audience, so nothing readable ends up in the public list.
        val audience = RevocationList.audienceOf(rootPub)
        if (base != null && base.isHashed && base.audience != audience) {
            throw KeyToolException("The base list is for another organisation (audience ${base.audience.take(12)}...).")
        }
        val list = RevocationList.issue(
            issuerPrivateKey = root.private,
            issuerPublicKey = rootPub,
            version = nextVersion,
            revokedSerials = (base?.revokedSerials ?: emptySet()) + deviceSerials,
            revokedDeviceIds = (base?.revokedDeviceIds ?: emptySet()) + deviceIds,
            revokedDelegationSerials = (base?.revokedDelegationSerials ?: emptySet()) + delegationSerials,
            audience = audience,
            revokedDeviceHashes = base?.revokedDeviceHashes ?: emptySet(),
        )
        Files.createDirectories(outFile.toAbsolutePath().parent)
        Files.write(outFile, HubRestCodec.encode(list).encodeToByteArray())
        out.println("Revocation list signed: ${outFile.toAbsolutePath()}")
        out.println("  version               : ${list.version}")
        out.println("  revoked delegations   : ${list.revokedDelegationSerials.sorted().joinToString(", ").ifEmpty { "-" }}")
        out.println("  revoked device serials: ${list.revokedSerials.size}, devices (hashed): ${list.revokedDeviceHashes.size}")
        if (base == null) {
            out.println("  WARNING: no --base list given. This list replaces the one devices hold: any device already revoked by the hub is NOT")
            out.println("           in it. Re-run with --base <the body of GET /api/v1/revocations> unless the hub has revoked nothing yet.")
        }
        if (lastIssued != null && delegationSerials.any { it >= lastIssued }) {
            out.println("  WARNING: you revoke delegation serial ${delegationSerials.filter { it >= lastIssued }.max()}, the newest this key ever issued (last issued: $lastIssued).")
            out.println("           Issue the replacement delegation with a higher serial first, or the hub will have no delegation left.")
        }
        out.println("Install it: copy it to <data-dir>/revocations/ on the hub and restart, or POST it to /admin/api/v1/revocations/install (admin + step-up).")
        out.println("The hub refuses to issue under a revoked delegation, and every certificate issued under it stops working at once.")
        return RevokeResult(list, outFile)
    }

    /** Explains how to replace the root key. Rotation is a deliberate, organisation-wide event, so it is not automated. */
    fun rotateHelp() {
        out.println(
            """
            Rotating the organisation ROOT key
            ----------------------------------
            A new root key means every device must trust a new root fingerprint, so it is a re-enrolment of the organisation,
            not a routine renewal. Routine renewal is a NEW DELEGATION under the SAME root (keytool issue-delegation with a
            higher serial): do that before the current delegation expires.

            Only rotate the root when it may have been exposed:
              1. keytool init-root --out <new-dir>            (a new key and fingerprint)
              2. keytool issue-delegation --root-key <new-dir>/root-identity.pk8 ...   (a delegation for the hub under the new root)
              3. Stop the hub, remove the pinned root (hub_meta key 'root.fingerprint') or set FLASH_ROOT_FINGERPRINT to the new one,
                 copy the new delegation.cert, start the hub.
              4. Distribute the new fingerprint and re-enrol every device with a fresh invitation. Devices pinned to the old root
                 refuse the hub, by design.
              5. Destroy the old root key and its backups.

            Replacing only the HUB's delegate key (the hub machine was compromised, the root is safe): delete server-identity.pk8 and
            .pub on the hub, start it (it generates a new delegate key and waits), run issue-delegation for the new public key with a
            HIGHER serial, then revoke the old delegation serial with a root-signed revocation list:
              keytool revoke --root-key <dir>/root-identity.pk8 --delegation-serial <old serial> --base current.json --out revocations.signed
            (current.json is the body of GET /api/v1/revocations; copy revocations.signed into <data-dir>/revocations/ on the hub).
            Devices enrolled under the old delegation stop working at once and enrol again with a fresh invitation.
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun loadRoot(file: Path): KeyPair {
        if (!Files.isRegularFile(file)) throw KeyToolException("Root key file not found: $file")
        val raw = Files.readAllBytes(file)
        val pkcs8 = if (RootKeyFile.isEncrypted(raw)) {
            val pass = passphrases.passphrase("Pass-phrase of the root key", false)
                ?: throw KeyToolException("The root key is encrypted; supply its pass-phrase (FLASH_ROOT_PASSPHRASE or the prompt).")
            try {
                RootKeyFile.decrypt(raw, pass)
            } catch (e: KeyFileException) {
                throw KeyToolException("Wrong pass-phrase or damaged root key file.")
            }
        } else {
            raw
        }
        val pubFile = file.resolveSibling(ROOT_PUB)
        if (!Files.isRegularFile(pubFile)) throw KeyToolException("$ROOT_PUB must be beside the private key (it is created by init-root).")
        val pair = try {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8.decodeToString().trim())))
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(pubFile).trim())))
            KeyPair(pub, priv)
        } catch (e: Exception) {
            throw KeyToolException("The root key files are corrupt.")
        }
        if (!KeyFiles.matches(pair)) throw KeyToolException("$ROOT_PUB does not belong to the private key; refusing to sign.")
        if (Spki.parseP256(pair.toBase64Spki()) == null) throw KeyToolException("The root key is not a P-256 key.")
        return pair
    }

    /** `--server-pub` is a file path holding the Base64 SPKI, or the Base64 text itself. */
    private fun resolveServerKey(value: String): String {
        val text = value.trim()
        val candidate = runCatching { Paths.get(text) }.getOrNull()
        val b64 = if (candidate != null && text.length < 400 && Files.isRegularFile(candidate)) Files.readString(candidate).trim() else text
        if (Spki.parseP256(b64) == null) throw KeyToolException("--server-pub is not a valid P-256 public key (Base64 SPKI as printed by the hub).")
        return b64
    }

    companion object {
        const val ROOT_PRIV = "root-identity.pk8"
        const val ROOT_PUB = "root-identity.pub"
        const val SERIAL_FILE = "delegation-serial.txt"
        const val MAX_DEVICES = 100_000
        const val MAX_VALID_DAYS = 366L
        val ALLOWED_SCOPES: Set<String> = setOf(HubScopes.ENROL_MEMBER, HubScopes.ENROL_DEPT_ADMIN, HubScopes.ENROL_ADMIN, HubScopes.REVOKE_MEMBER)

        /** Parses the `keytool` argument vector (after the word `keytool`). Returns the process exit code. */
        fun run(args: List<String>, tool: KeyTool, err: PrintStream = System.err): Int {
            val command = args.firstOrNull()
            val options = parseOptions(args.drop(1))
            return try {
                when (command) {
                    "init-root" -> {
                        tool.initRoot(Paths.get(options.required("out")), encrypt = !options.flag("no-passphrase"))
                        0
                    }
                    "issue-delegation" -> {
                        tool.issueDelegation(
                            rootKeyFile = Paths.get(options.required("root-key")),
                            serverPub = options.required("server-pub"),
                            scopes = options.required("scopes").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                            maxDevices = options.int("max-devices", 1000),
                            validDays = options.long("valid-days", 90),
                            serial = options.opt("serial")?.let { it.toLongOrNull() ?: throw KeyToolException("--serial must be a number") },
                            outFile = Paths.get(options.opt("out") ?: "delegation.cert"),
                        )
                        0
                    }
                    "revoke" -> {
                        tool.revoke(
                            rootKeyFile = Paths.get(options.required("root-key")),
                            delegationSerials = options.longs("delegation-serial"),
                            deviceSerials = options.longs("serial"),
                            deviceIds = options.opt("device-id")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
                            baseFile = options.opt("base")?.let { Paths.get(it) },
                            version = options.opt("version")?.let { it.toLongOrNull() ?: throw KeyToolException("--version must be a number") },
                            outFile = Paths.get(options.opt("out") ?: "revocations.signed"),
                        )
                        0
                    }
                    "rotate" -> {
                        tool.rotateHelp()
                        0
                    }
                    else -> {
                        err.println("Usage: keytool init-root --out <dir> [--no-passphrase]")
                        err.println("       keytool issue-delegation --root-key <file> --server-pub <file|base64> --scopes enrol:member,enrol:dept_admin,revoke:member")
                        err.println("                                [--max-devices N] [--valid-days N] [--serial N] [--out delegation.cert]")
                        err.println("                                (--valid-days must be at least FLASH_DEVICE_CERT_VALIDITY_DAYS + the renewal grace; default 90)")
                        err.println("       keytool revoke --root-key <file> --delegation-serial N[,N] [--serial N,..] [--device-id a,..] [--base current.json] [--out revocations.signed]")
                        err.println("       keytool rotate")
                        2
                    }
                }
            } catch (e: KeyToolException) {
                err.println("keytool: ${e.message}")
                2
            } catch (e: IllegalArgumentException) {
                err.println("keytool: ${e.message}")
                2
            }
        }

        private class Options(val map: Map<String, String>) {
            fun opt(name: String): String? = map[name]
            fun flag(name: String): Boolean = map[name]?.let { it == "true" } ?: false
            fun required(name: String): String = map[name] ?: throw KeyToolException("--$name is required")
            fun int(name: String, default: Int): Int = map[name]?.let { it.toIntOrNull() ?: throw KeyToolException("--$name must be a number") } ?: default
            fun longs(name: String): Set<Long> = map[name]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.map { it.toLongOrNull() ?: throw KeyToolException("--$name must be numbers separated by commas") }?.toSet() ?: emptySet()
            fun long(name: String, default: Long): Long = map[name]?.let { it.toLongOrNull() ?: throw KeyToolException("--$name must be a number") } ?: default
        }

        private fun parseOptions(args: List<String>): Options {
            val map = LinkedHashMap<String, String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a.startsWith("--")) {
                    val eq = a.indexOf('=')
                    when {
                        eq != -1 -> map[a.substring(2, eq)] = a.substring(eq + 1)
                        i + 1 < args.size && !args[i + 1].startsWith("--") -> {
                            map[a.substring(2)] = args[i + 1]
                            i++
                        }
                        else -> map[a.substring(2)] = "true"
                    }
                }
                i++
            }
            return Options(map)
        }
    }
}
