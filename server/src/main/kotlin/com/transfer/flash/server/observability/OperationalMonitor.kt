package com.transfer.flash.server.observability

import java.io.File
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.round

/**
 * Snapshot of filesystem capacity on the Hub's data directory (HR11).
 *
 * Read synchronously off the Files/FileStore API when `GET /health/detail` or `GET /metrics`
 * runs (both guarded by [ManagementGuard]).
 */
public data class DiskUsage(
    public val totalBytes: Long,
    public val usableBytes: Long,
) {
    public val usedPercent: Double
        get() = if (totalBytes <= 0L) 0.0 else ((totalBytes - usableBytes).coerceAtLeast(0L).toDouble() / totalBytes.toDouble()) * 100.0

    public val usedRatio: Double
        get() = (usedPercent / 100.0).coerceIn(0.0, 1.0)
}

/**
 * Documented warning and critical thresholds for Hub operational monitoring (HR11).
 *
 * Defaults align with the production runbook in `docs/deploy/HUB-SERVER-DEPLOYMENT.md`:
 * - **Quarantined/unreadable mailbox rows:** any row >= 1 is WARN (data loss or key mismatch risk); >= 10 is CRITICAL.
 * - **Oldest queued mail age:** >= 72 h (3 days) is WARN (prolonged offline backlog or client catch-up stall); >= 144 h (6 days, inside default 7 d TTL) is CRITICAL.
 * - **Disk pressure:** >= 80% used OR <= 512 MiB free is WARN; >= 92% used OR <= 128 MiB free is CRITICAL.
 * - **Delegation / TLS certificate expiry:** <= 14 days remaining is WARN; <= 3 days remaining is CRITICAL.
 */
public data class OperationalThresholds(
    public val quarantineWarnCount: Long = 1L,
    public val quarantineCriticalCount: Long = 10L,
    public val oldestMailWarnSeconds: Long = 3L * 24L * 3600L,
    public val oldestMailCriticalSeconds: Long = 6L * 24L * 3600L,
    public val diskUsedWarnPercent: Double = 80.0,
    public val diskUsedCriticalPercent: Double = 92.0,
    public val diskUsedWarnCeilingBytes: Long = 4L * 1024L * 1024L * 1024L,
    public val diskUsedCriticalCeilingBytes: Long = 1024L * 1024L * 1024L,
    public val diskFreeWarnBytes: Long = 512L * 1024L * 1024L,
    public val diskFreeCriticalBytes: Long = 128L * 1024L * 1024L,
    public val delegationWarnDays: Long = 14L,
    public val delegationCriticalDays: Long = 3L,
    public val tlsCertWarnDays: Long = 14L,
    public val tlsCertCriticalDays: Long = 3L,
)

public enum class AlertSeverity(public val wire: String, public val levelValue: Int) {
    WARN("warn", 1),
    CRITICAL("critical", 2),
}

public data class OperationalAlert(
    public val id: String,
    public val severity: AlertSeverity,
    public val summary: String,
    public val action: String,
)

/**
 * Evaluates actionable operational alerts without logging secrets, keys, device names, or payloads (HR11).
 */
public object OperationalMonitor {

    public const val ALERT_UNREADABLE_QUEUE: String = "unreadable_queue"
    public const val ALERT_OLDEST_QUEUED_MAIL: String = "oldest_queued_mail"
    public const val ALERT_DISK_PRESSURE: String = "disk_pressure"
    public const val ALERT_DELEGATION_EXPIRY: String = "delegation_expiring"
    public const val ALERT_TLS_CERT_EXPIRY: String = "tls_cert_expiring"

    public fun probeDirectory(dataDir: File?): DiskUsage? {
        if (dataDir == null) return null
        return runCatching {
            val existing = generateSequence(dataDir) { it.parentFile }.firstOrNull { it.exists() } ?: return null
            val store = Files.getFileStore(existing.toPath())
            val total = max(0L, store.totalSpace)
            val usable = max(0L, store.usableSpace)
            DiskUsage(totalBytes = total, usableBytes = usable)
        }.getOrNull()
    }

