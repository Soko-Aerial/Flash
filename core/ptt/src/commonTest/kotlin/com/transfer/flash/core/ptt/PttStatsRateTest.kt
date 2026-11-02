package com.transfer.flash.core.ptt

import com.transfer.flash.core.ptt.floor.PttFloorState
import com.transfer.flash.core.ptt.protocol.PttSessionCodec
import com.transfer.flash.core.ptt.protocol.PttSessionFrame
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * OPT-O12-01: the playout loop fires its amplitude callback once per 20 ms packet — 50 times a second. Every one of
 * those used to build a `PttPlayoutSnapshot` and a `PttSessionStats` and publish a new value on `stats`, which woke
 * every collector (the overlay's level bar) 50 times a second.
 *
 * The claim under test is about *emissions*: drive 50 callbacks and count what the flow published. The clock is a
 * hand-cranked monotonic counter and the floor's own events run on a lane this test releases by hand, so the only
 * publisher left is the amplitude callback — no dependence on how fast this machine is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttStatsRateTest {

    @Volatile
    private var nowMs: Long = 1L

    private val audio = FakePttAudio()
    private val lane = ManualDispatcher()

    private fun newEngine(): PttSessionEngine = PttSessionEngine(
        localId = { LOCAL_ID },
        localName = { LOCAL_NAME },
        isTrustedPeer = { true },
        snapshotMembers = { listOf(PEER) },
        sendControl = { _, _ -> true },
        sendAudio = { _, _ -> },
        hasMicPermission = { true },
        isCallActive = { false },
        audioRateHz = { 16_000 },
        audio = audio,
        elapsedRealtimeMs = { nowMs },
        commandLane = lane,
    )

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (!condition()) delay(5L)
            }
        }
    }

    /** Runs the floor's queued events until [condition] holds (the session start needs the lane released). */
    private suspend fun drainUntil(what: String, condition: () -> Boolean) {
        awaitUntil(what) {
            lane.drain()
            condition()
        }
    }

    private fun TestScope.countStats(engine: PttSessionEngine): Recorder<Float> {
        val emissions = Recorder<Float>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.stats.collect { stats -> stats?.let { emissions.add(it.amplitude01) } }
        }
        return emissions
    }

    /** Opens a listen session and stops releasing floor events, so the 2 Hz tick loop cannot publish behind us. */
    private suspend fun listening(engine: PttSessionEngine): FakePlayout {
        assertTrue(
            engine.onInboundText(
                PEER,
                PttSessionCodec.encode(PttSessionFrame.Start(SESSION, PEER, "Ann", 1L, 16_000, 20)),
            ),
        )
        drainUntil("playout started") { audio.playouts.snapshot().any { it.started } }
        assertIs<PttFloorState.Listening>(engine.state.value)
        return audio.playouts.snapshot().single { it.started }
    }

    @Test
    fun `fifty playout callbacks publish at most ten stats snapshots`() = runTest {
        val engine = newEngine()
        try {
            val emissions = countStats(engine)
            val playout = listening(engine)
            val before = emissions.size

            // One second of listening: 50 packets, one 20 ms callback each, the clock advancing with them.
            repeat(50) {
                nowMs += 20L
                playout.tick(amplitude = 0.3f)
            }

            assertEquals(50, playout.amplitudeCallbacks, "the playout really did call back 50 times")
            val published = emissions.size - before
            assertTrue(published in 1..10, "50 callbacks must not publish 50 snapshots (published $published)")
            assertEquals(0.3f, emissions.snapshot().last(), "the level bar still sees the latest amplitude")
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `the first callback publishes immediately`() = runTest {
        val engine = newEngine()
        try {
            val emissions = countStats(engine)
            val playout = listening(engine)

            nowMs += 20L
            playout.tick(amplitude = 0.5f)
            awaitUntil("the first level published") { emissions.snapshot().lastOrNull() == 0.5f }
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `a callback inside the publishing gap is dropped and one after it publishes`() = runTest {
        val engine = newEngine()
        try {
            val emissions = countStats(engine)
            val playout = listening(engine)

            nowMs += 20L
            playout.tick(amplitude = 0.1f)
            awaitUntil("first published") { emissions.snapshot().lastOrNull() == 0.1f }
            val afterFirst = emissions.size

            // Five packets = 100 ms is the gap; 80 ms of callbacks must not each publish.
            repeat(4) {
                nowMs += 20L
                playout.tick(amplitude = 0.2f)
            }
            assertEquals(afterFirst, emissions.size, "80 ms of callbacks stay inside the publishing gap")

            nowMs += 20L
            playout.tick(amplitude = 0.3f)
            awaitUntil("the next window published") { emissions.snapshot().lastOrNull() == 0.3f }
        } finally {
            engine.shutdown()
        }
    }

    private companion object {
        const val PEER = "peer-1"
        const val LOCAL_ID = "me"
        const val LOCAL_NAME = "Me"
        const val SESSION = "session-1"
    }
}
