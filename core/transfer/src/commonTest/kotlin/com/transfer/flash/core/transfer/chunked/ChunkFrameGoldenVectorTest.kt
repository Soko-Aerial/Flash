package com.transfer.flash.core.transfer.chunked

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Byte-identity golden vectors for the framing v2 wire format.
 *
 * These eleven hex strings were captured from the `java.nio.ByteBuffer` implementation of
 * [ChunkFrame.serialize] that shipped up to and including Phase 13B-2, *before* the Phase 13B-3b
 * rewrite onto okio touched a single line, and were asserted green against that implementation
 * first — so the vectors are known faithful rather than merely self-consistent with whatever the
 * code does today. R8's authorisation for that rewrite carries exactly one acceptance criterion,
 * byte-identical output, and this suite is that criterion.
 *
 * The suite lives in `commonTest` so `testAndroidHostTest` and `jvmTest` both run it. The point of
 * the sub-step is that one implementation now serves both targets, so one target agreeing proves
 * nothing.
 *
 * Expected values are written as concatenations of labelled pieces rather than as opaque blobs,
 * because the layout claim being defended is **little-endian** and that is only visible if
 * `chunkSize = 65536` reads as `"00000100"` on the page.
 */
class ChunkFrameGoldenVectorTest {

    @Test
    fun `serialized bytes are identical to the pre-rewrite golden vectors`() {
        for ((label, frame, expectedHex) in VECTORS) {
            val actual = ChunkFrame.serialize(frame)
            assertEquals(
                expectedHex,
                Sha256.hex(actual),
                "$label: serialized bytes differ from the golden vector",
            )
            assertEquals(
                expectedHex.length / 2,
                actual.size,
                "$label: frame length differs from the golden vector",
            )
        }
    }

    @Test
    fun `every golden vector parses and reserializes to its own bytes`() {
        for ((label, _, expectedHex) in VECTORS) {
            val bytes = hexToBytes(expectedHex)
            val parsed = assertNotNull(ChunkFrame.parse(bytes), "$label: golden bytes did not parse")
            assertEquals(
                expectedHex,
                Sha256.hex(ChunkFrame.serialize(parsed)),
                "$label: reserializing the parsed frame changed the bytes",
            )
        }
    }

    /**
     * The reason `commonMain` uses okio's UTF-8 encoder and not `String.encodeToByteArray()`.
     *
     * A file name may legally hold an unpaired surrogate, and the byte it becomes is wire-visible.
     * okio's `writeUtf8` emits `'?'` (0x3F) from a single implementation shared by every target;
     * `encodeToByteArray()` is an `expect`/`actual` whose JVM half happens to agree today but whose
     * Kotlin/Native half is not pinned by anything this repo can see. This asserts the byte rather
     * than trusting the library note above it.
     */
    @Test
    fun `an unpaired surrogate in a file name serializes as 0x3f`() {
        val loneHighSurrogate = Char(0xD83D)
        val frame = ChunkFrame.FileStart(
            transferId = "t-1",
            fileId = "f-1",
            fileName = "bad-$loneHighSurrogate.txt",
            totalBytes = 2L,
            totalChunks = 1,
            chunkSize = 65_536,
            fileSha256Hex = HASH_LOWER,
        )
        val hex = Sha256.hex(ChunkFrame.serialize(frame))
        assertTrue(
            hex.contains("0900" + "6261642d3f2e747874"),
            "expected a 9-byte name field encoding \"bad-?.txt\", got $hex",
        )
        val parsed = ChunkFrame.parse(ChunkFrame.serialize(frame)) as ChunkFrame.FileStart
        assertEquals("bad-?.txt", parsed.fileName, "round-tripped name")
    }

