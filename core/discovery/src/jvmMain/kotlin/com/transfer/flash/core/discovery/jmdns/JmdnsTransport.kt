@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.jmdns

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
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.FlashRadioTransport
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import com.transfer.flash.core.discovery.core.TxtCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

/**
 * Desktop-side TXT codec. Encode DELEGATES to the shared [TxtCodec] in `commonMain` so the wire
 * format has exactly one size-guarded encoder (R8: `TxtCodec` is called, never modified).
 *
 * Decode is an independent TOLERANT parser, byte-for-byte the same contract as `androidMain`'s
 * `NsdTxtCodec.decode`: a missing `name` falls back to the service name, a missing `proto` falls
 * back to our version, and missing `caps`/`fp8` degrade to `emptySet`/null. The shared
 * [TxtCodec.decode] is STRICT — it returns null when `proto` is absent — so using it here would
 * make the desktop hide pre-P3.5 advertisers that Android shows. Phase 16 tests desktop↔Android
 * parity, so the two decoders must agree.
 *
 * Duplicated rather than hoisted for the same reason `PlatformLock` is duplicated per module
 * (CONVENTIONS R2): `NsdTxtCodec` is `internal` in `androidMain`, and `androidMain`/`jvmMain` are
 * siblings with no `dependsOn` edge, so `jvmMain` cannot see it. Hoisting the tolerant decoder
 * into `commonMain` would additionally mean editing an `androidMain` file, which PHASE-14
 * forbids. Logged under Known issues.
 */
internal object JmdnsTxtCodec {

    data class ParsedIdentity(
        val deviceId: String?,
        val friendlyName: String?,
        val deviceModel: String?,
        val protocolVersion: Int?,
        /** Informational only (unauthenticated wire); see FlashAdvertisedIdentity KDoc. */
        val capabilities: Set<String> = emptySet(),
        val fingerprintPrefix: String? = null,
    )

    fun encode(identity: FlashAdvertisedIdentity): Map<String, String> = TxtCodec.encode(identity)

