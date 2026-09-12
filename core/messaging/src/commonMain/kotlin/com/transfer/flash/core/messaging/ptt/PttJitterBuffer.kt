package com.transfer.flash.core.messaging.ptt

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
    private var received: Long = 0L
    private var lost: Long = 0L
    private var late: Long = 0L
    private var duplicates: Long = 0L
    private var droppedOverflow: Long = 0L

    public fun push(seq: Long, captureTsMs: Long, pcm: ByteArray) {
        if (queue.containsKey(seq)) {
            duplicates++
            return
        }
        if (started && seq < nextSeq) {
            late++
            return
        }
        queue[seq] = Packet(seq, captureTsMs, pcm)
        received++
        if (queue.size > maxPackets) {
            queue.remove(queue.firstKey())
            droppedOverflow++
        }
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
        received = 0L
        lost = 0L
        late = 0L
        duplicates = 0L
        droppedOverflow = 0L
    }
}
