package com.transfer.flash.server.mailbox

import com.transfer.flash.server.auth.SecretBox
import com.transfer.flash.server.observability.MailboxStats
import com.transfer.flash.server.observability.RelayMetrics
import com.transfer.flash.server.storage.ServerDatabase
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable store-and-forward mailbox over [ServerDatabase] (schema: [MailboxSchema]).
 *
 * What this class guarantees (ADR-112, REL-02/03/07/08/09/11):
 * - **Idempotent enqueue.** `UNIQUE(origin_device_id, msg_id, target_device_id)` plus `ON CONFLICT DO NOTHING`: a retry
 *   returns [EnqueueResult.DuplicateQueued], concurrent duplicates store ONE row.
 * - **Atomic quotas.** The duplicate check, the per-recipient / per-sender / global caps and the insert run in one
 *   `BEGIN IMMEDIATE` transaction, so two concurrent senders can not both pass a cap that only has room for one.
 * - **Server time only.** `created_at` and `expires_at` come from [clock]; the sender's `sentAt` is stored as
 *   informational data and clamped when absurd, and a client can never lengthen the lifetime ([limits]`.ttlMs` is the cap).
 * - **Ack-driven removal.** [fetchPage] never removes; [acknowledge] (from `FLASH_RECV_ACK`) deletes; [deleteExpired]
 *   deletes the rest. Delivered payloads do not linger (REL-11).
 * - **Opaque payload.** Stored as the UTF-8 bytes of the string the sender wrote and returned verbatim.
 * - **Off the event loop.** Every suspend function runs its JDBC on `Dispatchers.IO`.
 *
 * Construction applies the `mailbox` migrations (idempotent) and throws if one fails, so the hub does not start on a
 * schema it can not use.
 *
 * @param registry rejects a recipient that was never enrolled (REL-08). The default accepts everything: production
 *   wiring must pass a real registry (see `DeviceRegistry`).
 */