    fun decode(
        attributes: Map<String, String>,
        fallbackName: String,
        fallbackProto: Int,
    ): ParsedIdentity = ParsedIdentity(
        deviceId = attributes[TxtCodec.KEY_DEVICE_ID]?.trim()?.takeIf { it.isNotEmpty() },
        friendlyName = attributes[TxtCodec.KEY_NAME]?.takeIf { it.isNotBlank() } ?: fallbackName,
        deviceModel = attributes[TxtCodec.KEY_MODEL]?.takeIf { it.isNotBlank() },
        protocolVersion = attributes[TxtCodec.KEY_PROTO]?.trim()?.toIntOrNull() ?: fallbackProto,
        capabilities = attributes[TxtCodec.KEY_CAPS]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet(),
        fingerprintPrefix = attributes[TxtCodec.KEY_FP8]?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * Desktop mDNS/DNS-SD [FlashRadioTransport], the `jvmMain` counterpart of `androidMain`'s
 * `NsdTransport`. Advertises `_flash-transfer._tcp.` and browses for it continuously, so a
 * desktop and an Android device on the same LAN see each other with no configuration (the
 * Phase 16 gate).
 *
 * ### Contract obligations this class honours, and why each is NOT optional
 *
 * PHASE-14's "Do NOT" list says to add no presence detection and no event deduplication, and its
 * sample class emits neither [FlashTransportEvent.Presence] nor
 * [FlashTransportEvent.StateChanged] and overrides no [restartBrowsing]. Measuring the actual
 * consumer shows all three are load-bearing, so all three are implemented and the deviations are
 * recorded in the phase log:
 *
 * - **Presence.** `FlashTransportEvent.Presence`'s KDoc: *"Emitting this is MANDATORY for any
 *   transport whose consumer ages peers out on a TTL."* `CompositeDiscovery` runs an internal
 *   sweeper every `DEFAULT_SWEEP_INTERVAL_MS` (5 s) that evicts anything unseen for
 *   `DEFAULT_GRACE_MS` (30 s). A transport that only ever emits `Found` therefore loses every
 *   peer ~30 s in and never recovers it — the exact field-reported bug that put the `Presence`
 *   variant in the interface.
 * - **restartBrowsing.** `CompositeDiscovery.watchdogBrowsing()` calls
 *   `transport.restartBrowsing()` for a transport stalled ≥ `DEFAULT_BROWSE_WATCHDOG_MS`. The
 *   interface default delegates to `startBrowsing()`, which returns early while `browsing` is
 *   true — so without an override the watchdog is a silent no-op and a responder killed by a
 *   Wi-Fi↔hotspot switch never comes back.
 * - **StateChanged.** It is the ONLY input to `CompositeDiscovery.applyBrowseState()`, which sets
 *   both `state.isDiscovering` and the stall stamps the watchdog reads. No `StateChanged` means
 *   the watchdog above can never fire in the first place.
 *
 * Adding them is not "also fixing things" (R1): a transport that omits them does not satisfy
 * [FlashRadioTransport], and R2 forbids stubbing to force a compile.
 *
 * ### Deliberate differences from `NsdTransport`
 *
 * - **No multicast lock, no API-level split, no connectivity callback.** Those are Android
 *   platform concerns; JmDNS owns its own sockets and there is no desktop equivalent.
 * - **ECO duty-cycling is not implemented.** [setMode] honours the advertise toggle (GHOST) and
 *   the restart-backoff base (BOOST) but browses continuously in every mode: the knob exists to
 *   spare a phone's battery, and `FlashRadioTransport.setMode` says implementations *should*
 *   honour duty cycle, not must. Documented rather than silently dropped.
 * - **`transportName` is `"jmdns"`**, mirroring the sibling's `"nsd"`. See the property KDoc.
 */
public class JmdnsTransport(
    private val directory: EndpointDirectory,
    /** Engine-wired TTL sweep, drained on every presence tick (plan C3.5). */
    private val sweep: (nowMs: Long) -> List<EndpointDirectory.Diff.Lost>,
    private val serviceType: String = DEFAULT_SERVICE_TYPE,
    private val instancePrefix: String = "Flash",
    private val retryDelayMs: (Int) -> Long = JmdnsRestartPolicy.exponentialBackoff,
    private val maxBrowsingRestarts: Int = DEFAULT_MAX_RESTARTS,
    initialModePolicy: DiscoveryModePolicy = DiscoveryModePolicy.forMode(
        FlashDiscoveryMode.STANDARD,
    ),
    /** Injected clock — tests drive a fake timeline instead of sleeping. */
    private val timeSourceMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val presenceSleep: suspend (Long) -> Unit = { delay(it) },
    /** Bound on presence ticks per browse session — determinism hook, as in `NsdTransport`. */
    private val maxPresenceTicks: Int = Int.MAX_VALUE,
    /**
     * Presence heartbeat period. MUST stay well below `CompositeDiscovery.DEFAULT_GRACE_MS`
     * (30 s) so a live peer is refreshed several times per grace window and one missed tick
     * cannot evict it.
     */
    private val presenceHeartbeatMs: Long = DEFAULT_PRESENCE_HEARTBEAT_MS,
    /**
     * Grace window before a JmDNS `serviceRemoved` becomes a typed [FlashTransportEvent.Lost].
     * mDNS over Wi-Fi re-announces constantly; without the window a single transient goodbye
     * flaps a peer out of the directory. Tests pass 0 for synchronous assertions.
     */
    private val lostDebounceMs: Long = DEFAULT_LOST_DEBOUNCE_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logInfo: (String) -> Unit = { FlashLog.i(TAG, it) },
    private val logWarn: (String) -> Unit = { FlashLog.w(TAG, it) },
    /** Test seam. Production leaves it null and gets [RealJmdnsBridge]. */
    bridgeOverride: JmdnsBridge? = null,
) : FlashRadioTransport {

    private val bridge: JmdnsBridge = bridgeOverride ?: RealJmdnsBridge(
        logWarn = { message, error -> logWarn("$message: ${error?.message ?: "?"}") },
    )

    /**
     * Radio-implementation name, matching the sibling transport's `"nsd"` rather than the
     * `"jmds-lan"` PHASE-14 proposes.
     *
     * `CompositeDiscovery` uses this string for two things: the per-transport directory key, and
     * `priorityRank()`, which looks the UPPERCASED name up in
     * `PRIORITY_ORDER = ["LAN","WIFI_DIRECT","WIFI_AWARE","BLE"]` and returns `size` (worst) on a
     * miss. `"nsd"` already misses, so `"jmdns"` keeps the two LAN transports ranked identically
     * and cross-platform dedup behaviour symmetric — which is what the Phase 16 gate checks.
     * Naming this one `"LAN"` would rank desktop first and Android last for the same radio.
     * (That `PRIORITY_ORDER` never matches the LAN transport at all is a pre-existing defect in
     * `androidMain`; it is out of scope here and logged under Known issues.)
     */
    override val transportName: String = "jmdns"

    private val _events = MutableSharedFlow<FlashTransportEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<FlashTransportEvent> = _events.asSharedFlow()

    @Volatile private var ownDeviceId: FlashDeviceId? = null

    /** Per-service re-resolve budget for JmDNS's empty-TXT resolution; lane-confined. */
    private val emptyTxtRetries = mutableMapOf<String, EmptyTxtRetryState>()

    /**
     * Service names JmDNS has already been asked to resolve, so a repeated announcement does not
     * ask again. See [requestResolveOffLane] for why a repeat is harmful rather than merely
     * redundant.
     *
     * **Concurrent, not lane-confined** — unlike the two maps above. `onServiceAdded` runs on a
     * JmDNS callback thread, not on [lane], and it is the caller that made this necessary.
     *
     * Bounded by [pruneResolveRequested], because service names are attacker-influenced wire data
     * and a peer re-registering on collision produces `Name (2)`, `Name (6)`, … for ever.
     */
    private val resolveRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The capability set last LOGGED for each service name; lane-confined.
     *
     * Exists only to keep [logInfo]'s "Peer capabilities" line from repeating. It is not
     * state anything reads — the parsed capabilities themselves are used directly.
     */
    private val loggedCapabilities = mutableMapOf<String, String>()

    /**
     * Retry bookkeeping for one service name that keeps resolving with JmDNS's empty TXT.
     *
     * [attempts] counts the re-resolves used in the CURRENT cycle and [lastAttemptAtMs] is when the
     * last one was issued — the pair is what makes the budget time-bounded. See
     * [emptyTxtDecision] for why a bare counter is not enough.
     */
    private class EmptyTxtRetryState(var attempts: Int = 0, var lastAttemptAtMs: Long = 0L)

    /** What to do with a service that resolved with JmDNS's empty TXT; see [emptyTxtDecision]. */
    private enum class EmptyTxtDecision { Retry, Suppressed, Drop }
    @Volatile private var advertising = false
    @Volatile private var browsing = false
    @Volatile private var opened = false
    @Volatile private var restartAttempt = 0
    @Volatile private var modePolicy: DiscoveryModePolicy = initialModePolicy
    @Volatile private var lastAdvertisedIdentity: FlashAdvertisedIdentity? = null
    @Volatile private var lastAdvertisedPort: Int = 0

    /**
     * Owns its own scope for the same reason `NsdTransport` does: the radio lifecycle IS the
     * scope lifetime, there is no outer owner below the engine, and callbacks surviving [stop]
     * would break the Found/Lost contract.
     */
    private var scope: CoroutineScope? = null

    /**
     * Serial lane guarding [directory] and the bookkeeping maps below — `EndpointDirectory` is
     * explicitly not thread-safe and JmDNS delivers callbacks on its own threads. Falls back to
     * the raw dispatcher when it rejects limited views (`Dispatchers.Unconfined` in tests).
     */
    private val lane: CoroutineDispatcher = try {
        dispatcher.limitedParallelism(1)
    } catch (_: UnsupportedOperationException) {
        dispatcher
    }

    /** serviceName → deviceId, so a JmDNS goodbye can produce a typed Lost. Lane-confined. */
    private val deviceIdsByServiceName = HashMap<String, FlashDeviceId>()

    /**
     * serviceNames JmDNS currently vouches for: added on a successful resolve, removed when a
     * debounced removal fires. This is the desktop analogue of `NsdTransport.monitoredServices`
     * and it is what makes the presence heartbeat honest — a tick only re-affirms peers the
     * radio has actually confirmed, never every row in the directory.
     */
    private val vouchedServices = HashSet<String>()

    /** Debounced removals in flight, keyed by serviceName. A re-resolve cancels one. */
    private val pendingLost = HashMap<String, Job>()

    private var heartbeatJob: Job? = null

    /**
     * JmDNS delivers callbacks on its own threads, so every handler here either hops onto the
     * [lane] (directory work) or onto [dispatcher] (blocking JmDNS calls). Nothing touches shared
     * state on a JmDNS thread.
     */
    private val browseEvents = object : JmdnsBrowseEvents {
        override fun onServiceAdded(serviceType: String, serviceName: String) {
            // An announcement carries no address and no TXT data. Resolution is a BLOCKING JmDNS
            // query, so it must not run on this callback thread (JmDNS would deadlock against its
            // own responder) nor on the serial lane (it would stall every directory update).
            requestResolveOffLane(serviceName)
        }

        override fun onServiceRemoved(serviceName: String) {
            handleServiceRemoved(serviceName)
        }

        override fun onServiceResolved(service: JmdnsResolvedService) {
            scope?.launch(lane) { handleServiceResolved(service) }
        }
    }

    // -- Advertising -----------------------------------------------------------

    override suspend fun startAdvertising(
        port: Int,
        identity: FlashAdvertisedIdentity,
    ): FlashResult<Unit> {
        // Recorded even in GHOST so exiting GHOST can resume, and so the self-filter knows our
        // own id the moment the engine tells us who we are.
        ownDeviceId = identity.deviceId
        lastAdvertisedIdentity = identity
        lastAdvertisedPort = port
        if (!modePolicy.advertises) {
            logInfo("Mode ${modePolicy.mode} suppresses advertising; request recorded only")
            emitState("Advertising suppressed (${modePolicy.mode})")
            return FlashResult.Success(Unit)
        }
        ensureScope()
        ensureOpen()?.let { return it }
        val request = JmdnsAdvertiseRequest(
            serviceType = serviceType,
            serviceName = "$instancePrefix ${identity.friendlyName.take(MAX_NAME_LENGTH)}",
            port = port,
            attributes = JmdnsTxtCodec.encode(identity),
        )
        return runCatching { bridge.register(request) }.fold(
            onSuccess = {
                advertising = true
                emitState("Advertising as ${request.serviceName}")
                FlashResult.Success(Unit)
            },
            onFailure = { error ->
                advertising = false
                emitState("Advertising failed: ${error.message}")
                FlashResult.Failure(FlashError.Unknown("mDNS registration failed", error))
            },
        )
    }

    override suspend fun setMode(policy: DiscoveryModePolicy) {
        val previous = modePolicy
        modePolicy = policy
        if (previous.advertises == policy.advertises) return
        if (!policy.advertises) {
            if (advertising) {
                runCatching { bridge.unregisterAll() }
                advertising = false
                emitState("Advertising suppressed (${policy.mode})")
            }
        } else if (!advertising) {
            val identity = lastAdvertisedIdentity ?: return // never advertised; nothing to resume
            startAdvertising(lastAdvertisedPort, identity)
        }
    }

    // -- Browsing --------------------------------------------------------------

    override suspend fun startBrowsing(): FlashResult<Unit> {
        if (browsing) return FlashResult.Success(Unit)
        ensureScope()
        ensureOpen()?.let { failure ->
            scheduleBrowseRestart()
            return failure
        }
        return runCatching { bridge.startBrowse(serviceType, browseEvents) }.fold(
            onSuccess = {
                browsing = true
                restartAttempt = 0
                emitState("Browsing $serviceType")
                startHeartbeat()
                FlashResult.Success(Unit)
            },
            onFailure = { error ->
                browsing = false
                emitState("Browsing failed: ${error.message}")
                scheduleBrowseRestart()
                FlashResult.Failure(FlashError.Unknown("mDNS browse failed", error))
            },
        )
    }

    /**
     * Full rebind, not a listener re-add.
     *
     * A responder bound to an address that has gone away — Wi-Fi ↔ hotspot switch, VPN coming up,
     * cable pulled — keeps reporting itself as healthy while its socket receives nothing. Removing
     * and re-adding a listener on that same dead responder changes nothing, so recovery closes
     * every responder and enumerates interfaces again.
     */
    override suspend fun restartBrowsing(): FlashResult<Unit> {
        logInfo("Forcing mDNS browse restart")
        val wasAdvertising = advertising
        browsing = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { bridge.stopBrowse(serviceType) }
        runCatching { bridge.close() }
        opened = false
        advertising = false
        val result = startBrowsing()
        // close() dropped the advertisement along with the responders that held it.
        val identity = lastAdvertisedIdentity
        if (wasAdvertising && identity != null) startAdvertising(lastAdvertisedPort, identity)
        return result
    }

    private fun scheduleBrowseRestart() {
        val active = scope ?: return
        val attempt = ++restartAttempt
        if (attempt > maxBrowsingRestarts) {
            logWarn("mDNS browse restart budget exhausted after $maxBrowsingRestarts attempts")
            emitState("Browsing gave up after $maxBrowsingRestarts restarts")
            return
        }
        active.launch(lane) {
            sleep(effectiveRetryDelayMs(attempt))
            if (browsing) return@launch
            restartBrowsing()
        }
    }

    /** Retry delay honouring the active policy's base (BOOST lowers it). */
    private fun effectiveRetryDelayMs(attempt: Int): Long =
        retryDelayMs(attempt) * modePolicy.restartBackoffBaseMs /
            DiscoveryModePolicy.DEFAULT_BACKOFF_BASE_MS

    // -- Resolution handling + directory diff mapping --------------------------

    /**
     * Mirrors `NsdTransport.handleServiceUpdated` step for step, because the two transports must
     * accept and reject exactly the same peers for the Phase 16 interop gate to mean anything.
     */
    private fun handleServiceResolved(data: JmdnsResolvedService) {
        if (!browsing) return
        val parsed = JmdnsTxtCodec.decode(
            attributes = data.attributes,
            fallbackName = data.serviceName,
            fallbackProto = FlashProtocol.VERSION,
        )
        val deviceIdString = parsed.deviceId ?: run {
            // An EMPTY TXT is not the same thing as "this peer published no attributes", and
            // treating it as one is what made the desktop invisible on 2026-09-13.
            //
            // JmDNS 3.5.12 delivers a ServiceInfo whose text is `ByteWrangler.EMPTY_TXT`
            // (`new byte[]{0}`, ByteWrangler.java:43) whenever the TXT record has not reached its
            // cache by the time the service is assembled — and its own recovery path for that case
            // is DEAD CODE:
            //
            //   ServiceInfoImpl.getTextBytes() (ServiceInfoImpl.java:545) returns EMPTY_TXT, one
            //   byte, whenever `_text` is null. So the guard
            //     if (cachedInfo.getTextBytes().length == 0) cachedInfo._setText(srvBytes);
            //   in JmDNSImpl.getServiceInfo (JmDNSImpl.java:834) can never be true, and the
            //   SRV-derived text fallback never runs. The ServiceInfo is built with `(byte[]) null`
            //   text at :797 and its A/AAAA loops overwrite `_text` with EMPTY_TXT at :810/:823.
            //
            // `ServiceInfoImpl.hasData()` only checks `getTextBytes().length > 0` — one byte
            // satisfies it — so the hollow ServiceInfo is delivered as a successful resolution,
            // with a correct address and port and no attributes at all. Measured: `txtBytes=1`
            // against the app's OWN name and port.
            //
            // The record itself is fine; it simply has not been seen yet. So retry a bounded
            // number of times instead of dropping the peer permanently — JmDNS caches the TXT when
            // it arrives, and the next resolution then carries it. `requestResolveInfo` blocks for
            // its own timeout, which spaces the attempts naturally.
            //
            // "Bounded" has to mean bounded in TIME, not per delivery: our own re-resolve makes
            // JmDNS re-deliver the same cached, attribute-less ServiceInfo, so the decision is
            // asked again immediately, and a bare attempt counter that resets on exhaustion
            // re-armed a fresh burst on the very next delivery. That was an unbounded
            // resolve→drop→resolve loop — one blocking JmDNS query per attempt, on a fresh
            // dispatcher task each time — and it pegged a core and grew the heap for as long as a
            // hollow record stayed in the cache. `emptyTxtDecision` now rate-limits the cycle.
            if (data.txtByteCount <= 1 && data.attributes.isEmpty()) {
                // ONCE THIS NAME HAS RESOLVED FOR REAL, never retry a hollow delivery again.
                //
                // The budget below is per CYCLE, and a cycle is reset by a successful resolve —
                // `emptyTxtRetries.remove(serviceName)` on the success path just above. So a peer
                // whose real and hollow records ALTERNATE re-armed the budget on every good
                // delivery, and the "bounded" retry ran unbounded. Measured 2026-09-14: 633,997
                // resolve requests in ~50 seconds from two service names, every one of them
                // `force=true` on this branch, which is what put 62 threads inside
                // `JmDNSImpl.addListener` and held the app at ~670% of one core.
                //
                // A vouch is exactly the fact needed here: the radio resolved this instance with
                // real attributes, so the `persistent = true` subscription is registered and every
                // later announcement arrives on its own. A hollow re-delivery after that is a cache
                // artefact, not a failure to resolve, and re-asking fixes nothing.
                if (data.serviceName in vouchedServices) return
                when (emptyTxtDecision(data.serviceName, timeSourceMs())) {
                    EmptyTxtDecision.Retry -> {
                        // `force`: this name is already in `resolveRequested` (the first ask came
                        // from `onServiceAdded`), and the retry is the whole point of this branch.
                        // Safe to force because `emptyTxtDecision` bounds it to a handful of
                        // attempts per cooldown, so it cannot become the unbounded stream that the
                        // non-forced path exists to prevent.
                        requestResolveOffLane(data.serviceName, force = true)
                        return
                    }
                    // Inside the cooldown: this name has already had its retries and been
                    // reported. Re-deciding it on every re-delivery is exactly the loop above, so
                    // stay silent — one drop line per cooldown is the useful signal, not a
                    // hundred.
                    EmptyTxtDecision.Suppressed -> return
                    // Budget spent and the cooldown elapsed: fall through and report it once,
                    // which also starts the next cycle.
                    EmptyTxtDecision.Drop -> Unit
                }
            }
            // Dropped for real. KEYS ONLY, never values: a TXT record is unauthenticated wire data
            // from an arbitrary peer, so its contents do not belong in a log. The key set is
            // bounded and is the part that answers the question — `txtKeys=[]` means no attributes
            // arrived even after retrying, while a non-empty set without `device_id` means the peer
            // published under different names, and those have different fixes.
            logInfo(
                "Dropping mDNS endpoint without device_id name=${data.serviceName} " +
                    "host=${data.hostAddress} port=${data.port} " +
                    "txtKeys=${data.attributes.keys.sorted()} txtBytes=${data.txtByteCount}",
            )
            return
        }
        // Resolved with real attributes; forget any retry budget for this name so a later
        // re-announcement starts clean.
        emptyTxtRetries.remove(data.serviceName)
        val deviceId = runCatching { FlashDeviceId(deviceIdString) }.getOrElse {
            logWarn("Invalid device_id '$deviceIdString' from ${data.serviceName}")
            return
        }
        if (deviceId == ownDeviceId) return // self-advertisement filtered by IDENTITY (C3.2)

        // Pre-directory protocol gate (P3.5-A4). Tolerance matches Android exactly: a MISSING
        // proto falls back to our version (legacy advertisers stay visible), an EXPLICIT
        // different version is a hard drop.
        val peerProto = parsed.protocolVersion ?: FlashProtocol.VERSION
        if (!FlashProtocol.isCompatible(peerProto)) {
            logWarn(
                "Dropping mDNS endpoint with proto=$peerProto " +
                    "(want ${FlashProtocol.VERSION}) name=${data.serviceName}",
            )
            return
        }
        if (parsed.capabilities.isNotEmpty()) {
            // Informational on an unauthenticated wire (RFC 6762); enforcement is at connect time.
            //
            // Logged once per service, and again only if the set actually changes — NOT on every
            // resolve. This used to fire on each one, and the same resolution is re-delivered
            // constantly: `presenceTick` re-queries every vouched peer every
            // [DEFAULT_PRESENCE_HEARTBEAT_MS] with `persistent = true`, which makes JmDNS hand back
            // the cached record immediately. So a peer that was simply sitting there printed this
            // line for ever, burying every real log line behind it (reported 2026-09-14 as "it's
            // looping"). The set is static for a running peer, so once is the whole value.
            val capsSummary = parsed.capabilities.sorted().joinToString(",")
            if (loggedCapabilities[data.serviceName] != capsSummary) {
                pruneLoggedCapabilities()
                loggedCapabilities[data.serviceName] = capsSummary
                logInfo("Peer capabilities caps=$capsSummary name=${data.serviceName}")
            }
        }
        val hostAddress = data.hostAddress ?: return // resolved without an address; wait for more
        val endpoint = FlashDiscoveredEndpoint(
            device = FlashDevice(
                id = deviceId,
                friendlyName = parsed.friendlyName ?: data.serviceName,
                transportType = FlashTransportType.LAN,
                presence = FlashPeerPresence.Online,
                protocolVersion = parsed.protocolVersion ?: FlashProtocol.VERSION,
            ),
            hostAddress = hostAddress,
            port = data.port,
            serviceName = data.serviceName,
            deviceKind = FlashDeviceKind.fromCapabilities(parsed.capabilities),
        )
        val diff = directory.applySeen(endpoint, timeSourceMs())
        deviceIdsByServiceName[data.serviceName] = deviceId
        // A resolved service needs no further asks for as long as it stays in the directory; the
        // `persistent = true` subscription from the original request covers every later
        // announcement. Kept in the set so a re-announcement does not ask again — see
        // `requestResolveOffLane`.
        pruneResolveRequested()
        // A successful resolve is the radio vouching for this instance: it is what later presence
        // ticks are allowed to re-affirm, and it cancels any debounced removal in flight.
        vouchedServices += data.serviceName
        pendingLost.remove(data.serviceName)?.cancel()
        emitDiff(diff, endpoint)
    }

    private fun handleServiceRemoved(serviceName: String) {
        val active = scope ?: return
        active.launch(lane) {
            if (!browsing) return@launch
            // Debounce the goodbye: mDNS over Wi-Fi (especially through a phone hotspot) drops and
            // re-announces constantly, and an undebounced removal makes a peer flap out of the
            // directory on a transient. A re-resolve inside the window cancels this job. Always
            // replace any prior pending job so a stale one cannot suppress a fresh removal.
            pendingLost.remove(serviceName)?.cancel()
            val job = active.launch(lane) {
                sleep(lostDebounceMs)
                if (!browsing) return@launch
                pendingLost.remove(serviceName)
                // Withdraw the vouch BEFORE the directory bookkeeping, so a presence tick racing
                // this job cannot re-affirm a peer that is being evicted.
                vouchedServices -= serviceName
                loggedCapabilities.remove(serviceName)
                // Cleared so a peer that goes away and comes back is asked for again. Without this
                // the flag would outlive the sighting and a returning peer would never be resolved
                // a second time — the failure mode of making a retry once-only.
                resolveRequested.remove(serviceName)
                val deviceId = deviceIdsByServiceName.remove(serviceName) ?: return@launch
                // The typed Lost below already carries the serviceName, so the generic Diff.Lost
                // mapping is skipped — one radio goodbye must not produce two Lost events.
                directory.applyLost(deviceId)
                emitEvent(FlashTransportEvent.Lost(deviceId, serviceName))
            }
            pendingLost[serviceName] = job
        }
    }

    /**
     * Presence heartbeat. Re-affirms every endpoint the radio still vouches for and drains the
     * injected [sweep], on a period well inside `CompositeDiscovery`'s 30 s grace window.
     *
     * Without this the desktop transport emits `Found` once and then silence, and the consumer's
     * sweeper evicts the peer ~30 s later with no way back — see the class KDoc.
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope?.launch(lane) {
            var ticks = 0
            while (browsing && ticks < maxPresenceTicks) {
                presenceSleep(presenceHeartbeatMs)
                ticks++
                if (!browsing) break
                presenceTick()
            }
        }
    }

    /**
     * `internal`, not `private`: `jvmTest` is an associated compilation of `jvmMain`, so a test can
     * drive one tick directly instead of waiting on wall-clock heartbeats. Not part of the public
     * ABI, so `explicitApi()` and downstream consumers are unaffected.
     */
    internal fun presenceTick() {
        val now = timeSourceMs()
        directory.snapshot().forEach { entry ->
            val serviceName = entry.endpoint.serviceName
            if (serviceName !in vouchedServices) return@forEach
            emitEvent(FlashTransportEvent.Presence(entry.endpoint))
            // NO re-resolve here. There used to be a `requestResolveOffLane(serviceName)` on this
            // line, "belt and braces" so an address change would be picked up even if JmDNS's own
            // cache refresh did not fire. It was actively harmful, and the mechanism is worth
            // stating because it is invisible from the call site:
            //
            // `requestResolveOffLane` asks with `persistent = true`. JmDNS implements that by
            // building a fresh `ServiceInfoResolver` and ADDING A LISTENER to a synchronised
            // collection on every call — it does not replace one. So a request per peer per tick
            // accumulated a listener per peer per tick, for ever, and every one of them contends on
            // that collection's monitor.
            //
            // Measured on a two-peer LAN, 2026-09-14: 75 `DefaultDispatcher` workers, of which 42
            // were BLOCKED in `JmDNSImpl.addListener` and 15 in `JmDNSImpl.updateRecord`; the app sat
            // at 369% of one core and 5.19 GB committed heap while doing nothing but discovering.
            // Because those workers share `Dispatchers.Default` with the dial and session code, the
            // starvation also surfaced as `WS handshake timed out` and sessions appearing then
            // vanishing — i.e. it was the cause of the flaky pairing, not just of the CPU load.
            //
            // Dropping it loses nothing, which is the part that makes this a fix rather than a
            // trade: `persistent = true` already means ONE request per service keeps a listener
            // registered for every later announcement, so re-asking could only ever duplicate what
            // was already subscribed. The first resolve still comes from `onServiceAdded`, and a peer
            // whose TXT arrives late is still recovered by the bounded empty-TXT budget.
        }
        sweep(now).forEach { agedOut ->
            emitEvent(
                FlashTransportEvent.Lost(agedOut.deviceId, findServiceNameFor(agedOut.deviceId)),
            )
        }
    }

    /** Pulls engine-wired sweep results into Lost events on demand (parity with `NsdTransport`). */
    public fun pollSweep() {
        scope?.launch(lane) {
            sweep(timeSourceMs()).forEach { agedOut ->
                emitEvent(
                    FlashTransportEvent.Lost(
                        agedOut.deviceId,
                        findServiceNameFor(agedOut.deviceId),
                    ),
                )
            }
        }
    }

    private fun findServiceNameFor(deviceId: FlashDeviceId): String? =
        deviceIdsByServiceName.entries.firstOrNull { it.value == deviceId }?.key

    /**
     * Maps a directory diff onto the event flow. `Unchanged` becomes
     * [FlashTransportEvent.Presence] — the same sighting classified as liveness rather than
     * change — because emitting nothing there is precisely what makes stable peers vanish.
     */
    private fun emitDiff(diff: EndpointDirectory.Diff, endpoint: FlashDiscoveredEndpoint) {
        when (diff) {
            is EndpointDirectory.Diff.Found ->
                emitEvent(FlashTransportEvent.Found(diff.entry.endpoint))
            is EndpointDirectory.Diff.Updated ->
                emitEvent(FlashTransportEvent.Updated(diff.entry.endpoint))
            is EndpointDirectory.Diff.Lost ->
                emitEvent(FlashTransportEvent.Lost(diff.deviceId, findServiceNameFor(diff.deviceId)))
            EndpointDirectory.Diff.Unchanged ->
                emitEvent(FlashTransportEvent.Presence(endpoint))
        }
    }

    // -- Lifecycle -------------------------------------------------------------

    override suspend fun stop(): FlashResult<Unit> {
        browsing = false
        advertising = false
        emitState("Stopped")
        heartbeatJob?.cancel()
        heartbeatJob = null
        pendingLost.values.forEach { it.cancel() }
        // BEFORE the bridge is closed, not after: `opened = false` is what makes an in-flight
        // empty-TXT retry stand down (see requestResolveOffLane), and setting it afterwards leaves a
        // window in which a retry passes the check and then hits JmDNS's terminated executor.
        opened = false
        runCatching { bridge.stopBrowse(serviceType) }
        runCatching { bridge.unregisterAll() }
        runCatching { bridge.close() }
        scope?.cancel()
        scope = null
        // Scope is down, so nothing can touch these concurrently any more.
        pendingLost.clear()
        vouchedServices.clear()
        deviceIdsByServiceName.clear()
        // Drain the directory too. A stopped transport knows nothing; leaving entries behind means
        // the NEXT browse session re-sights the same peers, dedups them to Unchanged, and publishes
        // only Presence for peers the consumer was never told about.
        runCatching { directory.sweepExpired(graceWindowMs = 0L, nowMs = Long.MAX_VALUE) }
        restartAttempt = 0
        return FlashResult.Success(Unit)
    }

    // -- Helpers ---------------------------------------------------------------

    private fun ensureScope(): CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + dispatcher).also { scope = it }

    /** Binds the responders on demand. Returns the failure to propagate, or null on success. */
    private fun ensureOpen(): FlashResult<Unit>? {
        if (opened) return null
        return runCatching { bridge.open() }.fold(
            onSuccess = {
                opened = true
                null
            },
            onFailure = { error ->
                logWarn("mDNS open failed: ${error.message}")
                FlashResult.Failure(FlashError.NetworkUnavailable(error.message))
            },
        )
    }

    private fun requestResolveOffLane(serviceName: String, force: Boolean = false) {
        val active = scope ?: return
        // ONCE per service name, unless a caller explicitly forces a retry.
        //
        // This is the fix for the desktop's CPU and memory runaway, and the reason is not obvious
        // from here — it is a property of JmDNS. `requestServiceInfo` is asked with `persistent =
        // true`, and JmDNS implements that by constructing a fresh `ServiceInfoResolver` which ADDS
        // A LISTENER to a synchronised collection; it does not replace the previous one. So asking
        // again does not refresh a subscription, it stacks another one, for ever.
        //
        // `onServiceAdded` fires on EVERY announcement, not merely the first, and a phone announces
        // every few seconds. So this method was being called continuously for the lifetime of the
        // process. Measured 2026-09-14 with two peers on the LAN: 61 threads blocked in
        // `JmDNSImpl.addListener`, 129 live threads, 530% of one core, and 950 MB of a 1 GB heap —
        // for an app that was only discovering. The starvation also hit the WS handshake coroutines,
        // which share `Dispatchers.Default`, and that is what surfaced as `WS handshake timed out`
        // and peers appearing then vanishing: the flaky pairing had the same root cause as the load.
        //
        // Skipping the repeat costs nothing. `persistent = true` means the FIRST request keeps a
        // listener registered for every later announcement, so a second one can only duplicate what
        // is already subscribed. Recovery paths that genuinely need a re-ask pass `force = true`:
        // the bounded empty-TXT budget, and a service that was removed and announced again (which
        // clears its entry below).
        if (!force && !resolveRequested.add(serviceName)) return
        // A retry queued by the empty-TXT budget can outlive the browse session that scheduled it.
        // `bridge.close()` shuts JmDNS's own executor down, so a request that lands after it is
        // rejected with "Task ... rejected from ThreadPoolExecutor[Terminated]" — noise in the middle
        // of teardown, where it reads like a real failure. Observed 2026-09-14 in the interop
        // harness's exit, right after `[discover] window closed`.
        //
        // Checked on both sides of the launch: before it, so a closed bridge costs nothing, and
        // inside it, because the coroutine may be scheduled before `stop()` runs and executed after.
        if (!opened) return
        active.launch(dispatcher) {
            if (!opened) return@launch
            runCatching { bridge.requestServiceInfo(serviceType, serviceName) }
                .onFailure { logWarn("resolve request failed for $serviceName: ${it.message}") }
        }
    }

    /**
     * Bounded re-resolve budget for a service that resolved with JmDNS's empty TXT.
     *
     * Confined to [lane] (only [handleServiceResolved] touches it), so no lock is needed.
     *
     * **The budget is per cycle, and a cycle is rate-limited in time.** A bare attempt counter is
     * not enough here, because the caller is re-entered by our own retry: `requestServiceInfo` is
     * asked with `persistent = true`, so JmDNS re-delivers the same cached ServiceInfo, which comes
     * back through [handleServiceResolved] as another empty-TXT resolution. Resetting the counter
     * on exhaustion therefore started a fresh burst of retries on the very next delivery — an
     * unbounded loop of blocking JmDNS queries, one dispatcher task each, that burned CPU and grew
     * the heap for as long as the hollow record stayed cached.
     *
     * @return [EmptyTxtDecision.Retry] when a re-resolve was issued and the caller must not drop
     *   the peer; [EmptyTxtDecision.Suppressed] while the cooldown is running (the name has already
     *   had its cycle and been reported); [EmptyTxtDecision.Drop] once per cooldown, which is when
     *   the caller should log the drop and the next cycle begins.
     */
    private fun emptyTxtDecision(serviceName: String, nowMs: Long): EmptyTxtDecision {
        pruneEmptyTxtRetries(nowMs)
        val state = emptyTxtRetries.getOrPut(serviceName) { EmptyTxtRetryState() }
        if (state.attempts < MAX_EMPTY_TXT_RETRIES) {
            state.attempts += 1
            state.lastAttemptAtMs = nowMs
            return EmptyTxtDecision.Retry
        }
        if (nowMs - state.lastAttemptAtMs < EMPTY_TXT_COOLDOWN_MS) {
            return EmptyTxtDecision.Suppressed
        }
        // Cooldown elapsed: report the drop (the caller logs it) and arm the next cycle, so the
        // following delivery is Retry again. Without the reset the name would be Suppressed for
        // ever after its first drop, and a peer whose TXT arrived late could never be re-seen.
        state.attempts = 0
        state.lastAttemptAtMs = nowMs
        return EmptyTxtDecision.Drop
    }

    /**
     * Keeps [emptyTxtRetries] bounded: service names are attacker-influenced wire data (a peer that
     * re-registers on collision produces `Name (2)`, `Name (6)`, … for ever), so the map needs an
     * eviction rule and not just the success-path removal.
     */
    private fun pruneEmptyTxtRetries(nowMs: Long) {
        if (emptyTxtRetries.size <= MAX_EMPTY_TXT_TRACKED_NAMES) return
        emptyTxtRetries.entries.removeAll { nowMs - it.value.lastAttemptAtMs >= EMPTY_TXT_COOLDOWN_MS }
    }

    /**
     * Keeps [loggedCapabilities] bounded.
     *
     * Same reason as [pruneEmptyTxtRetries] — a peer that re-registers on collision produces
     * `Name (2)`, `Name (6)`, … for ever, so the map grows with every collision and only shrinks on
     * a removal event, which a collision-renamed service never gets. That is a leak in a structure
     * whose entire job is to suppress log lines.
     *
     * Dropping the whole map rather than evicting by age is deliberate: this is a LOGGING
     * optimisation and not state anything reads, so the worst case of a clear is one repeated line
     * per live peer. Ageing entries would need a timestamp per name and buy nothing.
     */
    private fun pruneLoggedCapabilities() {
        if (loggedCapabilities.size > MAX_LOGGED_CAPABILITY_NAMES) loggedCapabilities.clear()
    }

    /**
     * Keeps [resolveRequested] bounded. Same reasoning as [pruneEmptyTxtRetries]: names come from the
     * wire, and a peer re-registering on collision mints a new one each time.
     *
     * Clearing wholesale is safe here in a way it would not be for `emptyTxtRetries`: the worst case
     * is that a live peer's name is asked for once more, which is exactly what the code did on every
     * announcement before, so it degrades to the old behaviour rather than to a wrong one.
     */
    private fun pruneResolveRequested() {
        if (resolveRequested.size > MAX_RESOLVE_REQUESTED_NAMES) resolveRequested.clear()
    }

    /**
     * Publishes browse state. Reports the BROWSE flag alone, never `advertising || browsing`:
     * `CompositeDiscovery.applyBrowseState` treats `browsing = true` as "this radio is healthy",
     * so an advertising-only transport claiming to browse can never be watchdog-recovered.
     */
    private fun emitState(message: String) {
        emitEvent(FlashTransportEvent.StateChanged(browsing, message))
    }

    private fun emitEvent(event: FlashTransportEvent) {
        _events.tryEmit(event)
    }

    public companion object {

        /**
         * DNS-SD type in JmDNS's fully-qualified form. Android's `NsdTransport` uses
         * `"_flash-transfer._tcp."` and `NsdManager` appends the domain itself; JmDNS expects the
         * domain spelled out. Both denote the same service on the `local.` domain, so the two
         * platforms discover each other — `JmdnsTransportTest` pins that equivalence against the
         * JmDNS parser so a library upgrade cannot change it silently.
         */
        public const val DEFAULT_SERVICE_TYPE: String = "_flash-transfer._tcp.local."

        public const val DEFAULT_MAX_RESTARTS: Int = 5
        public const val MAX_NAME_LENGTH: Int = 24

        /**
         * How many times a service resolving with JmDNS's empty TXT is re-resolved before the peer
         * is dropped. Small on purpose: each attempt blocks for JmDNS's own service-info timeout,
         * and a peer that genuinely publishes no attributes must still end up rejected rather than
         * retried forever.
         */
        private const val MAX_EMPTY_TXT_RETRIES: Int = 3

        /**
         * How long a service that exhausted its retries is left alone before its next cycle. This
         * is what makes the budget a rate rather than a count: a peer whose TXT never arrives costs
         * [MAX_EMPTY_TXT_RETRIES] blocking queries and ONE drop line per cooldown, instead of a
         * resolve→drop→resolve loop for as long as the hollow record stays cached.
         */
        private const val EMPTY_TXT_COOLDOWN_MS: Long = 60_000L

        /** Above this many tracked names, entries idle for a full cooldown are evicted. */
        internal const val MAX_EMPTY_TXT_TRACKED_NAMES: Int = 64

        /** Bound on [loggedCapabilities]; see [pruneLoggedCapabilities]. */
        internal const val MAX_LOGGED_CAPABILITY_NAMES: Int = 256

        /** Bound on [resolveRequested]; see [pruneResolveRequested]. */
        internal const val MAX_RESOLVE_REQUESTED_NAMES: Int = 256
        public const val DEFAULT_LOST_DEBOUNCE_MS: Long = 6_000L
        public const val DEFAULT_PRESENCE_HEARTBEAT_MS: Long = 10_000L
        internal const val EVENT_BUFFER: Int = 64
        internal const val TAG: String = "JmdnsTransport"
    }
}

/**
 * Pure restart-decision math, duplicated from `androidMain`'s `NsdRestartPolicy` for the same
 * source-set-visibility reason as [JmdnsTxtCodec].
 */
internal object JmdnsRestartPolicy {

    /** Exponential backoff: 1 s, 2 s, 4 s, … capped at 30 s (mirrors C4 reconnect defaults). */
    val exponentialBackoff: (Int) -> Long = { attempt ->
        ((1L shl (attempt - 1).coerceIn(0, 5)) * 1_000L).coerceAtMost(30_000L)
    }
}
