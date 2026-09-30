package com.transfer.flash.core.ptt

import com.transfer.flash.core.messaging.ptt.PttAudioLevel
import com.transfer.flash.core.messaging.ptt.PttJitterBuffer
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * The platform-neutral half of a playout device: drop-oldest inbox, jitter buffer, concealment and
 * counters. A platform device owns only the output line and the thread that calls [nextPacket] in a
 * loop and writes the result (the blocking write paces the loop; there is no sleep math).
 *
 * Threading: [offer] and [snapshot] are safe from any thread. [nextPacket] belongs to the playout
 * thread alone — it is the only toucher of the jitter buffer and the only writer of the counters, so
 * plain volatiles are enough.
 */
internal class PttPlayoutCore(
    sampleRateHz: Int,
    private val packetMs: Int,
    targetDepthMs: Long = DEFAULT_TARGET_DEPTH_MS,
) {
    private class Incoming(val seq: Long, val captureTsMs: Long, val pcm: ByteArray)

    val bytesPerPacket: Int = sampleRateHz * packetMs / 1000 * 2

    private val inbox = Channel<Incoming>(INBOX_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val buffer = PttJitterBuffer(targetDepthMs)
    private val zeros = ByteArray(bytesPerPacket)
    private var last: ByteArray? = null

    @Volatile
    private var readyTotal: Long = 0L

    @Volatile
    private var concealedTotal: Long = 0L

    @Volatile
    private var lastDepthPackets: Int = 0

    @Volatile
    var lastAmplitude: Float = 0f
        private set

    /** Non-blocking enqueue. Rejects a packet outside the negotiated format. */
    fun offer(seq: Long, captureTsMs: Long, pcm: ByteArray): Boolean {
        if (pcm.size != bytesPerPacket) return false
        return inbox.trySend(Incoming(seq, captureTsMs, pcm)).isSuccess
    }

    fun snapshot(): PttPlayoutSnapshot =
        PttPlayoutSnapshot(readyTotal, concealedTotal, lastDepthPackets, lastAmplitude)

    /**
     * One packet duration of audio to write: the next ready packet, a repeat of the last one on a
     * gap, or silence while starving. Also updates the counters and [lastAmplitude].
     */
    fun nextPacket(): ByteArray {
        var incoming = inbox.tryReceive().getOrNull()
        while (incoming != null) {
            buffer.push(incoming.seq, incoming.captureTsMs, incoming.pcm)
            incoming = inbox.tryReceive().getOrNull()
        }
        val out: ByteArray
        val level: Float
        when (val tick = buffer.poll(packetMs.toLong())) {
            is PttJitterBuffer.Poll.Ready -> {
                out = tick.pcm
                last = tick.pcm
                readyTotal += 1
                level = PttAudioLevel.rms01(tick.pcm)
            }
            is PttJitterBuffer.Poll.Concealed -> {
                out = last ?: zeros
                concealedTotal += 1
                level = PttAudioLevel.rms01(out) * CONCEALED_LEVEL_FACTOR
            }
            is PttJitterBuffer.Poll.Starving -> {
                out = zeros
                level = 0f
            }
        }
        lastAmplitude = level
        lastDepthPackets = buffer.stats().depthPackets
        return out
    }

    companion object {
        const val DEFAULT_TARGET_DEPTH_MS: Long = 120L
        const val INBOX_CAPACITY: Int = 64
        private const val CONCEALED_LEVEL_FACTOR = 0.5f
    }
}
