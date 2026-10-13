package com.transfer.flash.server.storage

import com.transfer.flash.server.mailbox.EnqueueResult
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxMessage
import com.transfer.flash.server.mailbox.MailboxQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ServerDatabaseTest {

    private lateinit var database: ServerDatabase
    private val extra = mutableListOf<ServerDatabase>()
    private val dirs = mutableListOf<File>()

    @Before
    fun setUp() {
        database = ServerDatabase.inMemory(poolSize = 1)
    }

    @After
    fun tearDown() {
        database.close()
        extra.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun tempDir(): File = Files.createTempDirectory("flash-db-test").toFile().also { dirs += it }

    private fun url(dir: File) = "jdbc:sqlite:${File(dir, "hub.db").path.replace('\\', '/')}"

    private fun fileDb(dir: File = tempDir(), pool: Int = 4): ServerDatabase = ServerDatabase(url(dir), pool).also { extra += it }

    private fun names(conn: Connection, type: String): Set<String> {
        val out = mutableSetOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='$type'").use { while (it.next()) out += it.getString(1) }
        }
        return out
    }

    private fun pragma(conn: Connection, name: String): String =
        conn.createStatement().use { st -> st.executeQuery("PRAGMA $name").use { it.next(); it.getString(1) } }

    @Test
    fun theDatabaseItselfOwnsOnlyTheSharedTables() {
        database.withConnection { conn ->
            val tables = names(conn, "table")
            assertTrue("device_registry table should exist", tables.contains("device_registry"))
            assertTrue("audit_log table should exist", tables.contains("audit_log"))
            assertFalse("the mailbox schema belongs to the mailbox migrations, not ServerDatabase", tables.contains("mailbox_envelopes"))
        }
    }

    @Test
    fun theMailboxMigrationsCreateTheCurrentSchemaWithItsIndexesAndConstraint() {
        MailboxQueue(database, MailboxLimits())
        database.withConnection { conn ->
            val columns = mutableMapOf<String, String>()
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(mailbox_envelopes)").use { while (it.next()) columns[it.getString("name")] = it.getString("type").uppercase() }
            }
            assertEquals("INTEGER", columns["seq"])
            assertEquals("BLOB", columns["payload"])
            assertEquals("INTEGER", columns["payload_bytes"])
            assertEquals("INTEGER", columns["expires_at"])
            assertEquals("TEXT", columns["group_id"])
            assertFalse("the delivered flag is gone: a delivered row is deleted", columns.containsKey("delivered"))
            assertFalse(columns.containsKey("auth_tag"))

            val indexes = names(conn, "index")
            assertTrue(indexes.contains("idx_mailbox_target_seq"))
            assertTrue("deleteExpired needs an index on expires_at (REL-11)", indexes.contains("idx_mailbox_expires"))
            assertTrue(indexes.contains("idx_mailbox_origin"))
            assertTrue(
                "the dedup key must be a UNIQUE index",
                indexes.any { it.startsWith("sqlite_autoindex_mailbox_envelopes") },
            )
        }
    }

    /** REL-15: every pooled connection gets the same pragmas, not just the first one. */
    @Test
    fun everyPooledConnectionCarriesThePragmas() {
        val db = fileDb(pool = 4)
        val open = mutableListOf<Connection>()
        try {
            repeat(4) { open += db.dataSource.connection } // four AT ONCE, so four distinct connections exist
            assertEquals(4, open.map { System.identityHashCode(it) }.toSet().size)
            for (conn in open) {
                assertEquals("busy_timeout", "5000", pragma(conn, "busy_timeout"))
                assertEquals("synchronous NORMAL = 1", "1", pragma(conn, "synchronous"))
                assertEquals("foreign_keys", "1", pragma(conn, "foreign_keys"))
                assertEquals("journal_mode", "wal", pragma(conn, "journal_mode").lowercase())
            }
        } finally {
            open.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun aNewFileIsCreatedWithIncrementalAutoVacuum() {
        val db = fileDb()
        assertEquals("2", db.pragma("auto_vacuum"))
    }

    @Test
    fun anOldFileWithoutAutoVacuumIsConvertedOnOpen() {
        val dir = tempDir()
        DriverManager.getConnection(url(dir)).use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE legacy(x INTEGER)")
                st.execute("INSERT INTO legacy VALUES (42)")
            }
            assertEquals("0", pragma(conn, "auto_vacuum"))
        }
        val db = fileDb(dir)
        assertEquals("2", db.pragma("auto_vacuum"))
        val kept = db.withConnection { c -> c.createStatement().use { it.executeQuery("SELECT x FROM legacy").use { rs -> rs.next(); rs.getInt(1) } } }
        assertEquals("the VACUUM must keep the data", 42, kept)
    }

    /** REL-15 / REL-03: the write lock is taken at BEGIN, so a second writer waits instead of failing mid-transaction. */
    @Test
    fun withTransactionTakesTheWriteLockUpFront() {
        val db = fileDb()
        db.withConnection { it.createStatement().use { st -> st.execute("CREATE TABLE t(x INTEGER)") } }
        db.withTransaction { _ ->
            // Another writer, with a short busy timeout, must be refused right now: with a deferred BEGIN it would not be.
            DriverManager.getConnection(url(dirOf(db))).use { other ->
                other.createStatement().use { it.execute("PRAGMA busy_timeout=50") }
                assertFailsWith<SQLException> { other.createStatement().use { it.execute("BEGIN IMMEDIATE") } }
            }
        }
    }

    private fun dirOf(db: ServerDatabase): File = File(db.jdbcUrl.removePrefix("jdbc:sqlite:")).parentFile

    @Test
    fun withTransactionRollsBackOnAnyThrowable() {
        val db = fileDb()
        db.withConnection { it.createStatement().use { st -> st.execute("CREATE TABLE t(x INTEGER)") } }
        assertFailsWith<IllegalStateException> {
            db.withTransaction { conn ->
                conn.createStatement().use { it.execute("INSERT INTO t VALUES (1)") }
                error("boom")
            }
        }
        val rows = db.withConnection { c -> c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM t").use { rs -> rs.next(); rs.getInt(1) } } }
        assertEquals(0, rows)
        // The connection went back to the pool in autocommit mode: the next write works.
        db.withTransaction { conn -> conn.createStatement().use { it.execute("INSERT INTO t VALUES (2)") } }
    }

    @Test
    fun probeReportsHealthAndThenFailureAfterClose() {
        val db = fileDb()
        val ok = db.probe()
        assertTrue(ok.ok)
        assertNull(ok.error)
        assertNotNull(ok.walPages)
        db.close()
        val down = db.probe()
        assertFalse("a closed database must be reported down", down.ok)
        assertNotNull(down.error)
    }

    @Test
    fun incrementalVacuumGivesBackThePagesOfDeletedRows() = runBlocking<Unit> {
        val db = fileDb()
        val queue = MailboxQueue(db, MailboxLimits(maxPayloadBytes = 200_000, perRecipientRows = 10_000, perRecipientBytes = 1L shl 30))
        val big = "x".repeat(100_000)
        repeat(60) { assertIs<EnqueueResult.Queued>(queue.enqueue(MailboxMessage("alice", "bob", "m$it", "MSG_TEXT", big, System.currentTimeMillis()))) }
        repeat(60) { queue.acknowledge("bob", "m$it") }
        val freeBefore = db.pragma("freelist_count")!!.toInt()
        assertTrue("deleted rows leave free pages ($freeBefore)", freeBefore > 100)

        db.checkpointTruncate()
        assertTrue(db.incrementalVacuum(1_000_000))
        val freeAfter = db.pragma("freelist_count")!!.toInt()
        assertEquals("every free page was returned to the filesystem", 0, freeAfter)
    }

    @Test
    fun closeIsIdempotentAndFoldsTheWalIntoTheMainFile() {
        val dir = tempDir()
        val db = fileDb(dir)
        db.registerDevice(DeviceRegistryRecord("d1", "n", "MEMBER", "eng", 1L, 1L, 1L))
        db.close()
        db.close() // second close must not throw
        val wal = File(dir, "hub.db-wal")
        assertTrue("no pending WAL after a clean close", !wal.exists() || wal.length() == 0L)
        // And the data is in the main file.
        DriverManager.getConnection(url(dir)).use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM device_registry").use { it.next(); assertEquals(1, it.getInt(1)) } }
        }
    }

    @Test
    fun testDeviceRegistryCrudOperations() {
        val now = System.currentTimeMillis()
        val device = DeviceRegistryRecord(
            deviceId = "dev-alpha-001",
            friendlyName = "Alpha Pixel 9",
            role = "FIELD_OPERATOR",
            department = "LOGISTICS",
            certSerial = 100245L,
            enrolledAt = now,
            lastSeenAt = now,
        )

        database.registerDevice(device)

        val retrieved = database.getDevice("dev-alpha-001")
        assertNotNull(retrieved)
        assertEquals("Alpha Pixel 9", retrieved?.friendlyName)
        assertEquals("FIELD_OPERATOR", retrieved?.role)
        assertEquals("LOGISTICS", retrieved?.department)
        assertEquals(100245L, retrieved?.certSerial)

        val updatedTime = now + 5000L
        val updated = database.updateDeviceLastSeen("dev-alpha-001", updatedTime)
        assertTrue(updated)

        val updatedRecord = database.getDevice("dev-alpha-001")
        assertEquals(updatedTime, updatedRecord?.lastSeenAt)

        assertNull(database.getDevice("non-existent-device"))
    }

    @Test
    fun testAuditLogChaining() {
        val entry1 = AuditLogRecord(
            timestamp = 1000L,
            actor = "admin-root",
            action = "DEVICE_ENROLLED",
            target = "dev-alpha-001",
            details = "Enrolled via offline token",
            prevHash = "GENESIS_HASH",
            entryHash = "HASH_ENTRY_1",
        )
        val id1 = database.insertAuditLog(entry1)
        assertTrue(id1 > 0)

        val entry2 = AuditLogRecord(
            timestamp = 2000L,
            actor = "admin-root",
            action = "DEVICE_REVOKED",
            target = "dev-alpha-001",
            details = "Compromised credential reported",
            prevHash = "HASH_ENTRY_1",
            entryHash = "HASH_ENTRY_2",
        )
        val id2 = database.insertAuditLog(entry2)
        assertTrue(id2 > id1)

        val latest = database.getLatestAuditLog()
        assertNotNull(latest)
        assertEquals(id2, latest?.id)
        assertEquals("DEVICE_REVOKED", latest?.action)
        assertEquals("HASH_ENTRY_1", latest?.prevHash)
        assertEquals("HASH_ENTRY_2", latest?.entryHash)
    }
}
