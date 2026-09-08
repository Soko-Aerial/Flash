package com.transfer.flash.core.persistence.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema migrations for [FlashDatabase]. From v2 onward destructive fallback is
 * FORBIDDEN in the production (encrypted) open path (C1.7): every version bump ships a
 * [Migration] here so no user data is ever silently wiped on upgrade.
 *
 * Pass [ALL] to [FlashDatabaseOpener.openEncrypted].
 */
public object FlashMigrations {

    /** v1 → v2: [MessageEntity] gained inline-attachment columns. */
    public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN attachmentTransferId TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachmentName TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachmentMime TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachmentSize INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachmentPath TEXT")
        }
    }

    /** v2 → v3: [MessageEntity] gained reply/quote columns. */
    public val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToId TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToPreview TEXT")
        }
    }

    /** v3 → v4: group membership/delivery state plus immutable group provenance. */
    public val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN groupCreatedBy TEXT")
            db.execSQL("ALTER TABLE conversations ADD COLUMN groupCreatedAt INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_members (" +
                    "groupId TEXT NOT NULL, deviceId TEXT NOT NULL, displayName TEXT NOT NULL, " +
                    "role TEXT NOT NULL, joinedAt INTEGER NOT NULL, membershipVersion INTEGER NOT NULL, " +
                    "operationId TEXT NOT NULL, isActive INTEGER NOT NULL, " +
                    "PRIMARY KEY(groupId, deviceId))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_group_members_groupId_isActive " +
                    "ON group_members(groupId, isActive)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_deliveries (" +
                    "messageId TEXT NOT NULL, memberId TEXT NOT NULL, state TEXT NOT NULL, " +
                    "attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, deliveredAt INTEGER, " +
                    "PRIMARY KEY(messageId, memberId))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_group_deliveries_memberId_nextAttemptAt " +
                    "ON group_deliveries(memberId, nextAttemptAt)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_group_deliveries_messageId_state " +
                    "ON group_deliveries(messageId, state)",
            )
        }
    }

    /** Every migration, in order, for the open path. */
    public val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
