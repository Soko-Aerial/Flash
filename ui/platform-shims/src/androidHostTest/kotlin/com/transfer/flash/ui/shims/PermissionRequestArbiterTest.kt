package com.transfer.flash.ui.shims

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Concurrency rules of the permission shim (review 2026-10-22, F51).
 *
 * The shim used to keep ONE pending continuation in a field. Two requests raised before the first
 * dialog was answered meant the second overwrote the first, so the first caller was suspended for
 * ever and the single callback could resume the survivor with the answer to the other permission.
 * These tests drive the arbiter with a fake launcher, which is the seam the Android launcher hides.
 *
 * Everything runs on `runBlocking`'s single-threaded event loop, so the "launcher" list needs no
 * locking and the interleavings are the ones the test asks for.
 */
class PermissionRequestArbiterTest {

    /** Waits for a condition, yielding to the other coroutines on the loop, with a hard ceiling. */
    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            delay(2)
        }
        fail("timed out waiting for: $what")
    }

    @Test
    fun `two concurrent requests raise two dialogs in turn and both callers are answered`() = runBlocking {
        val arbiter = PermissionRequestArbiter()
        val launched = mutableListOf<String>()
        val granted = mutableSetOf<String>()

        val first = async { arbiter.ensure("CAMERA", { it in granted }, { launched += it }) }
        waitUntil("the first dialog") { launched.size == 1 }

        val second = async { arbiter.ensure("MICROPHONE", { it in granted }, { launched += it }) }
        // The second request must NOT raise its dialog on top of the first.
        delay(50)
        assertEquals(listOf("CAMERA"), launched)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        granted += "CAMERA"
        arbiter.onResult(true)
        assertTrue(first.await(), "the first caller got its own answer")

        waitUntil("the second dialog") { launched.size == 2 }
        assertEquals("MICROPHONE", launched[1])

        granted += "MICROPHONE"
        arbiter.onResult(true)
        assertTrue(second.await(), "the second caller was not left suspended")
        assertEquals(2, launched.size)
    }

    @Test
    fun `a denied answer reaches the caller that raised the dialog`() = runBlocking {
        val arbiter = PermissionRequestArbiter()
        val launched = mutableListOf<String>()

        val first = async { arbiter.ensure("CAMERA", { false }, { launched += it }) }
        waitUntil("the dialog") { launched.size == 1 }

        arbiter.onResult(false)

        assertFalse(first.await())
    }

    @Test
    fun `a caller cancelled while queued never raises a dialog and does not block the next one`() = runBlocking {
        val arbiter = PermissionRequestArbiter()
        val launched = mutableListOf<String>()

        val first = async { arbiter.ensure("CAMERA", { false }, { launched += it }) }
        waitUntil("the first dialog") { launched.size == 1 }

        val queued = launch { arbiter.ensure("MICROPHONE", { false }, { launched += it }) }
        delay(20)
        queued.cancel()
        waitUntil("the queued caller to die") { queued.isCompleted }

        // Answering the live dialog must not raise the cancelled caller's dialog in its place.
        arbiter.onResult(true)
        assertTrue(first.await())
        delay(20)
        assertEquals(listOf("CAMERA"), launched)

        // ...and the arbiter is still usable.
        val third = async { arbiter.ensure("NOTIFICATIONS", { false }, { launched += it }) }
        waitUntil("a later dialog") { launched.size == 2 }
        arbiter.onResult(true)
        assertTrue(third.await())
        assertEquals(listOf("CAMERA", "NOTIFICATIONS"), launched)
    }

    @Test
    fun `a caller cancelled while its own dialog is up does not steal the next request`() = runBlocking {
        val arbiter = PermissionRequestArbiter()
        val launched = mutableListOf<String>()
        var answers = 0

        val cancelled = launch {
            arbiter.ensure("CAMERA", { false }, { launched += it })
            answers++
        }
        waitUntil("the dialog") { launched.size == 1 }

        cancelled.cancel()
        waitUntil("the cancelled caller to die") { cancelled.isCompleted }

        // The system still owns the dialog; its answer is consumed and nobody is resumed with it.
        arbiter.onResult(true)
        delay(20)
        assertEquals(0, answers)

        val next = async { arbiter.ensure("MICROPHONE", { false }, { launched += it }) }
        waitUntil("the next dialog") { launched.size == 2 }
        arbiter.onResult(true)
        assertTrue(next.await(), "the next caller got its own dialog and its own answer")
    }

    @Test
    fun `a permission granted meanwhile is answered without a second dialog`() = runBlocking {
        val arbiter = PermissionRequestArbiter()
        val launched = mutableListOf<String>()
        val granted = mutableSetOf<String>()

        val first = async { arbiter.ensure("CAMERA", { it in granted }, { launched += it }) }
        waitUntil("the dialog") { launched.size == 1 }
        val second = async { arbiter.ensure("CAMERA", { it in granted }, { launched += it }) }
        delay(20)

        granted += "CAMERA"
        arbiter.onResult(true)

        assertTrue(first.await())
        assertTrue(second.await(), "the queued duplicate is answered from the re-check")
        assertEquals(listOf("CAMERA"), launched, "the duplicate raised no second dialog")
    }

    @Test
    fun `an already-granted permission raises no dialog at all`() = runBlocking {
        val arbiter = PermissionRequestArbiter()
        val launched = mutableListOf<String>()

        assertTrue(arbiter.ensure("CAMERA", { true }, { launched += it }))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `a launcher that throws fails that caller and leaves the queue usable`() = runBlocking {
        val arbiter = PermissionRequestArbiter()
        val launched = mutableListOf<String>()

        val failed = async {
            runCatching { arbiter.ensure("CAMERA", { false }, { throw IllegalStateException("activity gone") }) }
        }
        val failure = failed.await()
        assertTrue(failure.isFailure, "the caller sees the launcher's failure instead of hanging")

        // A later request still gets its dialog rather than waiting behind a dead one.
        val next = async { arbiter.ensure("MICROPHONE", { false }, { launched += it }) }
        waitUntil("the next dialog") { launched.size == 1 }
        arbiter.onResult(true)
        assertTrue(next.await())
    }
}
