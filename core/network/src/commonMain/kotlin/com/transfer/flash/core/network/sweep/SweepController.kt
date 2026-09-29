package com.transfer.flash.core.network.sweep

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where a sweep stands. The Nearby screen renders this; it never shows hosts or ports. */
public sealed interface SweepState {
    public data object Idle : SweepState

    public data class Scanning(val scanned: Int, val total: Int, val automatic: Boolean) : SweepState

    /**
     * @property probed how many addresses were probed.
     * @property answered how many of them accepted a connection. They are already being dialed.
     * @property narrowed the network was wider than a /24 and only this device's /24 was covered.
     */
    public data class Finished(
        val probed: Int,
        val answered: Int,
        val narrowed: Boolean,
        val automatic: Boolean,
    ) : SweepState

    /** Nothing was swept, and why. */
    public data class Refused(val refusal: SweepRefusal) : SweepState
}

/**
 * Finds peers on a network that hides them from discovery (DR3, `docs/network/DISCOVERY-RESILIENCE-PLAN.md`
 * §3.3 C): a TCP connect probe on every host of the local subnet. Hosts that answer become dial hints for the
 * connection planner, which does the ordinary TLS and HELLO dial. The sweep never identifies or trusts
 * anything itself, exactly like an mDNS answer.
 *
 * Limits, all enforced in [SubnetSweepPlan] and [SweepPolicy]: RFC 1918 only, /24 or smaller (a wider network
 * is swept only when the user asks, and then only this device's /24), Wi-Fi/Ethernet/hotspot interfaces only
 * (the platform [subnets] source never lists cellular), at most [DEFAULT_CONCURRENCY] probes in flight with a
 * [DEFAULT_TIMEOUT_MS] timeout, one sweep at a time, and rate limits for both triggers.
 *
 * Triggers: [scanNow] (the user's "Scan network"), and the automatic fallback started by [start] when
 * [SweepPolicy] finds paired peers but nothing reachable for 60 s.
 *
 * A hit is one-shot: [hostsToDial] lists it for [HIT_TTL_MS], and the host reports [hitDialed] after the dial,
 * so a host that opens the port but is not a Flash device is dialed once per sweep, not forever.
 *
 * @param subnets LAN-capable IPv4 addresses of this device right now.
 * @param port the peer server port to probe.
 * @param situation the current pairing/session/discovery counts, for the automatic trigger.
 * @param autoEnabled owner decision D2 as one switch: false keeps the sweep manual-only.
 */
