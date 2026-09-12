package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PttSessionCodecTest {
    @Test
    fun startRoundTripsEscapedFields() {
        val frame = PttSessionFrame.Start(
            sessionId = "session-1",
            from = "device = 100%",
            senderName = "Field Phone = 100%",
            sentAt = 1757336400000L,
            sampleRateHz = 16000,
            packetMs = 20,
        )

        val encoded = PttSessionCodec.encode(frame)

        assertTrue(encoded.startsWith("FLASH_PTSS action=start "))
        assertEquals(frame, PttSessionCodec.decode(encoded))
    }

    @Test
    fun stopLeaveAndAckRoundTrip() {
        val stop = PttSessionFrame.Stop("s", "a", 7L)
        val leave = PttSessionFrame.Leave("s", "a", 7L)
        val ack = PttSessionFrame.HeartbeatAck("s", "a", 3L, 7L)
        assertEquals(stop, PttSessionCodec.decode(PttSessionCodec.encode(stop)))
        assertEquals(leave, PttSessionCodec.decode(PttSessionCodec.encode(leave)))
        assertEquals(ack, PttSessionCodec.decode(PttSessionCodec.encode(ack)))
    }

    @Test
    fun heartbeatRttIsOptional() {
        val without = PttSessionFrame.Heartbeat("s", "a", 9L, 7L, rttMs = null)
        assertEquals(without, PttSessionCodec.decode(PttSessionCodec.encode(without)))
        val with = PttSessionFrame.Heartbeat("s", "a", 9L, 7L, rttMs = 42L)
        assertEquals(with, PttSessionCodec.decode(PttSessionCodec.encode(with)))
    }

    @Test
    fun unknownActionIsIgnored() {
        assertNull(
            PttSessionCodec.decode("FLASH_PTSS action=future sessionId=s from=a sentAt=1"),
        )
    }

    @Test
    fun wrongPrefixIsIgnored() {
        assertNull(
            PttSessionCodec.decode("FLASH_PTT action=start sessionId=s from=a sentAt=1 rate=16000 pms=20"),
        )
    }

    @Test
    fun missingOrBlankFieldsAreRejected() {
        // Missing sessionId.
        assertNull(PttSessionCodec.decode("FLASH_PTSS action=stop from=a sentAt=1"))
        // Blank from.
        assertNull(PttSessionCodec.decode("FLASH_PTSS action=leave sessionId=s sentAt=1"))
        // Non-positive sentAt.
        assertNull(PttSessionCodec.decode("FLASH_PTSS action=stop sessionId=s from=a sentAt=0"))
    }

    @Test
    fun outOfRangeAudioParamsAreRejected() {
        // Unsupported capture rate.
        assertNull(
            PttSessionCodec.decode(
                "FLASH_PTSS action=start sessionId=s from=a sentAt=1 rate=44100 pms=20",
            ),
        )
        // Unsupported packet duration.
        assertNull(
            PttSessionCodec.decode(
                "FLASH_PTSS action=start sessionId=s from=a sentAt=1 rate=16000 pms=10",
            ),
        )
        // Negative heartbeat seq.
        assertNull(
            PttSessionCodec.decode("FLASH_PTSS action=hb sessionId=s from=a seq=-1 sentAt=1"),
        )
    }
}
