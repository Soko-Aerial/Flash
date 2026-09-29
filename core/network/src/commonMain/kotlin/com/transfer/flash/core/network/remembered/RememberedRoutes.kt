package com.transfer.flash.core.network.remembered

import com.transfer.flash.core.network.planner.ConnectionPlanner
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Remembered routes for paired peers (DR1, `docs/network/DISCOVERY-RESILIENCE-PLAN.md` §3.3 A,
 * ADR-047): the addresses a peer was last *authenticated* at, kept across restarts, so a network
 * that filters multicast does not make a just-connected peer unreachable.
 *
 * ## What this is, and is not
 * - A **dial hint source for the PC2 planner**, exactly like a PC4 tip. It never claims a peer is
 *   online: discovery's endpoint list is untouched, so PC3's Online/Offline states (which come from
 *   discovery and presence) do not change. A remembered peer with no live session is still Offline
 *   in the UI until a dial succeeds.
 * - **Not an identity source.** A route is dialed with the peer's device id named, so the TLS pin is
 *   checked during the handshake (`connectManual(host, port, deviceId)`). A stale route costs one
 *   failed dial and cannot reach the wrong device.
 * - **Separate from the network's `knownEndpoints`.** That table is the discovery route table and
 *   `forgetEndpoint` still deletes from it when discovery drops a peer (#17). Routes here survive
 *   that, which is the "forgetEndpoint removes only the discovery route" rule of the plan without
 *   changing `forgetEndpoint` itself.
 *
 * ## Rules
 * 1. **Paired peers only** (owner decision D1, pending confirmation): [isPaired] is asked on every
 *    write and every read, and rows of a peer that is no longer paired are deleted at [load].
 * 2. **Written only after an authenticated session** ([onAuthenticated], called by the network once
 *    TLS and HELLO have proven the key), never from a discovery sighting.
 * 3. **Up to [MAX_ROUTES_PER_PEER] routes per peer**, newest first (Wi-Fi, hotspot, Ethernet).
 * 4. **A live sighting outranks a remembered route.** [sightings] is meant to be appended *after*
 *    discovery's and the tips' sightings: the planner keeps the first sighting per device id.
 * 5. **A pin mismatch deletes that one route** ([onIdentityMismatch]): the address now belongs to
 *    someone else. The peer's other routes stay.
 * 6. **Expiry needs both time and failures**: a route is dropped only after [EXPIRE_FAILURES]
 *    failed dials *and* at least [EXPIRE_AFTER_MS] since its first failure, so a laptop that was
 *    switched off for a weekend keeps its route.
 * 7. **Failed routes back off** ([BACKOFF_BASE_MS] doubling to [BACKOFF_MAX_MS]) so an absent peer
 *    costs a few dials an hour, not four a minute; [resetBackoff] clears it on a network change.
 *    While one route is backing off the next-best is offered, so several routes are rotated
 *    through at the planner's per-peer cadence.
 *
 * Thread-safe without a lock: state is immutable and replaced by compare-and-set (`core:network`
 * declares no expect/actual, see the planner). Storage runs on one coroutine in submission order,
 * and a storage failure only costs persistence, never a dial.
 *
 * @param store null means memory only (resume-across-restart disabled), the same convention as
 *   `TransferStore`.
 * @param hasLiveSession lets a dial that "failed" only because the peer's own session won a glare
 *   race count as no failure at all.
 * @param wallClockMs epoch millis; persisted, so it cannot be monotonic. Injected because
 *   commonMain has no clock of its own.
 * @param monotonicMs backoff clock; injectable so tests can run on virtual time.
 */
public class RememberedRoutes(
    private val scope: CoroutineScope,
    private val store: RememberedEndpointStore?,
    private val isPaired: (deviceId: String) -> Boolean,
    private val wallClockMs: () -> Long,
    private val hasLiveSession: (deviceId: String) -> Boolean = { false },
    private val monotonicMs: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } },
    private val log: (String) -> Unit = {},
) : RouteObserver {

    private data class Entry(
        val route: RememberedRoute,
        /** In memory only: a restart forgets it, which only delays expiry. */
        val failures: Int = 0,
        val retryAtMs: Long = 0L,
    )

    private val state = MutableStateFlow<Map<String, List<Entry>>>(emptyMap())
    private val writes = Channel<suspend (RememberedEndpointStore) -> Unit>(Channel.UNLIMITED)

    /**
     * Every remembered route, ordered. Emits only when the set of routes changes (not on a failure
     * count or a backoff), so it is safe to hand to the connector as a wake edge.
     */
    public val changes: Flow<List<RememberedRoute>> = state
        .map { byPeer -> byPeer.values.flatten().map { it.route }.sortedWith(ROUTE_ORDER) }
        .distinctUntilChanged()

    /**
     * Loads the stored routes, then runs the storage writer until [scope] ends. Events that arrive
     * before the load finishes are kept: loaded rows only fill gaps, they never overwrite a route
     * this run has already seen.
     */
    public fun start(): Job = scope.launch {
        load()
        val s = store ?: return@launch
        for (op in writes) {
            try {
                op(s)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log("Remembered routes: storage write failed: ${t.message}")
            }
        }
    }

    /** Exposed for tests and for hosts that want the routes before the writer starts. */
    public suspend fun load() {
        val s = store ?: return
        val rows = try {
            s.loadAll()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("Remembered routes: load failed, continuing without stored routes: ${t.message}")
            return
        }
        val (paired, unpaired) = rows.partition { isPaired(it.deviceId) }
        unpaired.map { it.deviceId }.distinct().forEach { id -> enqueue { deleteForDevice(id) } }
        transact { current ->
            var next = current
            for ((id, loaded) in paired.groupBy { it.deviceId }) {
                val existing = next[id].orEmpty()
                val fresh = loaded.filter { row -> existing.none { it.route.host == row.host && it.route.port == row.port } }
                    .map { Entry(it) }
                next = next + (id to (existing + fresh).sortedByDescending { it.route.lastConnectedAtMs }.take(MAX_ROUTES_PER_PEER))
            }
            next to Unit
        }
        log("Remembered routes: loaded ${paired.size} route(s) for ${paired.map { it.deviceId }.distinct().size} paired peer(s)")
    }

    /**
     * The best route to try for each paired peer that has one and is not backing off. Append these
     * *after* discovery's and the tips' sightings (rule 4). The name is the id: a route has no
     * display name.
     */
    public fun sightings(): List<ConnectionPlanner.Sighting> {
        val now = monotonicMs()
        return state.value.entries
            .sortedBy { it.key }
            .mapNotNull { (deviceId, entries) ->
                if (!isPaired(deviceId)) return@mapNotNull null
                entries.filter { it.retryAtMs <= now }
                    .minWithOrNull(compareBy<Entry> { it.failures }.thenByDescending { it.route.lastConnectedAtMs })
                    ?.let { ConnectionPlanner.Sighting(deviceId, it.route.host, it.route.port, deviceId) }
            }
    }

    /** The route a planner dial for [deviceId] at [host]:[port] came from, or null for another source. */
    public fun routeFor(deviceId: String?, host: String, port: Int): RememberedRoute? =
        deviceId?.let { state.value[it] }?.firstOrNull { it.route.host == host && it.route.port == port }?.route

    /**
     * Reports that a dial made from [sightings] failed. A no-op while the peer has a live session
     * (a dial that lost a glare race is not a bad route). Otherwise the route backs off, and it
     * expires under rule 6.
     */
    public fun onDialFailed(route: RememberedRoute) {
        if (hasLiveSession(route.deviceId)) return
        val now = wallClockMs()
        val mono = monotonicMs()
        val outcome = transact<FailureOutcome?> { current ->
            val entries = current[route.deviceId].orEmpty()
            val entry = entries.firstOrNull { it.route.host == route.host && it.route.port == route.port }
                ?: return@transact current to null
            val failures = entry.failures + 1
            val firstFailure = entry.route.firstFailureAtMs ?: now
            if (failures >= EXPIRE_FAILURES && now - firstFailure >= EXPIRE_AFTER_MS) {
                return@transact current + (route.deviceId to entries.filterNot { it === entry }) to FailureOutcome.Expired
            }
            val updated = entry.copy(
                route = entry.route.copy(firstFailureAtMs = firstFailure),
                failures = failures,
                retryAtMs = mono + backoffMs(failures),
            )
            val next = current + (route.deviceId to entries.map { if (it === entry) updated else it })
            next to (if (entry.route.firstFailureAtMs == null) FailureOutcome.FirstFailure(updated.route) else FailureOutcome.Counted)
        }
        when (outcome) {
            FailureOutcome.Expired -> {
                log("Remembered route expired peer=${route.deviceId} at ${route.host}:${route.port}")
                enqueue { delete(route.deviceId, route.host, route.port) }
            }
            is FailureOutcome.FirstFailure -> enqueue { save(outcome.route) }
            FailureOutcome.Counted, null -> Unit
        }
    }

    /** Clears every backoff so the next sweep may dial all routes: a network change, screen-on, a manual retry. */
    public fun resetBackoff() {
        transact { current ->
            if (current.values.all { list -> list.all { it.retryAtMs == 0L } }) return@transact current to Unit
            current.mapValues { (_, list) -> list.map { it.copy(retryAtMs = 0L) } } to Unit
        }
    }

    /** Drops every route of [deviceId], e.g. after the peer was unpaired. */
    public fun forgetPeer(deviceId: String) {
        transact { current -> (if (deviceId in current) current - deviceId else current) to Unit }
        enqueue { deleteForDevice(deviceId) }
    }

    override fun onAuthenticated(deviceId: String, host: String, port: Int) {
        if (!isPaired(deviceId)) return
        val now = wallClockMs()
        val fresh = RememberedRoute(deviceId, host, port, lastConnectedAtMs = now)
        val outcome = transact { current ->
            val entries = current[deviceId].orEmpty()
            val old = entries.firstOrNull { it.route.host == host && it.route.port == port }
            val ordered = listOf(Entry(fresh)) + entries.filterNot { it === old }
            val write = old == null || old.route.firstFailureAtMs != null ||
                now - old.route.lastConnectedAtMs >= PERSIST_REFRESH_MS
            (current + (deviceId to ordered.take(MAX_ROUTES_PER_PEER))) to
                Authenticated(write, ordered.drop(MAX_ROUTES_PER_PEER).map { it.route }, isNew = old == null)
        }
        if (outcome.isNew) log("Remembered route saved peer=$deviceId at $host:$port")
        if (outcome.write) enqueue { save(fresh) }
        outcome.evicted.forEach { gone -> enqueue { delete(gone.deviceId, gone.host, gone.port) } }
    }

    override fun onIdentityMismatch(deviceId: String, host: String, port: Int) {
        transact { current ->
            val entries = current[deviceId].orEmpty()
            val kept = entries.filterNot { it.route.host == host && it.route.port == port }
            (if (kept.size == entries.size) current else current + (deviceId to kept)) to Unit
        }
        log("Remembered route dropped after a key mismatch peer=$deviceId at $host:$port")
        // Always issued: the row may exist in storage before the load has reached memory.
        enqueue { delete(deviceId, host, port) }
    }

    private sealed interface FailureOutcome {
        data object Expired : FailureOutcome
        data object Counted : FailureOutcome
        class FirstFailure(val route: RememberedRoute) : FailureOutcome
    }

    private class Authenticated(val write: Boolean, val evicted: List<RememberedRoute>, val isNew: Boolean)

    private fun enqueue(op: suspend RememberedEndpointStore.() -> Unit) {
        if (store != null) writes.trySend(op)
    }

    private fun <R> transact(f: (Map<String, List<Entry>>) -> Pair<Map<String, List<Entry>>, R>): R {
        while (true) {
            val current = state.value
            val (next, result) = f(current)
            if (next === current || state.compareAndSet(current, next)) return result
        }
    }

    public companion object {
        /** Plan §3.3 A: Wi-Fi, hotspot and Ethernet, newest first. */
        public const val MAX_ROUTES_PER_PEER: Int = 4

        /** Rule 6: failed dials before a route may expire. */
        public const val EXPIRE_FAILURES: Int = 5

        /** Rule 6: the least time between a route's first failure and its expiry. */
        public const val EXPIRE_AFTER_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Rule 7: the wait after the first failure; doubles per failure. */
        public const val BACKOFF_BASE_MS: Long = 30_000L

        public const val BACKOFF_MAX_MS: Long = 10L * 60 * 1000

        /** A route that just worked is rewritten at most this often, so a flapping session costs no write per flap. */
        public const val PERSIST_REFRESH_MS: Long = 60_000L

        internal fun backoffMs(failures: Int): Long {
            val doublings = (failures - 1).coerceIn(0, 16)
            return minOf(BACKOFF_BASE_MS shl doublings, BACKOFF_MAX_MS)
        }

        private val ROUTE_ORDER: Comparator<RememberedRoute> =
            compareBy<RememberedRoute> { it.deviceId }.thenByDescending { it.lastConnectedAtMs }.thenBy { it.host }.thenBy { it.port }
    }
}
