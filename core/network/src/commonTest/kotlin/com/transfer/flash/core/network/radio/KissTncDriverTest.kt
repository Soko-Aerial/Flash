package com.transfer.flash.core.network.radio

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.network.kiss.Ax25Address
import com.transfer.flash.core.network.kiss.Ax25FrameCodec
import com.transfer.flash.core.network.kiss.Kiss
import com.transfer.flash.core.network.radio.sim.FakeAirChannel
import com.transfer.flash.core.network.radio.sim.FakeTnc
import com.transfer.flash.core.network.radio.sim.InMemoryLink
import com.transfer.flash.core.network.radio.sim.inMemoryLinkPair
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KissTncDriverTest {

    private fun TestScope.clock() = object : FlashTimeSource {
        override fun nowMs(): Long = testScheduler.currentTime
    }

    private fun ui(info: ByteArray, from: String = "AAAAAA"): ByteArray =
        Ax25FrameCodec.encodeUi(Ax25Address("FLASH"), Ax25Address(from), info)

    private class Rig(
        val driver: KissTncDriver,
        val tnc: FakeTnc,
        val air: FakeAirChannel,
        val links: MutableList<InMemoryLink>,
        val evidence: RadioEvidenceLog,
    )

    private fun TestScope.rig(
        config: KissTncConfig = KissTncConfig(),
        pacing: TxPacingPolicy = TxPacingPolicy.NONE,
        readChunk: Int = Int.MAX_VALUE,
        echo: Boolean = false,
        air: FakeAirChannel = FakeAirChannel(),
        scope: CoroutineScope = backgroundScope,
    ): Rig {
        val links = mutableListOf<InMemoryLink>()
        val evidence = RadioEvidenceLog()
        var tncRef: FakeTnc? = null
        val first = inMemoryLinkPair("t", readChunk)
        links += first.first
        tncRef = FakeTnc(first.second, air, scope, echo = echo)
        val driver = KissTncDriver(
            scope = scope,
            openLink = { links.last() },
            config = config,
            pacing = pacing,
            clock = clock(),
            random01 = { 0.0 },
            evidence = evidence,
        )
        return Rig(driver, tncRef, air, links, evidence)
    }

    private suspend fun KissTncDriver.awaitConnected() {
        withTimeout(10_000) { state.first { it is TncLinkState.Connected } }
    }

    @Test
    fun sendsAKissWrappedAx25FrameToTheLink() = runTest {
        val r = rig()
        r.driver.start()
        r.driver.awaitConnected()
        val frame = ui("hello".encodeToByteArray())
        val res = r.driver.sendAx25(frame)
        assertIs<TxResult.Sent>(res)
        delay(10)
        assertEquals(1, r.tnc.transmitted.size)
        assertContentEquals(frame, r.tnc.transmitted[0])
        assertEquals(1, r.driver.counters.txFrames)
        assertTrue(r.evidence.snapshot().any { it.contains(" TX ") && it.contains("C0 00 ") })
    }

    @Test
    fun receivesFramesEvenWhenTheLinkFragmentsThemToSingleBytes() = runTest {
        val air = FakeAirChannel()
        val r = rig(readChunk = 1, air = air)
        val other = inMemoryLinkPair("other")
        val otherTnc = FakeTnc(other.second, air, backgroundScope)
        r.driver.start()
        r.driver.awaitConnected()
        val frame = ui(ByteArray(120) { (it * 3).toByte() }, from = "BBBBBB")
        val rx = async { withTimeout(5_000) { r.driver.received.first() } }
        delay(1)
        other.first.write(com.transfer.flash.core.network.kiss.KissFrameCodec.encodeData(frame))
        val got = rx.await()
        assertNotNull(got.ax25)
        assertEquals("BBBBBB", got.ax25!!.source.toString())
        assertContentEquals(frame, got.kiss.data)
        assertEquals(1, r.driver.counters.rxFrames)
        assertNotNull(otherTnc)
    }

    @Test
    fun garbageFromTheTncIsCountedNotFatal() = runTest {
        val r = rig()
        r.driver.start()
        r.driver.awaitConnected()
        r.tnc.injectRaw(byteArrayOf(1, 2, 3, 0xC0.toByte(), 0x00, 0xDB.toByte(), 0x41, 0xC0.toByte()))
        val frame = ui("ok".encodeToByteArray())
        val rx = async { withTimeout(5_000) { r.driver.received.first() } }
        delay(1)
        r.tnc.injectRaw(com.transfer.flash.core.network.kiss.KissFrameCodec.encodeData(frame))
        assertContentEquals(frame, rx.await().kiss.data)
        assertEquals(3, r.driver.decoderStats.garbageBytes)
        assertEquals(1, r.driver.decoderStats.badEscapes)
        assertTrue(r.driver.state.value is TncLinkState.Connected)
    }

    @Test
    fun nonAx25DataFrameIsDeliveredWithAnError() = runTest {
        val r = rig()
        r.driver.start()
        r.driver.awaitConnected()
        val rx = async { withTimeout(5_000) { r.driver.received.first() } }
        delay(1)
        r.tnc.injectRaw(com.transfer.flash.core.network.kiss.KissFrameCodec.encodeData(byteArrayOf(1, 2, 3)))
        val got = rx.await()
        assertEquals(null, got.ax25)
        assertNotNull(got.decodeError)
        assertEquals(1, r.driver.counters.rxNonAx25)
    }

    @Test
    fun parametersAreSentOnConnect() = runTest {
        val r = rig(config = KissTncConfig(txDelayMs = 300, persistence = 63, slotTimeMs = 100, txTailMs = 20, fullDuplex = false))
        r.driver.start()
        r.driver.awaitConnected()
        delay(50)
        assertEquals(
            listOf(Kiss.CMD_TXDELAY to 30, Kiss.CMD_PERSISTENCE to 63, Kiss.CMD_SLOT_TIME to 10, Kiss.CMD_TX_TAIL to 2, Kiss.CMD_FULL_DUPLEX to 0),
            r.tnc.parameters,
        )
    }

    @Test
    fun noParametersAreSentUnlessConfigured() = runTest {
        val r = rig()
        r.driver.start()
        r.driver.awaitConnected()
        delay(50)
        assertTrue(r.tnc.parameters.isEmpty())
    }

    @Test
    fun pacingSpacesTransmissionsByAirtimeGapAndJitter() = runTest {
        val policy = TxPacingPolicy(airBaud = 1200, txDelayMs = 300, txTailMs = 0, minGapMs = 500, jitterMs = 1000)
        val r = rig(pacing = policy)
        r.driver.start()
        r.driver.awaitConnected()
        val f = ui(ByteArray(100))
        val a = r.driver.sendAx25(f) as TxResult.Sent
        val b = r.driver.sendAx25(f) as TxResult.Sent
        val air = policy.airtimeMs(f.size)
        assertTrue(b.atMs - a.atMs >= air + 500, "gap=${b.atMs - a.atMs} need>=${air + 500}")
        assertTrue(b.atMs - a.atMs < air + 500 + 1000 + 5, "jitter 0.0 here, so only gap+airtime")
        assertTrue(air in 1000..2000, "100 B at 1200 baud with 300 ms keyup is about one to two seconds, was $air")
    }

    @Test
    fun aFullQueueRefusesInsteadOfGrowing() = runTest {
        val policy = TxPacingPolicy(minGapMs = 5_000, jitterMs = 0, maxQueue = 2)
        val r = rig(pacing = policy)
        r.driver.start()
        r.driver.awaitConnected()
        val f = ui(ByteArray(10))
        val results = (1..6).map { async { r.driver.sendAx25(f) } }
        val done = results.map { it.await() }
        assertTrue(done.any { it is TxResult.QueueFull }, "results=$done")
        assertTrue(done.any { it is TxResult.Sent })
    }

    @Test
    fun reconnectsAfterTheLinkDiesAndFailsSendsWhileDown() = runTest {
        val air = FakeAirChannel()
        val links = mutableListOf<InMemoryLink>()
        val tncs = mutableListOf<FakeTnc>()
        val pair1 = inMemoryLinkPair("one")
        links += pair1.first
        tncs += FakeTnc(pair1.second, air, backgroundScope)
        var opens = 0
        val driver = KissTncDriver(
            scope = backgroundScope,
            openLink = {
                opens++
                if (opens == 2) throw LinkException("radio not ready")
                if (opens >= 3 && links.size < 2) {
                    val p = inMemoryLinkPair("two")
                    links += p.first
                    tncs += FakeTnc(p.second, air, backgroundScope)
                }
                links.last()
            },
            config = KissTncConfig(reconnectBaseMs = 1_000, reconnectCapMs = 4_000),
            pacing = TxPacingPolicy.NONE,
            clock = clock(),
            random01 = { 0.0 },
        )
        driver.start()
        driver.awaitConnected()
        assertIs<TxResult.Sent>(driver.sendAx25(ui(ByteArray(5))))

        links[0].breakLink() // the cable is pulled
        withTimeout(10_000) { driver.state.first { it is TncLinkState.Waiting } }
        assertEquals(TxResult.LinkDown, driver.sendAx25(ui(ByteArray(5))))

        // attempt 2 fails to open, attempt 3 succeeds
        withTimeout(60_000) { driver.state.first { it is TncLinkState.Connected && opens >= 3 } }
        assertEquals(2, links.size)
        assertIs<TxResult.Sent>(driver.sendAx25(ui(ByteArray(5))))
        delay(10)
        assertEquals(1, tncs[1].transmitted.size)
        assertTrue(driver.counters.reconnects >= 2)
    }

    @Test
    fun halfAFrameFromTheOldLinkDoesNotLeakIntoTheNewOne() = runTest {
        val air = FakeAirChannel()
        val pairs = mutableListOf(inMemoryLinkPair("a"), inMemoryLinkPair("b"))
        val tncs = pairs.map { FakeTnc(it.second, air, backgroundScope) }
        var idx = 0
        val driver = KissTncDriver(
            scope = backgroundScope,
            openLink = { pairs[minOf(idx++, 1)].first },
            config = KissTncConfig(reconnectBaseMs = 1_000),
            pacing = TxPacingPolicy.NONE,
            clock = clock(),
            random01 = { 0.0 },
        )
        driver.start()
        driver.awaitConnected()
        tncs[0].injectRaw(byteArrayOf(0xC0.toByte(), 0x00, 0x41, 0x42)) // unfinished frame
        delay(5)
        pairs[0].first.breakLink()
        withTimeout(60_000) { driver.state.first { it is TncLinkState.Waiting } }
        withTimeout(60_000) { driver.state.first { it is TncLinkState.Connected } }
        val rx = async { withTimeout(5_000) { driver.received.first() } }
        delay(1)
        val good = ui("good".encodeToByteArray())
        tncs[1].injectRaw(byteArrayOf(0x43, 0xC0.toByte()) + com.transfer.flash.core.network.kiss.KissFrameCodec.encodeData(good))
        assertContentEquals(good, rx.await().kiss.data)
    }

    @Test
    fun stopFailsPendingSendsAndEndsInStopped() = runTest {
        val r = rig(pacing = TxPacingPolicy(minGapMs = 60_000, jitterMs = 0))
        r.driver.start()
        r.driver.awaitConnected()
        val f = ui(ByteArray(10))
        val first = async { r.driver.sendAx25(f) }
        val second = async { r.driver.sendAx25(f) }
        delay(10)
        r.driver.stop()
        assertEquals(TncLinkState.Stopped, r.driver.state.value)
        assertIs<TxResult.Sent>(first.await())
        assertEquals(TxResult.LinkDown, second.await())
    }

    @Test
    fun echoRadioReturnsOwnFrame() = runTest {
        val r = rig(echo = true)
        r.driver.start()
        r.driver.awaitConnected()
        val f = ui("echo".encodeToByteArray())
        val rx = async { withTimeout(5_000) { r.driver.received.first() } }
        delay(1)
        r.driver.sendAx25(f)
        assertContentEquals(f, rx.await().kiss.data)
    }

    // ---- Review R2 / R3 / R4 (2026-10-09): backoff reset, uninterruptible write, first-cause reason.

    /** Opens, then drops at once (EOF on the first read): a radio that accepts the open and hangs up. */
    private class DropLink : ByteLink {
        override val description: String = "drop"
        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int = -1
        override suspend fun write(data: ByteArray) {}
        override fun close() {}
    }

    /** The first [count] `Waiting` delays the driver announces. */
    private suspend fun TestScope.collectWaiting(driver: KissTncDriver, count: Int): List<Long> {
        val seen = mutableListOf<Long>()
        val done = CompletableDeferred<Unit>()
        val j = backgroundScope.launch {
            driver.state.collect { st ->
                if (st is TncLinkState.Waiting) {
                    seen += st.retryInMs
                    if (seen.size >= count) done.complete(Unit)
                }
            }
        }
        withTimeout(600_000) { done.await() }
        j.cancel()
        return seen
    }

    @Test
    fun aRadioThatConnectsAndDropsAtOnceIsRetriedWithGrowingBackoff() = runTest { // R2
        val driver = KissTncDriver(
            scope = backgroundScope,
            openLink = { DropLink() },
            config = KissTncConfig(reconnectBaseMs = 1_000, reconnectCapMs = 16_000, stableAfterMs = 10_000),
            pacing = TxPacingPolicy.NONE,
            clock = clock(),
            random01 = { 1.0 }, // the top of the jitter range: delay = the bound for the attempt
        )
        driver.start()
        val delays = collectWaiting(driver, 4)
        assertTrue(delays[1] > delays[0] && delays[2] > delays[1], "the backoff must grow while the link never proves stable: $delays")
        assertTrue(delays[0] >= 1_000)
    }

    @Test
    fun aLinkThatStayedUpLongEnoughStartsTheBackoffOver() = runTest { // R2
        var opens = 0
        val links = mutableListOf<InMemoryLink>()
        val air = FakeAirChannel()
        val driver = KissTncDriver(
            scope = backgroundScope,
            openLink = {
                opens++
                if (opens <= 2) {
                    DropLink()
                } else {
                    val p = inMemoryLinkPair("stable")
                    links += p.first
                    FakeTnc(p.second, air, backgroundScope)
                    p.first
                }
            },
            config = KissTncConfig(reconnectBaseMs = 1_000, reconnectCapMs = 16_000, stableAfterMs = 10_000),
            pacing = TxPacingPolicy.NONE,
            clock = clock(),
            random01 = { 1.0 },
        )
        driver.start()
        withTimeout(60_000) { driver.state.first { it is TncLinkState.Connected && opens >= 3 } } // the third open
        delay(10_500) // longer than stableAfterMs
        links.single().breakLink()
        val first = withTimeout(10_000) { driver.state.first { it is TncLinkState.Waiting } } as TncLinkState.Waiting
        assertEquals(1_000L, first.retryInMs, "a stable link must reset the backoff to the base delay")
    }

    @Test
    fun aLinkThatDeliveredAFrameStartsTheBackoffOver() = runTest { // R2
        var opens = 0
        val air = FakeAirChannel()
        lateinit var tnc: FakeTnc
        lateinit var link: InMemoryLink
        val driver = KissTncDriver(
            scope = backgroundScope,
            openLink = {
                opens++
                if (opens <= 2) {
                    DropLink()
                } else {
                    val p = inMemoryLinkPair("talker")
                    link = p.first
                    tnc = FakeTnc(p.second, air, backgroundScope)
                    p.first
                }
            },
            config = KissTncConfig(reconnectBaseMs = 1_000, reconnectCapMs = 16_000, stableAfterMs = 600_000),
            pacing = TxPacingPolicy.NONE,
            clock = clock(),
            random01 = { 1.0 },
        )
        driver.start()
        withTimeout(60_000) { driver.state.first { it is TncLinkState.Connected && opens >= 3 } }
        tnc.injectRaw(com.transfer.flash.core.network.kiss.KissFrameCodec.encodeData(ui("hi".encodeToByteArray())))
        delay(50)
        link.breakLink()
        val w = withTimeout(10_000) { driver.state.first { it is TncLinkState.Waiting } } as TncLinkState.Waiting
        assertEquals(1_000L, w.retryInMs, "one KISS frame proves the link, even if it was short-lived")
    }

    /** A write that ignores cancellation, like a blocking Bluetooth socket: only close() lets it return. */
    private class StuckWriteLink : ByteLink {
        override val description: String = "stuck"
        val gate = CompletableDeferred<Unit>()
        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
            if (gate.isCompleted) return -1
            delay(timeoutMs)
            return if (gate.isCompleted) -1 else 0
        }

        override suspend fun write(data: ByteArray) {
            withContext(NonCancellable) { gate.await() }
            throw LinkException("closed while writing")
        }

        override fun close() {
            gate.complete(Unit)
        }
    }

    @Test
    fun stoppingWhileAWriteIsStuckClosesTheLinkInsteadOfHanging() = runTest { // R3
        val link = StuckWriteLink()
        val driver = KissTncDriver(
            scope = backgroundScope,
            openLink = { link },
            config = KissTncConfig(),
            pacing = TxPacingPolicy.NONE,
            clock = clock(),
            random01 = { 0.0 },
        )
        driver.start()
        driver.awaitConnected()
        val send = async { driver.sendAx25(ui(ByteArray(5))) }
        delay(100) // the writer is now inside write(), uninterruptible
        withTimeout(10_000) { driver.stop() }
        assertTrue(link.gate.isCompleted, "stop() must close the link so a blocked write is released")
        assertEquals(TxResult.LinkDown, send.await())
        assertEquals(TncLinkState.Stopped, driver.state.value)
    }

    /** Write fails at once; read keeps waiting until the link is closed, then reports end of stream. */
    private class WriteFailsLink : ByteLink {
        override val description: String = "write-fails"
        private val closedGate = CompletableDeferred<Unit>()
        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
            if (closedGate.isCompleted) return -1
            withTimeoutOrNull(timeoutMs) { closedGate.await() }
            return if (closedGate.isCompleted) -1 else 0
        }

        override suspend fun write(data: ByteArray) {
            throw LinkException("boom")
        }

        override fun close() {
            closedGate.complete(Unit)
        }
    }

    @Test
    fun theFirstCauseOfALinkFailureIsTheReasonReported() = runTest { // R4
        val driver = KissTncDriver(
            scope = backgroundScope,
            openLink = { WriteFailsLink() },
            config = KissTncConfig(),
            pacing = TxPacingPolicy.NONE,
            clock = clock(),
            random01 = { 0.0 },
        )
        driver.start()
        driver.awaitConnected()
        driver.sendAx25(ui(ByteArray(5)))
        val w = withTimeout(10_000) { driver.state.first { it is TncLinkState.Waiting } } as TncLinkState.Waiting
        assertTrue(w.reason.startsWith("write_error:boom"), "the later EOF caused by closing must not hide the write error: ${w.reason}")
    }
}
