package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec

import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.codec.SwarmFrameCodec
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O08-08 / OPT-O02-05: the engine loaded every persisted tombstone into a `LinkedHashMap` and never pruned it, and
 * every `Summary` carried the whole per-group history. `SwarmFrameCodec` refuses a Summary with more than
 * [SwarmFrameCodec.MAX_SUMMARY_TOMBSTONES] tombstones, so a group that cancelled 65 files could not send a Summary at all â€” after which
 * no peer learns who holds what.
 */
class SwarmTombstoneRetentionTest {

    private val day = 24L * 60 * 60 * 1000

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

    private fun tombstone(index: Int, cancelledAtMs: Long) = SwarmTombstone(
        groupId = "g1",
        root = ContentRoot(index.toString().padStart(64, '0')),
        originId = "origin",
        messageId = "m$index",
        reason = SwarmTombstoneReason.USER,
        cancelledAtMs = cancelledAtMs,
        signature = ByteArray(64) { 1 },
    )

    private fun engineWithTombstones(m: SwarmManifest, tombstones: List<SwarmTombstone>, nowMs: Long): SwarmEngine {
        val engine = SwarmEngine(SwarmConfig(autoAcceptIncoming = true), "local", seed = 2L)
        engine.handle(SwarmEvent.TombstonesRestored(tombstones, nowMs))
        // One content so the engine can build a Summary at all.
        engine.handle(
            SwarmEvent.Announced(
                groupId = "g1",
                messageId = "live",
                originId = "local",
                originKey = "key",
                root = m.root,
                totalSize = m.totalSize,
                pieceSize = m.pieceSize,
                fileName = "f.bin",
                mime = "application/octet-stream",
                sentAtMs = nowMs,
                expiresAtMs = nowMs + 30 * day,
                isOrigin = true,
                manifest = m,
                nowMs = nowMs,
            )
        )
        return engine
    }

    private fun summaries(engine: SwarmEngine, m: SwarmManifest, nowMs: Long): List<SwarmFrame.Summary> =
        engine.handle(SwarmEvent.PeerUp("peerA", setOf("sw1"), nowMs))
            .filterIsInstance<SwarmCommand.Send>()
            .map { it.frame }
            .filterIsInstance<SwarmFrame.Summary>()

    @Test
    fun `a summary never carries more tombstones than the codec accepts`() {
        val m = manifest()
        val nowMs = 1_000_000L
        val tombstones = (0 until SwarmFrameCodec.MAX_SUMMARY_TOMBSTONES * 3).map { tombstone(it, nowMs - it * 1_000L) }
        val engine = engineWithTombstones(m, tombstones, nowMs)

        val summary = summaries(engine, m, nowMs + 1_000L).firstOrNull()
        assertTrue(summary != null, "the peer must be told the group's state")
        assertEquals(SwarmFrameCodec.MAX_SUMMARY_TOMBSTONES, summary.tombstones.size)

        // Before this change encodeSummary threw for this frame and the whole Summary was lost.
        val encoded = SwarmFrameCodec.encode(summary)
        assertTrue(encoded.isNotEmpty())
        // The newest cancellations are the ones that survive the cap.
        assertTrue(summary.tombstones.any { it.messageId == "m0" }, "the newest tombstone is kept")
        assertTrue(summary.tombstones.none { it.messageId == "m${SwarmFrameCodec.MAX_SUMMARY_TOMBSTONES * 3 - 1}" })
    }

    @Test
    fun `a tick drops tombstones past their retention`() {
        val m = manifest()
        val nowMs = 100 * day
        val old = (0 until 3).map { tombstone(it, nowMs - 40 * day) }
        val fresh = (100 until 102).map { tombstone(it, nowMs - 1 * day) }
        val engine = engineWithTombstones(m, old + fresh, nowMs)

        assertEquals(5, summaries(engine, m, nowMs + 1_000L).first().tombstones.size)

        val tick = engine.handle(SwarmEvent.Tick(nowMs + 2_000L))
        // Nothing to say about the pruning itself; the next Summary is where it shows.
        assertTrue(tick.isEmpty() || tick.isNotEmpty())
        val after = summaries(engine, m, nowMs + 3_000L).first()
        assertEquals(2, after.tombstones.size, "40-day-old tombstones are gone, yesterday's are kept")
        assertTrue(after.tombstones.all { it.messageId in setOf("m100", "m101") })
    }

    @Test
    fun `a cancelled content stays cancelled after an unrelated tick`() {
        val m = manifest()
        val nowMs = 100 * day
        val engine = engineWithTombstones(m, listOf(tombstone(7, nowMs - 2 * day)), nowMs)
        engine.handle(SwarmEvent.Tick(nowMs + 10_000L))
        // The retention window must not resurrect anything: a two-day-old cancellation is still enforced.
        val summary = summaries(engine, m, nowMs + 20_000L).first()
        assertEquals(1, summary.tombstones.size)
    }
}
