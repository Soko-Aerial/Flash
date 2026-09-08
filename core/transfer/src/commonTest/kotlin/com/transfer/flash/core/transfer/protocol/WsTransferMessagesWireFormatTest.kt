@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.transfer.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the **literal wire text** of the four FLSH v2 control frames.
 *
 * This suite lives in `commonTest` on purpose: it is the only thing in this module that executes
 * on BOTH the Android host-test JVM and the desktop `jvm()` target, which is what CONVENTIONS.md
 * R3.1 requires of a converted module — `jvmTest` running zero tests would mean the desktop
 * target is compiled but unproven.
 *
 * It is deliberately NOT a second round-trip suite. `WsTransferMessagesTest` (androidHostTest)
 * already round-trips all four frames, and a round-trip cannot see a *symmetric* change to
 * [com.transfer.flash.core.common.protocol.FlashTextFraming]'s escaping: swap the escape table for
 * another one and encode→parse still agrees with itself while every peer on the old build stops
 * understanding us. The literals below are what a peer actually reads off the socket, so they fail
 * in that case. CONVENTIONS.md R8 protects this format; these assertions are how it is protected.
 *
 * Escape table, applied in this order: `%`→`%25`, ` `→`%20`, `=`→`%3D`.
 */
internal class WsTransferMessagesWireFormatTest {

    @Test
    fun protocol_version_is_pinned_at_1() {
        // A silent bump would make every frame below unreadable to a deployed peer.
        assertEquals(1, WsTransferMessages.PROTOCOL_VERSION)
    }

    @Test
    fun hello_emits_the_exact_wire_text() {
        // Space AND '=' in the friendly name: both escapes fire, in table order.
        assertEquals(
            "FLASH_WS_HELLO version=1 deviceId=device-1234 name=Kali%20Phone%20%3D%20Pro",
            WsTransferMessages.hello("device-1234", "Kali Phone = Pro"),
        )
    }

    @Test
    fun fileStart_emits_the_exact_wire_text() {
        // A literal '%' in the file name must become %25 BEFORE spaces become %20, or the
        // resulting %2520 would decode back to a space.
        assertEquals(
            "FLASH_FILE_START version=1 transferId=ab12cd34 " +
                "name=my%20100%25%20file%20(final).zip size=987654321",
            WsTransferMessages.fileStart("ab12cd34", "my 100% file (final).zip", 987_654_321L),
        )
    }

    @Test
    fun fileEnd_emits_the_exact_wire_text() {
        assertEquals(
            "FLASH_FILE_END version=1 transferId=ab12cd34 bytes=42",
            WsTransferMessages.fileEnd("ab12cd34", 42L),
        )
    }

    @Test
    fun fileAck_emits_the_exact_wire_text_for_both_outcomes() {
        assertEquals(
            "FLASH_FILE_ACK version=1 transferId=ab12cd34 received=42 ok=true",
            WsTransferMessages.fileAck("ab12cd34", 42L, ok = true),
        )
        assertEquals(
            "FLASH_FILE_ACK version=1 transferId=ab12cd34 received=7 ok=false",
            WsTransferMessages.fileAck("ab12cd34", 7L, ok = false),
        )
    }

    @Test
    fun non_ascii_names_round_trip_on_every_target() {
        // CJK plus a surrogate pair. The framing does no byte encoding of its own, so this is the
        // case where a per-target String difference would show up if one existed.
        val name = "转移 🚀"
        val text = WsTransferMessages.hello("dev-1", name)

        assertEquals("FLASH_WS_HELLO version=1 deviceId=dev-1 name=转移%20🚀", text)
        assertEquals(name, WsTransferMessages.parseHello(text)?.friendlyName)
    }

    @Test
    fun a_literal_percent_20_in_a_value_survives_the_round_trip() {
        // "a%20b" is a value that already looks escaped. It survives only because escape() maps
        // '%' first and unescape() undoes %25 LAST; reverse either and this decodes to "a b".
        val text = WsTransferMessages.hello("dev-1", "a%20b")

        assertEquals("FLASH_WS_HELLO version=1 deviceId=dev-1 name=a%2520b", text)
        assertEquals("a%20b", WsTransferMessages.parseHello(text)?.friendlyName)
    }

    @Test
    fun each_frame_is_rejected_by_the_other_three_parsers() {
        val hello = WsTransferMessages.hello("dev-1", "Flash Phone")
        val start = WsTransferMessages.fileStart("t1", "a.bin", 1L)
        val end = WsTransferMessages.fileEnd("t1", 1L)
        val ack = WsTransferMessages.fileAck("t1", 1L, ok = true)

        assertNotNull(WsTransferMessages.parseHello(hello))
        assertNull(WsTransferMessages.parseFileStart(hello))
        assertNull(WsTransferMessages.parseFileEnd(hello))
        assertNull(WsTransferMessages.parseFileAck(hello))

        assertNotNull(WsTransferMessages.parseFileStart(start))
        assertNull(WsTransferMessages.parseHello(start))

        assertNotNull(WsTransferMessages.parseFileEnd(end))
        assertNull(WsTransferMessages.parseFileAck(end))

        assertNotNull(WsTransferMessages.parseFileAck(ack))
        // FLASH_FILE_END is not a prefix-match of FLASH_FILE_ACK: parseFields compares the whole
        // first token, so a shared "FLASH_FILE_" stem must not be enough.
        assertNull(WsTransferMessages.parseFileEnd(ack))
    }
}
