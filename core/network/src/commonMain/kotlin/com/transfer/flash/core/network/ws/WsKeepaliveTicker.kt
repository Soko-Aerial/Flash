package com.transfer.flash.core.network.ws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One keepalive clock for every connection of a network (PC1, `PRESENCE-CONNECTIONS-PLAN.md` §3.3).
 *
 * Before PC1 each [WsConnection] ran its own `delay(pingInterval)` loop, started whenever that
 * connection happened to open, so eight idle sessions woke the CPU and the radio eight times per
 * interval at unrelated moments. Here they are all serviced on the same tick: one wake-up per
 * interval, whatever the session count.
 *
 * - **No sessions, no timer.** The loop parks on [targets] while it is empty, so a phone that holds
 *   no sessions schedules nothing.
 * - **The interval is read per tick** ([intervalMs]), so a tier pinned in Settings reaches the
 *   clock without a restart. A connection keeps judging stalls against the interval it was created
 *   with; tiers differ by at most 1.5× (10–15 s), inside `WsKeepalive.STALL_FACTOR`, so a changed
 *   clock never looks like a frozen process. PC5 re-times live connections when modes change it.
 * - **A tick never waits on a connection.** Targets hand the work to their own scope
 *   ([WsConnection] launches it and skips a tick that is still running), so one socket blocked on a
 *   full send buffer cannot delay the others.
 *
 * Owns its scope rather than borrowing the network's: `WsFlashNetwork.stop` cancels the children of
 * its own scope, and the ticker must survive a stop/start of the same instance.
 */
public class WsKeepaliveTicker(
    private val intervalMs: () -> Long,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** One connection's side of the clock. Must return quickly; see the class KDoc. */
    public fun interface Target {
        public fun onKeepaliveTick()
    }

    private val targets = MutableStateFlow<Set<Target>>(emptySet())

    /** Ticks delivered so far, for tests. */
    internal var ticks: Long = 0L
        private set

    private val loop: Job = scope.launch {
        while (isActive) {
            // Park, with no timer armed, until at least one connection is registered.
            targets.first { it.isNotEmpty() }
            delay(intervalMs())
            val current = targets.value
            if (current.isEmpty()) continue
            ticks++
            current.forEach { target ->
                runCatching { target.onKeepaliveTick() }
            }
        }
    }

    public fun register(target: Target) {
        targets.update { it + target }
    }

    public fun unregister(target: Target) {
        targets.update { it - target }
    }

    /** Number of registered connections, for tests and diagnostics. */
    public val size: Int get() = targets.value.size

    /** Stops the clock for good. Registered connections are not closed; they stop receiving ticks. */
    public fun close() {
        loop.cancel()
    }
}
