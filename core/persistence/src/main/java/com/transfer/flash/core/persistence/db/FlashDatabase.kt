package com.transfer.flash.core.persistence.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReadCursorDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.TransferChunkDao
import com.transfer.flash.core.persistence.db.dao.TransferDao
import com.transfer.flash.core.persistence.db.dao.TrustedPeerDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReadCursorEntity
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import com.transfer.flash.core.persistence.db.entity.RecentSearchEntity
import com.transfer.flash.core.persistence.db.entity.ReactionEntity
import com.transfer.flash.core.persistence.db.entity.TransferChunkEntity
import com.transfer.flash.core.persistence.db.entity.TransferEntity
import com.transfer.flash.core.persistence.db.entity.TrustedPeerEntity

/**
 * Room 2.x database (androidx.room / SupportSQLite stack — NOT androidx.room3).
 *
 * Schema evolution rules (C1.7): `exportSchema = true`; schemas are versioned in-repo under
 * `core/persistence/schemas/`. From version 2 onward destructive migration is forbidden in the
 * production open path; every bump ships an explicit [androidx.room.migration.Migration].
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        ReceiptEntity::class,
        OutboxEntity::class,
        TransferEntity::class,
        TransferChunkEntity::class,
        RecentSearchEntity::class,
        TrustedPeerEntity::class,
        ReactionEntity::class,
        DraftEntity::class,
        ReadCursorEntity::class,
    ],
    version = FlashDatabase.DATABASE_VERSION,
    exportSchema = true,
)
abstract class FlashDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun conversationDao(): ConversationDao

    abstract fun receiptDao(): ReceiptDao

    abstract fun outboxDao(): OutboxDao

    abstract fun transferDao(): TransferDao

    abstract fun transferChunkDao(): TransferChunkDao

    abstract fun recentSearchDao(): RecentSearchDao

    abstract fun trustedPeerDao(): TrustedPeerDao

    abstract fun reactionDao(): ReactionDao

    abstract fun draftDao(): DraftDao

    abstract fun readCursorDao(): ReadCursorDao

    companion object {
        const val DATABASE_NAME = "flash.db"
        // v2: MessageEntity gained attachment columns (attachmentTransferId/Name/Mime/Size/Path).
        // v3: MessageEntity gained reply columns (replyToId/replyToPreview). Migrations in
        //     [FlashMigrations]; production open path is non-destructive from v2 onward.
        const val DATABASE_VERSION = 3
    }
}
