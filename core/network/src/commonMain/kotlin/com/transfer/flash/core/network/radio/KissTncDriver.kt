@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.radio

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.network.kiss.Ax25DecodeError
import com.transfer.flash.core.network.kiss.Ax25DecodeResult
import com.transfer.flash.core.network.kiss.Ax25Frame
import com.transfer.flash.core.network.kiss.Ax25FrameCodec
import com.transfer.flash.core.network.kiss.Kiss
import com.transfer.flash.core.network.kiss.KissDecoderStats
import com.transfer.flash.core.network.kiss.KissFrame
import com.transfer.flash.core.network.kiss.KissFrameCodec
import com.transfer.flash.core.network.kiss.KissStreamDecoder
import com.transfer.flash.core.network.resilience.ReconnectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * KISS parameters the driver sends after (re)connecting. `null` means "do not send this command, leave the radio's own
 * menu value alone": on a consumer handheld the TNC parameters are also set in its menus and it is unverified whether KISS
 * commands override them (BT-00).
 */
public class KissTncConfig(
    /** KISS port nibble for data and commands. A single-port TNC uses 0. */
    public val port: Int = 0,
    /** TXDELAY in milliseconds (sent in 10 ms units, 0..2550). */
    public val txDelayMs: Int? = null,
    /** Persistence P, 0..255. */
    public val persistence: Int? = null,
    /** Slot time in milliseconds (10 ms units). */
    public val slotTimeMs: Int? = null,
    /** TXtail in milliseconds (10 ms units). */
    public val txTailMs: Int? = null,
    /** Full duplex flag. */
    public val fullDuplex: Boolean? = null,
    /** How long a single read waits before the loop checks for cancellation. */
    public val readTimeoutMs: Long = 250,
    /** Size of the read buffer. */
    public val readBufferBytes: Int = 512,
    /** Largest KISS frame accepted from the TNC. */
    public val maxFrameBytes: Int = KissStreamDecoder.DEFAULT_MAX_FRAME_BYTES,
    /**
     * How long a link must stay up (or deliver one KISS frame) before the reconnect backoff starts over. A radio that
     * accepts the open and then drops at once would otherwise be retried every [reconnectBaseMs] for ever (review R2).
     */
    public val stableAfterMs: Long = 10_000,
    /** First reconnect delay. */
    public val reconnectBaseMs: Long = 1_000,
    /** Largest reconnect delay. */
    public val reconnectCapMs: Long = 15_000,
) {
    init {
        require(port in 0..15 && readTimeoutMs > 0 && readBufferBytes >= 16)
    }
}

/** Connection state of a [KissTncDriver]. */
public sealed interface TncLinkState {
    /** [KissTncDriver.start] was not called yet. */
    public data object Idle : TncLinkState

    /** Opening the link; [attempt] counts from 1 since the last stable connection. */
    public data class Connecting(val attempt: Int) : TncLinkState

    /** The link is open. */
    public data class Connected(val link: String, val sinceMs: Long) : TncLinkState

    /** The link failed with [reason]; the next attempt is in [retryInMs]. */
    public data class Waiting(val attempt: Int, val retryInMs: Long, val reason: String) : TncLinkState

    /** [KissTncDriver.stop] completed. */
    public data object Stopped : TncLinkState
}

/** Outcome of a send. */
public sealed interface TxResult {
    /** Written to the link at [atMs] after waiting [queuedMs] in the queue and for pacing. Says nothing about the air. */
    public data class Sent(val atMs: Long, val queuedMs: Long, val bytes: Int) : TxResult

    /** There is no open link, or it died before the frame was written. The caller keeps the frame and retries later. */
    public data object LinkDown : TxResult

    /** [TxPacingPolicy.maxQueue] frames were already waiting. */
    public data object QueueFull : TxResult
}

