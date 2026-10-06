package com.transfer.flash.core.persistence.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema migrations for [FlashDatabase]. From v2 onward destructive fallback is
 * FORBIDDEN in the production (encrypted) open path (C1.7): every version bump ships a
 * [Migration] here so no user data is ever silently wiped on upgrade.
 *
 * The SQL itself lives in `FlashSchemaSteps` (commonMain) and is shared with the desktop JVM's
 * `FlashJvmMigrations`; this object only wraps each step in a `SupportSQLiteDatabase` migration.
 * To add a version, append a step there — see that file's checklist.
 *
 * Pass [ALL] to [FlashDatabaseOpener.openEncrypted].
 */
public object FlashMigrations {

    /** v1 → v2: [MessageEntity] gained inline-attachment columns. */
    public val MIGRATION_1_2: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_1_2)

    /** v2 → v3: [MessageEntity] gained reply/quote columns. */
    public val MIGRATION_2_3: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_2_3)

    /** v3 → v4: group membership/delivery state plus immutable group provenance. */
    public val MIGRATION_3_4: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_3_4)

    /** v4 -> v5: DR1's `remembered_endpoints` table (ADR-047). */
    public val MIGRATION_4_5: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_4_5)

    /** v5 -> v6: v2-group columns (ADR-044 V1). */
    public val MIGRATION_5_6: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_5_6)

    /** v6 -> v7: group swarm content and tombstones (ADR-070, SW-6). */
    public val MIGRATION_6_7: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_6_7)

    /** v7 -> v8: group membership by shared secret, invites, and join requests (ADR-044, ADR-073, GM-2). */
    public val MIGRATION_7_8: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_7_8)

    /** v8 -> v9: group rotation notices (ADR-044, ADR-073, GM-6). */
    public val MIGRATION_8_9: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_8_9)

    /** v9 -> v10: group settings and local preferences (ADR-074, GM-9). */
    public val MIGRATION_9_10: Migration = SupportSqlMigration(FlashSchemaSteps.STEP_9_10)

    /** Every migration, in order, for the open path. */
    public val ALL: Array<Migration> = FlashSchemaSteps.ALL.map { SupportSqlMigration(it) }.toTypedArray()
}

private class SupportSqlMigration(private val step: FlashSchemaStep) : Migration(step.from, step.to) {
    override fun migrate(db: SupportSQLiteDatabase) {
        step.statements.forEach { db.execSQL(it) }
    }
}
