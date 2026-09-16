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

    /**
     * Guards ALL state below. Room's statement cache hands one [SQLiteStatement] instance to
     * whatever thread runs the next query for the same SQL — and the live chat repository
     * issues the same DAO read from several collectors at once (`openConversation`'s combine,
     * `sendText`, the inbound handler), all on `Dispatchers.IO`.
     *
     * Measured 2026-09-15: two workers inside `ConversationDao.get` simultaneously, both dying
     * in `getColumnCount` with `SQLite JDBC: inconsistent internal state`. The bytecode says
     * what that is — xerial's `checkCol` throws it when the `ResultSet.colsMeta` array is
     * null, i.e. our `resultSet` field referenced a result set that a concurrent `reset()` /
     * re-`execute()` on this same instance had pulled out from under the reader. Neither
     * `resultSet` nor `executed` was guarded or volatile, so even the *check* raced the
     * mutation. Every method body below therefore runs under this lock; the critical
     * sections are microseconds of field traffic plus the JDBC call itself, so contention
     * is one query's latency, not a bottleneck.
     */
    private val lock = Any()

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

    override fun step(): Boolean = synchronized(lock) {
        // Engine errors surface here — and Room only recognizes ONE type for them:
        // `EntityUpsertAdapter` catches `androidx.sqlite.SQLiteException` to turn a duplicate
        // into an update, matching on the SQLITE_CONSTRAINT_* strings in the message. The raw
        // xerial `org.sqlite.SQLiteException` is a `java.sql.SQLException`, so the catch missed
        // and every duplicate upsert crashed the caller (live: `ConversationDao.upsert` from
        // three chat coroutines at once). Translating preserves the message verbatim, so the
        // match — and the rethrow of genuinely different errors — behaves exactly as on
        // Android. Binds/reads stay raw: Room catches nothing around them, and a failure
        // there is a programming error, not an engine verdict.
        try {
            resultSet?.let { return it.next() }
            if (executed) return false
            executed = true
            if (!statement.execute()) return false
            val produced = statement.resultSet ?: return false
            resultSet = produced
            return produced.next()
        } catch (e: java.sql.SQLException) {
            throw androidx.sqlite.SQLiteException(e.message ?: "SQLite error during step")
        }
    }

    /** Rewinds for re-execution, per the interface contract, **retaining** any bindings. */
    override fun reset() = synchronized(lock) {
        resultSet?.let { runCatching { it.close() } }
        resultSet = null
        executed = false
    }

    override fun clearBindings() {
        runCatching { statement.clearParameters() }
    }

    // ---- binds: 1-based on both sides ----

    override fun bindBlob(index: Int, value: ByteArray) = synchronized(lock) {
        statement.setBytes(index, value)
    }

    override fun bindDouble(index: Int, value: Double) = synchronized(lock) {
        statement.setDouble(index, value)
    }

    override fun bindLong(index: Int, value: Long) = synchronized(lock) {
        statement.setLong(index, value)
    }

    override fun bindText(index: Int, value: String) = synchronized(lock) {
        statement.setString(index, value)
    }

    override fun bindNull(index: Int) = synchronized(lock) {
        statement.setNull(index, Types.NULL)
    }

    // ---- reads: 0-based here, 1-based in JDBC ----

    override fun getBlob(index: Int): ByteArray = synchronized(lock) {
        rows().getBytes(index + 1)
    }

    override fun getDouble(index: Int): Double = synchronized(lock) {
        rows().getDouble(index + 1)
    }

    override fun getLong(index: Int): Long = synchronized(lock) {
        rows().getLong(index + 1)
    }

    override fun getText(index: Int): String = synchronized(lock) {
        checkNotNull(rows().getString(index + 1)) {
            "column $index is NULL; callers must check isNull() first"
        }
    }

    override fun isNull(index: Int): Boolean = synchronized(lock) {
        rows().getObject(index + 1) == null
    }

    /**
     * Column metadata, snapshotted once from JDBC and served from memory afterwards.
     *
     * Two facts force this shape, and both were measured, not reasoned:
     *
     * 1. Room resolves column indices at *prepare* time — `columnIndexOf` calls
     *    `getColumnCount()` and then `getColumnName(i)` for each column, before any `step()`.
     *    Reading these off a `ResultSet` therefore fails with "no current row" on the very
     *    first query. JDBC's `PreparedStatement.getMetaData()` is the equivalent, and it is
     *    available without executing — but ONLY while fresh (see 2).
     * 2. xerial binds a statement's metadata object to its current result set: once that
     *    result set is closed, `statement.metaData` throws `SQLite JDBC: inconsistent internal
     *    state` on ANY read (probed 2026-09-15: fresh/bind/live all fine, post-close always
     *    throws, re-execute heals). Room reuses cached statements across queries with a
     *    `reset()` between them — and our `reset()` closes the result set — so the SECOND
     *    query's prepare-time metadata read died on the first query's corpse. That is the
     *    live desktop-chat crash: `ConversationDao.get` → `getColumnCount`, on workers that
     *    had simply queried before.
     *
     * Snapshotting is sound because metadata is a pure function of the SQL text, and one
     * statement instance compiles exactly one SQL string: reset/rebind/close cannot change
     * the columns. A live result set is still preferred for the snapshot itself — it types
     * computed columns (`SELECT COUNT(*) AS c`) that a prepared statement may not.
     *
     * The closed-check below stays: it decides WHICH source to snapshot from, so a dead
     * result set degrades to the statement metadata instead of throwing mid-snapshot.
     */
    private data class MetaSnapshot(val names: List<String>, val types: List<Int>)

    private var metaSnapshot: MetaSnapshot? = null

    private fun snapshot(): MetaSnapshot = synchronized(lock) {
        metaSnapshot ?: run {
            val live = resultSet
                ?.takeUnless { runCatching { it.isClosed }.getOrDefault(true) }
                ?.metaData
            val meta = live ?: statement.metaData
            val count = meta.columnCount
            MetaSnapshot(
                names = List(count) { meta.getColumnName(it + 1) },
                types = List(count) { mapColumnType(meta.getColumnType(it + 1)) },
            ).also { metaSnapshot = it }
        }
    }

    override fun getColumnCount(): Int = snapshot().names.size

    override fun getColumnName(index: Int): String = snapshot().names.getOrElse(index) { "" }

    /**
     * Maps JDBC's `java.sql.Types` onto the five `SQLITE_DATA_*` values Room's generated code
     * switches on.
     *
     * The `else` branch answers TEXT rather than throwing. A driver that threw on an unexpected type
     * would turn any JDBC quirk into a hard failure for a value SQLite could still hand back as a
     * string; TEXT is the permissive reading, and SQLite's own dynamic typing makes it the closest
     * thing to a correct default.
     */
    override fun getColumnType(index: Int): Int =
        snapshot().types.getOrElse(index) { SQLITE_DATA_TEXT }

    private fun mapColumnType(jdbcType: Int): Int =
        when (jdbcType) {
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
        synchronized(lock) {
            resultSet?.let { runCatching { it.close() } }
            resultSet = null
            runCatching { statement.close() }
        }
    }

    private fun rows(): ResultSet =
        resultSet ?: error("statement has no current row; call step() and check its result first")
}
