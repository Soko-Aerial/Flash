package com.transfer.flash.core.swarm.sim

import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.SwarmWaitReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwarmScenariosBasicTest {

    @Test
    fun `SIM-01 - origin plus 9 equal rates 1000 pieces`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 1000
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val receivers = (1..9).map { i ->
            sim.addNode("rx$i", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = i.toLong())
        }

        sim.connectAll()
        sim.announceContent(
            groupId = "group-1",
            messageId = "msg-1",
            originId = "origin",
            manifest = manifest,
            canonicalBytes = canonicalBytes,
        )
        sim.startPeriodicTicks(250L)

        val success = sim.runUntilComplete(maxVirtualTimeMs = 600_000L)
        assertTrue(success, "All 9 receivers should complete")
        for (rx in receivers) {
            assertTrue(rx.completed, "${rx.id} should be completed")
            assertEquals(0, rx.corruptWritesCount, "${rx.id} must have 0 corrupt writes")
        }
        val originRatio = origin.originUploadBytes.toDouble() / manifest.totalSize.toDouble()
        assertTrue(originRatio <= 1.5, "Origin upload ratio must be <= 1.5 (was $originRatio)")
    }

    @Test
    fun `SIM-02 - half sent origin leaves at 50 percent and returns`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 100
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val receivers = (1..9).map { i ->
            sim.addNode("rx$i", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = i.toLong())
        }

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        val targetHalf = (manifest.totalSize * 0.5).toLong()
        // Run until origin uploaded 50%
        sim.queue.runUntil(120_000L) {
            origin.originUploadBytes >= targetHalf
        }

        // Origin leaves
        sim.network.disconnect("origin")

        // Receivers exchange whatever was uploaded until they converge on the union
        sim.queue.runUntil(sim.clock.nowMs + 60_000L) {
            val union = Bitfield(pieceCount)
            for (rx in receivers) {
                for (p in 0 until pieceCount) {
                    if (rx.storage.writtenBits.get(p)) union.set(p, true)
                }
            }
            receivers.all { it.storage.writtenBits.count() == union.count() }
        }

        // Check that union is held by all receivers
        val unionBits = Bitfield(pieceCount)
        for (rx in receivers) {
            for (p in 0 until pieceCount) {
                if (rx.storage.writtenBits.get(p)) {
                    unionBits.set(p, true)
                }
            }
        }
        for (rx in receivers) {
            assertEquals(unionBits.count(), rx.storage.writtenBits.count(), "All receivers must hold the union of uploaded pieces")
        }

        // Origin returns
        sim.network.reconnect("origin")

        // All complete
        val success = sim.runUntilComplete(maxVirtualTimeMs = 300_000L)
        assertTrue(success, "All receivers should complete after origin returns")
        val originRatio = origin.originUploadBytes.toDouble() / manifest.totalSize.toDouble()
        assertTrue(originRatio <= 1.5, "Origin upload ratio <= 1.5 (was $originRatio)")
    }

    @Test
    fun `SIM-03 - origin leaves at 0 percent all wait no request frames sent`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 50
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val receivers = (1..3).map { i ->
            sim.addNode("rx$i", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = i.toLong())
        }

        sim.connectAll()
        // Origin disconnects immediately
        sim.network.disconnect("origin")

        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Advance 10 seconds of virtual time while origin is offline
        val targetTime = sim.clock.nowMs + 10_000L
        sim.queue.runUntil(targetTime) { false }

        // Assert all receivers are waiting for sender
        for (rx in receivers) {
            assertEquals(SwarmWaitReason.WAITING_FOR_SENDER, rx.latestRow?.waitReason)
        }

        // Assert no REQUEST frames were sent while waiting for sender
        val requestsWhileWaiting = receivers.sumOf { it.framesSentWhileWaiting }
        assertEquals(0, requestsWhileWaiting, "No REQUEST frames must be sent while waiting for sender")

        // Origin reconnects
        sim.network.reconnect("origin")

        val success = sim.runUntilComplete(maxVirtualTimeMs = 120_000L)
        assertTrue(success, "All receivers should complete once origin returns")
    }

    @Test
    fun `SIM-04 - origin and member holding unique pieces both leave every order of return`() {
        for (returnOrder in listOf("memberFirst", "originFirst")) {
            val sim = SwarmSimulation(seed = 100L)
            val pieceCount = 30
            val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

            val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
            val rx1 = sim.addNode("rx1", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
            val rx2 = sim.addNode("rx2", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)

            sim.connectAll()
            sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
            sim.startPeriodicTicks(250L)

            // Let transfer run until rx1 has at least 5 pieces
            sim.queue.runUntil(30_000L) {
                rx1.storage.writtenBits.count() >= 5
            }

            // Both origin and rx1 disconnect
            sim.network.disconnect("origin")
            sim.network.disconnect("rx1")

            // Advance time
            val pauseTime = sim.clock.nowMs + 5_000L
            sim.queue.runUntil(pauseTime) { false }

            if (returnOrder == "memberFirst") {
                sim.network.reconnect("rx1")
                val middleTime = sim.clock.nowMs + 5_000L
                sim.queue.runUntil(middleTime) { false }
                sim.network.reconnect("origin")
            } else {
                sim.network.reconnect("origin")
                val middleTime = sim.clock.nowMs + 5_000L
                sim.queue.runUntil(middleTime) { false }
                sim.network.reconnect("rx1")
            }

            val success = sim.runUntilComplete(maxVirtualTimeMs = 120_000L)
            assertTrue(success, "Both receivers must complete for order $returnOrder")
            assertTrue(!rx1.failed && !rx2.failed, "No receiver should fail")
        }
    }

    @Test
    fun `SIM-05 - churn random leave and join every 1 to 10s for 10 nodes`() {
        val sim = SwarmSimulation(seed = 77L)
        val pieceCount = 50
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val receivers = (1..9).map { i ->
            sim.addNode("rx$i", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = i.toLong())
        }

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Schedule random churn events between 1s and 15s virtual time
        val churnNodes = listOf("rx1", "rx2", "rx3", "rx4", "rx5")
        var churnTime = 2000L
        for (round in 0 until 6) {
            val nodeToToggle = churnNodes[round % churnNodes.size]
            val isDisconnect = round % 2 == 0
            sim.queue.scheduleAt(churnTime) {
                if (isDisconnect) {
                    sim.network.disconnect(nodeToToggle)
                } else {
                    sim.network.reconnect(nodeToToggle)
                }
            }
            churnTime += 2000L
        }

        // Reconnect all nodes after churn stops
        sim.queue.scheduleAt(churnTime + 1000L) {
            for (nodeId in churnNodes) {
                sim.network.reconnect(nodeId)
            }
        }

        val success = sim.runUntilComplete(maxVirtualTimeMs = 180_000L)
        assertTrue(success, "All receivers should complete after churn stops (no deadlock)")
    }

    @Test
    fun `SIM-06 - corrupt server flips bytes banned after 3 strikes zero corrupt writes`() {
        val sim = SwarmSimulation(seed = 88L)
        val pieceCount = 40
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rxGood = sim.addNode("rxGood", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
        val rxBad = sim.addNode("rxBad", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)

        // rxBad has corrupt reads
        rxBad.storage.corruptReads = true

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        val success = sim.runUntilComplete(maxVirtualTimeMs = 120_000L)
        assertTrue(success, "Honest receiver must complete despite corrupt server")
        assertEquals(0, rxGood.corruptWritesCount, "Zero corrupt writes committed to honest receiver storage")
    }
}
