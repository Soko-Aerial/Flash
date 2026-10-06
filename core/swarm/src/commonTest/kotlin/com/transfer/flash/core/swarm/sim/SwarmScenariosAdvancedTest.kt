package com.transfer.flash.core.swarm.sim

import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.engine.SwarmConfig
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwarmScenariosAdvancedTest {

    @Test
    fun `SIM-12 - heterogeneous rates comparison against direct push`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 30
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rxFast = sim.addNode("rxFast", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
        val rxMed = sim.addNode("rxMed", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)
        val rxSlow = sim.addNode("rxSlow", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 3L)

        // Set asymmetric rates: Fast=20 MB/s, Med=5 MB/s, Slow=1 MB/s
        sim.network.setLink("origin", "rxFast", SimNetwork.LinkConfig(latencyMs = 5L, rateBytesPerMs = 20_000L))
        sim.network.setLink("origin", "rxMed", SimNetwork.LinkConfig(latencyMs = 10L, rateBytesPerMs = 5_000L))
        sim.network.setLink("origin", "rxSlow", SimNetwork.LinkConfig(latencyMs = 20L, rateBytesPerMs = 1_000L))

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        val startMs = sim.clock.nowMs
        val success = sim.runUntilComplete(maxVirtualTimeMs = 120_000L)
        assertTrue(success, "All heterogeneous nodes should complete")
        val swarmDurationMs = sim.clock.nowMs - startMs

        // Direct sequential push baseline: origin pushed 3 times sequentially
        val totalBytes = manifest.totalSize
        val directPushDurationMs = (totalBytes / 20_000L) + (totalBytes / 5_000L) + (totalBytes / 1_000L)

        println("SIM-12 REPORT: Swarm Duration = ${swarmDurationMs}ms, Direct Push Baseline = ${directPushDurationMs}ms")
        assertTrue(rxFast.completed && rxMed.completed && rxSlow.completed)
    }

    @Test
    fun `SIM-13 - ECO node never serves and still completes`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 20
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rxEco = sim.addNode(
            "rxEco",
            isOrigin = false,
            manifest = manifest,
            canonicalBytes = canonicalBytes,
            config = SwarmConfig(autoAcceptIncoming = true, servingEnabled = false),
            seedOffset = 1L,
        )
        val rxNormal = sim.addNode("rxNormal", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        val success = sim.runUntilComplete(maxVirtualTimeMs = 60_000L)
        assertTrue(success, "ECO receiver must complete successfully")
        assertTrue(rxEco.completed && rxNormal.completed)
        assertEquals(0L, rxEco.uploadedBytes, "ECO node must never serve any bytes")
    }

    @Test
    fun `SIM-14 - node without sw1 never receives FSW1 frames`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 10
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rxSwarm = sim.addNode("rxSwarm", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
        val rxLegacy = sim.addNode("rxLegacy", isOrigin = false, isLegacy = true, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        val success = sim.queue.runUntil(60_000L) { rxSwarm.completed }
        assertTrue(success, "Swarm receiver should complete")
        assertEquals(0, rxLegacy.receivedFrames.size, "Legacy node without sw1 must never receive FSW1 frames")
    }

    @Test
    fun `SIM-15 - cross group node never serves G1 root to G2 only member`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 10
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val server = sim.addNode("server", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val g2Peer = sim.addNode("g2Peer", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)

        // server disallows g2Peer for group G1
        server.disallowedPeers.add("g2Peer")

        sim.connectAll()
        sim.announceContent("G1", "m1", "server", manifest, canonicalBytes)

        // g2Peer requests piece from G1
        val req = SwarmFrame.Request("G1", manifest.root, listOf(0))
        server.receiveFrame("g2Peer", req)

        // Server must reply with REJECT(NOT_MEMBER)
        val reject = server.sentFrames.filter { it.first == "g2Peer" && it.second is SwarmFrame.Reject }
            .map { it.second as SwarmFrame.Reject }
            .firstOrNull()

        assertTrue(reject != null, "Server must send Reject for cross-group request")
        assertEquals(SwarmRejectReason.NOT_MEMBER, reject.reason)
    }

    @Test
    fun `SIM-16 - request flood from one node serve caps hold others unstarved`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 30
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rxHonest = sim.addNode("rxHonest", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
        val rxFlooder = sim.addNode("rxFlooder", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Flooder sends 50 requests in rapid succession
        for (p in 0 until 50) {
            origin.receiveFrame("rxFlooder", SwarmFrame.Request("g1", manifest.root, listOf(p % pieceCount)))
        }

        // Honest node should complete without being starved
        val success = sim.queue.runUntil(60_000L) { rxHonest.completed }
        assertTrue(success, "Honest receiver must complete despite request flood")
    }
}
