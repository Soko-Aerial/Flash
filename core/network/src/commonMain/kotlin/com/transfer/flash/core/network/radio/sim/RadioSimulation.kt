package com.transfer.flash.core.network.radio.sim

import com.transfer.flash.core.network.kiss.KissFrame
import com.transfer.flash.core.network.kiss.KissFrameCodec
import com.transfer.flash.core.network.kiss.KissStreamDecoder
import com.transfer.flash.core.network.radio.ByteLink
import com.transfer.flash.core.network.radio.LinkException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * In-memory stand-ins for hardware. They exist for two reasons: unit tests of [com.transfer.flash.core.network.radio.KissTncDriver]
 * and the radio session, and the diagnostic tool's "self-test, no radio attached" mode, so tomorrow's run starts from a known
 * green baseline. A simulation proves the host code is self-consistent; it proves nothing about a real radio (BT-00).
 */

/**
 * One end of an in-memory byte pipe. [maxReadChunk] splits what the peer wrote into small reads (a real serial port delivers
 * arbitrary fragments, and [com.transfer.flash.core.network.kiss.KissStreamDecoder] must cope).
 */
public class InMemoryLink internal constructor(
    override val description: String,
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
    private val maxReadChunk: Int,
) : ByteLink {
    private var leftover: ByteArray = ByteArray(0)
    private var closed = false

    /** Everything written to this end (for assertions). */
    public val written: MutableList<ByteArray> = mutableListOf()

    override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
        if (leftover.isEmpty()) {
            if (closed) return -1
            val got = withTimeoutOrNull(timeoutMs) { incoming.receiveCatching() } ?: return if (closed) -1 else 0
            val chunk = got.getOrNull() ?: return -1
            leftover = chunk
        }
        val n = minOf(buffer.size, leftover.size, maxReadChunk)
        leftover.copyInto(buffer, 0, 0, n)
        leftover = leftover.copyOfRange(n, leftover.size)
        return n
    }

    override suspend fun write(data: ByteArray) {
        if (closed) throw LinkException("link closed")
        written += data.copyOf()
        val r = outgoing.trySend(data.copyOf())
        if (r.isFailure) throw LinkException("peer closed")
    }

    override fun close() {
        if (closed) return
        closed = true
        outgoing.close()
        incoming.close()
    }

    /** Simulates the radio or cable going away: both directions end. */
    public fun breakLink(): Unit = close()
}

/** Creates two connected [InMemoryLink] ends: bytes written to one are read from the other. */
public fun inMemoryLinkPair(name: String = "mem", maxReadChunk: Int = Int.MAX_VALUE): Pair<InMemoryLink, InMemoryLink> {
    val aToB = Channel<ByteArray>(Channel.UNLIMITED)
    val bToA = Channel<ByteArray>(Channel.UNLIMITED)
    return InMemoryLink("$name/host", bToA, aToB, maxReadChunk) to InMemoryLink("$name/tnc", aToB, bToA, maxReadChunk)
}

/** A shared simulated radio channel: whatever one [FakeTnc] transmits, every other [FakeTnc] on it hears (maybe). */
public class FakeAirChannel(
    /** Probability in [0, 1) that a frame is lost on the way to one listener. */
    public val lossRate: Double = 0.0,
    /** Air time of a frame of the given AX.25 length in milliseconds (virtual time under `runTest`). */
    public val airtimeMs: (Int) -> Long = { 0L },
    private val random: Random = Random(1),
) {
    private val tncs = mutableListOf<FakeTnc>()

    internal fun join(tnc: FakeTnc) {
        tncs += tnc
    }

    /** Frames put on the air in total. */
    public var transmissions: Int = 0
        private set

    internal suspend fun transmit(from: FakeTnc, ax25: ByteArray) {
        transmissions++
        val t = airtimeMs(ax25.size)
        if (t > 0) delay(t)
        for (other in tncs) {
            if (other === from) continue
            if (lossRate > 0 && random.nextDouble() < lossRate) continue
            other.hear(ax25)
        }
    }
}

/**
 * A fake KISS TNC. Its [hostSide] is wired to the driver under test. It decodes the KISS bytes the host writes, records
 * parameter commands, puts data frames on [air], and writes frames heard from the air back to the host as KISS data frames.
 * [echo] makes it hear its own transmissions (a test of the host path with a single "radio").
 */
public class FakeTnc(
    private val hostSide: ByteLink,
    private val air: FakeAirChannel,
    private val scope: CoroutineScope,
    private val echo: Boolean = false,
    /** If > 0, writes to the host are split into pieces of this size with a tiny gap (fragmented reads). */
    private val writeFragment: Int = 0,
) {
    /** Parameter commands received from the host as (command, value). */
    public val parameters: MutableList<Pair<Int, Int>> = mutableListOf()

    /** Data frames the host asked this TNC to transmit. */
    public val transmitted: MutableList<ByteArray> = mutableListOf()

    private val decoder = KissStreamDecoder()

    init {
        air.join(this)
        scope.launch { pump() }
    }

    internal suspend fun hear(ax25: ByteArray) {
        toHost(KissFrameCodec.encodeData(ax25))
    }

    /** Writes raw bytes to the host as-is (to inject garbage). */
    public suspend fun injectRaw(bytes: ByteArray): Unit = toHost(bytes)

    private suspend fun toHost(bytes: ByteArray) {
        try {
            if (writeFragment <= 0) {
                hostSide.write(bytes)
            } else {
                var i = 0
                while (i < bytes.size) {
                    val n = minOf(writeFragment, bytes.size - i)
                    hostSide.write(bytes.copyOfRange(i, i + n))
                    i += n
                    delay(1)
                }
            }
        } catch (_: LinkException) {
        }
    }

    private suspend fun pump() {
        val buf = ByteArray(256)
        while (coroutineContext.isActive) {
            val n = try {
                hostSide.read(buf, 100)
            } catch (_: LinkException) {
                return
            }
            if (n < 0) return
            if (n == 0) continue
            for (f: KissFrame in decoder.feed(buf, 0, n)) {
                if (f.isData) {
                    transmitted += f.data
                    scope.launch {
                        air.transmit(this@FakeTnc, f.data)
                        if (echo) hear(f.data)
                    }
                } else if (f.data.isNotEmpty()) {
                    parameters += f.command to (f.data[0].toInt() and 0xFF)
                }
            }
        }
    }
}
