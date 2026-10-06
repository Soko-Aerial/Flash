package com.transfer.flash.core.persistence.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android's counterpart of the desktop chain check: [FlashMigrations] is what the encrypted open
 * path registers, and it is built from the SQL shared with the desktop (`FlashSchemaSteps`). This
 * fails at build time when `DATABASE_VERSION` is bumped without a step, instead of on a device
 * holding an older database.
 *
 * The SQL itself is executed and validated against the exported schema by the desktop suite
 * (`FlashJvmMigrationsTest`), because the statements are the same list.
 */
class FlashMigrationsChainTest {

    @Test
    fun `migrations form an unbroken chain from version 1 to the current version`() {
        val all = FlashMigrations.ALL.toList()
        assertEquals(1, all.first().startVersion)
        all.zipWithNext { a, b -> assertEquals("gap after ${a.endVersion}", a.endVersion, b.startVersion) }
        assertEquals(
            "DATABASE_VERSION was bumped without a step in FlashSchemaSteps.ALL",
            FlashDatabase.DATABASE_VERSION,
            all.last().endVersion,
        )
    }

    @Test
    fun `the named migrations are the shared steps in order`() {
        assertEquals(
            listOf(
                FlashMigrations.MIGRATION_1_2,
                FlashMigrations.MIGRATION_2_3,
                FlashMigrations.MIGRATION_3_4,
                FlashMigrations.MIGRATION_4_5,
                FlashMigrations.MIGRATION_5_6,
                FlashMigrations.MIGRATION_6_7,
                FlashMigrations.MIGRATION_7_8,
                FlashMigrations.MIGRATION_8_9,
            ).map { it.startVersion to it.endVersion },
            FlashSchemaSteps.ALL.map { it.from to it.to },
        )
    }
}
