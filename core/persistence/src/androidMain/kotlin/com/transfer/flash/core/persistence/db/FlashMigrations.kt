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

    /** Every migration, in order, for the open path. */
    public val ALL: Array<Migration> = FlashSchemaSteps.ALL.map { SupportSqlMigration(it) }.toTypedArray()
}

private class SupportSqlMigration(private val step: FlashSchemaStep) : Migration(step.from, step.to) {
    override fun migrate(db: SupportSQLiteDatabase) {
        step.statements.forEach { db.execSQL(it) }
    }
}
