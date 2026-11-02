package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O08-01: [PieceScan] must return exactly the sequence of pieces the reference scan
 * ([PiecePicker.pickNextPiece]) returns, for the same seed and the same live scheduling state.
 *
 * This is the invariant the optimisation is allowed to trade nothing for: the fast scan is an index over the same
 * question, not a different picking rule. It is checked over randomised topologies (piece counts, holder overlaps,
 * window sizes that fill part way through a pass, endgame duplicates) rather than by example.
 */
class PieceScanEquivalenceTest {

    private class Fixture(
        val pieces: Int,
        val windows: List<Int>,
        val bitfields: List<Bitfield>,
        val local: Bitfield,
    ) {
        val peerIds: List<String> = windows.indices.map { "p$it" }
        val inFlightByPiece = LinkedHashMap<Int, MutableSet<String>>()
        val inFlightByPeer: List<MutableSet<Int>> = windows.indices.map { LinkedHashSet() }
        val requests = ArrayList<Pair<String, Int>>()

        fun available(): List<PeerPieceAvailability> = PeerPieceAvailabilityList.of(this)

        fun issue(peerIndex: Int, piece: Int) {
            inFlightByPiece.getOrPut(piece) { LinkedHashSet() }.add(peerIds[peerIndex])
            inFlightByPeer[peerIndex].add(piece)
            requests.add(peerIds[peerIndex] to piece)
        }

        /** The scheduler's choice, made deterministic so the test compares piece sequences and not tie-breaks. */
        fun chooseSource(piece: Int): Int {
            for (i in windows.indices) {
                if (inFlightByPeer[i].size >= windows[i]) continue
                if (piece in inFlightByPeer[i]) continue
                if (!bitfields[i].get(piece)) continue
                return i
            }
            return -1
        }
    }

    private object PeerPieceAvailabilityList {
        fun of(f: Fixture): List<PeerPieceAvailability> {
            val list = ArrayList<PeerPieceAvailability>(f.windows.size)
            for (i in f.windows.indices) {
                if (f.inFlightByPeer[i].size < f.windows[i]) {
                    list.add(PeerPieceAvailability(f.peerIds[i], f.bitfields[i], f.inFlightByPeer[i].size, f.windows[i]))
                }
            }
            return list
        }
    }

    private fun fixture(seed: Long): Fixture {
        val random = SeededRandom(seed)
        val pieces = 1 + random.nextInt(260)
        val peerCount = 1 + random.nextInt(6)
        val local = Bitfield(pieces)
        for (p in 0 until pieces) {
            if (random.nextInt(10) < 2) local.set(p, true)
        }
        // One topology in five is an endgame: only a handful of pieces are still missing.
        if (seed % 5 == 0L && pieces > 6) {
            for (p in 0 until pieces - 3) local.set(p, true)
        }
        val bitfields = List(peerCount) {
            val bf = Bitfield(pieces)
            for (p in 0 until pieces) {
                if (random.nextInt(10) < 5) bf.set(p, true)
            }
            bf
        }
        val windows = List(peerCount) { 1 + random.nextInt(3) }
        return Fixture(pieces, windows, bitfields, local)
    }

    @Test
    fun `fast scan picks exactly the reference sequence across random topologies`() {
        var comparedPicks = 0
        for (seed in 1L..250L) {
            val f = fixture(seed)
            val canDuplicate = { piece: Int ->
                // Deterministic gate that switches halfway through the pass, as a real timeout does.
                ((f.inFlightByPiece[piece]?.size ?: 0) == 0) || piece % 2 == 0
            }
            val reference = PiecePicker(SeededRandom(seed))
            val fast = PiecePicker(SeededRandom(seed))
            val scan = fast.beginScan(f.local, f.available(), f.inFlightByPiece, canDuplicate)

            for (slot in 0 until 12) {
                val expected = reference.pickNextPiece(
                    localBitfield = f.local,
                    availablePeers = f.available(),
                    inFlightByPiece = f.inFlightByPiece,
                    canDuplicate = canDuplicate,
                )
                val actual = scan.pickNext()
                assertEquals(expected, actual, "seed=$seed slot=$slot reference=$expected fast=$actual")
                if (expected == null) break
                comparedPicks++

                val chosen = f.chooseSource(expected)
                if (chosen < 0) break
                f.issue(chosen, expected)
                scan.onPeerRequested(f.peerIds[chosen], expected)
            }
        }
        assertTrue(comparedPicks > 1_000, "the property must actually compare picks, compared=$comparedPicks")
    }

