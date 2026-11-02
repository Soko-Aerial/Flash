package com.transfer.flash.core.ptt.floor

/**
 * Single-threaded jitter buffer for inbound PTT PCM packets (ADR-032, Phase 1).
 *
 * Playout scheduling, not wall-clock pacing: the host ticks [poll] once per audio packet
 * duration and writes whatever comes back. The first packets fill up to [targetDepthMs]
 * ([Poll.Starving] until then); afterwards every tick consumes exactly one slot —
 * [Poll.Ready] for the expected sequence, [Poll.Concealed] for a gap (host repeats the
 * last frame or writes zeros). Packets arriving after their slot passed count as late;
 * duplicates and over-capacity overflow are dropped with counters.
 *
 * **Sequence numbers are a u32 on the wire** (`PttAudioFrame`), so they wrap. Everything inside
 * this class is kept in an unwrapped 64-bit domain: a later packet whose number is numerically
 * smaller (0xFFFFFFFF followed by 0) is the *next* packet, not an ancient one (RVW22 F45). The
 * `seq` values reported by [Poll] are in that unwrapped domain, which only ever matters for the
 * comparisons a consumer makes between two of them.
 *
 * Overflow keeps the head of the stream: when more than [maxPackets] packets are queued, the
 * newest arrival is dropped, never the packet the next [poll] must return (RVW22 F46).
 *
 * Threading contract: NOT synchronized. All [push]/[poll]/[stats]/[reset] calls must
 * happen on ONE thread — the engine funnels network pushes through the playout thread's
 * drop-oldest channel, so late audio is shed before it ever reaches this buffer.
 *
 * **Storage (OPT-O12-04):** a fixed ring of [CAPACITY] slots indexed by `seq % CAPACITY`, not a
 * `TreeMap`. Every push and poll is O(1), allocates nothing and boxes nothing; the tree allocated a
 * `Long` key, a node and a `Poll.Ready` per packet (50/s). The ring is larger than [maxPackets], so
 * the live window (at most [maxPackets] consecutive sequences) can never wrap onto itself, and a
 * slot is cleared as soon as its packet is played or dropped.
 */
public class PttJitterBuffer(
    private val targetDepthMs: Long = 120L,
    private val maxPackets: Int = 100,
) {
    public sealed interface Poll {
        public data class Ready(val pcm: ByteArray, val seq: Long) : Poll
        public data class Concealed(val seq: Long) : Poll
        public data object Starving : Poll
    }

    public data class Stats(
        val received: Long,
        val lost: Long,
        val late: Long,
        val duplicates: Long,
        val droppedOverflow: Long,
        val depthPackets: Int,
    )

    private class Packet(val seq: Long, val captureTsMs: Long, val pcm: ByteArray)

    /** Slots, `seq % capacity`. A slot holds a packet only while that packet is still queued. */
    private val slots = arrayOfNulls<Packet>(capacityFor(maxPackets))
    private var queued: Int = 0
    private var started: Boolean = false
    private var nextSeq: Long = 0L

    /** Lowest queued sequence while the buffer has not started; only [poll] reads it. */
    private var earliestSeq: Long = 0L

    /**
     * Highest sequence seen so far, in the unwrapped domain, and the reference every arriving u32 is
     * unwrapped against. Null until the first packet of the session.
     */
    private var newestSeq: Long? = null

    private var received: Long = 0L
    private var lost: Long = 0L
    private var late: Long = 0L
    private var duplicates: Long = 0L
    private var droppedOverflow: Long = 0L

    public fun push(seq: Long, captureTsMs: Long, pcm: ByteArray) {
        val unwrapped = unwrap(seq)
        val slot = (unwrapped % slots.size).toInt()
        val existing = slots[slot]
        if (existing != null && existing.seq == unwrapped) {
            duplicates++
            return
        }
        if (started && unwrapped < nextSeq) {
            late++
            return
        }
        if (existing != null) {
            // Only reachable when an arrival lands a whole ring behind a queued packet: it is older
            // than what is already waiting, so it is late too (never a reason to evict the newer one).
            late++
            return
        }
        slots[slot] = Packet(unwrapped, captureTsMs, pcm)
        queued++
        received++
        val newest = newestSeq
        if (newest == null || unwrapped > newest) newestSeq = unwrapped
        if (!started && (queued == 1 || unwrapped < earliestSeq)) earliestSeq = unwrapped
        if (queued > maxPackets) {
            // RVW22 F46: drop the newest packet, never the one about to play. Removing the earliest
            // guarantees concealment on the very next tick (an audible gap the listener hears now);
            // the newest packet is the one that has waited least and that no tick has been promised.
            val newestPacket = newestSeq
            if (newestPacket != null) {
                val newestSlot = (newestPacket % slots.size).toInt()
                if (slots[newestSlot]?.seq == newestPacket) {
                    slots[newestSlot] = null
                    queued--
                    // The dropped packet was the unwrap reference; the next-highest queued one is now.
                    newestSeq = highestQueuedAtMost(newestPacket - 1)
                }
            }
            droppedOverflow++
        }
    }

    private fun unwrap(seq: Long): Long {
        val reference = newestSeq ?: return seq
        // Signed 32-bit difference: 0 after 0xFFFFFFFF is +1, not -4294967295.
        return reference + (seq - reference).toInt()
    }

    public fun poll(packetMs: Long): Poll {
        if (!started) {
            if (queued > 0 && queued * packetMs >= targetDepthMs) {
                started = true
                nextSeq = earliestSeq
            } else {
                return Poll.Starving
            }
        }
        val slot = (nextSeq % slots.size).toInt()
        val packet = slots[slot]
        val seq = nextSeq
        nextSeq++
        if (packet != null && packet.seq == seq) {
            slots[slot] = null
            queued--
            return Poll.Ready(packet.pcm, packet.seq)
        }
        lost++
        return Poll.Concealed(seq)
    }

    public fun stats(): Stats = Stats(received, lost, late, duplicates, droppedOverflow, queued)

    public fun reset() {
        slots.fill(null)
        queued = 0
        started = false
        nextSeq = 0L
        earliestSeq = 0L
        newestSeq = null
        received = 0L
        lost = 0L
        late = 0L
        duplicates = 0L
        droppedOverflow = 0L
    }

    /**
     * The highest sequence at or below [limit] that is still queued, or null. Only used when the
     * newest arrival had to be dropped: the unwrap reference moves back to the highest packet that
     * is still waiting, so the next arrival is still unwrapped against a live packet.
     */
    private fun highestQueuedAtMost(limit: Long): Long? {
        for (seq in limit downTo (limit - slots.size + 1)) {
            val packet = slots[(seq % slots.size).toInt()]
            if (packet != null && packet.seq == seq) return seq
        }
        return null
    }

    private companion object {
        /**
         * Ring size: a power of two above [DEFAULT_MAX_PACKETS] so the live window can never wrap onto
         * itself (a slot is only reused after its packet was played or dropped).
         */
        const val DEFAULT_MAX_PACKETS = 100
    }
}

/** Smallest ring that holds [maxPackets] consecutive sequences without aliasing. */
private fun capacityFor(maxPackets: Int): Int {
    var capacity = 16
    while (capacity <= maxPackets) capacity = capacity shl 1
    return capacity
}
