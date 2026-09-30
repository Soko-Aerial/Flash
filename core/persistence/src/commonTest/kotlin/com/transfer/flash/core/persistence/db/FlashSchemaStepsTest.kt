package com.transfer.flash.core.persistence.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tripwire for "bumped `DATABASE_VERSION`, forgot the migration".
 *
 * Runs on the Android host-test JVM and on the desktop `jvm()` target, so the shared step list is
 * checked on both. Room's own error for a missing step only appears when an older file is opened
 * on a device; this fails at build time instead.
 */
class FlashSchemaStepsTest {

    @Test
    fun `steps form an unbroken chain from version 1 to the current version`() {
        val steps = FlashSchemaSteps.ALL
        steps.forEachIndexed { index, step ->
            assertEquals(index + 1, step.from, "step #$index must start at version ${index + 1}")
            assertEquals(index + 2, step.to, "step #$index must end at version ${index + 2}")
        }
        assertEquals(
            FlashDatabase.DATABASE_VERSION,
            steps.last().to,
            "DATABASE_VERSION was bumped without a step in FlashSchemaSteps.ALL (C1.7 forbids " +
                "destructive fallback, so an older database could not be opened)",
        )
    }

    @Test
    fun `the v6 step only adds columns so existing rows are never rewritten`() {
        val step = FlashSchemaSteps.STEP_5_6
        assertEquals(5 to 6, step.from to step.to)
        step.statements.forEach { sql ->
            assertTrue(sql.startsWith("ALTER TABLE ") && " ADD COLUMN " in sql, "not an additive column: $sql")
            // An old row must read as a legacy group: NOT NULL is only allowed with a default.
            if ("NOT NULL" in sql) assertTrue(" DEFAULT " in sql, "NOT NULL column without a default: $sql")
        }
        assertEquals(8, step.statements.size, "4 conversation + 3 member + 1 message columns")
    }

    @Test
    fun `a step that skips a version is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { FlashSchemaStep(from = 1, to = 3, statements = listOf("SELECT 1")) }
        Unit
    }

    @Test
    fun `a step without SQL is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { FlashSchemaStep(from = 4, to = 5, statements = emptyList()) }
        assertFailsWith<IllegalArgumentException> { FlashSchemaStep(from = 4, to = 5, statements = listOf(" ")) }
        Unit
    }
}
