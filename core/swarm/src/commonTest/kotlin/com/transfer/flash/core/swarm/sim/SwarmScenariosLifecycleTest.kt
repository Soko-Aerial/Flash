package com.transfer.flash.core.swarm.sim

import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmScenariosLifecycleTest {

    @Test
    fun `SIM-07 - member removed mid-transfer gets NOT_MEMBER and deletes partial`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 30
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rxKeep = sim.addNode("rxKeep", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
        val rxDrop = sim.addNode("rxDrop", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Run until rxDrop has at least 3 pieces
        sim.queue.runUntil(15_000L) {
            rxDrop.storage.writtenBits.count() >= 3
        }

        // Membership changed: rxDrop is removed from allowed peers
        val allowed = setOf("origin", "rxKeep")
        origin.processCommands(origin.engine.handle(SwarmEvent.MembershipChanged("g1", allowed, localActive = true, nowMs = sim.clock.nowMs)))
        rxKeep.processCommands(rxKeep.engine.handle(SwarmEvent.MembershipChanged("g1", allowed, localActive = true, nowMs = sim.clock.nowMs)))
        val dropCmds = rxDrop.engine.handle(SwarmEvent.MembershipChanged("g1", allowed, localActive = false, nowMs = sim.clock.nowMs))
        rxDrop.processCommands(dropCmds)

        // Assert rxDrop entered FAILED/CANCELLED and partial is deleted
        assertTrue(rxDrop.storage.isPartialDeleted, "rxDrop partial file must be deleted upon removal")
        assertEquals(SwarmLifecycleState.FAILED, rxDrop.latestRow?.state)

        // Remaining honest node completes
        val success = sim.queue.runUntil(120_000L) { rxKeep.completed }
        assertTrue(success, "rxKeep should complete successfully")
    }

    @Test
    fun `SIM-08 - origin cancels while 2 nodes are offline offline nodes cancel before requesting`() {
        val sim = SwarmSimulation(seed = 55L)
        val pieceCount = 20
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rxOnline = sim.addNode("rxOnline", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
        val rxOff1 = sim.addNode("rxOff1", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)
        val rxOff2 = sim.addNode("rxOff2", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 3L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)

        // Disconnect offline nodes
        sim.network.disconnect("rxOff1")
        sim.network.disconnect("rxOff2")

        // Origin cancels as origin
        val cancelCmds = origin.engine.handle(
            SwarmEvent.LocalCancel("g1", manifest.root, asOrigin = true, nowMs = sim.clock.nowMs)
        )
        origin.processCommands(cancelCmds)

        // Run until online nodes receive cancel and cancel locally
        sim.queue.runUntil(sim.clock.nowMs + 5000L) {
            rxOnline.cancelled
        }
        assertTrue(rxOnline.cancelled, "Online node should cancel within one RTT")

        // Record frame counts for offline nodes before reconnect
        val sentBefore1 = rxOff1.sentFrames.size
        val sentBefore2 = rxOff2.sentFrames.size

        // Reconnect offline nodes
        sim.network.reconnect("rxOff1")
        sim.network.reconnect("rxOff2")

        // Let simulation process reconnect summary
        sim.queue.runUntil(sim.clock.nowMs + 5000L) {
            rxOff1.cancelled && rxOff2.cancelled
        }

        assertTrue(rxOff1.cancelled, "rxOff1 should cancel after reconnect")
        assertTrue(rxOff2.cancelled, "rxOff2 should cancel after reconnect")

        // Assert NO REQUEST frames were sent after reconnect
        val requests1 = rxOff1.sentFrames.drop(sentBefore1).count { it.second is SwarmFrame.Request }
        val requests2 = rxOff2.sentFrames.drop(sentBefore2).count { it.second is SwarmFrame.Request }
        assertEquals(0, requests1, "rxOff1 must not send any REQUEST frame after reconnecting to a cancelled content")
        assertEquals(0, requests2, "rxOff2 must not send any REQUEST frame after reconnecting to a cancelled content")
    }

    @Test
    fun `SIM-09 - non-origin forges cancel ignored and transfer completes`() {
        val sim = SwarmSimulation(seed = 66L)
        val pieceCount = 20
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rxGood = sim.addNode("rxGood", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
        val rxAttacker = sim.addNode("rxAttacker", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Attacker sends forged cancel (signature invalid)
        val forgedCancel = SwarmFrame.Cancel("g1", manifest.root, originId = "rxAttacker", messageId = "m1", reason = SwarmTombstoneReason.USER, cancelledAtMs = sim.clock.nowMs, signature = ByteArray(64))
        rxGood.receiveFrame("rxAttacker", forgedCancel)

        // Assert rxGood did not cancel
        assertFalse(rxGood.cancelled, "rxGood must not cancel on forged cancel frame")

        // Transfer completes
        val success = sim.runUntilComplete(maxVirtualTimeMs = 60_000L)
        assertTrue(success, "Transfer should complete successfully despite forged cancel")
        assertTrue(rxGood.completed)
    }

    @Test
    fun `SIM-10 - receiver crashes mid-download unsynced writes dropped and restarts`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 20
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rx = sim.addNode("rx1", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Download 5 pieces
        sim.queue.runUntil(15_000L) {
            rx.storage.writtenBits.count() >= 5
        }

        // Simulate crash: unsynced writes dropped
        rx.storage.crash()

        // Restart node engine
        rx.onPeerDown("origin")
        rx.onPeerUp("origin", setOf("sw1"))

        // Run until complete
        val success = sim.runUntilComplete(maxVirtualTimeMs = 60_000L)
        assertTrue(success, "Receiver should complete after restart")
        assertTrue(rx.completed)
    }

    @Test
    fun `SIM-11 - origin crashes and restarts members complete`() {
        val sim = SwarmSimulation(seed = 42L)
        val pieceCount = 20
        val (manifest, canonicalBytes) = sim.createManifest(pieceCount = pieceCount, pieceSize = 65536)

        val origin = sim.addNode("origin", isOrigin = true, manifest = manifest, canonicalBytes = canonicalBytes)
        val rx1 = sim.addNode("rx1", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 1L)
        val rx2 = sim.addNode("rx2", isOrigin = false, manifest = manifest, canonicalBytes = canonicalBytes, seedOffset = 2L)

        sim.connectAll()
        sim.announceContent("g1", "m1", "origin", manifest, canonicalBytes)
        sim.startPeriodicTicks(250L)

        // Run until rx1 has 3 pieces
        sim.queue.runUntil(10_000L) {
            rx1.storage.writtenBits.count() >= 3
        }

        // Origin crashes (disconnects, restarts)
        sim.network.disconnect("origin")
        val pauseTime = sim.clock.nowMs + 5_000L
        sim.queue.runUntil(pauseTime) { false }

        // Origin restarts and reconnects
        sim.network.reconnect("origin")

        val success = sim.runUntilComplete(maxVirtualTimeMs = 60_000L)
        assertTrue(success, "Members should complete after origin restart")
        assertTrue(rx1.completed && rx2.completed)
    }
}
