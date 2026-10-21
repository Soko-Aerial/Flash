package com.transfer.flash.server.mailbox

import com.transfer.flash.server.storage.ServerDatabase

/**
 * The relay's port to durable offline storage. The relay never parses a payload: it hands the store a
 * [MailboxMessage] and later receives [QueuedMessage]s back, byte for byte.
 *
 * Delivery contract (ADR-112): a stored message stays until the RECIPIENT acknowledges it ([acknowledge], driven by
 * `FLASH_RECV_ACK`) or it expires. Reading a page ([fetchPage]) never removes anything, so a message sent to a socket
 * that died before the client stored it is simply delivered again on the next connect.
 */
interface MailboxStore {
    suspend fun enqueue(message: MailboxMessage, ttlOverrideMs: Long? = null): EnqueueResult

    /** Up to [limit] unexpired messages for [target] with `seq > afterSeq`, oldest (lowest `seq`) first. */
    suspend fun fetchPage(target: String, afterSeq: Long, limit: Int): List<QueuedMessage>

    /**
     * [fetchPage] that also reports the rows it had to quarantine because they could not be opened (RLY-01). A store without
     * sealed payloads never quarantines, so the default wraps [fetchPage]. The drain uses this to tell the senders.
     */
    suspend fun fetchPageDetailed(target: String, afterSeq: Long, limit: Int): MailboxPage = MailboxPage(fetchPage(target, afterSeq, limit))

    /**
     * Deletes [target]'s stored copy of [msgId] and returns who sent it. With [origin] only that sender's copy goes; without it only
     * the OLDEST copy goes (two senders may use the same id, one acknowledgement must never destroy both: REV-08).
     * Idempotent: an unknown id returns an empty list.
     */
    suspend fun acknowledge(target: String, msgId: String, origin: String? = null): List<AckedRow>

    /** Deletes up to [limit] expired rows and returns them so the senders can be told. */
    suspend fun deleteExpired(limit: Int = 500): List<ExpiredRow>

    /** Counts a message that could not be kept even though the mailbox accepted responsibility for it. */
    fun noteDropped(count: Int = 1) {}

    /** The relay-facing mailbox of a hub that has none: every enqueue is [EnqueueResult.Unavailable]. */
    object None : MailboxStore {
        override suspend fun enqueue(message: MailboxMessage, ttlOverrideMs: Long?): EnqueueResult = EnqueueResult.Unavailable
        override suspend fun fetchPage(target: String, afterSeq: Long, limit: Int): List<QueuedMessage> = emptyList()
        override suspend fun acknowledge(target: String, msgId: String, origin: String?): List<AckedRow> = emptyList()
        override suspend fun deleteExpired(limit: Int): List<ExpiredRow> = emptyList()
    }
}

/**
 * Decides whether a mailbox recipient exists (REL-08): a message for an id nobody enrolled would otherwise sit in the
 * database until it expires. SERVER-A's enrolment records are the production source; [DatabaseDeviceRegistry] reads the
 * `device_registry` table, [AcceptAll] is the explicit "no registry wired" default and is not for production.
 */
fun interface DeviceRegistry {
    suspend fun isEnrolled(deviceId: String): Boolean

    companion object {
        val AcceptAll: DeviceRegistry = DeviceRegistry { true }
    }
}

/**
 * [DeviceRegistry] over the identity store of [ServerDatabase]. The enrolment (identity store, `hub_devices`) is the
 * production source of truth: a device is a valid mailbox recipient while it is ACTIVE (ADR-124 e2e finding: this class
 * used to read only the legacy `device_registry` table, which nothing populates, so every offline recipient was answered
 * `ERR_UNKNOWN_RECIPIENT` and the mailbox never stored anything). The legacy table still counts, for databases and
 * tests that registered a device through [ServerDatabase.registerDevice].
 */
class DatabaseDeviceRegistry(private val database: ServerDatabase) : DeviceRegistry {
    override suspend fun isEnrolled(deviceId: String): Boolean = database.io { conn ->
        val active = try {
            conn.prepareStatement("SELECT 1 FROM hub_devices WHERE device_id = ? AND status = 'ACTIVE'").use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeQuery().use { it.next() }
            }
        } catch (_: java.sql.SQLException) {
            false // the identity store has not created its table yet (a bare mailbox database)
        }
        active || conn.prepareStatement("SELECT 1 FROM device_registry WHERE device_id = ?").use { stmt ->
            stmt.setString(1, deviceId)
            stmt.executeQuery().use { it.next() }
        }
    }
}
