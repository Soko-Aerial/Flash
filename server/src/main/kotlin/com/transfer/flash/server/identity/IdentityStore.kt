package com.transfer.flash.server.identity

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.server.storage.Migration
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.decodeSet
import com.transfer.flash.server.storage.encodeSet
import com.transfer.flash.server.storage.immediate
import com.transfer.flash.server.storage.longOrNull
import com.transfer.flash.server.storage.query
import com.transfer.flash.server.storage.queryOne
import com.transfer.flash.server.storage.scalarLong
import com.transfer.flash.server.storage.update
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet

/** Lifecycle of an enrolled device in the hub's registry (SEC-07/SEC-08, ADR-111). */
enum class DeviceStatus {
    ACTIVE,

    /** An administrator revoked the device id: it may not enrol again until an administrator lifts the revocation. */
    REVOKED,

    /** The device left the organisation itself (`POST /api/v1/device/unenroll`); a fresh invite may enrol it again. */
    UNENROLLED,
}

/** One row of the device registry. */
data class DeviceRecord(
    val deviceId: String,
    val subjectPublicKey: String,
    val serial: Long,
    val name: String,
    val department: String?,
    val role: String,
    val enrolledAt: Long,
    val certExpiry: Long,
    val lastSeenAt: Long?,
    val status: DeviceStatus,
    val statusChangedAt: Long,
    val delegationSerial: Long,
    val inviteId: String?,
)

enum class InviteState { PENDING, USED, EXPIRED, REVOKED }

/** One invite, WITHOUT its secret token (only a hash is stored, so a database leak yields no usable invite). */
data class InviteRecord(
    val inviteId: String,
    val department: String?,
    val role: String,
    val allowedGroups: Set<String>,
    val autoJoinGroups: Set<String>,
    val createdAt: Long,
    val expiresAt: Long,
    val maxUses: Int,
    val uses: Int,
    val revokedAt: Long?,
    val note: String?,
    val createdBy: String?,
) {
    fun state(nowMs: Long): InviteState = when {
        revokedAt != null -> InviteState.REVOKED
        uses >= maxUses -> InviteState.USED
        nowMs > expiresAt -> InviteState.EXPIRED
        else -> InviteState.PENDING
    }
}

/** One published end-to-end sealing key of a device (ADR-121, ADR-124): PUBLIC material only, never a private key. */
data class SealingKeyRecord(
    val deviceId: String,
    /** Base64 X.509 SPKI of the P-256 ECDH key. */
    val sealingPublicKey: String,
    /** Base64 DER ECDSA signature by the device identity key over `HubAuth.sealingKeyBindingBytes`. */
    val sealingSignature: String,
    /** Upper-case hex SHA-256 of the SPKI bytes (audit and replay bookkeeping; not secret). */
    val keyFingerprint: String,
    val publishedAt: Long,
    /** 1 for the first key, +1 for each rotation. */
    val version: Int,
)

sealed interface SealingPutOutcome {
    /** Stored. [rotated] is true when it replaced a different key. */
    data class Stored(val record: SealingKeyRecord, val rotated: Boolean) : SealingPutOutcome

    /** The same key was already published: nothing changed. */
    data class Unchanged(val record: SealingKeyRecord) : SealingPutOutcome

    /** The key was this device's key before and was rotated away: an old binding cannot be re-published. */
    data object RetiredKey : SealingPutOutcome

    /** The device is not ACTIVE in the registry. */
    data object NotActive : SealingPutOutcome
}

enum class EnrollRefusal {
    INVITE_UNKNOWN, INVITE_EXPIRED, INVITE_USED, INVITE_REVOKED, DEVICE_REVOKED, DEVICE_CONFLICT, QUOTA,
}

sealed interface EnrollOutcome {
    data class Enrolled(
        val cert: DeviceCert,
        val invite: InviteRecord,
        val device: DeviceRecord,
        /** True when an ACTIVE registration of the same key was re-issued (no quota slot consumed). */
        val reissued: Boolean,
    ) : EnrollOutcome

    data class Refused(val reason: EnrollRefusal) : EnrollOutcome
}

