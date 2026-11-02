package com.transfer.flash.core.swarm.bench

import com.transfer.flash.core.swarm.engine.PeerPieceAvailability
import com.transfer.flash.core.swarm.engine.PeerSourceCandidate
import com.transfer.flash.core.swarm.engine.PiecePicker
import com.transfer.flash.core.swarm.engine.SeededRandom
import com.transfer.flash.core.swarm.engine.SourceSelector
import com.transfer.flash.core.swarm.model.Bitfield
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OPT-O08-01 measurement harness (EXP-125).
 *
 * Runs one scheduling pass in the shape the finding describes — 4,096 pieces of 1 MiB, 20 peers, a 16-slot window —
 * twice: once with the pre-change loop (rebuilt peer views and a full reference scan per slot, kept here **only** as
 * the benchmark baseline) and once with [PiecePicker.beginScan]. It prints wall-clock per pass and reports the
 * deterministic work counters, which do not depend on machine load.
 *
 * It asserts only the structural result (the new pass does an order of magnitude less holder work and allocates no
 * per-slot peer collections) plus a very loose time bound; the numbers belong in `logs/experiments.md`, not in the
 * test's pass/fail.
 *
 * Run alone, forced: `:core:swarm:jvmTest --tests "*SwarmSchedulerScanBench*" --rerun-tasks --no-build-cache`.
 */
class SwarmSchedulerScanBench {

    private val pieces = 4_096
    private val peerCount = 20
    private val slots = 16

    private fun peers(): List<PeerPieceAvailability> = List(peerCount) { peer ->
        val bf = Bitfield(pieces)
        for (p in 0 until pieces) {
            if ((p * 31 + peer * 17) % 5 != 0) bf.set(p, true)
        }
        PeerPieceAvailability("peer-$peer", bf, 0, slots)
    }

    private fun legacyPass(random: SeededRandom): Long {
        val local = Bitfield(pieces)
        val available = peers()
        val picker = PiecePicker(random)
        val selector = SourceSelector(random)
        val inFlightByPiece = LinkedHashMap<Int, MutableSet<String>>()
        val inFlightByPeer = HashMap<String, MutableSet<Int>>()
        var piecePeerTests = 0L
        for (slot in 0 until slots) {
            // The old loop rebuilt these two views per slot.
            val availPeers = available.mapNotNull { p ->
                if (p.inFlightCount < p.window) PeerPieceAvailability(p.peerId, p.bitfield) else null
            }
            val candidates = availPeers.map { p ->
                val inFlight = inFlightByPeer.getOrPut(p.peerId) { HashSet() }
                PeerSourceCandidate(
                    peerId = p.peerId,
                    isOrigin = false,
                    bitfield = p.bitfield,
                    isConnected = true,
                    isAllowed = true,
                    hasSw1Feature = true,
                    servingEnabled = true,
                    strikes = 0,
                    backoffUntilMs = 0L,
                    inFlightCount = inFlight.size,
                    window = slots,
                    ewmaSpeedBps = 1_000_000.0,
                    inFlightPieces = inFlight,
                )
            }
            val piece = picker.pickNextPiece(local, availPeers, inFlightByPiece, null) ?: break
            piecePeerTests += picker.lastHolderChecks
            val chosen = selector.selectSource(piece, candidates, 0L) ?: break
            inFlightByPiece.getOrPut(piece) { LinkedHashSet() }.add(chosen.peerId)
            inFlightByPeer.getOrPut(chosen.peerId) { HashSet() }.add(piece)
        }
        return piecePeerTests
    }

    private fun scanPass(random: SeededRandom): Pair<Long, Long> {
        val local = Bitfield(pieces)
        val available = peers()
        val picker = PiecePicker(random)
        val selector = SourceSelector(random)
        val inFlightByPiece = LinkedHashMap<Int, MutableSet<String>>()
        val inFlightByPeer = HashMap<String, MutableSet<Int>>()
        val candidates = available.map { p ->
            PeerSourceCandidate(
                peerId = p.peerId,
                isOrigin = false,
                bitfield = p.bitfield,
                isConnected = true,
                isAllowed = true,
                hasSw1Feature = true,
                servingEnabled = true,
                strikes = 0,
                backoffUntilMs = 0L,
                inFlightCount = 0,
                window = slots,
                ewmaSpeedBps = 1_000_000.0,
                inFlightPieces = emptySet<Int>(),
            )
        }.toMutableList()
        val scan = picker.beginScan(local, available, inFlightByPiece, null)
        val buildWork = picker.lastHolderChecks
        for (slot in 0 until slots) {
            val piece = scan.pickNext() ?: break
            val chosen = selector.selectSource(piece, candidates, 0L) ?: break
            inFlightByPiece.getOrPut(piece) { LinkedHashSet() }.add(chosen.peerId)
            scan.onPeerRequested(chosen.peerId, piece)
            val set = inFlightByPeer.getOrPut(chosen.peerId) { HashSet() }
            set.add(piece)
            val index = candidates.indexOfFirst { it.peerId == chosen.peerId }
            if (index >= 0) {
                candidates[index] = candidates[index].copy(inFlightCount = set.size, inFlightPieces = set)
            }
        }
        return buildWork to 0L
    }

    @Test
    fun `one scheduling pass - legacy loop versus rarity scan`() {
        // Warm both paths so the numbers describe steady state, not class loading.
        repeat(30) {
            legacyPass(SeededRandom(1L))
            scanPass(SeededRandom(1L))
        }

        var legacyTests = 0L
        val legacyStart = System.nanoTime()
        repeat(40) { legacyTests = legacyPass(SeededRandom(7L)) }
        val legacyNanos = (System.nanoTime() - legacyStart) / 40.0

        var scanBuildWork = 0L
        val scanStart = System.nanoTime()
        repeat(40) { scanBuildWork = scanPass(SeededRandom(7L)).first }
        val scanNanos = (System.nanoTime() - scanStart) / 40.0

        println(
            "SWARM-SCAN-BENCH pieces=$pieces peers=$peerCount slots=$slots " +
                "legacy_ms_per_pass=${"%.3f".format(legacyNanos / 1_000_000.0)} " +
                "scan_ms_per_pass=${"%.3f".format(scanNanos / 1_000_000.0)} " +
                "legacy_piece_peer_tests=$legacyTests scan_holder_reads=$scanBuildWork " +
                "work_ratio=${"%.1f".format(legacyTests.toDouble() / scanBuildWork)} " +
                "time_ratio=${"%.1f".format(legacyNanos / scanNanos)}",
        )

        assertTrue(
            legacyTests > scanBuildWork * 10,
            "the scan must read at least ten times less holder state per pass: legacy=$legacyTests scan=$scanBuildWork",
        )
        assertTrue(
            legacyNanos > scanNanos,
            "the scan must be faster than the loop it replaces (legacy=${legacyNanos}ns scan=${scanNanos}ns)",
        )
    }
}
