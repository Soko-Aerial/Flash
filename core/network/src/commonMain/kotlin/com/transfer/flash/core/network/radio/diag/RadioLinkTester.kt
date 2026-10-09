package com.transfer.flash.core.network.radio.diag

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.network.kiss.Ax25Address
import com.transfer.flash.core.network.kiss.Ax25DecodeError
import com.transfer.flash.core.network.kiss.Ax25FrameCodec
import com.transfer.flash.core.network.radio.DumpDirection
import com.transfer.flash.core.network.radio.KissTncDriver
import com.transfer.flash.core.network.radio.RadioCrypto
import com.transfer.flash.core.network.radio.RadioEncode
import com.transfer.flash.core.network.radio.RadioEvidenceLog
import com.transfer.flash.core.network.radio.RadioIngest
import com.transfer.flash.core.network.radio.RadioKind
import com.transfer.flash.core.network.radio.RadioSession
import com.transfer.flash.core.network.radio.RadioSessionConfig
import com.transfer.flash.core.network.radio.InMemoryRadioStore
import com.transfer.flash.core.network.radio.TncRx
import com.transfer.flash.core.network.radio.TxResult
import com.transfer.flash.core.network.radio.RadioWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Which of the two test stations this tool instance plays. Both sides use the same [RadioLinkTester.TEST_PASSPHRASE]. */
public enum class TestRole(public val deviceId: String) {
    STATION_A("bt00-a"),
    STATION_B("bt00-b");

    /** The other station. */
    public val peer: TestRole get() = if (this == STATION_A) STATION_B else STATION_A
}

/** Result of [RadioLinkTester.runBurst]. */
public class BurstResult(
    public val requested: Int,
    public val accepted: Int,
    public val refused: Int,
    public val acked: Int,
    public val rttMs: List<Long>,
    public val wallMs: Long,
    public val bodyBytesPerFrame: Int,
    public val infoBytesPerFrame: Int,
) {
    /** Lost = accepted by the driver but never acknowledged within the wait. */
    public val lost: Int get() = accepted - acked

    /** Median round trip in ms, or null. */
    public val medianRttMs: Long? get() = rttMs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** Fastest and slowest round trip in ms. */
    public val minRttMs: Long? get() = rttMs.minOrNull()

    /** Slowest. */
    public val maxRttMs: Long? get() = rttMs.maxOrNull()

    /** Acknowledged application bytes per second over the whole burst (the number BT-00 item 5 asks for). */
    public val goodputBytesPerSec: Double get() = if (wallMs <= 0) 0.0 else acked.toDouble() * bodyBytesPerFrame * 1000.0 / wallMs

    /** Bytes written to the radio per second including every header (an upper bound of what the TNC accepted). */
    public val writeRateBytesPerSec: Double get() = if (wallMs <= 0) 0.0 else accepted.toDouble() * infoBytesPerFrame * 1000.0 / wallMs

    /** One-line summary for the screen and the log. */
    public fun summary(): String =
        "burst requested=$requested accepted=$accepted refused=$refused acked=$acked lost=$lost wallMs=$wallMs " +
            "rttMin=${minRttMs ?: "-"} rttMed=${medianRttMs ?: "-"} rttMax=${maxRttMs ?: "-"} " +
            "goodput=${goodputBytesPerSec.toLong()}B/s ($bodyBytesPerFrame B body per $infoBytesPerFrame B frame)"
}

/**
 * The BT-00 hardware-spike logic, independent of any UI: it drives a [KissTncDriver] through the checks that decide the open
 * questions in `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md` section 0.2, and records everything in [log].
 *
 * What it can answer (with a radio): does the byte path work, does the TNC pass 0xC0/0xDB transparently, how many bytes per
 * second does a 220-byte frame really achieve, what is the round trip with a second station in [responder] mode.
 * What it cannot answer: anything about the radio's LCD, 9600 baud, or the one-app-at-a-time limit (those need a person).
 *
 * The test frames use a fixed, publicly known test key ([TEST_PASSPHRASE]). They prove the frame format and the link; they do
 * NOT demonstrate confidentiality. The plaintext test frames (kiss/ax25 tests) contain only their own description.
 */
