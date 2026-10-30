package com.transfer.flash.desktop.radio

import com.transfer.flash.core.radio.audio.RadioPcmInput
import com.transfer.flash.core.radio.audio.RadioPcmOutput
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.LinkException
import com.transfer.flash.ui.radio.RadioVoicePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop host of radio voice (ADR-225): what the card shows when the audio channel opens, fails, or the mic is blocked. */
class DesktopRadioVoiceTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class QuietLink : ByteLink {
        @Volatile var closed = false
        override val description: String = "quiet"
        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
            delay(20)
            return if (closed) -1 else 0
        }
        override suspend fun write(data: ByteArray) {}
        override fun close() { closed = true }
    }

    private class NoOutput : RadioPcmOutput {
        override fun start(sampleRate: Int) {}
        override fun write(pcm: ShortArray, length: Int) {}
        override fun stop() {}
    }

    private class NoInput : RadioPcmInput {
        override fun start(): Int = 32_000
        override suspend fun read(buffer: ShortArray): Int { delay(10); return 0 }
        override fun stop() {}
    }

    private fun voice(
        openLink: suspend () -> ByteLink?,
        microphoneOpens: () -> Boolean = { true },
    ) = DesktopRadioVoice(
        scope = scope,
        openLink = openLink,
        newOutput = { NoOutput() },
        newInput = { NoInput() },
        microphoneOpens = microphoneOpens,
    )

    private suspend fun DesktopRadioVoice.waitFor(phase: RadioVoicePhase) = withTimeout(3_000) {
        while (state.value.phase != phase) delay(10)
    }

    @Test
    fun radvU70_anOpenedChannelShowsListeningAndSwitchOffClosesTheLink() = runBlocking {
        val link = QuietLink()
        val v = voice({ link })

        v.setListening(true)
        v.waitFor(RadioVoicePhase.LISTENING)
        assertTrue(v.state.value.available)

        v.setListening(false)
        assertEquals(RadioVoicePhase.OFF, v.state.value.phase)
        assertTrue(link.closed, "the link must be closed")
    }

    @Test
    fun radvU71_aRefusedChannelShowsTheReasonAsFailed() = runBlocking {
        val v = voice({ throw LinkException("The radio refused the audio channel.") })

        v.setListening(true)
        v.waitFor(RadioVoicePhase.FAILED)

        assertEquals("The radio refused the audio channel.", v.state.value.message)
    }

    @Test
    fun radvU72_noRememberedRadioSaysChooseOneFirst() = runBlocking {
        val v = voice({ null })

        v.setListening(true)
        v.waitFor(RadioVoicePhase.FAILED)

        assertEquals("Choose a radio first.", v.state.value.message)
    }

    @Test
    fun radvU73_aBlockedMicrophoneIsExplainedAndTheSessionStaysUp() = runBlocking {
        val v = voice({ QuietLink() }, microphoneOpens = { false })
        v.setListening(true)
        v.waitFor(RadioVoicePhase.LISTENING)

        v.pushToTalk(true)

        assertEquals(RadioVoicePhase.LISTENING, v.state.value.phase)
        assertTrue(v.state.value.message.orEmpty().contains("Privacy"))
        v.close()
    }

    @Test
    fun radvU74_holdToTalkTransmitsAndReleaseReturnsToListening() = runBlocking {
        val v = voice({ QuietLink() })
        v.setListening(true)
        v.waitFor(RadioVoicePhase.LISTENING)

        v.pushToTalk(true)
        v.waitFor(RadioVoicePhase.TRANSMITTING)
        v.pushToTalk(false)
        v.waitFor(RadioVoicePhase.LISTENING)
        v.close()
    }
}
