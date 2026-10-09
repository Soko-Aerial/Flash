package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-102 wire: `ss` / `sst` on the status frame, what an old build does with them, and what the status book keeps. */
@OptIn(FlashInternalApi::class)
class ScreenShareWireTest {

    private val callId = "0199c0de-1111-4222-8333-444455556666"
    private val from = "device-uuid-7777"

    @Test
    fun `a presenting status round-trips with its start counter`() {
        val frame = CallWireFrame.Status(callId = callId, from = from, micOn = true, cameraOn = true, sharing = true, shareStartedAt = 1_790_000_000_123L)
        val text = CallFrameCodec.encode(frame)
        assertTrue("ss=1" in text && "sst=1790000000123" in text, text)
        assertEquals(frame, CallFrameCodec.decode(text))
    }

    @Test
    fun `the end of a share is stated and carries no start counter`() {
        val text = CallFrameCodec.encode(CallWireFrame.Status(callId = callId, from = from, sharing = false, shareStartedAt = 99L))
        assertTrue("ss=0" in text, text)
        assertFalse("sst=" in text, text)
        val back = CallFrameCodec.decode(text) as CallWireFrame.Status
        assertEquals(false, back.sharing)
        assertNull(back.shareStartedAt)
    }

    @Test
    fun `a status that says nothing about sharing writes no share field`() {
        val text = CallFrameCodec.encode(CallWireFrame.Status(callId = callId, from = from, micOn = false))
        assertFalse("ss=" in text || "sst=" in text, text)
        val back = CallFrameCodec.decode(text) as CallWireFrame.Status
        assertNull(back.sharing)
        assertNull(back.shareStartedAt)
    }

    @Test
    fun `garbage in the share fields reads as not stated`() {
        val back = CallFrameCodec.decode("FLASH_CALL action=status callId=$callId from=$from ss=maybe sst=-5") as CallWireFrame.Status
        assertNull(back.sharing)
        assertNull(back.shareStartedAt)
        val letters = CallFrameCodec.decode("FLASH_CALL action=status callId=$callId from=$from ss=1 sst=abc") as CallWireFrame.Status
        assertEquals(true, letters.sharing)
        assertNull(letters.shareStartedAt)
    }

    @Test
    fun `an older build reads a presenting status as a camera that is on and ignores the rest`() {
        // What a build from before ADR-102 knows: the keys of ADR-067 only. The new keys are extra, never required.
        val text = CallFrameCodec.encode(CallWireFrame.Status(callId = callId, from = from, micOn = true, cameraOn = true, sharing = true, shareStartedAt = 5L))
        val known = setOf("action", "callId", "from", "mic", "cam", "hand", "rv", "react", "rseq", "vu")
        val keys = text.removePrefix("FLASH_CALL ").split(' ').map { it.substringBefore('=') }
        assertEquals(setOf("ss", "sst"), keys.filter { it !in known }.toSet())
        assertTrue("cam=1" in text, "the older build must still see a picture: $text")
        // A decoder that does not know ss/sst is a decoder that drops the keys; simulate it with the tail removed.
        val stripped = text.split(' ').filter { !it.startsWith("ss=") && !it.startsWith("sst=") }.joinToString(" ")
        val old = CallFrameCodec.decode(stripped) as CallWireFrame.Status
        assertEquals(true, old.cameraOn)
        assertNull(old.sharing)
    }

    // ---- status book

    private val book = CallStatusBook(clock = { 1_000_000L })

    private fun status(sharing: Boolean?, at: Long? = null, cam: Boolean? = null) =
        CallWireFrame.Status(callId, from, cameraOn = cam, sharing = sharing, shareStartedAt = at)

    @Test
    fun `the book keeps who presents and since when`() {
        assertFalse(book.peer("p").sharing)
        book.apply("p", status(true, 77L, cam = true))
        assertTrue(book.peer("p").sharing)
        assertEquals(77L, book.peer("p").shareStartedAt)
        assertTrue(book.peer("p").cameraOn)
    }

    @Test
    fun `a status that does not mention sharing leaves it as it was`() {
        book.apply("p", status(true, 77L))
        book.apply("p", status(null))
        assertTrue(book.peer("p").sharing)
        assertEquals(77L, book.peer("p").shareStartedAt)
    }

    @Test
    fun `ss 0 ends it and forgets the start`() {
        book.apply("p", status(true, 77L))
        book.apply("p", status(false))
        assertFalse(book.peer("p").sharing)
        assertEquals(0L, book.peer("p").shareStartedAt)
    }

    @Test
    fun `presenters do not leak into each other`() {
        book.apply("a", status(true, 1L))
        assertFalse(book.peer("b").sharing)
    }
}
