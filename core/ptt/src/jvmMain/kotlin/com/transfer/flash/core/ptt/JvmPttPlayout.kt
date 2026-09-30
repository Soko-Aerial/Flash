@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.ptt

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

/**
 * Desktop speaker playout for PTT (ADR-058), the counterpart of the Android `PttPlayout`.
 *
 * The inbox, jitter buffer and concealment live in the shared [PttPlayoutCore]; this class owns the
 * output line and the thread that writes one packet duration per pass. A blocking line write paces
 * the loop (no sleep math). One instance per session. Unexpected line death reports [onPlayoutLost];
 * [stop] never reports.
 */
internal class JvmPttPlayout(
    private val lines: PttPcmLines,
    private val sampleRateHz: Int,
    private val packetMs: Int,
    private val onAmplitude: (Float) -> Unit = {},
    private val onPlayoutLost: () -> Unit = {},
    targetDepthMs: Long = PttPlayoutCore.DEFAULT_TARGET_DEPTH_MS,
) : PttPlayoutDevice {
    private val core = PttPlayoutCore(sampleRateHz, packetMs, targetDepthMs)
    private val running = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var output: PttPcmOutput? = null

    override fun offer(seq: Long, captureTsMs: Long, pcm: ByteArray): Boolean {
        if (!running.get()) return false
        return core.offer(seq, captureTsMs, pcm)
    }

    override fun snapshot(): PttPlayoutSnapshot = core.snapshot()

    override fun start(): Boolean {
        if (running.get()) return true
        val line = lines.openOutput(sampleRateHz, core.bytesPerPacket * LINE_PACKETS) ?: run {
            FlashLog.w(TAG, "Playback line init failed rate=$sampleRateHz")
            return false
        }
        output = line
        running.set(true)
        val thread = Thread({ loop(line) }, "ptt-playout").apply { isDaemon = true }
        worker = thread
        thread.start()
        FlashLog.i(TAG, "Playout started rate=$sampleRateHz packetMs=$packetMs")
        return true
    }

    override fun stop() {
        running.set(false)
        closeOutput()
        runCatching { worker?.join(JOIN_MS) }
        worker = null
    }

    private fun loop(line: PttPcmOutput) {
        try {
            while (running.get()) {
                writeFully(line, core.nextPacket())
                onAmplitude(core.lastAmplitude)
            }
        } catch (error: Exception) {
            // Line death mid-session (device unplugged). running is still true only if stop() did not start this exit.
            FlashLog.w(TAG, "Playout loop died", error)
            if (running.getAndSet(false)) onPlayoutLost()
        } finally {
            closeOutput()
        }
    }

    private fun writeFully(line: PttPcmOutput, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size && running.get()) {
            val written = line.write(pcm, offset, pcm.size - offset)
            if (written <= 0) throw IllegalStateException("playback write=$written")
            offset += written
        }
    }

    private fun closeOutput() {
        val current = output ?: return
        // flush() before stop()/close(): discarding the queued data is what releases a write blocked on a full buffer.
        runCatching { current.flush() }
        runCatching { current.stop() }
        runCatching { current.close() }
        if (output === current) output = null
    }

    private companion object {
        const val TAG = "PTT_OUT"
        const val LINE_PACKETS = 8
        const val JOIN_MS = 1000L
    }
}
