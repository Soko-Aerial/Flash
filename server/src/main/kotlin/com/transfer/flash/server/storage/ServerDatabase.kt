package com.transfer.flash.server.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.transfer.flash.server.auth.SecureFiles
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Record representing an enrolled device in the enterprise hub directory.
 */
data class DeviceRegistryRecord(
    val deviceId: String,
    val friendlyName: String,
    val role: String,
    val department: String,
    val certSerial: Long,
    val enrolledAt: Long,
    val lastSeenAt: Long,
)

/**
 * Record representing an audit log entry with SHA-256 hash chaining.
 */
data class AuditLogRecord(
    val id: Long = 0,
    val timestamp: Long,
    val actor: String,
    val action: String,
    val target: String,
    val details: String,
    val prevHash: String,
    val entryHash: String,
)

/** Result of [ServerDatabase.probe] (`/health/detail`). */
data class DatabaseProbe(
    val ok: Boolean,
    val latencyMs: Long,
    /** Pages in the WAL file right after a passive checkpoint (null when the journal is not WAL or the probe failed). */
    val walPages: Int?,
    /** Pages of the WAL already copied into the database file. */
    val walCheckpointedPages: Int?,
    val error: String? = null,
)

/**
 * SQLite database initialization and connection pool management for the standalone server module.
 *
 * Every pooled connection is opened with the same pragmas, passed to the driver as connection properties (REL-15:
 * `connectionInitSql` runs a single statement on some drivers, so a two-statement init string was only partly applied;
 * the properties path is applied by the driver on every `connect`, and `ServerDatabaseTest` reads each pragma back
 * from several simultaneously open connections to prove it): `busy_timeout=5000`, `synchronous=NORMAL`,
 * `foreign_keys=ON`, and, for a file database, `journal_mode=WAL`.
 *
 * The file is created with `auto_vacuum=INCREMENTAL` so [incrementalVacuum] can give back the pages of deleted
 * mailbox rows (REL-11); an older file with `auto_vacuum=NONE` is converted once, at open, with a `VACUUM` when it is
 * small enough to do so quickly.
 *
 * Schema: this class creates only the shared `device_registry` and `audit_log` tables (owned, and to be migrated, by
 * the identity/audit components). The mailbox schema belongs to the `mailbox` migration component
 * ([com.transfer.flash.server.mailbox.MailboxSchema]).
 *
 * Blocking JDBC must not run on a Ktor event-loop thread: use [io] / [ioTransaction] from coroutines (REL-11).
 */