enum class RenewRefusal { NOT_ENROLLED, NOT_ACTIVE, KEY_MISMATCH, UNKNOWN_SERIAL }

sealed interface RenewOutcome {
    data class Renewed(val cert: DeviceCert, val device: DeviceRecord) : RenewOutcome
    data class Refused(val reason: RenewRefusal) : RenewOutcome
}

/**
 * Durable identity state of the hub (SEC-07/08/18, ADR-111): invite tokens, the monotonic device-serial counter, the
 * enrolment quota (counted from the registry, never from an in-memory number), the device registry and the history
 * of certificate serials.
 *
 * Everything that must be atomic is ONE `BEGIN IMMEDIATE` transaction ([enroll], [renew]): the invite use, the quota
 * check, the serial allocation, the registry write and the certificate signing happen together or not at all, so a
 * failed enrolment can never burn a token or a quota slot and two concurrent requests can never share a serial.
 *
 * Blocking JDBC: call from `Dispatchers.IO`. Never call another store from inside a transaction here (pool deadlock).
 */
class IdentityStore(private val db: ServerDatabase) {

    // ------------------------------------------------------------------------------------------------ invites

    /** Stores a new invite for the hash of [token]. The caller shows the token exactly once. */
    fun createInvite(
        token: String,
        department: String?,
        role: String,
        allowedGroups: Set<String>,
        autoJoinGroups: Set<String>,
        expiresAt: Long,
        maxUses: Int,
        nowMs: Long,
        note: String? = null,
        createdBy: String? = null,
    ): InviteRecord {
        val hash = hashToken(token)
        val id = inviteIdOf(hash)
        db.withConnection { conn ->
            conn.update(
                "INSERT INTO hub_invites(invite_id, token_hash, department, role, allowed_groups, auto_join_groups, " +
                    "created_at, expires_at, max_uses, uses, note, created_by) VALUES(?,?,?,?,?,?,?,?,?,0,?,?)",
                id, hash, department, role, encodeSet(allowedGroups), encodeSet(autoJoinGroups),
                nowMs, expiresAt, maxUses, note, createdBy,
            )
        }
        return InviteRecord(id, department, role, allowedGroups, autoJoinGroups, nowMs, expiresAt, maxUses, 0, null, note, createdBy)
    }

    fun inviteByToken(token: String): InviteRecord? = db.withConnection { conn ->
        conn.queryOne("SELECT * FROM hub_invites WHERE token_hash = ?", hashToken(token), map = ::inviteRow)
    }

    fun invite(inviteId: String): InviteRecord? = db.withConnection { conn ->
        conn.queryOne("SELECT * FROM hub_invites WHERE invite_id = ?", inviteId, map = ::inviteRow)
    }

    fun listInvites(limit: Int = 500, offset: Int = 0): List<InviteRecord> = db.withConnection { conn ->
        conn.query(
            "SELECT * FROM hub_invites ORDER BY created_at DESC, invite_id LIMIT ? OFFSET ?",
            limit.coerceIn(1, 1000), offset.coerceAtLeast(0), map = ::inviteRow,
        )
    }

    /** Revokes a pending invite. False when it does not exist or was already revoked. */
    fun revokeInvite(inviteId: String, nowMs: Long): Boolean = db.withConnection { conn ->
        conn.update("UPDATE hub_invites SET revoked_at = ? WHERE invite_id = ? AND revoked_at IS NULL", nowMs, inviteId) > 0
    }

    // ------------------------------------------------------------------------------------------------ enrol / renew

