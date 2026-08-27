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

    /** Every migration, in order, for the open path. */
    public val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