    public fun evaluate(
        quarantinedRows: Long,
        oldestMailAgeSeconds: Long,
        disk: DiskUsage?,
        delegationDaysRemaining: Long?,
        tlsDaysRemaining: Long?,
        thresholds: OperationalThresholds = OperationalThresholds(),
    ): List<OperationalAlert> {
        val alerts = ArrayList<OperationalAlert>(5)

        if (quarantinedRows >= thresholds.quarantineCriticalCount) {
            alerts += OperationalAlert(
                id = ALERT_UNREADABLE_QUEUE,
                severity = AlertSeverity.CRITICAL,
                summary = "$quarantinedRows unreadable mailbox row(s) quarantined",
                action = "Stop the hub immediately, verify data.key matches the snapshot, or restore from the last clean backup.",
            )
        } else if (quarantinedRows >= thresholds.quarantineWarnCount) {
            alerts += OperationalAlert(
                id = ALERT_UNREADABLE_QUEUE,
                severity = AlertSeverity.WARN,
                summary = "$quarantinedRows unreadable mailbox row(s) quarantined",
                action = "Inspect server logs for MailboxQueue quarantine entries and confirm data.key integrity before restarting.",
            )
        }

        if (oldestMailAgeSeconds >= thresholds.oldestMailCriticalSeconds) {
            alerts += OperationalAlert(
                id = ALERT_OLDEST_QUEUED_MAIL,
                severity = AlertSeverity.CRITICAL,
                summary = "Oldest queued message is ${oldestMailAgeSeconds}s old (near TTL expiration)",
                action = "Check offline devices in Console -> Devices, relay health, and sweeper logs before queued mail expires.",
            )
        } else if (oldestMailAgeSeconds >= thresholds.oldestMailWarnSeconds) {
            alerts += OperationalAlert(
                id = ALERT_OLDEST_QUEUED_MAIL,
                severity = AlertSeverity.WARN,
                summary = "Oldest queued message is ${oldestMailAgeSeconds}s old",
                action = "Review offline devices or stuck relay drains in Console -> Devices.",
            )
        }

        if (disk != null && disk.totalBytes > 0L) {
            val pct = round(disk.usedPercent * 10.0) / 10.0
            val usableMb = disk.usableBytes / (1024L * 1024L)
            if (disk.usableBytes <= thresholds.diskFreeCriticalBytes ||
                (disk.usedPercent >= thresholds.diskUsedCriticalPercent && disk.usableBytes <= thresholds.diskUsedCriticalCeilingBytes)
            ) {
                alerts += OperationalAlert(
                    id = ALERT_DISK_PRESSURE,
                    severity = AlertSeverity.CRITICAL,
                    summary = "Data disk usage is $pct% ($usableMb MiB free)",
                    action = "Lower fileRetentionDays or globalStoredBytes in Settings, run maintenance/TTL sweep, and expand volume capacity.",
                )
            } else if (disk.usableBytes <= thresholds.diskFreeWarnBytes ||
                (disk.usedPercent >= thresholds.diskUsedWarnPercent && disk.usableBytes <= thresholds.diskUsedWarnCeilingBytes)
            ) {
                alerts += OperationalAlert(
                    id = ALERT_DISK_PRESSURE,
                    severity = AlertSeverity.WARN,
                    summary = "Data disk usage is $pct% ($usableMb MiB free)",
                    action = "Monitor sealed file growth in /admin/api/v1/files/stats and schedule volume expansion.",
                )
            }
        }

        if (delegationDaysRemaining != null) {
            if (delegationDaysRemaining <= thresholds.delegationCriticalDays) {
                alerts += OperationalAlert(
                    id = ALERT_DELEGATION_EXPIRY,
                    severity = AlertSeverity.CRITICAL,
                    summary = "Hub delegation certificate has $delegationDaysRemaining day(s) remaining",
                    action = "Mint and apply a rotated root-signed delegation certificate immediately (keytool renew-delegation).",
                )
            } else if (delegationDaysRemaining <= thresholds.delegationWarnDays) {
                alerts += OperationalAlert(
                    id = ALERT_DELEGATION_EXPIRY,
                    severity = AlertSeverity.WARN,
                    summary = "Hub delegation certificate has $delegationDaysRemaining day(s) remaining",
                    action = "Plan delegation certificate rotation using the offline organisation root key.",
                )
            }
        }

        if (tlsDaysRemaining != null) {
            if (tlsDaysRemaining <= thresholds.tlsCertCriticalDays) {
                alerts += OperationalAlert(
                    id = ALERT_TLS_CERT_EXPIRY,
                    severity = AlertSeverity.CRITICAL,
                    summary = "Hub TLS certificate has $tlsDaysRemaining day(s) remaining",
                    action = "Renew and reload the TLS certificate or reverse proxy ACME certificate immediately.",
                )
            } else if (tlsDaysRemaining <= thresholds.tlsCertWarnDays) {
                alerts += OperationalAlert(
                    id = ALERT_TLS_CERT_EXPIRY,
                    severity = AlertSeverity.WARN,
                    summary = "Hub TLS certificate has $tlsDaysRemaining day(s) remaining",
                    action = "Verify automated TLS certificate renewal before expiry.",
                )
            }
        }

        return alerts
    }
}
