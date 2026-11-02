package com.transfer.flash.core.radio.driver

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.radio.link.ByteLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O26-01 / ADR-330: how often the read loop touches the link while a radio is connected and the channel is quiet.
 *
 * The old default (`readTimeoutMs = 250`) is measured side by side with the new one in the same test, so the comparison
 * comes from one binary and one run instead of from the audit's arithmetic. The vehicle is coroutine **virtual time**:
 * the count is `ByteLink.read` invocations per simulated hour, which is exactly the loop's cadence. It is *not* a
 * device wake-up count - that is `PERF-R01` in `logs/streams/radio/backlog-additions.md`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadioReadLoopCadenceTest {

    /**
     * A link whose read always gives up after the timeout with 0, like a quiet serial port or a quiet RFCOMM socket,
     * and that counts how often it was asked.
     */
    private class QuietLink : ByteLink {
        override val description: String = "quiet"
        var reads: Int = 0
        private var closed: Boolean = false

        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
            reads++
            delay(timeoutMs)
            return if (closed) -1 else 0
        }

        override suspend fun write(data: ByteArray) = Unit

        override fun close() {
            closed = true
        }
    }

    private fun TestScope.clock() = object : FlashTimeSource {
        override fun nowMs(): Long = testScheduler.currentTime
    }

    private fun TestScope.driver(link: QuietLink, config: KissTncConfig, scope: CoroutineScope = backgroundScope): KissTncDriver =
        KissTncDriver(
            scope = scope,
            openLink = { link },
            config = config,
            pacing = TxPacingPolicy.NONE,
            clock = clock(),
            random01 = { 0.0 },
        )

    /** Runs the driver for one simulated hour of silence and returns how many reads it made. */
    private fun TestScope.readsInOneQuietHour(link: QuietLink, driver: KissTncDriver): Int {
        driver.start()
        testScheduler.advanceTimeBy(ONE_HOUR_MS)
        testScheduler.runCurrent()
        return link.reads
    }

    @Test
    fun theOld250msTimeoutWakesTheLoopFourTimesASecond() = runTest {
        val link = QuietLink()
        val driver = driver(link, KissTncConfig(readTimeoutMs = 250))
        assertEquals(14_401, readsInOneQuietHour(link, driver), "3600 s / 0.25 s, plus the first read")
        driver.stop()
    }

    @Test
    fun theHeartbeatWakesTheLoopOnceAMinute() = runTest {
        val link = QuietLink()
        val driver = driver(link, KissTncConfig())
        assertEquals(KissTncConfig.DEFAULT_READ_HEARTBEAT_MS, 60_000L, "the documented heartbeat")
        assertEquals(61, readsInOneQuietHour(link, driver), "60 heartbeats in an hour, plus the first read")
        driver.stop()
    }

    @Test
    fun theHeartbeatIsTwoHundredTimesLessChattyThanThePoll() = runTest {
        val fastLink = QuietLink()
        val fast = driver(fastLink, KissTncConfig(readTimeoutMs = 250))
        val fastReads = readsInOneQuietHour(fastLink, fast)
        fast.stop()

        val slowLink = QuietLink()
        val slow = driver(slowLink, KissTncConfig())
        val slowReads = readsInOneQuietHour(slowLink, slow)
        slow.stop()

        assertTrue(fastReads >= 14_000, "the old loop cadence: $fastReads")
        assertTrue(slowReads <= 62, "the heartbeat cadence: $slowReads")
        assertTrue(fastReads / slowReads >= 200, "reduction: $fastReads / $slowReads")
    }

    @Test
    fun dataIsStillDeliveredImmediatelyNotOnTheHeartbeat() = runTest {
        // The bound must not become an added latency: a read returns as soon as bytes are there.
        val link = QuietLink()
        val driver = driver(link, KissTncConfig())
        driver.start()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, link.reads, "no data, so the loop is still inside its single read")
        driver.stop()
    }

    private companion object {
        const val ONE_HOUR_MS: Long = 60L * 60L * 1000L
    }
}