    /**
     * Keeps [HASH_ASCII] honest: it is the digest hex from [HASH_LOWER] encoded one ASCII byte per
     * character, which is what makes the `sha256hex` field 64 bytes wide on the wire. Without this
     * the constant would be an unexplained blob repeated in nine vectors.
     */
    @Test
    fun `the hash field piece is the ascii encoding of the digest hex`() {
        assertEquals(4096, ChunkFrame.MAX_STRING_BYTES, "MAX_STRING_BYTES changed")
        assertEquals(Sha256.HEX_LENGTH, HASH_LOWER.length, "digest hex length")
        assertEquals(
            HASH_ASCII,
            Sha256.hex(HASH_LOWER.encodeToByteArray()),
            "HASH_ASCII is not the ASCII encoding of HASH_LOWER",
        )
        assertEquals(Sha256.HEX_LENGTH * 2, HASH_ASCII.length, "HASH_ASCII width")
    }
}

/** `MAGIC` = 'F','L','S','H' followed by `VERSION` = 2, the first five bytes of every frame. */
private const val MAGIC_V2 = "464c5348" + "02"

/** `string transferId` = `"t-1"`: a uint16 LE length of 3, then the UTF-8 bytes. */
private const val T1 = "0300" + "742d31"

/** `string fileId` = `"f-1"`. */
private const val F1 = "0300" + "662d31"

/** The digest used by every FILE_START vector. Lowercase; V4 feeds the uppercase form in. */
private const val HASH_LOWER = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

/**
 * [HASH_LOWER] as it appears on the wire: 64 ASCII bytes, so 128 hex characters here. Asserted
 * against `Sha256.hex(HASH_LOWER.encodeToByteArray())` rather than trusted.
 */
private const val HASH_ASCII =
    "6261373831366266" + "3866303163666561" + "3431343134306465" + "3564616532323233" +
        "6230303336316133" + "3936313737613963" + "6234313066663631" + "6632303031356164"

/** SHA-256 of zero bytes, raw 32-byte form, as CHUNK carries it. */
private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

/** SHA-256 of `"chunk-payload"`, raw 32-byte form. */
private const val PAYLOAD_SHA256 = "9d835e725fcadca0091f8dce74e4ea20953fb0ed6fb284c486aa4accad4d8073"

private fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd-length hex: ${hex.length}" }
    return ByteArray(hex.length / 2) { i ->
        ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
    }
}

/**
 * The eleven captured vectors: a label, the frame, and the expected hex of
 * `ChunkFrame.serialize(frame)`. The third byte group in each expected value is the payload length
 * as a uint32 LE, so `"61000000"` is 97 = 107 total bytes − a 10-byte header.
 */