    /**
     * The atomic enrolment (see the class comment). [issue] signs the certificate for the allocated serial; it runs
     * inside the transaction, so an exception from it rolls the whole enrolment back.
     */
    fun enroll(
        token: String,
        deviceId: String,
        subjectPublicKey: String,
        friendlyName: String?,
        nowMs: Long,
        maxActiveDevices: Int,
        delegationSerial: Long,
        issue: (serial: Long, invite: InviteRecord) -> DeviceCert,
    ): EnrollOutcome = db.immediate { conn ->
        val invite = conn.queryOne("SELECT * FROM hub_invites WHERE token_hash = ?", hashToken(token), map = ::inviteRow)
            ?: return@immediate EnrollOutcome.Refused(EnrollRefusal.INVITE_UNKNOWN)
        when (invite.state(nowMs)) {
            InviteState.REVOKED -> return@immediate EnrollOutcome.Refused(EnrollRefusal.INVITE_REVOKED)
            InviteState.USED -> return@immediate EnrollOutcome.Refused(EnrollRefusal.INVITE_USED)
            InviteState.EXPIRED -> return@immediate EnrollOutcome.Refused(EnrollRefusal.INVITE_EXPIRED)
            InviteState.PENDING -> Unit
        }

        val existing = conn.queryOne("SELECT * FROM hub_devices WHERE device_id = ?", deviceId, map = ::deviceRow)
        var reissue = false
        if (existing != null) {
            when (existing.status) {
                DeviceStatus.REVOKED -> return@immediate EnrollOutcome.Refused(EnrollRefusal.DEVICE_REVOKED)
                DeviceStatus.ACTIVE -> {
                    if (existing.subjectPublicKey != subjectPublicKey) {
                        return@immediate EnrollOutcome.Refused(EnrollRefusal.DEVICE_CONFLICT)
                    }
                    reissue = true
                }
                DeviceStatus.UNENROLLED -> Unit
            }
        }
        if (!reissue) {
            val active = conn.scalarLong("SELECT COUNT(*) FROM hub_devices WHERE status = 'ACTIVE'")
            if (active >= maxActiveDevices) return@immediate EnrollOutcome.Refused(EnrollRefusal.QUOTA)
        }

        val serial = nextSerial(conn)
        val cert = issue(serial, invite)
        conn.update("UPDATE hub_invites SET uses = uses + 1 WHERE invite_id = ?", invite.inviteId)
        val name = friendlyName?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: existing?.name.orEmpty()
        conn.update(
            "INSERT INTO hub_devices(device_id, subject_public_key, serial, name, department, role, enrolled_at, cert_expiry, " +
                "last_seen_at, status, status_changed_at, delegation_serial, invite_id) VALUES(?,?,?,?,?,?,?,?,NULL,'ACTIVE',?,?,?) " +
                "ON CONFLICT(device_id) DO UPDATE SET subject_public_key = excluded.subject_public_key, serial = excluded.serial, " +
                "name = excluded.name, department = excluded.department, role = excluded.role, enrolled_at = excluded.enrolled_at, " +
                "cert_expiry = excluded.cert_expiry, status = 'ACTIVE', status_changed_at = excluded.status_changed_at, " +
                "delegation_serial = excluded.delegation_serial, invite_id = excluded.invite_id",
            deviceId, subjectPublicKey, serial, name, cert.department, cert.role, nowMs, cert.validTo, nowMs, delegationSerial,
            invite.inviteId,
        )
        conn.update(
            "INSERT INTO hub_cert_history(serial, device_id, issued_at, valid_to) VALUES(?,?,?,?)",
            serial, deviceId, nowMs, cert.validTo,
        )
        val device = conn.queryOne("SELECT * FROM hub_devices WHERE device_id = ?", deviceId, map = ::deviceRow)!!
        EnrollOutcome.Enrolled(cert, invite.copy(uses = invite.uses + 1), device, reissued = reissue)
    }

