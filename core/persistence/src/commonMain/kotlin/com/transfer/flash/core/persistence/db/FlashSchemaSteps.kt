package com.transfer.flash.core.persistence.db

/**
 * One schema upgrade: the SQL that takes a database from [from] to [to].
 *
 * Only DDL and data-preserving statements belong here. Room runs a migration inside the open
 * transaction, so a step either applies completely or not at all.
 */
internal class FlashSchemaStep(
    val from: Int,
    val to: Int,
    val statements: List<String>,
) {
    init {
        require(to == from + 1) { "a schema step moves exactly one version: $from -> $to" }
        require(statements.isNotEmpty() && statements.none { it.isBlank() }) {
            "step $from -> $to has no SQL or a blank statement"
        }
    }
}

/**
 * The SQL of every [FlashDatabase] migration, written **once** for every target.
 *
 * Android wraps these in `SupportSQLiteDatabase` migrations (`FlashMigrations`, androidMain) and the
 * desktop JVM wraps the same statements in `SQLiteConnection` migrations (`FlashJvmMigrations`,
 * jvmMain). Before this file the SQL existed only in `androidMain`, and the desktop opener
 * registered no migrations at all: a database that pre-dated a schema bump would have failed to open
 * with "A migration from X to Y was required but not found", and the desktop engine swallows a
 * database failure and falls back to an empty chat repository. Keeping the SQL in one place means a
 * new version cannot be migrated on one platform and forgotten on the other.
 *
 * ## Adding a schema version
 *
 * 1. Bump `FlashDatabase.DATABASE_VERSION` and let Room export the new `schemas/<n>.json`.
 * 2. Append the step to [ALL]. Nothing else needs touching: both wrappers are built from this list.
 * 3. Run `FlashSchemaStepsTest` (fails if the chain is not contiguous up to `DATABASE_VERSION`) and
 *    `FlashJvmMigrationsTest` (upgrades a real database and lets Room validate it against the
 *    exported schema).
 *
 * Destructive fallback is forbidden in the production open path (C1.7): every bump ships a step.
 */
internal object FlashSchemaSteps {

    /** v1 → v2: `messages` gained inline-attachment columns. */
    val STEP_1_2: FlashSchemaStep = FlashSchemaStep(
        from = 1,
        to = 2,
        statements = listOf(
            "ALTER TABLE messages ADD COLUMN attachmentTransferId TEXT",
            "ALTER TABLE messages ADD COLUMN attachmentName TEXT",
            "ALTER TABLE messages ADD COLUMN attachmentMime TEXT",
            "ALTER TABLE messages ADD COLUMN attachmentSize INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE messages ADD COLUMN attachmentPath TEXT",
        ),
    )

    /** v2 → v3: `messages` gained reply/quote columns. */
    val STEP_2_3: FlashSchemaStep = FlashSchemaStep(
        from = 2,
        to = 3,
        statements = listOf(
            "ALTER TABLE messages ADD COLUMN replyToId TEXT",
            "ALTER TABLE messages ADD COLUMN replyToPreview TEXT",
        ),
    )

    /** v3 → v4: group membership/delivery state plus immutable group provenance. */
    val STEP_3_4: FlashSchemaStep = FlashSchemaStep(
        from = 3,
        to = 4,
        statements = listOf(
            "ALTER TABLE conversations ADD COLUMN groupCreatedBy TEXT",
            "ALTER TABLE conversations ADD COLUMN groupCreatedAt INTEGER",
            "CREATE TABLE IF NOT EXISTS group_members (" +
                "groupId TEXT NOT NULL, deviceId TEXT NOT NULL, displayName TEXT NOT NULL, " +
                "role TEXT NOT NULL, joinedAt INTEGER NOT NULL, membershipVersion INTEGER NOT NULL, " +
                "operationId TEXT NOT NULL, isActive INTEGER NOT NULL, " +
                "PRIMARY KEY(groupId, deviceId))",
            "CREATE INDEX IF NOT EXISTS index_group_members_groupId_isActive " +
                "ON group_members(groupId, isActive)",
            "CREATE TABLE IF NOT EXISTS group_deliveries (" +
                "messageId TEXT NOT NULL, memberId TEXT NOT NULL, state TEXT NOT NULL, " +
                "attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, deliveredAt INTEGER, " +
                "PRIMARY KEY(messageId, memberId))",
            "CREATE INDEX IF NOT EXISTS index_group_deliveries_memberId_nextAttemptAt " +
                "ON group_deliveries(memberId, nextAttemptAt)",
            "CREATE INDEX IF NOT EXISTS index_group_deliveries_messageId_state " +
                "ON group_deliveries(messageId, state)",
        ),
    )