public class RadioLinkTester(
    private val scope: CoroutineScope,
    private val driver: KissTncDriver,
    public val log: RadioEvidenceLog,
    private val clock: FlashTimeSource,
    crypto: RadioCrypto,
    public val role: TestRole = TestRole.STATION_A,
    private val infoBudget: Int = RadioWire.DEFAULT_INFO_BUDGET,
) {
    private val session: RadioSession

    /**
     * R1: [RadioSession] is documented "not synchronised, use from one coroutine", yet the receive collector (ingest, ACKs)
     * and the caller's coroutine (burst, test frames) both use it, and [pendingAcks] / [seq] with it. Every access to those
     * goes through this lock; it is never held across a send, so a slow radio cannot stall the receive side.
     */
    private val sessionLock = Mutex()
    private val pairKey: ByteArray = crypto.sha256(("flash-bt00-test-key-v1/" + TEST_PASSPHRASE).encodeToByteArray())
    private val pendingAcks = HashMap<Long, Pair<Long, CompletableDeferred<Long>>>() // firstCounter -> (sentAt, rtt)
    private val feedFlow = MutableStateFlow<List<String>>(emptyList())
    private var collector: Job? = null
    private var seq = 0

    /** When true, every received PING is answered with an ACK (run this on the second station). */
    public var responder: Boolean = true

    init {
        session = RadioSession(
            localId = role.deviceId,
            crypto = crypto,
            replayStore = InMemoryRadioStore(),
            counterStore = InMemoryRadioStore(),
            config = RadioSessionConfig(infoBudget = infoBudget),
        )
        session.addPeer(role.peer.deviceId, pairKey)
    }

    /** The last 300 human-readable lines, newest last. */
    public val feed: StateFlow<List<String>> get() = feedFlow.asStateFlow()

    /** Begins consuming [KissTncDriver.received]; idempotent. */
    public fun start() {
        if (collector?.isActive == true) return
        collector = scope.launch {
            driver.received.collect { handleRx(it) }
        }
    }

    /** Stops consuming. */
    public fun stop() {
        collector?.cancel()
        collector = null
    }

    /**
     * Test 1, transparency: one plaintext UI frame whose information field holds the byte values 0..(n-1), so it contains
     * 0xC0 (FEND) and 0xDB (FESC). If a receiving station shows these bytes intact, the whole path escapes correctly.
     */
    public suspend fun sendKissTransparencyTest(infoBytes: Int = 220): TxResult {
        val info = ByteArray(infoBytes.coerceIn(1, 255)) { it.toByte() }
        return sendPlain("kiss-transparency", info)
    }

    /** Test 2: a short readable plaintext UI frame, for a monitor receiver or APRS software on a second radio. */
    public suspend fun sendAx25TextTest(): TxResult {
        val n = sessionLock.withLock { ++seq }
        return sendPlain("ax25-text", "FLASH BT-00 AX25 UI TEST #$n at ${clock.nowMs()}".encodeToByteArray())
    }

    /** Test 3: one real Flash radio frame of exactly [infoBudget] bytes (a PING with a full body), sealed with the test key. */
    public suspend fun sendFlashFramePayload(): TxResult {
        val body = ByteArray(RadioWire.maxBodyUnsegmented(infoBudget)) { (it * 31 + 7).toByte() }
        val enc = sessionLock.withLock { session.encode(role.peer.deviceId, RadioKind.PING, body, clock.nowMs()) }
        if (enc !is RadioEncode.Frames) return TxResult.LinkDown
        note("flash-frame size=${enc.frames[0].size} counter=${enc.firstCounter}")
        return sendInfo("flash-frame", enc.frames[0])
    }

    /**
     * Test 4: [count] PING frames with [bodyBytes] each, paced by the driver's policy; waits up to [ackWaitMs] after the last
     * frame for ACKs from a responder. Reports round trips and goodput.
     */
    public suspend fun runBurst(count: Int, bodyBytes: Int, ackWaitMs: Long = 30_000): BurstResult {
        val body = ByteArray(bodyBytes.coerceIn(0, RadioWire.maxBodyUnsegmented(infoBudget))) { (it * 17 + 3).toByte() }
        val started = clock.nowMs()
        var accepted = 0
        var refused = 0
        val waiters = ArrayList<CompletableDeferred<Long>>()
        val ownCounters = ArrayList<Long>() // this burst's pending-ACK keys: a finishing burst must not clear another burst's
        var infoSize = 0
        note("burst start count=$count body=${body.size}")
        for (i in 0 until count) {
            val ack = CompletableDeferred<Long>()
            // Encode and register the expected ACK in one step, so an ACK can never arrive for a counter nobody waits for.
            val enc = sessionLock.withLock {
                session.encode(role.peer.deviceId, RadioKind.PING, body, clock.nowMs()).also {
                    if (it is RadioEncode.Frames) {
                        pendingAcks[it.firstCounter] = clock.nowMs() to ack
                        ownCounters += it.firstCounter
                    }
                }
            }
            if (enc !is RadioEncode.Frames) {
                refused++
                continue
            }
            infoSize = enc.frames[0].size
            val res = sendInfo("burst-${i + 1}/$count", enc.frames[0])
            if (res is TxResult.Sent) {
                accepted++
                waiters += ack
                // RTT counts from the moment the bytes were written (unless the ACK already came in).
                sessionLock.withLock { if (!ack.isCompleted && pendingAcks.containsKey(enc.firstCounter)) pendingAcks[enc.firstCounter] = res.atMs to ack }
            } else {
                refused++
                sessionLock.withLock { pendingAcks.remove(enc.firstCounter) }
            }
        }
        val deadline = clock.nowMs() + ackWaitMs
        val rtts = ArrayList<Long>()
        var lastAckAt = started
        for (w in waiters) {
            val remaining = (deadline - clock.nowMs()).coerceAtLeast(0)
            val rtt = withTimeoutOrNull(remaining) { w.await() }
            if (rtt != null) {
                rtts += rtt
                lastAckAt = maxOf(lastAckAt, clock.nowMs())
            }
        }
        sessionLock.withLock { ownCounters.forEach { pendingAcks.remove(it) } }
        val wall = (if (rtts.isEmpty()) clock.nowMs() else lastAckAt) - started
        val result = BurstResult(count, accepted, refused, rtts.size, rtts, wall, body.size, infoSize)
        note(result.summary())
        return result
    }

    /** A note in both the log and the on-screen feed. */
    public fun note(text: String) {
        log.note(clock.nowMs(), "tester", listOf("msg" to text))
        pushFeed("${clock.nowMs()} $text")
    }

    /** One-line description of a received frame, for the screen. */
    public fun describe(rx: TncRx): String {
        val k = rx.kiss
        val a = rx.ax25
        return if (a != null) {
            "RX kiss(port=${k.port} cmd=${k.command}) ${a.source}>${a.destination} ctl=0x${a.control.toString(16)} " +
                "pid=${a.pid?.let { "0x" + it.toString(16) } ?: "-"} info=${a.info.size}B ${infoPreview(a.info)}"
        } else {
            "RX kiss(port=${k.port} cmd=${k.command}) ${k.data.size}B " +
                (rx.decodeError?.let { "not-ax25(${it.name.lowercase()})" } ?: "non-data")
        }
    }

    private suspend fun sendPlain(label: String, info: ByteArray): TxResult {
        val src = Ax25Address("BT00" + role.name.last(), 0)
        val frame = Ax25FrameCodec.encodeUi(Ax25Address("FLTEST"), src, info)
        note("$label size=${frame.size}")
        return sendRaw(label, frame)
    }

    private suspend fun sendInfo(label: String, info: ByteArray): TxResult {
        val src = sessionLock.withLock { session.stationLabel(role.peer.deviceId, clock.nowMs()) }
        val frame = Ax25FrameCodec.encodeUi(Ax25Address("FLTEST"), src, info)
        return sendRaw(label, frame)
    }

    private suspend fun sendRaw(label: String, ax25: ByteArray): TxResult {
        val res = driver.sendAx25(ax25, label)
        pushFeed("${clock.nowMs()} TX $label ${ax25.size}B -> ${res::class.simpleName}")
        return res
    }

    private suspend fun handleRx(rx: TncRx) {
        pushFeed("${rx.atMs} ${describe(rx)}")
        val a = rx.ax25 ?: return
        if (!a.isUi) return
        when (val r = sessionLock.withLock { session.ingest(a.info, clock.nowMs()) }) {
            is RadioIngest.Delivered -> {
                note("flash-rx kind=${r.kind} from=${r.peerId} body=${r.body.size}B counter=${r.firstCounter}")
                if (r.kind == RadioKind.PING && responder) {
                    val ack = sessionLock.withLock { session.encodeAck(r.peerId, r.firstCounter, clock.nowMs()) }
                    if (ack is RadioEncode.Frames) sendInfo("ack", ack.frames[0])
                } else if (r.kind == RadioKind.ACK && r.body.size == 4) {
                    val acked = r.body.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                    val p = sessionLock.withLock { pendingAcks.remove(acked) }
                    if (p != null) p.second.complete(clock.nowMs() - p.first)
                }
            }
            is RadioIngest.Partial -> note("flash-rx partial ${r.received}/${r.total}")
            is RadioIngest.Dropped -> if (r.reason != com.transfer.flash.core.network.radio.RadioDrop.NOT_FLASH) {
                note("flash-rx dropped reason=${r.reason}${r.detail?.let { " ($it)" } ?: ""}")
            }
        }
    }

    private fun infoPreview(info: ByteArray): String {
        val printable = info.take(40).all { it in 32..126 }
        return if (printable) "\"" + info.take(40).map { it.toInt().toChar() }.joinToString("") + "\"" else "hex=" + RadioEvidenceLog.toHex(info, 24)
    }

    private fun pushFeed(line: String) {
        // update{} is an atomic read-modify-write: the receive collector and the caller both push lines (R1).
        feedFlow.update { cur -> if (cur.size >= 300) cur.drop(cur.size - 299) + line else cur + line }
    }

    /** Constants. */
    public companion object {
        /** Both test stations derive the same key from this public string. A TEST key: never use for real traffic. */
        public const val TEST_PASSPHRASE: String = "flash-bt00-hardware-spike"
    }
}

/** Used by the UI to show a decode problem as text. */
public fun describeDecodeError(e: Ax25DecodeError?): String = e?.name?.lowercase()?.replace('_', ' ') ?: "ok"

/** Unused import guard: [DumpDirection] is part of this package's public evidence vocabulary. */
internal val EVIDENCE_DIRECTIONS: List<DumpDirection> = DumpDirection.entries
