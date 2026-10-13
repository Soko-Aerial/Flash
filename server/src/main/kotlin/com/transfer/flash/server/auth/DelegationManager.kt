package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** Where the hub is in its delegation lifecycle (SEC-04). */
enum class DelegationStatus {
    /** No `delegation.cert` yet: the hub only serves setup pages and health; enrolment is refused. */
    AWAITING,

    /** A valid delegation is loaded and inside its window. */
    ACTIVE,

    /** A delegation is loaded but its window has ended: enrolment and renewal are refused until it is replaced. */
    EXPIRED,

    /** The file exists but is unusable (bad signature, wrong delegate key, wrong root...). Fail closed. */
    INVALID,
}

/** Outcome of [DelegationManager.load] / [DelegationManager.reload]. */
sealed interface DelegationLoad {
    data class Loaded(val cert: DelegationCert, val replaced: Boolean) : DelegationLoad
    data object Missing : DelegationLoad
    data class Rejected(val reason: String) : DelegationLoad
}

/**
 * Owns the hub's [DelegationCert]: loads `delegation.cert`, validates it against the hub's own delegate key and the
 * pinned root, tracks its expiry and hot-reloads a replacement without a restart (SEC-04).
 *
 * A delegation is only accepted when:
 * - its root signature verifies and it delegates to THIS hub's key ([delegatePublicKey]);
 * - when a root pin exists ([rootPin]) its root key hashes to the pin (a swapped file cannot move the hub to another root);
 * - for a REPLACEMENT: same root, same delegate key, and a strictly higher serial (no rollback to an older delegation);
 * - never older than the highest serial this hub has EVER accepted ([highestAcceptedSerial], persisted: REV-16), so a restart does not
 *   forget the rollback guard and let an older, still valid delegation back in.
 *
 * @param rootPin the pinned root fingerprint (upper-case hex SHA-256 of the root SPKI) or null before the first activation.
 * @param onRootAdopted called once when the first valid delegation fixes the root (the caller persists the pin).
 * @param requireRootPin when true the FIRST delegation is refused unless a pin already exists (`FLASH_ROOT_FINGERPRINT`): trust on first
 *   use of whatever file appears is exactly what an attacker with write access to the data directory would use (REV-16).
 * @param highestAcceptedSerial the highest delegation serial accepted so far (persisted), or null.
 * @param onSerialAccepted called with each accepted serial so the caller can persist the new maximum.
 */