    /** v4 → v5: DR1's remembered routes for paired peers (ADR-047). */
    val STEP_4_5: FlashSchemaStep = FlashSchemaStep(
        from = 4,
        to = 5,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS remembered_endpoints (" +
                "deviceId TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, " +
                "lastConnectedAt INTEGER NOT NULL, firstFailureAt INTEGER, " +
                "PRIMARY KEY(deviceId, host, port))",
        ),
    )

    /**
     * v5 → v6: v2 groups (ADR-044 V1). Every column is nullable or defaulted, so existing rows read
     * as legacy (`groupProto = 1`, no keys, no signatures) and nothing is rewritten.
     */
    val STEP_5_6: FlashSchemaStep = FlashSchemaStep(
        from = 5,
        to = 6,
        statements = listOf(
            "ALTER TABLE conversations ADD COLUMN groupProto INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE conversations ADD COLUMN groupOwnerKey TEXT",
            "ALTER TABLE conversations ADD COLUMN groupNonce TEXT",
            "ALTER TABLE conversations ADD COLUMN groupCharterSig TEXT",
            "ALTER TABLE group_members ADD COLUMN subjectKey TEXT",
            "ALTER TABLE group_members ADD COLUMN certSig TEXT",
            "ALTER TABLE group_members ADD COLUMN issuerId TEXT",
            "ALTER TABLE messages ADD COLUMN groupSig TEXT",
        ),
    )

    /**
     * v6 → v7: group swarm content and tombstones (ADR-070, SW-6).
     */
    val STEP_6_7: FlashSchemaStep = FlashSchemaStep(
        from = 6,
        to = 7,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS swarm_content (" +
                "root TEXT NOT NULL, groupId TEXT NOT NULL, messageId TEXT NOT NULL, role TEXT NOT NULL, " +
                "originId TEXT NOT NULL, originKey TEXT NOT NULL, fileName TEXT NOT NULL, mime TEXT NOT NULL, " +
                "totalSize INTEGER NOT NULL, pieceSize INTEGER NOT NULL, manifest BLOB, bits BLOB NOT NULL, " +
                "bytesDone INTEGER NOT NULL, state TEXT NOT NULL, waitReason TEXT, failReason TEXT, " +
                "localTransferId TEXT NOT NULL, sourceUri TEXT, sourcePersistent INTEGER NOT NULL, " +
                "partialKey TEXT NOT NULL, finalPath TEXT, identitySize INTEGER NOT NULL, " +
                "identityModifiedMs INTEGER NOT NULL, deliveredTo TEXT NOT NULL, createdAtMs INTEGER NOT NULL, " +
                "lastProgressAtMs INTEGER NOT NULL, expiresAtMs INTEGER NOT NULL, " +
                "PRIMARY KEY(root, groupId))",
            "CREATE INDEX IF NOT EXISTS index_swarm_content_groupId ON swarm_content (groupId)",
            "CREATE INDEX IF NOT EXISTS index_swarm_content_messageId ON swarm_content (messageId)",
            "CREATE INDEX IF NOT EXISTS index_swarm_content_state ON swarm_content (state)",
            "CREATE INDEX IF NOT EXISTS index_swarm_content_localTransferId ON swarm_content (localTransferId)",
            "CREATE TABLE IF NOT EXISTS swarm_tombstone (" +
                "groupId TEXT NOT NULL, messageId TEXT NOT NULL, root TEXT NOT NULL, originId TEXT NOT NULL, " +
                "reason TEXT NOT NULL, cancelledAtMs INTEGER NOT NULL, signature BLOB NOT NULL, " +
                "receivedAtMs INTEGER NOT NULL, expiresAtMs INTEGER NOT NULL, " +
                "PRIMARY KEY(groupId, messageId))",
            "CREATE INDEX IF NOT EXISTS index_swarm_tombstone_root ON swarm_tombstone (root)",
            "CREATE INDEX IF NOT EXISTS index_swarm_tombstone_groupId ON swarm_tombstone (groupId)",
        ),
    )

