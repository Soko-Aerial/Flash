package com.transfer.flash.server.mailbox

import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.server.storage.ServerDatabase
import java.sql.Connection
import java.util.UUID

/**
 * Result of an enqueue operation.
 */
sealed class EnqueueResult {
    data class Enqueued(val envelopeId: String) : EnqueueResult()
    data class Duplicate(val existingEnvelopeId: String?) : EnqueueResult()
    data class QuotaExceeded(val reason: String) : EnqueueResult()
    data class Error(val cause: Throwable) : EnqueueResult()

    val isSuccess: Boolean get() = this is Enqueued
    val isDuplicate: Boolean get() = this is Duplicate
}

/**
 * Metadata record for a queued envelope retaining internal row ID and lifecycle timestamps.
 */
data class QueuedEnvelope(
    val envelopeId: String,
    val envelope: SealedEnvelope,
    val createdAt: Long,
    val expiresAt: Long,
)

/**
 * Primary interface for the Store-and-Forward Mailbox queue.
 * Allows Subagent 1's frame router to decouple from direct database specifics.
 */
interface MailboxQueueSeam {
    fun enqueue(
        envelope: SealedEnvelope,
        recipientDeviceId: String,
        ttlMs: Long = MailboxQueue.DEFAULT_TTL_MS,
    ): EnqueueResult

    fun fetchPending(targetDeviceId: String, limit: Int = 100): List<SealedEnvelope>

    fun fetchPendingWithMetadata(targetDeviceId: String, limit: Int = 100): List<QueuedEnvelope>

    fun markDelivered(envelopeIds: List<String>): Int

    fun markDelivered(targetDeviceId: String, msgId: String): Boolean

    fun getQueuedCount(targetDeviceId: String): Int

    fun getQueuedBytes(targetDeviceId: String): Long

    fun deleteExpired(): Int
}

/**
 * Production implementation of [MailboxQueueSeam] backed by [ServerDatabase].
 * Also implements [com.transfer.flash.server.relay.MailboxQueueSeam] for direct integration with RelayFrameRouter.
 */
