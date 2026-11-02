package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O28-04: the scheduler ran without telemetry. A piece that timed out vanished from the in-flight map, a receiver
 * whose window was full while pieces were missing looked identical to one with nothing to do, and duplicate pieces won
 * in an endgame race were thrown away without a byte count.
 *
 * The engine reports these to a [SwarmTelemetry] callback (it stays sans-IO: the host forwards to `FlashProbe` and a
 * test collects a list). Every probe is on a rare event or rate-limited — never one per piece.
 */
class SwarmTelemetryTest {

    private class Collector : SwarmTelemetry {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun emit(name: String, fields: List<Pair<String, Any?>>) {
            events += name to fields.toMap()
        }
        fun count(name: String) = events.count { it.first == name }
        fun first(name: String) = events.first { it.first == name }.second
    }

    private val pieceSize = 1_048_576
    private val collector = Collector()

    private fun manifest(pieceCount: Int = 8): SwarmManifest {
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

    private fun receiver(m: SwarmManifest): SwarmEngine {
        val engine = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "local", seed = 3L, telemetry = collector)
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "m1",
                originId = "peerA",
                originKey = "keyA",
                root = m.root,
                totalSize = m.totalSize,
                pieceSize = m.pieceSize,
                fileName = "f.bin",
                mime = "application/octet-stream",
                sentAtMs = 1_000L,
                expiresAtMs = 10_000_000L,
                isOrigin = false,
                autoAccept = true,
                nowMs = 1_000L,
            )
        )
        return engine
    }

    @Test
    fun `a timed-out piece request is reported with its peer and elapsed time`() {
        val m = manifest()
        val engine = receiver(m)
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1_000L))
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", m.root, m, true, 1_000L))
        engine.handle(SwarmEvent.HaveAllArrived("peerA", SwarmFrame.HaveAll("g1", m.root), 1_000L))
        // Nothing answers; the 5 s floor elapses.
        engine.handle(SwarmEvent.Tick(1_000L + 6_000L))

        assertTrue(collector.count("swarm.piece.timeout") > 0)
        val fields = collector.first("swarm.piece.timeout")
        assertEquals("peerA", fields["peer"])
        assertTrue((fields["elapsedMs"] as Long) >= 5_000L)
        assertTrue((fields["piece"] as Int) >= 0)
    }

    @Test
    fun `a receiver whose window is full reports a stall once per interval`() {
        val m = manifest(pieceCount = 300)
        val engine = receiver(m)
        for (peer in listOf("peerA", "peerB", "peerC", "peerD")) {
            engine.handle(SwarmEvent.PeerUp(peer, setOf("sw1"), 1_000L))
        }
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", m.root, m, true, 1_000L))
        for (peer in listOf("peerA", "peerB", "peerC", "peerD")) {
            engine.handle(SwarmEvent.HaveAllArrived(peer, SwarmFrame.HaveAll("g1", m.root), 1_000L))
        }
        engine.handle(SwarmEvent.Tick(2_000L))
        val first = collector.count("swarm.sched.stall")
        assertTrue(first >= 1, "four peers x four pieces x 1 MiB fills the 16 MiB window; events=" + collector.events.map { it.first })
        val fields = collector.first("swarm.sched.stall")
        assertEquals("window_full", fields["reason"])
        assertEquals(16L * 1024 * 1024, fields["budgetBytes"])

        // Inside the reporting interval a second tick adds nothing: this is a state, not a per-tick event.
        engine.handle(SwarmEvent.Tick(3_000L))
        assertEquals(first, collector.count("swarm.sched.stall"))

        // Still full, and past the interval: reported once more (before the 5 s request timeout would free the window).
        engine.handle(SwarmEvent.Tick(4_500L))
        assertTrue(collector.count("swarm.sched.stall") > first, "it reports again once the interval has passed")
    }

    @Test
    fun `a duplicate piece that lost the endgame race is counted as wasted`() {
        val m = manifest()
        val engine = receiver(m)
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1_000L))
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", m.root, m, true, 1_000L))
        engine.handle(SwarmEvent.PieceStored("g1", m.root, 0, 1_000L))
        // An unsolicited copy of a piece we already hold.
        engine.handle(
            SwarmEvent.PieceArrived("peerB", "g1", m.root, 0, ByteArray(pieceSize), verified = true, nowMs = 6_000L)
        )
        val fields = collector.first("swarm.recv.wasted")
        assertEquals(pieceSize.toLong(), fields["wastedBytes"])
        assertEquals(0, fields["piece"])
    }

    @Test
    fun `a healthy transfer reports nothing`() {
        val m = manifest(pieceCount = 2)
        val engine = receiver(m)
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), 1_000L))
        engine.handle(SwarmEvent.ManifestComplete("peerA", "g1", m.root, m, true, 1_000L))
        engine.handle(
            SwarmEvent.HaveArrived("peerA", SwarmFrame.Have("g1", m.root, listOf(PieceRange(0, 2))), 1_000L)
        )
        engine.handle(SwarmEvent.Tick(1_250L))
        assertTrue(collector.events.isEmpty(), "no probe on a normal path: ${collector.events.map { it.first }}")
    }
}