    @Test
    fun `a piece whose only holder is already asking is not offered again`() {
        val local = Bitfield(8)
        val only = Bitfield(8).apply { set(3, true) }
        val fixture = Fixture(
            pieces = 8,
            windows = listOf(4),
            bitfields = listOf(only),
            local = local,
        )
        fixture.issue(0, 3)
        val picker = PiecePicker(SeededRandom(7L))
        val scan = picker.beginScan(local, fixture.available(), fixture.inFlightByPiece, null)
        assertEquals(null, scan.pickNext(), "piece 3 is in flight from its only holder, nothing else is held")
        assertEquals(
            null,
            picker.pickNextPiece(local, fixture.available(), fixture.inFlightByPiece, null),
            "the reference agrees",
        )
    }

    @Test
    fun `filling a peer's window mid-pass drops its pieces exactly as the reference does`() {
        val local = Bitfield(40)
        // Two peers: p0 holds odd pieces (window 2), p1 holds even pieces (window 99).
        val p0 = Bitfield(40)
        val p1 = Bitfield(40)
        for (i in 0 until 40) {
            if (i % 2 == 1) p0.set(i, true) else p1.set(i, true)
        }
        val fixture = Fixture(40, listOf(2, 99), listOf(p0, p1), local)
        val reference = PiecePicker(SeededRandom(3L))
        val fast = PiecePicker(SeededRandom(3L))
        val scan = fast.beginScan(local, fixture.available(), fixture.inFlightByPiece, null)
        for (slot in 0 until 20) {
            val expected = reference.pickNextPiece(local, fixture.available(), fixture.inFlightByPiece, null)
            val actual = scan.pickNext()
            assertEquals(expected, actual, "slot=$slot")
            if (expected == null) break
            val chosen = fixture.chooseSource(expected)
            if (chosen < 0) break
            fixture.issue(chosen, expected)
            scan.onPeerRequested(fixture.peerIds[chosen], expected)
        }
        assertEquals(2, fixture.inFlightByPeer[0].size, "p0's window of 2 is full after the pass")
    }

    @Test
    fun `the fast scan does strictly less holder work than the reference for the same picks`() {
        // 4,096 pieces, 20 peers, 16 slots: the shape O08-01 measures.
        val pieces = 4_096
        val peerBitfields = List(20) { peer ->
            Bitfield(pieces).also { bf ->
                for (p in 0 until pieces) if ((p + peer) % 3 != 0) bf.set(p, true)
            }
        }
        val local = Bitfield(pieces)
        val peers = peerBitfields.mapIndexed { i, bf -> PeerPieceAvailability("p$i", bf, 0, 64) }
        val picker = PiecePicker(SeededRandom(11L))
        val scan = picker.beginScan(local, peers, emptyMap(), null)
        val fastWork = picker.lastHolderChecks
        assertTrue(scan.pickNext() != null)
        var referenceWork = 0L
        repeat(16) {
            val picked = picker.pickNextPiece(local, peers, emptyMap(), null)
            assertTrue(picked != null)
            referenceWork += picker.lastHolderChecks
        }
        // The reference repeats the whole scan for every slot; the fast scan pays it once per pass.
        assertTrue(
            fastWork * 6 < referenceWork,
            "expected the fast scan to do an order of magnitude less work: fast=$fastWork reference=$referenceWork",
        )
    }
}
