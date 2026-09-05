@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.transfer.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsTransferMessagesTest {

    @Test
    fun `hello round trips with spaces and equals in name`() {
        val text = WsTransferMessages.hello("device-1234", "Kali Phone = Pro")

        val parsed = WsTransferMessages.parseHello(text)

        assertEquals(WsTransferMessages.PROTOCOL_VERSION, parsed?.protocolVersion)
        assertEquals("device-1234", parsed?.deviceId)
        assertEquals("Kali Phone = Pro", parsed?.friendlyName)
    }

    @Test
    fun `fileStart round trips with percent and space in file name`() {
        val text = WsTransferMessages.fileStart("ab12cd34", "my 100% file (final).zip", 987_654_321L)

        val parsed = WsTransferMessages.parseFileStart(text)

        assertEquals("ab12cd34", parsed?.transferId)
        assertEquals("my 100% file (final).zip", parsed?.fileName)
        assertEquals(987_654_321L, parsed?.fileSize)
    }

    @Test
    fun `fileEnd round trips`() {
        val parsed = WsTransferMessages.parseFileEnd(WsTransferMessages.fileEnd("ab12cd34", 42L))

        assertEquals("ab12cd34", parsed?.transferId)
        assertEquals(42L, parsed?.bytesSent)
    }

    @Test
    fun `fileAck round trips both outcomes`() {
        val ok = WsTransferMessages.parseFileAck(WsTransferMessages.fileAck("ab12cd34", 42L, ok = true))
        val failed = WsTransferMessages.parseFileAck(WsTransferMessages.fileAck("ab12cd34", 7L, ok = false))

        assertEquals(true, ok?.ok)
        assertEquals(42L, ok?.bytesReceived)
        assertEquals(false, failed?.ok)
        assertEquals(7L, failed?.bytesReceived)
    }

    @Test
    fun `parsers reject unknown and malformed messages`() {
        assertNull(WsTransferMessages.parseHello("SOMETHING_ELSE version=1"))
        assertNull(WsTransferMessages.parseHello("FLASH_WS_HELLO version=1 name=NoId"))
        assertNull(WsTransferMessages.parseFileStart("FLASH_FILE_START version=1 transferId=x name=y"))
        assertNull(WsTransferMessages.parseFileEnd("FLASH_FILE_END version=1 transferId=x bytes=abc"))
        assertNull(WsTransferMessages.parseFileAck("FLASH_FILE_ACK version=1 transferId=x received=1 ok=maybe"))
    }

    @Test
    fun `hello does not collide with file message prefixes`() {
        val text = WsTransferMessages.hello("device-1234", "Flash Phone")

        assertTrue(WsTransferMessages.parseHello(text) != null)
        assertNull(WsTransferMessages.parseFileStart(text))
        assertNull(WsTransferMessages.parseFileEnd(text))
        assertNull(WsTransferMessages.parseFileAck(text))
    }
}
