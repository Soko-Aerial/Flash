@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.planner

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.result.runSuspendCatching
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [ConnectionPlanner] on every host (PC2). It replaces the three sweep loops that used to live
 * in the app holder, `core:engine`'s `Flash.create` and the desktop engine.
 *
 * A sweep runs:
 * - every [sweepIntervalMs] (the retry path after a refused or timed-out dial);
 * - on every emission of the `edges` flow passed to [start], normally discovery's endpoint list.
 *   Pairing needs a session within 3 s of the tap, so a new peer is dialed when it appears, not up
 *   to a tick later (measured on the desktop, 2026-09-14);
 * - on [sweepNow] (screen-on, manual retry, network rejoin);
 * - when the planner reports a deferred first-contact dial falling due ([ConnectionPlanner.Plan.recheckInMs]).
 *
 * Sweeps run one at a time on one coroutine, and each dial runs in its own coroutine, so one
 * unreachable peer cannot delay the others. While [quiet] is true (Android: a call owns the radio)
 * sweeps are skipped but the loop keeps running, so dialing resumes on the next wake after it clears.
 *
 * Log lines keep the exact text the hosts printed before PC2: `tools/pc0/phone-baseline.ps1`
 * counts `Auto-connect dialing peer=`, `Auto-connect result peer=… success=true` and
 * `Auto-connect dialing gateway`.
 */
public class AutoConnector(
    private val scope: CoroutineScope,
    private val planner: ConnectionPlanner,
    private val links: ConnectionPlanner.Links,
    private val sightings: () -> List<ConnectionPlanner.Sighting>,
    private val dial: suspend (ConnectionPlanner.Dial) -> FlashResult<*>,
    private val gatewayHosts: () -> List<String> = { emptyList() },
    private val quiet: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
    private val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    /** Monotonic milliseconds for the planner; injectable so tests can run on virtual time. */
    private val nowMs: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } },
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Starts the sweep loop; cancel the returned job (or [scope]) to stop it. */
    public fun start(edges: Flow<*>? = null): Job = scope.launch {
        if (edges != null) launch { edges.collect { wake.trySend(Unit) } }
        while (isActive) {
            val recheck = sweep()
            val wait = recheck?.coerceIn(1L, sweepIntervalMs) ?: sweepIntervalMs
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** Asks for a sweep now instead of at the next tick. Cheap and safe from any thread. */
    public fun sweepNow() {
        wake.trySend(Unit)
    }

    private fun sweep(): Long? {
        if (quiet()) return null
        val plan = runCatching {
            planner.plan(
                nowMs = nowMs(),
                sightings = sightings(),
                links = links,
                gatewayHosts = runCatching { gatewayHosts() }.getOrDefault(emptyList()),
            )
        }.getOrElse { t ->
            log("Auto-connect sweep failed: ${t.message}")
            return null
        }
        plan.dials.forEach { d -> scope.launch { runDial(d) } }
        return plan.recheckInMs
    }

    private suspend fun runDial(d: ConnectionPlanner.Dial) {
        log(
            if (d.isGatewayProbe) "Auto-connect dialing gateway at ${d.host}:${d.port} (hotspot host probe)"
            else "Auto-connect dialing peer=${d.name} id=${d.key} at ${d.host}:${d.port}",
        )
        // try/finally, not runCatching alone: the planner entry must always be released, and a
        // cancelled sweep must actually stop (audit B3).
        try {
            val result = runSuspendCatching { dial(d) }
            val outcome = result.getOrNull()
            val ok = outcome is FlashResult.Success
            val detail = when {
                ok -> ""
                outcome != null -> " detail=$outcome"
                else -> " detail=threw ${result.exceptionOrNull()?.message}"
            }
            log(
                if (d.isGatewayProbe) "Auto-connect result gateway ${d.host} success=$ok$detail"
                else "Auto-connect result peer=${d.key} success=$ok$detail",
            )
        } finally {
            planner.dialFinished(d.key)
        }
    }

    public companion object {
        /** The sweep cadence all three hosts used before PC2. */
        public const val DEFAULT_SWEEP_INTERVAL_MS: Long = 5_000L
    }
}
