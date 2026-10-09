package com.transfer.flash.core.network.radio

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.network.kiss.Ax25FrameCodec
import com.transfer.flash.core.network.radio.diag.RadioLinkTester
import com.transfer.flash.core.network.radio.diag.TestRole
import com.transfer.flash.core.network.radio.sim.FakeAirChannel
import com.transfer.flash.core.network.radio.sim.FakeTnc
import com.transfer.flash.core.network.radio.sim.inMemoryLinkPair
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadioLinkTesterTest {

    private fun TestScope.clock() = object : FlashTimeSource {
        override fun nowMs(): Long = 1_800_000_000_000L + testScheduler.currentTime
    }

    private class Station(val driver: KissTncDriver, val tester: RadioLinkTester, val tnc: FakeTnc, val log: RadioEvidenceLog, val host: com.transfer.flash.core.network.radio.sim.InMemoryLink)

    private suspend fun TestScope.station(role: TestRole, air: FakeAirChannel, pacing: TxPacingPolicy): Station {
        val (host, radioSide) = inMemoryLinkPair(role.name)
        val tnc = FakeTnc(radioSide, air, backgroundScope)
        val log = RadioEvidenceLog()
        val driver = KissTncDriver(
            scope = backgroundScope,
            openLink = { host },
            config = KissTncConfig(),
            pacing = pacing,
            clock = clock(),
            random01 = { 0.0 },
            evidence = log,
        )
        val tester = RadioLinkTester(backgroundScope, driver, log, clock(), JdkRadioCrypto(), role)
        driver.start()
        withTimeout(10_000) { driver.state.first { it is TncLinkState.Connected } }
        tester.start()
        return Station(driver, tester, tnc, log, host)
    }

    @Test
    fun theTransparencyFrameCarriesEveryByteValueIncludingFendAndFesc() = runTest {
        val air = FakeAirChannel()
        val a = station(TestRole.STATION_A, air, TxPacingPolicy.NONE)
        val res = a.tester.sendKissTransparencyTest(220)
        assertTrue(res is TxResult.Sent)
        kotlinx.coroutines.delay(10)
        val sent = a.tnc.transmitted.single()
        val decoded = Ax25FrameCodec.decode(sent)
        assertTrue(decoded is com.transfer.flash.core.network.kiss.Ax25DecodeResult.Ok)
        val info = decoded.frame.info
        assertEquals(220, info.size)
        assertContentEquals(ByteArray(220) { it.toByte() }, info)
        // The wire form must have escaped the 0xC0 and 0xDB inside the info field.
        val wire = a.host.written.flatMap { it.toList() }.map { it.toInt() and 0xFF }
        val body = wire.drop(1).dropLast(1) // between the outer FENDs
        assertTrue(body.none { it == 0xC0 }, "a raw FEND leaked into the frame body")
        assertTrue(wire.zipWithNext().any { it == (0xDB to 0xDC) }, "FEND must be sent as FESC TFEND")
        assertTrue(wire.zipWithNext().any { it == (0xDB to 0xDD) }, "FESC must be sent as FESC TFESC")
    }

    @Test
    fun aFlashFramePayloadUsesTheWholeInfoBudget() = runTest {
        val air = FakeAirChannel()
        val a = station(TestRole.STATION_A, air, TxPacingPolicy.NONE)
        a.tester.sendFlashFramePayload()
        kotlinx.coroutines.delay(10)
        val sent = Ax25FrameCodec.decode(a.tnc.transmitted.single())
        assertTrue(sent is com.transfer.flash.core.network.kiss.Ax25DecodeResult.Ok)
        assertEquals(RadioWire.DEFAULT_INFO_BUDGET, sent.frame.info.size)
        assertEquals(0xF1, sent.frame.info[0].toInt() and 0xFF)
        assertEquals(0xF0, sent.frame.pid)
    }

    @Test
    fun twoStationsMeasureRoundTripsAndGoodputOverTheFakeAir() = runTest {
        val air = FakeAirChannel(airtimeMs = { len -> (len + 4) * 8L * 1000 / 1200 })
        val pacing = TxPacingPolicy(minGapMs = 200, jitterMs = 0, txDelayMs = 300, txTailMs = 20)
        val a = station(TestRole.STATION_A, air, pacing)
        val b = station(TestRole.STATION_B, air, pacing)
        b.tester.responder = true
        val result = a.tester.runBurst(count = 5, bodyBytes = 192, ackWaitMs = 60_000)
        assertEquals(5, result.accepted)
        assertEquals(5, result.acked, result.summary())
        assertEquals(0, result.lost)
        assertTrue(result.goodputBytesPerSec > 0)
        // 192 B at 1200 baud can never beat the air rate (150 B/s) once the keyup and gaps are added.
        assertTrue(result.goodputBytesPerSec < 150, result.summary())
        assertTrue(result.medianRttMs!! > 0)
        assertTrue(a.log.snapshot().any { it.contains("burst_start") })
    }

    @Test
    fun withoutAResponderTheBurstReportsLossInsteadOfHanging() = runTest {
        val air = FakeAirChannel()
        val a = station(TestRole.STATION_A, air, TxPacingPolicy.NONE)
        val result = a.tester.runBurst(count = 3, bodyBytes = 50, ackWaitMs = 2_000)
        assertEquals(3, result.accepted)
        assertEquals(0, result.acked)
        assertEquals(3, result.lost)
        assertEquals(null, result.medianRttMs)
    }

    @Test
    fun theFeedDescribesReceivedFramesForTheScreen() = runTest {
        val air = FakeAirChannel()
        val a = station(TestRole.STATION_A, air, TxPacingPolicy.NONE)
        val b = station(TestRole.STATION_B, air, TxPacingPolicy.NONE)
        a.tester.sendAx25TextTest()
        kotlinx.coroutines.delay(50)
        assertTrue(b.tester.feed.value.any { it.contains("RX kiss") && it.contains("FLASH BT-00 AX25 UI TEST") }, b.tester.feed.value.toString())
    }

    @Test
    fun concurrentBurstsAndTheReceiveSideShareOneSessionWithoutLosingAcks() { // R1, R8 (a finishing burst used to clear the other bursts' pending ACKs)
        // Real threads: the receive collector (ingest, ACKs) and four callers (encode, pending-ACK table) all touch the one
        // non-thread-safe RadioSession. Every accepted frame must be ACKed exactly once.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val air = FakeAirChannel()
            val clock = object : FlashTimeSource {
                override fun nowMs(): Long = System.currentTimeMillis()
            }
            // NONE still charges the airtime of the frame at 1200 baud (about half a second each); the test is about locking, not pacing.
            val instant = TxPacingPolicy(airBaud = 100_000_000, txDelayMs = 0, txTailMs = 0, minGapMs = 0, jitterMs = 0, maxQueue = 64)
            fun side(role: TestRole): RadioLinkTester {
                val (host, radioSide) = inMemoryLinkPair(role.name)
                FakeTnc(radioSide, air, scope)
                val log = RadioEvidenceLog()
                val driver = KissTncDriver(scope, { host }, KissTncConfig(), instant, clock, { 0.0 }, log)
                driver.start()
                return RadioLinkTester(scope, driver, log, clock, JdkRadioCrypto(), role).also { it.start() }
            }
            val a = side(TestRole.STATION_A)
            val b = side(TestRole.STATION_B)
            b.responder = true
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.delay(300) // let both drivers connect
                val results = withTimeout(60_000) {
                    (1..4).map { async(kotlinx.coroutines.Dispatchers.Default) { a.runBurst(count = 25, bodyBytes = 40, ackWaitMs = 30_000) } }
                        .map { it.await() }
                }
                assertEquals(100, results.sumOf { it.accepted }, results.joinToString { it.summary() })
                assertEquals(100, results.sumOf { it.acked }, results.joinToString { it.summary() })
            }
        } finally {
            scope.cancel()
        }
    }
}