/** One frame received from the TNC. [ax25] is null for a non-data KISS frame or when [decodeError] explains why not. */
public class TncRx(
    public val atMs: Long,
    public val kiss: KissFrame,
    public val ax25: Ax25Frame?,
    public val decodeError: Ax25DecodeError?,
)

/** Totals since the driver was created. */
public data class TncCounters(
    val txFrames: Long = 0,
    val txBytes: Long = 0,
    val rxFrames: Long = 0,
    val rxBytes: Long = 0,
    val rxNonAx25: Long = 0,
    val reconnects: Long = 0,
)

/**
 * Host side of a KISS TNC: owns the serial link, frames and paces everything written, decodes everything read, and
 * reconnects with backoff when the link dies.
 *
 * Threading: [sendAx25] and [sendParameter] may be called from any coroutine; reads and writes happen on the driver's own
 * coroutines inside the [scope] passed in. The [ByteLink] is only ever touched from those.
 *
 * Evidence: link state changes are emitted as `PROBE` lines (`radio.link.up`, `radio.link.down`, `radio.tnc.params`, see
 * `docs/testing/PROBES.md`) and to [evidence]; raw hex of every write and read goes to [evidence] only.
 *
 * Status: unit-tested against in-memory links and an echo-radio fake; never run against a real TNC.
 */
