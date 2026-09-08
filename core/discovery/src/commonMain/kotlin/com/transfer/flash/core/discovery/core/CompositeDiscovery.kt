package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.result.onSuccess
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.concurrent.PlatformLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Multi-radio discovery facade (plan C3.9). Implements the existing
 * [FlashDiscovery] interface on top of N [FlashRadioTransport]s.
 *
 * Architecture (research-backed, see logs/experiments.md EXP entries + plan C3):
 *
 * **Event log + snapshot pattern.** All transports' events are merged into one
 * [mergedEvents] [SharedFlow] while [discoveredEndpoints] is a [StateFlow]
 * rebuilt from the per-transport [EndpointDirectory]s on every diff. This split
 * is deliberate: StateFlow conflates updates by equality (kotlinx docs,
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/),
 * so pushing discrete Found/Updated/Lost *events* through a StateFlow would
 * silently drop intermediate transitions for slow collectors — e.g., a
 * Found followed quickly by Lost could collapse and consumers would never see
 * either. Snapshots, by contrast, are idempotent full-state values where
 * conflation is exactly the desired behavior. Hence: events → SharedFlow with
 * extra buffering and DROP_OLDEST overflow (slow UI consumers lose the oldest
 * cosmetic events rather than blocking radio threads or crashing with
 * BufferOverflowException); state → StateFlow.
 *
 * **Cross-radio dedup by deviceId.** One directory per transport keeps
 * per-radio truth; the global endpoint list collapses peers seen on multiple
 * radios into ONE endpoint reported via the highest-priority transport:
 * `LAN > WIFI_DIRECT > WIFI_AWARE > BLE`. Priority rationale: Android's own
 * connectivity stack scores/ranks networks by transport policy
 * (https://source.android.com/docs/core/connect/network-selection) and treats
 * transports as capabilities ranked per request
 * (https://developer.android.com/reference/android/net/NetworkCapabilities);
 * Nearby Connections exposes explicit strategy choices trading bandwidth vs
 * topology (https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Strategy).
 * LAN wins because it offers the highest sustained throughput through the AP
 * infrastructure; Wi-Fi Direct next (direct P2P, no AP needed but slower in
 * practice per our own benchmarks); Wi-Fi Aware after (higher throughput than
 * BLE across longer range — https://developer.android.com/develop/connectivity/wifi/wifi-aware);
 * BLE last as a presence-only signal (plan C3.8). Unknown transport names rank
 * below all known ones.
 *
 * **Hysteresis on loss.** When the highest-priority transport reports a peer
 * Lost while the same deviceId is still alive in a lower-priority directory,
 * the composite does NOT emit Lost; it emits Updated carrying the fallback
 * endpoint. Rationale: "peer gone" would be factually wrong (the peer is still
 * reachable), and flapping the UI between Lost/Found as radios disagree is
 * worse than briefly reporting a slower path. A real Lost is emitted only when
 * the last sighting disappears (radio goodbye or sweeper aging).
 *
 * **Presence sweeping (plan C3.5).** Radios miss goodbye packets routinely —
 * mDNS goodbyes are TTL=0 records (RFC 6762 §10.1,
 * https://datatracker.ietf.org/doc/html/rfc6762#section-10.1) that many stacks
 * never send on crash/kill, forcing waiters to fall back to record expiry:
 * 120 s for SRV/A/AAAA records, 75 min for PTR/TXT
 * (RFC 6762 §10; https://datatracker.ietf.org/doc/html/rfc6762#section-10).
 * Waiting out even the 120 s SRV TTL is far too slow for chat-style presence.
 * Default grace of 30 s matches plan C3.5's example: short enough that peer
 * departure converges within half a minute, long enough to ride out single
 * missed re-announcements without flapping during brief Wi-Fi drops. Callers
 * drive [sweep] periodically with explicit timestamps (pure logic, testable).
 *
 * Threading: event handling mutates internal maps under a lock; flows are
 * thread-safe. Collectors are launched lazily into an injected scope so tests
 * can supply a synchronous dispatcher for deterministic assertions.
 */
public class CompositeDiscovery(
    private val transports: List<FlashRadioTransport>,
    private val directoryFactory: () -> EndpointDirectory = { StandardEndpointDirectory() },
    scopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default) },
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
    /**
     * Period between automatic presence sweeps while any transport runs
     * (P3.5 fix: radio goodbyes are routinely missed — RFC 6762 §10.1 — so
     * aging MUST be driven internally, not left to callers).
     */
    private val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    /**
     * How long a transport may report `browsing = false` while this composite
     * still wants it browsing before the sweeper forces a
     * [FlashRadioTransport.restartBrowsing].
     *
     * Needed because a radio can stop browsing without anyone asking it to: the
     * NSD transport exhausts its restart budget and gives up, or the platform
     * silently stops delivering after a Wi-Fi ↔ hotspot switch. Nothing used to
     * notice — `startDiscovery()` is a no-op while the transport still believes
     * it is browsing — so discovery stayed dead until the process restarted.
     *
     * Must exceed [sweepIntervalMs] so a transient false (state event racing a
     * start) cannot trigger a pointless radio teardown; it also rate-limits
     * repeated attempts to one per window.
     */
    private val browseWatchdogMs: Long = DEFAULT_BROWSE_WATCHDOG_MS,
    private val delayFn: suspend (Long) -> Unit = { ms -> kotlinx.coroutines.delay(ms) },
    /** Determinism hook for JVM tests (same pattern as NsdTransport.maxDutyCycles). */
    private val maxSweepLoops: Int = Int.MAX_VALUE,
) : FlashDiscovery {

    public companion object {
        /**
         * Default presence grace window (ms) for [sweep]. See class KDoc for
         * the mDNS-TTL research behind choosing 30 s.
         */
        public const val DEFAULT_GRACE_MS: Long = 30_000L

        /**
         * Default period between automatic sweeps: fast enough that a departed
         * peer disappears at most ~[DEFAULT_SWEEP_INTERVAL_MS] + [DEFAULT_GRACE_MS]
         * after its last real sighting, slow enough to be negligible load.
         */
        public const val DEFAULT_SWEEP_INTERVAL_MS: Long = 5_000L

        /**
         * Default [browseWatchdogMs]: two sweep intervals. Long enough that an
         * in-flight start/restart is never interrupted, short enough that a radio
         * that gave up is back within ~15 s instead of never.
         */
        public const val DEFAULT_BROWSE_WATCHDOG_MS: Long = 10_000L

        /** Highest priority first; unknown names rank after these. */
        public val PRIORITY_ORDER: List<String> = listOf("LAN", "WIFI_DIRECT", "WIFI_AWARE", "BLE")

        public fun priorityRank(transportName: String): Int {
            // `uppercase()` with no argument is the locale-independent overload added in
            // Kotlin 1.5; on JVM it compiles to exactly the previous
            // `toUpperCase(Locale.ROOT)`, so transport-name matching is unchanged.
            val idx = PRIORITY_ORDER.indexOf(transportName.uppercase())
            return if (idx >= 0) idx else PRIORITY_ORDER.size
        }
    }

    private val scope: CoroutineScope = scopeFactory()

    /**
     * Was `Any()` + `kotlin.synchronized`, which is JVM-only. [PlatformLock] is the
     * `expect`/`actual` seam that replaces it; both current `actual`s are the same
     * monitor, so the 18 critical sections below are unchanged in behaviour.
     */
    private val lock = PlatformLock()
    private val directories = HashMap<String, EndpointDirectory>()
    private val browsingByTransport = HashMap<String, Boolean>()
    private val advertisingByTransport = HashMap<String, Boolean>()

    /**
     * transportName → wall clock of the FIRST `browsing = false` report received
     * while [desiredBrowsing]; cleared as soon as the transport reports browsing
     * again. Drives the browse watchdog (see [browseWatchdogMs]).
     */
    private val browseStalledSince = HashMap<String, Long>()

    /**
     * Whether this composite currently WANTS its transports browsing. Separate
     * from [browsingByTransport], which is what the radios actually report; the
     * gap between the two is precisely what the watchdog repairs.
     */
    @Volatile private var desiredBrowsing = false
    private var collecting = false
    private var sweeperJob: kotlinx.coroutines.Job? = null

    private var advertisedPort: Int = 0
    private var identity: FlashAdvertisedIdentity? = null

    /**
     * Active discovery mode (P3.5-B3). STANDARD is applied implicitly at
     * construction — the composite's [currentPolicy] AND every transport's own
     * default both start at STANDARD (transport setMode is suspend, so an eager
     * constructor fan-out is impossible); explicit application happens via
     * [setMode].
     */
    private val _discoveryMode = MutableStateFlow(FlashDiscoveryMode.STANDARD)

    /** Current discovery mode; updated by [setMode] before transports are fanned out to. */
    public val discoveryMode: StateFlow<FlashDiscoveryMode> = _discoveryMode

    /** Policy table entry for [_discoveryMode]; single source for state-message logic. */
    @Volatile private var currentPolicy: DiscoveryModePolicy =
        DiscoveryModePolicy.forMode(FlashDiscoveryMode.STANDARD)

    private val _mergedEvents = MutableSharedFlow<FlashTransportEvent>(
        replay = 0,
        extraBufferCapacity = EXTRA_BUFFER_CAPACITY,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Merged, deduplicated event stream from all transports (plus composite-
     * generated Lost/Updated from sweeps and cross-transport hysteresis).
     * Live traffic only — no replay, matching FlashTransportEvent semantics;
     * buffer absorbs bursts, DROP_OLDEST sheds oldest events under overload.
     */
    public val mergedEvents: SharedFlow<FlashTransportEvent> = _mergedEvents

    private val _discoveredEndpoints =
        MutableStateFlow<List<FlashDiscoveredEndpoint>>(emptyList())
    override val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>> =
        _discoveredEndpoints

    private val _state = MutableStateFlow(FlashDiscoveryState())
    override val state: StateFlow<FlashDiscoveryState> = _state

    // ---------------------------------------------------------------------
    // FlashDiscovery API
    // ---------------------------------------------------------------------

    /** Starts continuous browsing on EVERY transport (aggregate result). */
    override suspend fun startDiscovery(): FlashResult<Unit> {
        desiredBrowsing = true
        val result = aggregate { transport ->
            transport.startBrowsing().onSuccess {
                markBrowsing(transport.transportName, true)
                clearStall(transport.transportName)
            }
        }
        // Browsing without the sweeper means nothing ages peers out and nothing
        // watchdogs a dead radio; startAll() used to be the only path that armed it.
        startSweeperLocked()
        return result
    }

    /**
     * Forces every transport to tear down and re-arm its browse
     * ([FlashRadioTransport.restartBrowsing]), regardless of what it believes its
     * own state is.
     *
     * [startDiscovery] cannot do this: it delegates to `startBrowsing()`, which is
     * idempotent and therefore a no-op for a transport whose browse died silently
     * (Wi-Fi ↔ hotspot switch, doze, an OEM mDNS stack that stopped delivering).
     * Callers use this from connectivity changes / screen-on, where the whole point
     * is to distrust the cached state.
     */
    public suspend fun restartDiscovery(): FlashResult<Unit> {
        desiredBrowsing = true
        val result = aggregate { transport ->
            transport.restartBrowsing().onSuccess {
                markBrowsing(transport.transportName, true)
                clearStall(transport.transportName)
            }
        }
        startSweeperLocked()
        return result
    }

    /**
     * Stops browsing everywhere. The radio seam only exposes full [FlashRadioTransport.stop],
     * so this stops advertising too and then transparently restarts advertising
     * when it was previously active (identity retained from startAll).
     */
    override suspend fun stopDiscovery(): FlashResult<Unit> {
        val resumeAdvertising = _state.value.isAdvertising
        desiredBrowsing = false
        val result = aggregate { transport ->
            transport.stop().onSuccess {
                markBrowsing(transport.transportName, false)
                markAdvertising(transport.transportName, false)
            }
        }
        lock.withLock { browseStalledSince.clear() }
        refreshState()
        if (resumeAdvertising) {
            identity?.let { startAdvertisingInternal(advertisedPort, it) }
        }
        return result
    }

    /**
     * Starts advertising using the identity previously supplied to [startAll].
     * Returns Failure when no identity has been set yet — bare-port advertising
     * cannot construct TXT records.
     */
    override suspend fun startAdvertising(listenPort: Int): FlashResult<Unit> {
        val id = identity
            ?: return FlashResult.Failure(
                FlashError.Unknown("No advertised identity set; call startAll(port, identity) first"),
            )
        return startAdvertisingInternal(listenPort, id)
    }

    /**
     * Stops advertising everywhere; browsing is transparently restarted where
     * it was active (radio seam only offers full stop).
     */
    override suspend fun stopAdvertising(): FlashResult<Unit> {
        val resumeBrowsing = _state.value.isDiscovering
        val result = aggregate { transport ->
            transport.stop().onSuccess {
                markBrowsing(transport.transportName, false)
                markAdvertising(transport.transportName, false)
            }
        }
        refreshState()
        if (resumeBrowsing) {
            aggregate { transport ->
                transport.startBrowsing().onSuccess { markBrowsing(transport.transportName, true) }
            }
        }
        return result
    }

    override suspend fun stopAll(): FlashResult<Unit> {
        stopSweeper()
        desiredBrowsing = false
        val result = aggregate { transport ->
            transport.stop().onSuccess {
                markBrowsing(transport.transportName, false)
                markAdvertising(transport.transportName, false)
            }
        }
        lock.withLock { browseStalledSince.clear() }
        refreshState()
        return result
    }

    // ---------------------------------------------------------------------
    // Plan C3.9 contract
    // ---------------------------------------------------------------------

    /**
     * Advertises AND browses on EVERY transport. Success iff every transport
     * succeeded at both; otherwise Failure(FlashError.Unknown) whose message
     * lists which transports failed and why. Partially-started transports keep
     * running (flags reflect reality) so callers can stopAll() cleanly.
     *
     * P3.5-B3: identity is passed through UNCHANGED — it now carries the
     * A-work additions (capabilities / fingerprintPrefix), which transports
     * serialize into their radio-specific TXT records themselves. GHOST-mode
     * transports report their suppressed advertise as Success (documented
     * no-op) so aggregation stays uniform; [refreshState] consults
     * [currentPolicy] so `isAdvertising` never claims visibility in GHOST.
     */
    public suspend fun startAll(port: Int, identity: FlashAdvertisedIdentity): FlashResult<Unit> {
        this.identity = identity
        this.advertisedPort = port
        desiredBrowsing = true
        val failures = mutableListOf<String>()
        lock.withLock { collectingOrStart() }
        startSweeperLocked()
        for (transport in transports) {
            val advResult = transport.startAdvertising(port, identity)
            val browseResult = transport.startBrowsing()
            val ok = mutableListOf<String>()
            if (advResult.isSuccess) markAdvertising(transport.transportName, true) else ok += "advertising"
            if (browseResult.isSuccess) markBrowsing(transport.transportName, true) else ok += "browsing"
            if (ok.isNotEmpty()) {
                val reason = listOfNotNull(
                    (advResult as? FlashResult.Failure)?.error?.takeIf { "advertising" in ok },
                    (browseResult as? FlashResult.Failure)?.error?.takeIf { "browsing" in ok },
                ).joinToString("; ")
                failures += "${transport.transportName} failed ${ok.joinToString("+")}: $reason"
            }
        }
        refreshState()
        return if (failures.isEmpty()) {
            FlashResult.Success(Unit)
        } else {
            FlashResult.Failure(FlashError.Unknown(failures.joinToString(" | ")))
        }
    }

    // ---------------------------------------------------------------------
    // Discovery modes (P3.5-B3)
    // ---------------------------------------------------------------------

    /**
     * Switches the discovery mode: stores the mode's [DiscoveryModePolicy],
     * fans it out to EVERY transport's [FlashRadioTransport.setMode] (radios
     * without mode support inherit the interface's no-op default), and reflects
     * the mode into the status message prefix (e.g. `"[ECO] Advertising and
     * browsing"`). Existing consumers of [state] keep parsing the suffix
     * unchanged — the prefix is purely additive.
     */
    public suspend fun setMode(mode: FlashDiscoveryMode) {
        currentPolicy = DiscoveryModePolicy.forMode(mode)
        _discoveryMode.value = mode
        lock.withLock { collectingOrStart() }
        for (transport in transports) {
            transport.setMode(currentPolicy)
        }
        refreshState()
    }

    private data class AgedOut(val deviceId: FlashDeviceId, val serviceName: String?)

    /**
     * Ages out endpoints not re-seen within [graceWindowMs] (boundary: an age
     * of EXACTLY the window counts as expired). Emits Lost once per aged-out
     * peer — or Updated when another transport still reports it alive
     * (hysteresis). Idempotent per instant: repeated calls at the same or later
     * time produce no duplicates because aged entries were removed.
     */
    public fun sweep(nowMs: Long, graceWindowMs: Long = DEFAULT_GRACE_MS) {
        val agedOut = mutableListOf<AgedOut>()
        lock.withLock {
            val serviceNames = HashMap<FlashDeviceId, String>()
            for (transport in transports) {
                directoryFor(transport.transportName).snapshot().forEach {
                    serviceNames[it.endpoint.deviceId] = it.endpoint.serviceName
                }
            }
            for (transport in transports) {
                val lost = directoryFor(transport.transportName)
                    .sweepExpired(graceWindowMs, nowMs)
                lost.forEach { diff ->
                    agedOut += AgedOut(diff.deviceId, serviceNames[diff.deviceId])
                }
            }
            rebuildEndpointsLocked()
        }
        for (aged in agedOut) {
            lock.withLock {
                val representative = globalRepresentativeLocked(aged.deviceId)
                if (representative != null) {
                    // Hysteresis: peer alive on a lower-priority radio — no Lost.
                    _mergedEvents.tryEmit(FlashTransportEvent.Updated(representative.endpoint))
                } else {
                    _mergedEvents.tryEmit(
                        FlashTransportEvent.Lost(aged.deviceId, aged.serviceName),
                    )
                }
            }
        }
        refreshState()
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private suspend fun aggregate(
        action: suspend (FlashRadioTransport) -> FlashResult<Unit>,
    ): FlashResult<Unit> {
        lock.withLock { collectingOrStart() }
        val failures = mutableListOf<String>()
        for (transport in transports) {
            when (val result = action(transport)) {
                is FlashResult.Success -> Unit
                is FlashResult.Failure ->
                    failures += "${transport.transportName}: ${result.error}"
            }
        }
        refreshState()
        return if (failures.isEmpty()) FlashResult.Success(Unit)
        else FlashResult.Failure(FlashError.Unknown(failures.joinToString("; ")))
    }

    private suspend fun startAdvertisingInternal(
        port: Int,
        identity: FlashAdvertisedIdentity,
    ): FlashResult<Unit> = aggregate { transport ->
        transport.startAdvertising(port, identity)
            .onSuccess {
                advertisedPort = port
                markAdvertising(transport.transportName, true)
            }
    }

    private fun directoryFor(transportName: String): EndpointDirectory =
        directories.getOrPut(transportName) { directoryFactory() }

    private fun markBrowsing(name: String, value: Boolean) = lock.withLock {
        browsingByTransport[name] = value
    }

    private fun markAdvertising(name: String, value: Boolean) = lock.withLock {
        advertisingByTransport[name] = value
    }

    /**
     * Clears a transport's stall stamp because it was just (re)started BY REQUEST.
     * Deliberately not folded into [markBrowsing]: the watchdog re-stamps before
     * attempting its own restart, and that stamp is what rate-limits it to one
     * attempt per [browseWatchdogMs] window.
     */
    private fun clearStall(name: String) = lock.withLock {
        browseStalledSince.remove(name)
    }

    private fun collectingOrStart() {
        if (collecting) return
        collecting = true
        for (transport in transports) {
            scope.launch {
                transport.events.collect { event -> handleEvent(transport, event) }
            }
        }
    }

    /**
     * Automatic presence aging while the engine runs (P3.5 stale-endpoint fix):
     * sweeps every [sweepIntervalMs] so departed peers converge to Lost at most
     * ~grace + interval after their last sighting, even when radios miss
     * goodbye packets. The same tick drives [watchdogBrowsing], so a radio that
     * silently stopped browsing is re-armed on the same schedule. Bounded by
     * [maxSweepLoops] as a JVM-test hook.
     */
    private fun startSweeperLocked() {
        if (sweeperJob?.isActive == true) return
        sweeperJob = scope.launch {
            var loops = 0
            while (loops < maxSweepLoops && kotlinx.coroutines.currentCoroutineContext()
                    .let { it[kotlinx.coroutines.Job]?.isActive == true }
            ) {
                delayFn(sweepIntervalMs)
                sweep(nowMs = clock())
                watchdogBrowsing(nowMs = clock())
                loops += 1
            }
        }
    }

    private fun stopSweeper() {
        sweeperJob?.cancel()
        sweeperJob = null
    }

    private fun handleEvent(transport: FlashRadioTransport, event: FlashTransportEvent) {
        when (event) {
            is FlashTransportEvent.Found -> applySighting(transport, event.endpoint)
            is FlashTransportEvent.Updated -> applySighting(transport, event.endpoint)
            is FlashTransportEvent.Presence -> applyPresence(transport, event.endpoint)
            is FlashTransportEvent.Lost -> applyLoss(transport, event.deviceId)
            is FlashTransportEvent.StateChanged -> applyBrowseState(transport, event.browsing)
        }
    }

    /**
     * Liveness refresh (see [FlashTransportEvent.Presence]). Bumps `lastSeenAt`
     * so [sweep] stops aging out a peer that is demonstrably still there, and
     * emits NOTHING user-visible — presence is not a transition.
     *
     * This is the fix for the "device appears, then disappears ~30 s later and
     * never comes back" report: a stable peer produces one Found and then only
     * deduped sightings, so the sweeper starved and evicted it, and the false
     * Lost also unbound its network route. The self-heal branch covers the peers
     * that were already evicted before this event existed (and any future radio
     * that reports presence for a peer this composite has forgotten): a heartbeat
     * for an unknown peer is promoted to a real sighting rather than discarded.
     */
    private fun applyPresence(transport: FlashRadioTransport, endpoint: FlashDiscoveredEndpoint) {
        val known = lock.withLock {
            directoryFor(transport.transportName).get(endpoint.deviceId) != null
        }
        if (!known) {
            applySighting(transport, endpoint)
            return
        }
        lock.withLock {
            // Unchanged by construction, so no diff to publish and no snapshot
            // rebuild: only lastSeenAt moves, and the snapshot's contents are
            // identical (re-publishing would churn the UI list for nothing).
            directoryFor(transport.transportName).applySeen(endpoint, clock())
        }
    }

    /**
     * Records what a transport reports about its OWN browse state, instead of
     * trusting the flag this composite optimistically set when it called
     * `startBrowsing()`. A radio that exhausted its restart budget, or whose
     * platform browse died, reports `browsing = false` here — which is both what
     * makes [state] honest and what arms the watchdog in [watchdogBrowsing].
     */
    private fun applyBrowseState(transport: FlashRadioTransport, browsing: Boolean) {
        lock.withLock {
            val name = transport.transportName
            browsingByTransport[name] = browsing
            if (browsing) {
                browseStalledSince.remove(name)
            } else if (desiredBrowsing && !browseStalledSince.containsKey(name)) {
                browseStalledSince[name] = clock()
            }
        }
        refreshState()
    }

    /**
     * Re-arms transports that stopped browsing while this composite still wants
     * them browsing (see [browseWatchdogMs]). Runs on the sweeper tick.
     */
    private suspend fun watchdogBrowsing(nowMs: Long) {
        if (!desiredBrowsing) return
        val stalled = lock.withLock {
            browseStalledSince
                .filterValues { since -> nowMs - since >= browseWatchdogMs }
                .keys
                .toList()
        }
        if (stalled.isEmpty()) return
        for (name in stalled) {
            val transport = transports.firstOrNull { it.transportName == name } ?: continue
            // Re-stamp BEFORE the attempt: a failed restart then retries one full
            // window later instead of hammering the radio every sweep.
            lock.withLock { browseStalledSince[name] = nowMs }
            transport.restartBrowsing().onSuccess { markBrowsing(name, true) }
        }
        refreshState()
    }

    private fun applySighting(transport: FlashRadioTransport, endpoint: FlashDiscoveredEndpoint) {
        lock.withLock {
            val deviceId = endpoint.deviceId
            val previousRepresentative = globalRepresentativeLocked(deviceId)
            when (directoryFor(transport.transportName).applySeen(endpoint, clock())) {
                // Was a non-local `return` from applySighting. `PlatformLock.withLock` is not
                // `inline` (an `expect class` member cannot be), so a non-local return no
                // longer compiles. Identical in effect here because the `withLock` call is the
                // whole function body: returning from the lambda returns from the function.
                is EndpointDirectory.Diff.Unchanged -> return@withLock
                else -> Unit
            }
            val newRepresentative = globalRepresentativeLocked(deviceId)
            when {
                previousRepresentative == null ->
                    _mergedEvents.tryEmit(FlashTransportEvent.Found(newRepresentative!!.endpoint))
                newRepresentative != previousRepresentative ->
                    _mergedEvents.tryEmit(FlashTransportEvent.Updated(newRepresentative!!.endpoint))
                // Else: lower-priority sighting absorbed silently; snapshot unchanged.
            }
            rebuildEndpointsLocked()
        }
    }

    private fun applyLoss(transport: FlashRadioTransport, deviceId: FlashDeviceId) {
        lock.withLock {
            val serviceName = directoryFor(transport.transportName)
                .get(deviceId)?.endpoint?.serviceName
            when (directoryFor(transport.transportName).applyLost(deviceId)) {
                is EndpointDirectory.Diff.Lost -> {
                    rebuildEndpointsLocked()
                    val representative = globalRepresentativeLocked(deviceId)
                    if (representative != null) {
                        _mergedEvents.tryEmit(FlashTransportEvent.Updated(representative.endpoint))
                    } else {
                        _mergedEvents.tryEmit(FlashTransportEvent.Lost(deviceId, serviceName))
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * Best-known entry for a deviceId across ALL directories: lowest priority
     * rank wins; ties broken by most recent sighting.
     */
    private fun globalRepresentativeLocked(deviceId: FlashDeviceId): EndpointDirectory.Entry? {
        var best: EndpointDirectory.Entry? = null
        var bestRank = Int.MAX_VALUE
        for (transport in transports) {
            val rank = priorityRank(transport.transportName)
            val entry = directoryFor(transport.transportName).get(deviceId) ?: continue
            val current = best
            if (current == null ||
                rank < bestRank ||
                (rank == bestRank && entry.lastSeenAtMs > current.lastSeenAtMs)
            ) {
                best = entry
                bestRank = rank
            }
        }
        return best
    }

    private fun rebuildEndpointsLocked() {
        data class Rep(val rank: Int, val entry: EndpointDirectory.Entry)

        val representatives = HashMap<FlashDeviceId, Rep>()
        for (transport in transports) {
            val rank = priorityRank(transport.transportName)
            for (entry in directoryFor(transport.transportName).snapshot()) {
                val deviceId = entry.endpoint.deviceId
                val current = representatives[deviceId]
                if (current == null ||
                    rank < current.rank ||
                    (rank == current.rank && entry.lastSeenAtMs > current.entry.lastSeenAtMs)
                ) {
                    representatives[deviceId] = Rep(rank, entry)
                }
            }
        }
        _discoveredEndpoints.value = representatives.values
            .map { it.entry }
            .sortedWith(
                compareByDescending<EndpointDirectory.Entry> { it.lastSeenAtMs }
                    .thenComparator { a, b ->
                        a.endpoint.deviceId.value.compareTo(b.endpoint.deviceId.value)
                    },
            )
            .map { it.endpoint }
    }

    private fun refreshState() = lock.withLock {
        val anyBrowsing = browsingByTransport.values.any { it }
        // GHOST (P3.5-B3): transports report their suppressed advertise as
        // Success, so the raw flag would over-report visibility. The policy is
        // authoritative for what the outside world can see.
        val anyAdvertising = currentPolicy.advertises && advertisingByTransport.values.any { it }
        val message = when {
            anyBrowsing && anyAdvertising -> "Advertising and browsing"
            anyBrowsing -> "Browsing"
            anyAdvertising -> "Advertising"
            else -> "Idle"
        }
        _state.value = FlashDiscoveryState(
            isDiscovering = anyBrowsing,
            isAdvertising = anyAdvertising,
            advertisedPort = advertisedPort,
            statusMessage = "[${currentPolicy.mode.name}] $message",
        )
    }
}

private const val EXTRA_BUFFER_CAPACITY = 256
