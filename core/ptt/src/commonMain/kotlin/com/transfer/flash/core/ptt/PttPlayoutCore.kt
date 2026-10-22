package com.transfer.flash.core.ptt

import com.transfer.flash.core.ptt.floor.PttAudioLevel
import com.transfer.flash.core.ptt.floor.PttJitterBuffer
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
    private var concealedRun: Int = 0

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
                concealedRun = 0
                readyTotal += 1
                level = PttAudioLevel.rms01(tick.pcm)
            }
            is PttJitterBuffer.Poll.Concealed -> {
                // Repeating the last packet at full volume over a long gap is a buzzing loop: fade it
                // (half, then a quarter) and go silent from the third packet on (RVW22 F27).
                concealedRun += 1
                val repeated = last
                out = if (repeated == null || concealedRun > MAX_CONCEALED_PACKETS) {
                    zeros
                } else {
                    attenuated(repeated, CONCEALED_GAIN_SHIFT * concealedRun)
                }
                concealedTotal += 1
                level = PttAudioLevel.rms01(out)
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

    /** [pcm] (LE int16) shifted right by [shift] bits, i.e. divided by 2^shift. */
    private fun attenuated(pcm: ByteArray, shift: Int): ByteArray {
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt() shr shift
            out[i] = sample.toByte()
            out[i + 1] = (sample shr 8).toByte()
            i += 2
        }
        return out
    }

    companion object {
        const val DEFAULT_TARGET_DEPTH_MS: Long = 120L
        const val INBOX_CAPACITY: Int = 64
        private const val CONCEALED_GAIN_SHIFT = 1
        private const val MAX_CONCEALED_PACKETS = 2
    }
}
