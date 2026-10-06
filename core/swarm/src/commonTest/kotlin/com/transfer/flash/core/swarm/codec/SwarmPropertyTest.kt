package com.transfer.flash.core.swarm.codec

import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwarmPropertyTest {

    private val root = ContentRoot("aa".repeat(32))

    private fun sampleFrames(): List<SwarmFrame> = listOf(
        SwarmFrame.Summary(
            groupId = "group-1",
            tombstones = listOf(
                SwarmTombstone("group-1", root, "origin-1", "msg-1", SwarmTombstoneReason.USER, 1000L, byteArrayOf(1, 2, 3)),
            ),
            entries = listOf(
                SwarmFrame.Summary.Entry(root, SwarmContentState.ALL, servingEnabled = true),
                SwarmFrame.Summary.Entry(ContentRoot("bb".repeat(32)), SwarmContentState.PARTIAL, servingEnabled = false),
            ),
        ),
        SwarmFrame.ManifestGet(groupId = "group-1", root = root, fragmentIndex = 3),
        SwarmFrame.ManifestPart(groupId = "group-1", root = root, fragmentIndex = 0, fragmentCount = 2, bytes = byteArrayOf(1, 2, 3, 4)),
        SwarmFrame.Have(groupId = "group-1", root = root, ranges = listOf(PieceRange(0, 5), PieceRange(10, 3))),
        SwarmFrame.HaveAll(groupId = "group-1", root = root),
        SwarmFrame.Request(groupId = "group-1", root = root, pieces = listOf(0, 5, 9)),
        SwarmFrame.Piece(groupId = "group-1", root = root, index = 42, bytes = byteArrayOf(9, 8, 7, 6, 5)),
        SwarmFrame.Reject(groupId = "group-1", root = root, reason = SwarmRejectReason.BUSY, retryAfterMs = 5000L, scopeAll = false, pieces = listOf(1, 2)),
        SwarmFrame.Reject(groupId = "group-1", root = root, reason = SwarmRejectReason.NOT_MEMBER, retryAfterMs = 0L, scopeAll = true, pieces = emptyList()),
        SwarmFrame.Cancel(groupId = "group-1", root = root, originId = "origin-1", messageId = "msg-1", reason = SwarmTombstoneReason.DELETED, cancelledAtMs = 50000L, signature = byteArrayOf(4, 5, 6)),
        SwarmFrame.CancelAck(groupId = "group-1", root = root, messageId = "msg-1"),
        SwarmFrame.SourceStatus(groupId = "group-1", root = root, originId = "origin-1", messageId = "msg-1", status = SourceState.RESTORED, reason = SourceReason.NONE, atMs = 99999L, signature = byteArrayOf(7, 8)),
        SwarmFrame.Unrequest(groupId = "group-1", root = root, pieces = listOf(3, 7)),
    )

    @Test
    fun `all 12 frame types round trip through codec`() {
        for (frame in sampleFrames()) {
            val encoded = SwarmFrameCodec.encode(frame)
            val result = SwarmFrameCodec.decode(encoded)
            assertTrue(result is DecodeResult.Decoded, "Failed to decode type ${frame.typeCode}: $result")
            assertEquals(frame, result.frame, "Frame mismatch for type ${frame.typeCode}")
        }
    }

    @Test
    fun `10000 random byte arrays never throw`() {
        val rng = Random(42)
        for (i in 0 until 10_000) {
            val length = rng.nextInt(0, 256)
            val bytes = rng.nextBytes(length)
            // Must return Decoded, Unknown, or Malformed, never throw
            val result = SwarmFrameCodec.decode(bytes)
            assertTrue(result is DecodeResult.Malformed || result is DecodeResult.Unknown || result is DecodeResult.Decoded)
        }
    }

    @Test
    fun `every truncation of a valid frame gives malformed`() {
        for (frame in sampleFrames()) {
            val encoded = SwarmFrameCodec.encode(frame)
            for (truncatedLen in 0 until encoded.size) {
                val truncated = encoded.copyOfRange(0, truncatedLen)
                val result = SwarmFrameCodec.decode(truncated)
                assertTrue(
                    result is DecodeResult.Malformed,
                    "Truncation of type ${frame.typeCode} to $truncatedLen/${encoded.size} should be Malformed, got: $result",
                )
            }
        }
    }

    @Test
    fun `trailing bytes produce malformed`() {
        val frame = SwarmFrame.HaveAll("group-1", root)
        val encoded = SwarmFrameCodec.encode(frame)
        val withTrailing = encoded + byteArrayOf(0x00, 0x01)
        val result = SwarmFrameCodec.decode(withTrailing)
        assertTrue(result is DecodeResult.Malformed)
    }

    @Test
    fun `duplicate request pieces produce malformed`() {
        // Build valid request frame
        val frame = SwarmFrame.Request("group-1", root, listOf(1, 2))
        val encoded = SwarmFrameCodec.encode(frame)
        // Corrupt piece 2 to be piece 1
        // Envelope: 12B. groupId: 2B len + 7B. root: 32B. count: 1B (idx 53). piece 0: 54..57. piece 1: 58..61
        val corrupted = encoded.copyOf()
        corrupted[58] = corrupted[54]
        corrupted[59] = corrupted[55]
        corrupted[60] = corrupted[56]
        corrupted[61] = corrupted[57]

        val result = SwarmFrameCodec.decode(corrupted)
        assertTrue(result is DecodeResult.Malformed, "Duplicate pieces must be rejected as Malformed")
    }

    @Test
    fun `decodeStr with invalid UTF-8 bytes fails with Malformed`() {
        val frame = SwarmFrame.CancelAck("group-1", root, "msg-1")
        val encoded = SwarmFrameCodec.encode(frame)
        // Corrupt group-1 string: Envelope 12B, len 2B (idx 12, 13). String bytes at 14..20.
        val corrupted = encoded.copyOf()
        corrupted[14] = 0xFF.toByte() // 0xFF is never valid UTF-8
        val result = SwarmFrameCodec.decode(corrupted)
        assertTrue(result is DecodeResult.Malformed, "Invalid UTF-8 in string should decode to Malformed, got $result")
    }

    @Test
    fun `decodeHave with integer overflow in range fails with Malformed`() {
        val frame = SwarmFrame.Have("g1", root, listOf(PieceRange(0, 1)))
        val encoded = SwarmFrameCodec.encode(frame)
        // Envelope: 12. groupId: 2 + 2 = 4 (12..15). root: 32 (16..47). rangeCount: 2 (48..49). start: 50..53. count: 54..57.
        val corrupted = encoded.copyOf()
        corrupted[50] = 0x64; corrupted[51] = 0; corrupted[52] = 0; corrupted[53] = 0
        corrupted[54] = 0xFF.toByte(); corrupted[55] = 0xFF.toByte(); corrupted[56] = 0xFF.toByte(); corrupted[57] = 0x7F
        val result = SwarmFrameCodec.decode(corrupted)
        assertTrue(result is DecodeResult.Malformed, "Integer overflow in range should decode to Malformed, got $result")
    }
}
