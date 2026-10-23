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

    private data class Packet(val seq: Long, val captureTsMs: Long, val pcm: ByteArray)

    private val queue = sortedMapOf<Long, Packet>()
    private var started: Boolean = false
    private var nextSeq: Long = 0L

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
        if (queue.containsKey(unwrapped)) {
            duplicates++
            return
        }
        if (started && unwrapped < nextSeq) {
            late++
            return
        }
        queue[unwrapped] = Packet(unwrapped, captureTsMs, pcm)
        received++
        val newest = newestSeq
        if (newest == null || unwrapped > newest) newestSeq = unwrapped
        if (queue.size > maxPackets) {
            // RVW22 F46: drop the newest packet, never the one about to play. Removing the earliest
            // guarantees concealment on the very next tick (an audible gap the listener hears now);
            // the newest packet is the one that has waited least and that no tick has been promised.
            queue.remove(queue.lastKey())
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
            if (queue.isNotEmpty() && queue.size * packetMs >= targetDepthMs) {
                started = true
                nextSeq = queue.firstKey()
            } else {
                return Poll.Starving
            }
        }
        val packet = queue.remove(nextSeq)
        val seq = nextSeq
        nextSeq++
        return if (packet != null) {
            Poll.Ready(packet.pcm, packet.seq)
        } else {
            lost++
            Poll.Concealed(seq)
        }
    }

    public fun stats(): Stats = Stats(received, lost, late, duplicates, droppedOverflow, queue.size)

    public fun reset() {
        queue.clear()
        started = false
        nextSeq = 0L
        newestSeq = null
        received = 0L
        lost = 0L
        late = 0L
        duplicates = 0L
        droppedOverflow = 0L
    }
}
