package com.transfer.flash.core.messaging

import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presence grace-window logic (ERROR-031). The field symptom these guard is a peer reported
 * **Online while its app was not running**: a send then single-ticked into a socket that no longer
 * existed and never arrived.
 */
class PresenceHoldTest {

    @Test
    fun `a departing peer is Connecting for the grace window and absent after it`() = runBlocking {
        val sessions = MutableStateFlow(setOf("peer"))
        val seen = Collections.synchronizedList(mutableListOf<PresenceSnapshot>())
        val collector = launch { sessions.withReconnectGrace(HOLD_MS).collect { seen.add(it) } }

        // Rising edge is instant.
        awaitUntil(seen) { it.online == setOf("peer") && it.connecting.isEmpty() }

        val departedAt = System.currentTimeMillis()
        sessions.value = emptySet()

        // Falling edge is instant too — but into Connecting, not Online and not Offline.
        awaitUntil(seen) { it.online.isEmpty() && it.connecting == setOf("peer") }

        // …and the peer only becomes fully absent once its own deadline passes.
        awaitUntil(seen) { it.online.isEmpty() && it.connecting.isEmpty() }
        val absentAfterMs = System.currentTimeMillis() - departedAt
        assertTrue("went absent after only ${absentAfterMs}ms", absentAfterMs >= HOLD_MS - 50)

        collector.cancel()
    }

    @Test
    fun `an absent peer is never reported Online no matter how often it reappears`() = runBlocking {
        val sessions = MutableStateFlow(setOf("peer"))
        val seen = Collections.synchronizedList(mutableListOf<PresenceSnapshot>())
        val collector = launch { sessions.withReconnectGrace(HOLD_MS).collect { seen.add(it) } }
        awaitUntil(seen) { it.online == setOf("peer") }

        // Five reconnect cycles, each far shorter than the grace window — the exact shape of a
        // Doze/screen-off flap. The old hold lived inside transformLatest and re-emitted
        // `shown + live` on every upstream set, so the peer was published as ONLINE throughout
        // these gaps while the session set was empty, and the pending Offline verdict was
        // cancelled before it could ever fire.
        val flapStartedAt = System.currentTimeMillis()
        repeat(5) {
            sessions.value = emptySet()
            awaitUntil(seen) { it.online.isEmpty() && it.connecting == setOf("peer") }
            delay(40)
            sessions.value = setOf("peer")
            awaitUntil(seen) { it.online == setOf("peer") && it.connecting.isEmpty() }
            delay(40)
        }
        val flappedForMs = System.currentTimeMillis() - flapStartedAt
        assertTrue("flap ($flappedForMs ms) must outlast the grace window", flappedForMs > HOLD_MS)

        // The peer finally stays away: it goes absent on its own deadline, measured from the LAST
        // departure and nothing else.
        sessions.value = emptySet()
        awaitUntil(seen) { it.online.isEmpty() && it.connecting.isEmpty() }

        // No snapshot ever claimed a peer was Online and reconnecting at the same time.
        synchronized(seen) {
            seen.forEach { assertTrue("states must be disjoint: $it", (it.online intersect it.connecting).isEmpty()) }
        }

        collector.cancel()
    }

    @Test
    fun `one peer's churn cannot postpone another peer's Offline verdict`() = runBlocking {
        val sessions = MutableStateFlow(setOf("quiet", "chatty"))
        val seen = Collections.synchronizedList(mutableListOf<PresenceSnapshot>())
        val collector = launch { sessions.withReconnectGrace(HOLD_MS).collect { seen.add(it) } }
        awaitUntil(seen) { it.online == setOf("quiet", "chatty") }

        // "quiet" leaves once and never returns. "chatty" then reconnects continuously for longer
        // than the grace window: under the transformLatest hold every one of those emissions
        // cancelled the shared delay, so ONE flapping peer kept EVERY departed peer latched Online.
        val departedAt = System.currentTimeMillis()
        sessions.value = setOf("chatty")

        val churnUntil = departedAt + HOLD_MS * 2
        while (System.currentTimeMillis() < churnUntil) {
            sessions.value = emptySet()
            delay(30)
            sessions.value = setOf("chatty")
            delay(30)
        }

        val latest = seen.last()
        assertEquals("quiet must not be Online", false, "quiet" in latest.online)
        assertEquals("quiet's grace must have expired", false, "quiet" in latest.connecting)

        collector.cancel()
    }

    /** Polls the collected snapshots until the newest one satisfies [condition]. */
    private suspend fun awaitUntil(
        seen: List<PresenceSnapshot>,
        timeoutMs: Long = 3_000,
        condition: (PresenceSnapshot) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (seen.lastOrNull()?.let(condition) == true) return
            delay(10)
        }
        throw AssertionError("condition never met; last snapshot was ${seen.lastOrNull()}")
    }

    private companion object {
        /** Short stand-in for `RealFlashChatRepository.OFFLINE_HOLD_MS` so the flaps run in ms. */
        const val HOLD_MS = 300L
    }
}