public class SweepController(
    private val scope: CoroutineScope,
    private val subnets: () -> List<LocalSubnet>,
    probe: HostProbe,
    private val port: Int,
    private val situation: () -> SweepSituation,
    private val policy: SweepPolicy = SweepPolicy(),
    private val autoEnabled: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } },
    concurrency: Int = DEFAULT_CONCURRENCY,
    timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    private val sweeper = SubnetSweeper(probe, concurrency, timeoutMs)
    private val running = MutableStateFlow(false)
    private val mutableState = MutableStateFlow<SweepState>(SweepState.Idle)

    /** Hosts that answered, with when. Emits when the set changes, so the planner can be woken. */
    private val hits = MutableStateFlow<Map<String, Long>>(emptyMap())

    public val state: StateFlow<SweepState> = mutableState.asStateFlow()

    /** Changes whenever a hit is added or consumed; use it as a planner wake-up edge. */
    public val hitsChanged: StateFlow<Map<String, Long>> = hits.asStateFlow()

    /** Hosts that answered a recent sweep and have not been dialed yet. */
    public fun hostsToDial(): List<String> {
        val now = nowMs()
        return hits.value.filterValues { foundAt -> now - foundAt < HIT_TTL_MS }.keys.toList()
    }

    /** The dial for [host] has ended, whatever its outcome. */
    public fun hitDialed(host: String) {
        hits.update { it - host }
    }

    /**
     * The user's "Scan network". Returns false when nothing started: a sweep is already running, the previous one
     * ended a moment ago, or no subnet may be swept. [state] says which.
     */
    public fun scanNow(): Boolean {
        if (!policy.manualAllowed(nowMs())) {
            mutableState.value = SweepState.Refused(SweepRefusal.RATE_LIMITED)
            return false
        }
        if (!running.compareAndSet(false, true)) return false
        scope.launch { runSweep(manual = true) }
        return true
    }

    /**
     * Starts the automatic fallback: every [checkIntervalMs] it asks [SweepPolicy] whether a sweep is due.
     * Cancel the returned job to stop it.
     */
    public fun start(checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS): Job = scope.launch {
        while (isActive) {
            delay(checkIntervalMs)
            val current = runCatching { situation() }.getOrNull() ?: continue
            val enabled = autoEnabled()
            // Observe even when disabled, so re-enabling starts the quiet period from the truth.
            val due = policy.autoDue(nowMs(), if (enabled) current else current.copy(connectionAllowsAuto = false))
            if (due && enabled && running.compareAndSet(false, true)) runSweep(manual = false)
        }
    }

    /** Runs one sweep. The caller has set [running]; this always clears it. */
    private suspend fun runSweep(manual: Boolean) {
        try {
            val all = runCatching { subnets() }.getOrDefault(emptyList()).take(MAX_SUBNETS)
            val now = nowMs()
            val targets = LinkedHashSet<String>()
            val swept = ArrayList<String>()
            var refusal: SweepRefusal? = null
            var narrowed = false
            for (subnet in all) {
                val key = SubnetSweepPlan.networkKey(subnet)
                if (!manual && key != null && !policy.autoAllowedFor(now, key)) continue
                when (val outcome = SubnetSweepPlan.plan(subnet, manual)) {
                    is SubnetSweepPlan.Outcome.Targets -> {
                        targets += outcome.hosts
                        narrowed = narrowed || outcome.narrowed
                        key?.let(swept::add)
                    }
                    is SubnetSweepPlan.Outcome.Refused -> {
                        refusal = refusal ?: outcome.refusal
                        // Recorded so an automatic check does not re-log the same refusal every interval.
                        if (!manual) key?.let(swept::add)
                        log("Sweep skipped ${subnet.interfaceName} ${subnet.address}/${subnet.prefixLength}: ${outcome.refusal}")
                    }
                }
            }
            if (targets.isEmpty()) {
                if (manual || swept.isNotEmpty()) policy.recordSweep(now, swept)
                if (manual) mutableState.value = SweepState.Refused(refusal ?: SweepRefusal.NO_LAN)
                return
            }

            log("Sweep started hosts=${targets.size} networks=${swept.joinToString()} manual=$manual narrowed=$narrowed")
            mutableState.value = SweepState.Scanning(0, targets.size, automatic = !manual)
            val answered = try {
                sweeper.sweep(targets.toList(), port) { scanned ->
                    mutableState.value = SweepState.Scanning(scanned, targets.size, automatic = !manual)
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = SweepState.Idle
                throw cancelled
            }

            val foundAt = nowMs()
            hits.update { known -> known.filterValues { foundAt - it < HIT_TTL_MS } + answered.associateWith { foundAt } }
            policy.recordSweep(foundAt, swept)
            mutableState.value = SweepState.Finished(targets.size, answered.size, narrowed, automatic = !manual)
            log("Sweep finished probed=${targets.size} answered=${answered.size} hosts=${answered.joinToString()}")
        } finally {
            running.value = false
        }
    }

    public companion object {
        /** Plan §3.3 C. */
        public const val DEFAULT_CONCURRENCY: Int = 32

        /** Plan §3.3 C. A LAN host answers a SYN in single-digit milliseconds; this only bounds silent hosts. */
        public const val DEFAULT_TIMEOUT_MS: Int = 300

        /** How long a hit stays a dial hint. */
        public const val HIT_TTL_MS: Long = 60_000L

        public const val DEFAULT_CHECK_INTERVAL_MS: Long = 10_000L

        /** A phone on Wi-Fi that also hosts a hotspot has two; more than this is a misreport. */
        public const val MAX_SUBNETS: Int = 4
    }
}
