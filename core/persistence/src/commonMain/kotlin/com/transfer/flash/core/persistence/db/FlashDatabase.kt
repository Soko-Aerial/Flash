package com.transfer.flash.core.persistence.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.GroupDeliveryDao
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReadCursorDao
import com.transfer.flash.core.persistence.db.dao.RememberedEndpointDao
import com.transfer.flash.core.persistence.db.dao.SwarmDao
import com.transfer.flash.core.persistence.db.dao.GroupInviteDao
import com.transfer.flash.core.persistence.db.dao.GroupJoinRequestDao
import com.transfer.flash.core.persistence.db.dao.GroupPreferencesDao
import com.transfer.flash.core.persistence.db.dao.GroupRotationDao
import com.transfer.flash.core.persistence.db.dao.GroupSecretDao
import com.transfer.flash.core.persistence.db.dao.GroupSettingsDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.TransferChunkDao
import com.transfer.flash.core.persistence.db.dao.TransferDao
import com.transfer.flash.core.persistence.db.dao.TrustedPeerDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import com.transfer.flash.core.persistence.db.entity.GroupInviteEntity
import com.transfer.flash.core.persistence.db.entity.GroupJoinRequestEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.GroupPreferencesEntity
import com.transfer.flash.core.persistence.db.entity.GroupRotationEntity
import com.transfer.flash.core.persistence.db.entity.GroupSecretEntity
import com.transfer.flash.core.persistence.db.entity.GroupSettingsEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReadCursorEntity
import com.transfer.flash.core.persistence.db.entity.RememberedEndpointEntity
import com.transfer.flash.core.persistence.db.entity.SwarmContentEntity
import com.transfer.flash.core.persistence.db.entity.SwarmTombstoneEntity
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
 * production open path; every bump ships an explicit [androidx.room.migration.Migration]. The SQL
 * is written once in `FlashSchemaSteps` and reaches both Android and the desktop JVM from there.
 *
 * Phase 09B-1 moved this file to `commonMain` and added exactly one line, `@ConstructedBy`. That is
 * a narrow, sanctioned R8 exception (PHASE-09B Obstacle B): Room's reflective builder is
 * Android-only, so every non-Android target needs the generated-constructor route, and the
 * annotation is how the processor is told which object to emit. It adds no column, no index and no
 * SQL; it does not change [DATABASE_VERSION], the entity list, or `exportSchema`. The exported
 * schema JSON under `core/persistence/schemas/` must be byte-identical afterwards — that is the
 * gate that discharges the exception.
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
        GroupMemberEntity::class,
        GroupDeliveryEntity::class,
        RememberedEndpointEntity::class,
        SwarmContentEntity::class,
        SwarmTombstoneEntity::class,
        GroupSecretEntity::class,
        GroupInviteEntity::class,
        GroupJoinRequestEntity::class,
        GroupRotationEntity::class,
        GroupSettingsEntity::class,
        GroupPreferencesEntity::class,
    ],
    version = FlashDatabase.DATABASE_VERSION,
    exportSchema = true,
)
@ConstructedBy(FlashDatabaseConstructor::class)
public abstract class FlashDatabase : RoomDatabase() {

    public abstract fun messageDao(): MessageDao

    public abstract fun conversationDao(): ConversationDao

    public abstract fun receiptDao(): ReceiptDao

    public abstract fun outboxDao(): OutboxDao

    public abstract fun transferDao(): TransferDao

    public abstract fun transferChunkDao(): TransferChunkDao

    public abstract fun recentSearchDao(): RecentSearchDao

    public abstract fun trustedPeerDao(): TrustedPeerDao

    public abstract fun reactionDao(): ReactionDao

    public abstract fun draftDao(): DraftDao

    public abstract fun readCursorDao(): ReadCursorDao

    public abstract fun groupMemberDao(): GroupMemberDao

    public abstract fun groupDeliveryDao(): GroupDeliveryDao

    public abstract fun rememberedEndpointDao(): RememberedEndpointDao

    public abstract fun swarmDao(): SwarmDao

    public abstract fun groupSecretDao(): GroupSecretDao

    public abstract fun groupInviteDao(): GroupInviteDao

    public abstract fun groupJoinRequestDao(): GroupJoinRequestDao

    public abstract fun groupRotationDao(): GroupRotationDao

    public abstract fun groupSettingsDao(): GroupSettingsDao

    public abstract fun groupPreferencesDao(): GroupPreferencesDao

    public companion object {
        public const val DATABASE_NAME: String = "flash.db"
        // v2: MessageEntity gained attachment columns (attachmentTransferId/Name/Mime/Size/Path).
        // v3: MessageEntity gained reply columns (replyToId/replyToPreview).
        // v4: group membership/delivery tables and conversation group provenance.
        // v5: remembered_endpoints, the DR1 dial hints for paired peers (ADR-047).
        // v6: signed group membership and messages, the nullable v2-group columns (ADR-044 V1).
        // v7: swarm_content and swarm_tombstone for group swarm transfers (ADR-072, SW-6).
        // v8: group_secret, group_invite, group_join_request for group membership by secret (ADR-044, ADR-073, GM-2).
        // v9: group_rotation for group rotation notices (ADR-044, ADR-073, GM-6).
        // v10: group_settings and group_preferences for signed settings and local prefs (ADR-074, GM-9).
        public const val DATABASE_VERSION: Int = 10
    }
}
