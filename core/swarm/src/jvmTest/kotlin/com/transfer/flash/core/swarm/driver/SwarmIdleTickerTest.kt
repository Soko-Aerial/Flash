package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.engine.SwarmConfig
import com.transfer.flash.core.swarm.engine.SwarmEngine
import com.transfer.flash.core.swarm.engine.SwarmEvent
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPT-O08-05: the driver ticked at 4 Hz for the lifetime of the process, with zero swarm content, allocating a tick
 * event and running the engine sweep over an empty map — 14,400 wake-ups an hour.
 *
 * The ticker now runs at 4 Hz only while a content is offered or transferring, and at 0.2 Hz otherwise, waking early
 * when an announcement, an inbound frame or a resume arrives.
 */
class SwarmIdleTickerTest {

    private fun manifest(pieceCount: Int = 4): SwarmManifest {
        val pieceSize = 65_536
        val total = pieceCount.toLong() * pieceSize
        val unsigned = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = total,
            fileSha256 = Sha256.digest(ByteArray(pieceCount) { it.toByte() }),
            pieceHashes = List(pieceCount) { Sha256.digest(ByteArray(64) { b -> (it + b).toByte() }) },
            root = ContentRoot.fromBytes(ByteArray(32)),
        )
        return unsigned.copy(root = ManifestCodec.computeRoot(ManifestCodec.encodeCanonical(unsigned)))
    }

    private fun announce(m: SwarmManifest, autoAccept: Boolean) = SwarmEvent.Announced(
        groupId = "g1",
        messageId = "m1",
        originId = "origin",
        originKey = "keyOrig",
        root = m.root,
        totalSize = m.totalSize,
        pieceSize = m.pieceSize,
        fileName = "f.bin",
        mime = "application/octet-stream",
        sentAtMs = 1_000L,
        expiresAtMs = 10_000_000L,
        isOrigin = false,
        autoAccept = autoAccept,
        nowMs = 1_000L,
    )

    @Test
    fun `the idle period is twenty times the active one`() {
        assertEquals(250L, tickDelayMs(hasActiveWork = true))
        assertEquals(5_000L, tickDelayMs(hasActiveWork = false))
        // 4 Hz -> 0.2 Hz: 14,400 wake-ups an hour become 720.
        assertEquals(14_400L, 3_600_000L / tickDelayMs(hasActiveWork = true))
        assertEquals(720L, 3_600_000L / tickDelayMs(hasActiveWork = false))
    }

    @Test
    fun `the engine reports work only while a content is offered or transferring`() {
        val m = manifest()
        val engine = SwarmEngine(SwarmConfig(autoAcceptIncoming = false), "local", seed = 1L)
        assertFalse(engine.hasActiveWork(), "nothing announced yet")

        // An offer that waits for a person is work: its manifest prefetch and expiry are tick-driven.
        engine.handle(announce(m, autoAccept = false))
        assertTrue(engine.hasActiveWork(), "an offer is active work")

        engine.handle(SwarmEvent.LocalCancel("g1", m.root, asOrigin = false, reason = SwarmTombstoneReason.USER, nowMs = 2_000L))
        assertFalse(engine.hasActiveWork(), "a cancelled content is not work")

        val second = manifest(pieceCount = 2)
        val accepting = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "local", seed = 1L)
        accepting.handle(announce(second, autoAccept = true))
        assertTrue(accepting.hasActiveWork(), "a transfer being fetched is work")

        accepting.handle(SwarmEvent.PeerUp("origin", setOf("sw1"), 2_000L))
        accepting.handle(SwarmEvent.CancelArrived("origin", tombstone(second), signatureValid = true, nowMs = 3_000L))
        assertFalse(accepting.hasActiveWork(), "a tombstoned content is not work")
    }

    private fun tombstone(m: SwarmManifest) = com.transfer.flash.core.swarm.codec.SwarmFrame.Cancel(
        groupId = "g1",
        root = m.root,
        originId = "origin",
        messageId = "m1",
        reason = SwarmTombstoneReason.USER,
        cancelledAtMs = 3_000L,
        signature = ByteArray(64) { 1 },
    )
}
