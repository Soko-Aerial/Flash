package com.transfer.flash.core.network.bridge

import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Sink side of the C3→C4 seam: endpoint memory (implemented by DefaultFlashNetwork). */
fun interface EndpointMemory {
    fun rememberEndpoint(deviceId: String, host: String, port: Int)

    /**
     * #17: drop a route the discovery layer no longer advertises so the "discovered peers"
     * set (and `peerCountDiscovered`) shrinks instead of growing monotonically. Default no-op
     * keeps SAM constructors working; real network impls override to prune + refresh state.
     */
    fun forgetEndpoint(deviceId: String) {}
}

/**
 * C3→C4 seam (plan §3 A12 / C4): binds discovered endpoints into the network
 * layer's route memory so `connect(device)` resolves host/port by deviceId.
 *
 * Pure plumbing, JVM-testable: flows/scope injected.
 */
object DiscoveryRouteBinder {

    /** Binds one snapshot immediately (also used per-emission by [observe]). */
    fun bindAll(memory: EndpointMemory, endpoints: List<FlashDiscoveredEndpoint>) {
        endpoints.forEach { ep -> memory.rememberEndpoint(ep.deviceId.value, ep.hostAddress, ep.port) }
    }

    /**
     * Keeps [memory] updated for as long as [scope] lives. Conflation-safe:
     * every emission re-binds the FULL snapshot, so missed intermediate states
     * never leave stale routes behind (routes are additive; Lost handling is
     * the network layer's session concern).
     *
     * #17: reconciles against the previous snapshot — device ids that dropped out
     * of the discovery set are forgotten so `peerCountDiscovered` shrinks. Only ids
     * this observer itself bound are ever forgotten, so inbound-HELLO endpoints
     * (never present in the discovery snapshot) are left untouched.
     */
    fun observe(
        scope: kotlinx.coroutines.CoroutineScope,
        endpoints: StateFlow<List<FlashDiscoveredEndpoint>>,
        memory: EndpointMemory,
    ): Job = scope.launch {
        var previousIds = emptySet<String>()
        endpoints.collect { snapshot ->
            val currentIds = snapshot.mapTo(HashSet()) { it.deviceId.value }
            (previousIds - currentIds).forEach { memory.forgetEndpoint(it) }
            bindAll(memory, snapshot)
            previousIds = currentIds
        }
    }
}