class DelegationManager(
    private val file: Path,
    private val delegatePublicKey: String,
    private val rootPin: () -> String? = { null },
    private val onRootAdopted: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val requireRootPin: Boolean = false,
    private val highestAcceptedSerial: () -> Long? = { null },
    private val onSerialAccepted: (Long) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(DelegationManager::class.java)

    @Volatile
    var current: DelegationCert? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val warned = HashSet<Pair<Long, Int>>()

    val status: DelegationStatus
        get() {
            val cert = current
            return when {
                cert == null -> if (lastError != null) DelegationStatus.INVALID else DelegationStatus.AWAITING
                !cert.isValidAt(clock(), 0L) -> DelegationStatus.EXPIRED
                else -> DelegationStatus.ACTIVE
            }
        }

    /** True when the hub may issue certificates right now. */
    val canIssue: Boolean get() = status == DelegationStatus.ACTIVE

    /** Whole days until the delegation ends (negative once expired), or null with no delegation. */
    fun daysLeft(): Long? = current?.let { Math.floorDiv(it.validTo - clock(), DAY_MS) }

    /** Loads the file at startup (or after it appears). */
    @Synchronized
    fun load(): DelegationLoad = read(initial = true)

    /** Re-reads the file and swaps in a replacement delegation (admin `reload`, or a file drop). */
    @Synchronized
    fun reload(): DelegationLoad = read(initial = current == null)

    /** Installs a delegation supplied directly (the admin API upload): validates, writes the file, swaps. */
    @Synchronized
    fun install(cert: DelegationCert): DelegationLoad {
        val verdict = validate(cert, initial = current == null)
        if (verdict != null) {
            lastError = if (current == null) verdict else lastError
            return DelegationLoad.Rejected(verdict)
        }
        SecureFiles.writeOwnerOnly(file, HubRestCodec.encode(cert).encodeToByteArray())
        return adopt(cert)
    }

    /** Logs a warning when 14 or 3 days remain (once per threshold per delegation). Returns the messages logged. */
    @Synchronized
    fun checkExpiryWarnings(): List<String> {
        val cert = current ?: return emptyList()
        val days = daysLeft() ?: return emptyList()
        val messages = ArrayList<String>()
        for (threshold in WARN_DAYS) {
            if (days < threshold && warned.add(cert.serial to threshold)) {
                val msg = if (days < 0) {
                    "Hub delegation #${cert.serial} EXPIRED ${-days} day(s) ago: enrolment and renewal are refused. Issue a new delegation with the offline root key."
                } else {
                    "Hub delegation #${cert.serial} expires in $days day(s) (threshold $threshold). Issue a replacement with the offline root key and drop it at $file."
                }
                log.warn(msg)
                messages += msg
            }
        }
        return messages
    }

    private fun read(initial: Boolean): DelegationLoad {
        if (!Files.exists(file)) {
            return if (current != null) DelegationLoad.Rejected("delegation file disappeared; keeping the loaded one") else DelegationLoad.Missing
        }
        val cert = try {
            HubRestCodec.decode<DelegationCert>(Files.readString(file))
        } catch (e: Exception) {
            val reason = "delegation file is unreadable or corrupt"
            if (current == null) lastError = reason
            log.error("{}: {}", reason, file)
            return DelegationLoad.Rejected(reason)
        }
        if (current != null && cert == current) return DelegationLoad.Loaded(cert, replaced = false)
        val verdict = validate(cert, initial)
        if (verdict != null) {
            if (current == null) lastError = verdict
            log.error("delegation file rejected: {}", verdict)
            return DelegationLoad.Rejected(verdict)
        }
        return adopt(cert)
    }

    private fun adopt(cert: DelegationCert): DelegationLoad {
        val replaced = current != null
        if (rootPin() == null) {
            onRootAdopted(rootFingerprint(cert))
            log.warn(
                "TRUST ON FIRST USE: the hub adopted root key {} from the first delegation it saw. Compare it with the fingerprint " +
                    "'keytool init-root' printed; set FLASH_ROOT_FINGERPRINT (and FLASH_REQUIRE_ROOT_PIN=true) so this can not happen.",
                rootFingerprint(cert),
            )
        }
        onSerialAccepted(cert.serial)
        current = cert
        lastError = null
        log.info(
            "Hub delegation #{} {} (valid until {}, {} day(s) left, maxDevices {}, scopes {})",
            cert.serial, if (replaced) "REPLACED" else "loaded", cert.validTo, daysLeft(), cert.maxDevices, cert.scopes,
        )
        return DelegationLoad.Loaded(cert, replaced)
    }

    /** Null when acceptable, otherwise a fixed, log-safe reason. */
    private fun validate(cert: DelegationCert, initial: Boolean): String? {
        if (!cert.verifySignature(JvmEcdsaVerifier)) return "delegation signature does not verify against its root key"
        if (cert.delegatePublicKey != delegatePublicKey) return "delegation is for a different server key"
        if (!Spki.isValidP256(cert.rootPublicKey)) return "delegation root key is not a P-256 key"
        val pin = rootPin()
        if (pin == null && requireRootPin) {
            return "no root fingerprint is pinned: set FLASH_ROOT_FINGERPRINT to the value 'keytool init-root' printed, then restart"
        }
        highestAcceptedSerial()?.let { if (cert.serial < it) return "delegation serial ${cert.serial} is older than #$it, which this hub accepted before" }
        if (pin != null && !pin.equals(rootFingerprint(cert), ignoreCase = true)) {
            return "delegation root does not match this hub's pinned root (${pin.take(16)}...)"
        }
        val existing = current
        if (!initial && existing != null) {
            if (cert.rootPublicKey != existing.rootPublicKey) return "replacement delegation has a different root key"
            if (cert.serial <= existing.serial) return "replacement delegation serial ${cert.serial} is not higher than ${existing.serial}"
        }
        if (cert.validTo <= cert.validFrom) return "delegation window is empty"
        return null
    }

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000
        val WARN_DAYS: List<Int> = listOf(14, 3)

        fun rootFingerprint(cert: DelegationCert): String = FlashCrypto.sha256FingerprintHex(cert.rootPublicKey)
    }
}
