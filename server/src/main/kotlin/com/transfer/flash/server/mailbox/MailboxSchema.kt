package com.transfer.flash.server.mailbox

import com.transfer.flash.server.storage.Migration
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import java.sql.Connection

/**
 * The `mailbox` schema component (ADR-111, [Migrator]).
 *
 * - **v1** is the table exactly as the first hub build created it (so a database written by that build is recognised
 *   and a fresh one starts from the same point). No behaviour: `CREATE ... IF NOT EXISTS`.
 * - **v2** (REL-03, REL-08, REL-09, REL-11, REL-15) replaces it:
 *   - `seq INTEGER PRIMARY KEY AUTOINCREMENT`: a monotonic arrival order that does not depend on any clock;
 *   - `UNIQUE(origin_device_id, msg_id, target_device_id)`: a duplicate send is a no-op, not a second row;
 *   - `created_at` / `expires_at` are SERVER times; the sender's own timestamp survives only as `sent_at`;
 *   - `payload_bytes`: the stored size, so quota checks never read payloads;
 *   - `group_id`: the group a fanned-out message belongs to (null for a direct message), so a replay still names it;
 *   - the unused `hops`, `counter`, `auth_tag` and `delivered` columns are gone (the relay wire carries none of them
 *     and a delivered row is deleted, REL-11);
 *   - indexes for the page fetch `(target_device_id, seq)`, for the expiry sweep `(expires_at)` and for the per-sender
 *     quota `(origin_device_id)`;
 *   - `mailbox_totals` (one row) kept exact by triggers, so the global cap is O(1) instead of a table scan.
 *   Rows of the old table that were not delivered are carried over, in their old order, with fresh server times.
 * - **v3** (ADR-130, FO-15a) adds `sealed`: 1 when `payload` is AES-256-GCM ciphertext under the hub data key. A marker column, not a
 *   byte prefix, because a payload is arbitrary sender-controlled bytes. `payload_bytes` keeps the PLAIN size, so the quotas and the
 *   totals triggers do not change.
 * - **v4** (RLY-01, ADR-222) adds `undeliverable_reason`: set when a sealed row can not be opened (wrong or lost data key, a row moved to
 *   another message id). A quarantined row is kept (a restored key can still read it until the TTL sweeper removes it) but is never
 *   fetched again, so one unreadable row can not stop a device's drain. The partial index serves the page fetch, which only wants
 *   the others.
 */
object MailboxSchema {
    const val COMPONENT = "mailbox"

    /** Rows migrated from v1 get this lifetime counted from the migration time. */
    const val MIGRATED_TTL_MS: Long = MailboxLimits.DEFAULT_TTL_MS

    fun steps(clock: () -> Long = System::currentTimeMillis): List<Migration> = listOf(
        Migration(1, "baseline mailbox table of the first hub build") { conn -> baseline(conn) },
        Migration(2, "server-clock ordering, unique dedup key, quotas, totals") { conn -> v2(conn, clock()) },
        Migration(3, "sealed marker: payloads encrypted at rest under the hub data key (ADR-130)") { conn ->
            conn.createStatement().use { it.execute("ALTER TABLE mailbox_envelopes ADD COLUMN sealed INTEGER NOT NULL DEFAULT 0") }
        },
        Migration(4, "quarantine marker for rows that can not be opened (RLY-01)") { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE mailbox_envelopes ADD COLUMN undeliverable_reason TEXT")
                stmt.execute(
                    "CREATE INDEX idx_mailbox_target_deliverable ON mailbox_envelopes(target_device_id, seq) WHERE undeliverable_reason IS NULL",
                )
            }
        },
    )

    /** Registers and applies the mailbox migrations on [database]. Idempotent; throws (fail closed) when a step fails. */
    fun migrate(database: ServerDatabase, clock: () -> Long = System::currentTimeMillis) {
        val migrator = Migrator(database)
        migrator.register(COMPONENT, steps(clock))
        migrator.migrate()
    }

    private fun baseline(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS mailbox_envelopes (
                    id TEXT PRIMARY KEY,
                    origin_device_id TEXT NOT NULL,
                    target_device_id TEXT NOT NULL,
                    msg_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    hops INTEGER NOT NULL DEFAULT 0,
                    counter INTEGER NOT NULL DEFAULT 0,
                    payload BLOB NOT NULL,
                    auth_tag TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    private fun v2(conn: Connection, now: Long) {
        conn.createStatement().use { stmt ->
            stmt.execute("DROP INDEX IF EXISTS idx_mailbox_target_delivered_expires")
            stmt.execute("DROP INDEX IF EXISTS idx_mailbox_origin_msg")
            stmt.execute("ALTER TABLE mailbox_envelopes RENAME TO mailbox_envelopes_v1")
            stmt.execute(
                """
                CREATE TABLE mailbox_envelopes (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    origin_device_id TEXT NOT NULL,
                    target_device_id TEXT NOT NULL,
                    msg_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    payload_bytes INTEGER NOT NULL,
                    group_id TEXT,
                    sent_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    UNIQUE (origin_device_id, msg_id, target_device_id)
                )
                """.trimIndent(),
            )
        }
        // Undelivered rows keep their relative order (old client-clock created_at, then insertion order) but get server
        // times; a row the old build stored as "never expires" (expires_at = 0) now expires like every other.
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO mailbox_envelopes
                (origin_device_id, target_device_id, msg_id, kind, payload, payload_bytes, sent_at, created_at, expires_at)
            SELECT origin_device_id, target_device_id, msg_id, kind, payload, LENGTH(payload),
                   MIN(MAX(created_at, 1), ?), ?, ?
            FROM mailbox_envelopes_v1
            WHERE delivered = 0
            ORDER BY created_at ASC, rowid ASC
            """.trimIndent(),
        ).use { stmt ->
            stmt.setLong(1, now)
            stmt.setLong(2, now)
            stmt.setLong(3, now + MIGRATED_TTL_MS)
            stmt.executeUpdate()
        }
        conn.createStatement().use { stmt ->
            stmt.execute("DROP TABLE mailbox_envelopes_v1")
            stmt.execute("CREATE INDEX idx_mailbox_target_seq ON mailbox_envelopes(target_device_id, seq)")
            stmt.execute("CREATE INDEX idx_mailbox_expires ON mailbox_envelopes(expires_at)")
            stmt.execute("CREATE INDEX idx_mailbox_origin ON mailbox_envelopes(origin_device_id)")
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS mailbox_totals (k INTEGER PRIMARY KEY CHECK (k = 1), rows INTEGER NOT NULL, bytes INTEGER NOT NULL)",
            )
            stmt.execute(
                """
                INSERT OR REPLACE INTO mailbox_totals(k, rows, bytes)
                SELECT 1, COUNT(*), COALESCE(SUM(payload_bytes), 0) FROM mailbox_envelopes
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TRIGGER mailbox_totals_ai AFTER INSERT ON mailbox_envelopes BEGIN
                    UPDATE mailbox_totals SET rows = rows + 1, bytes = bytes + NEW.payload_bytes WHERE k = 1;
                END
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TRIGGER mailbox_totals_ad AFTER DELETE ON mailbox_envelopes BEGIN
                    UPDATE mailbox_totals SET rows = rows - 1, bytes = bytes - OLD.payload_bytes WHERE k = 1;
                END
                """.trimIndent(),
            )
        }
    }
}
