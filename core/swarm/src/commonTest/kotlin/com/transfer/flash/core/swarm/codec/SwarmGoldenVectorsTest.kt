package com.transfer.flash.core.swarm.codec

import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden vector verification for FSW1 wire frames and canonical manifests (SW-3).
 * Asserts hand-calculated byte sequences to freeze wire layout.
 */
class SwarmGoldenVectorsTest {

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val h = clean[i * 2]
            val l = clean[i * 2 + 1]
            val high = if (h in '0'..'9') h - '0' else h - 'a' + 10
            val low = if (l in '0'..'9') l - '0' else l - 'a' + 10
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private val root1 = ContentRoot("01".repeat(32))
    private val root2 = ContentRoot("02".repeat(32))

    @Test
    fun `golden HAVE_ALL frame vector`() {
        val frame = SwarmFrame.HaveAll("g1", root1)
        val encoded = SwarmFrameCodec.encode(frame)

        // Envelope:
        // magic: 46 53 57 31
        // version: 01
        // type: 05
        // flags: 00 00
        // bodyLength: 24 00 00 00 (36 bytes: groupId 4B + root 32B)
        // Body:
        // str "g1": 02 00 67 31
        // root1: 32 bytes of 01
        val expectedHex = "46535731010500002400000002006731" + "01".repeat(32)

        assertEquals(expectedHex, encoded.toHex())
        val decoded = SwarmFrameCodec.decode(encoded)
        assertTrue(decoded is DecodeResult.Decoded)
        assertEquals(frame, decoded.frame)
    }

    @Test
    fun `golden PIECE frame with 3-byte payload`() {
        val payload = byteArrayOf(0x0a, 0x0b, 0x0c)
        val frame = SwarmFrame.Piece("g1", root1, index = 7, bytes = payload)
        val encoded = SwarmFrameCodec.encode(frame)

        // Envelope:
        // magic: 46 53 57 31, ver: 01, type: 07, flags: 00 00, bodyLen: 2f 00 00 00 (47 bytes: groupId 4B + root 32B + index 4B + len 4B + 3B)
        // Body:
        // str "g1": 02 00 67 31
        // root: 32B "01"
        // index: 07 00 00 00
        // length: 03 00 00 00
        // payload: 0a 0b 0c
        val expectedHex = "46535731010700002f00000002006731" + "01".repeat(32) + "07000000030000000a0b0c"

        assertEquals(expectedHex, encoded.toHex())
        val decoded = SwarmFrameCodec.decode(encoded)
        assertTrue(decoded is DecodeResult.Decoded)
        assertEquals(frame, decoded.frame)
    }

    @Test
    fun `golden REQUEST frame vector`() {
        val frame = SwarmFrame.Request("g1", root1, listOf(3, 8))
        val encoded = SwarmFrameCodec.encode(frame)

        // Envelope:
        // magic: 46 53 57 31, ver: 01, type: 06, flags: 00 00, bodyLen: 2d 00 00 00 (45 bytes: 36 + 1 + 8)
        // Body:
        // groupId: 02 00 67 31
        // root: 32B "01"
        // count: 02
        // piece 3: 03 00 00 00
        // piece 8: 08 00 00 00
        val expectedHex = "46535731010600002d00000002006731" + "01".repeat(32) + "020300000008000000"

        assertEquals(expectedHex, encoded.toHex())
        val decoded = SwarmFrameCodec.decode(encoded)
        assertTrue(decoded is DecodeResult.Decoded)
        assertEquals(frame, decoded.frame)
    }

    @Test
    fun `golden HAVE frame vector`() {
        val frame = SwarmFrame.Have("g1", root1, listOf(PieceRange(0, 5), PieceRange(10, 2)))
        val encoded = SwarmFrameCodec.encode(frame)

        // Envelope:
        // magic: 46 53 57 31, ver: 01, type: 04, flags: 00 00, bodyLen: 36 00 00 00 (54 bytes: 36 + 2 + 16)
        // Body:
        // groupId: 02 00 67 31
        // root: 32B "01"
        // rangeCount: 02 00
        // range 0: start=00 00 00 00, count=05 00 00 00
        // range 1: start=0a 00 00 00, count=02 00 00 00
        val expectedHex = "46535731010400003600000002006731" + "01".repeat(32) + "020000000000050000000a00000002000000"

        assertEquals(expectedHex, encoded.toHex())
        val decoded = SwarmFrameCodec.decode(encoded)
        assertTrue(decoded is DecodeResult.Decoded)
        assertEquals(frame, decoded.frame)
    }

    @Test
    fun `golden REJECT frame vector`() {
        val frame = SwarmFrame.Reject("g1", root1, reason = SwarmRejectReason.BUSY, retryAfterMs = 1500L, scopeAll = false, pieces = listOf(4))
        val encoded = SwarmFrameCodec.encode(frame)

        // Envelope:
        // magic: 46 53 57 31, ver: 01, type: 08, flags: 00 00, bodyLen: 2f 00 00 00 (47 bytes: 36 + 1 + 4 + 1 + 1 + 4)
        // Body:
        // groupId: 02 00 67 31
        // root: 32B "01"
        // reason: 01 (BUSY)
        // retryAfterMs: dc 05 00 00 (1500)
        // scopeAll: 00
        // count: 01
        // piece: 04 00 00 00
        val expectedHex = "46535731010800002f00000002006731" + "01".repeat(32) + "01dc050000000104000000"

        assertEquals(expectedHex, encoded.toHex())
        val decoded = SwarmFrameCodec.decode(encoded)
        assertTrue(decoded is DecodeResult.Decoded)
        assertEquals(frame, decoded.frame)
    }

