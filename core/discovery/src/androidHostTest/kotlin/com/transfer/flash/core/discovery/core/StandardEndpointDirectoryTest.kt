package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardEndpointDirectoryTest {

    private fun endpoint(
        id: String,
        host: String = "192.168.1.$id",
        port: Int = 40_000,
        serviceName: String = "svc-$id",
        friendlyName: String = "Phone $id",
        protocolVersion: Int = 1,
    ) = FlashDiscoveredEndpoint(
        device = com.transfer.flash.core.common.model.FlashDevice(
            id = FlashDeviceId(id),
            friendlyName = friendlyName,
            transportType = com.transfer.flash.core.common.model.FlashTransportType.LAN,
            protocolVersion = protocolVersion,
        ),
        hostAddress = host,
        port = port,
        serviceName = serviceName,
    )

    @Test
    fun applySeen_newPeer_returnsFoundWithFirstAndLastSeen() {
        val dir = StandardEndpointDirectory()
        val diff = dir.applySeen(endpoint("a"), nowMs = 100)
        val entry = (diff as EndpointDirectory.Diff.Found).entry
        assertEquals(100L, entry.firstSeenAtMs)
        assertEquals(100L, entry.lastSeenAtMs)
    }

    @Test
    fun applySeen_identicalResighting_touchesLastSeen_returnsUnchanged_preservesFirstSeen() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a"), nowMs = 100)

        val diff = dir.applySeen(endpoint("a"), nowMs = 500)
        assertTrue(diff is EndpointDirectory.Diff.Unchanged)
        assertEquals(100L, dir.get(FlashDeviceId("a"))?.firstSeenAtMs)
        assertEquals(500L, dir.get(FlashDeviceId("a"))?.lastSeenAtMs)
    }

    @Test
    fun applySeen_hostAddressChange_returnsUpdated_withPrevious() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a", host = "10.0.0.2"), nowMs = 100)

        val diff = dir.applySeen(endpoint("a", host = "10.0.0.9"), nowMs = 200)
        val updated = diff as EndpointDirectory.Diff.Updated
        assertEquals("10.0.0.9", updated.entry.endpoint.hostAddress)
        assertEquals("10.0.0.2", updated.previous.endpoint.hostAddress)
        assertEquals(100L, updated.entry.firstSeenAtMs)
    }

    @Test
    fun applySeen_portChange_returnsUpdated() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a", port = 1), nowMs = 100)
        assertTrue(dir.applySeen(endpoint("a", port = 2), nowMs = 200) is EndpointDirectory.Diff.Updated)
    }

    @Test
    fun applySeen_nameChange_returnsUpdated() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a", friendlyName = "Old Name"), nowMs = 100)
        assertTrue(
            dir.applySeen(endpoint("a", friendlyName = "New Name"), nowMs = 200)
                is EndpointDirectory.Diff.Updated,
        )
    }

    @Test
    fun applySeen_protocolVersionChange_returnsUpdated() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a", protocolVersion = 1), nowMs = 100)
        assertTrue(
            dir.applySeen(endpoint("a", protocolVersion = 2), nowMs = 200)
                is EndpointDirectory.Diff.Updated,
        )
    }

    @Test
    fun applyLost_knownPeer_removesAndReturnsLost_unknownReturnsUnchanged() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a"), nowMs = 100)

        assertTrue(dir.applyLost(FlashDeviceId("a")) is EndpointDirectory.Diff.Lost)
        assertNull(dir.get(FlashDeviceId("a")))
        assertTrue(dir.applyLost(FlashDeviceId("a")) is EndpointDirectory.Diff.Unchanged)
        assertTrue(dir.applyLost(FlashDeviceId("ghost")) is EndpointDirectory.Diff.Unchanged)
    }

    @Test
    fun sweep_boundary_exactlyAtWindowIsExpired_geComparison() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a"), nowMs = 1_000)

        // One millisecond BEFORE the window: not expired.
        assertTrue(dir.sweepExpired(graceWindowMs = 30_000, nowMs = 30_999).isEmpty())
        assertEquals(1, dir.snapshot().size)

        // Exactly AT the window boundary (now - lastSeen == grace): expired.
        val lost = dir.sweepExpired(graceWindowMs = 30_000, nowMs = 31_000)
        assertEquals(1, lost.size)
        assertEquals(FlashDeviceId("a"), lost[0].deviceId)
        assertTrue(dir.snapshot().isEmpty())
    }

    @Test
    fun sweep_negativeAge_neverExpires() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a"), nowMs = 5_000)
        assertTrue(dir.sweepExpired(graceWindowMs = 1_000, nowMs = 4_999).isEmpty())
        assertEquals(1, dir.snapshot().size)
    }

    @Test
    fun sweep_reportsEachAgedPeerOnce_only() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("a"), nowMs = 0)
        // Seen late enough to survive the FIRST sweep (age 5s < 30s) but well
        // inside grace for the SECOND one too (age 15s) — isolates once-only reporting.
        dir.applySeen(endpoint("b"), nowMs = 45_000)

        val first = dir.sweepExpired(graceWindowMs = 30_000, nowMs = 50_000)
        assertEquals(setOf(FlashDeviceId("a")), first.map { it.deviceId }.toSet())

        // Second sweep at a later instant must NOT re-report the same peer.
        val second = dir.sweepExpired(graceWindowMs = 30_000, nowMs = 60_000)
        assertTrue(second.isEmpty())
    }

    @Test
    fun snapshot_orderedByLastSeenDesc_thenDeviceIdAscendingForStability() {
        val dir = StandardEndpointDirectory()
        dir.applySeen(endpoint("b"), nowMs = 100)
        dir.applySeen(endpoint("a"), nowMs = 300)
        dir.applySeen(endpoint("c"), nowMs = 300)

        val ids = dir.snapshot().map { it.endpoint.deviceId.value }
        // a and c share lastSeen=300 → deviceId ascending; b (older) last.
        assertEquals(listOf("a", "c", "b"), ids)
    }
}