    /**
     * v7 → v8: group membership by shared secret, invites, and join requests (ADR-044, ADR-073, GM-2).
     */
    val STEP_7_8: FlashSchemaStep = FlashSchemaStep(
        from = 7,
        to = 8,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS group_secret (" +
                "groupId TEXT NOT NULL, epoch INTEGER NOT NULL, secretWrapped BLOB NOT NULL, " +
                "`commit` TEXT NOT NULL, source TEXT NOT NULL, receivedAtMs INTEGER NOT NULL, " +
                "PRIMARY KEY(groupId, epoch))",
            "CREATE INDEX IF NOT EXISTS index_group_secret_groupId ON group_secret (groupId)",
            "CREATE TABLE IF NOT EXISTS group_invite (" +
                "groupId TEXT NOT NULL, inviterId TEXT NOT NULL, inviterFingerprint TEXT NOT NULL, " +
                "acceptedAtMs INTEGER NOT NULL, state TEXT NOT NULL, " +
                "PRIMARY KEY(groupId))",
            "CREATE INDEX IF NOT EXISTS index_group_invite_state ON group_invite (state)",
            "CREATE TABLE IF NOT EXISTS group_join_request (" +
                "groupId TEXT NOT NULL, subjectId TEXT NOT NULL, subjectKey TEXT NOT NULL, " +
                "label TEXT NOT NULL, requestSig TEXT NOT NULL, viaPeerId TEXT, " +
                "requestedAtMs INTEGER NOT NULL, state TEXT NOT NULL, decidedBy TEXT, " +
                "decidedAtMs INTEGER, " +
                "PRIMARY KEY(groupId, subjectId, subjectKey))",
            "CREATE INDEX IF NOT EXISTS index_group_join_request_groupId ON group_join_request (groupId)",
            "CREATE INDEX IF NOT EXISTS index_group_join_request_state ON group_join_request (state)",
        ),
    )

    /**
     * v8 → v9: group rotation notices (ADR-044, ADR-073, GM-6).
     */
    val STEP_8_9: FlashSchemaStep = FlashSchemaStep(
        from = 8,
        to = 9,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS group_rotation (" +
                "groupId TEXT NOT NULL, newEpoch INTEGER NOT NULL, prevEpoch INTEGER NOT NULL, " +
                "`commit` TEXT NOT NULL, reason TEXT NOT NULL, adminId TEXT NOT NULL, " +
                "rotationId TEXT NOT NULL, removedIds TEXT NOT NULL, sig TEXT NOT NULL, " +
                "receivedAtMs INTEGER NOT NULL, " +
                "PRIMARY KEY(groupId, newEpoch))",
            "CREATE INDEX IF NOT EXISTS index_group_rotation_groupId ON group_rotation (groupId)",
        ),
    )

    /**
     * v9 → v10: group settings and device-local preferences (ADR-074, GM-9).
     */
    val STEP_9_10: FlashSchemaStep = FlashSchemaStep(
        from = 9,
        to = 10,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS group_settings (" +
                "groupId TEXT NOT NULL, version INTEGER NOT NULL, joinPolicy TEXT NOT NULL, " +
                "inviteSharers TEXT NOT NULL, maxMembers INTEGER NOT NULL, " +
                "swarmServing INTEGER NOT NULL, membersMayAdd INTEGER NOT NULL, " +
                "opId TEXT NOT NULL, signerId TEXT NOT NULL, sig TEXT NOT NULL, " +
                "PRIMARY KEY(groupId))",
            "CREATE TABLE IF NOT EXISTS group_preferences (" +
                "groupId TEXT NOT NULL, serveToGroup INTEGER NOT NULL, serveWifiOnly INTEGER NOT NULL, " +
                "batteryThresholdPercent INTEGER NOT NULL, keepAvailableDays INTEGER NOT NULL, " +
                "autoAcceptSizeBytes INTEGER NOT NULL, " +
                "PRIMARY KEY(groupId))",
        ),
    )

    /**
     * v10 → v11: the swarm offer of a group file message (ERROR-108). Nullable, so every existing row reads as "no offer".
     */
    val STEP_10_11: FlashSchemaStep = FlashSchemaStep(
        from = 10,
        to = 11,
        statements = listOf(
            "ALTER TABLE messages ADD COLUMN swarmRoot TEXT",
            "ALTER TABLE messages ADD COLUMN swarmPieceSize INTEGER",
            "ALTER TABLE messages ADD COLUMN swarmRootSig TEXT",
        ),
    )

