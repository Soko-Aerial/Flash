@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.auth

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.HubScopes
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.immediate
import com.transfer.flash.server.storage.query
import com.transfer.flash.server.storage.queryOne
import com.transfer.flash.server.storage.update
import java.security.PrivateKey
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * What lets the hub sign a [RevocationList] itself: its delegate key, and a delegation that grants `revoke:member`.
 * Without both, revocations are still ENFORCED locally at once but no new signed list can be published; an offline
 * root-signed list can be installed instead ([SqliteRevocationService.installRootSigned]).
 */
class RevocationSigner(
    private val delegatePrivateKey: PrivateKey,
    val delegatePublicKey: String,
    private val delegation: () -> DelegationCert?,
) {
    fun canSign(): Boolean {
        val cert = delegation() ?: return false
        return cert.delegatePublicKey == delegatePublicKey && cert.hasScope(HubScopes.REVOKE_MEMBER)
    }

    internal fun sign(version: Long, serials: Set<Long>, deviceIds: Set<String>, audience: String, deviceHashes: Set<String>): RevocationList =
        RevocationList.issue(
            issuerPrivateKey = delegatePrivateKey,
            issuerPublicKey = delegatePublicKey,
            version = version,
            revokedSerials = serials,
            revokedDeviceIds = deviceIds,
            audience = audience,
            revokedDeviceHashes = deviceHashes,
        )
}

/** One persisted revocation, for the admin API. */
data class RevocationEntry(val kind: String, val subject: String, val reason: String, val revokedAt: Long, val revokedBy: String)

/** Outcome of a revoke / unrevoke. */
data class RevocationResult(
    /** False when nothing changed (already revoked, or unknown). */
    val changed: Boolean,
    val serials: Set<Long> = emptySet(),
    /** Version of the signed list published by this change, or null when no new list could be signed. */
    val publishedVersion: Long? = null,
    /** True when the change is enforced here but clients can not learn it until a signed list is published. */
    val needsSignedList: Boolean = false,
)

/** Summary of the persisted state, for health and the admin overview. */
data class RevocationStatus(
    val listVersion: Long,
    val revokedSerials: Int,
    val revokedDeviceIds: Int,
    val revokedDelegationSerials: Int,
    val unpublishedChanges: Boolean,
    val canSign: Boolean,
    /** The numbers of the delegations the root revoked. A hub whose own delegation is in here must not issue anything. */
    val revokedDelegations: Set<Long> = emptySet(),
)

/**
 * The real [RevocationService] (SEC-05, ADR-110/111).
 *
 * - Every revocation is a row in SQLite (survives restarts) and is enforced from an in-memory snapshot, so
 *   [isRevoked] costs two hash lookups on every request.
 * - Each change also publishes a new SIGNED [RevocationList] with a strictly higher version (`GET /api/v1/revocations`).
 *   The delegate signs device/serial lists when its delegation has `revoke:member`; delegation serials can only be
 *   revoked by the root, through [installRootSigned], and once a root list carries them the delegate can not sign any
 *   further list (a clients rejects a delegate-signed list that revokes delegations, see `RevocationList.verifyTrusted`).
 * - A list with a version that is not higher than the current one is never accepted or produced.
 */
