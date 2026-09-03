@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashRadioTransport
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import com.transfer.flash.core.discovery.core.TxtCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext

// ---------------------------------------------------------------------------
// Bridge seam: every NsdManager/WifiManager interaction lives behind this
// interface so JVM unit tests can fake radio callbacks (plan C3.4, R2).
// ---------------------------------------------------------------------------

/** TXT-record payload of one advertisement (neutral form of NsdServiceInfo attrs). */
public data class AdvertiseRequest(
    val serviceName: String,
    val serviceType: String,
    val port: Int,
    val txtRecords: Map<String, String>,
)

/** Neutral callbacks mirroring [NsdManager.RegistrationListener]. */
public interface AdvertiseEvents {
    public fun onRegistered(actualServiceName: String, actualPort: Int)
    public fun onRegistrationFailed(errorCode: Int)
    public fun onUnregistered()
    public fun onUnregistrationFailed(errorCode: Int)
}

/**
 * Which resolution mechanism the bridge should use for a discovered service
 * (plan C3.4 split). Recorded verbatim by test fakes to assert branch selection.
 */
public enum class ResolutionStrategy {
    /** API 34+: continuous monitoring via `registerServiceInfoCallback`. */
    INFO_CALLBACK,

    /** API < 34: one-shot resolve through the hardened [NsdResolveQueue] (ERROR-006 protections preserved). */
    LEGACY_RESOLVE_QUEUE,
}

public data class BrowseRequest(
    val serviceType: String,
    /**
     * True requests the API 33+ `discoverServices(String, Int, NetworkRequest, Executor, Listener)`
     * overload which tracks networks automatically (proper Found/Lost across Wi-Fi drops).
     * False requests the legacy PROTOCOL_DNS_SD call. https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager
     */
    val useNetworkRequestDiscovery: Boolean,
)

/** Neutral callbacks mirroring [NsdManager.DiscoveryListener]. */
public interface BrowseEvents {
    public fun onStarted()
    public fun onStartFailed(errorCode: Int)
    public fun onServiceFound(serviceName: String)
    public fun onServiceLost(serviceName: String)
}

/** Resolution/monitoring flavor requested by the transport. */
public data class MonitorRequest(val serviceName: String, val strategy: ResolutionStrategy)

/** Fully resolved service data, neutralized out of [NsdServiceInfo]. */
public data class ResolvedServiceData(
    val hostAddress: String?,
    val port: Int,
    val serviceName: String,
    val attributes: Map<String, String>,
)

/** Neutral callbacks mirroring [NsdManager.ResolveListener]/[NsdManager.ServiceInfoCallback]. */
public interface MonitorEvents {
    /** First invocation per peer acts as Found, subsequent ones as Updated. */
    public fun onUpdated(data: ResolvedServiceData)

    /** Radio-reported loss (ServiceInfoCallback.onServiceLost or post-resolve loss). */
    public fun onMonitorLost(serviceName: String?)

    public fun onRegistrationFailed(errorCode: Int)

    public fun onUnregistered()
}

/**
 * Thin wrapper over everything NSD-related this transport needs. Production
 * implementation: [RealNsdManagerBridge]. Test implementations record calls and
 * replay neutral callbacks.
 *
 * Deviation note (R4 additive): the ERROR-006-hardened `NsdResolveQueue` stays
 * untouched and is owned INSIDE [RealNsdManagerBridge] for the
 * [ResolutionStrategy.LEGACY_RESOLVE_QUEUE] strategy — preserving its serialized,
 * generation-checked resolution while keeping Android types out of the transport logic.
 */
public interface NsdManagerBridge {
    /** Acquires (true) or releases (false) the Wi-Fi multicast lock. */
    public fun setMulticastLock(active: Boolean)

    /** Initiates advertising. Returns false when the call itself threw (async failures arrive via [AdvertiseEvents]). */
    public fun advertise(request: AdvertiseRequest, events: AdvertiseEvents): Boolean

    public fun unadvertise(events: AdvertiseEvents)

    /** Starts browsing. Returns false when the call itself threw (treated like onStartFailed). */
    public fun startBrowse(request: BrowseRequest, events: BrowseEvents): Boolean

    public fun stopBrowse()

    /** Starts resolution ([LEGACY_RESOLVE_QUEUE]) or continuous monitoring ([INFO_CALLBACK]). */
    public fun monitor(request: MonitorRequest, events: MonitorEvents): Boolean

    /** Cancels all outstanding monitors/resolutions. */
    public fun cancelMonitors()

    /**
     * Registers a listener fired whenever the set of usable local networks
     * changes (Wi-Fi connected/lost, hotspot/tether up or down).
     *
     * Needed because the browse is deliberately UNBOUND (see
     * [RealNsdManagerBridge.startBrowse]): the unbound `discoverServices`
     * overload is what makes a hotspot HOST able to see its clients, but unlike
     * the API 33+ NetworkRequest overload it does NOT track networks, so a
     * browse started on one interface keeps "running" against an interface that
     * no longer exists. Nothing then re-arms it and discovery is silently dead
     * until the process restarts.
     *
     * @return true when observation started. Default false for fakes/hosts with
     *   no connectivity service — callers must degrade, not fail.
     */
    public fun observeNetworkChanges(onChanged: () -> Unit): Boolean = false

    /** Stops [observeNetworkChanges]. Idempotent. */
    public fun stopObservingNetworkChanges() {}
}

/**
 * Production bridge. Owns the real [NsdManager], the Wi-Fi multicast lock and the
 * hardened [NsdResolveQueue]. All framework calls are wrapped so unexpected
 * exceptions surface as boolean initiation-failures rather than crashes.
 */
