package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.core.ptt.PttPressOutcome
import com.transfer.flash.core.security.pairing.PairingPhase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Push-to-talk between two real [DesktopEngine]s over the real WS transport (ADR-058): what the wiring in
 * `DesktopEngine.assemble` and its two inbound routing points must do, with fake microphone and speaker.
 *
 * What this proves: the Start frame, the PTT1 audio frames and the Stop frame really travel `alpha -> beta`,
 * through the session collectors, are recognised before pairing/chat/transfer parsing, and drive beta's playout;
 * and that the release tears the listener down. What it cannot prove: sound (the devices are fakes) and a phone
 * on the other end. Those are `TEST-BACKLOG.md` PTTD-01 and PTTD-02.
 */
class DesktopEnginePttTest {

    private val tempDirs = mutableListOf<File>()
    private val engines = mutableListOf<DesktopEngine>()

    @AfterTest
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        engines.clear()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun engine(name: String, audio: FakePttAudio): DesktopEngine {
        val state = Files.createTempDirectory("flash-ptt-$name-state").toFile()
        val received = Files.createTempDirectory("flash-ptt-$name-received").toFile()
        tempDirs += state
        tempDirs += received
        return testDesktopEngine(receivedRoot = received, stateDir = state, pttAudio = audio).also { engines += it }
    }

    @Test
    fun `a talk session travels between two paired desktops and ends on release`() = runBlocking {
        val alphaAudio = FakePttAudio()
        val betaAudio = FakePttAudio()
        val alpha = engine("alpha", alphaAudio)
        val beta = engine("beta", betaAudio)
        val alphaId = alpha.localDeviceId
        val betaId = beta.localDeviceId

        alpha.start()
        beta.start()
        assertTrue(
            await(BOOT_TIMEOUT_MS) { alpha.ready.value && beta.ready.value },
            "engines did not boot: alpha=${alpha.startError.value} beta=${beta.startError.value}",
        )
        assertTrue(
            await(SESSION_TIMEOUT_MS) {
                alpha.network?.activeSessions?.value?.keys?.any { it.value == betaId } == true &&
                    beta.network?.activeSessions?.value?.keys?.any { it.value == alphaId } == true
            },
            "no session between the engines",
        )
        // PTT is for paired peers only (trust gate on both the sender's member list and the receiver's frames).
        alpha.pairing.beginPair(betaId, "beta") { }
        assertTrue(await(PAIR_TIMEOUT_MS) { beta.pairing.pairing.value?.phase == PairingPhase.RequestReceived }, "no pair request")
        beta.pairing.acceptLocal()
        assertTrue(
            await(PAIR_TIMEOUT_MS) { alpha.trust.isTrusted(FlashDeviceId(betaId)) && beta.trust.isTrusted(FlashDeviceId(alphaId)) },
            "pairing did not persist trust on both sides",
        )

        val alphaPtt = assertNotNull(alpha.ptt, "the PTT engine must exist once the engine is ready")
        val betaPtt = assertNotNull(beta.ptt)

        assertEquals(PttPressOutcome.ACCEPTED, alphaPtt.onPttButton())

        assertTrue(await(PTT_TIMEOUT_MS) { betaAudio.playouts.firstOrNull()?.started == true }, "beta never started playout")
        val listening = assertIs<PttFloorState.Listening>(betaPtt.state.value)
        assertEquals(alphaId, listening.holderId)
        val playout = betaAudio.playouts.single()
        assertEquals(16_000, playout.sampleRateHz)
        assertEquals(20, playout.packetMs)

        assertTrue(await(PTT_TIMEOUT_MS) { alphaAudio.captures.firstOrNull()?.packetsEnabled == true }, "alpha's packets never opened")
        val capture = alphaAudio.captures.single()
        val sound = ByteArray(640) { (it % 60 + 1).toByte() }
        repeat(FRAMES) { capture.emit(sound, captureTsMs = it * 20L) }

        assertTrue(
            await(PTT_TIMEOUT_MS) { playout.offered.size >= FRAMES },
            "only ${playout.offered.size} of $FRAMES audio frames reached beta's playout",
        )
        assertEquals((0L until FRAMES).toList(), playout.offered.map { it.seq }.sorted().take(FRAMES))
        assertContentEquals(sound, playout.offered.first().pcm)

        alphaPtt.stopLocal()

        assertTrue(await(PTT_TIMEOUT_MS) { betaPtt.state.value is PttFloorState.Idle }, "beta did not leave Listening after alpha's Stop")
        assertTrue(playout.stopped)
        assertTrue(capture.stopped)
    }

    @Test
    fun `an unpaired peer's press finds nobody to talk to`() = runBlocking {
        val alpha = engine("alpha", FakePttAudio())
        val beta = engine("beta", FakePttAudio())
        alpha.start()
        beta.start()
        assertTrue(await(BOOT_TIMEOUT_MS) { alpha.ready.value && beta.ready.value }, "engines did not boot")
        assertTrue(
            await(SESSION_TIMEOUT_MS) { alpha.network?.activeSessions?.value?.keys?.any { it.value == beta.localDeviceId } == true },
            "no session between the engines",
        )

        // Connected but not paired: not a PTT member, so nothing is captured and nothing is sent.
        assertEquals(PttPressOutcome.NO_PEERS, assertNotNull(alpha.ptt).onPttButton())
    }

    private suspend fun await(timeoutMs: Long, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(POLL_MS)
            true
        } == true

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
        const val SESSION_TIMEOUT_MS = 30_000L
        const val PAIR_TIMEOUT_MS = 15_000L
        const val PTT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
        const val FRAMES = 8
    }
}
