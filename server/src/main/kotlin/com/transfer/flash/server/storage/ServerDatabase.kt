package com.transfer.flash.server.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

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

/**
 * SQLite database initialization and connection pool management for the standalone server module.
 *
 * Configures HikariCP, WAL mode (`PRAGMA journal_mode=WAL`), `PRAGMA synchronous=NORMAL`,
 * and `PRAGMA busy_timeout=5000`. Initializes the tables:
 * - `mailbox_envelopes`
 * - `device_registry`
 * - `audit_log`
 * and corresponding indexes.
 */
class ServerDatabase(
    val jdbcUrl: String = DEFAULT_JDBC_URL,
    maxPoolSize: Int = 10,
) : AutoCloseable {

    companion object {
        const val DEFAULT_JDBC_URL = "jdbc:sqlite:mailbox.db"
        const val IN_MEMORY_JDBC_URL = "jdbc:sqlite::memory:"

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
    }

    val dataSource: HikariDataSource

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
            connectionInitSql = "PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL;"
            isAutoCommit = true
        }

        dataSource = HikariDataSource(config)
        initializeSchema()
    }

    private fun initializeSchema() {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                if (!jdbcUrl.contains(":memory:")) {
                    try {
                        stmt.execute("PRAGMA journal_mode=WAL;")
                    } catch (_: Exception) {
                        // WAL may not be allowed on certain in-memory or read-only filesystems
                    }
                }
                stmt.execute("PRAGMA synchronous=NORMAL;")
                stmt.execute("PRAGMA busy_timeout=5000;")

                // 1. mailbox_envelopes
                stmt.execute("""
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
                    );
                """.trimIndent())

                // 2. device_registry
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

                // 3. audit_log
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

                // 4. Indexes
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_mailbox_target_delivered_expires 
                    ON mailbox_envelopes(target_device_id, delivered, expires_at);
                """.trimIndent())

                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_mailbox_origin_msg 
                    ON mailbox_envelopes(origin_device_id, msg_id);
                """.trimIndent())
            }
        }
    }

    /**
     * Executes a block within a pooled JDBC Connection, safely returning it to HikariCP upon completion.
     */
    fun <T> withConnection(block: (Connection) -> T): T {
        return dataSource.connection.use(block)
    }

    /**
     * Executes a block within a transactional JDBC Connection, committing on success or rolling back on exception.
     */
    fun <T> withTransaction(block: (Connection) -> T): T {
        return dataSource.connection.use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = prevAutoCommit
            }
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

    override fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }
}