    /**
     * v11 → v12: device-local pinned messages (UI roadmap 3.4). A new table only, so nothing existing is touched.
     */
    val STEP_11_12: FlashSchemaStep = FlashSchemaStep(
        from = 11,
        to = 12,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS message_pins (" +
                "conversationId TEXT NOT NULL, messageId TEXT NOT NULL, pinnedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(conversationId, messageId))",
            "CREATE INDEX IF NOT EXISTS index_message_pins_conversationId ON message_pins (conversationId)",
        ),
    )

    /**
     * v12 → v13: group history sync (ADR-100). The signed ceiling is one defaulted column (every existing row reads
     * as `D30`, the default, whose canonical bytes are unchanged); the join decision and the per-holder watermark are
     * new device-local tables, so nothing existing is rewritten.
     */
    val STEP_12_13: FlashSchemaStep = FlashSchemaStep(
        from = 12,
        to = 13,
        statements = listOf(
            "ALTER TABLE group_settings ADD COLUMN historyCeiling TEXT NOT NULL DEFAULT 'D30'",
            "CREATE TABLE IF NOT EXISTS group_history_state (" +
                "groupId TEXT NOT NULL, cardState TEXT NOT NULL, windowMs INTEGER NOT NULL, " +
                "includeFiles INTEGER NOT NULL, decidedAtMs INTEGER NOT NULL, lastContactAtMs INTEGER NOT NULL, " +
                "createdAtMs INTEGER NOT NULL, PRIMARY KEY(groupId))",
            "CREATE TABLE IF NOT EXISTS group_sync_watermark (" +
                "groupId TEXT NOT NULL, holderId TEXT NOT NULL, sentAt INTEGER NOT NULL, " +
                "messageId TEXT NOT NULL, updatedAtMs INTEGER NOT NULL, PRIMARY KEY(groupId, holderId))",
        ),
    )

