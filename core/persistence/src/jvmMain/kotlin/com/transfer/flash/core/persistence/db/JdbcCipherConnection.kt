package com.transfer.flash.core.persistence.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.sql.Connection

/**
 * A [SQLiteConnection] over one JDBC connection.
 *
 * Thin by design: JDBC already models everything the interface asks for, and the two places where it
 * does not — index bases and transaction detection — are stated here rather than buried.
 */
internal class JdbcCipherConnection(
    private val connection: Connection,
) : SQLiteConnection {

    /**
     * Whether a transaction is currently open.
     *
     * Room asks this to decide between a plain `BEGIN` and a nested `SAVEPOINT`, and getting it
     * wrong is not cosmetic: reporting `false` inside an open transaction makes Room re-`BEGIN`, and
     * SQLite rejects a nested `BEGIN` outright — the whole write would fail. JDBC's own signal is
     * `autoCommit`, which is `false` exactly while a transaction is open, so it is the honest
     * answer rather than a guess. (The interface's default implementation throws, so returning
     * something real here is load-bearing rather than defensive.)
     */
    override fun inTransaction(): Boolean = !connection.autoCommit

    override fun prepare(sql: String): SQLiteStatement =
        JdbcCipherStatement(connection.prepareStatement(sql))

    override fun close() {
        runCatching { connection.close() }
    }
}