    /**
     * Renews the certificate of an ACTIVE device: same key, NEW serial. [presentedSerial] must be one this hub issued
     * to this device; the registry (not the presented certificate) decides role and department.
     */
    fun renew(
        deviceId: String,
        presentedSerial: Long,
        subjectPublicKey: String,
        nowMs: Long,
        delegationSerial: Long,
        issue: (serial: Long, current: DeviceRecord) -> DeviceCert,
    ): RenewOutcome = db.immediate { conn ->
        val current = conn.queryOne("SELECT * FROM hub_devices WHERE device_id = ?", deviceId, map = ::deviceRow)
            ?: return@immediate RenewOutcome.Refused(RenewRefusal.NOT_ENROLLED)
        if (current.status != DeviceStatus.ACTIVE) return@immediate RenewOutcome.Refused(RenewRefusal.NOT_ACTIVE)
        if (current.subjectPublicKey != subjectPublicKey) return@immediate RenewOutcome.Refused(RenewRefusal.KEY_MISMATCH)
        val known = conn.scalarLong(
            "SELECT COUNT(*) FROM hub_cert_history WHERE serial = ? AND device_id = ?", presentedSerial, deviceId,
        )
        if (known == 0L) return@immediate RenewOutcome.Refused(RenewRefusal.UNKNOWN_SERIAL)

        val serial = nextSerial(conn)
        val cert = issue(serial, current)
        conn.update(
            "UPDATE hub_devices SET serial = ?, cert_expiry = ?, delegation_serial = ? WHERE device_id = ?",
            serial, cert.validTo, delegationSerial, deviceId,
        )
        conn.update(
            "INSERT INTO hub_cert_history(serial, device_id, issued_at, valid_to) VALUES(?,?,?,?)",
            serial, deviceId, nowMs, cert.validTo,
        )
        RenewOutcome.Renewed(cert, current.copy(serial = serial, certExpiry = cert.validTo, delegationSerial = delegationSerial))
    }

    // ------------------------------------------------------------------------------------------------ registry

    fun device(deviceId: String): DeviceRecord? = db.withConnection { conn ->
        conn.queryOne("SELECT * FROM hub_devices WHERE device_id = ?", deviceId, map = ::deviceRow)
    }

    fun listDevices(
        status: DeviceStatus? = null,
        department: String? = null,
        search: String? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): List<DeviceRecord> = db.withConnection { conn ->
        val where = ArrayList<String>()
        val params = ArrayList<Any?>()
        if (status != null) { where += "status = ?"; params += status.name }
        if (department != null) { where += "department = ?"; params += department }
        if (!search.isNullOrBlank()) {
            where += "(device_id LIKE ? ESCAPE '\\' OR name LIKE ? ESCAPE '\\')"
            val like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            params += like; params += like
        }
        val sql = "SELECT * FROM hub_devices" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY enrolled_at DESC, device_id LIMIT ? OFFSET ?"
        params += limit.coerceIn(1, 500); params += offset.coerceAtLeast(0)
        conn.query(sql, *params.toTypedArray(), map = ::deviceRow)
    }

    /**
     * Ids of every ACTIVE device, optionally only those of one [department], with no row cap. The console overview counts
     * "online now" from these; slicing the first 500 rows before filtering undercounted a large organisation (review F5).
     */
    fun activeDeviceIds(department: String? = null): List<String> = db.withConnection { conn ->
        if (department == null) {
            conn.query("SELECT device_id FROM hub_devices WHERE status = ?", DeviceStatus.ACTIVE.name) { rs -> rs.getString("device_id") }
        } else {
            conn.query(
                "SELECT device_id FROM hub_devices WHERE status = ? AND department = ?", DeviceStatus.ACTIVE.name, department,
            ) { rs -> rs.getString("device_id") }
        }
    }

    fun countByStatus(): Map<DeviceStatus, Long> = db.withConnection { conn ->
        val rows = conn.query("SELECT status, COUNT(*) AS n FROM hub_devices GROUP BY status") { rs ->
            DeviceStatus.valueOf(rs.getString("status")) to rs.getLong("n")
        }.toMap()
        DeviceStatus.entries.associateWith { rows[it] ?: 0L }
    }

    fun activeDeviceCount(): Long = db.withConnection { it.scalarLong("SELECT COUNT(*) FROM hub_devices WHERE status = 'ACTIVE'") }

    fun serialsOf(deviceId: String): Set<Long> = db.withConnection { conn ->
        conn.query("SELECT serial FROM hub_cert_history WHERE device_id = ?", deviceId) { it.getLong(1) }.toSet()
    }

    fun deviceForSerial(serial: Long): String? = db.withConnection { conn ->
        conn.queryOne("SELECT device_id FROM hub_cert_history WHERE serial = ?", serial) { it.getString(1) }
    }