class SqliteRevocationService(
    private val db: ServerDatabase,
    private val rootPublicKey: String,
    private val signer: RevocationSigner? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notifyScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RevocationService, SelfRevocation {

    private val log = LoggerFactory.getLogger(SqliteRevocationService::class.java)

    private class Snapshot(
        val serials: Set<Long>,
        val deviceIds: Set<String>,
        /** Hashes of device ids this hub never saw (they came from an offline root list, ADR-129): checked by hashing the presented id. */
        val deviceHashes: Set<String>,
        val delegationSerials: Set<Long>,
        val list: RevocationList?,
        val unpublished: Boolean,
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot(emptySet(), emptySet(), emptySet(), emptySet(), null, false)

    /** The audience every list of this organisation is issued for (ADR-129). */
    private val audience: String = RevocationList.audienceOf(rootPublicKey)

    private val handlers = CopyOnWriteArrayList<suspend (String, Long?, String) -> Unit>()

    init {
        reload()
        republishPlainList()
    }

    // ------------------------------------------------------------------------------------------------ RevocationService

    override fun isRevoked(serial: Long?, deviceId: String): Boolean {
        val s = snapshot
        if (deviceId in s.deviceIds || (serial != null && serial in s.serials)) return true
        return s.deviceHashes.isNotEmpty() && RevocationList.deviceHash(audience, deviceId) in s.deviceHashes
    }

    override fun current(): RevocationList? = snapshot.list

    override fun isDelegationRevoked(delegationSerial: Long): Boolean = delegationSerial in snapshot.delegationSerials

    override fun onRevoked(handler: suspend (deviceId: String, serial: Long?, reason: String) -> Unit) {
        handlers += handler
    }

    // ------------------------------------------------------------------------------------------------ queries

    fun status(): RevocationStatus {
        val s = snapshot
        return RevocationStatus(
            listVersion = s.list?.version ?: 0L,
            revokedSerials = s.serials.size,
            revokedDeviceIds = s.deviceIds.size + s.deviceHashes.size,
            revokedDelegationSerials = s.delegationSerials.size,
            unpublishedChanges = s.unpublished,
            canSign = signer?.canSign() == true && s.delegationSerials.isEmpty(),
            revokedDelegations = s.delegationSerials,
        )
    }

    fun entries(): List<RevocationEntry> = db.withConnection { conn ->
        conn.query("SELECT kind, subject, reason, revoked_at, revoked_by FROM hub_revocations ORDER BY id") { rs ->
            RevocationEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5))
        }
    }

    /** A previously published list by [version], for audit and tests. */
    fun listAt(version: Long): RevocationList? = db.withConnection { conn ->
        conn.queryOne("SELECT body FROM hub_revocation_lists WHERE version = ?", version) { HubRestCodec.decodeOrNull<RevocationList>(it.getString(1), STORED_LIST_MAX_BYTES) }
    }

    // ------------------------------------------------------------------------------------------------ changes

    /**
     * Revokes [deviceId] AND every certificate serial it was ever issued (revoke by id and by serial). The registry
     * status becomes REVOKED, which also blocks re-enrolment of the id until [unrevokeDevice].
     */
    fun revokeDevice(deviceId: String, reason: String, by: String): RevocationResult {
        val now = clock()
        val result = db.immediate { conn ->
            val serials = conn.query("SELECT serial FROM hub_cert_history WHERE device_id = ?", deviceId) { it.getLong(1) }.toSet()
            var changed = insert(conn, "DEVICE", deviceId, reason, now, by)
            for (serial in serials) changed = insert(conn, "SERIAL", serial.toString(), reason, now, by) || changed
            conn.update("UPDATE hub_devices SET status = 'REVOKED', status_changed_at = ? WHERE device_id = ? AND status <> 'REVOKED'", now, deviceId)
            val published = if (changed) publish(conn, now) else null
            Triple(changed, serials, published)
        }
        val outcome = finish(result.first, result.second, result.third)
        if (result.first) fire(deviceId, null, reason)
        return outcome
    }

    /** Self-service leave: the device's certificates die, the id stays free to enrol again with a fresh invite. */
    override fun revokeSelf(deviceId: String, by: String): RevocationResult {
        val now = clock()
        val result = db.immediate { conn ->
            val serials = conn.query("SELECT serial FROM hub_cert_history WHERE device_id = ?", deviceId) { it.getLong(1) }.toSet()
            var changed = false
            for (serial in serials) changed = insert(conn, "SERIAL", serial.toString(), "unenrolled", now, by) || changed
            conn.update(
                "UPDATE hub_devices SET status = 'UNENROLLED', status_changed_at = ? WHERE device_id = ? AND status = 'ACTIVE'",
                now, deviceId,
            )
            val published = if (changed) publish(conn, now) else null
            Triple(changed, serials, published)
        }
        val outcome = finish(result.first, result.second, result.third)
        if (result.first) fire(deviceId, null, "unenrolled")
        return outcome
    }

    /** Revokes one certificate serial (lost device whose id is kept). */
    fun revokeSerial(serial: Long, reason: String, by: String): RevocationResult {
        val now = clock()
        var deviceId: String? = null
        val result = db.immediate { conn ->
            deviceId = conn.queryOne("SELECT device_id FROM hub_cert_history WHERE serial = ?", serial) { it.getString(1) }
            val changed = insert(conn, "SERIAL", serial.toString(), reason, now, by)
            val published = if (changed) publish(conn, now) else null
            Triple(changed, setOf(serial), published)
        }
        val outcome = finish(result.first, result.second, result.third)
        if (result.first) deviceId?.let { fire(it, serial, reason) }
        return outcome
    }

    /**
     * Lifts a device-id revocation so the id may enrol again. Its OLD certificates stay revoked (their serials remain
     * listed): the device has to enrol afresh with a new invite, which proves an administrator wants it back.
     */
    fun unrevokeDevice(deviceId: String, by: String): RevocationResult {
        val now = clock()
        val result = db.immediate { conn ->
            val removed = conn.update("DELETE FROM hub_revocations WHERE kind = 'DEVICE' AND subject = ?", deviceId) > 0
            conn.update(
                "UPDATE hub_devices SET status = ?, status_changed_at = ? WHERE device_id = ? AND status = 'REVOKED'",
                DeviceStatus.UNENROLLED.name, now, deviceId,
            )
            val published = if (removed) publish(conn, now) else null
            Triple(removed, emptySet<Long>(), published)
        }
        log.info("Revocation of device lifted by {} (changed={})", by, result.first)
        return finish(result.first, result.second, result.third)
    }

    /**
     * Installs a list signed OFFLINE by the root key (the only way to revoke a delegation serial). It must verify, be
     * issued by the root key, and carry a strictly higher version than the current list. Its contents are merged into
     * the local state. Returns true when installed.
     */
    fun installRootSigned(list: RevocationList): Boolean {
        if (list.issuerPublicKey != rootPublicKey || !list.verifySignature()) return false
        val now = clock()
        val fallenDevices = LinkedHashSet<String>()
        val revokedByList = LinkedHashSet<String>()
        val installed = db.immediate { conn ->
            val top = conn.queryOne("SELECT MAX(version) FROM hub_revocation_lists") { it.getLong(1) } ?: 0L
            if (list.version <= top) return@immediate false
            conn.update(
                "INSERT INTO hub_revocation_lists(version, body, signer, created_at) VALUES(?,?,?,?)",
                list.version, HubRestCodec.encode(list), "root", now,
            )
            // A hashed list (ADR-129) names no ids: the ones this hub knows are found by hashing its own registry; a hash it cannot match is
            // kept as such and still blocks that id the moment it presents itself (isRevoked hashes the presented id).
            val named: Set<String> = if (list.isHashed) {
                conn.query("SELECT device_id FROM hub_devices") { it.getString(1) }
                    .filter { RevocationList.deviceHash(list.audience, it) in list.revokedDeviceHashes }.toSet()
            } else {
                list.revokedDeviceIds
            }
            revokedByList += named
            for (s in list.revokedSerials) insert(conn, "SERIAL", s.toString(), "root list", now, "root")
            for (d in named) insert(conn, "DEVICE", d, "root list", now, "root")
            if (list.isHashed) {
                val matched = named.map { RevocationList.deviceHash(list.audience, it) }.toSet()
                for (h in list.revokedDeviceHashes - matched) insert(conn, "DEVICE_HASH", h, "root list", now, "root")
            }
            for (d in list.revokedDelegationSerials) insert(conn, "DELEGATION", d.toString(), "root list", now, "root")
            for (d in named) {
                conn.update("UPDATE hub_devices SET status = 'REVOKED', status_changed_at = ? WHERE device_id = ? AND status <> 'REVOKED'", now, d)
            }
            // REV-04: a revoked delegation takes every certificate issued under it with it, at once and everywhere (handshake, bearer,
            // each relay frame): their serials are revoked here, the devices stay UNREVOKED by id so they can enrol again under the new
            // delegation with a fresh invitation. A certificate the registry cannot attribute to a delegation is revoked with its device.
            for (delegation in list.revokedDelegationSerials) {
                val devices = conn.query("SELECT device_id FROM hub_devices WHERE delegation_serial = ?", delegation) { it.getString(1) }
                for (id in devices) {
                    for (serial in conn.query("SELECT serial FROM hub_cert_history WHERE device_id = ?", id) { it.getLong(1) }) {
                        insert(conn, "SERIAL", serial.toString(), "delegation $delegation revoked", now, "root")
                    }
                    fallenDevices += id
                }
            }
            true
        }
        if (installed) {
            reload()
            for (deviceId in revokedByList) fire(deviceId, null, "root list")
            for (deviceId in fallenDevices) fire(deviceId, null, "delegation revoked")
        }
        return installed
    }

    /**
     * ADR-129 migration: a list published before the hashed format names device ids in the clear and is still what the public
     * endpoint serves. When this hub can sign, replace it with a hashed list of the same content (a strictly higher version) at
     * startup. A hub that cannot sign keeps serving the old list until an administrator revokes something or installs a root list.
     */
    private fun republishPlainList() {
        val current = snapshot.list ?: return
        if (current.isHashed || current.revokedDeviceIds.isEmpty()) return
        val published = db.immediate { conn -> publish(conn, clock()) }
        if (published != null) {
            reload()
            log.info("Revocation list republished in the hashed format (version {})", published)
        } else {
            log.warn("The published revocation list still names device ids in the clear and this hub cannot sign a hashed one (delegation lacks revoke:member, or a root list revokes a delegation)")
        }
    }

    /** Re-reads the persisted state (startup, tests). */
    fun reload() {
        snapshot = db.withConnection { conn -> load(conn) }
    }

    // ------------------------------------------------------------------------------------------------ internals

    private fun insert(conn: java.sql.Connection, kind: String, subject: String, reason: String, now: Long, by: String): Boolean =
        conn.update(
            "INSERT OR IGNORE INTO hub_revocations(kind, subject, reason, revoked_at, revoked_by) VALUES(?,?,?,?,?)",
            kind, subject, reason.take(200), now, by.take(128),
        ) > 0

    /** Builds and stores a new signed list when the delegate may sign one; returns its version or null. */
    private fun publish(conn: java.sql.Connection, now: Long): Long? {
        val rows = rows(conn)
        val s = signer
        if (s == null || !s.canSign() || rows.delegations.isNotEmpty()) return null
        val top = conn.queryOne("SELECT MAX(version) FROM hub_revocation_lists") { it.getLong(1) } ?: 0L
        val list = s.sign(top + 1, rows.serials, rows.devices, audience, rows.deviceHashes)
        conn.update(
            "INSERT INTO hub_revocation_lists(version, body, signer, created_at) VALUES(?,?,?,?)",
            list.version, HubRestCodec.encode(list), "delegate", now,
        )
        return list.version
    }

    private class Rows(val serials: Set<Long>, val devices: Set<String>, val deviceHashes: Set<String>, val delegations: Set<Long>)

    private fun rows(conn: java.sql.Connection): Rows {
        val serials = HashSet<Long>()
        val devices = HashSet<String>()
        val hashes = HashSet<String>()
        val delegations = HashSet<Long>()
        conn.query("SELECT kind, subject FROM hub_revocations") { rs -> rs.getString(1) to rs.getString(2) }.forEach { (k, v) ->
            when (k) {
                "SERIAL" -> serials += v.toLong()
                "DEVICE" -> devices += v
                "DEVICE_HASH" -> hashes += v
                "DELEGATION" -> delegations += v.toLong()
            }
        }
        return Rows(serials, devices, hashes, delegations)
    }

    private fun load(conn: java.sql.Connection): Snapshot {
        val rows = rows(conn)
        val serials = rows.serials
        val devices = rows.devices
        val delegations = rows.delegations
        val list = conn.queryOne("SELECT body FROM hub_revocation_lists ORDER BY version DESC LIMIT 1") {
            HubRestCodec.decodeOrNull<RevocationList>(it.getString(1), STORED_LIST_MAX_BYTES)
        }
        val unpublished = if (list == null) {
            serials.isNotEmpty() || devices.isNotEmpty() || rows.deviceHashes.isNotEmpty() || delegations.isNotEmpty()
        } else {
            val sameDevices = if (list.isHashed) {
                list.revokedDeviceHashes == devices.mapTo(HashSet()) { RevocationList.deviceHash(list.audience, it) } + rows.deviceHashes
            } else {
                list.revokedDeviceIds == devices && rows.deviceHashes.isEmpty()
            }
            list.revokedSerials != serials || !sameDevices || list.revokedDelegationSerials != delegations
        }
        return Snapshot(serials, devices, rows.deviceHashes, delegations, list, unpublished)
    }

    private fun finish(changed: Boolean, serials: Set<Long>, published: Long?): RevocationResult {
        reload()
        val s = snapshot
        if (changed && published == null) {
            log.warn("Revocation enforced locally but no signed list could be published (delegate lacks revoke:member, or a root list revokes a delegation)")
        }
        return RevocationResult(changed, serials, published, needsSignedList = s.unpublished)
    }

    private fun fire(deviceId: String, serial: Long?, reason: String) {
        if (handlers.isEmpty()) return
        notifyScope.launch {
            for (handler in handlers) {
                try {
                    handler(deviceId, serial, reason)
                } catch (t: Throwable) {
                    log.warn("revocation handler failed: {}", t.javaClass.simpleName)
                }
            }
        }
    }

    private companion object {
        /** Stored lists are our own output: allow far more than the 64 KiB request cap. */
        const val STORED_LIST_MAX_BYTES: Int = 16 * 1024 * 1024
    }
}

/** Self-service leave: a device revokes its own certificates (the id stays free). */
interface SelfRevocation {
    fun revokeSelf(deviceId: String, by: String): RevocationResult
}