private val VECTORS: List<Triple<String, ChunkFrame, String>> = listOf(
    Triple(
        "V1 fileStart ascii minimal",
        ChunkFrame.FileStart(
            transferId = "t-1",
            fileId = "f-1",
            fileName = "a.txt",
            totalBytes = 1L,
            totalChunks = 1,
            chunkSize = 65_536,
            fileSha256Hex = HASH_LOWER,
        ),
        MAGIC_V2 + "01" + "61000000" + T1 + F1 +
            "0500" + "612e747874" +
            "0100000000000000" + "01000000" + "00000100" + HASH_ASCII,
    ),
    Triple(
        "V2 fileStart unicode name",
        ChunkFrame.FileStart(
            transferId = "t-1",
            fileId = "f-1",
            fileName = "h" + Char(0xE9) + "llo-" + Char(0x65E5) + Char(0x672C) + "-" +
                Char(0xD83D) + Char(0xDE00) + ".txt",
            totalBytes = 1_000_003L,
            totalChunks = 62,
            chunkSize = 16_384,
            fileSha256Hex = HASH_LOWER,
        ),
        // 22 UTF-8 bytes for 14 UTF-16 chars: 2 for e-acute, 3 each for the two CJK ideographs,
        // 4 for the emoji's surrogate pair.
        MAGIC_V2 + "01" + "72000000" + T1 + F1 +
            "1600" + "68" + "c3a9" + "6c6c6f2d" + "e697a5" + "e69cac" + "2d" + "f09f9880" + "2e747874" +
            "43420f0000000000" + "3e000000" + "00400000" + HASH_ASCII,
    ),
    Triple(
        "V3 fileStart lone surrogate name",
        ChunkFrame.FileStart(
            transferId = "t-1",
            fileId = "f-1",
            fileName = "bad-" + Char(0xD83D) + ".txt",
            totalBytes = 2L,
            totalChunks = 1,
            chunkSize = 65_536,
            fileSha256Hex = HASH_LOWER,
        ),
        // The unpaired high surrogate is one byte, 0x3F '?', not U+FFFD's three.
        MAGIC_V2 + "01" + "65000000" + T1 + F1 +
            "0900" + "6261642d" + "3f" + "2e747874" +
            "0200000000000000" + "01000000" + "00000100" + HASH_ASCII,
    ),
    Triple(
        "V4 fileStart extremes uppercase hash",
        ChunkFrame.FileStart(
            transferId = "t",
            fileId = "f",
            fileName = "x",
            totalBytes = Long.MAX_VALUE,
            totalChunks = Int.MAX_VALUE,
            chunkSize = Int.MAX_VALUE,
            fileSha256Hex = HASH_LOWER.uppercase(),
        ),
        // Little-endian makes the sign bit the LAST byte: 7f, not the first.
        // The uppercase input is normalized to lowercase before it reaches the wire.
        MAGIC_V2 + "01" + "59000000" +
            "0100" + "74" + "0100" + "66" + "0100" + "78" +
            "ffffffffffffff7f" + "ffffff7f" + "ffffff7f" + HASH_ASCII,
    ),
    Triple(
        "V5 fileStart 300 byte name",
        ChunkFrame.FileStart(
            transferId = "t-1",
            fileId = "f-1",
            fileName = "a".repeat(300),
            totalBytes = 3L,
            totalChunks = 1,
            chunkSize = 65_536,
            fileSha256Hex = HASH_LOWER,
        ),
        // A name longer than 255 bytes, so the uint16 length prefix is exercised: 300 = 0x012C.
        MAGIC_V2 + "01" + "88010000" + T1 + F1 +
            "2c01" + "61".repeat(300) +
            "0300000000000000" + "01000000" + "00000100" + HASH_ASCII,
    ),
    Triple(
        "V6 chunk empty data",
        ChunkFrame.Chunk(
            transferId = "t-1",
            fileId = "f-1",
            index = 0,
            data = ByteArray(0),
            chunkSha256 = Sha256.digest(ByteArray(0)),
        ),
        MAGIC_V2 + "02" + "32000000" + T1 + F1 +
            "00000000" + "00000000" + EMPTY_SHA256,
    ),
    Triple(
        "V7 chunk binary max index",
        ChunkFrame.Chunk(
            transferId = "t-1",
            fileId = "f-1",
            index = Int.MAX_VALUE,
            data = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFE.toByte(), 0xFF.toByte()),
            chunkSha256 = Sha256.digest("chunk-payload".encodeToByteArray()),
        ),
        // Opaque bytes: the high-bit ones must survive untouched, which is why CHUNK is not JSON.
        MAGIC_V2 + "02" + "38000000" + T1 + F1 +
            "ffffff7f" + "06000000" + "00017f80feff" + PAYLOAD_SHA256,
    ),
    Triple(
        "V8 ack empty",
        ChunkFrame.AckBatch("t-1", "f-1", emptyList()),
        MAGIC_V2 + "03" + "0e000000" + T1 + F1 + "00000000",
    ),
    Triple(
        "V9 ack unsorted with duplicate",
        ChunkFrame.AckBatch("t-1", "f-1", listOf(9, 0, 7, 8, 0)),
        // Ascending order is part of the format, so serialize sorts. The duplicate 0 is KEPT —
        // count stays 5 — because deduplication is the caller's job, not the framing's.
        MAGIC_V2 + "03" + "22000000" + T1 + F1 +
            "05000000" + "00000000" + "00000000" + "07000000" + "08000000" + "09000000",
    ),
    Triple(
        "V10 complete verified",
        ChunkFrame.Complete("t-1", "f-1", verified = true),
        MAGIC_V2 + "04" + "0b000000" + T1 + F1 + "01",
    ),
    Triple(
        "V11 complete unverified",
        ChunkFrame.Complete("t-1", "f-1", verified = false),
        MAGIC_V2 + "04" + "0b000000" + T1 + F1 + "00",
    ),
)
