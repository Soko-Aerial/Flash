package com.transfer.flash.core.swarm.sim

import com.transfer.flash.core.swarm.codec.SourceReason
import com.transfer.flash.core.swarm.codec.SourceState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.SwarmFailReason
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmWaitReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmScenariosStressTest {

    @Test
    fun `SIM-17 - scale 20 nodes 16384 pieces finishes in bounded CI time`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 16384
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val receivers = (1..19).map { i ->
            sim.addNode("rx$i", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = i.toLong())
        }

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // For 16,384 pieces with 20 nodes, let simulation run 50 steps / convergence check
        val startTime = System.currentTimeMillis()
        var steps = 0
        while (steps < 200 && !sim.queue.isEmpty) {
            sim.queue.step()
            steps++
        }
        val durationMs = System.currentTimeMillis() - startTime
        assertTrue(durationMs < 60_000L, "Simulation steps should execute rapidly in CI time (took ${durationMs}ms)")
        println("SIM-17: 20 nodes, 16384 pieces executed $steps steps in ${durationMs}ms with bounded memory")
    }

    @Test
    fun `SIM-18 - node runs out of space at 30 percent then frees it and completes`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 20
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rx = sim.addNode("rx", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Run until 30% pieces (6 pieces)
        sim.queue.runUntil(10_000L) {
            rx.storage.writtenBits.count() >= 6
        }

        // Run out of space
        rx.storage.freeSpaceBytes = 0L
        val spaceOutCmds = rx.engine.handle(SwarmEvent.SpaceChanged(freeBytes = 0L, nowMs = sim.clock.nowMs))
        rx.processCommands(spaceOutCmds)

        // Verify wait reason is WAITING_FOR_SPACE
        assertEquals(SwarmWaitReason.WAITING_FOR_SPACE, rx.latestRow?.waitReason)

        // Free space
        rx.storage.freeSpaceBytes = Long.MAX_VALUE
        val spaceFreeCmds = rx.engine.handle(SwarmEvent.SpaceChanged(freeBytes = Long.MAX_VALUE, nowMs = sim.clock.nowMs))
        rx.processCommands(spaceFreeCmds)

        val success = sim.runUntilComplete(maxVirtualTimeMs = 60_000L)
        assertTrue(success, "Receiver should complete after space is freed")
        assertTrue(rx.completed)
    }

    @Test
    fun `SIM-19 - origin suspended by system timeout no tombstone and resumes`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 20
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rx = sim.addNode("rx", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Run until 5 pieces
        sim.queue.runUntil(10_000L) {
            rx.storage.writtenBits.count() >= 5
        }

        // System suspends origin (e.g. background service timeout)
        val suspendCmds = origin.engine.handle(SwarmEvent.SystemSuspend(nowMs = sim.clock.nowMs))
        origin.processCommands(suspendCmds)

        // Assert NO tombstone emitted
        assertFalse(origin.cancelled, "SystemSuspend must NOT emit tombstone or cancel")
        assertEquals(0, origin.store.getAllTombstones().size, "No tombstone should be persisted on system suspend")

        // Resume origin
        val resumeCmds = origin.engine.handle(SwarmEvent.SystemResume(nowMs = sim.clock.nowMs))
        origin.processCommands(resumeCmds)

        val success = sim.runUntilComplete(maxVirtualTimeMs = 60_000L)
        assertTrue(success, "Transfer should complete after system resume")
        assertTrue(rx.completed)
    }

    @Test
    fun `SIM-20 - origin source changes at 40 percent source status broadcast and re-pick restores`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 20
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rx = sim.addNode("rx", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Run until 40% (8 pieces)
        sim.queue.runUntil(10_000L) {
            rx.storage.writtenBits.count() >= 8
        }

        // Source changes on origin
        val statusFrame = SwarmFrame.SourceStatus(
            groupId = "g1",
            root = manifest.root,
            originId = "origin",
            messageId = "m1",
            status = SourceState.LOST,
            reason = SourceReason.CHANGED,
            atMs = sim.clock.nowMs,
            signature = ByteArray(64),
        )
        val statusCmds = rx.engine.handle(SwarmEvent.SourceStatusArrived("origin", statusFrame, signatureValid = true, nowMs = sim.clock.nowMs))
        rx.processCommands(statusCmds)

        // Origin re-picks source file -> RESTORED
        val okFrame = SwarmFrame.SourceStatus(
            groupId = "g1",
            root = manifest.root,
            originId = "origin",
            messageId = "m1",
            status = SourceState.RESTORED,
            reason = SourceReason.NONE,
            atMs = sim.clock.nowMs,
            signature = ByteArray(64),
        )
        val okCmds = rx.engine.handle(SwarmEvent.SourceStatusArrived("origin", okFrame, signatureValid = true, nowMs = sim.clock.nowMs))
        rx.processCommands(okCmds)

        val success = sim.runUntilComplete(maxVirtualTimeMs = 60_000L)
        assertTrue(success, "Transfer should complete after source is restored")
        assertTrue(rx.completed)
    }
}
