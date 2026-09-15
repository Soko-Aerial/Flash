// FlashTextFraming/FlashProtocol/FlashLog are @FlashInternalApi — library-internal, opted into here
// exactly as NsdTransport and JmdsTransport do: this is a radio transport, not published API.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.multicast

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.concurrent.PlatformLock
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashRadioTransport
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Peer discovery by self-announcing UDP multicast — the second LAN transport, running ALONGSIDE the
 * DNS-SD ones (`NsdTransport` on Android, `JmdsTransport` on the desktop), never in place of them.
 *
 * ## Why this exists next to a working mDNS transport
 *
 * NSD/JmDNS work. What they cannot do is tell the difference between "this peer published no
 * attributes" and "this peer's attributes have not reached my resolver cache yet", and they cannot
 * expire a peer that died without saying goodbye:
 *
 * - JmDNS 3.5.12 hands back a hollow `ServiceInfo` (`txtKeys=[] txtBytes=1`) when the TXT has not
 *   been cached yet, so a peer resolves with a correct address and NO `device_id` — measured against
 *   the desktop's own record, and again against the phone's on 2026-09-14, where it left the desktop
 *   unable to build an endpoint for the phone and therefore never dialing it.
 * - mDNS has no periodic positive signal. A peer that is killed sends no goodbye, so its records sit
 *   in every neighbour's cache until their TTL expires — an hour with JmDNS's default — and the only
 *   thing a transport can do about it is guess.
 *
 * This transport has neither problem, because it defines its own liveness:
 *
 * - **Discovery is one datagram that carries the whole peer** — identity (the shared `TxtCodec`
 *   vocabulary) plus its server port — and **the peer's address is the datagram's source**, so
 *   there is no resolution step to fail or to go stale.
 * - **Every peer re-announces on a cadence**, so a lease bounds a peer's life. A live peer renews it;
 *   a dead one stops existing after [peerLeaseMs] (`Lost`), with no dependence on anyone sending a
 *   goodbye or on a resolver's cache expiring. The `Presence` heartbeat this emits is therefore a
 *   *positive* signal backed by a fresh datagram, not an assertion from the absence of bad news.
 *
 * ## Contract
 *
 * Implements [FlashRadioTransport] like any other radio, so [com.transfer.flash.core.discovery.core.CompositeDiscovery]
 * merges it with the DNS-SD transports and dedups by device id. When both transports see a peer the
 * composite keeps one endpoint (same address, same port, same device id) and its loss hysteresis
 * means whichever transport reports the peer first wins the right to keep it visible — so adding this
 * transport cannot make a peer that NSD already sees disappear.
 */
