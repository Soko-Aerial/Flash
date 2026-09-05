package com.transfer.flash.core.network

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [FlashSession.sendText]'s default implementation, which Phase 10 changed from
 * `text.toByteArray(Charsets.UTF_8)` (JVM-only, CONVENTIONS.md R6) to
 * `text.encodeToByteArray()`.
 *
 * This suite lives in `commonTest` on purpose: it is the only thing in this module that
 * executes on BOTH the Android host-test JVM and the desktop `jvm()` target, which is what
 * CONVENTIONS.md R3.1 requires of a converted module. `jvmTest` running zero tests would
 * mean the desktop target is compiled but unproven.
 *
 * The two encoders differ for exactly one input class — unpaired surrogates, where the JVM
 * emits `0x3F` and Kotlin common emits U+FFFD — so the cases below are all well-formed.
 */
internal class FlashSessionSendTextTest {

    private class RecordingSession : FlashSession {
        val sent: MutableList<ByteArray> = mutableListOf()
        var disconnectedWith: String? = null

        private val state = MutableStateFlow(FlashConnectionState.Connected)

        override val peer: FlashDevice = FlashDevice(
            id = FlashDeviceId("peer-1"),
            friendlyName = "Peer",
            transportType = FlashTransportType.LAN,
        )
        override val connectionState: StateFlow<FlashConnectionState> = state.asStateFlow()
        override val transportType: FlashTransportType = FlashTransportType.LAN

        override suspend fun send(message: ByteArray): FlashResult<Unit> {
            sent += message
            return FlashResult.Success(Unit)
        }

        override fun disconnect(reason: String) {
            disconnectedWith = reason
            state.value = FlashConnectionState.Disconnected
        }
    }

    private fun expectedUtf8(text: String): ByteArray = text.encodeToByteArray()

    @Test
    fun sendText_delegates_to_send_exactly_once() = runTest {
        val session = RecordingSession()
        val result = session.sendText("hello")
        assertTrue(result.isSuccess, "sendText should surface send()'s result")
        assertEquals(1, session.sent.size, "sendText must call send() exactly once")
    }

    @Test
    fun sendText_encodes_ascii_as_utf8() = runTest {
        val session = RecordingSession()
        session.sendText("hello")
        assertContentEquals(byteArrayOf(104, 101, 108, 108, 111), session.sent.single())
    }

    @Test
    fun sendText_encodes_multibyte_latin_as_utf8() = runTest {
        val text = "héllo wörld"
        val session = RecordingSession()
        session.sendText(text)
        assertContentEquals(expectedUtf8(text), session.sent.single())
        // 11 characters, 13 bytes: é and ö are two bytes each.
        assertEquals(13, session.sent.single().size)
    }

    @Test
    fun sendText_encodes_cjk_as_three_byte_sequences() = runTest {
        val text = "转移"
        val session = RecordingSession()
        session.sendText(text)
        assertContentEquals(expectedUtf8(text), session.sent.single())
        assertEquals(6, session.sent.single().size)
    }

    @Test
    fun sendText_encodes_a_paired_surrogate_as_four_bytes() = runTest {
        // U+1F680 ROCKET — a correctly paired surrogate, the case that must NOT change.
        val text = "🚀"
        val session = RecordingSession()
        session.sendText(text)
        assertContentEquals(
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x9A.toByte(), 0x80.toByte()),
            session.sent.single(),
        )
    }

    @Test
    fun sendText_encodes_the_empty_string_as_zero_bytes() = runTest {
        val session = RecordingSession()
        session.sendText("")
        assertEquals(0, session.sent.single().size)
    }

    @Test
    fun disconnect_default_reason_is_unchanged_by_the_conversion() {
        val session = RecordingSession()
        session.disconnect()
        assertEquals("Normal disconnect", session.disconnectedWith)
        assertEquals(FlashConnectionState.Disconnected, session.connectionState.value)
    }

    @Test
    fun peerDeviceId_default_delegates_to_peer_id() {
        val session = RecordingSession()
        assertEquals(FlashDeviceId("peer-1"), session.peerDeviceId)
    }
}