    /**
     * v13 → v14: offline organization directory trust (Plan B, ADR-134).
     * Adds `org_directory_member`, `org_department` and `org_sync_state`.
     */
    val STEP_13_14: FlashSchemaStep = FlashSchemaStep(
        from = 13,
        to = 14,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS `org_directory_member` (" +
                "`deviceId` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`displayNameFolded` TEXT NOT NULL, " +
                "`departmentId` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, " +
                "`subjectPublicKeyFingerprint` TEXT NOT NULL, " +
                "`statementBytes` BLOB NOT NULL, " +
                "`statementSerial` INTEGER NOT NULL, " +
                "`statementIssuedAtMs` INTEGER NOT NULL, " +
                "`statementDelegationSerial` INTEGER NOT NULL, " +
                "`lastSyncedAtMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`))",
            "CREATE INDEX IF NOT EXISTS `index_org_directory_member_subjectPublicKeyFingerprint` " +
                "ON `org_directory_member` (`subjectPublicKeyFingerprint`)",
            "CREATE INDEX IF NOT EXISTS `index_org_directory_member_displayNameFolded` " +
                "ON `org_directory_member` (`displayNameFolded`)",
            "CREATE INDEX IF NOT EXISTS `index_org_directory_member_departmentId` " +
                "ON `org_directory_member` (`departmentId`)",
            "CREATE TABLE IF NOT EXISTS `org_department` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`memberCount` INTEGER NOT NULL, " +
                "`headDeviceIdsJson` TEXT NOT NULL, " +
                "`hidden` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `org_sync_state` (" +
                "`id` INTEGER NOT NULL, " +
                "`directoryEpoch` TEXT NOT NULL, " +
                "`lastSyncedVersion` INTEGER NOT NULL, " +
                "`offlineGraceDays` INTEGER NOT NULL, " +
                "`lastSyncSuccessMs` INTEGER NOT NULL, " +
                "`lastSyncAttemptMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        ),
    )

    /**
     * v14 → v15: bound snapshot to organization root fingerprint and record sync error kind.
     * Adds `orgRootFingerprint` and `lastErrorKind` to `org_sync_state`.
     */
    val STEP_14_15: FlashSchemaStep = FlashSchemaStep(
        from = 14,
        to = 15,
        statements = listOf(
            "ALTER TABLE `org_sync_state` ADD COLUMN `orgRootFingerprint` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `org_sync_state` ADD COLUMN `lastErrorKind` TEXT DEFAULT NULL",
        ),
    )

    /**
     * v15 → v16: hub-signed group roster statements, for hub groups that keep working on the LAN
     * (Plan B Phase B4, `docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md` section 10).
     */
    val STEP_15_16: FlashSchemaStep = FlashSchemaStep(
        from = 15,
        to = 16,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS `hub_group_roster` (" +
                "`groupId` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL, " +
                "`statementJson` TEXT NOT NULL, " +
                "`delegationChainJson` TEXT NOT NULL, " +
                "`validUntilMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`groupId`))",
        ),
    )

    /**
     * v16 → v17: organization announcements (Plan A3, ADR-138).
     * Adds `hub_announcement` and `hub_announcement_state`.
     */
    val STEP_16_17: FlashSchemaStep = FlashSchemaStep(
        from = 16,
        to = 17,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS `hub_announcement` (" +
                "`id` TEXT NOT NULL, " +
                "`scope` TEXT NOT NULL, " +
                "`departmentId` TEXT, " +
                "`title` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, " +
                "`authorName` TEXT NOT NULL, " +
                "`authorRole` TEXT NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL, " +
                "`updatedAtMs` INTEGER NOT NULL, " +
                "`edited` INTEGER NOT NULL, " +
                "`pinned` INTEGER NOT NULL, " +
                "`expiresAtMs` INTEGER, " +
                "`seq` INTEGER NOT NULL, " +
                "`readAtMs` INTEGER, " +
                "`searchFolded` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_hub_announcement_scope_departmentId_createdAtMs` " +
                "ON `hub_announcement` (`scope`, `departmentId`, `createdAtMs`)",
            "CREATE INDEX IF NOT EXISTS `index_hub_announcement_readAtMs` " +
                "ON `hub_announcement` (`readAtMs`)",
            "CREATE INDEX IF NOT EXISTS `index_hub_announcement_searchFolded` " +
                "ON `hub_announcement` (`searchFolded`)",
            "CREATE TABLE IF NOT EXISTS `hub_announcement_state` (" +
                "`id` INTEGER NOT NULL, " +
                "`epoch` TEXT NOT NULL, " +
                "`seq` INTEGER NOT NULL, " +
                "`lastSyncAtMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        ),
    )

    /**
     * v17 → v18: files sent and received through the organisation hub (ADR-215, ADR-216, HUB-FILES P3).
     * Adds `hub_file`.
     */
    val STEP_17_18: FlashSchemaStep = FlashSchemaStep(
        from = 17,
        to = 18,
        statements = listOf(
            "CREATE TABLE IF NOT EXISTS `hub_file` (" +
                "`transferId` TEXT NOT NULL, " +
                "`direction` TEXT NOT NULL, " +
                "`fileId` TEXT NOT NULL, " +
                "`peerId` TEXT NOT NULL, " +
                "`hubUrl` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, " +
                "`mime` TEXT NOT NULL, " +
                "`plainBytes` INTEGER NOT NULL, " +
                "`sourceUri` TEXT, " +
                "`fileKey` TEXT, " +
                "`cipherSha256` TEXT, " +
                "`cipherChunkBytes` INTEGER NOT NULL, " +
                "`chunkCount` INTEGER NOT NULL, " +
                "`expiresAtMs` INTEGER NOT NULL, " +
                "`messageId` TEXT, " +
                "`state` TEXT NOT NULL, " +
                "`paused` INTEGER NOT NULL, " +
                "`errorText` TEXT, " +
                "`bytesDone` INTEGER NOT NULL, " +
                "`localPath` TEXT, " +
                "`createdAtMs` INTEGER NOT NULL, " +
                "`updatedAtMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`transferId`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_hub_file_direction_fileId` ON `hub_file` (`direction`, `fileId`)",
            "CREATE INDEX IF NOT EXISTS `index_hub_file_messageId` ON `hub_file` (`messageId`)",
        ),
    )

    /** Every step, in order, from version 1 to `FlashDatabase.DATABASE_VERSION`. */
    val ALL: List<FlashSchemaStep> = listOf(
        STEP_1_2, STEP_2_3, STEP_3_4, STEP_4_5, STEP_5_6, STEP_6_7, STEP_7_8, STEP_8_9, STEP_9_10, STEP_10_11, STEP_11_12, STEP_12_13, STEP_13_14, STEP_14_15, STEP_15_16, STEP_16_17, STEP_17_18
    )
}
