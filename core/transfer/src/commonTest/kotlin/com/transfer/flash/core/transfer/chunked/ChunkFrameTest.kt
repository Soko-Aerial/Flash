package com.transfer.flash.core.transfer.chunked

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moved from `androidHostTest` to `commonTest` in Phase 13B-3e, so both the Android and the JVM
 * target now run it. The JUnit 4 → `kotlin.test` conversion is mechanical, with one trap worth
 * naming: `org.junit.Assert` takes its optional message FIRST and `kotlin.test` takes it LAST, so
 * every message-carrying assertion had to have its arguments swapped. A missed swap does not fail
 * to compile here — `assertNull(message, value)` would simply assert the wrong argument.
 */
class ChunkFrameTest {

    private val start = ChunkFrame.FileStart(
        transferId = "t-1",
        fileId = "f-1",
        fileName = "photo ünicode 100%.bin",
        totalBytes = 1_000_003L,
        totalChunks = 62,
        chunkSize = 16_384,
        fileSha256Hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    )

    private val chunk = ChunkFrame.Chunk(
        transferId = "t-1",
        fileId = "f-1",
        index = 41,
        data = ByteArray(1234) { (it * 7).toByte() },
        chunkSha256 = Sha256.digest("chunk-payload".encodeToByteArray()),
    )

    private val ack = ChunkFrame.AckBatch("t-1", "f-1", listOf(9, 0, 7, 8))

    private val complete = ChunkFrame.Complete("t-1", "f-1", verified = true)

    @Test
    fun `fileStart roundtrips`() {
        assertEquals(start, ChunkFrame.parse(ChunkFrame.serialize(start)))
    }

    @Test
    fun `chunk roundtrips including binary payload`() {
        val parsed = ChunkFrame.parse(ChunkFrame.serialize(chunk))
        assertEquals(chunk, parsed)
        assertTrue(chunk.data.contentEquals((parsed as ChunkFrame.Chunk).data))
    }

    @Test
    fun `ackBatch normalizes to ascending indexes and roundtrips`() {
        val parsed = ChunkFrame.parse(ChunkFrame.serialize(ack)) as ChunkFrame.AckBatch
        assertEquals(listOf(0, 7, 8, 9), parsed.indexes)
    }

    @Test
    fun `complete roundtrips both verified states`() {
        assertEquals(complete, ChunkFrame.parse(ChunkFrame.serialize(complete)))
        val failed = complete.copy(verified = false)
        assertEquals(failed, ChunkFrame.parse(ChunkFrame.serialize(failed)))
    }

    @Test
    fun `header layout is magic version type length`() {
        val bytes = ChunkFrame.serialize(start)
        assertEquals('F'.code.toByte(), bytes[0])
        assertEquals('L'.code.toByte(), bytes[1])
        assertEquals('S'.code.toByte(), bytes[2])
        assertEquals('H'.code.toByte(), bytes[3])
        assertEquals(2.toByte(), bytes[4])
        assertEquals(1.toByte(), bytes[5])
        assertEquals(bytes.size, ChunkFrame.HEADER_SIZE + readI32(bytes, 6))
    }

    @Test
    fun `every truncation of a valid frame is rejected`() {
        val full = ChunkFrame.serialize(start)
        for (len in 0 until full.size) {
            assertNull(ChunkFrame.parse(full.copyOf(len)), "length $len must not parse")
        }
    }

    @Test
    fun `corrupted frames are rejected`() {
        val full = ChunkFrame.serialize(chunk)

        // Bad magic.
        val badMagic = full.copyOf()
        badMagic[2] = 'X'.code.toByte()
        assertNull(ChunkFrame.parse(badMagic))

        // Wrong version.
        val badVersion = full.copyOf()
        badVersion[4] = 3
        assertNull(ChunkFrame.parse(badVersion))

        // Unknown type byte with consistent declared length.
        val badType = full.copyOf()
        badType[5] = 9
        assertNull(ChunkFrame.parse(badType))

        // Payload length field disagrees with actual size.
        val badLength = full.copyOf()
        badLength[6] = ((badLength[6].toInt() + 1) and 0xFF).toByte()
        assertNull(ChunkFrame.parse(badLength))

        // Trailing garbage after a complete frame.
        assertNull(ChunkFrame.parse(full + 0.toByte()))

        // Empty / null-length input.
        assertNull(ChunkFrame.parse(ByteArray(0)))

        // Declared string length exceeding the payload must be rejected, not crash.
        val corruptStart = ChunkFrame.serialize(start)
        corruptStart[ChunkFrame.HEADER_SIZE] = 0x0F
        corruptStart[ChunkFrame.HEADER_SIZE + 1] = 0xFF.toByte()
        assertNull(
            ChunkFrame.parse(corruptStart),
            "oversized declared string length must be rejected",
        )
    }

    @Test
    fun `serialization refuses invalid digests and constructor rejects bad facts`() {
        try {
            ChunkFrame.FileStart(
                transferId = "t",
                fileId = "f",
                fileName = "x.bin",
                totalBytes = 1,
                totalChunks = 1,
                chunkSize = Chunker.MIN_CHUNK_SIZE_BYTES,
                fileSha256Hex = "ab",
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("fileSha256Hex"))
        }
    }

    private fun readI32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
