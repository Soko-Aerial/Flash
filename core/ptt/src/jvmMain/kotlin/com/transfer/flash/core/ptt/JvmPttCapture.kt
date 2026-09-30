@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.ptt

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

/**
 * Desktop microphone capture for PTT (ADR-058), the counterpart of the Android `PttCapture`.
 *
 * Pulls fixed-size PCM16 mono chunks off a [PttPcmInput] on a dedicated thread and hands them to
 * [onPacket] (still on the capture thread; the engine forwards without blocking this loop). The rate
 * falls back requested -> 8000 when the driver refuses it, like the Android loop. Any unexpected loop
 * exit while still armed reports [onCaptureLost] once; [stop] never reports.
 *
 * Unlike Android there is no system-mute callback: a desktop whose microphone access is blocked (the
 * Windows privacy switch) may deliver silence without an error. That case is NOT detected here; it is
 * a documented limitation and a device test (TEST-BACKLOG PTTD-03).
 */
internal class JvmPttCapture(
    private val lines: PttPcmLines,
    private val requestedRateHz: Int,
    private val packetMs: Int,
    private val onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
    private val onCaptureLost: () -> Unit,
    private val clockMs: () -> Long = ::pttElapsedRealtimeMs,
    private val stalledReadLimitMs: Long = STALLED_READ_LIMIT_MS,
) : PttCaptureDevice {
    private val running = AtomicBoolean(false)
    private val packetsEnabled = AtomicBoolean(false)
    private val lossReported = AtomicBoolean(false)

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var input: PttPcmInput? = null

    @Volatile
    private var actualRateHz: Int = requestedRateHz

    @Volatile
    private var actualPacketMs: Int = packetMs

    override fun start(): PttCaptureStart {
        if (running.get()) return PttCaptureStart.Started(actualRateHz, actualPacketMs)
        val rates = listOf(requestedRateHz, FALLBACK_RATE_HZ).distinct()
        var opened: PttPcmInput? = null
        for (rate in rates) {
            val effectivePacketMs = if (rate <= FALLBACK_RATE_HZ) LOW_PACKET_MS else packetMs
            val bufferBytes = rate * effectivePacketMs / 1000 * BYTES_PER_SAMPLE * BUFFER_PACKETS
            val candidate = lines.openInput(rate, bufferBytes) ?: continue
            opened = candidate
            actualRateHz = rate
            actualPacketMs = effectivePacketMs
            break
        }
        val line = opened ?: run {
            FlashLog.w(TAG, "No workable capture line (rates=$rates)")
            return PttCaptureStart.Failed
        }
        input = line
        packetsEnabled.set(false)
        lossReported.set(false)
        running.set(true)
        val service = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ptt-capture").apply { isDaemon = true }
        }
        executor = service
        val bytesPerPacket = actualRateHz * actualPacketMs / 1000 * BYTES_PER_SAMPLE
        FlashLog.i(TAG, "Capture started rate=$actualRateHz packetMs=$actualPacketMs")
        service.execute { captureLoop(line, bytesPerPacket) }
        return PttCaptureStart.Started(actualRateHz, actualPacketMs)
    }

    private fun captureLoop(line: PttPcmInput, bytesPerPacket: Int) {
        val chunk = ByteArray(bytesPerPacket)
        var offset = 0
        var stalledSinceMs = NOT_STALLED
        try {
            while (running.get()) {
                val read = runCatching { line.read(chunk, offset, chunk.size - offset) }.getOrDefault(-1)
                if (!running.get()) break
                when {
                    read > 0 -> {
                        stalledSinceMs = NOT_STALLED
                        offset += read
                        if (offset == chunk.size) {
                            if (packetsEnabled.get()) onPacket(chunk.copyOf(), clockMs())
                            offset = 0
                        }
                    }
                    read < 0 -> {
                        FlashLog.w(TAG, "Capture read error=$read — ending capture")
                        break
                    }
                    else -> {
                        // 0 bytes from a live line: a stopped or vanished device would otherwise spin here.
                        val now = clockMs()
                        if (stalledSinceMs == NOT_STALLED) stalledSinceMs = now
                        if (now - stalledSinceMs > stalledReadLimitMs) {
                            FlashLog.w(TAG, "Capture delivered nothing for ${now - stalledSinceMs} ms — ending capture")
                            break
                        }
                        Thread.sleep(IDLE_SLEEP_MS)
                    }
                }
            }
        } finally {
            val unexpected = running.getAndSet(false)
            closeInput()
            shutdownExecutorFromWorker()
            if (unexpected && lossReported.compareAndSet(false, true)) {
                FlashLog.w(TAG, "Capture loop exited while armed")
                onCaptureLost()
            }
        }
    }

    override fun enablePackets() {
        if (running.get()) packetsEnabled.set(true)
    }

    override fun stop() {
        packetsEnabled.set(false)
        running.set(false)
        closeInput()
        val service = executor
        if (service != null) {
            service.shutdown()
            runCatching { service.awaitTermination(1L, TimeUnit.SECONDS) }
            service.shutdownNow()
            if (executor === service) executor = null
        }
    }

    private fun closeInput() {
        val current = input ?: return
        // stop() first: it is what releases a read that is blocked on the device.
        runCatching { current.stop() }
        runCatching { current.close() }
        if (input === current) input = null
    }

    private fun shutdownExecutorFromWorker() {
        val service = executor ?: return
        service.shutdown()
        if (executor === service) executor = null
    }

    private companion object {
        const val TAG = "PTT_CAP"
        const val FALLBACK_RATE_HZ = 8000
        const val LOW_PACKET_MS = 60
        const val BUFFER_PACKETS = 4
        const val BYTES_PER_SAMPLE = 2
        const val NOT_STALLED = -1L
        const val STALLED_READ_LIMIT_MS = 1_000L
        const val IDLE_SLEEP_MS = 2L
    }
}
