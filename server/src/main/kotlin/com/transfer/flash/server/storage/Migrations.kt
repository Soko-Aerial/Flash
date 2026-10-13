package com.transfer.flash.server.storage

import java.sql.Connection

/**
 * One versioned schema step of one component (SEC-18 / REL-persistence, ADR-111).
 *
 * [version] must be > 0 and strictly increasing inside a component. [apply] runs inside the transaction that also
 * records the version, so a failing step leaves neither a half-applied schema nor a bumped version. Steps SHOULD still
 * be written idempotently (`CREATE TABLE IF NOT EXISTS`, guarded `ALTER`) because an operator may restore a database
 * from a backup that already contains part of a step.
 */
class Migration(
    val version: Int,
    val description: String,
    val apply: (Connection) -> Unit,
)

/** A migration step failed; the hub must not start (fail closed), the cause carries the SQL error. */
class MigrationFailedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Component-scoped schema migrator over [ServerDatabase].
 *
 * Every component (`identity`, `directory`, `admin`, `audit`, `mailbox`, ...) registers its own ordered step list and
 * owns its own row of `schema_migrations(component, version)`. [migrate] applies, per component, every step whose
 * version is greater than the stored one, each in its own `BEGIN IMMEDIATE` transaction together with the version
 * bump. Failure aborts: the exception propagates and the caller (Main) must refuse to start.
 *
 * A component whose stored version is HIGHER than the newest registered step was written by a newer hub build:
 * [migrate] refuses (downgrade protection) instead of running old code against a newer schema.
 */
class Migrator(private val db: ServerDatabase) {

    private val components = LinkedHashMap<String, List<Migration>>()

    @Synchronized
    fun register(component: String, steps: List<Migration>) {
        require(component.isNotBlank()) { "component must not be blank" }
        require(component !in components) { "component '$component' registered twice" }
        var previous = 0
        for (step in steps) {
            require(step.version > previous) {
                "component '$component': versions must be strictly increasing and > 0 (got ${step.version} after $previous)"
            }
            previous = step.version
        }
        components[component] = steps.toList()
    }

    /** The stored version of [component], or 0 when it never migrated. Creates the bookkeeping table if needed. */
    fun currentVersion(component: String): Int = db.withConnection { conn ->
        ensureTable(conn)
        readVersion(conn, component)
    }

    /** Applies every pending step of every registered component. Idempotent: a second call does nothing. */
    @Synchronized
    fun migrate() {
        db.withConnection { conn ->
            ensureTable(conn)
            for ((component, steps) in components) {
                val stored = readVersion(conn, component)
                val newest = steps.lastOrNull()?.version ?: 0
                if (stored > newest) {
                    throw MigrationFailedException(
                        "component '$component' is at schema version $stored but this build only knows $newest; refusing to start (downgrade)",
                    )
                }
                for (step in steps) {
                    if (step.version <= stored) continue
                    applyStep(conn, component, step)
                }
            }
        }
    }

    private fun applyStep(conn: Connection, component: String, step: Migration) {
        val wasAutoCommit = conn.autoCommit
        conn.autoCommit = true
        var inTransaction = false
        try {
            conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            inTransaction = true
            // Re-read under the write lock: another process (or a racing start) may have applied it meanwhile.
            if (readVersion(conn, component) >= step.version) {
                conn.createStatement().use { it.execute("COMMIT") }
                inTransaction = false
                return
            }
            step.apply(conn)
            conn.prepareStatement(
                "INSERT INTO schema_migrations(component, version) VALUES(?, ?) " +
                    "ON CONFLICT(component) DO UPDATE SET version = excluded.version",
            ).use {
                it.setString(1, component)
                it.setInt(2, step.version)
                it.executeUpdate()
            }
            conn.createStatement().use { it.execute("COMMIT") }
            inTransaction = false
        } catch (t: Throwable) {
            if (inTransaction) {
                try {
                    conn.createStatement().use { it.execute("ROLLBACK") }
                } catch (_: Throwable) {
                    // The original failure is the one that matters.
                }
            }
            throw MigrationFailedException(
                "migration $component#${step.version} (${step.description}) failed: ${t.message}",
                t,
            )
        } finally {
            conn.autoCommit = wasAutoCommit
        }
    }

    private fun ensureTable(conn: Connection) {
        conn.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS schema_migrations(component TEXT PRIMARY KEY, version INTEGER NOT NULL)")
        }
    }

    private fun readVersion(conn: Connection, component: String): Int =
        conn.prepareStatement("SELECT version FROM schema_migrations WHERE component = ?").use { stmt ->
            stmt.setString(1, component)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
}