    fun setStatus(deviceId: String, status: DeviceStatus, nowMs: Long): Boolean = db.withConnection { conn ->
        conn.update("UPDATE hub_devices SET status = ?, status_changed_at = ? WHERE device_id = ?", status.name, nowMs, deviceId) > 0
    }

    fun rename(deviceId: String, name: String): Boolean = db.withConnection { conn ->
        conn.update("UPDATE hub_devices SET name = ? WHERE device_id = ?", name.trim().take(MAX_NAME), deviceId) > 0
    }

    /** Registry department only; the certificate keeps its department until the device renews. */
    fun setDepartment(deviceId: String, department: String?): Boolean = db.withConnection { conn ->
        conn.update("UPDATE hub_devices SET department = ? WHERE device_id = ?", department, deviceId) > 0
    }

    fun setRole(deviceId: String, role: String): Boolean = db.withConnection { conn ->
        conn.update("UPDATE hub_devices SET role = ? WHERE device_id = ?", role, deviceId) > 0
    }

    fun touchLastSeen(deviceId: String, nowMs: Long) {
        db.withConnection { it.update("UPDATE hub_devices SET last_seen_at = ? WHERE device_id = ?", nowMs, deviceId) }
    }

    // ------------------------------------------------------------------------------------------------ sealing keys

    fun sealingKey(deviceId: String): SealingKeyRecord? = db.withConnection { conn ->
        conn.queryOne("SELECT * FROM hub_sealing_keys WHERE device_id = ?", deviceId, map = ::sealingRow)
    }

    /** The published keys of [deviceIds] (one query per call; devices without a key are absent from the map). */
    fun sealingKeys(deviceIds: Collection<String>): Map<String, SealingKeyRecord> {
        if (deviceIds.isEmpty()) return emptyMap()
        return db.withConnection { conn ->
            deviceIds.distinct().chunked(400).flatMap { chunk ->
                conn.query(
                    "SELECT * FROM hub_sealing_keys WHERE device_id IN (" + chunk.joinToString(",") { "?" } + ")",
                    *chunk.toTypedArray(), map = ::sealingRow,
                )
            }.associateBy { it.deviceId }
        }
    }

    /**
     * Publishes or rotates [deviceId]'s sealing key. The CALLER has already verified the binding signature against
     * the device's certified key; this method only enforces state: ACTIVE device, no re-publication of a retired key.
     */
    fun putSealingKey(deviceId: String, sealingPublicKey: String, sealingSignature: String, keyFingerprint: String, nowMs: Long): SealingPutOutcome =
        db.immediate { conn ->
            val status = conn.queryOne("SELECT status FROM hub_devices WHERE device_id = ?", deviceId) { it.getString(1) }
            if (status != DeviceStatus.ACTIVE.name) return@immediate SealingPutOutcome.NotActive
            val existing = conn.queryOne("SELECT * FROM hub_sealing_keys WHERE device_id = ?", deviceId, map = ::sealingRow)
            if (existing != null && existing.keyFingerprint == keyFingerprint) return@immediate SealingPutOutcome.Unchanged(existing)
            val retired = conn.scalarLong(
                "SELECT COUNT(*) FROM hub_sealing_retired WHERE device_id = ? AND key_fingerprint = ?", deviceId, keyFingerprint,
            )
            if (retired > 0) return@immediate SealingPutOutcome.RetiredKey
            if (existing != null) {
                conn.update(
                    "INSERT OR IGNORE INTO hub_sealing_retired(device_id, key_fingerprint, retired_at) VALUES(?,?,?)",
                    deviceId, existing.keyFingerprint, nowMs,
                )
            }
            val version = (existing?.version ?: 0) + 1
            conn.update(
                "INSERT INTO hub_sealing_keys(device_id, sealing_public_key, sealing_signature, key_fingerprint, published_at, version) " +
                    "VALUES(?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET sealing_public_key = excluded.sealing_public_key, " +
                    "sealing_signature = excluded.sealing_signature, key_fingerprint = excluded.key_fingerprint, " +
                    "published_at = excluded.published_at, version = excluded.version",
                deviceId, sealingPublicKey, sealingSignature, keyFingerprint, nowMs, version,
            )
            SealingPutOutcome.Stored(
                SealingKeyRecord(deviceId, sealingPublicKey, sealingSignature, keyFingerprint, nowMs, version),
                rotated = existing != null,
            )
        }

