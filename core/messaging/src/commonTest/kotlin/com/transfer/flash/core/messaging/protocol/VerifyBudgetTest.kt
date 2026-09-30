package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyBudgetTest {
    @Test
    fun aPeerGetsExactlyItsWindowAllowance() {
        val budget = VerifyBudget(perWindow = 10, windowMs = 1_000L)
        assertTrue(budget.tryConsume("p", 6, nowMs = 0L))
        assertTrue(budget.tryConsume("p", 4, nowMs = 10L))
        assertFalse(budget.tryConsume("p", 1, nowMs = 20L))
    }

    @Test
    fun aRefusedRequestReservesNothing() {
        val budget = VerifyBudget(perWindow = 10, windowMs = 1_000L)
        assertTrue(budget.tryConsume("p", 8, nowMs = 0L))
        assertFalse(budget.tryConsume("p", 5, nowMs = 1L), "does not fit")
        assertTrue(budget.tryConsume("p", 2, nowMs = 2L), "the failed request must not have used the 2 that were left")
    }

    @Test
    fun theWindowRollsOver() {
        val budget = VerifyBudget(perWindow = 3, windowMs = 1_000L)
        assertTrue(budget.tryConsume("p", 3, nowMs = 0L))
        assertFalse(budget.tryConsume("p", 1, nowMs = 999L))
        assertTrue(budget.tryConsume("p", 3, nowMs = 1_000L))
    }

    @Test
    fun peersDoNotShareABudget() {
        val budget = VerifyBudget(perWindow = 2, windowMs = 1_000L)
        assertTrue(budget.tryConsume("a", 2, nowMs = 0L))
        assertFalse(budget.tryConsume("a", 1, nowMs = 0L))
        assertTrue(budget.tryConsume("b", 2, nowMs = 0L))
    }

    @Test
    fun nothingToVerifyIsAlwaysAllowed() {
        val budget = VerifyBudget(perWindow = 1, windowMs = 1_000L)
        assertTrue(budget.tryConsume("p", 1, nowMs = 0L))
        assertTrue(budget.tryConsume("p", 0, nowMs = 1L))
    }

    @Test
    fun aRequestLargerThanTheWholeWindowIsNeverAdmitted() {
        val budget = VerifyBudget(perWindow = 5, windowMs = 1_000L)
        assertFalse(budget.tryConsume("p", 6, nowMs = 0L))
        assertFalse(budget.tryConsume("p", 6, nowMs = 5_000L))
    }
}
