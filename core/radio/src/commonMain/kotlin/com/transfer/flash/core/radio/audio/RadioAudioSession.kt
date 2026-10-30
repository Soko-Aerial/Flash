package com.transfer.flash.core.radio.audio

import com.transfer.flash.core.radio.link.ByteLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Plays received radio audio. Must not block: drop the oldest audio rather than wait (about 800 ms cap). */
public interface RadioPcmOutput {
    /** Prepares to play 32 kHz mono PCM16. */
    public fun start(sampleRate: Int)

    /** Queues [length] samples of [pcm]; non-blocking. */
    public fun write(pcm: ShortArray, length: Int)

    /** Stops and releases the output. Idempotent. */
    public fun stop()
}

/** Captures the microphone. */
public interface RadioPcmInput {
    /** Starts capture and returns the actual sample rate (mono PCM16); the session resamples to 32 kHz. */
    public fun start(): Int

    /** Reads up to [buffer].size samples; suspends until some are ready. -1 when stopped. */
    public suspend fun read(buffer: ShortArray): Int

    /** Stops and releases the microphone. Idempotent. */
    public fun stop()
}

public enum class RadioAudioPhase { LISTENING, RECEIVING, TRANSMITTING, CLOSED, FAILED }

/** What the UI shows. Levels are the 0..1 peak of the last chunk. */
public data class RadioAudioState(
    val phase: RadioAudioPhase = RadioAudioPhase.LISTENING,
    val rxLevel: Float = 0f,
    val txLevel: Float = 0f,
    /** A short sentence for people: why the link failed, or why a transmit was refused. */
    val message: String? = null,
)

/**
 * One live audio link to a radio (the vendor RFCOMM channel, HTCommander's "audio channel"). Plays what the radio
 * receives and, while push-to-talk is held, sends the microphone; sending frames keys the radio itself.
 *
 * Push-to-talk is refused while a transmission is being received (anti-doubling). The session owns [link] and closes it.
 * Not thread-safe: call from one thread (the UI thread).
 */
public class RadioAudioSession(
    private val link: ByteLink,
    private val output: RadioPcmOutput,
    private val input: RadioPcmInput,
    private val scope: CoroutineScope,
    private val bitpool: Int = RadioAudioFormat.DEFAULT_BITPOOL,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(RadioAudioState())
    public val state: StateFlow<RadioAudioState> = _state.asStateFlow()

    private val writeLock = Mutex()
    private var readJob: Job? = null
    private var txJob: Job? = null
    private var closed = false
    private var outputStarted = false

    @kotlin.concurrent.Volatile
    private var txStop = false

    /** Starts listening. Call once. */
    public fun start() {
        check(readJob == null) { "already started" }
        readJob = scope.launch { readLoop() }
    }

    /** Starts transmitting. Returns false when refused (receiving, closed, failed, already sending). */
    public fun pushToTalkDown(): Boolean {
        if (closed || _state.value.phase == RadioAudioPhase.FAILED) return false
        if (_state.value.phase == RadioAudioPhase.RECEIVING) {
            _state.value = _state.value.copy(message = "The radio is receiving. Wait until it is quiet.")
            return false
        }
        if (txJob?.isActive == true) return false
        txStop = false
        _state.value = _state.value.copy(phase = RadioAudioPhase.TRANSMITTING, txLevel = 0f, message = null)
        txJob = scope.launch { txLoop() }
        return true
    }

    /** Stops transmitting; the END frame is sent in order after the last audio. */
    public fun pushToTalkUp() {
        txStop = true
    }

    /** Ends the session. If it is transmitting, the radio is unkeyed first (END frame, at most 1 s). */
    public fun close() {
        if (closed) return
        closed = true
        val tx = txJob
        if (tx != null && tx.isActive) {
            txStop = true
            scope.launch {
                withTimeoutOrNull(UNKEY_TIMEOUT_MS) { tx.join() }
                tx.cancel()
                shutdown(RadioAudioState(RadioAudioPhase.CLOSED))
            }
        } else {
            shutdown(RadioAudioState(RadioAudioPhase.CLOSED))
        }
    }

    private fun shutdown(final: RadioAudioState) {
        readJob?.cancel()
        link.close()
        stopOutput()
        _state.value = final
    }

    private suspend fun readLoop() {
        val buf = ByteArray(2048)
        var idleMs = 0L
        val receiver = RadioAudioReceiver(
            onRunStart = {
                startOutput()
                _state.value = _state.value.copy(phase = RadioAudioPhase.RECEIVING, message = null)
                log("rx run start")
            },
            onPcm = { pcm ->
                output.write(pcm, pcm.size)
                _state.value = _state.value.copy(rxLevel = peak(pcm, pcm.size))
                idleMs = 0
            },
            onRunEnd = {
                if (_state.value.phase == RadioAudioPhase.RECEIVING) {
                    _state.value = _state.value.copy(phase = RadioAudioPhase.LISTENING, rxLevel = 0f)
                }
                log("rx run end")
            },
        )
        val decoder = AudioFrameDecoder { receiver.onFrame(it) }
        try {
            while (true) {
                val n = link.read(buf, READ_TIMEOUT_MS)
                when {
                    n < 0 -> { fail("The radio closed the audio link."); return }
                    n == 0 -> {
                        idleMs += READ_TIMEOUT_MS
                        if (receiver.inRun && idleMs >= RX_IDLE_END_MS) receiver.endRun()
                    }
                    else -> decoder.feed(buf, 0, n)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("rx failed: ${e.message}")
            fail("The audio link to the radio was lost.")
        }
    }

    private suspend fun txLoop() {
        val tx = RadioAudioTransmitter(bitpool)
        val buf = ShortArray(640)
        var failed = false
        try {
            val resampler = LinearResampler(input.start())
            while (!txStop) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                _state.value = _state.value.copy(txLevel = peak(buf, n))
                for (p in tx.push(resampler.process(buf, n))) send(p)
            }
        } catch (e: CancellationException) {
            // fall through: still unkey
        } catch (e: Exception) {
            log("tx failed: ${e.message}")
            failed = true
        } finally {
            input.stop()
        }
        try {
            withContext(NonCancellable) { for (p in tx.finish()) send(p) }
        } catch (e: Exception) {
            log("tx end failed: ${e.message}")
            failed = true
        }
        if (failed) {
            fail("The audio link to the radio was lost.")
        } else if (_state.value.phase == RadioAudioPhase.TRANSMITTING) {
            _state.value = _state.value.copy(phase = RadioAudioPhase.LISTENING, txLevel = 0f)
        }
    }

    private suspend fun send(data: ByteArray) = writeLock.withLock { link.write(data) }

    private fun startOutput() {
        if (outputStarted) return
        outputStarted = true
        output.start(RadioAudioFormat.SAMPLE_RATE)
    }

    private fun stopOutput() {
        if (!outputStarted) return
        outputStarted = false
        output.stop()
    }

    private fun fail(message: String) {
        if (closed) return
        closed = true
        txStop = true
        txJob?.cancel()
        shutdown(RadioAudioState(RadioAudioPhase.FAILED, message = message))
    }

    private fun peak(pcm: ShortArray, n: Int): Float {
        var m = 0
        for (i in 0 until n) m = maxOf(m, abs(pcm[i].toInt()))
        return m / 32768f
    }

    private companion object {
        const val READ_TIMEOUT_MS = 100L

        /** A run with no audio for this long has ended even if the END frame never arrived. */
        const val RX_IDLE_END_MS = 700L

        const val UNKEY_TIMEOUT_MS = 1_000L
    }
}
