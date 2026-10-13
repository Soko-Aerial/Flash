package com.transfer.flash.server.storage

import java.sql.Connection

/**
 * Runs [block] in a `BEGIN IMMEDIATE` transaction on one pooled connection: the write lock is taken up front, so two
 * writers serialise instead of failing half way with `SQLITE_BUSY` (REL-15: the deferred `BEGIN` of
 * [ServerDatabase.withTransaction] upgrades a read lock to a write lock in the middle of a statement and loses).
 *
 * Commits when [block] returns, rolls back when it throws. Never open a second connection from inside [block]: with a
 * single-connection pool (in-memory tests) that would deadlock, so pass the [Connection] along instead.
 */
fun <T> ServerDatabase.immediate(block: (Connection) -> T): T = withConnection { conn ->
    val wasAutoCommit = conn.autoCommit
    conn.autoCommit = true
    var open = false
    try {
        conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        open = true
        val result = block(conn)
        conn.createStatement().use { it.execute("COMMIT") }
        open = false
        result
    } catch (t: Throwable) {
        if (open) {
            try {
                conn.createStatement().use { it.execute("ROLLBACK") }
            } catch (_: Throwable) {
                // The original failure is the one that matters.
            }
        }
        throw t
    } finally {
        conn.autoCommit = wasAutoCommit
    }
}