class ServerDatabase(
    val jdbcUrl: String = DEFAULT_JDBC_URL,
    maxPoolSize: Int = 10,
) : AutoCloseable {

    companion object {
        const val DEFAULT_JDBC_URL = "jdbc:sqlite:mailbox.db"
        const val IN_MEMORY_JDBC_URL = "jdbc:sqlite::memory:"

        /** An old file is converted to incremental auto-vacuum with a VACUUM only when it is smaller than this. */
        const val VACUUM_CONVERSION_MAX_BYTES: Long = 256L * 1024 * 1024

        private val logger = LoggerFactory.getLogger(ServerDatabase::class.java)

        /**
         * Resolves SQLite JDBC driver class name. Prefers Willena's encrypted fork driver
         * `io.github.willena.sqlite.JdbcDriver`, falling back to standard `org.sqlite.JDBC`.
         */
        fun resolveDriverClass(): String {
            return try {
                Class.forName("io.github.willena.sqlite.JdbcDriver")
                "io.github.willena.sqlite.JdbcDriver"
            } catch (_: ClassNotFoundException) {
                "org.sqlite.JDBC"
            }
        }

        /**
         * Factory for an in-memory SQLite database instance suitable for testing.
         * Uses a named shared-cache in-memory URI so multiple pool connections share the same memory instance.
         */
        fun inMemory(poolSize: Int = 1): ServerDatabase {
            val url = "jdbc:sqlite:file:memdb_${System.nanoTime()}?mode=memory&cache=shared"
            return ServerDatabase(jdbcUrl = url, maxPoolSize = poolSize)
        }

        private fun isMemoryUrl(url: String): Boolean = url.contains(":memory:") || url.contains("mode=memory")

        private fun filePathOf(url: String): File? {
            if (isMemoryUrl(url)) return null
            val raw = url.removePrefix("jdbc:sqlite:").substringBefore('?')
            return File(raw.removePrefix("file:"))
        }
    }

    val dataSource: HikariDataSource

    private val isMemory: Boolean = isMemoryUrl(jdbcUrl)
    private val closed = AtomicBoolean(false)

    init {
        val config = HikariConfig().apply {
            this.jdbcUrl = this@ServerDatabase.jdbcUrl
            driverClassName = resolveDriverClass()
            poolName = "FlashServerHikariPool"

            val isPlainMemory = this@ServerDatabase.jdbcUrl == IN_MEMORY_JDBC_URL
            if (isPlainMemory) {
                // Standalone un-named in-memory SQLite instances are connection-scoped.
                maximumPoolSize = 1
                minimumIdle = 1
            } else {
                maximumPoolSize = maxPoolSize
                minimumIdle = minOf(2, maxPoolSize)
            }

            connectionTestQuery = "SELECT 1"
            isAutoCommit = true

            // Per-connection pragmas, applied by the driver on every connect (see the class comment).
            val props = Properties()
            props["busy_timeout"] = "5000"
            props["synchronous"] = "NORMAL"
            props["foreign_keys"] = "true"
            if (!isMemory) props["journal_mode"] = "WAL"
            dataSourceProperties = props
        }

        // REV-03: the directory and the file are private to the hub's account BEFORE SQLite creates anything in them.
        if (!isMemory) hardenFilesBeforeOpen()

        // The auto_vacuum mode of a NEW file is fixed by its first table; set it before anything is created.
        if (!isMemory) prepareFileForIncrementalVacuum()

        dataSource = HikariDataSource(config)
        initializeSchema()
        if (!isMemory) verifyFilesPrivate()
    }

    /** The database, its `-wal` and its `-shm` (SQLite gives the side files the permissions of the main file). */
    private fun databaseFiles(): List<java.nio.file.Path> {
        val main = filePathOf(jdbcUrl)?.toPath()?.toAbsolutePath() ?: return emptyList()
        return listOf(main, main.resolveSibling(main.fileName.toString() + "-wal"), main.resolveSibling(main.fileName.toString() + "-shm"))
    }

    private fun hardenFilesBeforeOpen() {
        val files = databaseFiles()
        val main = files.firstOrNull() ?: return
        main.parent?.let { if (!SecureFiles.restrictDirectory(it)) logger.warn("Could not restrict the data directory {} to the hub's account; check its permissions by hand", it) }
        try {
            if (!java.nio.file.Files.exists(main)) java.nio.file.Files.createFile(main)
        } catch (e: java.io.IOException) {
            logger.warn("Could not pre-create {} owner-only: {}", main.fileName, e.message)
        }
        files.filter { java.nio.file.Files.exists(it) }.forEach { SecureFiles.restrictDataFile(it) }
    }

    private fun verifyFilesPrivate() {
        for (f in databaseFiles().filter { java.nio.file.Files.exists(it) }) {
            if (!SecureFiles.isPrivateToHubAccount(f) && !SecureFiles.restrictDataFile(f) || !SecureFiles.isPrivateToHubAccount(f)) {
                logger.warn(
                    "SECURITY: {} is readable by accounts other than the hub's. It holds password hashes, invite hashes and queued messages: " +
                        "restrict it by hand (chmod 600 / an owner-only ACL).",
                    f.fileName,
                )
            }
        }
    }

    private fun prepareFileForIncrementalVacuum() {
        val file = filePathOf(jdbcUrl) ?: return
        val existed = file.exists() && file.length() > 0
        try {
            java.sql.DriverManager.getConnection(jdbcUrl).use { conn ->
                val mode = conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA auto_vacuum").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
                if (mode == 2) return
                conn.createStatement().use { it.execute("PRAGMA auto_vacuum=INCREMENTAL") }
                if (existed) {
                    if (file.length() <= VACUUM_CONVERSION_MAX_BYTES) {
                        logger.info("Converting {} to auto_vacuum=INCREMENTAL (VACUUM, {} bytes)", file.name, file.length())
                        conn.createStatement().use { it.execute("VACUUM") }
                    } else {
                        logger.warn("{} is large ({} bytes); not converting to auto_vacuum=INCREMENTAL at startup", file.name, file.length())
                    }
                }
            }
        } catch (t: Throwable) {
            // Never block startup over a space optimisation.
            logger.warn("Could not set auto_vacuum=INCREMENTAL on {}: {}", file.name, t.message)
        }
    }

    private fun initializeSchema() {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                // device_registry
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS device_registry (
                        device_id TEXT PRIMARY KEY,
                        friendly_name TEXT NOT NULL,
                        role TEXT NOT NULL,
                        department TEXT NOT NULL,
                        cert_serial INTEGER NOT NULL,
                        enrolled_at INTEGER NOT NULL,
                        last_seen_at INTEGER NOT NULL
                    );
                """.trimIndent())

                // audit_log
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        actor TEXT NOT NULL,
                        action TEXT NOT NULL,
                        target TEXT NOT NULL,
                        details TEXT NOT NULL,
                        prev_hash TEXT NOT NULL,
                        entry_hash TEXT NOT NULL
                    );
                """.trimIndent())
            }
        }
    }

    /**
     * Executes a block within a pooled JDBC Connection, safely returning it to HikariCP upon completion.
     * BLOCKING: from a coroutine use [io].
     */
    fun <T> withConnection(block: (Connection) -> T): T {
        return dataSource.connection.use(block)
    }

    /**
     * Executes a block in a write transaction that takes the write lock up front (`BEGIN IMMEDIATE`), so a
     * check-then-insert can not interleave with another writer and a writer never fails late with `SQLITE_BUSY` on
     * lock upgrade (REL-15, REL-03). Commits on success, rolls back on any throwable. BLOCKING: use [ioTransaction].
     */
    fun <T> withTransaction(block: (Connection) -> T): T {
        return dataSource.connection.use { conn ->
            val previous = conn.autoCommit
            conn.autoCommit = true
            conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val result = block(conn)
                conn.createStatement().use { it.execute("COMMIT") }
                result
            } catch (t: Throwable) {
                try {
                    conn.createStatement().use { it.execute("ROLLBACK") }
                } catch (_: Throwable) {
                    // The original failure is the one that matters.
                }
                throw t
            } finally {
                conn.autoCommit = previous
            }
        }
    }

    /** [withConnection] on the IO dispatcher. */
    suspend fun <T> io(block: (Connection) -> T): T = withContext(Dispatchers.IO) { withConnection(block) }

    /** [withTransaction] on the IO dispatcher. */
    suspend fun <T> ioTransaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) { withTransaction(block) }

    /** The current value of a pragma on a pooled connection (diagnostics and tests). */
    fun pragma(name: String): String? = withConnection { conn ->
        conn.createStatement().use { st -> st.executeQuery("PRAGMA $name").use { rs -> if (rs.next()) rs.getString(1) else null } }
    }

    /**
     * Cheap health probe for `/health/detail`: `SELECT 1` plus a passive WAL checkpoint (which reports the WAL size and
     * how much of it has reached the main file). Never throws.
     */
    fun probe(): DatabaseProbe {
        val started = System.nanoTime()
        return try {
            withConnection { conn ->
                conn.createStatement().use { st -> st.executeQuery("SELECT 1").use { it.next() } }
                var pages: Int? = null
                var done: Int? = null
                if (!isMemory) {
                    conn.createStatement().use { st ->
                        st.executeQuery("PRAGMA wal_checkpoint(PASSIVE)").use { rs ->
                            if (rs.next()) {
                                pages = rs.getInt(2).takeIf { it >= 0 }
                                done = rs.getInt(3).takeIf { it >= 0 }
                            }
                        }
                    }
                }
                DatabaseProbe(true, (System.nanoTime() - started) / 1_000_000, pages, done)
            }
        } catch (t: Throwable) {
            DatabaseProbe(false, (System.nanoTime() - started) / 1_000_000, null, null, t.javaClass.simpleName)
        }
    }

    /** Returns up to [pages] free pages to the filesystem (needs `auto_vacuum=INCREMENTAL`; a no-op otherwise). */
    fun incrementalVacuum(pages: Int = 1000): Boolean = try {
        // sqlite-jdbc: execute() steps the pragma ONCE (one page freed, measured 2450 -> 2449); executeUpdate() steps to the end.
        withConnection { conn -> conn.createStatement().use { it.executeUpdate("PRAGMA incremental_vacuum($pages)") } }
        true
    } catch (t: Throwable) {
        logger.warn("incremental_vacuum failed: {}", t.message)
        false
    }

    /** Folds the whole WAL into the database file and truncates it. Best effort; used before close. */
    fun checkpointTruncate(): Boolean {
        if (isMemory || dataSource.isClosed) return false
        return try {
            withConnection { conn -> conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
            true
        } catch (t: Throwable) {
            logger.warn("wal_checkpoint(TRUNCATE) failed: {}", t.message)
            false
        }
    }

    // --- Device Registry Helper Operations ---

    fun registerDevice(device: DeviceRegistryRecord) {
        withConnection { conn ->
            val sql = """
                INSERT OR REPLACE INTO device_registry (
                    device_id, friendly_name, role, department, cert_serial, enrolled_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, device.deviceId)
                stmt.setString(2, device.friendlyName)
                stmt.setString(3, device.role)
                stmt.setString(4, device.department)
                stmt.setLong(5, device.certSerial)
                stmt.setLong(6, device.enrolledAt)
                stmt.setLong(7, device.lastSeenAt)
                stmt.executeUpdate()
            }
        }
    }

    fun getDevice(deviceId: String): DeviceRegistryRecord? {
        return withConnection { conn ->
            val sql = """
                SELECT device_id, friendly_name, role, department, cert_serial, enrolled_at, last_seen_at
                FROM device_registry WHERE device_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        DeviceRegistryRecord(
                            deviceId = rs.getString("device_id"),
                            friendlyName = rs.getString("friendly_name"),
                            role = rs.getString("role"),
                            department = rs.getString("department"),
                            certSerial = rs.getLong("cert_serial"),
                            enrolledAt = rs.getLong("enrolled_at"),
                            lastSeenAt = rs.getLong("last_seen_at"),
                        )
                    } else null
                }
            }
        }
    }

    fun updateDeviceLastSeen(deviceId: String, lastSeenAt: Long = System.currentTimeMillis()): Boolean {
        return withConnection { conn ->
            val sql = "UPDATE device_registry SET last_seen_at = ? WHERE device_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, lastSeenAt)
                stmt.setString(2, deviceId)
                stmt.executeUpdate() > 0
            }
        }
    }

    // --- Audit Log Helper Operations ---

    fun insertAuditLog(entry: AuditLogRecord): Long {
        return withConnection { conn ->
            val sql = """
                INSERT INTO audit_log (timestamp, actor, action, target, details, prev_hash, entry_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
                stmt.setLong(1, entry.timestamp)
                stmt.setString(2, entry.actor)
                stmt.setString(3, entry.action)
                stmt.setString(4, entry.target)
                stmt.setString(5, entry.details)
                stmt.setString(6, entry.prevHash)
                stmt.setString(7, entry.entryHash)
                stmt.executeUpdate()
                stmt.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    fun getLatestAuditLog(): AuditLogRecord? {
        return withConnection { conn ->
            val sql = """
                SELECT id, timestamp, actor, action, target, details, prev_hash, entry_hash
                FROM audit_log ORDER BY id DESC LIMIT 1
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        AuditLogRecord(
                            id = rs.getLong("id"),
                            timestamp = rs.getLong("timestamp"),
                            actor = rs.getString("actor"),
                            action = rs.getString("action"),
                            target = rs.getString("target"),
                            details = rs.getString("details"),
                            prevHash = rs.getString("prev_hash"),
                            entryHash = rs.getString("entry_hash"),
                        )
                    } else null
                }
            }
        }
    }

    /** Idempotent. Checkpoints the WAL (file databases) before the pool is closed so no `-wal` file is left behind. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        checkpointTruncate()
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }
}