    /** Removes the key (the device chose to stop being a sealing recipient). The retired list stays. */
    fun deleteSealingKey(deviceId: String): Boolean = db.withConnection { conn ->
        conn.update("DELETE FROM hub_sealing_keys WHERE device_id = ?", deviceId) > 0
    }

    // ------------------------------------------------------------------------------------------------ meta

    fun meta(key: String): String? = db.withConnection { conn ->
        conn.queryOne("SELECT v FROM hub_meta WHERE k = ?", key) { it.getString(1) }
    }

    fun putMeta(key: String, value: String) {
        db.withConnection {
            it.update("INSERT INTO hub_meta(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", key, value)
        }
    }

    /** Sets [key] only when it is not set yet; returns the value that is stored afterwards. */
    fun putMetaIfAbsent(key: String, value: String): String = db.immediate { conn ->
        conn.update("INSERT OR IGNORE INTO hub_meta(k, v) VALUES(?, ?)", key, value)
        conn.queryOne("SELECT v FROM hub_meta WHERE k = ?", key) { it.getString(1) }!!
    }

    // ------------------------------------------------------------------------------------------------ internals

    private fun nextSerial(conn: Connection): Long {
        conn.update("UPDATE hub_counters SET value = value + 1 WHERE name = 'device_serial'")
        return conn.scalarLong("SELECT value FROM hub_counters WHERE name = 'device_serial'")
    }

    private fun inviteRow(rs: ResultSet) = InviteRecord(
        inviteId = rs.getString("invite_id"),
        department = rs.getString("department"),
        role = rs.getString("role"),
        allowedGroups = decodeSet(rs.getString("allowed_groups")),
        autoJoinGroups = decodeSet(rs.getString("auto_join_groups")),
        createdAt = rs.getLong("created_at"),
        expiresAt = rs.getLong("expires_at"),
        maxUses = rs.getInt("max_uses"),
        uses = rs.getInt("uses"),
        revokedAt = rs.longOrNull("revoked_at"),
        note = rs.getString("note"),
        createdBy = rs.getString("created_by"),
    )

    private fun sealingRow(rs: ResultSet) = SealingKeyRecord(
        deviceId = rs.getString("device_id"),
        sealingPublicKey = rs.getString("sealing_public_key"),
        sealingSignature = rs.getString("sealing_signature"),
        keyFingerprint = rs.getString("key_fingerprint"),
        publishedAt = rs.getLong("published_at"),
        version = rs.getInt("version"),
    )

    private fun deviceRow(rs: ResultSet) = DeviceRecord(
        deviceId = rs.getString("device_id"),
        subjectPublicKey = rs.getString("subject_public_key"),
        serial = rs.getLong("serial"),
        name = rs.getString("name"),
        department = rs.getString("department"),
        role = rs.getString("role"),
        enrolledAt = rs.getLong("enrolled_at"),
        certExpiry = rs.getLong("cert_expiry"),
        lastSeenAt = rs.longOrNull("last_seen_at"),
        status = DeviceStatus.valueOf(rs.getString("status")),
        statusChangedAt = rs.getLong("status_changed_at"),
        delegationSerial = rs.getLong("delegation_serial"),
        inviteId = rs.getString("invite_id"),
    )

