@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.security.enterprise.HubFileKey
import com.transfer.flash.core.security.enterprise.HubFileLayout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HUBFILE-U10..U14: the sealed message that carries a hub file's ticket (ADR-216). */
class HubFileFrameCodecTest {

    private val key = HubFileKey.generate()

    private fun frame(
        size: Long = 3_000_000L,
        name: String = "Quarterly report = final.pdf",
        meta: String = "",
        groupId: String? = null,
        groupSig: String? = null,
    ): HubFileFrame {
        val layout = HubFileLayout.of(size, 1_048_576)!!
        return HubFileFrame(
            localId = "msg-1",
            senderId = "device-a",
            senderName = "Ada Lovelace",
            sentAt = 1_700_000_000_000L,
            ticket = HubFileTicket(
                fileId = "0123456789abcdef0123456789abcdef",
                key = key,
                cipherSha256 = "ab".repeat(32),
                plainBytes = size,
                cipherChunkBytes = 1_048_576,
                chunkCount = layout.chunkCount,
                expiresAtMs = 1_700_600_000_000L,
            ),
            fileName = name,
            mime = "application/pdf",
            meta = meta,
            groupId = groupId,
            groupSig = groupSig,
        )
    }

    private fun decoded(text: String): HubFileFrame = assertIs<HubFileFrameCodec.Result.Frame>(HubFileFrameCodec.decode(text)).frame

    private fun invalid(text: String): String = assertIs<HubFileFrameCodec.Result.Invalid>(HubFileFrameCodec.decode(text)).reason

    /** The encoded frame with one field replaced (or removed when [value] is null). */
    private fun with(field: String, value: String?): String {
        val fields = FlashTextFraming.parseFields(HubFileFrameCodec.encode(frame()), HubFileFrameCodec.PREFIX)!!.toMutableMap()
        if (value == null) fields.remove(field) else fields[field] = value
        return FlashTextFraming.encodeFields(HubFileFrameCodec.PREFIX, fields.toList())
    }

    @Test
    fun `HUBFILE-U10 a frame survives the round trip with every field, spaces and equals signs included`() {
        val original = frame(meta = "dur=1200;amps=1,2,3")
        val back = decoded(HubFileFrameCodec.encode(original))
        assertEquals(original.localId, back.localId)
        assertEquals(original.senderId, back.senderId)
        assertEquals(original.senderName, back.senderName)
        assertEquals(original.sentAt, back.sentAt)
        assertEquals(original.fileName, back.fileName)
        assertEquals(original.mime, back.mime)
        assertEquals(original.meta, back.meta)
        assertEquals(original.ticket.fileId, back.ticket.fileId)
        assertEquals(key.encode(), back.ticket.key.encode())
        assertEquals(original.ticket.cipherSha256, back.ticket.cipherSha256)
        assertEquals(original.ticket.plainBytes, back.ticket.plainBytes)
        assertEquals(original.ticket.chunkCount, back.ticket.chunkCount)
        assertEquals(original.ticket.expiresAtMs, back.ticket.expiresAtMs)
    }

    @Test
    fun `HUBFILE-U11 other text is not this frame and a different version is refused`() {
        assertNull(HubFileFrameCodec.decode("FLASH_MSG localId=1"))
        assertNull(HubFileFrameCodec.decode("FLASH_HUBFILEX v=1"))
        assertNull(HubFileFrameCodec.decode(""))
        assertEquals("version", invalid(with("v", "2")))
        assertEquals("version", invalid(with("v", null)))
    }

    @Test
    fun `HUBFILE-U12 every field rule refuses by name`() {
        assertEquals("localId", invalid(with("localId", "")))
        assertEquals("localId", invalid(with("localId", "x".repeat(129))))
        assertEquals("senderId", invalid(with("senderId", null)))
        assertEquals("sentAt", invalid(with("sentAt", "-1")))
        assertEquals("fileId", invalid(with("fileId", "../../etc/passwd")))
        assertEquals("fileId", invalid(with("fileId", "0123456789ABCDEF0123456789ABCDEF")))
        assertEquals("key", invalid(with("key", "hfk1.short")))
        assertEquals("key", invalid(with("key", null)))
        assertEquals("sha256", invalid(with("sha256", "ab".repeat(31))))
        assertEquals("size", invalid(with("size", "0")))
        assertEquals("size", invalid(with("size", "abc")))
        assertEquals("chunks", invalid(with("chunks", "2")), "a chunk count that does not fit the size is a lie")
        assertEquals("chunkBytes", invalid(with("chunkBytes", "16")), "one request per few bytes is refused")
        assertEquals("layout", invalid(with("chunkBytes", (2 * 1024 * 1024).toString())), "above the hub's chunk maximum")
        assertEquals("expiresAt", invalid(with("expiresAt", "-5")))
        assertEquals("mime", invalid(with("mime", "x".repeat(128))))
        assertEquals("meta", invalid(with("meta", "m".repeat(4097))))
    }

