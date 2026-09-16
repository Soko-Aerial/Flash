package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.time.SystemTimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the presence UI is allowed to claim about a peer, split into the two honest states.
 *
 * [online] is the live session set exactly as the transport reports it. [connecting] is the peers
 * whose session has just gone but whose reconnect grace has not expired yet — recovery is plausibly
 * under way, so the UI says "Connecting…" instead of either lying about a usable link or flashing
 * Offline through a sub-second session swap. The two sets are disjoint.
 */
internal data class PresenceSnapshot(
    val online: Set<String>,
    val connecting: Set<String>,
)

/**
 * Splits a live-session set into [PresenceSnapshot.online] / [PresenceSnapshot.connecting] with a
 * per-departure grace period of [holdMs].
 *
 * Rising edge is instant. On a falling edge the peer moves to `connecting` and a **hard deadline**
 * is stamped for it: `holdMs` after *that departure*, it becomes fully absent (in neither set)
 * whether or not anything else happened in between.
 *
 * ## Why this is not `transformLatest { … delay(holdMs) }` (ERROR-031)
 *
 * The previous implementation held the falling edge inside `transformLatest`, which cancels its
 * block on every upstream emission. A peer reconnecting every 1–4 s therefore cancelled the pending
 * Offline verdict before it could ever fire, and — because the same body emitted `shown + live`
 * first — the dot stayed **Online with an empty session set**, indefinitely. That is the "peer shows
 * Online while its app is not even running" report: the header promised a link the transport could
 * not deliver, so a send single-ticked into a socket that no longer existed.
 *
 * Here the timer is a *sibling* of the collector rather than its child, so no upstream traffic can
 * cancel it, and the deadline is stamped with `putIfAbsent` — a peer that keeps reappearing cannot
 * refresh its own deadline. A peer that is genuinely gone therefore reaches "absent" exactly
 * [holdMs] after its last departure, and while it is held it is reported *Connecting*, never Online.
 */
internal fun Flow<Set<String>>.withReconnectGrace(holdMs: Long): Flow<PresenceSnapshot> {
    // distinctUntilChanged on the UPSTREAM keeps a chatty source (a repeated identical set) from
    // re-walking the hold map on every duplicate; it is no longer load-bearing for correctness.
    val upstream = distinctUntilChanged()
    return channelFlow {
        // Guards the three pieces of mutable state below, which the collector and every pending
        // grace timer touch concurrently. `send` happens under the same lock so two publishers can
        // never invert the order of their snapshots downstream.
        val guard = Mutex()
        val graceStartedAt = HashMap<String, Long>()
        var live: Set<String> = emptySet()
        var published: PresenceSnapshot? = null

        suspend fun publish() {
            guard.withLock {
                // Common code cannot read the clock directly; the shared seam (Phase 06) instead.
                val now = SystemTimeSource.nowMs()
                // A grace ends when the peer is back (it is Online again) or when its own deadline
                // passes — never because some other peer's event arrived.
                graceStartedAt.entries.removeAll { (id, since) -> id in live || now - since >= holdMs }
                val next = PresenceSnapshot(online = live, connecting = graceStartedAt.keys.toSet())
                if (next != published) {
                    published = next
                    send(next)
                }
            }
        }

            upstream.collect { sessions ->
            val departed = guard.withLock {
                val gone = live - sessions
                live = sessions
                val now = SystemTimeSource.nowMs()
                // putIfAbsent, not put: a peer that flaps must not be able to push its own deadline
                // into the future on every bounce.
                gone.forEach { graceStartedAt.putIfAbsent(it, now) }
                gone.isNotEmpty()
            }
            publish()
            if (departed) {
                // One timer per departure event, launched as a sibling of this collector. Nothing
                // upstream can cancel it; the worst case is a redundant `publish()` that emits
                // nothing because the peer came back.
                launch {
                    delay(holdMs)
                    publish()
                }
            }
        }
    }
}