class MailboxQueue(
    private val database: ServerDatabase,
    private val quotaManager: QuotaManager = QuotaManager(),
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
) : MailboxQueueSeam, com.transfer.flash.server.relay.MailboxQueueSeam {

    companion object {
        const val DEFAULT_TTL_MS: Long = 7 * 24 * 60 * 60 * 1000L // 7 days retention
    }

    /**
     * Enqueues a sealed envelope for store-and-forward delivery to [recipientDeviceId].
     *
     * Performs:
     * 1. Deduplication on `(origin_device_id, msg_id, target_device_id)` using index `idx_mailbox_origin_msg`.
     * 2. Quota check through [QuotaManager].
     * 3. Insert into `mailbox_envelopes`.
     */
    override fun enqueue(
        envelope: SealedEnvelope,
        recipientDeviceId: String,
        ttlMs: Long,
    ): EnqueueResult {
        return try {
            database.withConnection { conn ->
                // 1. Deduplication check
                val existingId = checkDuplicate(conn, envelope.originDeviceId, envelope.msgId, recipientDeviceId)
                if (existingId != null) {
                    return@withConnection EnqueueResult.Duplicate(existingId)
                }

                // 2. Quota verification
                val payloadSize = envelope.payload.size.toLong()
                val currentCount = queryQueuedCount(conn, recipientDeviceId)
                val currentBytes = queryQueuedBytes(conn, recipientDeviceId)
                val quotaResult = quotaManager.checkDeviceQuota(
                    targetDeviceId = recipientDeviceId,
                    currentQueuedBytes = currentBytes,
                    currentPendingCount = currentCount,
                    newPayloadSizeBytes = payloadSize,
                )
                if (!quotaResult.isAllowed) {
                    val reason = (quotaResult as QuotaCheckResult.Exceeded).reason
                    return@withConnection EnqueueResult.QuotaExceeded(reason)
                }

                // 3. Database insertion
                val envelopeId = UUID.randomUUID().toString()
                val now = if (envelope.timestamp > 0) envelope.timestamp else System.currentTimeMillis()
                val expiresAt = now + ttlMs

                val sql = """
                    INSERT INTO mailbox_envelopes (
                        id, origin_device_id, target_device_id, msg_id, kind, hops, counter,
                        payload, auth_tag, created_at, expires_at, delivered
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, envelopeId)
                    stmt.setString(2, envelope.originDeviceId)
                    stmt.setString(3, recipientDeviceId)
                    stmt.setString(4, envelope.msgId)
                    stmt.setString(5, envelope.kind)
                    stmt.setInt(6, envelope.hops)
                    stmt.setLong(7, envelope.counter)
                    stmt.setBytes(8, envelope.payload)
                    stmt.setString(9, envelope.authTagOrSignature)
                    stmt.setLong(10, now)
                    stmt.setLong(11, expiresAt)
                    stmt.executeUpdate()
                }

                EnqueueResult.Enqueued(envelopeId)
            }
        } catch (e: Throwable) {
            EnqueueResult.Error(e)
        }
    }

    /**
     * Enqueues an envelope for all recipients declared in [SealedEnvelope.recipientDeviceIds].
     */
    fun enqueueAll(envelope: SealedEnvelope, ttlMs: Long = defaultTtlMs): Map<String, EnqueueResult> {
        return envelope.recipientDeviceIds.associateWith { recipientId ->
            enqueue(envelope, recipientId, ttlMs)
        }
    }

    /**
     * Implementation of [com.transfer.flash.server.relay.MailboxQueueSeam] bridging FlashEnvelope to SealedEnvelope.
     */
    override suspend fun enqueue(recipientId: String, envelope: FlashEnvelope): Boolean {
        val sealed = SealedEnvelope(
            version = 1,
            originDeviceId = envelope.senderId,
            msgId = envelope.id,
            hops = 1,
            counter = 0L,
            kind = envelope.type,
            recipientDeviceIds = listOf(recipientId),
            payload = envelope.payloadJson.toByteArray(Charsets.UTF_8),
            authTagOrSignature = "",
            timestamp = envelope.sentAt,
        )
        val result = enqueue(sealed, recipientId)
        return result.isSuccess
    }

    /**
     * Implementation of [com.transfer.flash.server.relay.MailboxQueueSeam] raw frame enqueue.
     */
    override suspend fun enqueueRaw(recipientId: String, rawFrame: String): Boolean {
        val sealed = SealedEnvelope(
            version = 1,
            originDeviceId = "raw",
            msgId = UUID.randomUUID().toString(),
            hops = 1,
            counter = 0L,
            kind = EnvelopeKind.META,
            recipientDeviceIds = listOf(recipientId),
            payload = rawFrame.toByteArray(Charsets.UTF_8),
            authTagOrSignature = "",
            timestamp = System.currentTimeMillis(),
        )
        val result = enqueue(sealed, recipientId)
        return result.isSuccess
    }

    /**
     * Fetches undelivered, non-expired envelopes for [targetDeviceId] in order of creation.
     */
    override fun fetchPending(targetDeviceId: String, limit: Int): List<SealedEnvelope> {
        return fetchPendingWithMetadata(targetDeviceId, limit).map { it.envelope }
    }

    /**
     * Implements [com.transfer.flash.server.relay.MailboxQueueSeam] to fetch pending FlashEnvelopes.
     */
    override suspend fun fetchPendingEnvelopes(targetDeviceId: String, limit: Int): List<FlashEnvelope> {
        val pending = fetchPending(targetDeviceId, limit)
        return pending.map { sealed ->
            FlashEnvelope(
                id = sealed.msgId,
                type = sealed.kind,
                payloadJson = sealed.payload.decodeToString(),
                senderId = sealed.originDeviceId,
                sentAt = sealed.timestamp,
            )
        }
    }

    /**
     * Fetches undelivered, non-expired envelopes with internal IDs and timestamps.
     */
    override fun fetchPendingWithMetadata(targetDeviceId: String, limit: Int): List<QueuedEnvelope> {
        return database.withConnection { conn ->
            val sql = """
                SELECT id, origin_device_id, target_device_id, msg_id, kind, hops, counter,
                       payload, auth_tag, created_at, expires_at
                FROM mailbox_envelopes
                WHERE target_device_id = ? 
                  AND delivered = 0 
                  AND (expires_at > ? OR expires_at = 0)
                ORDER BY created_at ASC
                LIMIT ?
            """.trimIndent()

            val now = System.currentTimeMillis()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, targetDeviceId)
                stmt.setLong(2, now)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<QueuedEnvelope>()
                    while (rs.next()) {
                        val envelope = SealedEnvelope(
                            version = 1,
                            originDeviceId = rs.getString("origin_device_id"),
                            msgId = rs.getString("msg_id"),
                            hops = rs.getInt("hops"),
                            counter = rs.getLong("counter"),
                            kind = rs.getString("kind"),
                            recipientDeviceIds = listOf(rs.getString("target_device_id")),
                            payload = rs.getBytes("payload") ?: ByteArray(0),
                            authTagOrSignature = rs.getString("auth_tag"),
                            timestamp = rs.getLong("created_at"),
                        )
                        results.add(
                            QueuedEnvelope(
                                envelopeId = rs.getString("id"),
                                envelope = envelope,
                                createdAt = rs.getLong("created_at"),
                                expiresAt = rs.getLong("expires_at"),
                            )
                        )
                    }
                    results
                }
            }
        }
    }

    /**
     * Marks envelopes as delivered by internal primary key IDs.
     */
    override fun markDelivered(envelopeIds: List<String>): Int {
        if (envelopeIds.isEmpty()) return 0
        return database.withConnection { conn ->
            val placeholders = envelopeIds.joinToString(",") { "?" }
            val sql = "UPDATE mailbox_envelopes SET delivered = 1 WHERE id IN ($placeholders)"
            conn.prepareStatement(sql).use { stmt ->
                envelopeIds.forEachIndexed { index, id ->
                    stmt.setString(index + 1, id)
                }
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Marks envelope as delivered matching target device and origin message ID.
     */
    override fun markDelivered(targetDeviceId: String, msgId: String): Boolean {
        return database.withConnection { conn ->
            val sql = "UPDATE mailbox_envelopes SET delivered = 1 WHERE target_device_id = ? AND msg_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, targetDeviceId)
                stmt.setString(2, msgId)
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Implements [com.transfer.flash.server.relay.MailboxQueueSeam] to mark envelopes delivered by msgId list.
     */
    override suspend fun markEnvelopesDelivered(targetDeviceId: String, msgIds: List<String>): Int {
        if (msgIds.isEmpty()) return 0
        return database.withConnection { conn ->
            val placeholders = msgIds.joinToString(",") { "?" }
            val sql = "UPDATE mailbox_envelopes SET delivered = 1 WHERE target_device_id = ? AND msg_id IN ($placeholders)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, targetDeviceId)
                msgIds.forEachIndexed { index, id ->
                    stmt.setString(index + 2, id)
                }
                stmt.executeUpdate()
            }
        }
    }

    override fun getQueuedCount(targetDeviceId: String): Int {
        return database.withConnection { conn ->
            queryQueuedCount(conn, targetDeviceId)
        }
    }

    override fun getQueuedBytes(targetDeviceId: String): Long {
        return database.withConnection { conn ->
            queryQueuedBytes(conn, targetDeviceId)
        }
    }

    override fun deleteExpired(): Int {
        return database.withConnection { conn ->
            val sql = "DELETE FROM mailbox_envelopes WHERE expires_at < ? AND expires_at > 0"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    private fun checkDuplicate(
        conn: Connection,
        originDeviceId: String,
        msgId: String,
        targetDeviceId: String,
    ): String? {
        val sql = """
            SELECT id FROM mailbox_envelopes 
            WHERE origin_device_id = ? AND msg_id = ? AND target_device_id = ?
            LIMIT 1
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, originDeviceId)
            stmt.setString(2, msgId)
            stmt.setString(3, targetDeviceId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString("id") else null
            }
        }
    }

    private fun queryQueuedCount(conn: Connection, targetDeviceId: String): Int {
        val sql = """
            SELECT COUNT(*) FROM mailbox_envelopes 
            WHERE target_device_id = ? AND delivered = 0 AND (expires_at > ? OR expires_at = 0)
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, targetDeviceId)
            stmt.setLong(2, System.currentTimeMillis())
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private fun queryQueuedBytes(conn: Connection, targetDeviceId: String): Long {
        val sql = """
            SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM mailbox_envelopes 
            WHERE target_device_id = ? AND delivered = 0 AND (expires_at > ? OR expires_at = 0)
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, targetDeviceId)
            stmt.setLong(2, System.currentTimeMillis())
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }
}
