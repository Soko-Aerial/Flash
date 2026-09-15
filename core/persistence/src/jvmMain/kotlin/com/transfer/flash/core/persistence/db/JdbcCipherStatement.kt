package com.transfer.flash.core.persistence.db

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteStatement
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * A [SQLiteStatement] over one JDBC `PreparedStatement`.
 *
 * Two impedance mismatches are the whole of this class, and both are silent if you get them wrong:
 *
 * 1. **Index bases differ between the two halves of the interface.** `bind*` is 1-based on both
 *    sides — fine. `get*` is **0-based** in `androidx.sqlite` and 1-based in JDBC, so every read
 *    adds one. Getting this backwards does not throw; it returns the neighbouring column, which is
 *    how a "message text" can quietly become a "sender id".
 * 2. **`reset()` keeps bindings, `clearBindings()` drops them.** The interface's KDoc is explicit
 *    ("Any parameter bound via the `bind*()` APIs will retain their value"), and Room's generated
 *    code depends on it: it prepares once, binds, steps, then resets and re-binds for the next row
 *    batch. Clearing parameters in `reset()` — the intuitive thing, and what JDBC's own
 *    `clearParameters()` invites — would blank every binding on each rewind.
 */
internal class JdbcCipherStatement(
    private val statement: PreparedStatement,
) : SQLiteStatement {

    private var resultSet: ResultSet? = null

    /**
     * Whether [step] has already executed the statement.
     *
     * Needed because `statement.execute()` must run exactly once: a non-query (INSERT/UPDATE/DDL)
     * produces no `ResultSet` at all, so without this flag the second `step()` would re-run the
     * statement instead of reporting "no more rows" — and re-running an INSERT is not the harmless
     * no-op it looks like.
     */
    private var executed = false

    override fun step(): Boolean {
        resultSet?.let { return it.next() }
        if (executed) return false
        executed = true
        if (!statement.execute()) return false
        val produced = statement.resultSet ?: return false
        resultSet = produced
        return produced.next()
    }

    /** Rewinds for re-execution, per the interface contract, **retaining** any bindings. */
    override fun reset() {
        resultSet?.let { runCatching { it.close() } }
        resultSet = null
        executed = false
    }

    override fun clearBindings() {
        runCatching { statement.clearParameters() }
    }

    // ---- binds: 1-based on both sides ----

    override fun bindBlob(index: Int, value: ByteArray) = statement.setBytes(index, value)

    override fun bindDouble(index: Int, value: Double) = statement.setDouble(index, value)

    override fun bindLong(index: Int, value: Long) = statement.setLong(index, value)

    override fun bindText(index: Int, value: String) = statement.setString(index, value)

    override fun bindNull(index: Int) = statement.setNull(index, Types.NULL)

    // ---- reads: 0-based here, 1-based in JDBC ----

    override fun getBlob(index: Int): ByteArray = rows().getBytes(index + 1)

    override fun getDouble(index: Int): Double = rows().getDouble(index + 1)

    override fun getLong(index: Int): Long = rows().getLong(index + 1)

    override fun getText(index: Int): String =
        checkNotNull(rows().getString(index + 1)) {
            "column $index is NULL; callers must check isNull() first"
        }

    override fun isNull(index: Int): Boolean = rows().getObject(index + 1) == null

    /**
     * Column metadata comes from the **prepared statement**, not from a result row.
     *
     * This is the one place the two halves of the interface genuinely disagree, and it is not
     * optional: Room resolves column indices at *prepare* time — `columnIndexOf` calls
     * `getColumnCount()` and then `getColumnName(i)` for each column, before any `step()`. Reading
     * these off a `ResultSet` therefore fails with "no current row" on the very first query. JDBC's
     * `PreparedStatement.getMetaData()` is the equivalent, and it is available without executing.
     *
     * A live result set still wins when there is one: it is the more accurate source for computed
     * columns (`SELECT COUNT(*) AS c`), which a prepared statement may not type.
     */
    private fun columnMeta() = resultSet?.metaData ?: statement.metaData

    override fun getColumnCount(): Int = columnMeta()?.columnCount ?: 0

    override fun getColumnName(index: Int): String = columnMeta()?.getColumnName(index + 1).orEmpty()

    /**
     * Maps JDBC's `java.sql.Types` onto the five `SQLITE_DATA_*` values Room's generated code
     * switches on.
     *
     * The `else` branch answers TEXT rather than throwing. A driver that threw on an unexpected type
     * would turn any JDBC quirk into a hard failure for a value SQLite could still hand back as a
     * string; TEXT is the permissive reading, and SQLite's own dynamic typing makes it the closest
     * thing to a correct default. A null metadata (some statements are unanalysable) takes the same
     * path.
     */
    override fun getColumnType(index: Int): Int =
        when (columnMeta()?.getColumnType(index + 1)) {
            Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT, Types.BOOLEAN ->
                SQLITE_DATA_INTEGER
            Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL ->
                SQLITE_DATA_FLOAT
            Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY ->
                SQLITE_DATA_BLOB
            Types.NULL -> SQLITE_DATA_NULL
            else -> SQLITE_DATA_TEXT
        }

    override fun close() {
        resultSet?.let { runCatching { it.close() } }
        resultSet = null
        runCatching { statement.close() }
    }

    private fun rows(): ResultSet =
        resultSet ?: error("statement has no current row; call step() and check its result first")
}
