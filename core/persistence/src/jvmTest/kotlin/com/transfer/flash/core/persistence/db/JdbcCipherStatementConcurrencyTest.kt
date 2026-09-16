package com.transfer.flash.core.persistence.db

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Regression suite for the live `SQLite JDBC: inconsistent internal state` crash
 * (2026-09-15, desktop chat send/receive run).
 *
 * The crash died in [JdbcCipherStatement.getColumnCount], called by Room at prepare time
 * through `ConnectionWithLock$CachedStatement`, on TWO workers inside one DAO read at once.
 * Bytecode evidence (`CoreResultSet.checkCol` throws that exact message when the result
 * set's column table is null) plus code evidence (the wrapper's `resultSet`/`executed`
 * fields had no guard at all) says a shared cached statement was pulled out from under
 * its reader by a concurrent `reset()`/re-`execute()` on another thread. Every method on
 * the statement is now serialized, and metadata reads degrade past dead result sets.
 *
 * What this suite does NOT do, deliberately: overlap full bind/step/read/reset cycles on
 * one statement across threads. Per-method locking makes each call atomic, but a CYCLE is
 * not atomic — Room serializes those per connection itself (`ConnectionWithLock`), and a
 * test asserting otherwise would pin a guarantee nothing provides.
 */
class JdbcCipherStatementConcurrencyTest {

    private val tempDirs = mutableListOf<File>()
    private var openConnection: JdbcCipherConnection? = null

    @AfterTest
    fun tearDown() {
        runCatching { openConnection?.close() }
        openConnection = null
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun openDb(): JdbcCipherConnection {
        Class.forName("org.sqlite.JDBC")
        val dir = Files.createTempDirectory("flash-stmt-concurrency").toFile()
        tempDirs += dir
        val connection = JdbcCipherConnection(
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + File(dir, "t.db").absolutePath.replace('\\', '/'),
                org.sqlite.mc.SQLiteMCConfig.Builder().withKey(KEY).build().toProperties(),
            ),
        )
        openConnection = connection
        connection.prepare("CREATE TABLE t (id TEXT PRIMARY KEY, v INTEGER)").step()
        val insert = connection.prepare("INSERT INTO t (id, v) VALUES (?, ?)")
        (1..5).forEach { i ->
            insert.bindText(1, "row-$i")
            insert.bindLong(2, i.toLong())
            insert.step()
            insert.reset()
        }
        return connection
    }

    @Test
    fun `metadata survives reset and close via snapshot instead of throwing`() {
        val stmt = openDb().prepare("SELECT id, v FROM t")
        assertEquals(2, stmt.getColumnCount())
        assertEquals("id", stmt.getColumnName(0))
        assertTrue(stmt.step())
        stmt.reset()
        // The observed live crash died HERE, on the second query's prepare-time metadata read:
        // xerial binds statement metadata to its dead result set, so post-close metadata reads
        // threw "inconsistent internal state". Metadata is a pure function of the SQL text, so
        // the statement serves its snapshot instead.
        assertEquals(2, stmt.getColumnCount())
        assertEquals("v", stmt.getColumnName(1))
        assertTrue(stmt.step())
        stmt.close()
        assertEquals(2, stmt.getColumnCount())
        assertEquals("id", stmt.getColumnName(0))
    }

    @Test
    fun `sequential prepare-metadata-bind-step-read-reset cycles hold their values`() {
        val stmt = openDb().prepare("SELECT id, v FROM t WHERE v >= ? ORDER BY v")
        repeat(50) { round ->
            assertEquals(2, stmt.getColumnCount())
            assertEquals("id", stmt.getColumnName(0))
            stmt.bindLong(1, 3)
            var count = 0
            while (stmt.step()) {
                count++
                assertTrue(stmt.getText(0).startsWith("row-"))
                assertTrue(stmt.getLong(1) >= 3L)
            }
            assertEquals(3, count, "round $round must see rows 3..5")
            stmt.reset()
        }
        stmt.close()
    }

    @Test
    fun `concurrent metadata reads on one shared statement never throw`() = runBlocking(Dispatchers.IO) {
        val stmt = openDb().prepare("SELECT id, v FROM t")
        val stepper = launch {
            while (stmt.step()) { /* hold a live result set while others read metadata */ }
        }
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val readers = (1..8).map {
            launch(Dispatchers.IO) {
                repeat(500) {
                    runCatching {
                        stmt.getColumnCount()
                        stmt.getColumnName(1)
                        stmt.getColumnType(0)
                    }.onFailure(errors::add)
                }
            }
        }
        (readers + stepper).joinAll()
        assertTrue(errors.isEmpty(), "metadata reads threw: ${errors.peek()}")
        stmt.close()
    }

    private companion object {
        const val KEY = "concurrency test key"
    }
}
