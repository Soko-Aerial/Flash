package com.transfer.flash.core.network.sweep

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * What the host tells the sweep about the moment (DR3). Built by the host each time it is asked, so the
 * controller holds no reference to trust, sessions or discovery.
 *
 * @property pairedPeers how many paired peers this device has.
 * @property liveSessions sessions that currently carry traffic, inbound or outbound.
 * @property discoveredPeers devices discovery lists right now, on any source.
 * @property connectionAllowsAuto false in ECO (the battery mode never sweeps by itself) and while a
 *   call owns the radio.
 */
public data class SweepSituation(
    val pairedPeers: Int,
    val liveSessions: Int,
    val discoveredPeers: Int,
    val connectionAllowsAuto: Boolean,
)

/**
 * When a sweep may start (DR3, plan §3.3 C).
 *
 * **Automatic** (owner decision D2, one switch in the host): only when the device has paired peers and
 * nothing at all has been seen (no live session, no discovered device) for [quietBeforeAutoMs], and
 * then at most once per network per [autoIntervalMs]. A network that was refused is recorded too, so a
 * /16 does not re-log its refusal every check.
 *
 * **Manual** ("Scan network"): any time, but not again within [manualCooldownMs] of the previous sweep
 * ending, so a held-down button cannot turn into a port scanner.
 *
 * Thread-safe: the state is immutable and replaced by compare-and-set (the module has no locks in
 * common code, same reason as `ConnectionPlanner`).
 */
public class SweepPolicy(
    private val quietBeforeAutoMs: Long = DEFAULT_QUIET_BEFORE_AUTO_MS,
    private val autoIntervalMs: Long = DEFAULT_AUTO_INTERVAL_MS,
    private val manualCooldownMs: Long = DEFAULT_MANUAL_COOLDOWN_MS,
) {
    private data class State(
        /** Since when the "paired peers exist but nothing is reachable" condition has held. */
        val idleSinceMs: Long? = null,
        val lastSweepMsByNetwork: Map<String, Long> = emptyMap(),
        val lastFinishedMs: Long? = null,
    )

    private val state = MutableStateFlow(State())

    /**
     * Feeds one observation and answers whether an automatic sweep is due now. Call it on a steady
     * cadence: the quiet period is measured between calls.
     */
    public fun autoDue(nowMs: Long, situation: SweepSituation): Boolean {
        val eligible = situation.connectionAllowsAuto &&
            situation.pairedPeers > 0 &&
            situation.liveSessions == 0 &&
            situation.discoveredPeers == 0
        val updated = state.updateAndGet { s -> s.copy(idleSinceMs = if (eligible) s.idleSinceMs ?: nowMs else null) }
        val since = updated.idleSinceMs ?: return false
        return nowMs - since >= quietBeforeAutoMs
    }

    /** Whether the automatic sweep may cover [networkKey] again. */
    public fun autoAllowedFor(nowMs: Long, networkKey: String): Boolean {
        val last = state.value.lastSweepMsByNetwork[networkKey] ?: return true
        return nowMs - last >= autoIntervalMs
    }

    /** Whether the user's "Scan network" may run now. */
    public fun manualAllowed(nowMs: Long): Boolean {
        val finished = state.value.lastFinishedMs ?: return true
        return nowMs - finished >= manualCooldownMs
    }

    /** Records that [networkKeys] were swept (or refused) at [nowMs], and that a sweep just ended. */
    public fun recordSweep(nowMs: Long, networkKeys: Collection<String>) {
        while (true) {
            val current = state.value
            val next = current.copy(
                lastSweepMsByNetwork = current.lastSweepMsByNetwork + networkKeys.associateWith { nowMs },
                lastFinishedMs = nowMs,
            )
            if (state.compareAndSet(current, next)) return
        }
    }

    public companion object {
        /** Plan §3.3 C: nothing seen for 60 s before the automatic fallback runs. */
        public const val DEFAULT_QUIET_BEFORE_AUTO_MS: Long = 60_000L

        /** Plan §3.3 C: at most once per network per 10 minutes. */
        public const val DEFAULT_AUTO_INTERVAL_MS: Long = 10 * 60_000L

        public const val DEFAULT_MANUAL_COOLDOWN_MS: Long = 5_000L
    }
}
