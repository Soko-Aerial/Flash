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

    /** Every step, in order, from version 1 to `FlashDatabase.DATABASE_VERSION`. */
    val ALL: List<FlashSchemaStep> = listOf(STEP_1_2, STEP_2_3, STEP_3_4, STEP_4_5, STEP_5_6)
}
