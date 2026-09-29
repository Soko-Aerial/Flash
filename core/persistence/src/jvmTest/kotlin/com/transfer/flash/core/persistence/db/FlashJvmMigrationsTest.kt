package com.transfer.flash.core.persistence.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.room.testing.MigrationTestHelper
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * Upgrades REAL encrypted files created at an old schema version, on the desktop JVM.
 *
 * Until this suite existed the JVM opener registered no migrations, and neither platform had a test
 * that ran one: Android's `FlashMigrations` had only ever been proven by upgrading installs on
 * devices. Room's [MigrationTestHelper] builds a database from the exported `schemas/<n>.json`, so
 * the starting point is exactly what an old release wrote, and [MigrationTestHelper.runMigrationsAndValidate]
 * has Room compare the upgraded database with the target schema (tables, columns, indices).
 *
 * The seam test at the bottom goes through [openEncryptedFlashDatabase], the call `:desktop` makes.
 * The direct tests would keep passing if someone removed the `addMigrations` line from the opener;
 * that one would not.
 */
class FlashJvmMigrationsTest {

    private val workDir: File = Files.createTempDirectory("flash-jvm-migration").toFile()
    private val dbFile = File(workDir, "flash.db")

    @get:Rule
    val helper = MigrationTestHelper(
        schemaDirectoryPath = SCHEMA_DIR,
        databasePath = dbFile.toPath(),
        driver = JdbcCipherSQLiteDriver(KEY),
        databaseClass = FlashDatabase::class,
        databaseFactory = FlashDatabaseConstructor::initialize,
    )

    @AfterTest
    fun tearDown() {
        runCatching { workDir.deleteRecursively() }
    }

    @Test
    fun `the exported schemas the tests depend on are present`() {
        // A missing schema would make the helper fail with a message about JSON, not about this.
        listOf(1, 3, 4, FlashDatabase.DATABASE_VERSION).forEach { version ->
            assertTrue(
                Files.exists(SCHEMA_DIR.resolve("com.transfer.flash.core.persistence.db.FlashDatabase/$version.json")),
                "schemas/…/$version.json is missing; the tests run from the module directory",
            )
        }
    }

    @Test
    fun `every JVM migration is registered and the chain reaches the current version`() {
        val migrations = FlashJvmMigrations.ALL.toList()
        assertEquals(1, migrations.first().startVersion)
        migrations.zipWithNext { a, b -> assertEquals(a.endVersion, b.startVersion, "gap after ${a.endVersion}") }
        assertEquals(FlashDatabase.DATABASE_VERSION, migrations.last().endVersion)
    }

    @Test
    fun `a v1 database upgrades to the current schema and Room validates it`() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).close()
    }

    @Test
    fun `a v3 database upgrades to the current schema and Room validates it`() {
        helper.createDatabase(3).close()
        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).close()
    }

    @Test
    fun `a v4 database gains the remembered_endpoints table and Room validates it`() {
        // The DR1 step (ADR-047): the only change between v4 and v5.
        helper.createDatabase(4).close()
        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).use { upgraded ->
            upgraded.execSQL(
                "INSERT INTO remembered_endpoints (deviceId, host, port, lastConnectedAt) " +
                    "VALUES ('peer', '192.168.1.5', 45822, 1)",
            )
            upgraded.prepare("SELECT port, firstFailureAt FROM remembered_endpoints").use { row ->
                assertTrue(row.step())
                assertEquals(45822L, row.getLong(0))
                assertTrue(row.isNull(1), "firstFailureAt must be nullable and default to NULL")
            }
        }
    }

    @Test
    fun `rows written at v1 survive the whole chain with the new columns defaulted`() {
        helper.createDatabase(1).use { v1 -> insertMessage(v1, version = 1) }

        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).use { upgraded ->
            upgraded.prepare(
                "SELECT text, attachmentSize, attachmentName, replyToId FROM messages WHERE localId = 'm1'",
            ).use { row ->
                assertTrue(row.step(), "the v1 message row was lost by the migration")
                assertEquals("hello from v1", row.getText(0))
                assertEquals(0L, row.getLong(1), "attachmentSize must default to 0 (NOT NULL column)")
                assertTrue(row.isNull(2), "attachmentName must default to NULL")
                assertTrue(row.isNull(3), "replyToId must default to NULL")
            }
        }
    }

    @Test
    fun `the public desktop opener upgrades an older file instead of failing`() = runBlocking {
        helper.createDatabase(3).use { v3 -> insertMessage(v3, version = 3) }

        // Exactly what DesktopEngine calls. Without registered migrations Room throws
        // "A migration from 3 to 4 was required but not found" on the first query.
        val db = openEncryptedFlashDatabase(dbFile, KEY)
        try {
            assertEquals(
                "hello from v1",
                db.messageDao().observeConversation("c1").first().single().text,
            )
            // The v4 tables exist and are usable: this is what group chat needs.
            assertTrue(db.groupMemberDao().activeMembers("g1").isEmpty())
            // And the v5 table (DR1) is reachable through the DAO Room generated for it.
            assertTrue(db.rememberedEndpointDao().all().isEmpty())
        } finally {
            db.close()
        }
    }

    /**
     * A conversation with one message, valid for the schema at [version]. `attachmentSize` exists
     * from v2 and is NOT NULL without a default in Room's own DDL, so a v2+ row has to supply it.
     */
    private fun insertMessage(connection: SQLiteConnection, version: Int) {
        connection.execSQL(
            "INSERT INTO conversations (id, title, isGroup, pinned, muted, archived, sortOrder) " +
                "VALUES ('c1', 'Chat', 0, 0, 0, 0, 1)",
        )
        val attachmentColumn = if (version >= 2) ", attachmentSize" else ""
        val attachmentValue = if (version >= 2) ", 0" else ""
        connection.execSQL(
            "INSERT INTO messages (localId, conversationId, senderId, text, sentAt, status$attachmentColumn) " +
                "VALUES ('m1', 'c1', 'peer', 'hello from v1', 1, 'SENT'$attachmentValue)",
        )
    }

    private companion object {
        const val KEY = "correct horse battery staple"

        // Gradle runs tests with the module directory as the working directory.
        val SCHEMA_DIR: Path = Path.of("schemas")
    }
}
