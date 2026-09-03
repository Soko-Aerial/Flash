package com.transfer.flash.core.common

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.result.fold
import com.transfer.flash.core.common.result.flatMap
import com.transfer.flash.core.common.result.getOrElse
import com.transfer.flash.core.common.result.getOrNull
import com.transfer.flash.core.common.result.map
import com.transfer.flash.core.common.result.onFailure
import com.transfer.flash.core.common.result.onSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashResultTest {

    @Test
    fun success_properties_and_accessors() {
        val result: FlashResult<String> = FlashResult.Success("Flash")

        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals("Flash", result.getOrNull())
        assertEquals("Flash", result.getOrElse { "Fallback" })
    }

    @Test
    fun failure_properties_and_accessors() {
        val error = FlashError.NetworkUnavailable("No Wi-Fi")
        val result: FlashResult<String> = FlashResult.Failure(error)

        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        assertEquals("Fallback", result.getOrElse { "Fallback" })
    }

    @Test
    fun map_transforms_success_only() {
        val success: FlashResult<Int> = FlashResult.Success(42)
        val mappedSuccess = success.map { it * 2 }
        assertEquals(FlashResult.Success(84), mappedSuccess)

        val failure: FlashResult<Int> = FlashResult.Failure(FlashError.ConnectionTimeout(5000))
        val mappedFailure = failure.map { it * 2 }
        assertEquals(failure, mappedFailure)
    }

    @Test
    fun flatMap_chains_results() {
        val success: FlashResult<Int> = FlashResult.Success(10)
        val chained = success.flatMap { FlashResult.Success("Number: $it") }
        assertEquals(FlashResult.Success("Number: 10"), chained)

        val failedChain = success.flatMap { FlashResult.Failure(FlashError.Cancelled("User cancelled")) }
        assertTrue(failedChain.isFailure)
    }

    @Test
    fun callbacks_onSuccess_and_onFailure() {
        var successHandled = false
        var failureHandled = false

        FlashResult.Success("test").onSuccess { successHandled = true }.onFailure { failureHandled = true }
        assertTrue(successHandled)
        assertFalse(failureHandled)

        successHandled = false
        FlashResult.Failure(FlashError.ProtocolMismatch(1, 2)).onSuccess { successHandled = true }.onFailure { failureHandled = true }
        assertFalse(successHandled)
        assertTrue(failureHandled)
    }

    @Test
    fun fold_evaluates_branches() {
        val success: FlashResult<String> = FlashResult.Success("hello")
        val successFolded = success.fold(
            onSuccess = { "success: $it" },
            onFailure = { "failure: $it" }
        )
        assertEquals("success: hello", successFolded)

        val failure: FlashResult<String> = FlashResult.Failure(FlashError.PeerUnavailable("dev-1"))
        val failureFolded = failure.fold(
            onSuccess = { "success: $it" },
            onFailure = { "failure" }
        )
        assertEquals("failure", failureFolded)
    }

    @Test
    fun runCatching_captures_exceptions() {
        val success = FlashResult.runCatching { "ok" }
        assertEquals(FlashResult.Success("ok"), success)

        val failure = FlashResult.runCatching { throw IllegalStateException("boom") }
        assertTrue(failure.isFailure)
        val error = (failure as FlashResult.Failure).error
        assertTrue(error is FlashError.Unknown)
        assertEquals("boom", (error as FlashError.Unknown).message)
    }
}