public class KissTncDriver(
    private val scope: CoroutineScope,
    private val openLink: suspend () -> ByteLink,
    private val config: KissTncConfig = KissTncConfig(),
    private val pacing: TxPacingPolicy = TxPacingPolicy(),
    private val clock: FlashTimeSource = SystemTimeSource,
    private val random01: () -> Double = { Random.nextDouble() },
    private val evidence: RadioEvidence = RadioEvidence.None,
) {
    private class TxItem(
        val bytes: ByteArray,
        val ax25Len: Int,
        val paced: Boolean,
        val label: String,
        val queuedAtMs: Long,
    ) {
        val result: CompletableDeferred<TxResult> = CompletableDeferred()
    }

    private val queue = Channel<TxItem>(capacity = pacing.maxQueue + PARAMETER_SLOTS)
    private val pacer = TxPacer(pacing, random01)
    private val decoder = KissStreamDecoder(config.maxFrameBytes)
    private val stateFlow = MutableStateFlow<TncLinkState>(TncLinkState.Idle)
    private val rxFlow = MutableSharedFlow<TncRx>(extraBufferCapacity = 128, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val lock = Any()
    private var counts = TncCounters()
    private var pendingFrames = 0
    private var job: Job? = null

    /** Connection state. */
    public val state: StateFlow<TncLinkState> get() = stateFlow.asStateFlow()

    /** Frames from the TNC, in order. A slow collector loses the oldest (the buffer is 128). */
    public val received: SharedFlow<TncRx> get() = rxFlow.asSharedFlow()

    /** Totals. */
    public val counters: TncCounters get() = withRadioLock(lock) { counts }

    /** KISS decoder counters (garbage bytes, bad escapes, oversize frames). */
    public val decoderStats: KissDecoderStats get() = decoder.statistics

    /** Starts connecting; idempotent. */
    public fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    /** Stops, closes the link and fails queued sends with [TxResult.LinkDown]. */
    public suspend fun stop() {
        val j = job
        job = null
        j?.cancel()
        j?.join()
        drainQueue()
        stateFlow.value = TncLinkState.Stopped
    }

    /** Queues an AX.25 frame (already encoded, no FCS) for paced transmission. */
    public suspend fun sendAx25(ax25: ByteArray, label: String = "ax25"): TxResult {
        if (stateFlow.value !is TncLinkState.Connected) return TxResult.LinkDown
        val item = TxItem(KissFrameCodec.encodeData(ax25, config.port), ax25.size, true, label, clock.nowMs())
        val admitted = withRadioLock(lock) {
            if (pendingFrames >= pacing.maxQueue) false else { pendingFrames++; true }
        }
        if (!admitted) return TxResult.QueueFull
        try {
            if (queue.trySend(item).isFailure) return TxResult.QueueFull
            return item.result.await()
        } finally {
            withRadioLock(lock) { pendingFrames-- }
        }
    }

    /** Sends a KISS parameter command (TXDELAY etc.) unpaced, e.g. from a diagnostic tool. [value] is the raw byte. */
    public suspend fun sendParameter(command: Int, value: Int, label: String = "param"): TxResult {
        if (stateFlow.value !is TncLinkState.Connected) return TxResult.LinkDown
        val item = TxItem(KissFrameCodec.encodeParameter(command, value, config.port), 0, false, label, clock.nowMs())
        if (queue.trySend(item).isFailure) return TxResult.QueueFull
        return item.result.await()
    }

    private suspend fun runLoop() {
        val backoff = ReconnectPolicy(baseMs = config.reconnectBaseMs, capMs = config.reconnectCapMs, random01 = random01)
        var attempt = 0
        while (coroutineContext.isActive) {
            attempt++
            stateFlow.value = TncLinkState.Connecting(attempt)
            var link: ByteLink? = null
            var reason: String
            var upAt = -1L
            var rxFramesAtUp = 0L
            try {
                link = openLink()
                upAt = clock.nowMs()
                rxFramesAtUp = counters.rxFrames
                stateFlow.value = TncLinkState.Connected(link.description, upAt)
                probe("radio.link.up", "link" to link.description, "attempt" to attempt)
                decoder.reset()
                pacer.reset()
                enqueueParameters()
                reason = serve(link)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                reason = "open_failed:${t.message ?: t::class.simpleName}"
            } finally {
                runCatching { link?.close() }
                drainQueue()
            }
            val s = decoder.statistics
            probe(
                "radio.link.down",
                "reason" to reason,
                "kissOk" to s.framesOk,
                "garbage" to s.garbageBytes,
                "badEscape" to s.badEscapes,
                "oversize" to s.oversize,
            )
            withRadioLock(lock) { counts = counts.copy(reconnects = counts.reconnects + 1) }
            // R2: the backoff starts over only for a link that proved itself: it stayed up, or a frame came in.
            val stable = upAt >= 0 && (clock.nowMs() - upAt >= config.stableAfterMs || counters.rxFrames > rxFramesAtUp)
            if (stable) {
                backoff.reset()
                attempt = 0
            }
            val wait = backoff.nextDelay()
            stateFlow.value = TncLinkState.Waiting(attempt + 1, wait, reason)
            delay(wait)
        }
    }

    private suspend fun serve(link: ByteLink): String {
        var reason = "closed"
        val reasonLock = Any()
        // The first cause wins: a read that returns "cancelled" because the writer failed must not hide "write_error" (R4).
        fun fail(why: String) = withRadioLock(reasonLock) { if (reason == "closed") reason = why }
        coroutineScope {
            // R3: a blocking write (an RFCOMM socket) ignores coroutine cancellation. Closing the link is what wakes it, so
            // the link is closed the moment the scope is cancelled or either side ends, not when serve() finally returns.
            val closer = launch {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { link.close() }
                }
            }
            val tx = launch {
                try {
                    txLoop(link)
                } catch (e: LinkException) {
                    fail("write_error:${e.message}")
                }
            }
            val rx = launch {
                val why = readLoop(link)
                if (why != "cancelled") fail(why)
            }
            rx.invokeOnCompletion { tx.cancel(); closer.cancel() }
            tx.invokeOnCompletion { rx.cancel(); closer.cancel() }
        }
        return reason
    }

    private suspend fun readLoop(link: ByteLink): String {
        val buf = ByteArray(config.readBufferBytes)
        while (coroutineContext.isActive) {
            val n = try {
                link.read(buf, config.readTimeoutMs)
            } catch (e: LinkException) {
                return "read_error:${e.message}"
            }
            if (n < 0) return "eof"
            if (n == 0) continue
            val now = clock.nowMs()
            evidence.dump(now, DumpDirection.RX, buf.copyOf(n))
            val frames = decoder.feed(buf, 0, n)
            withRadioLock(lock) { counts = counts.copy(rxBytes = counts.rxBytes + n) }
            for (kf in frames) deliver(kf, now)
        }
        return "cancelled"
    }

    private fun deliver(kf: KissFrame, now: Long) {
        var ax25: Ax25Frame? = null
        var err: Ax25DecodeError? = null
        if (kf.isData) {
            when (val r = Ax25FrameCodec.decode(kf.data)) {
                is Ax25DecodeResult.Ok -> ax25 = r.frame
                is Ax25DecodeResult.Error -> err = r.reason
            }
        }
        withRadioLock(lock) {
            counts = counts.copy(rxFrames = counts.rxFrames + 1, rxNonAx25 = counts.rxNonAx25 + if (ax25 == null) 1 else 0)
        }
        rxFlow.tryEmit(TncRx(now, kf, ax25, err))
    }

    private suspend fun txLoop(link: ByteLink) {
        for (item in queue) {
            try {
                if (item.paced) {
                    val wait = pacer.waitMs(clock.nowMs())
                    if (wait > 0) delay(wait)
                }
                val now = clock.nowMs()
                try {
                    link.write(item.bytes)
                } catch (e: LinkException) {
                    item.result.complete(TxResult.LinkDown)
                    throw e
                }
                evidence.dump(now, DumpDirection.TX, item.bytes, item.label)
                if (item.paced) pacer.onTransmitted(now, item.ax25Len)
                withRadioLock(lock) { counts = counts.copy(txFrames = counts.txFrames + 1, txBytes = counts.txBytes + item.bytes.size) }
                item.result.complete(TxResult.Sent(now, now - item.queuedAtMs, item.bytes.size))
            } finally {
                item.result.complete(TxResult.LinkDown) // no-op when already completed
            }
        }
    }

    private fun enqueueParameters() {
        fun cmd(command: Int, value: Int?, label: String) {
            if (value == null) return
            val item = TxItem(KissFrameCodec.encodeParameter(command, value.coerceIn(0, 255), config.port), 0, false, label, clock.nowMs())
            queue.trySend(item)
        }
        cmd(Kiss.CMD_TXDELAY, config.txDelayMs?.div(10), "TXDELAY")
        cmd(Kiss.CMD_PERSISTENCE, config.persistence, "P")
        cmd(Kiss.CMD_SLOT_TIME, config.slotTimeMs?.div(10), "SLOTTIME")
        cmd(Kiss.CMD_TX_TAIL, config.txTailMs?.div(10), "TXTAIL")
        cmd(Kiss.CMD_FULL_DUPLEX, config.fullDuplex?.let { if (it) 1 else 0 }, "FULLDUPLEX")
        if (config.txDelayMs != null || config.persistence != null || config.slotTimeMs != null ||
            config.txTailMs != null || config.fullDuplex != null
        ) {
            probe(
                "radio.tnc.params",
                "txDelayMs" to config.txDelayMs,
                "p" to config.persistence,
                "slotMs" to config.slotTimeMs,
                "tailMs" to config.txTailMs,
                "fullDuplex" to config.fullDuplex,
            )
        }
    }

    private fun drainQueue() {
        while (true) {
            val item = queue.tryReceive().getOrNull() ?: break
            item.result.complete(TxResult.LinkDown)
        }
    }

    private fun probe(name: String, vararg fields: Pair<String, Any?>) {
        FlashProbe.emit(name, *fields)
        evidence.note(clock.nowMs(), name, fields.asList())
    }

    private companion object {
        /** Room for the five parameter commands queued at connect, beyond the frame queue. */
        const val PARAMETER_SLOTS: Int = 8
    }
}