    @Test
    fun `HUBFILE-P4-U20 a group ticket round trips and a one-to-one ticket has no group`() {
        val group = decoded(HubFileFrameCodec.encode(frame(groupId = "g2-crew", groupSig = "c2ln/+9=")))
        assertEquals("g2-crew", group.groupId)
        assertEquals("c2ln/+9=", group.groupSig)
        val unsigned = decoded(HubFileFrameCodec.encode(frame(groupId = "g-legacy")))
        assertEquals("g-legacy", unsigned.groupId)
        assertNull(unsigned.groupSig, "a legacy group has no signature to carry")
        val plain = decoded(HubFileFrameCodec.encode(frame()))
        assertNull(plain.groupId)
        assertNull(plain.groupSig)
        assertFalse("groupId" in FlashTextFraming.parseFields(HubFileFrameCodec.encode(frame()), HubFileFrameCodec.PREFIX)!!)
    }

    @Test
    fun `HUBFILE-P4-U21 the group fields refuse by name`() {
        assertEquals("groupId", invalid(with("groupId", "")))
        assertEquals("groupId", invalid(with("groupId", "g".repeat(129))))
        assertEquals("groupId", invalid(with("groupId", "g2\u0000x")))
        assertEquals("groupSig", invalid(with("groupSig", "c2ln")), "a signature with no group is meaningless")
        val grouped = HubFileFrameCodec.encode(frame(groupId = "g2-crew"))
        val fields = FlashTextFraming.parseFields(grouped, HubFileFrameCodec.PREFIX)!!.toMutableMap()
        fun withSig(value: String) = FlashTextFraming.encodeFields(HubFileFrameCodec.PREFIX, (fields + ("groupSig" to value)).toList())
        assertEquals("groupSig", invalid(withSig("")))
        assertEquals("groupSig", invalid(withSig("s".repeat(1025))))
        assertEquals("groupSig", invalid(withSig("a\nb")))
    }

    @Test
    fun `HUBFILE-P4-U22 the printed form of a group ticket hides the key and the signature`() {
        val text = frame(groupId = "g2-crew", groupSig = "SECRETSIGNATURE").toString()
        assertFalse(text.contains(key.encode()))
        assertFalse(text.contains("SECRETSIGNATURE"))
        assertFalse(text.contains("g2-crew"), "ids in logs are cut to 8 characters; the group id is not printed whole")
    }

    @Test
    fun `HUBFILE-U13 a name that could be a path is refused`() {
        for (bad in listOf("", "   ", ".", "..", "a/b.pdf", "..\\b.pdf", "x\u0000.pdf", "x\n.pdf", "n".repeat(256))) {
            assertEquals("name", invalid(with("name", bad)), "name '$bad'")
        }
        assertEquals("résumé (1).pdf", decoded(with("name", "résumé (1).pdf")).fileName)
        assertEquals("application/octet-stream", decoded(with("mime", "")).mime)
    }

    @Test
    fun `the key is never in the frame's text form`() {
        val text = frame().toString()
        assertFalse(text.contains(key.encode()))
        assertFalse(text.contains("hfk1"))
    }

    @Test
    fun `HUBFILE-U14 1000 mutated or random inputs never throw`() {
        val random = Random(20261020)
        val valid = HubFileFrameCodec.encode(frame(groupId = "g2-crew", groupSig = "c2ln"))
        val alphabet = " =%./\\\u0000\n abcdefxyz0123456789ABCDEF-_hfk1.FLASH_HUBFILE"
        repeat(1000) { round ->
            val input = when (round % 4) {
                0 -> valid.substring(0, random.nextInt(valid.length + 1))
                1 -> valid.toCharArray().also { chars ->
                    repeat(1 + random.nextInt(6)) { chars[random.nextInt(chars.size)] = alphabet[random.nextInt(alphabet.length)] }
                }.concatToString()
                2 -> HubFileFrameCodec.PREFIX + " " + (1..random.nextInt(40)).joinToString(" ") {
                    listOf("v", "localId", "fileId", "key", "size", "chunks", "chunkBytes", "name", "sha256", "groupId", "groupSig").random(random) +
                        "=" + (1..random.nextInt(20)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
                }
                else -> (1..random.nextInt(300)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            }
            val result = HubFileFrameCodec.decode(input)
            if (result is HubFileFrameCodec.Result.Frame) {
                // Whatever decodes is internally consistent: the layout matches and the name is not a path.
                val t = result.frame.ticket
                assertEquals(HubFileLayout.of(t.plainBytes, t.cipherChunkBytes)!!.chunkCount, t.chunkCount)
                assertTrue(result.frame.fileName.none { it == '/' || it == '\\' })
                assertTrue(result.frame.groupSig == null || result.frame.groupId != null, "a signature never outlives its group")
            }
        }
    }
}
