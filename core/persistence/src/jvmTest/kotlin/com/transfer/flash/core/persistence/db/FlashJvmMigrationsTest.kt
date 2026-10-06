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
        listOf(1, 3, 4, 5, 6, 7, 8, 9, FlashDatabase.DATABASE_VERSION).forEach { version ->
            assertTrue(
                Files.exists(SCHEMA_DIR.resolve("com.transfer.flash.core.persistence.db.FlashDatabase/$version.json")),
                "schemas/…/$version.json is missing; the tests run from the module directory",
            )
        }
    }

    @Test
    fun `a v6 database gains the swarm tables and Room validates it`() = runBlocking {
        helper.createDatabase(6).use { v6 ->
            v6.execSQL(
                "INSERT INTO conversations (id, title, isGroup, pinned, muted, archived, sortOrder, groupCreatedBy, groupCreatedAt, groupProto) " +
                    "VALUES ('g2-crew', 'Crew', 1, 0, 0, 0, 1, 'owner', 1, 2)",
            )
        }

        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).use { upgraded ->
            upgraded.execSQL(
                "INSERT INTO swarm_content (root, groupId, messageId, role, originId, originKey, fileName, mime, totalSize, pieceSize, bits, bytesDone, state, localTransferId, sourcePersistent, partialKey, identitySize, identityModifiedMs, deliveredTo, createdAtMs, lastProgressAtMs, expiresAtMs) " +
                    "VALUES ('root1', 'g2-crew', 'msg1', 'ORIGIN', 'orig', 'key', 'file.bin', 'application/octet-stream', 1000, 100, x'00', 0, 'INITIALIZING', 'tx1', 1, 'part1', 1000, 1, '[]', 1, 1, 1000)",
            )
            upgraded.prepare("SELECT root, state FROM swarm_content WHERE root = 'root1'").use { row ->
                assertTrue(row.step(), "the swarm_content row was not inserted")
                assertEquals("root1", row.getText(0))
                assertEquals("INITIALIZING", row.getText(1))
            }
        }
    }

    @Test
    fun `a v7 database gains the group secret tables and Room validates it`() = runBlocking {
        helper.createDatabase(7).use { v7 ->
            v7.execSQL(
                "INSERT INTO conversations (id, title, isGroup, pinned, muted, archived, sortOrder, groupCreatedBy, groupCreatedAt, groupProto) " +
                    "VALUES ('g2-crew', 'Crew', 1, 0, 0, 0, 1, 'owner', 1, 2)",
            )
        }

        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).use { upgraded ->
            upgraded.execSQL(
                "INSERT INTO group_secret (groupId, epoch, secretWrapped, `commit`, source, receivedAtMs) " +
                    "VALUES ('g2-crew', 1, x'000102', '316155ba2ffa797afffc435dbba71497d1146322565fdc77e22bf66d9a9f728e', 'CREATED', 1000)",
            )
            upgraded.prepare("SELECT groupId, epoch, `commit` FROM group_secret WHERE groupId = 'g2-crew' AND epoch = 1").use { row ->
                assertTrue(row.step(), "the group_secret row was not inserted")
                assertEquals("g2-crew", row.getText(0))
                assertEquals(1L, row.getLong(1))
                assertEquals("316155ba2ffa797afffc435dbba71497d1146322565fdc77e22bf66d9a9f728e", row.getText(2))
            }

            upgraded.execSQL(
                "INSERT INTO group_invite (groupId, inviterId, inviterFingerprint, acceptedAtMs, state) " +
                    "VALUES ('g2-crew', 'dev1', 'fp123', 1000, 'JOINED')",
            )
            upgraded.prepare("SELECT groupId, state FROM group_invite WHERE groupId = 'g2-crew'").use { row ->
                assertTrue(row.step(), "the group_invite row was not inserted")
                assertEquals("g2-crew", row.getText(0))
                assertEquals("JOINED", row.getText(1))
            }

            upgraded.execSQL(
                "INSERT INTO group_join_request (groupId, subjectId, subjectKey, label, requestSig, viaPeerId, requestedAtMs, state, decidedBy, decidedAtMs) " +
                    "VALUES ('g2-crew', 'subj1', 'key1', 'Label', 'sig1', 'peer1', 1000, 'PENDING', null, null)",
            )
            upgraded.prepare("SELECT groupId, state FROM group_join_request WHERE groupId = 'g2-crew'").use { row ->
                assertTrue(row.step(), "the group_join_request row was not inserted")
                assertEquals("g2-crew", row.getText(0))
                assertEquals("PENDING", row.getText(1))
            }
        }
    }

    @Test
    fun `a v8 database gains the group rotation table and Room validates it`() = runBlocking {
        helper.createDatabase(8).use { v8 ->
            v8.execSQL(
                "INSERT INTO conversations (id, title, isGroup, pinned, muted, archived, sortOrder, groupCreatedBy, groupCreatedAt, groupProto) " +
                    "VALUES ('g2-crew', 'Crew', 1, 0, 0, 0, 1, 'owner', 1, 2)",
            )
        }

        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).use { upgraded ->
            upgraded.execSQL(
                "INSERT INTO group_rotation (groupId, newEpoch, prevEpoch, `commit`, reason, adminId, rotationId, removedIds, sig, receivedAtMs) " +
                    "VALUES ('g2-crew', 2, 1, '316155ba2ffa797afffc435dbba71497d1146322565fdc77e22bf66d9a9f728e', 'REMOVAL', 'owner', '0123456789abcdef0123456789abcdef', 'devX', 'sigBase64', 1000)",
            )
            upgraded.prepare("SELECT groupId, newEpoch, `commit`, reason FROM group_rotation WHERE groupId = 'g2-crew' AND newEpoch = 2").use { row ->
                assertTrue(row.step(), "the group_rotation row was not inserted")
                assertEquals("g2-crew", row.getText(0))
                assertEquals(2L, row.getLong(1))
                assertEquals("316155ba2ffa797afffc435dbba71497d1146322565fdc77e22bf66d9a9f728e", row.getText(2))
                assertEquals("REMOVAL", row.getText(3))
            }
        }
    }

    @Test
    fun `a v9 database gains the group settings and preferences tables and Room validates it`() = runBlocking {
        helper.createDatabase(9).use { v9 ->
            v9.execSQL(
                "INSERT INTO conversations (id, title, isGroup, pinned, muted, archived, sortOrder, groupCreatedBy, groupCreatedAt, groupProto) " +
                    "VALUES ('g2-crew', 'Crew', 1, 0, 0, 0, 1, 'owner', 1, 2)",
            )
        }

        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).use { upgraded ->
            upgraded.execSQL(
                "INSERT INTO group_settings (groupId, version, joinPolicy, inviteSharers, maxMembers, swarmServing, membersMayAdd, opId, signerId, sig) " +
                    "VALUES ('g2-crew', 1, 'APPROVE', 'ALL', 20, 1, 0, 'op1', 'owner', 'sig1')",
            )
            upgraded.prepare("SELECT groupId, version, joinPolicy, maxMembers FROM group_settings WHERE groupId = 'g2-crew'").use { row ->
                assertTrue(row.step(), "the group_settings row was not inserted")
                assertEquals("g2-crew", row.getText(0))
                assertEquals(1L, row.getLong(1))
                assertEquals("APPROVE", row.getText(2))
                assertEquals(20L, row.getLong(3))
            }

            upgraded.execSQL(
                "INSERT INTO group_preferences (groupId, serveToGroup, serveWifiOnly, batteryThresholdPercent, keepAvailableDays, autoAcceptSizeBytes) " +
                    "VALUES ('g2-crew', 1, 0, 20, 7, 104857600)",
            )
            upgraded.prepare("SELECT groupId, serveToGroup, batteryThresholdPercent, autoAcceptSizeBytes FROM group_preferences WHERE groupId = 'g2-crew'").use { row ->
                assertTrue(row.step(), "the group_preferences row was not inserted")
                assertEquals("g2-crew", row.getText(0))
                assertEquals(1L, row.getLong(1))
                assertEquals(20L, row.getLong(2))
                assertEquals(104857600L, row.getLong(3))
            }
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
    fun `a v5 database gains the v2 group columns and its legacy rows read as legacy`() = runBlocking {
        // The ADR-044 V1 step: the only change between v5 and v6.
        helper.createDatabase(5).use { v5 ->
            v5.execSQL(
                "INSERT INTO conversations (id, title, isGroup, pinned, muted, archived, sortOrder, groupCreatedBy, groupCreatedAt) " +
                    "VALUES ('g-old', 'Old group', 1, 0, 0, 0, 1, 'owner', 1)",
            )
            v5.execSQL(
                "INSERT INTO group_members (groupId, deviceId, displayName, role, joinedAt, membershipVersion, operationId, isActive) " +
                    "VALUES ('g-old', 'owner', 'Owner', 'owner', 1, 1, 'op-1', 1)",
            )
            v5.execSQL(
                "INSERT INTO messages (localId, conversationId, senderId, text, sentAt, status, attachmentSize) " +
                    "VALUES ('m-old', 'g-old', 'owner', 'hi', 1, 'SENT', 0)",
            )
        }

        helper.runMigrationsAndValidate(FlashDatabase.DATABASE_VERSION, FlashJvmMigrations.ALL.toList()).use { upgraded ->
            upgraded.prepare("SELECT groupProto, groupOwnerKey, groupNonce, groupCharterSig FROM conversations WHERE id = 'g-old'").use { row ->
                assertTrue(row.step(), "the legacy group conversation was lost by the migration")
                assertEquals(1L, row.getLong(0), "an existing group must read as protocol 1 (legacy)")
                assertTrue(row.isNull(1) && row.isNull(2) && row.isNull(3), "charter columns must default to NULL")
            }
            upgraded.prepare("SELECT subjectKey, certSig, issuerId FROM group_members WHERE deviceId = 'owner'").use { row ->
                assertTrue(row.step(), "the legacy member row was lost by the migration")
                assertTrue(row.isNull(0) && row.isNull(1) && row.isNull(2), "cert columns must default to NULL")
            }
            upgraded.prepare("SELECT groupSig FROM messages WHERE localId = 'm-old'").use { row ->
                assertTrue(row.step(), "the legacy message row was lost by the migration")
                assertTrue(row.isNull(0), "groupSig must default to NULL")
            }
        }
    }

    @Test
    fun `v2 group columns round trip through the DAOs and a rename skips signed rows`() = runBlocking {
        helper.createDatabase(5).close()
        val db = openEncryptedFlashDatabase(dbFile, KEY)
        try {
            db.conversationDao().upsert(
                com.transfer.flash.core.persistence.db.entity.ConversationEntity(
                    id = "g2-abc", title = "Crew", isGroup = true, groupCreatedBy = "owner", groupCreatedAt = 7L,
                    groupProto = 2, groupOwnerKey = "T1dORVI=", groupNonce = "Tk9OQ0U=", groupCharterSig = "Q0hBUlRFUg==",
                ),
            )
            val conversation = db.conversationDao().get("g2-abc")!!
            assertEquals(2, conversation.groupProto)
            assertEquals("T1dORVI=", conversation.groupOwnerKey)
            assertEquals("Tk9OQ0U=", conversation.groupNonce)
            assertEquals("Q0hBUlRFUg==", conversation.groupCharterSig)

            val members = db.groupMemberDao()
            members.upsert(
                com.transfer.flash.core.persistence.db.entity.GroupMemberEntity(
                    groupId = "g2-abc", deviceId = "bea", displayName = "Béa", role = "member", joinedAt = 1L,
                    membershipVersion = 3L, operationId = "op-3", isActive = true,
                    subjectKey = "QkVB", certSig = "U0lH", issuerId = "owner",
                ),
            )
            members.upsert(
                com.transfer.flash.core.persistence.db.entity.GroupMemberEntity(
                    groupId = "g-legacy", deviceId = "bea", displayName = "Béa", role = "member", joinedAt = 1L,
                    membershipVersion = 1L, operationId = "op-1", isActive = true,
                ),
            )
            members.updateMemberDisplayName("bea", "Beatrice")

            val signed = members.member("g2-abc", "bea")!!
            assertEquals("Béa", signed.displayName, "a signed label must not be rewritten by a device rename")
            assertEquals("QkVB", signed.subjectKey)
            assertEquals("U0lH", signed.certSig)
            assertEquals("owner", signed.issuerId)
            assertEquals("Beatrice", members.member("g-legacy", "bea")!!.displayName, "a legacy row still follows the device name")

            db.messageDao().insert(
                com.transfer.flash.core.persistence.db.entity.MessageEntity(
                    localId = "m1", conversationId = "g2-abc", senderId = "bea", senderName = "Béa", text = "hi",
                    sentAt = 1L, status = "SENT", groupSig = "TVNHU0lH",
                ),
            )
            assertEquals("TVNHU0lH", db.messageDao().getByLocalId("m1")!!.groupSig)
        } finally {
            db.close()
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