class MailboxQueue(
    private val database: ServerDatabase,
    val limits: MailboxLimits = MailboxLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val registry: DeviceRegistry = DeviceRegistry.AcceptAll,
    private val metrics: RelayMetrics? = null,
    /** The hub data key (ADR-130): payloads are stored sealed when it is active. [SecretBox.None] stores them as given (tests). */
    private val secrets: SecretBox = SecretBox.None,
) : MailboxStore, MailboxStats {

    private val logger = LoggerFactory.getLogger(MailboxQueue::class.java)
    private val delivered = AtomicLong()
    private val expired = AtomicLong()
    private val dropped = AtomicLong()
    private val quarantined = AtomicLong()

    init {
        MailboxSchema.migrate(database, clock)
        sealLegacyPayloads()
    }

    /** What a payload is bound to, so a sealed value cannot be moved to another row (length-prefixed: no ambiguous joins). */
    private fun payloadContext(origin: String, target: String, msgId: String): String =
        "mailbox_envelopes.payload|${origin.length}:$origin|${target.length}:$target|${msgId.length}:$msgId"

    /**
     * ADR-130: rows written before the data key was used for payloads are sealed in place at startup, in small transactions. Idempotent
     * (it only touches `sealed = 0`); a hub without a data key leaves them alone.
     */
    private fun sealLegacyPayloads() {
        if (!secrets.isActive) return
        var total = 0
        while (true) {
            val batch = database.withTransaction { conn ->
                val rows = conn.prepareStatement(
                    "SELECT seq, origin_device_id, target_device_id, msg_id, payload FROM mailbox_envelopes WHERE sealed = 0 LIMIT 200",
                ).use { st ->
                    st.executeQuery().use { rs ->
                        val out = ArrayList<Array<Any?>>()
                        while (rs.next()) out.add(arrayOf(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5)))
                        out
                    }
                }
                conn.prepareStatement("UPDATE mailbox_envelopes SET payload = ?, sealed = 1 WHERE seq = ?").use { up ->
                    for (r in rows) {
                        val plain = (r[4] as ByteArray?) ?: ByteArray(0)
                        up.setBytes(1, secrets.sealBytes(plain, payloadContext(r[1] as String, r[2] as String, r[3] as String)))
                        up.setLong(2, r[0] as Long)
                        up.addBatch()
                    }
                    up.executeBatch()
                }
                rows.size
            }
            total += batch
            if (batch < 200) break
        }
        if (total > 0) logger.info("Sealed {} queued message payload(s) that were stored in plain form (ADR-130)", total)
    }

    // ---- MailboxStore ----------------------------------------------------------------------------------------

    override suspend fun enqueue(message: MailboxMessage, ttlOverrideMs: Long?): EnqueueResult {
        val payloadBytes = message.payload.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > limits.maxPayloadBytes) {
            return refused(QuotaScope.PAYLOAD, "payload of ${payloadBytes.size} bytes exceeds ${limits.maxPayloadBytes}")
        }
        val known = try {
            registry.isEnrolled(message.target)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return failed("registry", t)
        }
        if (!known) {
            dropped.incrementAndGet()
            metrics?.inc(RelayMetrics.MAILBOX_EVENTS, 1, "event" to "dropped")
            return EnqueueResult.UnknownRecipient
        }
        val result = try {
            database.ioTransaction { conn -> insert(conn, message, payloadBytes, ttlOverrideMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return failed("enqueue", t)
        }
        if (result is EnqueueResult.QuotaExceeded) {
            dropped.incrementAndGet()
            metrics?.inc(RelayMetrics.MAILBOX_EVENTS, 1, "event" to "dropped")
        }
        return result
    }

    private fun refused(scope: QuotaScope, reason: String): EnqueueResult {
        dropped.incrementAndGet()
        metrics?.inc(RelayMetrics.MAILBOX_EVENTS, 1, "event" to "dropped")
        return EnqueueResult.QuotaExceeded(scope, reason)
    }

    private fun failed(operation: String, t: Throwable): EnqueueResult {
        dropped.incrementAndGet()
        metrics?.inc(RelayMetrics.DB_ERRORS, 1, "op" to operation)
        logger.error("Mailbox {} failed: {}", operation, t.toString())
        return EnqueueResult.Failed(t)
    }

    private fun insert(conn: Connection, m: MailboxMessage, payload: ByteArray, ttlOverrideMs: Long?): EnqueueResult {
        val now = clock()

        // A quarantined copy (RLY-01) is not a delivery in progress: the sender was told DROPPED, so its resend must not be swallowed
        // as a duplicate of a row that will never be delivered. Nor is a row whose lifetime has run out but which the sweeper has not
        // removed yet: no recipient can fetch it, so answering the resend "duplicate" would lose the message when the sweep runs.
        conn.prepareStatement(
            "DELETE FROM mailbox_envelopes WHERE origin_device_id = ? AND msg_id = ? AND target_device_id = ? " +
                "AND (undeliverable_reason IS NOT NULL OR expires_at <= ?)",
        ).use { stmt ->
            stmt.setString(1, m.origin)
            stmt.setString(2, m.msgId)
            stmt.setString(3, m.target)
            stmt.setLong(4, now)
            stmt.executeUpdate()
        }
        existingSeq(conn, m)?.let { return EnqueueResult.DuplicateQueued(it) }

        val size = payload.size.toLong()
        val (recipientRows, recipientBytes) = usage(conn, "target_device_id", m.target, now)
        if (recipientRows >= limits.perRecipientRows) {
            return EnqueueResult.QuotaExceeded(QuotaScope.RECIPIENT, "recipient mailbox holds ${limits.perRecipientRows} messages")
        }
        if (recipientBytes + size > limits.perRecipientBytes) {
            return EnqueueResult.QuotaExceeded(QuotaScope.RECIPIENT, "recipient mailbox byte quota ${limits.perRecipientBytes} exceeded")
        }
        val (senderRows, senderBytes) = usage(conn, "origin_device_id", m.origin, now)
        if (senderRows >= limits.perSenderRows) {
            return EnqueueResult.QuotaExceeded(QuotaScope.SENDER, "sender has ${limits.perSenderRows} undelivered messages")
        }
        if (senderBytes + size > limits.perSenderBytes) {
            return EnqueueResult.QuotaExceeded(QuotaScope.SENDER, "sender byte quota ${limits.perSenderBytes} exceeded")
        }
        val (totalRows, totalBytes) = totals(conn)
        if (totalRows >= limits.globalRows || totalBytes + size > limits.globalBytes) {
            return EnqueueResult.QuotaExceeded(QuotaScope.GLOBAL, "hub mailbox is full")
        }

        val lifetime = ttlOverrideMs?.takeIf { it > 0 }?.coerceAtMost(limits.ttlMs) ?: limits.ttlMs
        val sentAt = if (m.sentAt in 1..(now + limits.maxClockSkewMs)) m.sentAt else now

        val inserted = conn.prepareStatement(
            """
            INSERT INTO mailbox_envelopes
                (origin_device_id, target_device_id, msg_id, kind, payload, payload_bytes, group_id, sent_at, created_at, expires_at, sealed)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(origin_device_id, msg_id, target_device_id) DO NOTHING
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, m.origin)
            stmt.setString(2, m.target)
            stmt.setString(3, m.msgId)
            stmt.setString(4, m.type)
            stmt.setBytes(5, if (secrets.isActive) secrets.sealBytes(payload, payloadContext(m.origin, m.target, m.msgId)) else payload)
            stmt.setLong(6, size)
            stmt.setString(7, m.group)
            stmt.setLong(8, sentAt)
            stmt.setLong(9, now)
            stmt.setLong(10, now + lifetime)
            stmt.setInt(11, if (secrets.isActive) 1 else 0)
            stmt.executeUpdate()
        }
        if (inserted == 0) return EnqueueResult.DuplicateQueued(existingSeq(conn, m) ?: 0L)
        val seq = conn.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
        return EnqueueResult.Queued(seq)
    }

    private fun existingSeq(conn: Connection, m: MailboxMessage): Long? =
        conn.prepareStatement(
            "SELECT seq FROM mailbox_envelopes WHERE origin_device_id = ? AND msg_id = ? AND target_device_id = ?",
        ).use { stmt ->
            stmt.setString(1, m.origin)
            stmt.setString(2, m.msgId)
            stmt.setString(3, m.target)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    /**
     * Unexpired rows and bytes still waiting for/by [who] (`column` is a fixed internal name, never client input).
     *
     * A quarantined row (RLY-01) is NOT waiting: its sender was told `DROPPED` and its recipient will never receive it, so it must not
     * count against [MailboxLimits.perRecipientRows]/[MailboxLimits.perSenderRows]. Before this filter a hub whose data key changed
     * locked each affected device out of its own mailbox for the whole TTL, once 1,000 unopenable rows had piled up (2026-10-22 review F1).
     * The rows still occupy disk until the TTL sweeps them, so [totals] (the global budget) keeps counting them — it is the triggers in
     * [MailboxSchema] that maintain it, never this class.
     */
    private fun usage(conn: Connection, column: String, who: String, now: Long): Pair<Long, Long> =
        conn.prepareStatement(
            "SELECT COUNT(*), COALESCE(SUM(payload_bytes), 0) FROM mailbox_envelopes " +
                "WHERE $column = ? AND expires_at > ? AND undeliverable_reason IS NULL",
        ).use { stmt ->
            stmt.setString(1, who)
            stmt.setLong(2, now)
            stmt.executeQuery().use { rs -> rs.next(); rs.getLong(1) to rs.getLong(2) }
        }

    private fun totals(conn: Connection): Pair<Long, Long> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT rows, bytes FROM mailbox_totals WHERE k = 1").use { rs ->
                if (rs.next()) rs.getLong(1) to rs.getLong(2) else 0L to 0L
            }
        }

    override suspend fun fetchPage(target: String, afterSeq: Long, limit: Int): List<QueuedMessage> =
        fetchPageDetailed(target, afterSeq, limit).messages

    /**
     * RLY-01 (ADR-222): a row that can not be opened is quarantined (`undeliverable_reason`) and skipped, and the page is filled from the
     * rows behind it, so `LIMIT` counts only rows that can be delivered and one bad page can not end a drain. The quarantined rows are
     * returned so the router can tell the senders. If marking a row fails the exception reaches the caller (the router cuts the session
     * and the device drains afresh); it is never swallowed into an endless loop.
     */
    override suspend fun fetchPageDetailed(target: String, afterSeq: Long, limit: Int): MailboxPage {
        val wanted = limit.coerceIn(1, 1000)
        val messages = ArrayList<QueuedMessage>()
        val bad = ArrayList<QuarantinedRow>()
        var cursor = afterSeq
        var rounds = 0
        while (messages.size < wanted && rounds++ < MAX_QUARANTINE_ROUNDS) {
            val ask = wanted - messages.size
            val scan = scanRows(target, cursor, ask)
            messages += scan.messages
            if (scan.bad.isNotEmpty()) {
                markQuarantined(scan.bad)
                bad += scan.bad.map { it.row }
            }
            if (scan.scanned < ask) break // fewer rows than asked for: nothing is left behind them
            cursor = scan.lastSeq
        }
        if (bad.isNotEmpty()) {
            quarantined.addAndGet(bad.size.toLong())
            metrics?.inc(RelayMetrics.MAILBOX_EVENTS, bad.size.toLong(), "event" to "quarantined")
            logger.error(
                "Mailbox of device {}: {} queued message(s) cannot be opened (data key lost or changed?); quarantined, the senders are told DROPPED, the rows expire with the TTL",
                target.take(DEVICE_ID_LOG_CHARS), bad.size,
            )
        }
        return MailboxPage(messages, bad)
    }

    private class BadRow(val row: QuarantinedRow, val reason: String)

    private class Scan(val messages: List<QueuedMessage>, val bad: List<BadRow>, val scanned: Int, val lastSeq: Long)

    private suspend fun scanRows(target: String, afterSeq: Long, limit: Int): Scan {
        val now = clock()
        return database.io { conn ->
            conn.prepareStatement(
                """
                SELECT seq, origin_device_id, target_device_id, msg_id, kind, payload, sent_at, created_at, expires_at, group_id, sealed
                FROM mailbox_envelopes
                WHERE target_device_id = ? AND seq > ? AND expires_at > ? AND undeliverable_reason IS NULL
                ORDER BY seq ASC
                LIMIT ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, target)
                stmt.setLong(2, afterSeq)
                stmt.setLong(3, now)
                stmt.setInt(4, limit)
                stmt.executeQuery().use { rs ->
                    val rows = ArrayList<QueuedMessage>()
                    val bad = ArrayList<BadRow>()
                    var scanned = 0
                    var lastSeq = afterSeq
                    while (rs.next()) {
                        scanned++
                        lastSeq = rs.getLong(1)
                        val raw = rs.getBytes(6) ?: ByteArray(0)
                        val plain = if (rs.getInt(11) == 1) {
                            try {
                                secrets.openBytes(raw, payloadContext(rs.getString(2), rs.getString(3), rs.getString(4)))
                            } catch (e: RuntimeException) {
                                // Wrong or missing data key, or a tampered row. Only the exception class is kept, never payload bytes.
                                bad.add(
                                    BadRow(
                                        QuarantinedRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                                        "unopenable:" + e.javaClass.simpleName,
                                    ),
                                )
                                continue
                            }
                        } else {
                            raw
                        }
                        rows.add(
                            QueuedMessage(
                                seq = rs.getLong(1),
                                origin = rs.getString(2),
                                target = rs.getString(3),
                                msgId = rs.getString(4),
                                type = rs.getString(5),
                                payload = String(plain, Charsets.UTF_8),
                                sentAt = rs.getLong(7),
                                createdAt = rs.getLong(8),
                                expiresAt = rs.getLong(9),
                                group = rs.getString(10),
                            ),
                        )
                    }
                    Scan(rows, bad, scanned, lastSeq)
                }
            }
        }
    }

    private suspend fun markQuarantined(rows: List<BadRow>) {
        database.ioTransaction { conn ->
            conn.prepareStatement("UPDATE mailbox_envelopes SET undeliverable_reason = ? WHERE seq = ?").use { stmt ->
                for (r in rows) {
                    stmt.setString(1, r.reason)
                    stmt.setLong(2, r.row.seq)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    override suspend fun acknowledge(target: String, msgId: String, origin: String?): List<AckedRow> {
        val rows = database.ioTransaction { conn ->
            // One row per acknowledgement: the named sender's copy, or the oldest copy when the device did not say whose it was.
            val found = conn.prepareStatement(
                "SELECT seq, origin_device_id, created_at FROM mailbox_envelopes WHERE target_device_id = ? AND msg_id = ?" +
                    (if (origin != null) " AND origin_device_id = ?" else "") + " ORDER BY seq LIMIT 1",
            ).use { stmt ->
                stmt.setString(1, target)
                stmt.setString(2, msgId)
                if (origin != null) stmt.setString(3, origin)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) Triple(rs.getLong(1), rs.getString(2), rs.getLong(3)) else null
                }
            }
            if (found == null) {
                emptyList()
            } else {
                conn.prepareStatement("DELETE FROM mailbox_envelopes WHERE seq = ?").use { stmt ->
                    stmt.setLong(1, found.first)
                    stmt.executeUpdate()
                }
                listOf(AckedRow(found.second, msgId, found.third))
            }
        }
        if (rows.isNotEmpty()) {
            delivered.addAndGet(rows.size.toLong())
            metrics?.inc(RelayMetrics.MAILBOX_EVENTS, rows.size.toLong(), "event" to "delivered")
            val now = clock()
            for (r in rows) metrics?.mailboxResidence?.observeSeconds((now - r.createdAt) / 1000.0)
        }
        return rows
    }

    override suspend fun deleteExpired(limit: Int): List<ExpiredRow> {
        val now = clock()
        val rows = database.ioTransaction { conn ->
            val found = conn.prepareStatement(
                "SELECT seq, origin_device_id, target_device_id, msg_id, undeliverable_reason FROM mailbox_envelopes WHERE expires_at <= ? ORDER BY expires_at LIMIT ?",
            ).use { stmt ->
                stmt.setLong(1, now)
                stmt.setInt(2, limit.coerceIn(1, 5000))
                stmt.executeQuery().use { rs ->
                    val list = ArrayList<Triple<Long, ExpiredRow, Boolean>>()
                    while (rs.next()) {
                        val seq = rs.getLong(1)
                        val origin = rs.getString(2)
                        val target = rs.getString(3)
                        val msgId = rs.getString(4)
                        val isQuarantined = rs.getString(5) != null
                        list.add(Triple(seq, ExpiredRow(origin, target, msgId), isQuarantined))
                    }
                    list
                }
            }
            if (found.isNotEmpty()) {
                conn.prepareStatement("DELETE FROM mailbox_envelopes WHERE seq = ?").use { stmt ->
                    for ((seq, _, _) in found) {
                        stmt.setLong(1, seq)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
            }
            // F39 / ERROR-775: Quarantined rows are deleted from disk by their own rule (ADR-223),
            // but must not be returned to emit an EXPIRED event because they were already reported
            // as DROPPED when quarantined (RLY-01, ADR-222).
            found.filter { !it.third }.map { it.second }
        }
        if (rows.isNotEmpty()) {
            expired.addAndGet(rows.size.toLong())
            metrics?.inc(RelayMetrics.MAILBOX_EVENTS, rows.size.toLong(), "event" to "expired")
        }
        return rows
    }

    override fun noteDropped(count: Int) {
        dropped.addAndGet(count.toLong())
        metrics?.inc(RelayMetrics.MAILBOX_EVENTS, count.toLong(), "event" to "dropped")
    }

    /** Messages still deliverable to [target] (unexpired and not quarantined). */
    suspend fun pendingCount(target: String): Int = database.io { conn -> usage(conn, "target_device_id", target, clock()).first.toInt() }

    // ---- MailboxStats ----------------------------------------------------------------------------------------

    override fun queuedCount(): Long = database.withConnection { totals(it).first }

    override fun queuedBytes(): Long = database.withConnection { totals(it).second }

    override fun oldestAgeMs(deviceId: String): Long? = database.withConnection { conn ->
        val now = clock()
        conn.prepareStatement(
            "SELECT created_at FROM mailbox_envelopes WHERE target_device_id = ? AND expires_at > ? AND undeliverable_reason IS NULL " +
                "ORDER BY seq ASC LIMIT 1",
        ).use { stmt ->
            stmt.setString(1, deviceId)
            stmt.setLong(2, now)
            stmt.executeQuery().use { rs -> if (rs.next()) (now - rs.getLong(1)).coerceAtLeast(0) else null }
        }
    }

    override fun oldestAgesMs(limit: Int): Map<String, Long> = database.withConnection { conn ->
        val now = clock()
        conn.prepareStatement(
            """
            SELECT target_device_id, MIN(created_at) AS oldest FROM mailbox_envelopes
            WHERE expires_at > ? AND undeliverable_reason IS NULL GROUP BY target_device_id ORDER BY oldest ASC LIMIT ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.setLong(1, now)
            stmt.setInt(2, limit.coerceIn(1, 1000))
            stmt.executeQuery().use { rs ->
                val map = LinkedHashMap<String, Long>()
                while (rs.next()) map[rs.getString(1)] = (now - rs.getLong(2)).coerceAtLeast(0)
                map
            }
        }
    }

    override fun deliveredTotal(): Long = delivered.get()

    override fun expiredTotal(): Long = expired.get()

    override fun droppedTotal(): Long = dropped.get()

    override fun quarantinedTotal(): Long = quarantined.get()

    private companion object {
        /** Pages of a single fetch spent skipping quarantined rows; the router simply asks again after that. */
        const val MAX_QUARANTINE_ROUNDS = 20

        /** A device id is logged by its first characters only. */
        const val DEVICE_ID_LOG_CHARS = 8
    }
}