public class RealNsdManagerBridge(
    context: Context,
    private val tag: String = TAG,
) : NsdManagerBridge {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var advertiseListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Service infos stashed from onServiceFound so monitor() can hand them to NSD APIs.
     *
     * CONCURRENT by necessity: written from the NSD callback thread
     * (ConnectivityThread, via [DIRECT_EXECUTOR]) and read/cleared from the
     * transport's coroutine lane. A plain HashMap here loses writes and can
     * throw ConcurrentModificationException mid-iteration, which shows up as a
     * peer that is found but never resolves.
     */
    private val foundServices = java.util.concurrent.ConcurrentHashMap<String, NsdServiceInfo>()

    /** Lazily created: the hardened serialized resolver (ERROR-006). */
    @Volatile private var resolveQueue: NsdResolveQueue? = null

    /** Active ServiceInfoCallbacks keyed by service name (API 34+ path). Concurrent: see [foundServices]. */
    private val infoCallbacks =
        java.util.concurrent.ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()

    private val connectivityManager: android.net.ConnectivityManager? =
        runCatching {
            appContext.getSystemService(android.net.ConnectivityManager::class.java)
        }.getOrNull()

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun setMulticastLock(active: Boolean) {
        runCatching {
            if (active) {
                if (multicastLock?.isHeld != true) {
                    multicastLock = wifiManager.createMulticastLock("flash-nsd-transport").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } else {
                multicastLock?.let { lock ->
                    if (lock.isHeld) lock.release()
                }
                multicastLock = null
            }
        }.onFailure { Log.w(tag, "Multicast lock toggle failed", it) }
    }

    override fun advertise(request: AdvertiseRequest, events: AdvertiseEvents): Boolean {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                events.onRegistered(info.serviceName, info.port)

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                events.onRegistrationFailed(errorCode)

            override fun onServiceUnregistered(info: NsdServiceInfo) =
                events.onUnregistered()

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                events.onUnregistrationFailed(errorCode)
        }
        advertiseListener = listener
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = request.serviceName
            serviceType = request.serviceType
            port = request.port
            request.txtRecords.forEach { (key, value) ->
                if (key != null && value != null) setAttribute(key, value)
            }
        }
        return runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Log.w(tag, "Failed to initiate NSD registration", it) }
            .isSuccess
    }

    override fun unadvertise(events: AdvertiseEvents) {
        advertiseListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
                .onFailure { Log.w(tag, "Unable to unregister NSD service", it) }
        }
        advertiseListener = null
    }

    override fun startBrowse(request: BrowseRequest, events: BrowseEvents): Boolean {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = events.onStarted()
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                events.onStartFailed(errorCode)

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                foundServices[serviceInfo.serviceName] = serviceInfo
                events.onServiceFound(serviceInfo.serviceName)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) =
                events.onServiceLost(serviceInfo.serviceName)

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(tag, "NSD discovery stop failed error=$errorCode")
            }
        }
        discoveryListener = listener
        // Browse UNBOUND (no NetworkRequest transport filter). The API 33+ NetworkRequest overload
        // only delivers services seen on a CONNECTED TRANSPORT_WIFI/ETHERNET network — which does
        // NOT exist on a device that is HOSTING a Wi-Fi hotspot (SoftAP up, Wi-Fi STA off). Bound
        // that way, a hotspot host never discovers its clients even though it still advertises (so
        // the client finds the host) — exactly the asymmetric-discovery symptom. The unbound
        // overload listens on all local interfaces, including the SoftAP/tether interface, restoring
        // symmetric discovery over a hotspot. `request.useNetworkRequestDiscovery` is retained on the
        // wire request for API gating/telemetry but no longer narrows the browse network.
        return runCatching {
            @Suppress("DEPRECATION")
            nsdManager.discoverServices(request.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Log.w(tag, "NSD discovery failed", it) }
            .isSuccess
    }

    override fun stopBrowse() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { Log.w(tag, "Unable to stop NSD discovery", it) }
        }
        discoveryListener = null
        foundServices.clear()
    }

    override fun monitor(request: MonitorRequest, events: MonitorEvents): Boolean {
        val info = foundServices[request.serviceName] ?: return false
        return when (request.strategy) {
            ResolutionStrategy.INFO_CALLBACK -> monitorWithInfoCallback(info, events)
            ResolutionStrategy.LEGACY_RESOLVE_QUEUE -> monitorWithResolveQueue(info, events)
        }
    }

    /**
     * API 34+ path: continuous updates via `registerServiceInfoCallback`
     * (https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager.ServiceInfoCallback).
     */
    private fun monitorWithInfoCallback(info: NsdServiceInfo, events: MonitorEvents): Boolean {
        val monitoredName = info.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                mapResolved(serviceInfo)?.let(events::onUpdated)
            }

            // API 34-36 abstract method — exists on every SDK this compiles against.
            // The framework signature carries no service name, so we supply the one
            // this callback was registered FOR. Reporting null here made the loss
            // unattributable and the transport dropped it outright, leaving a
            // departed peer pinned in the directory forever.
            override fun onServiceLost() {
                events.onMonitorLost(monitoredName)
            }

            // Forward-compat: SDK 37 (Android 17) re-typed ServiceInfoCallback with an
            // NsdServiceInfo parameter. Declared WITHOUT `override` so it compiles at
            // compileSdk 35 (where the interface only has the no-arg variant); on
            // Android 17 devices the matching JVM signature implements the newer
            // framework method at runtime. Harmless extra method on API <= 36.
            fun onServiceLost(serviceInfo: NsdServiceInfo) {
                events.onMonitorLost(serviceInfo.serviceName ?: monitoredName)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                infoCallbacks.remove(monitoredName)
                events.onRegistrationFailed(errorCode)
            }

            override fun onServiceInfoCallbackUnregistered() {
                infoCallbacks.remove(monitoredName)
                events.onUnregistered()
            }
        }
        // Registering a second callback for a name we are already monitoring leaks the
        // first one (never unregistered) and duplicates the update stream. Retire the
        // previous registration first so re-finds stay idempotent.
        infoCallbacks.remove(monitoredName)?.let { stale ->
            runCatching { nsdManager.unregisterServiceInfoCallback(stale) }
                .onFailure { Log.w(tag, "Unable to retire stale ServiceInfoCallback for $monitoredName", it) }
        }
        return runCatching {
            nsdManager.registerServiceInfoCallback(info, DIRECT_EXECUTOR, callback)
            infoCallbacks[monitoredName] = callback
        }.onFailure { Log.w(tag, "registerServiceInfoCallback failed for $monitoredName", it) }
            .isSuccess
    }

    /**
     * Legacy path (< 34): the EXISTING hardened serialized resolver — ERROR-006
     * protections (single in-flight resolve, dedup by name, generation checks) preserved.
     */
    private fun monitorWithResolveQueue(info: NsdServiceInfo, events: MonitorEvents): Boolean {
        val queue = synchronized(queueLock) {
            resolveQueue ?: NsdResolveQueue(
                nsdManager = nsdManager,
                tag = tag,
                onResolvedCallback = { resolved ->
                    mapResolved(resolved)?.let(events::onUpdated)
                },
            ).also { resolveQueue = it }
        }
        queue.enqueue(info) { true }
        return true
    }

    override fun cancelMonitors() {
        infoCallbacks.values.forEach { callback ->
            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                .onFailure { Log.w(tag, "Unable to unregister ServiceInfoCallback", it) }
        }
        infoCallbacks.clear()
        resolveQueue?.clear()
        foundServices.clear()
    }

    override fun observeNetworkChanges(onChanged: () -> Unit): Boolean {
        val manager = connectivityManager ?: return false
        stopObservingNetworkChanges()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = onChanged()
            override fun onLost(network: android.net.Network) = onChanged()
        }
        return runCatching {
            // No NetworkRequest filter: a hotspot HOST has no connected Wi-Fi/Ethernet
            // network at all, and that transition is exactly the one we must react to.
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { Log.w(tag, "Unable to observe network changes", it) }
            .isSuccess
    }

    override fun stopObservingNetworkChanges() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
                .onFailure { Log.w(tag, "Unable to stop observing network changes", it) }
        }
        networkCallback = null
    }

    private fun mapResolved(info: NsdServiceInfo): ResolvedServiceData? {
        val hostAddress = runCatching { info.host?.hostAddress }.getOrNull()
        val attributes = runCatching {
            info.attributes.mapValues { (_, value) ->
                value?.toString(StandardCharsets.UTF_8).orEmpty()
            }
        }.getOrElse {
            Log.w(tag, "Failed reading TXT attributes for ${info.serviceName}", it)
            emptyMap()
        }
        return ResolvedServiceData(
            hostAddress = hostAddress,
            port = info.port,
            serviceName = info.serviceName,
            attributes = attributes,
        )
    }

    private val queueLock = Any()

    public companion object {
        public const val TAG: String = "DISCOVERY"

        /**
         * Direct executor: NSD callbacks stay on ConnectivityThread, matching
         * NsdFlashDiscovery behavior (no extra thread hops, no executor lifecycle).
         */
        public val DIRECT_EXECUTOR: java.util.concurrent.Executor =
            java.util.concurrent.Executor { block -> block.run() }
    }
}

// ---------------------------------------------------------------------------
// Pure, JVM-testable helpers (TXT codec + restart policy).
// ---------------------------------------------------------------------------

/**
 * NSD-side TXT codec. Key set mirrors the concurrent core.TxtCodec contract
 * ({device_id,name,model,proto,caps,fp8} — P3.5-A2 added `caps`/`fp8`, synced
 * manually in both codecs). Encode now DELEGATES to [TxtCodec] (trivial
 * unification, P3.5-B2 session): single size-guarded encoder, no drift risk.
 * Decode intentionally remains independent: the NSD resolve path needs the
 * tolerant fallback contract (missing name/proto degrade instead of dropping),
 * which the strict core codec does not provide.
 * TODO(unify): narrowed to the DECODE path only; fold once callers can accept
 * strict decoding (noted in logs/progress.md).
 */
