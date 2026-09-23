@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network

import com.transfer.flash.core.common.result.runSuspendCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit B3: why cancellation-sensitive waits moved from `runCatching` to [runSuspendCatching], and one
 * thing that is NOT a reason. An earlier claim that `withTimeoutOrNull { runCatching { … } }` turned a
 * timeout into a failure was wrong; the first test pins the real behaviour so that claim stays retracted.
 */
class SuspendCatchingTest {

    @Test
    fun `withTimeoutOrNull returns null on its own timeout even around plain runCatching`() = runBlocking {
        // kotlinx.coroutines discards the block's result after its own timeout, so the old handshake code
        // DID report timeouts correctly. Pinned so nobody "fixes" this again for the wrong reason.
        val waiter = CompletableDeferred<String>()
        assertNull(withTimeoutOrNull(50) { runCatching { waiter.await() } })
    }

    @Test
    fun `runSuspendCatching lets the timeout through, so withTimeoutOrNull returns null`() = runBlocking {
        val waiter = CompletableDeferred<String>()
        val outcome = withTimeoutOrNull(50) { runSuspendCatching { waiter.await() } }
        assertNull("a timeout must read as a timeout", outcome)
    }

    @Test
    fun `ordinary failures are still captured`() = runBlocking {
        val waiter = CompletableDeferred<String>().apply { completeExceptionally(IllegalStateException("version mismatch")) }
        val outcome = withTimeoutOrNull(1_000) { runSuspendCatching { waiter.await() } }
        assertEquals("version mismatch", outcome!!.exceptionOrNull()!!.message)
    }

    @Test
    fun `a cancelled coroutine stops instead of continuing past the call`() = runBlocking {
        var ranPastTheCall = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            runSuspendCatching { awaitCancellation() }
            ranPastTheCall = true
        }
        yield()
        job.cancel()
        runCatching { job.await() }
        assertTrue("cancellation must not be swallowed", !ranPastTheCall)
    }
}