public class MulticastTransport(
    private val socketFactory: MulticastSocketFactory,
    private val directory: EndpointDirectory,
    private val group: String = DEFAULT_GROUP,
    private val port: Int = DEFAULT_PORT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeSourceMs: () -> Long = { com.transfer.flash.core.common.time.SystemTimeSource.nowMs() },
    /** Cadence of the periodic announcement that renews this device's lease on its peers. */
    private val announceIntervalMs: Long = DEFAULT_ANNOUNCE_INTERVAL_MS,
    /** How long a peer survives without an announcement. See [DEFAULT_PEER_LEASE_MS]. */
    private val peerLeaseMs: Long = DEFAULT_PEER_LEASE_MS,
    private val sweepIntervalMs: Long = DEFAULT_SWEEP_INTERVAL_MS,
    /**
     * Pause after a receive that returned nothing.
     *
     * A real socket blocks for [RECEIVE_TIMEOUT_MS] before returning null, so this adds a few
     * milliseconds to an already-idle path. It exists for the case the timeout does NOT happen: a
     * socket that fails instantly (closed underneath us, an interface torn down) would otherwise be
     * polled in a tight loop, and a thread spinning on a dead socket is the same class of defect as
     * the unbounded re-resolve loop that pegged a core in `JmdsTransport`. It also guarantees every
     * iteration of the loop yields, which is what makes this transport testable on a single-threaded
     * dispatcher.
     */
    private val receiveRetryDelayMs: Long = RECEIVE_RETRY_DELAY_MS,
    /** Injectable for deterministic tests; production takes the process-wide RNG. */
    private val random: Random = Random.Default,
    private val logInfo: (String) -> Unit = { FlashLog.i(TAG, it) },
    private val logWarn: (String, Throwable?) -> Unit = { message, cause -> FlashLog.w(TAG, message, cause) },
) : FlashRadioTransport {

    override val transportName: String = TRANSPORT_NAME

    private val _events = MutableSharedFlow<FlashTransportEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<FlashTransportEvent> = _events.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var browseJob: Job? = null
    private var announceJob: Job? = null
    private var sweepJob: Job? = null

    /**
     * Guards [bindings], [leases], [directory] and the announce bookkeeping.
     *
     * Not optional politeness. `StandardEndpointDirectory`'s KDoc says it is not thread-safe and that
     * "the owner guards access", and this transport is **not** the single lane it first looks like:
     * [startReceiveLoops] launches ONE BLOCKING RECEIVE LOOP PER INTERFACE on the same dispatcher, and
     * `Dispatchers.IO` runs those in parallel. Two interfaces hearing two peers in the same instant
     * would mutate one `LinkedHashMap` from two threads — whose classic outcomes are a corrupted map
     * and an entry list that loops forever — and the sweep loop is a third writer. `NsdTransport` can
     * keep a bare map because the platform hands it one callback at a time; here the concurrency is
     * ours, so the guard has to be ours too.
     *
     * Deliberately NOT held across socket I/O: sends and receives happen outside it, and only the
     * state transitions inside. Emitting under it is safe — [MutableSharedFlow.tryEmit] never runs a
     * collector inline, so it cannot block and cannot invert lock order against a consumer.
     */
    private val lock = PlatformLock()

    private var bindings: List<MulticastSocketBinding> = emptyList()

    @Volatile private var browsing: Boolean = false
    @Volatile private var advertising: Boolean = false
    @Volatile private var ownDeviceId: FlashDeviceId? = null

    /**
     * True while the last bind attempt found no usable interface. Latched so the degraded state is
     * reported once per episode rather than on every retry (see [ensureBound]).
     */
    private var bindDegraded: Boolean = false

    /**
     * Volatile because it is *meant* to be read per announcement, not captured: a mode change has to
     * be visible to the very next datagram, and the announce lane is a different lane from the one
     * that sets the mode.
     */
    @Volatile private var modePolicy: DiscoveryModePolicy = DiscoveryModePolicy.forMode(
        com.transfer.flash.core.discovery.core.FlashDiscoveryMode.STANDARD,
    )

    /** Identity/port retained so leaving GHOST can resume advertising without a caller action. */
    private var lastIdentity: FlashAdvertisedIdentity? = null
    private var lastAdvertisedPort: Int = 0
    private var lastAnnounceAtMs: Long = Long.MIN_VALUE

    /**
     * peer → (serviceName, lastSeenMs). The lease map: a peer is in [directory] only while it is in
     * here and unexpired.
     *
     * Guarded by [lock] — see there for why this lane is concurrent rather than serial. An earlier
     * version of this KDoc claimed the map was lane-confined because [handleDatagram] and
     * [sweepLeases] run "on the transport's own dispatcher"; that was wrong, and it was the kind of
     * wrong that only shows up on a multi-interface host under load.
     */
    private val leases = HashMap<FlashDeviceId, PeerLease>()

    private class PeerLease(val serviceName: String, var lastSeenAtMs: Long)

    // -- Advertising -----------------------------------------------------------

    override suspend fun startAdvertising(port: Int, identity: FlashAdvertisedIdentity): FlashResult<Unit> {
        ownDeviceId = identity.deviceId
        lock.withLock {
            lastIdentity = identity
            lastAdvertisedPort = port
        }
        if (!modePolicy.advertises) {
            logInfo("Mode ${modePolicy.mode} suppresses announcing; request recorded only")
            emitState("Announcing suppressed (${modePolicy.mode})")
            return FlashResult.Success(Unit)
        }
        ensureScope()
        // A bind failure is tolerated here, not returned — [recoverBindingsIfNeeded] says why.
        ensureBound()
        advertising = true
        // Announcement is the one thing that makes this device visible, and a peer's lease on us is
        // only as long as our cadence: start it now rather than at the first interval.
        startAnnounceLoop(burst = true)
        emitState("Announcing on $group:$port (udp)")
        return FlashResult.Success(Unit)
    }

    override suspend fun setMode(policy: DiscoveryModePolicy) {
        val previous = modePolicy
        modePolicy = policy
        if (previous.advertises == policy.advertises) return
        if (!policy.advertises) {
            announceJob?.cancel()
            announceJob = null
            advertising = false
            emitState("Announcing suppressed (${policy.mode})")
        } else {
            // Read together under the lock: a resume racing a rename must not re-advertise the old
            // port with the new name.
            val resume = lock.withLock {
                lastIdentity?.let { it to lastAdvertisedPort }
            } ?: return // never advertised; nothing to resume
            startAdvertising(resume.second, resume.first)
        }
    }

    // -- Browsing --------------------------------------------------------------

    override suspend fun startBrowsing(): FlashResult<Unit> {
        if (browsing) return FlashResult.Success(Unit)
        ensureScope()
        // A bind failure is tolerated here, not returned — [recoverBindingsIfNeeded] says why.
        ensureBound()
        browsing = true
        startReceiveLoops()
        startSweepLoop()
        emitState("Listening on $group:$port (udp)")
        return FlashResult.Success(Unit)
    }

    /**
     * Full rebind, not a listener re-add — the same recovery shape the DNS-SD transports needed.
     *
     * A socket bound to an interface that has gone away (Wi-Fi ↔ hotspot switch, VPN coming up,
     * cable pulled) keeps looking healthy while receiving nothing. Re-arming a receive loop on a
     * dead socket changes nothing, so recovery closes every socket and binds again.
     */
    override suspend fun restartBrowsing(): FlashResult<Unit> {
        logInfo("Forcing multicast rebind")
        stopLoops()
        closeBindings()
        browsing = false
        return startBrowsing()
    }

    override suspend fun stop(): FlashResult<Unit> {
        stopLoops()
        closeBindings()
        browsing = false
        advertising = false
        lock.withLock {
            // Forget the degradation latch: a restart is a new episode, and its first failure has to
            // be reported rather than swallowed as a repeat of the last one.
            bindDegraded = false
            leases.clear()
        }
        scope?.cancel()
        scope = null
        emitState("Stopped")
        return FlashResult.Success(Unit)
    }

    // -- Inbound ---------------------------------------------------------------

    /**
     * Feeds one received datagram through discovery.
     *
     * @param sourceBinding the interface it arrived on, for logs only — the ADDRESS comes from the
     *   datagram itself, which is the whole reason this transport cannot hold a stale address.
     */
    internal fun handleDatagram(datagram: MulticastDatagram, sourceBinding: String? = null) {
        val announcement = MulticastProtocol.decode(datagram.payload.decodeToString())
            ?: return // not ours, a version we do not speak, or malformed: silence, not a peer
        val deviceId = announcement.identity.deviceId
        // Multicast loopback is enabled so our own announcements come back; identity is the filter,
        // never the address (a multi-homed host announces from several).
        if (deviceId == ownDeviceId) return
        val peerProto = announcement.identity.protocolVersion
        if (!FlashProtocol.isCompatible(peerProto)) {
            logWarn(
                "Dropping multicast peer proto=$peerProto (want ${FlashProtocol.VERSION}) " +
                    "id=${deviceId.value}",
                null,
            )
            return
        }
        // An address we cannot dial is worse than no endpoint: it would replace a good one.
        val host = datagram.sourceAddress.takeIf { it.isNotBlank() } ?: return

        val now = timeSourceMs()
        val endpoint = FlashDiscoveredEndpoint(
            device = FlashDevice(
                id = deviceId,
                friendlyName = announcement.identity.friendlyName.ifBlank { deviceId.value.take(SHORT_ID) },
                transportType = FlashTransportType.LAN,
                presence = FlashPeerPresence.Online,
                protocolVersion = peerProto,
            ),
            hostAddress = host,
            port = announcement.port,
            deviceKind = FlashDeviceKind.fromCapabilities(announcement.identity.capabilities),
            // Diagnostics only: unlike DNS-SD there is no service instance on the wire, so this is
            // the peer's own name, which is what a human reading a log expects to see anyway.
            serviceName = announcement.identity.friendlyName.ifBlank { deviceId.value },
        )

        // The state transition — and only it — under the lock: the directory dedup, the lease, and
        // the decision to answer. Everything below (emitting, sending) happens outside it.
        val outcome = lock.withLock {
            val known = leases[deviceId]
            val diff = directory.applySeen(endpoint, now)
            leases[deviceId] = (known ?: PeerLease(endpoint.serviceName, now))
                .also { it.lastSeenAtMs = now }
            // First sighting of a device we did not know: it may have just joined and not yet heard
            // us, so answer shortly. Bounded to first contact only, which is what keeps this from
            // becoming a per-announcement reply storm between two devices.
            diff to (known == null && advertising)
        }
        emitDiff(outcome.first, endpoint, sourceBinding)
        if (outcome.second) replyToNewPeer()
    }

    // -- Announcement ----------------------------------------------------------

    /** Sends one announcement on every bound interface. No-op while not advertising. */
    public fun announceNow() {
        // Encode and snapshot under the lock; SEND outside it. A socket write is the one thing here
        // that can block, and a blocked send must not hold up discovery state.
        val outbound = lock.withLock {
            val identity = lastIdentity
            if (!advertising || identity == null) {
                null
            } else {
                lastAnnounceAtMs = timeSourceMs()
                bindings to MulticastProtocol.encode(
                    // The mode's capability flags belong to whoever is announcing right now, not to
                    // the identity recorded at start-up: a mode change has to be visible to peers
                    // immediately.
                    identity.copy(capabilities = identity.capabilities + modePolicy.capabilityFlags),
                    lastAdvertisedPort,
                ).encodeToByteArray()
            }
        } ?: return
        for (binding in outbound.first) {
            if (!binding.send(outbound.second)) {
                logWarn("Announce failed on ${binding.label}", null)
            }
        }
    }

    /**
     * Answers an announcement from a peer we had not seen before, after a small random delay.
     *
     * The delay is not decoration: if every listener replied at once, a device joining a busy network
     * would produce a burst of simultaneous replies. The suppression window keeps two devices that
     * discover each other in the same instant from trading replies indefinitely.
     */
    private fun replyToNewPeer() {
        if (lock.withLock { timeSourceMs() - lastAnnounceAtMs < REPLY_SUPPRESS_MS }) return
        val active = scope ?: return
        active.launch(dispatcher) {
            delay(1L + random.nextLong(REPLY_MAX_JITTER_MS))
            announceNow()
        }
    }

    // -- Lease expiry ----------------------------------------------------------

    /**
     * Expires peers whose lease ran out: no announcement for [peerLeaseMs], so three consecutive
     * announcements were missed.
     *
     * This is the mechanism the DNS-SD transports lack. mDNS cannot say "this peer is gone" without
     * either a goodbye the dead process never sent or a cache TTL nobody controls; here the peer's
     * own cadence is the contract, so its absence is evidence.
     */
    internal fun sweepLeases(nowMs: Long = timeSourceMs()) {
        val expired = lock.withLock {
            val due = leases.filterValues { nowMs - it.lastSeenAtMs >= peerLeaseMs }.keys.toList()
            due.mapNotNull { deviceId ->
                val lease = leases.remove(deviceId) ?: return@mapNotNull null
                directory.applyLost(deviceId)
                deviceId to lease
            }
        }
        for ((deviceId, lease) in expired) {
            logInfo("Multicast lease expired for ${lease.serviceName} id=${deviceId.value}")
            emitEvent(FlashTransportEvent.Lost(deviceId, lease.serviceName))
        }
    }

    // -- Loops -----------------------------------------------------------------

    private fun startReceiveLoops() {
        val active = scope ?: return
        browseJob?.cancel()
        // Snapshot under the lock: these are the SAME loops whose parallel execution is why the lock
        // exists, so reading the list they were built from has to be guarded too.
        val sockets = lock.withLock { bindings }
        browseJob = active.launch(dispatcher) {
            val loops = sockets.map { binding ->
                launch(dispatcher) {
                    while (isActive && browsing) {
                        val datagram = runCatching { binding.receive(RECEIVE_TIMEOUT_MS) }
                            .getOrElse { error ->
                                // A transient receive error is not a dead socket (on Windows the
                                // next receive surfaces a previous send's ICMP error).
                                logWarn("Receive failed on ${binding.label}: ${error.message}", error)
                                null
                            }
                        if (datagram != null) handleDatagram(datagram, binding.label) else delay(receiveRetryDelayMs)
                    }
                }
            }
            loops.forEach { it.join() }
        }
    }

    private fun startAnnounceLoop(burst: Boolean) {
        val active = scope ?: return
        announceJob?.cancel()
        announceJob = active.launch(dispatcher) {
            if (burst) {
                // A burst, not one datagram: the first is easily lost, and a peer that has just
                // started may not be listening yet. Same shape LocalSend uses.
                for (gap in ANNOUNCE_BURST_GAPS_MS) {
                    delay(gap)
                    if (!advertising) return@launch
                    announceNow()
                }
            }
            while (isActive && advertising) {
                delay(nextAnnounceDelayMs())
                if (!advertising) return@launch
                announceNow()
            }
        }
    }

    /** [announceIntervalMs] ±20 %: devices that start together must not stay in lockstep for ever. */
    private fun nextAnnounceDelayMs(): Long {
        val jitter = (announceIntervalMs * ANNOUNCE_JITTER_FRACTION).toLong()
        return max(1L, announceIntervalMs + random.nextLong(-jitter, jitter + 1))
    }

    private fun startSweepLoop() {
        val active = scope ?: return
        sweepJob?.cancel()
        sweepJob = active.launch(dispatcher) {
            while (isActive && browsing) {
                delay(sweepIntervalMs)
                recoverBindingsIfNeeded()
                sweepLeases()
            }
        }
    }

    /**
     * Re-attempts a bind that has not succeeded yet, and starts the receive loops once it does.
     *
     * This is the repair half of the tolerance in [startBrowsing]: neither `startAdvertising` nor
     * `startBrowsing` returns a bind failure, because `CompositeDiscovery.startAll` aggregates every
     * transport's outcome into one result and both hosts turn a failed `startAll` into a failure to
     * boot — `check(...)` in the app's `DiscoveryEngineHolder`, and the desktop engine's equivalent.
     * A host can legitimately have no usable interface at boot: Wi-Fi off, cellular only, a VPN
     * holding the default route, an adapter still coming up. This transport runs ALONGSIDE the
     * DNS-SD pair rather than instead of them, so it must never be the reason a stack that worked
     * yesterday refuses to start today.
     *
     * The two directions are not symmetric, which is why the tolerant one wins: here the receive
     * loops simply have no sockets to read for a while, whereas the strict direction is an app that
     * will not start because a radio was off.
     *
     * Nothing external has to notice the recovery: a phone whose Wi-Fi was off at boot starts
     * hearing peers the moment an interface exists. Cheap while it keeps failing — with no candidate
     * interface the attempt is an enumeration that returns nothing.
     */
    internal fun recoverBindingsIfNeeded() {
        if (!browsing || lock.withLock { bindings.isNotEmpty() }) return
        if (ensureBound() is FlashResult.Failure) return
        // Bound at last. The receive loops were started against an EMPTY socket list (they had
        // nothing to read, and the job they ran in completed), so they have to be started again now
        // that there are sockets.
        startReceiveLoops()
        emitState("Listening on $group:$port (udp)")
    }

    private fun stopLoops() {
        browseJob?.cancel()
        browseJob = null
        announceJob?.cancel()
        announceJob = null
        sweepJob?.cancel()
        sweepJob = null
    }

    // -- Sockets ---------------------------------------------------------------

    /**
     * Binds the sockets if they are not bound yet, under [MulticastTransport.lock].
     *
     * Atomic on purpose: `startAll` calls advertising and browsing back to back, the sweep retry runs
     * on its own lane, and two of those racing could otherwise both call `socketFactory.bind()` — the
     * second set of sockets would then be unreferenced and leak until the process died. The lock is
     * not held across a receive; a bind is a short sequence of syscalls.
     */
    private fun ensureBound(): FlashResult<Unit> = lock.withLock {
        if (bindings.isNotEmpty()) {
            FlashResult.Success(Unit)
        } else {
            val bound = runCatching { socketFactory.bind(group, port) }.getOrElse { error ->
                logWarn("Multicast bind failed: ${error.message}", error)
                emptyList()
            }
            if (bound.isEmpty()) {
                // Once per degradation episode, not once per retry: the sweep re-attempts every few
                // seconds, and a state event and a log line per attempt would be pure noise.
                if (!bindDegraded) {
                    bindDegraded = true
                    emitState("No usable interface for multicast on $group:$port — retrying")
                }
                // A failed bind is exactly when the factory's process-wide resource must not be left
                // held: on Android that is the chipset-wide multicast lock, and a transport that never
                // started would otherwise keep filtering multicast in hardware for the whole process.
                // `close()` is idempotent for both implementations, so this is safe on a retry.
                runCatching { socketFactory.close() }
                FlashResult.Failure(
                    FlashError.NetworkUnavailable("no usable multicast interface on this host"),
                )
            } else {
                if (bindDegraded) {
                    bindDegraded = false
                    logInfo("Multicast interface became usable after a degraded start")
                }
                bindings = bound
                logInfo("Multicast bound on ${bound.joinToString { it.label }} ($group:$port)")
                FlashResult.Success(Unit)
            }
        }
    }

    private fun closeBindings() {
        // Close outside the lock: closing a socket is I/O-ish and must not stall the receive loops
        // that are trying to take the lock to record a datagram.
        val closing = lock.withLock {
            val current = bindings
            bindings = emptyList()
            current
        }
        closing.forEach { runCatching { it.close() } }
        // The factory may hold a process-wide resource for the bindings (Android's chipset-wide
        // multicast lock); releasing it here is what keeps a stopped transport from holding it.
        runCatching { socketFactory.close() }
    }

    // -- Plumbing --------------------------------------------------------------

    private fun ensureScope() {
        if (scope?.isActive == true) return
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    private fun emitDiff(diff: EndpointDirectory.Diff, endpoint: FlashDiscoveredEndpoint, sourceBinding: String?) {
        when (diff) {
            is EndpointDirectory.Diff.Found -> {
                logInfo(
                    "Found ${endpoint.friendlyName} at ${endpoint.hostAddress}:${endpoint.port}" +
                        (sourceBinding?.let { " via $it" } ?: ""),
                )
                emitEvent(FlashTransportEvent.Found(endpoint))
            }
            is EndpointDirectory.Diff.Updated -> emitEvent(FlashTransportEvent.Updated(endpoint))
            is EndpointDirectory.Diff.Lost -> emitEvent(
                FlashTransportEvent.Lost(endpoint.deviceId, endpoint.serviceName),
            )
            // Unchanged means the radio re-confirmed a peer with no field change — the case a
            // TTL-aged consumer reads as death unless it is told otherwise. Silence here is the
            // historical "peer vanishes after the grace window" bug, so this MUST emit.
            EndpointDirectory.Diff.Unchanged -> emitEvent(FlashTransportEvent.Presence(endpoint))
        }
    }

    private fun emitState(message: String) {
        emitEvent(FlashTransportEvent.StateChanged(browsing, message))
    }

    private fun emitEvent(event: FlashTransportEvent) {
        _events.tryEmit(event)
    }

    public companion object {
        /** Transport identifier for `CompositeDiscovery`'s priority/dedup reporting. */
        public const val TRANSPORT_NAME: String = "multicast"

        /**
         * Multicast group for Flash announcements.
         *
         * Inside `224.0.0.0/24` for a measured reason LocalSend documents as well: on some Android
         * devices this is the only range that can receive UDP multicast. `224.0.0.167` is
         * LocalSend's own group, so this is a DIFFERENT address — both apps can run on one LAN
         * without reading each other's traffic.
         */
        public const val DEFAULT_GROUP: String = "224.0.0.168"

        /** Distinct from the WebSocket port (45822) so the two never contend for a socket. */
        public const val DEFAULT_PORT: Int = 45823

        /** Cadence of the announcement that renews our lease on every peer's list. */
        public const val DEFAULT_ANNOUNCE_INTERVAL_MS: Long = 20_000L

        /**
         * How long a peer survives without hearing from it: three missed announcements at the
         * default cadence. Long enough that one dropped datagram (or one skipped announce while a
         * screen is off) never evicts a live peer, short enough that a peer which died without a
         * goodbye is gone within a minute — the DNS-SD path needs an hour for the same case.
         */
        public const val DEFAULT_PEER_LEASE_MS: Long = 60_000L

        public const val DEFAULT_SWEEP_INTERVAL_MS: Long = 5_000L

        /** Blocking receive timeout: bounds how long [stop]/[restartBrowsing] wait for a loop. */
        internal const val RECEIVE_TIMEOUT_MS: Int = 1_000

        /** Pause after an empty receive, so a failing socket cannot be polled in a tight loop. */
        internal const val RECEIVE_RETRY_DELAY_MS: Long = 50L

        /** Delays of the start-up announcement burst. */
        private val ANNOUNCE_BURST_GAPS_MS: List<Long> = listOf(0L, 300L, 1_000L)

        /** Do not answer a new peer if we announced this recently. */
        private const val REPLY_SUPPRESS_MS: Long = 3_000L

        /** Upper bound of the random delay before answering a new peer. */
        private const val REPLY_MAX_JITTER_MS: Long = 500L

        private const val ANNOUNCE_JITTER_FRACTION: Double = 0.2
        private const val EVENT_BUFFER: Int = 64
        private const val SHORT_ID: Int = 8
        internal const val TAG: String = "MulticastTransport"
    }
}
