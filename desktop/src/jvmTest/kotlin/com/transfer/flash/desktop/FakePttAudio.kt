package com.transfer.flash.desktop

import com.transfer.flash.core.ptt.PttAudioPlatform
import com.transfer.flash.core.ptt.PttCaptureDevice
import com.transfer.flash.core.ptt.PttCaptureStart
import com.transfer.flash.core.ptt.PttPlayoutDevice
import com.transfer.flash.core.ptt.PttPlayoutSnapshot
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Push-to-talk audio for desktop tests: opens no microphone and no speaker. `DesktopEngine`'s production
 * default is the real `javax.sound` pair, so every test engine gets one of these (see `testDesktopEngine`).
 */
internal class FakePttAudio : PttAudioPlatform {
    val captures = CopyOnWriteArrayList<FakeCapture>()
    val playouts = CopyOnWriteArrayList<FakePlayout>()

    override fun createCapture(
        requestedRateHz: Int,
        packetMs: Int,
        onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
        onCaptureLost: () -> Unit,
    ): PttCaptureDevice = FakeCapture(requestedRateHz, packetMs, onPacket).also { captures += it }

    override fun createPlayout(
        sampleRateHz: Int,
        packetMs: Int,
        onAmplitude: (Float) -> Unit,
        onPlayoutLost: () -> Unit,
    ): PttPlayoutDevice = FakePlayout(sampleRateHz, packetMs).also { playouts += it }

    class FakeCapture(
        val requestedRateHz: Int,
        val packetMs: Int,
        private val onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
    ) : PttCaptureDevice {
        @Volatile
        var packetsEnabled = false

        @Volatile
        var stopped = false

        override fun start(): PttCaptureStart = PttCaptureStart.Started(requestedRateHz, packetMs)

        override fun enablePackets() {
            packetsEnabled = true
        }

        override fun stop() {
            stopped = true
            packetsEnabled = false
        }

        fun emit(pcm: ByteArray, captureTsMs: Long) = onPacket(pcm, captureTsMs)
    }

    class FakePlayout(val sampleRateHz: Int, val packetMs: Int) : PttPlayoutDevice {
        class Offered(val seq: Long, val captureTsMs: Long, val pcm: ByteArray)

        @Volatile
        var started = false

        @Volatile
        var stopped = false

        val offered = CopyOnWriteArrayList<Offered>()

        override fun offer(seq: Long, captureTsMs: Long, pcm: ByteArray): Boolean {
            offered += Offered(seq, captureTsMs, pcm)
            return started && !stopped
        }

        override fun snapshot() = PttPlayoutSnapshot(0L, 0L, 0, 0f)

        override fun start(): Boolean {
            started = true
            return true
        }

        override fun stop() {
            stopped = true
        }
    }
}