    @Test
    fun `golden CANCEL frame vector`() {
        val sig = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)
        val frame = SwarmFrame.Cancel(
            groupId = "g1",
            root = root1,
            originId = "devA",
            messageId = "msg1",
            reason = SwarmTombstoneReason.USER,
            cancelledAtMs = 10000L,
            signature = sig,
        )
        val encoded = SwarmFrameCodec.encode(frame)

        // Envelope:
        // magic: 46 53 57 31, ver: 01, type: 09, flags: 00 00
        // Body:
        // groupId: 02 00 67 31
        // root: 32B "01"
        // originId: 04 00 64 65 76 41 ("devA")
        // messageId: 04 00 6d 73 67 31 ("msg1")
        // reason: 01 (USER)
        // cancelledAtMs: 10 27 00 00 00 00 00 00 (10000)
        // sig: 08 00 30 06 02 01 01 02 01 02 (10 bytes)
        // Total body len: 36 + 6 + 6 + 1 + 8 + 10 = 67 bytes (0x43)
        val expectedHex = "46535731010900004300000002006731" + "01".repeat(32) +
            "040064657641" + "04006d736731" + "01" + "1027000000000000" + "08003006020101020102"

        assertEquals(expectedHex, encoded.toHex())
        val decoded = SwarmFrameCodec.decode(encoded)
        assertTrue(decoded is DecodeResult.Decoded)
        assertEquals(frame, decoded.frame)
    }

    @Test
    fun `golden SOURCE_STATUS frame vector`() {
        val sig = byteArrayOf(0x01, 0x02)
        val frame = SwarmFrame.SourceStatus(
            groupId = "g1",
            root = root1,
            originId = "devA",
            messageId = "msg1",
            status = SourceState.LOST,
            reason = SourceReason.DELETED,
            atMs = 5000L,
            signature = sig,
        )
        val encoded = SwarmFrameCodec.encode(frame)

        // Envelope:
        // magic: 46 53 57 31, ver: 01, type: 0b, flags: 00 00
        // Body:
        // groupId: 02 00 67 31
        // root: 32B "01"
        // originId: 04 00 64 65 76 41
        // messageId: 04 00 6d 73 67 31
        // status: 01 (LOST)
        // reason: 01 (DELETED)
        // atMs: 88 13 00 00 00 00 00 00 (5000)
        // sig: 02 00 01 02 (4 bytes)
        // Total body: 36 + 6 + 6 + 1 + 1 + 8 + 4 = 62 bytes (0x3e)
        val expectedHex = "46535731010b00003e00000002006731" + "01".repeat(32) +
            "040064657641" + "04006d736731" + "0101" + "8813000000000000" + "02000102"

        assertEquals(expectedHex, encoded.toHex())
        val decoded = SwarmFrameCodec.decode(encoded)
        assertTrue(decoded is DecodeResult.Decoded)
        assertEquals(frame, decoded.frame)
    }

    @Test
    fun `golden SUMMARY frame vector`() {
        val entry = SwarmFrame.Summary.Entry(root2, SwarmContentState.PARTIAL, servingEnabled = true)
        val frame = SwarmFrame.Summary("g1", emptyList(), listOf(entry))
        val encoded = SwarmFrameCodec.encode(frame)

        // Envelope:
        // magic: 46 53 57 31, ver: 01, type: 01, flags: 00 00
        // Body:
        // groupId: 02 00 67 31 (4B)
        // tombCount: 00 00 (2B)
        // entryCount: 01 00 (2B)
        // entry:
        //   root2: 32B "02"
        //   state: 01 (PARTIAL)
        //   flags: 01 (servingEnabled = true)
        // Total body: 4 + 2 + 2 + 34 = 42 bytes (0x2a)
        val expectedHex = "46535731010100002a00000002006731" + "00000100" + "02".repeat(32) + "0101"

        assertEquals(expectedHex, encoded.toHex())
        val decoded = SwarmFrameCodec.decode(encoded)
        assertTrue(decoded is DecodeResult.Decoded)
        assertEquals(frame, decoded.frame)
    }

    @Test
    fun `golden 2-piece manifest canonical bytes and root vector`() {
        val piece0 = ByteArray(32) { 0x10.toByte() }
        val piece1 = ByteArray(32) { 0x20.toByte() }
        val fileSha = ByteArray(32) { 0x30.toByte() }

        val manifest = SwarmManifest(
            version = 1,
            pieceSize = 65536,
            totalSize = 100000L,
            fileSha256 = fileSha,
            pieceHashes = listOf(piece0, piece1),
            root = ContentRoot("00".repeat(32)),
        )

        val canonicalBytes = ManifestCodec.encodeCanonical(manifest)
        // Header:
        // magic: 46 53 57 4d ("FSWM")
        // version: 01
        // pieceSize: 00 00 01 00 (65536 LE)
        // totalSize: a0 86 01 00 00 00 00 00 (100000 LE)
        // fileSha: 32B "30"
        // pieceCount: 02 00 00 00 (2 LE)
        // piece0: 32B "10"
        // piece1: 32B "20"
        // Total = 4 + 1 + 4 + 8 + 32 + 4 + 64 = 117 bytes
        val expectedHex = "4653574d0100000100a086010000000000" + "30".repeat(32) + "02000000" + "10".repeat(32) + "20".repeat(32)

        assertEquals(expectedHex, canonicalBytes.toHex())
        val computedRoot = ManifestCodec.computeRoot(canonicalBytes)
        // Root is SHA-256 of canonicalBytes
        assertEquals(64, computedRoot.hex.length)

        val decoded = ManifestCodec.decode(canonicalBytes, computedRoot)
        assertEquals(manifest.pieceSize, decoded?.pieceSize)
        assertEquals(manifest.totalSize, decoded?.totalSize)
        assertEquals(2, decoded?.pieceCount)
    }
}
