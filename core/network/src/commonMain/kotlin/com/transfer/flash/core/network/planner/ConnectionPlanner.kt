package com.transfer.flash.core.network.planner

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Decides which peers to dial (PC2, `docs/network/PRESENCE-CONNECTIONS-PLAN.md` §3.5–3.6, ADR-045).
 *
 * Before PC2 this decision existed three times: the app holder's sweep with the app's
 * `AutoConnectGate`, `core:engine`'s `Flash.create` sweep with a ported copy of that gate, and the
 * desktop's `dialIfNeeded`. The copies had drifted. The desktop had no suppression window, so an
 * unreachable peer was redialed every 5 s. It also counted a stale session as connected, which is
 * the ERROR-031 trap the other two had already fixed. This class is the single copy, and it has no
 * platform types, so every rule is unit-tested in `commonTest`.
 *
 * ## Rules (STANDARD behaviour; PC5 adds the ECO/BOOST rules of §3.4)
 * 1. **Never dial yourself**, and dial each device once per sweep even when discovery lists it on
 *    several transports.
 * 2. **A live session clears the peer.** "Live" is the network's freshness check
 *    (`hasLiveSession`, ERROR-031), not mere presence in the registry. Clearing means a later drop
 *    re-arms at once instead of waiting out an old suppression window.
 * 3. **The reconnect engine goes first.** No dial while its backoff loop is redialing that peer,
 *    because two outbound dials to one peer only widen the glare window (ERROR-023).
 * 4. **One attempt per [suppressMs] per peer, never two at once**, until [dialFinished]. This is
 *    the old `AutoConnectGate`, unchanged.
 * 5. **Deterministic first dialer (new in PC2).** When a peer with no session first becomes
 *    dialable, the side with the lower device id dials at once and the higher id waits
 *    [firstContactDeferMs]. Usually the lower id's session lands in that time, so the higher id
 *    never dials and the pair does not glare. The wait is short on purpose: pairing gives up after
 *    3 s without the peer's HELLO (`FINGERPRINT_WAIT_MS`), and when discovery is one-sided (an
 *    Android hotspot drops mDNS) the higher id is the only side that can dial. The wait applies
 *    once per no-session episode. Later retries follow rule 4 only.
 * 6. **Gateway probes** (the Android hotspot-host probe) have no device id. They are keyed
 *    `gateway:<host>`, skipped while any session reaches that host, gated by rule 4, and never
 *    deferred.
 * 7. **Dial on demand (PC3, [planUrgent]).** A send to a peer that is seen but has no session skips
 *    rule 5's wait and rule 4's 15 s window. It still respects rules 2 and 3, one attempt in flight,
 *    and a [URGENT_FLOOR_MS] floor since the last attempt of any kind, so a burst of queued
 *    messages to an unreachable peer costs one dial, not one per message.
 * 8. **Mode filter (PC5).** [plan]'s `allowed` set, when given, limits which sighted peers may be
 *    dialed (ECO's `ConnectionModeController.dialFilter`). Peers outside it are treated as if
 *    discovery did not list them. Gateway probes and [planUrgent] ignore it.
 * 9. **Sweep hits (DR3, [plan]'s `sweepHosts`).** A host that answered the subnet sweep has no device id
 *    until its HELLO arrives, so it is handled like a gateway probe: keyed `sweep:<host>`, skipped while
 *    any session reaches that host, gated by rule 4, never deferred, and outside rule 8's filter (a sweep
 *    is either the user's own action or the automatic fallback, which STANDARD and BOOST allow).
 *
 * Thread-safe without a lock. The state is immutable and replaced by compare-and-set, because
 * `core:network` deliberately declares no expect/actual classes (see its `build.gradle.kts`).
 * [Links] are queried once per [plan], outside the retry loop, so a retry reuses the same answers.
 */
public class ConnectionPlanner(
    private val localDeviceId: String,
    private val suppressMs: Long = DEFAULT_SUPPRESS_MS,
    private val firstContactDeferMs: Long = DEFAULT_FIRST_CONTACT_DEFER_MS,
) {
    /** A peer discovery currently reports, reduced to what a dial needs. */
    public data class Sighting(
        val deviceId: String,
        val host: String,
        val port: Int,
        val name: String,
    )

    /** What the planner needs to know about the network's current sessions. */
    public interface Links {
        /** A registered session that has carried traffic recently (ERROR-031). */
        public fun hasLiveSession(deviceId: String): Boolean

        /** The network's own backoff loop is redialing [deviceId] right now. */
        public fun isReconnectInFlight(deviceId: String): Boolean

        /** Some registered session's remembered endpoint is [host]; for gateway probes only. */
        public fun hasSessionAtHost(host: String): Boolean = false
    }

    /**
     * One dial to start now. The caller must pass [key] to [dialFinished] when the attempt ends,
     * however it ends.
     */
    public data class Dial(
        val key: String,
        val host: String,
        val port: Int,
        /** Null for a gateway probe or sweep hit, whose device is unknown until its HELLO arrives. */
        val peerDeviceId: String?,
        val name: String,
    ) {
        public val isGatewayProbe: Boolean get() = key.startsWith(GATEWAY_KEY_PREFIX)

        /** A host the subnet sweep found open (DR3); its device is unknown until HELLO, like a gateway's. */
        public val isSweepHit: Boolean get() = key.startsWith(SWEEP_KEY_PREFIX)
    }

    /**
     * @property recheckInMs when a deferred first-contact dial becomes due. Null when nothing is
     *   waiting, so the caller's normal sweep cadence applies.
     */
    public data class Plan(val dials: List<Dial>, val recheckInMs: Long?)

    private data class State(
        val lastAttemptMs: Map<String, Long> = emptyMap(),
        val inFlight: Set<String> = emptySet(),
        /** When each peer was first seen dialable in its current no-session episode (rule 5). */
        val dialableSinceMs: Map<String, Long> = emptyMap(),
    )

    private class Candidate(
        val dial: Dial,
        val live: Boolean,
        val reconnecting: Boolean,
        val deferred: Boolean,
    )

    private val state = MutableStateFlow(State())

    /**
     * Returns the dials to start at [nowMs] and records them as in flight.
     *
     * @param nowMs any monotonic millisecond clock, used consistently across calls.
     * @param gatewayHosts IPv4 gateways to probe (Android hotspot clients); empty elsewhere.
     * @param sweepHosts hosts the subnet sweep found listening (DR3); rule 9.
     * @param allowed rule 8: the only device ids that may be dialed, or null for all.
     */
    public fun plan(
        nowMs: Long,
        sightings: List<Sighting>,
        links: Links,
        gatewayHosts: List<String> = emptyList(),
        allowed: Set<String>? = null,
        sweepHosts: List<String> = emptyList(),
    ): Plan {
        val candidates = ArrayList<Candidate>(sightings.size + gatewayHosts.size + sweepHosts.size)
        val seen = HashSet<String>()
        for (s in sightings) {
            if (s.deviceId == localDeviceId || (allowed != null && s.deviceId !in allowed)) continue
            if (!seen.add(s.deviceId)) continue
            candidates += Candidate(
                dial = Dial(key = s.deviceId, host = s.host, port = s.port, peerDeviceId = s.deviceId, name = s.name),
                live = links.hasLiveSession(s.deviceId),
                reconnecting = links.isReconnectInFlight(s.deviceId),
                deferred = localDeviceId > s.deviceId,
            )
        }
        for (host in gatewayHosts) {
            val key = GATEWAY_KEY_PREFIX + host
            if (!seen.add(key)) continue
            candidates += Candidate(
                dial = Dial(key = key, host = host, port = 0, peerDeviceId = null, name = "gateway"),
                live = links.hasSessionAtHost(host),
                reconnecting = links.isReconnectInFlight(key),
                deferred = false,
            )
        }
        for (host in sweepHosts) {
            val key = SWEEP_KEY_PREFIX + host
            if (!seen.add(key)) continue
            candidates += Candidate(
                dial = Dial(key = key, host = host, port = 0, peerDeviceId = null, name = "sweep"),
                live = links.hasSessionAtHost(host),
                reconnecting = links.isReconnectInFlight(key),
                deferred = false,
            )
        }

        while (true) {
            val current = state.value
            val (next, plan) = step(current, nowMs, candidates)
            if (state.compareAndSet(current, next)) return plan
        }
    }

    /**
     * Rule 7: a dial for [sighting] right now because something is waiting to be sent, or null when
     * none should start (a live session, a dial already in flight, or the floor not yet passed).
     */
    public fun planUrgent(nowMs: Long, sighting: Sighting, links: Links, floorMs: Long = URGENT_FLOOR_MS): Dial? {
        if (sighting.deviceId == localDeviceId) return null
        val key = sighting.deviceId
        val live = links.hasLiveSession(key)
        val reconnecting = links.isReconnectInFlight(key)
        while (true) {
            val current = state.value
            val next: State
            val dial: Dial?
            when {
                live -> {
                    next = current.copy(
                        lastAttemptMs = current.lastAttemptMs - key,
                        inFlight = current.inFlight - key,
                        dialableSinceMs = current.dialableSinceMs - key,
                    )
                    dial = null
                }
                reconnecting || key in current.inFlight -> return null
                current.lastAttemptMs[key]?.let { nowMs - it < floorMs } == true -> return null
                else -> {
                    next = current.copy(
                        lastAttemptMs = current.lastAttemptMs + (key to nowMs),
                        inFlight = current.inFlight + key,
                    )
                    dial = Dial(key = key, host = sighting.host, port = sighting.port, peerDeviceId = key, name = sighting.name)
                }
            }
            if (state.compareAndSet(current, next)) return dial
        }
    }

    /** Ends the in-flight attempt for [key]; the [suppressMs] window still applies. */
    public fun dialFinished(key: String) {
        while (true) {
            val current = state.value
            if (key !in current.inFlight) return
            if (state.compareAndSet(current, current.copy(inFlight = current.inFlight - key))) return
        }
    }

    private fun step(s: State, now: Long, candidates: List<Candidate>): Pair<State, Plan> {
        val last = s.lastAttemptMs.toMutableMap()
        val inFlight = s.inFlight.toMutableSet()
        val since = s.dialableSinceMs.toMutableMap()
        val dials = ArrayList<Dial>()
        var recheck: Long? = null

        for (c in candidates) {
            val key = c.dial.key
            if (c.live) {
                last.remove(key)
                inFlight.remove(key)
                since.remove(key)
                continue
            }
            if (c.reconnecting || key in inFlight) continue
            val previous = last[key]
            if (previous != null && now - previous < suppressMs) continue
            if (c.deferred) {
                val start = since.getOrPut(key) { now }
                val remaining = start + firstContactDeferMs - now
                if (remaining > 0) {
                    recheck = minOf(recheck ?: remaining, remaining)
                    continue
                }
            }
            last[key] = now
            inFlight += key
            dials += c.dial
        }

        // Forget peers discovery no longer reports once their suppression has run out. A peer that
        // flickers out of discovery and back inside the window stays suppressed.
        val present = candidates.mapTo(HashSet()) { it.dial.key }
        last.entries.removeAll { (key, at) -> key !in present && key !in inFlight && now - at >= suppressMs }
        since.keys.retainAll(present)

        return State(last, inFlight, since) to Plan(dials, recheck)
    }

    public companion object {
        /** The old `AutoConnectGate` window: at most one auto-dial per peer per 15 s. */
        public const val DEFAULT_SUPPRESS_MS: Long = 15_000L

        /** Rule 5's wait for the higher id. Must stay well under pairing's 3 s HELLO wait. */
        public const val DEFAULT_FIRST_CONTACT_DEFER_MS: Long = 1_500L

        public const val GATEWAY_KEY_PREFIX: String = "gateway:"

        public const val SWEEP_KEY_PREFIX: String = "sweep:"

        /** Rule 7's floor between attempts to one peer when a send is waiting. */
        public const val URGENT_FLOOR_MS: Long = 5_000L
    }
}
