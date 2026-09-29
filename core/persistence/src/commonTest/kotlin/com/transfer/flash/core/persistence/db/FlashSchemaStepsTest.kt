package com.transfer.flash.core.persistence.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
