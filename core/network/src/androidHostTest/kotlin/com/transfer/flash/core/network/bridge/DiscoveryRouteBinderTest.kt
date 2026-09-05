package com.transfer.flash.core.network.bridge

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryRouteBinderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun endpoint(id: String, host: String, port: Int) = FlashDiscoveredEndpoint(
        device = FlashDevice(
            id = FlashDeviceId(id),
            friendlyName = "Phone $id",
            transportType = FlashTransportType.LAN,
        ),
        hostAddress = host,
        port = port,
        serviceName = "svc-$id",
    )

    @Test
    fun bindAll_routesEveryEndpointIntoMemory() {
        val routes = HashMap<String, Pair<String, Int>>()
        val memory = EndpointMemory { id, host, port -> routes[id] = host to port }

        DiscoveryRouteBinder.bindAll(
            memory,
            listOf(endpoint("a", "10.0.0.1", 1000), endpoint("b", "10.0.0.2", 2000)),
        )

        assertEquals(2, routes.size)
        assertEquals("10.0.0.1" to 1000, routes["a"])
        assertEquals("10.0.0.2" to 2000, routes["b"])
    }

    @Test
    fun observe_rebindsFullSnapshotOnEveryEmission() {
        val endpoints = MutableStateFlow<List<FlashDiscoveredEndpoint>>(emptyList())
        val routes = HashMap<String, Pair<String, Int>>()
        val memory = EndpointMemory { id, host, port -> routes[id] = host to port }

        val job = DiscoveryRouteBinder.observe(scope, endpoints, memory)

        // Emission 1: two peers appear.
        endpoints.value = listOf(endpoint("a", "10.0.0.1", 1000), endpoint("b", "10.0.0.2", 2000))
        // Emission 2: peer a changes address (Updated), b unchanged.
        endpoints.value = listOf(
            endpoint("a", "10.0.0.9", 1000),
            endpoint("b", "10.0.0.2", 2000),
        )
        // Emission 3: b disappears (Lost) — route refresh leaves only a's newest.
        endpoints.value = listOf(endpoint("a", "10.0.0.9", 1000))

        assertTrue(job.isActive)
        assertEquals("10.0.0.9" to 1000, routes["a"])
        assertEquals("10.0.0.2" to 2000, routes["b"]) // stale route retained; Lost cleanup is session-level
    }

    @Test
    fun observe_bindsCurrentSnapshotOnStart_lateCollectorSafe() = runBlockingCompat {
        val endpoints = MutableStateFlow(listOf(endpoint("early", "192.168.1.7", 45821)))
        val routes = HashMap<String, Pair<String, Int>>()
        val memory = EndpointMemory { id, host, port -> routes[id] = host to port }

        // StateFlow replays current value to late collectors — binder must bind it.
        DiscoveryRouteBinder.observe(scope, endpoints, memory)

        assertEquals("192.168.1.7" to 45821, routes["early"])
        Unit
    }

    @Test
    fun observe_forgetsEndpointsDroppedFromSnapshot_butLeavesUntrackedRoutes() {
        val endpoints = MutableStateFlow<List<FlashDiscoveredEndpoint>>(emptyList())
        val routes = HashMap<String, Pair<String, Int>>()
        // Full EndpointMemory (not SAM): honours both remember and forget, like the real network impls.
        val memory = object : EndpointMemory {
            override fun rememberEndpoint(deviceId: String, host: String, port: Int) {
                routes[deviceId] = host to port
            }

            override fun forgetEndpoint(deviceId: String) {
                routes.remove(deviceId)
            }
        }
        // An inbound-HELLO route the binder never bound — must survive discovery churn.
        routes["hello-only"] = "10.9.9.9" to 9999

        DiscoveryRouteBinder.observe(scope, endpoints, memory)

        // Emission 1: a + b discovered.
        endpoints.value = listOf(endpoint("a", "10.0.0.1", 1000), endpoint("b", "10.0.0.2", 2000))
        assertEquals("10.0.0.1" to 1000, routes["a"])
        assertEquals("10.0.0.2" to 2000, routes["b"])

        // Emission 2: b lost — its route is pruned, a stays, hello-only untouched.
        endpoints.value = listOf(endpoint("a", "10.0.0.1", 1000))
        assertEquals("10.0.0.1" to 1000, routes["a"])
        assertEquals(null, routes["b"])
        assertEquals("10.9.9.9" to 9999, routes["hello-only"])
    }

    private fun runBlockingCompat(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
