package com.transfer.flash.core.persistence.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The desktop JVM's counterpart of Android's `FlashMigrations`: every schema step from version 1 to
 * [FlashDatabase.DATABASE_VERSION], as `SQLiteConnection` migrations.
 *
 * The SQL is shared with Android through `FlashSchemaSteps` (commonMain). It is registered by
 * [openFlashDatabase], so every JVM open path — the encrypted desktop one and the driver-injected
 * test one — upgrades an older file instead of failing with "A migration from X to Y was required
 * but not found". Internal on purpose: nothing outside this module opens the database.
 */
internal object FlashJvmMigrations {

    val ALL: Array<Migration> = FlashSchemaSteps.ALL.map { ConnectionMigration(it) }.toTypedArray()
}

private class ConnectionMigration(private val step: FlashSchemaStep) : Migration(step.from, step.to) {
    override fun migrate(connection: SQLiteConnection) {
        step.statements.forEach { connection.execSQL(it) }
    }
}