internal object NsdTxtCodec {
    const val KEY_DEVICE_ID = TxtCodec.KEY_DEVICE_ID
    const val KEY_NAME = TxtCodec.KEY_NAME
    const val KEY_MODEL = TxtCodec.KEY_MODEL
    const val KEY_PROTO = TxtCodec.KEY_PROTO
    const val KEY_CAPS = TxtCodec.KEY_CAPS
    const val KEY_FP8 = TxtCodec.KEY_FP8
    val KEYS = listOf(KEY_DEVICE_ID, KEY_NAME, KEY_MODEL, KEY_PROTO, KEY_CAPS, KEY_FP8)

    fun encode(identity: FlashAdvertisedIdentity): LinkedHashMap<String, String> =
        LinkedHashMap(TxtCodec.encode(identity))

    data class ParsedIdentity(
        val deviceId: String?,
        val friendlyName: String?,
        val deviceModel: String?,
        val protocolVersion: Int?,
        /** Informational only (unauthenticated wire); see FlashAdvertisedIdentity KDoc. */
        val capabilities: Set<String> = emptySet(),
        val fingerprintPrefix: String? = null,
    )

    /**
     * Tolerant decode: missing/blank device_id yields null deviceId (caller drops the
     * endpoint — identity-aware filtering, plan C3.2); name/proto fall back gracefully;
     * missing caps/fp8 default to emptySet/null (pre-P3.5 advertisers accepted).
     */
    fun decode(attributes: Map<String, String>, fallbackName: String, fallbackProto: Int): ParsedIdentity {
        val deviceId = attributes[KEY_DEVICE_ID]?.trim()?.takeIf { it.isNotEmpty() }
        val name = attributes[KEY_NAME]?.takeIf { it.isNotBlank() } ?: fallbackName
        val model = attributes[KEY_MODEL]?.takeIf { it.isNotBlank() }
        val proto = attributes[KEY_PROTO]?.trim()?.toIntOrNull() ?: fallbackProto
        val capabilities = attributes[KEY_CAPS]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        return ParsedIdentity(
            deviceId = deviceId,
            friendlyName = name,
            deviceModel = model,
            protocolVersion = proto,
            capabilities = capabilities,
            fingerprintPrefix = attributes[KEY_FP8]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/** Pure restart-decision math so retry scheduling is unit-testable without virtual time. */
internal object NsdRestartPolicy {

    /** @param delayMs null means "give up" (attempt budget exhausted). */
    data class Decision(val attempt: Int, val delayMs: Long?)

    fun computeRestart(attempt: Int, maxAttempts: Int, delayProvider: (Int) -> Long): Decision =
        if (attempt > maxAttempts) Decision(attempt, null)
        else Decision(attempt, delayProvider(attempt))

    /** Exponential backoff: 1 s, 2 s, 4 s, … capped at 30 s (mirrors C4 reconnect defaults). */
    val exponentialBackoff: (Int) -> Long = { attempt ->
        ((1L shl (attempt - 1).coerceIn(0, 5)) * 1_000L).coerceAtMost(30_000L)
    }
}

// ---------------------------------------------------------------------------
// NsdTransport
// ---------------------------------------------------------------------------

/**
 * Continuous, identity-aware NSD transport (plan C3.2–C3.4).
 *
 * - **Advertising (C3.2):** registers `_flash-transfer._tcp.` with TXT records
 *   {device_id,name,model,proto}; resolved events carrying OUR deviceId are
 *   self-filtered by identity (never by service-name string match).
 * - **Continuous browsing (C3.3):** browse runs until [stop]; start failures and
 *   runtime losses restart browsing honoring the injected [retryDelayMs] provider
 *   with a capped attempt budget; transitions emit [FlashTransportEvent.StateChanged].
 * - **Resolution split (C3.4):** API ≥ 34 → `registerServiceInfoCallback` continuous
 *   monitoring + `discoverServices(NetworkRequest, …)` overload (proper Found/Lost
 *   across Wi-Fi drops, added API 33); API < 34 → legacy discover +
 *   hardened [NsdResolveQueue]. Thresholds documented in [NsdApiThresholds].
 * - **Modes (P3.5-B2):** [setMode] applies a [DiscoveryModePolicy]: GHOST stops /
 *   suppresses advertising (startAdvertising becomes a documented no-op), ECO
 *   duty-cycles browse bursts with idle gaps (wakeable mid-idle), BOOST lowers the
 *   restart-backoff base; STANDARD/RECEIVE_KIOSK browse continuously (KIOSK's
 *   `kiosk` flag flows purely via advertised caps from the identity).
 *
 * DEVIATION (documented per task spec): this transport owns an internal
 * `CoroutineScope(SupervisorJob() + dispatcher)` created lazily on first start and
 * cancelled in [stop]. Rationale: the radio lifecycle IS the scope lifetime — there
 * is no outer owner below the engine, and leaking callbacks after stop would break
 * the Found/Lost contract. Engine wiring later may replace this via constructor
 * injection if desired.
 */
public class NsdTransport(
    context: Context?,
    private val apiLevel: NsdApiLevel,
    private val directory: EndpointDirectory,
    private val sweep: (nowMs: Long) -> List<EndpointDirectory.Diff.Lost>,
    private val serviceType: String = DEFAULT_SERVICE_TYPE,
    private val instancePrefix: String = "Flash",
    private val retryDelayMs: (Int) -> Long = NsdRestartPolicy.exponentialBackoff,
    private val maxBrowsingRestarts: Int = DEFAULT_MAX_RESTARTS,
    /**
     * Bound on ECO duty cycles per browse session — determinism hook for JVM
     * tests (no-op `sleep` would otherwise make the duty loop infinite), exactly
     * analogous to [maxBrowsingRestarts]. Production default is effectively
     * unbounded.
     */
    private val maxDutyCycles: Int = Int.MAX_VALUE,
    initialModePolicy: DiscoveryModePolicy = DiscoveryModePolicy.forMode(
        com.transfer.flash.core.discovery.core.FlashDiscoveryMode.STANDARD,
    ),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeSourceMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { ms -> delay(ms) },
    /**
     * One ECO idle gap; returns TRUE when woken early by [setMode] (immediate
     * browse resume), FALSE when the full gap elapsed. Injectable for the same
     * determinism reason as [sleep]; null uses the conflated-channel default.
     */
    private val idleWaitOverride: (suspend (Long) -> Boolean)? = null,
    /**
     * Grace window before a radio-reported service loss (`onServiceLost` / monitor lost) is
     * promoted to a typed [FlashTransportEvent.Lost]. mDNS over Wi-Fi — and especially over a
     * phone HOTSPOT — drops and re-announces services frequently; without this window a single
     * transient goodbye flaps the peer out of the directory (the symptom: a discovered device
     * vanishes on a tab switch and never returns). A re-find within the window cancels the
     * pending removal. JVM tests pass 0 for synchronous assertions.
     */
    private val lostDebounceMs: Long = DEFAULT_LOST_DEBOUNCE_MS,
    /**
     * Period of the presence heartbeat (see [presenceTick]). MUST stay well below
     * the consumer's presence grace window (`CompositeDiscovery.DEFAULT_GRACE_MS`,
     * 30 s) so a live peer is refreshed several times per window and a single
     * missed tick never evicts it.
     */
    private val presenceHeartbeatMs: Long = DEFAULT_PRESENCE_HEARTBEAT_MS,
    /**
     * Heartbeat pacing. Deliberately NOT the [sleep] hook: JVM tests replace
     * [sleep] with a no-op to keep the browse loop synchronous, which would spin
     * the heartbeat forever. Tests that exercise the heartbeat inject here and
     * bound it with [maxPresenceTicks].
     */
    private val presenceSleep: suspend (Long) -> Unit = { ms -> delay(ms) },
    /** Bound on heartbeat ticks per browse session — determinism hook, mirrors [maxDutyCycles]. */
    private val maxPresenceTicks: Int = Int.MAX_VALUE,
    /**
     * Delay before retrying a monitor/resolve that failed to start, multiplied by the attempt
     * number (linear backoff). Sized well under [presenceHeartbeatMs]: the heartbeat used to be
     * the ONLY retry, so a single failed resolve cost a full 10 s of discovery latency and three
     * cost 30 s. Tests pass 0 to disable.
     */
    private val monitorRetryMs: Long = DEFAULT_MONITOR_RETRY_MS,
    /** Consecutive fast retries per service before falling back to the heartbeat. */
    private val maxMonitorRetries: Int = DEFAULT_MAX_MONITOR_RETRIES,
    /** Pacing hook for [retryMonitorSoon]; separate from [sleep] for the same reason as [presenceSleep]. */
    private val monitorRetrySleep: suspend (Long) -> Unit = { ms -> delay(ms) },
    /**
     * Period of the advertise watchdog (see [startAdvertiseWatchdog]). An NSD registration can be
     * dropped by the framework or the OEM power manager without any recoverable signal beyond the
     * `onRegistrationFailed`/`onUnregistered` callback, and nothing else in the stack re-registers:
     * `CompositeDiscovery` watchdogs the browse only, and screen-on / connectivity re-arms restart
     * browsing only. Without this loop a phone that lost its advertisement stayed invisible to
     * every peer until the process restarted. Tests pass 0 to disable.
     */
    private val advertiseWatchdogMs: Long = DEFAULT_ADVERTISE_WATCHDOG_MS,
    /** Pacing hook for the advertise watchdog; see [presenceSleep]. */
    private val advertiseWatchdogSleep: suspend (Long) -> Unit = { ms -> delay(ms) },
    /** Bound on advertise-watchdog ticks — determinism hook, mirrors [maxPresenceTicks]. */
    private val maxAdvertiseWatchdogTicks: Int = Int.MAX_VALUE,
    /**
     * Debounce applied before a connectivity change forces a browse restart:
     * Wi-Fi transitions arrive as bursts (lost → available → available) and each
     * restart tears down the radio browse.
     */
    private val networkChangeDebounceMs: Long = DEFAULT_NETWORK_CHANGE_DEBOUNCE_MS,
    private val logInfo: (String) -> Unit = { Log.i(TAG, it) },
    private val logWarn: (String) -> Unit = { Log.w(TAG, it) },
    bridgeOverride: NsdManagerBridge? = null,
) : FlashRadioTransport {

    init {
        require(bridgeOverride != null || context != null) {
            "NsdTransport requires either a Context or an injected NsdManagerBridge (tests)"
        }
    }

    private val bridge: NsdManagerBridge =
        bridgeOverride ?: RealNsdManagerBridge(requireNotNull(context) { "context required" })

    override val transportName: String = "nsd"

    private val _events = MutableSharedFlow<FlashTransportEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<FlashTransportEvent> = _events.asSharedFlow()

    @Volatile private var ownDeviceId: FlashDeviceId? = null
    @Volatile private var advertising = false
    @Volatile private var browsing = false
    @Volatile private var restartAttempt = 0
    @Volatile private var advertisedPort: Int = 0

    /**
     * Active mode policy (P3.5-B2). Defaults to STANDARD at construction so the
     * transport ALWAYS has a policy even before [setMode] is ever called.
     * Volatile: read from radio-callback lanes, written by [setMode].
     */
    @Volatile private var modePolicy: DiscoveryModePolicy = initialModePolicy

    /** Conflated wake-up signal so ECO idle gaps end immediately on [setMode]. */
    private val modeChanges = Channel<Unit>(Channel.CONFLATED)

    /** ECO idle wait; test override or conflated-channel default (wake on setMode). */
    private val idleWait: suspend (Long) -> Boolean =
        idleWaitOverride ?: { ms -> withTimeoutOrNull(ms) { modeChanges.receive() } != null }

    /** Identity/port retained so exiting GHOST can resume advertising (B2). */
    @Volatile private var lastAdvertisedIdentity: FlashAdvertisedIdentity? = null
    @Volatile private var lastAdvertisedPort: Int = 0
    @Volatile private var dutyCyclesCompleted = 0

    /** Reverse map serviceName → deviceId so radio loss can produce typed Lost events. */
    private val deviceIdsByServiceName = HashMap<String, FlashDeviceId>()

    /**
     * serviceName → true once [NsdManagerBridge.monitor] was successfully initiated for it.
     *
     * This is the transport's LIVENESS AUTHORITY, and it is deliberately not
     * time-based. NSD gives no periodic positive re-sighting: on API ≥ 34
     * `registerServiceInfoCallback` fires on registration and on change only, and
     * pre-34 `resolveService` is one-shot. What the platform DOES give is an
     * explicit goodbye (`onServiceLost`). So "the platform told us this service
     * exists and has not told us it is gone" is the strongest presence signal
     * available, and it is what [presenceTick] converts into heartbeats.
     *
     * A `false` value means the service was found but its monitor never started
     * (bridge returned false / registration failed) — [presenceTick] retries
     * those instead of leaving the peer permanently invisible.
     */
    private val monitoredServices = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * serviceName → in-flight debounced removal job (see [lostDebounceMs]). Touched only from the
     * [lane], so no external synchronization. A re-find cancels and removes the matching entry.
     */
    private val pendingLost = HashMap<String, Job>()

    /** Presence heartbeat loop; one per browse session. */
    @Volatile private var heartbeatJob: Job? = null

    /** serviceName → in-flight fast monitor retry (see [retryMonitorSoon]). */
    private val monitorRetryJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** serviceName → consecutive failed monitor starts, bounding [retryMonitorSoon]. */
    private val monitorRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Advertise keepalive loop; re-registers a dropped advertisement (see [startAdvertiseWatchdog]). */
    @Volatile private var advertiseWatchdogJob: Job? = null

    /**
     * Whether an advertisement is *wanted*, as opposed to [advertising] which is whether the radio
     * currently has one. The two diverging with nothing to reconcile them is what made a phone
     * disappear from its peers after a screen-off: the framework dropped the registration, the
     * callback set `advertising = false`, and nothing ever registered again.
     */
    @Volatile private var advertiseDesired = false

    /** Debounced connectivity-change restart. */
    @Volatile private var networkChangeJob: Job? = null

    @Volatile private var observingNetwork = false

    /** Wall clock of the most recent browse (re)start; gates heartbeat eviction. */
    @Volatile private var browseStartedAtMs: Long = 0L

    private var scope: CoroutineScope? = null

    /**
     * Serial lane guarding [directory] + bookkeeping maps (directory itself is NOT thread-safe).
     * Falls back to the raw dispatcher when it does not support limiting views
     * (e.g. Dispatchers.Unconfined in JVM tests) — production Dispatchers.IO gets a real
     * parallelism-1 view.
     */
    private val lane: CoroutineDispatcher = try {
        dispatcher.limitedParallelism(1)
    } catch (_: UnsupportedOperationException) {
        dispatcher
    }

    private val advertiseEvents = object : AdvertiseEvents {
        override fun onRegistered(actualServiceName: String, actualPort: Int) {
            advertisedPort = actualPort
            advertising = true
            emitState("Advertising as $actualServiceName")
        }

        override fun onRegistrationFailed(errorCode: Int) {
            advertising = false
            releaseMulticastLockIfIdle()
            emitState("Advertising failed: $errorCode")
        }

        override fun onUnregistered() {
            advertising = false
            releaseMulticastLockIfIdle()
        }

        override fun onUnregistrationFailed(errorCode: Int) {
            logWarn("NSD unregistration failed error=$errorCode")
        }
    }

    private val browseEvents = object : BrowseEvents {
        override fun onStarted() {
            restartAttempt = 0
            emitState("Scanning network")
        }

        override fun onStartFailed(errorCode: Int) {
            scope?.launch(lane) {
                if (!browsing) return@launch
                logWarn("NSD discovery start failed error=$errorCode")
                if (!backoffOrGiveUp()) return@launch
                browseLoop()
            }
        }

        override fun onServiceFound(serviceName: String) {
            val strategy = resolutionStrategy()
            // The platform vouches for this service's existence from now until it
            // reports the matching loss; record that BEFORE monitoring so a failed
            // monitor is still retryable by the heartbeat.
            monitoredServices.putIfAbsent(serviceName, false)
            scope?.launch(lane) {
                if (!browsing) return@launch
                startMonitor(serviceName, strategy)
            }
        }

        override fun onServiceLost(serviceName: String) {
            handleMonitorLost(serviceName)
        }
    }

    /**
     * Initiates monitoring/resolution for one service and records whether it took.
     * A false return used to be discarded, which is how a peer could be "found"
     * by the radio and then never surface: nothing resolved it and nothing retried.
     *
     * A failure here is retried by [retryMonitorSoon] within [monitorRetryMs] rather than waiting
     * for the next presence heartbeat. The heartbeat is a 10 s tick, so leaving it as the only
     * retry path made one failed resolve cost 10 s of discovery latency, two cost 20 s and three
     * cost 30 s — the "sometimes 2 seconds, sometimes half a minute" spread users actually saw.
     */
    private fun startMonitor(serviceName: String, strategy: ResolutionStrategy): Boolean {
        val started = runCatching {
            bridge.monitor(MonitorRequest(serviceName, strategy), monitorEventsFor(serviceName))
        }
            .getOrElse { error ->
                logWarn("Monitor initiation threw for name=$serviceName: ${error.message}")
                false
            }
        monitoredServices[serviceName] = started
        if (!started) {
            logWarn("Monitor initiation failed for name=$serviceName; fast-retrying in ${monitorRetryMs}ms")
            retryMonitorSoon(serviceName)
        }
        return started
    }

    /**
     * Schedules one short-delay monitor retry for [serviceName], collapsing duplicates so a
     * repeatedly-failing name cannot accumulate retry jobs. Bounded by [maxMonitorRetries]; past
     * that the presence heartbeat remains the (slow) backstop, so a permanently unresolvable
     * service costs a bounded amount of work rather than spinning.
     */
    private fun retryMonitorSoon(serviceName: String) {
        if (monitorRetryMs <= 0L) return
        val attempts = monitorRetries.merge(serviceName, 1) { previous, one -> previous + one } ?: 1
        if (attempts > maxMonitorRetries) return
        val activeScope = scope ?: return
        monitorRetryJobs.remove(serviceName)?.cancel()
        monitorRetryJobs[serviceName] = activeScope.launch(lane) {
            monitorRetrySleep(monitorRetryMs * attempts)
            monitorRetryJobs.remove(serviceName)
            if (!browsing) return@launch
            // Gone (radio loss / eviction) or already resolved in the meantime: nothing to do.
            if (monitoredServices[serviceName] != false) return@launch
            startMonitor(serviceName, resolutionStrategy())
        }
    }

    /**
     * Per-service [MonitorEvents]. The shared instance this replaced could not attribute an
     * asynchronous `onServiceInfoCallbackRegistrationFailed` to a service name, so the failure was
     * logged and dropped: [monitoredServices] kept the optimistic `true` written when
     * `registerServiceInfoCallback` merely did not throw, and from then on the presence heartbeat
     * neither retried the monitor (step 1 only retries `false`) nor evicted the peer (it is still
     * "monitored"). The peer stayed invisible until the next browse restart.
     *
     * [monitoredName] is used for attribution only. On the LEGACY_RESOLVE_QUEUE path the bridge's
     * queue captures whichever instance it was handed first and reuses it for every service, so
     * anything that must be per-service reads the name off the payload instead — and the two
     * callbacks that cannot do that (`onRegistrationFailed`, `onUnregistered`) are INFO_CALLBACK
     * only, where the instance really is per-service.
     */
    private fun monitorEventsFor(monitoredName: String): MonitorEvents = object : MonitorEvents {
        override fun onUpdated(data: ResolvedServiceData) {
            monitorRetries.remove(data.serviceName)
            scope?.launch(lane) { handleServiceUpdated(data) }
        }

        override fun onMonitorLost(serviceName: String?) {
            handleMonitorLost(serviceName ?: monitoredName)
        }

        override fun onRegistrationFailed(errorCode: Int) {
            logWarn("Service monitor registration failed name=$monitoredName error=$errorCode")
            scope?.launch(lane) {
                // Only demote a registration we still believe in: a later successful re-register
                // for the same name must not be clobbered by a stale failure callback.
                if (monitoredServices[monitoredName] != true) return@launch
                monitoredServices[monitoredName] = false
                retryMonitorSoon(monitoredName)
            }
        }

        override fun onUnregistered() {}
    }

    // -- Advertising (C3.2) ---------------------------------------------------

    override suspend fun startAdvertising(port: Int, identity: FlashAdvertisedIdentity): FlashResult<Unit> {
        lastAdvertisedIdentity = identity
        lastAdvertisedPort = port
        ownDeviceId = identity.deviceId
        if (!modePolicy.advertises) {
            // GHOST (P3.5-B2): deliberate no-op. Success is returned so callers
            // (e.g. CompositeDiscovery.startAll) do not treat the mode as an
            // error; nothing is registered with the radio.
            return FlashResult.Success(Unit)
        }
        ensureScope()
        acquireMulticastLockIfNeeded()
        advertiseDesired = true
        // Connectivity observation is normally armed by startBrowsing(); an advertise-only
        // transport needs it too, because the re-registration below is driven by it.
        observeNetworkChangesIfNeeded()

        val initiated = registerAdvertisement(port, identity)
        return if (initiated) {
            startAdvertiseWatchdog()
            FlashResult.Success(Unit)
        } else {
            // Keep advertiseDesired: the watchdog is what turns a failed start into a retry
            // instead of permanent invisibility. The multicast lock stays for the same reason.
            startAdvertiseWatchdog()
            FlashResult.Failure(
                FlashError.NetworkUnavailable("Failed to initiate NSD service registration"),
            )
        }
    }

    /** Builds the request from [identity] and hands it to the radio. True when the call took. */
    private fun registerAdvertisement(port: Int, identity: FlashAdvertisedIdentity): Boolean {
        val txt = NsdTxtCodec.encode(identity)
        val request = AdvertiseRequest(
            serviceName = "$instancePrefix ${identity.friendlyName.take(MAX_NAME_LENGTH)}",
            serviceType = serviceType,
            port = port,
            txtRecords = txt,
        )
        return runCatching { bridge.advertise(request, advertiseEvents) }.getOrElse { false }
    }

    /**
     * Reconciles wanted-vs-actual advertising state on a fixed period.
     *
     * NSD gives no positive "still advertised" signal, only a failure or unregistration callback,
     * and Android drops registrations for reasons an app cannot prevent: the mDNS daemon restarts,
     * the interface the service was registered on goes away (Wi-Fi ↔ hotspot), or an OEM power
     * manager freezes the process. Every one of those left [advertising] false with nothing to fix
     * it, and a peer's own presence heartbeat then evicted this device ~20-30 s later. This loop is
     * the missing counterpart to `CompositeDiscovery`'s browse watchdog.
     */
    private fun startAdvertiseWatchdog() {
        if (advertiseWatchdogMs <= 0L) return
        if (advertiseWatchdogJob?.isActive == true) return
        advertiseWatchdogJob = scope?.launch(lane) {
            var ticks = 0
            while (coroutineContext.isActive && ticks < maxAdvertiseWatchdogTicks) {
                advertiseWatchdogSleep(advertiseWatchdogMs)
                ticks += 1
                if (!advertiseDesired || !coroutineContext.isActive) return@launch
                if (advertising) continue
                val identity = lastAdvertisedIdentity ?: continue
                logWarn("Advertisement is down; re-registering")
                acquireMulticastLockIfNeeded()
                registerAdvertisement(lastAdvertisedPort, identity)
            }
        }
    }

    /**
     * Forces a fresh registration even when [advertising] still reports true.
     *
     * A registration bound to an interface that no longer exists keeps reporting success — the
     * advertise-side twin of the dead-browse problem [restartBrowsing] exists for. Used on
     * connectivity changes, where "still advertising" is exactly the claim not to trust.
     */
    private suspend fun restartAdvertising() {
        if (!advertiseDesired) return
        val identity = lastAdvertisedIdentity ?: return
        runCatching { bridge.unadvertise(advertiseEvents) }
        advertising = false
        acquireMulticastLockIfNeeded()
        registerAdvertisement(lastAdvertisedPort, identity)
    }

    // -- Mode wiring (P3.5-B2) -------------------------------------------------

    /**
     * Applies a [DiscoveryModePolicy] live-when-safe:
     * - advertise toggle takes effect IMMEDIATELY (unadvertise on entering
     *   GHOST; resume from retained identity on leaving it);
     * - ECO duty-cycle and BOOST backoff knobs are read at the NEXT browse-loop
     *   iteration / next backoff computation; an in-progress ECO idle gap is
     *   cut short via a conflated wake-up so leaving ECO resumes browsing
     *   immediately.
     */
    override suspend fun setMode(policy: DiscoveryModePolicy) {
        val previous = modePolicy
        modePolicy = policy
        modeChanges.trySend(Unit) // wake any in-flight idleWait (no-op if none)
        if (previous.advertises == policy.advertises) return
        if (!policy.advertises) {
            if (advertising) stopAdvertisingInternal()
        } else if (!advertising) {
            val identity = lastAdvertisedIdentity ?: return // never advertised; nothing to resume
            startAdvertising(lastAdvertisedPort, identity)
        }
    }

    private fun stopAdvertisingInternal() {
        advertiseDesired = false
        advertiseWatchdogJob?.cancel()
        advertiseWatchdogJob = null
        runCatching { bridge.unadvertise(advertiseEvents) }
        advertising = false
        releaseMulticastLockIfIdle()
    }

    /** Retry delay honoring the active policy's base (BOOST lowers it, B2). */
    private fun effectiveRetryDelayMs(attempt: Int): Long =
        retryDelayMs(attempt) * modePolicy.restartBackoffBaseMs /
            DiscoveryModePolicy.DEFAULT_BACKOFF_BASE_MS

    // -- Continuous browsing (C3.3 + C3.4) ------------------------------------

    override suspend fun startBrowsing(): FlashResult<Unit> {
        if (browsing) return FlashResult.Success(Unit)
        ensureScope()
        browsing = true
        restartAttempt = 0
        dutyCyclesCompleted = 0
        acquireMulticastLockIfNeeded()
        observeNetworkChangesIfNeeded()
        browseStartedAtMs = timeSourceMs()
        emitState("Starting browse")
        scope?.launch(lane) { browseLoop() }
        startHeartbeat()
        return FlashResult.Success(Unit)
    }

    /**
     * Forced browse restart (see [FlashRadioTransport.restartBrowsing]).
     *
     * [startBrowsing] returns early while `browsing` is true, so it can never
     * recover a browse that died without telling us — the exact failure mode of
     * a Wi-Fi ↔ hotspot switch, and the reason the screen-on re-arm used to be a
     * silent no-op. This tears the radio browse down unconditionally and starts a
     * clean one, re-delivering `onServiceFound` for every service still present.
     */
    override suspend fun restartBrowsing(): FlashResult<Unit> {
        val wasBrowsing = browsing
        browsing = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { bridge.stopBrowse() }
        runCatching { bridge.cancelMonitors() }
        monitoredServices.clear()
        monitorRetryJobs.values.forEach { it.cancel() }
        monitorRetryJobs.clear()
        monitorRetries.clear()
        // Deliberately NOT emitState() here: that would publish a transient
        // browsing=false and let a consumer watchdog race this restart. The
        // "Starting browse" transition from startBrowsing() is the observable one.
        if (wasBrowsing) logInfo("Forced browse restart")
        return startBrowsing()
    }

    /**
     * Starts browsing until success — continuously (STANDARD/KIOSK/BOOST) or as
     * the first burst of the ECO duty cycle ([DiscoveryModePolicy.browseDutyCycleMs]
     * scan → [DiscoveryModePolicy.idleDutyCycleMs] idle → repeat). Duty-cycle
     * knobs are read per iteration so [setMode] takes effect at the next phase.
     */
    private suspend fun browseLoop() {
        while (browsing && coroutineContext.isActive) {
            val request = BrowseRequest(
                serviceType = serviceType,
                useNetworkRequestDiscovery = useNetworkRequestDiscovery(),
            )
            val started = runCatching { bridge.startBrowse(request, browseEvents) }
                .getOrElse { false }
            if (!started) {
                logWarn("NSD browse initiation failed; retrying")
                if (!backoffOrGiveUp()) return
                continue
            }
            restartAttempt = 0
            val browseMs = modePolicy.browseDutyCycleMs ?: return // continuous mode
            sleep(browseMs)
            if (!browsing || !coroutineContext.isActive) return
            bridge.stopBrowse() // end of scan burst; radio loss callbacks ignored below
            val idleMs = modePolicy.idleDutyCycleMs
            emitState("ECO idle for ${idleMs ?: 0}ms")
            dutyCyclesCompleted += 1
            val wokeEarly = idleWait(idleMs ?: 0L)
            if (!browsing) return
            // Budget consumed AFTER the paired idle so a bounded session always
            // ends in idle, never mid-presence-gap (burst→idle invariant).
            if (dutyCyclesCompleted >= maxDutyCycles) {
                browsing = false
                releaseMulticastLockIfIdle()
                emitState("ECO duty-cycle budget exhausted after $dutyCyclesCompleted cycles")
                return
            }
            if (!wokeEarly) continue
            // Mode changed mid-idle: loop re-reads the fresh policy immediately.
        }
    }

    /**
     * One backoff step: consumes an attempt, sleeps per the policy-scaled
     * [effectiveRetryDelayMs], and returns false when the attempt budget is
     * exhausted (browsing disabled + final state emitted).
     */
    private suspend fun backoffOrGiveUp(): Boolean {
        restartAttempt += 1
        val decision = NsdRestartPolicy.computeRestart(
            restartAttempt,
            maxBrowsingRestarts,
            ::effectiveRetryDelayMs,
        )
        val backoffMs = decision.delayMs
        if (backoffMs == null) {
            browsing = false
            releaseMulticastLockIfIdle()
            emitState("Browsing gave up after $restartAttempt attempts")
            return false
        }
        emitState("Browsing restarting (attempt $restartAttempt in ${backoffMs}ms)")
        sleep(backoffMs)
        return browsing && coroutineContext.isActive
    }

    // -- Presence heartbeat ----------------------------------------------------

    /**
     * Starts the per-browse-session presence heartbeat.
     *
     * WHY this exists: a directory dedups a repeated, unchanged sighting into
     * "no change", so a stable peer produces exactly ONE Found and then silence.
     * Any consumer that ages peers out on a TTL (CompositeDiscovery's 30 s grace)
     * reads that silence as departure and evicts a peer that never left — and
     * because the sighting still dedups to "no change" afterwards, nothing ever
     * re-announces it. That is the "device shows up, disappears half a minute
     * later, never comes back" symptom. The heartbeat republishes presence for
     * everything the radio still vouches for, so silence now means something.
     */
    private fun startHeartbeat() {
        if (presenceHeartbeatMs <= 0L) return
        heartbeatJob?.cancel()
        heartbeatJob = scope?.launch(lane) {
            var ticks = 0
            while (browsing && coroutineContext.isActive && ticks < maxPresenceTicks) {
                presenceSleep(presenceHeartbeatMs)
                if (!browsing || !coroutineContext.isActive) return@launch
                presenceTick()
                ticks += 1
            }
        }
    }

    /**
     * One heartbeat tick, on the [lane]:
     * 1. retry monitors that never started (a found-but-unresolved peer is
     *    otherwise invisible forever — nothing else retries);
     * 2. re-affirm every endpoint whose service the platform still vouches for
     *    ([monitoredServices]) as [FlashTransportEvent.Presence];
     * 3. evict endpoints whose service the platform has reported gone;
     * 4. drain the injected [sweep] safety net.
     *
     * Step 3 is suppressed for one grace period after a browse (re)start: the
     * radio needs a moment to re-deliver `onServiceFound` for services that are
     * still there, and evicting during that window would recreate the very false
     * Lost this heartbeat exists to prevent.
     */
    private suspend fun presenceTick() {
        val strategy = resolutionStrategy()
        for ((serviceName, monitorStarted) in monitoredServices.entries.toList()) {
            if (!monitorStarted) startMonitor(serviceName, strategy)
        }

        val now = timeSourceMs()
        val evictionArmed = now - browseStartedAtMs >= presenceHeartbeatMs * EVICTION_GRACE_TICKS
        for (entry in directory.snapshot()) {
            val serviceName = entry.endpoint.serviceName
            when {
                monitoredServices.containsKey(serviceName) -> {
                    // Keep OUR lastSeen honest as well, so a transport-side sweep
                    // (step 4) measures "last known alive", not "last field change".
                    directory.applySeen(entry.endpoint, now)
                    emitEvent(FlashTransportEvent.Presence(entry.endpoint))
                }
                evictionArmed && !pendingLost.containsKey(serviceName) -> {
                    val deviceId = entry.endpoint.deviceId
                    synchronized(deviceIdsByServiceName) { deviceIdsByServiceName.remove(serviceName) }
                    directory.applyLost(deviceId)
                    logInfo("Heartbeat evicting unmonitored peer name=$serviceName")
                    emitEvent(FlashTransportEvent.Lost(deviceId, serviceName))
                }
            }
        }

        sweep(now).forEach { agedOut ->
            val serviceName = findServiceNameFor(agedOut.deviceId)
            if (serviceName != null) {
                synchronized(deviceIdsByServiceName) { deviceIdsByServiceName.remove(serviceName) }
                monitoredServices.remove(serviceName)
            }
            emitEvent(FlashTransportEvent.Lost(agedOut.deviceId, serviceName))
        }
    }

    // -- Connectivity re-arm ---------------------------------------------------

    private fun observeNetworkChangesIfNeeded() {
        if (observingNetwork) return
        observingNetwork = runCatching { bridge.observeNetworkChanges(::onNetworkChanged) }
            .getOrElse { false }
    }

    /**
     * Connectivity changed (Wi-Fi joined/dropped, hotspot toggled). The browse is
     * unbound and therefore network-blind, so nothing else would notice that the
     * interface it was started on is gone. Debounced because a single Wi-Fi
     * transition arrives as a burst of callbacks.
     *
     * The advertisement is re-registered for the same reason and is deliberately NOT gated on
     * `advertising`: a registration pinned to a vanished interface still reports itself as healthy,
     * so trusting that flag here is what let a device keep "advertising" into a dead interface
     * while every peer saw it drop off.
     */
    private fun onNetworkChanged() {
        val activeScope = scope ?: return
        networkChangeJob?.cancel()
        networkChangeJob = activeScope.launch(lane) {
            delay(networkChangeDebounceMs)
            if (advertiseDesired) {
                logInfo("Connectivity changed; re-registering NSD advertisement")
                restartAdvertising()
            }
            if (!browsing) return@launch
            logInfo("Connectivity changed; forcing NSD browse restart")
            restartBrowsing()
        }
    }

    // -- Resolution handling (C3.4) + directory diff mapping (C3.5 groundwork) -

    private suspend fun handleServiceUpdated(data: ResolvedServiceData) {
        if (!browsing) return
        val parsed = NsdTxtCodec.decode(
            attributes = data.attributes,
            fallbackName = data.serviceName,
            fallbackProto = FlashProtocol.VERSION,
        )
        val deviceIdString = parsed.deviceId ?: run {
            logInfo("Dropping NSD endpoint without device_id name=${data.serviceName}")
            return
        }
        val deviceId = runCatching { FlashDeviceId(deviceIdString) }.getOrElse {
            logWarn("Invalid device_id '$deviceIdString' from ${data.serviceName}")
            return
        }
        if (deviceId == ownDeviceId) return // self-advertisement filtered by IDENTITY (C3.2)

        // Pre-directory protocol gate (P3.5-A4): incompatible versions are dropped
        // BEFORE entering the directory. Tolerance: a MISSING proto falls back to
        // our version (legacy/foreign advertisers stay visible); an EXPLICIT
        // different version is a hard drop.
        val peerProto = parsed.protocolVersion ?: FlashProtocol.VERSION
        if (!FlashProtocol.isCompatible(peerProto)) {
            logWarn("Dropping NSD endpoint with proto=$peerProto (want ${FlashProtocol.VERSION}) name=${data.serviceName}")
            return
        }

        // Caps are INFORMATIONAL on an unauthenticated wire (RFC 6762): accepted
        // as-is, logged, never retained on the shared endpoint model. Any
        // caps-based peer filtering happens at CONNECT time (C3.10 seam).
        if (parsed.capabilities.isNotEmpty()) {
            logInfo("Peer capabilities caps=${parsed.capabilities.joinToString(",")} name=${data.serviceName}")
        }

        val hostAddress = data.hostAddress ?: return // unresolved yet; wait for next update
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
        )
        val diff = directory.applySeen(endpoint, timeSourceMs())
        synchronized(deviceIdsByServiceName) {
            deviceIdsByServiceName[data.serviceName] = deviceId
        }
        // A successful resolution proves the platform is vouching for this exact
        // (possibly renamed) instance, so register it under the name the directory
        // keys off — otherwise the heartbeat could not match the entry back to a
        // monitor and would eventually evict a live peer.
        monitoredServices[data.serviceName] = true
        // The peer is back (or still here): cancel any debounced removal armed by a prior
        // transient loss so it never flaps out of the directory.
        pendingLost.remove(data.serviceName)?.cancel()
        emitDiff(diff, endpoint)
    }

    private fun handleMonitorLost(serviceName: String?) {
        if (serviceName == null) return
        scope?.launch(lane) {
            if (!browsing) return@launch
            // Debounce transient radio goodbyes (mDNS over Wi-Fi / hotspot flaps constantly): defer
            // the removal by [lostDebounceMs]; a re-find (handleServiceUpdated) cancels it. Always
            // replace any prior pending job for this service so a stale (cancelled-scope) entry can
            // never suppress a fresh removal. Only a peer still absent after the window is evicted.
            pendingLost.remove(serviceName)?.cancel()
            val job = scope?.launch(lane) {
                delay(lostDebounceMs)
                if (!browsing) return@launch
                pendingLost.remove(serviceName)
                // The platform has withdrawn its vouch for this service: drop it from the
                // liveness map BEFORE the directory bookkeeping, so a heartbeat racing this
                // job cannot re-affirm a peer we are in the middle of evicting.
                monitoredServices.remove(serviceName)
                val deviceId = synchronized(deviceIdsByServiceName) {
                    deviceIdsByServiceName.remove(serviceName)
                } ?: return@launch
                // Bookkeep the loss in the directory; the typed Lost event below carries the
                // serviceName we already know, so the generic Diff.Lost emission is skipped
                // (avoids duplicate Lost events for one radio goodbye).
                directory.applyLost(deviceId)
                emitEvent(FlashTransportEvent.Lost(deviceId, serviceName))
            } ?: return@launch
            pendingLost[serviceName] = job
        }
    }

    /** Pulls engine-wired sweep results into Lost events (sweeper wiring is the engine's job, C3.5). */
    public fun pollSweep() {
        scope?.launch(lane) {
            sweep(timeSourceMs()).forEach { agedOut ->
                val serviceName = findServiceNameFor(agedOut.deviceId)
                emitEvent(FlashTransportEvent.Lost(agedOut.deviceId, serviceName))
            }
        }
    }

    private fun findServiceNameFor(deviceId: FlashDeviceId): String? =
        synchronized(deviceIdsByServiceName) {
            deviceIdsByServiceName.entries.firstOrNull { it.value == deviceId }?.key
        }

    /**
     * Maps a directory diff onto the event flow.
     *
     * [EndpointDirectory.Diff.Unchanged] used to emit NOTHING, which is the bug
     * that made stable peers vanish: a consumer aging peers out on a TTL saw one
     * Found and then permanent silence for a peer that was sitting right there.
     * It now emits [FlashTransportEvent.Presence] — same sighting, classified as
     * liveness rather than change — so downstream TTLs are actually fed.
     */
    private suspend fun emitDiff(diff: EndpointDirectory.Diff, endpoint: FlashDiscoveredEndpoint) {
        when (diff) {
            is EndpointDirectory.Diff.Found -> emitEvent(FlashTransportEvent.Found(diff.entry.endpoint))
            is EndpointDirectory.Diff.Updated -> emitEvent(FlashTransportEvent.Updated(diff.entry.endpoint))
            is EndpointDirectory.Diff.Lost -> emitEvent(
                FlashTransportEvent.Lost(diff.deviceId, findServiceNameFor(diff.deviceId)),
            )
            EndpointDirectory.Diff.Unchanged -> emitEvent(FlashTransportEvent.Presence(endpoint))
        }
    }

    // -- Lifecycle -------------------------------------------------------------

    override suspend fun stop(): FlashResult<Unit> {
        browsing = false
        advertising = false
        advertiseDesired = false
        emitState("Stopped")
        heartbeatJob?.cancel()
        heartbeatJob = null
        advertiseWatchdogJob?.cancel()
        advertiseWatchdogJob = null
        networkChangeJob?.cancel()
        networkChangeJob = null
        monitorRetryJobs.values.forEach { it.cancel() }
        monitorRetryJobs.clear()
        monitorRetries.clear()
        runCatching { bridge.stopBrowse() }
        runCatching { bridge.unadvertise(advertiseEvents) }
        runCatching { bridge.cancelMonitors() }
        runCatching { bridge.stopObservingNetworkChanges() }
        observingNetwork = false
        runCatching { bridge.setMulticastLock(false) }
        scope?.cancel()
        scope = null
        // Scope is down, so nothing can touch these concurrently any more.
        pendingLost.clear()
        monitoredServices.clear()
        synchronized(deviceIdsByServiceName) { deviceIdsByServiceName.clear() }
        // Drain the directory too. A stopped transport knows nothing; leaving entries
        // behind meant the NEXT browse session re-sighted the same peers, deduped them
        // to Unchanged, and (before Presence existed) published nothing at all — peers
        // that were physically present stayed invisible until their fields happened to
        // change. Restart must start from empty.
        runCatching { directory.sweepExpired(graceWindowMs = 0L, nowMs = Long.MAX_VALUE) }
        restartAttempt = 0
        dutyCyclesCompleted = 0
        browseStartedAtMs = 0L
        return FlashResult.Success(Unit)
    }

    // -- Helpers ---------------------------------------------------------------

    private fun resolutionStrategy(): ResolutionStrategy =
        if (apiLevel.sdkInt >= NsdApiThresholds.SDK_SERVICE_INFO_CALLBACK) {
            ResolutionStrategy.INFO_CALLBACK
        } else {
            ResolutionStrategy.LEGACY_RESOLVE_QUEUE
        }

    private fun useNetworkRequestDiscovery(): Boolean =
        apiLevel.sdkInt >= NsdApiThresholds.SDK_NETWORK_REQUEST_DISCOVERY

    private fun acquireMulticastLockIfNeeded() {
        // Take the Wi-Fi multicast lock on ALL API levels while browsing/advertising.
        // The T-extensions 7+ framework-managed multicast reception (approximated by
        // NsdApiThresholds.SDK_MULTICAST_LOCK_NOT_NEEDED = 34) only applies to FOREGROUND
        // apps. Flash discovers from a backgrounded connectedDevice FGS with the screen off,
        // where the framework does NOT deliver mDNS to us — so we must hold the explicit lock
        // regardless of API level, or returning peers are never re-discovered. setMulticastLock
        // is idempotent (guards on isHeld), so repeated acquire calls are safe.
        bridge.setMulticastLock(true)
    }

    private fun releaseMulticastLockIfIdle() {
        // advertiseDesired counts: dropping the lock while the advertise watchdog is mid-recovery
        // would take away the multicast reception the re-registration needs.
        if (advertising || browsing || advertiseDesired) return
        bridge.setMulticastLock(false)
    }

    /**
     * Publishes the CURRENT browse state with a human-readable reason.
     *
     * [FlashTransportEvent.StateChanged.browsing] reports the browse flag only.
     * It used to report `advertising || browsing`, which made "browsing" true for
     * an advertise-only transport and, worse, kept it true after the browse gave
     * up — so no consumer could ever detect a dead browse and re-arm it. The
     * advertising detail stays in [message].
     */
    private fun emitState(message: String) {
        emitEvent(FlashTransportEvent.StateChanged(browsing, message))
    }

    private fun emitEvent(event: FlashTransportEvent) {
        _events.tryEmit(event)
    }

    private fun ensureScope() {
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        }
    }

    public companion object {
        public const val TAG: String = "DISCOVERY"
        public const val DEFAULT_SERVICE_TYPE: String = "_flash-transfer._tcp."
        public const val DEFAULT_MAX_RESTARTS: Int = 5
        public const val MAX_NAME_LENGTH: Int = 24

        /**
         * Default radio-loss debounce (see the `lostDebounceMs` constructor param). ~6s comfortably
         * spans an mDNS re-announce interval, so a peer that is merely blinking (common on a phone
         * hotspot) is retained, while a genuinely departed peer clears within a few seconds.
         */
        public const val DEFAULT_LOST_DEBOUNCE_MS: Long = 6_000L

        /**
         * Presence heartbeat period (see the `presenceHeartbeatMs` constructor param).
         * 10s is comfortably inside [CompositeDiscovery]'s 30s grace window, so a live
         * peer is re-affirmed roughly three times before it could ever age out, and it
         * costs nothing on the radio (the heartbeat republishes cached state; it does
         * not transmit).
         */
        public const val DEFAULT_PRESENCE_HEARTBEAT_MS: Long = 10_000L

        /**
         * Base delay before retrying a monitor/resolve that failed to start (multiplied by the
         * attempt number). Deliberately far below [DEFAULT_PRESENCE_HEARTBEAT_MS]: the heartbeat
         * used to be the only retry path, so each failed resolve added a full 10 s to the time a
         * peer took to appear — the difference between "found in 2 seconds" and "found in 30".
         */
        public const val DEFAULT_MONITOR_RETRY_MS: Long = 600L

        /**
         * Fast monitor retries per service before deferring to the presence heartbeat. Four
         * attempts at a linear backoff cover ~6 s, which is longer than any transient mDNS
         * resolve failure observed on a phone hotspot, without spinning on a name the radio
         * genuinely cannot resolve.
         */
        public const val DEFAULT_MAX_MONITOR_RETRIES: Int = 4

        /**
         * Advertise watchdog period. Matches the presence heartbeat so a device that loses its
         * registration re-registers well inside a peer's 30 s grace window and never actually
         * disappears from that peer's list.
         */
        public const val DEFAULT_ADVERTISE_WATCHDOG_MS: Long = 10_000L

        /**
         * Debounce applied to connectivity callbacks before forcing a browse restart.
         * One Wi-Fi transition arrives as a burst (lost → available → available), and
         * restarting per callback would thrash the radio.
         */
        public const val DEFAULT_NETWORK_CHANGE_DEBOUNCE_MS: Long = 1_500L

        /**
         * Heartbeat ticks after a browse (re)start during which the heartbeat will NOT
         * evict endpoints the platform has not (yet) re-announced. A fresh browse needs
         * a moment to re-deliver `onServiceFound` for services that never went away;
         * evicting inside that window would fabricate exactly the false Lost the
         * heartbeat exists to prevent.
         */
        private const val EVICTION_GRACE_TICKS: Long = 2L

        private const val EVENT_BUFFER = 64
    }
}
