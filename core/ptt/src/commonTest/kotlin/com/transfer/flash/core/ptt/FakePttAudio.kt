package com.transfer.flash.core.ptt

import kotlin.concurrent.Volatile

/** Thread-safe append-only list: the engine calls the fakes from its own threads, the test reads from another. */
internal class Recorder<T> {
    private val lock = PttLock()
    private val items = mutableListOf<T>()

    fun add(value: T) {
        lock.withLock { items += value }
    }

    fun snapshot(): List<T> = lock.withLock { items.toList() }

    val size: Int get() = snapshot().size
}

/**
 * A [PttAudioPlatform] that opens no hardware. Every device it hands out is kept in [captures] /
 * [playouts], and [events] is one ordered log across the devices and the test's transport fakes, so a
 * test can assert ordering (for example "Start frame sent before packets are enabled").
 */
internal class FakePttAudio : PttAudioPlatform {
    @Volatile
    var captureStart: PttCaptureStart = PttCaptureStart.Started(actualRateHz = 16_000, actualPacketMs = 20)

    @Volatile
    var playoutStarts: Boolean = true

    val captures = Recorder<FakeCapture>()
    val playouts = Recorder<FakePlayout>()
    val events = Recorder<String>()

    override fun createCapture(
        requestedRateHz: Int,
        packetMs: Int,
        onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
        onCaptureLost: () -> Unit,
    ): PttCaptureDevice = FakeCapture(requestedRateHz, packetMs, onPacket, onCaptureLost, captureStart, events)
        .also { captures.add(it) }

    override fun createPlayout(
        sampleRateHz: Int,
        packetMs: Int,
        onAmplitude: (Float) -> Unit,
        onPlayoutLost: () -> Unit,
    ): PttPlayoutDevice = FakePlayout(sampleRateHz, packetMs, onPlayoutLost, playoutStarts, events)
        .also { playouts.add(it) }
}

internal class FakeCapture(
    val requestedRateHz: Int,
    val packetMs: Int,
    private val onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
    private val onCaptureLost: () -> Unit,
    private val startResult: PttCaptureStart,
    private val events: Recorder<String>,
) : PttCaptureDevice {
    @Volatile
    var started: Boolean = false

    @Volatile
    var packetsEnabled: Boolean = false

    @Volatile
    var stopped: Boolean = false

    override fun start(): PttCaptureStart {
        started = true
        events.add("capture.start")
        return startResult
    }

    override fun enablePackets() {
        packetsEnabled = true
        events.add("capture.enablePackets")
    }

    override fun stop() {
        stopped = true
        packetsEnabled = false
        events.add("capture.stop")
    }

    /** Simulates the capture thread delivering one packet. */
    fun emit(pcm: ByteArray, captureTsMs: Long = 0L) = onPacket(pcm, captureTsMs)

    /** Simulates the device dying underneath a live session. */
    fun lose() = onCaptureLost()
}

internal class FakePlayout(
    val sampleRateHz: Int,
    val packetMs: Int,
    private val onPlayoutLost: () -> Unit,
    private val startResult: Boolean,
    private val events: Recorder<String>,
) : PttPlayoutDevice {
    class Offered(val seq: Long, val captureTsMs: Long, val pcm: ByteArray)

    @Volatile
    var started: Boolean = false

    @Volatile
    var stopped: Boolean = false

    val offered = Recorder<Offered>()

    override fun offer(seq: Long, captureTsMs: Long, pcm: ByteArray): Boolean {
        offered.add(Offered(seq, captureTsMs, pcm))
        return started && !stopped
    }

    override fun snapshot(): PttPlayoutSnapshot =
        PttPlayoutSnapshot(readyTotal = 0L, concealedTotal = 0L, depthPackets = 0, amplitude01 = 0f)

    override fun start(): Boolean {
        started = startResult
        events.add("playout.start")
        return startResult
    }

    override fun stop() {
        stopped = true
        events.add("playout.stop")
    }

    fun lose() = onPlayoutLost()
}