    companion object {
        const val COMPONENT: String = "identity"
        const val MAX_NAME: Int = 128
        private const val FIRST_SERIAL: Long = 1000L

        /** SHA-256 hex of an invite token: what is stored and what the admin API calls the invite's secret fingerprint. */
        fun hashToken(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()).joinToString("") { "%02x".format(it) }

        fun inviteIdOf(tokenHash: String): String = tokenHash.take(12)

        val MIGRATIONS: List<Migration> = listOf(
            Migration(1, "identity: meta, counters, invites, devices, certificate history, revocations") { conn ->
                conn.createStatement().use { st ->
                    st.execute("CREATE TABLE IF NOT EXISTS hub_meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
                    st.execute("CREATE TABLE IF NOT EXISTS hub_counters(name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
                    st.execute("INSERT OR IGNORE INTO hub_counters(name, value) VALUES('device_serial', $FIRST_SERIAL)")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS hub_invites(" +
                            "invite_id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, department TEXT, role TEXT NOT NULL, " +
                            "allowed_groups TEXT NOT NULL DEFAULT '', auto_join_groups TEXT NOT NULL DEFAULT '', " +
                            "created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, max_uses INTEGER NOT NULL, " +
                            "uses INTEGER NOT NULL DEFAULT 0, revoked_at INTEGER, note TEXT, created_by TEXT)",
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS hub_devices(" +
                            "device_id TEXT PRIMARY KEY, subject_public_key TEXT NOT NULL, serial INTEGER NOT NULL, " +
                            "name TEXT NOT NULL DEFAULT '', department TEXT, role TEXT NOT NULL, enrolled_at INTEGER NOT NULL, " +
                            "cert_expiry INTEGER NOT NULL, last_seen_at INTEGER, status TEXT NOT NULL, " +
                            "status_changed_at INTEGER NOT NULL, delegation_serial INTEGER NOT NULL DEFAULT 0, invite_id TEXT)",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_hub_devices_status ON hub_devices(status)")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS hub_cert_history(" +
                            "serial INTEGER PRIMARY KEY, device_id TEXT NOT NULL, issued_at INTEGER NOT NULL, valid_to INTEGER NOT NULL)",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_hub_cert_history_device ON hub_cert_history(device_id)")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS hub_revocations(" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, subject TEXT NOT NULL, " +
                            "reason TEXT NOT NULL DEFAULT '', revoked_at INTEGER NOT NULL, revoked_by TEXT NOT NULL DEFAULT '', " +
                            "UNIQUE(kind, subject))",
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS hub_revocation_lists(" +
                            "version INTEGER PRIMARY KEY, body TEXT NOT NULL, signer TEXT NOT NULL, created_at INTEGER NOT NULL)",
                    )
                }
            },
            Migration(2, "identity: end-to-end sealing keys (public material only), retired keys, cleanup triggers") { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS hub_sealing_keys(" +
                            "device_id TEXT PRIMARY KEY, sealing_public_key TEXT NOT NULL, sealing_signature TEXT NOT NULL, " +
                            "key_fingerprint TEXT NOT NULL, published_at INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 1)",
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS hub_sealing_retired(" +
                            "device_id TEXT NOT NULL, key_fingerprint TEXT NOT NULL, retired_at INTEGER NOT NULL, " +
                            "PRIMARY KEY(device_id, key_fingerprint))",
                    )
                    // A device that is revoked, unenrolled or deleted leaves no sealing key behind, whichever code path did it.
                    st.execute(
                        "CREATE TRIGGER IF NOT EXISTS trg_hub_sealing_on_status AFTER UPDATE OF status ON hub_devices " +
                            "WHEN NEW.status <> 'ACTIVE' BEGIN " +
                            "DELETE FROM hub_sealing_keys WHERE device_id = NEW.device_id; " +
                            "DELETE FROM hub_sealing_retired WHERE device_id = NEW.device_id; END",
                    )
                    st.execute(
                        "CREATE TRIGGER IF NOT EXISTS trg_hub_sealing_on_delete AFTER DELETE ON hub_devices BEGIN " +
                            "DELETE FROM hub_sealing_keys WHERE device_id = OLD.device_id; " +
                            "DELETE FROM hub_sealing_retired WHERE device_id = OLD.device_id; END",
                    )
                }
            },
        )

        /** Registers and applies the identity schema, returning the store. */
        fun open(db: ServerDatabase): IdentityStore {
            Migrator(db).apply { register(COMPONENT, MIGRATIONS) }.migrate()
            return IdentityStore(db)
        }

        /** A private in-memory store (tests, and services built without a database). */
        fun inMemory(): IdentityStore = open(ServerDatabase.inMemory(poolSize = 1))
    }
}
