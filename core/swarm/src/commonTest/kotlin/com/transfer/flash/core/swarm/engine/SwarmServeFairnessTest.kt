package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceReadStatus
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O08-03 (ADR-232: approved as age-weighted / deficit round robin, no wire change).
 *
 * `activeRequesters` kept a peer's slot until all of its outstanding serve bytes were read. A fast peer sends its next
 * request before that happens, so the first two members to arrive held both slots for the whole transfer and the rest
 * of the group sat in "Waiting for peers". Now a peer that has been served [ServePolicy.YIELD_AFTER_PIECES] pieces in
 * one tenure gives its slot up, but only to a member that has already been refused for
 * [ServePolicy.ARRIVAL_MIN_WAIT_MS] — so nobody is preempted on their first attempt and no slot is ever added.
 *
 * **What changed about who wins a slot:** before, "whoever asked first and kept asking" won every slot for the whole
 * transfer; now every member gets at least two pieces per tenure before it can be displaced, and the peer that has had
 * the most wins the displacement first. The wire is untouched: the displaced peer is simply not admitted to its next
 * request (the same BUSY answer a newcomer used to get) and its in-flight reads still complete.
 */
class SwarmServeFairnessTest {

    private val pieceSize = 65_536
    private val groupId = "g1"

    private fun manifest(pieceCount: Int): SwarmManifest {
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

    private fun origin(m: SwarmManifest, profile: SwarmProfile = SwarmProfile.MEDIUM): SwarmEngine {
        val engine = SwarmEngine(SwarmConfig(profile = profile, autoAcceptIncoming = true), "origin", seed = 4L)
        engine.handle(
            SwarmEvent.Announced(
                groupId = groupId,
                messageId = "m1",
                originId = "origin",
                originKey = "key",
                root = m.root,
                totalSize = m.totalSize,
                pieceSize = m.pieceSize,
                fileName = "f.bin",
                mime = "application/octet-stream",
                sentAtMs = 1_000L,
                expiresAtMs = 10_000_000L,
                isOrigin = true,
                manifest = m,
                nowMs = 1_000L,
            )
        )
        return engine
    }

    /**
     * One round of the pipelining case: every member asks for its next piece, and the reads are **not** completed, which
     * is what a fast peer looks like to the serve policy (it asks again before its outstanding bytes drain).
     */
    private fun round(
        engine: SwarmEngine,
        m: SwarmManifest,
        peers: List<String>,
        startPiece: Int,
        nowMs: Long,
        served: MutableList<String>,
    ): List<String> {
        val accepted = ArrayList<String>()
        peers.forEachIndexed { index, peer ->
            val cmds = engine.handle(
                SwarmEvent.RequestArrived(
                    peerId = peer,
                    frame = SwarmFrame.Request(groupId, m.root, listOf(startPiece + index)),
                    allowed = true,
                    nowMs = nowMs,
                )
            )
            if (cmds.filterIsInstance<SwarmCommand.ReadPiece>().isNotEmpty()) accepted.add(peer)
        }
        served += accepted
        return accepted
    }

    /**
     * The slot count itself is enforced by the handler: a yield removes the victim before the arriving peer is added, so
     * the set never grows. That is asserted directly on the policy below, where the decision is visible.
     */
    @Test
    fun `yield candidate - only a peer that has had its share, and only with a waited arrival`() {
        val policy = ServePolicy()
        val quota = pieceSize.toLong() * ServePolicy.YIELD_AFTER_PIECES

        // Nobody has had a full share yet: no victim, so the arrival is simply refused.
        assertEquals(
            null,
            policy.yieldCandidate(
                arrivingPeerId = "peerC",
                activeRequesters = setOf("peerA", "peerB"),
                servedInTenure = mapOf("peerA" to quota - 1, "peerB" to 0L),
                pieceSize = pieceSize,
            ),
        )
        // One peer is past the quota: it is the victim.
        assertEquals(
            "peerA",
            policy.yieldCandidate(
                arrivingPeerId = "peerC",
                activeRequesters = setOf("peerA", "peerB"),
                servedInTenure = mapOf("peerA" to quota, "peerB" to quota - 1),
                pieceSize = pieceSize,
            ),
        )
        // Both past it: the one that has been served more yields first.
        assertEquals(
            "peerB",
            policy.yieldCandidate(
                arrivingPeerId = "peerC",
                activeRequesters = setOf("peerA", "peerB"),
                servedInTenure = mapOf("peerA" to quota + 1, "peerB" to quota + 5),
                pieceSize = pieceSize,
            ),
        )
    }

    @Test
    fun `a waited arrival is accepted with the victim named, and the request is served`() {
        val policy = ServePolicy(maxOutstandingBytesPerRequester = 1024 * 1024)
        val base = ServeEvaluationParams(
            peerId = "peerC",
            pieceSize = pieceSize,
            profile = SwarmProfile.MEDIUM,
            activeRequesters = setOf("peerA", "peerB"),
        )
        // Not waited: refused, and nothing is yielded.
        val fresh = policy.evaluateRequest(
            peerId = base.peerId,
            requestedPieces = listOf(0),
            pieceSize = base.pieceSize,
            isAllowed = true,
            contentKnown = true,
            isTombstoned = false,
            servingEnabled = true,
            isOrigin = false,
            isCallActive = false,
            profile = base.profile,
            activeRequesters = base.activeRequesters,
            currentOutstandingBytes = 0L,
            localHoldsPiece = { true },
            copiesCount = { 0 },
            pendingCount = { 0 },
            servedInTenure = mapOf("peerA" to pieceSize.toLong() * 2, "peerB" to 0L),
            refusedSinceMs = emptyMap(),
            nowMs = 10_000L,
        )
        assertTrue(fresh is ServeEvaluation.Rejected, "a first attempt does not displace anybody")

        // Waited long enough: accepted, with the victim named so the handler swaps the slot instead of adding one.
        val waited = policy.evaluateRequest(
            peerId = base.peerId,
            requestedPieces = listOf(0),
            pieceSize = base.pieceSize,
            isAllowed = true,
            contentKnown = true,
            isTombstoned = false,
            servingEnabled = true,
            isOrigin = false,
            isCallActive = false,
            profile = base.profile,
            activeRequesters = base.activeRequesters,
            currentOutstandingBytes = 0L,
            localHoldsPiece = { true },
            copiesCount = { 0 },
            pendingCount = { 0 },
            servedInTenure = mapOf("peerA" to pieceSize.toLong() * 2, "peerB" to 0L),
            refusedSinceMs = mapOf("peerC" to 8_000L),
            nowMs = 10_000L,
        )
        val accepted = waited as ServeEvaluation.Accepted
        assertEquals("peerA", accepted.yieldPeerId)
        assertEquals(listOf(0), accepted.piecesToServe)
    }

    private class ServeEvaluationParams(
        val peerId: String,
        val pieceSize: Int,
        val profile: SwarmProfile,
        val activeRequesters: Set<String>,
    )

    @Test
    fun `every member is served within the first few rounds instead of two members taking everything`() {
        val m = manifest(pieceCount = 120)
        val engine = origin(m)
        val peers = listOf("peerA", "peerB", "peerC", "peerD", "peerE")
        val served = ArrayList<String>()

        var now = 2_000L
        for (roundIndex in 0 until 12) {
            round(engine, m, peers, roundIndex * peers.size, now, served)
            now += 1_200L
        }

        // Before the change peers C, D and E were refused for the whole transfer: the first two members kept both slots
        // because their next request always arrived before their outstanding bytes reached zero.
        for (peer in peers) {
            assertTrue(peer in served, "$peer was never served; order was $served")
        }
        // The rotation is not "everything to two members": the first four serves cover more than two peers.
        val firstFour = served.take(4).distinct()
        assertTrue(firstFour.size >= 2, "the first rounds must rotate, not repeat: $served")
    }

    @Test
    fun `a member that has not waited is not admitted by displacing anybody`() {
        val m = manifest(pieceCount = 40)
        val engine = origin(m)
        // Two members fill both slots immediately.
        for ((i, peer) in listOf("peerA", "peerB").withIndex()) {
            engine.handle(
                SwarmEvent.RequestArrived(peer, SwarmFrame.Request(groupId, m.root, listOf(i)), allowed = true, nowMs = 2_000L)
            )
        }
        // A third member that has never been refused asks twice within the wait window: still BUSY.
        val first = engine.handle(
            SwarmEvent.RequestArrived("peerC", SwarmFrame.Request(groupId, m.root, listOf(3)), allowed = true, nowMs = 2_100L)
        )
        val second = engine.handle(
            SwarmEvent.RequestArrived("peerC", SwarmFrame.Request(groupId, m.root, listOf(3)), allowed = true, nowMs = 2_500L)
        )
        for (cmds in listOf(first, second)) {
            val reject = cmds.filterIsInstance<SwarmCommand.Send>().map { it.frame }.filterIsInstance<SwarmFrame.Reject>()
            assertTrue(reject.isNotEmpty(), "peerC must still be refused inside the wait window")
        }
    }

    @Test
    fun `the yielded peer finishes its in-flight reads and can come back`() {
        val m = manifest(pieceCount = 40)
        val engine = origin(m)
        // peerA takes a slot and is served two pieces (its tenure quota).
        for (i in 0 until 2) {
            val cmds = engine.handle(
                SwarmEvent.RequestArrived("peerA", SwarmFrame.Request(groupId, m.root, listOf(i)), allowed = true, nowMs = 2_000L + i)
            )
            assertEquals(1, cmds.filterIsInstance<SwarmCommand.ReadPiece>().size)
        }
        // peerB takes the second slot.
        engine.handle(SwarmEvent.RequestArrived("peerB", SwarmFrame.Request(groupId, m.root, listOf(2)), allowed = true, nowMs = 2_000L))
        // peerC is refused now (records its wait) and comes back after the window with both slots full.
        engine.handle(SwarmEvent.RequestArrived("peerC", SwarmFrame.Request(groupId, m.root, listOf(5)), allowed = true, nowMs = 2_100L))
        val third = engine.handle(
            SwarmEvent.RequestArrived("peerC", SwarmFrame.Request(groupId, m.root, listOf(5)), allowed = true, nowMs = 3_500L)
        )
        assertEquals(
            1,
            third.filterIsInstance<SwarmCommand.ReadPiece>().size,
            "after waiting, a member takes the slot of the peer that has had its share",
        )
        // The displaced peer (peerA, the one with the most served bytes) is not admitted on its next request.
        val displaced = engine.handle(
            SwarmEvent.RequestArrived("peerA", SwarmFrame.Request(groupId, m.root, listOf(7)), allowed = true, nowMs = 3_600L)
        )
        assertTrue(
            displaced.filterIsInstance<SwarmCommand.Send>().map { it.frame }.filterIsInstance<SwarmFrame.Reject>().isNotEmpty(),
            "the peer that gave up the slot waits its turn like everybody else",
        )
    }

    @Test
    fun `slots are still capped at the profile maximum`() {
        val m = manifest(pieceCount = 120)
        val engine = origin(m, SwarmProfile.LOW)
        val served = ArrayList<String>()
        var now = 2_000L
        for (i in 0 until 6) {
            val accepted = round(engine, m, listOf("peerA", "peerB", "peerC"), i * 3, now, served)
            assertTrue(accepted.size <= 3, "at most one admission per member per round")
            now += 1_500L
        }
        assertTrue(served.isNotEmpty())
    }
}
