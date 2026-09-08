package com.transfer.flash.core.transfer.chunked

import okio.Buffer
import okio.ByteString.Companion.toByteString

/**
 * Framing v2 wire types for chunked, resumable transfers (C5.3), self-contained binary format.
 *
 * These frames travel as `FlashEnvelope` payloads (version negotiated per [com.transfer.flash.core.common.protocol.FlashProtocol]
 * assert-on-handshake) but are **binary**, not JSON: CHUNK payloads are up to 256 KB of opaque
 * bytes and must not pay base64/JSON-escape overhead. The layout below is the single source of
 * truth; the Windows/Linux client and the future Rust bridge implement from this doc.
 *
 * ## Phase 13B-3b: `java.nio` → okio, under an explicit R8 authorisation
 *
 * This file is named on CONVENTIONS.md R8's untouchable list because it *is* a wire format. The
 * human authorised the rewrite on 2026-09-05 with one acceptance criterion: **byte-identical
 * output**. Golden hex vectors were captured from the previous `java.nio.ByteBuffer` implementation
 * *before* any edit, asserted against it to prove the vectors faithful, and are asserted against
 * this implementation from `commonTest` — see `ChunkFrameGoldenVectorTest`. The layout
 * documentation below is unchanged because the layout is unchanged.
 *
 * Three seams were replaced. All three were checked by measurement, not assumed:
 *
 * - `ByteArrayOutputStream` + a little-endian `ByteBuffer` scratch → okio [Buffer], whose
 *   `writeShortLe`/`writeIntLe`/`writeLongLe` are the same little-endian primitives. Assembling
 *   header and payload with [Buffer.writeAll] *moves* segments instead of copying them, so this
 *   costs one array copy per frame where the old code cost two.
 * - `String.toByteArray(Charsets.UTF_8)` → [Buffer.writeUtf8]. `Charsets` is JVM-only. The reason
 *   this is okio's encoder rather than Kotlin's `String.encodeToByteArray()` is **unpaired
 *   surrogates**: a filename may legally contain one, and the byte it becomes is wire-visible.
 *   okio emits `'?'` (0x3F) for one in a single `commonMain` implementation shared by every target.
 *   `encodeToByteArray()` is an `expect`/`actual` whose JVM half is literally
 *   `toByteArray(Charsets.UTF_8)` — identical today — but whose behaviour on a future
 *   Kotlin/Native target is not fixed by anything this repo can see. Picking okio removes the
 *   question instead of answering it for one platform. `V3` in the golden vectors pins the byte.
 * - `String(bytes, Charsets.UTF_8)` → [okio.ByteString.utf8], and `String(bytes,
 *   Charsets.US_ASCII)` → [asciiBytes]'s decoding counterpart in [Reader.fixedString]. Both
 *   substitute U+FFFD for malformed input exactly as the JDK decoders with `REPLACE` did.
 *
 * ## Byte layout (all multi-byte scalars LITTLE-ENDIAN)
 *
 * Every frame:
 * ```
 * offset  size  field
 * 0       4     MAGIC = 'F','L','S','H' (0x46 0x4C 0x53 0x48)
 * 4       1     VERSION = 2 (matches FlashProtocol.VERSION v2)
 * 5       1     TYPE    = 1 FILE_START | 2 CHUNK | 3 ACK_BATCH | 4 COMPLETE
 * 6       4     PAYLOAD_LENGTH (uint32 LE; enforced against actual remaining bytes on parse)
 * 10      ...   PAYLOAD
 * ```
 *
 * Shared payload field encodings:
 * ```
 * string = uint16 LE byteLength + UTF-8 bytes          (byteLength <= MAX_STRING_BYTES)
 * sha256hex = exactly 64 ASCII hex characters          (validated, normalized lowercase)
 * sha256raw = exactly 32 raw digest bytes              (compact form for per-chunk hashes)
 * ```
 *
 * ### TYPE=1 FILE_START (sender → receiver)
 * ```
 * string transferId
 * string fileId
 * string fileName
 * int64  totalBytes      (> 0)
 * int32  totalChunks     (> 0, == ceil(totalBytes / chunkSize))
 * int32  chunkSize       ([MIN_CHUNK_SIZE_BYTES, MAX_CHUNK_SIZE_BYTES])
 * sha256hex fileSha256Hex
 * ```
 *
 * ### TYPE=2 CHUNK (sender → receiver)
 * ```
 * string transferId
 * string fileId
 * int32  index           ([0, totalChunks))
 * int32  dataLength      (== expected chunk length for index; last chunk may be shorter)
 * bytes  data[dataLength]
 * sha256raw chunkSha256  (independent SHA-256 over `data` — verified before disk write, C5.5;
 *                         raw 32 B chosen over hex to keep per-chunk overhead at 32 B,
 *                         mirroring BitTorrent's compact piece-hash model)
 * ```
 *
 * ### TYPE=3 ACK_BATCH (receiver → sender)
 * ```
 * string transferId
 * string fileId
 * int32  count
 * int32  indexes[count]  (ascending, deduplicated received-and-verified chunk indexes)
 * ```
 * Absence of an index from every ACK batch is the implicit NACK: the sender's confirmed-mirror
 * never gains that index and resume/re-request logic targets exactly those holes.
 *
 * ### TYPE=4 COMPLETE (receiver → sender)
 * ```
 * string transferId
 * string fileId
 * uint8  verified        (1 = whole-file digest re-check passed or was not enabled; 0 = failed)
 * ```
 *
 * ## Parse contract
 *
 * [parse] is total: it returns **null** on any malformation (bad magic/version/type, truncated
 * header or payload, declared lengths exceeding remaining bytes, oversized strings, invalid hex,
 * trailing bytes after a complete frame, out-of-range verified byte). It never throws on
 * untrusted input. Serialization throws [IllegalArgumentException] only for programmer errors
 * (oversized strings, invalid digests, blank ids).
 *
 * Direction conventions follow LocalSend protocol v2's prepare/upload/ack split
 * (https://github.com/localsend/protocol §4): metadata first, then content, receiver-side
 * verification feedback to the sender.
 */
public sealed class ChunkFrame {

    public abstract val type: FrameType

    public enum class FrameType(public val code: Byte) {
        FILE_START(1),
        CHUNK(2),
        ACK_BATCH(3),
        COMPLETE(4),
    }

    public data class FileStart(
        val transferId: String,
        val fileId: String,
        val fileName: String,
        val totalBytes: Long,
        val totalChunks: Int,
        val chunkSize: Int,
        val fileSha256Hex: String,
    ) : ChunkFrame() {
        init {
            require(transferId.isNotBlank()) { "transferId must not be blank" }
            require(fileId.isNotBlank()) { "fileId must not be blank" }
            require(fileName.isNotEmpty()) { "fileName must not be empty" }
            require(totalBytes > 0) { "totalBytes must be > 0, was $totalBytes" }
            require(totalChunks > 0) { "totalChunks must be > 0, was $totalChunks" }
            require(Sha256.isValidHex(fileSha256Hex)) { "fileSha256Hex is not a SHA-256 hex digest" }
        }

        override val type: FrameType get() = FrameType.FILE_START
    }

    public data class Chunk(
        val transferId: String,
        val fileId: String,
        val index: Int,
        val data: ByteArray,
        /** Independent SHA-256 over [data], raw 32-byte form (see class KDoc). */
        val chunkSha256: ByteArray,
    ) : ChunkFrame() {
        init {
            require(transferId.isNotBlank()) { "transferId must not be blank" }
            require(fileId.isNotBlank()) { "fileId must not be blank" }
            require(index >= 0) { "index must be >= 0, was $index" }
            require(data.size <= MAX_CHUNK_DATA_BYTES) {
                "chunk data ${data.size} exceeds MAX_CHUNK_DATA_BYTES=$MAX_CHUNK_DATA_BYTES"
            }
            require(chunkSha256.size == Sha256.RAW_LENGTH) {
                "chunkSha256 must be ${Sha256.RAW_LENGTH} raw bytes, was ${chunkSha256.size}"
            }
        }

        override val type: FrameType get() = FrameType.CHUNK

        override fun equals(other: Any?): Boolean =
            other is Chunk &&
                other.transferId == transferId &&
                other.fileId == fileId &&
                other.index == index &&
                other.data.contentEquals(data) &&
                other.chunkSha256.contentEquals(chunkSha256)

        override fun hashCode(): Int {
            var result = transferId.hashCode()
            result = 31 * result + fileId.hashCode()
            result = 31 * result + index
            result = 31 * result + data.contentHashCode()
            result = 31 * result + chunkSha256.contentHashCode()
            return result
        }
    }

    public data class AckBatch(
        val transferId: String,
        val fileId: String,
        val indexes: List<Int>,
    ) : ChunkFrame() {
        init {
            require(transferId.isNotBlank()) { "transferId must not be blank" }
            require(fileId.isNotBlank()) { "fileId must not be blank" }
            require(indexes.size <= MAX_ACK_COUNT) {
                "ACK batch ${indexes.size} exceeds MAX_ACK_COUNT=$MAX_ACK_COUNT"
            }
            require(indexes.all { it >= 0 }) { "ACK indexes must be non-negative" }
        }

        override val type: FrameType get() = FrameType.ACK_BATCH
    }

    public data class Complete(
        val transferId: String,
        val fileId: String,
        val verified: Boolean,
    ) : ChunkFrame() {
        init {
            require(transferId.isNotBlank()) { "transferId must not be blank" }
            require(fileId.isNotBlank()) { "fileId must not be blank" }
        }

        override val type: FrameType get() = FrameType.COMPLETE
    }

    public companion object {

        public val MAGIC: ByteArray = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte())

        /** Framing version; pinned to FlashProtocol.VERSION (v2). */
        public const val VERSION: Int = 2

        public const val HEADER_SIZE: Int = 10

        /** Hard cap for a single id/name string on the wire. */
        public const val MAX_STRING_BYTES: Int = 4096

        /** Hard cap for CHUNK data accepted when parsing untrusted input (>= max chunk size). */
        public const val MAX_CHUNK_DATA_BYTES: Int = 1024 * 1024

        /** Hard cap for ACK batch size when parsing untrusted input. */
        public const val MAX_ACK_COUNT: Int = 1 shl 20

        /** Serializes a frame to the full header + payload byte layout documented above. */
        public fun serialize(frame: ChunkFrame): ByteArray {
            val payload = PayloadWriter()
            when (frame) {
                is FileStart -> {
                    payload.string(frame.transferId)
                    payload.string(frame.fileId)
                    payload.string(frame.fileName)
                    payload.i64(frame.totalBytes)
                    payload.i32(frame.totalChunks)
                    payload.i32(frame.chunkSize)
                    payload.rawAscii(Sha256.normalizeHex(frame.fileSha256Hex))
                }

                is Chunk -> {
                    payload.string(frame.transferId)
                    payload.string(frame.fileId)
                    payload.i32(frame.index)
                    payload.i32(frame.data.size)
                    payload.bytes(frame.data)
                    payload.bytes(frame.chunkSha256)
                }

                is AckBatch -> {
                    payload.string(frame.transferId)
                    payload.string(frame.fileId)
                    payload.i32(frame.indexes.size)
                    // Ascending order is part of the documented format.
                    frame.indexes.sorted().forEach(payload::i32)
                }

                is Complete -> {
                    payload.string(frame.transferId)
                    payload.string(frame.fileId)
                    payload.u8(if (frame.verified) 1 else 0)
                }
            }
            val body = payload.buffer()
            val bodySize = body.size
            require(bodySize <= Int.MAX_VALUE) { "payload too large: $bodySize" }
            val out = Buffer()
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeByte(frame.type.code.toInt())
            out.writeIntLe(bodySize.toInt())
            // writeAll MOVES body's segments into out instead of copying their bytes.
            out.writeAll(body)
            return out.readByteArray()
        }

        /**
         * Parses one full frame; returns null on ANY malformation (see class KDoc).
         * Tolerates nothing silently: trailing garbage after the payload is rejected.
         */
        public fun parse(bytes: ByteArray): ChunkFrame? {
            if (bytes.size < HEADER_SIZE) return null
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i]) return null
            }
            if (bytes[4].toInt() and 0xFF != VERSION) return null
            val typeCode = bytes[5]
            val type = FrameType.entries.firstOrNull { it.code == typeCode } ?: return null
            val payloadLength = readI32Le(bytes, 6)
            if (payloadLength < 0 || payloadLength != bytes.size - HEADER_SIZE) return null
            val reader = try {
                // payloadLength is a LENGTH; the reader's end offset must be header + length.
                Reader(bytes, HEADER_SIZE, HEADER_SIZE + payloadLength)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return try {
                when (type) {
                    FrameType.FILE_START -> {
                        val transferId = reader.string()
                        val fileId = reader.string()
                        val fileName = reader.string()
                        val totalBytes = reader.i64()
                        val totalChunks = reader.i32()
                        val chunkSize = reader.i32()
                        val hash = reader.fixedString(Sha256.HEX_LENGTH)
                        if (!reader.exhausted()) return null
                        FileStart(
                            transferId = transferId,
                            fileId = fileId,
                            fileName = fileName,
                            totalBytes = totalBytes,
                            totalChunks = totalChunks,
                            chunkSize = chunkSize,
                            fileSha256Hex = hash.lowercase(),
                        )
                    }

                    FrameType.CHUNK -> {
                        val transferId = reader.string()
                        val fileId = reader.string()
                        val index = reader.i32()
                        val dataLength = reader.i32()
                        if (dataLength < 0 || dataLength > MAX_CHUNK_DATA_BYTES) return null
                        val data = reader.bytes(dataLength) ?: return null
                        val hash = reader.bytes(Sha256.RAW_LENGTH) ?: return null
                        if (!reader.exhausted()) return null
                        Chunk(
                            transferId = transferId,
                            fileId = fileId,
                            index = index,
                            data = data,
                            chunkSha256 = hash,
                        )
                    }

                    FrameType.ACK_BATCH -> {
                        val transferId = reader.string()
                        val fileId = reader.string()
                        val count = reader.i32()
                        if (count < 0 || count > MAX_ACK_COUNT) return null
                        if (reader.remaining() != count * 4) return null
                        val indexes = ArrayList<Int>(count)
                        repeat(count) { indexes.add(reader.i32()) }
                        if (!reader.exhausted()) return null
                        AckBatch(transferId = transferId, fileId = fileId, indexes = indexes)
                    }

                    FrameType.COMPLETE -> {
                        val transferId = reader.string()
                        val fileId = reader.string()
                        val verifiedByte = reader.u8() ?: return null
                        if (verifiedByte > 1) return null
                        if (!reader.exhausted()) return null
                        Complete(transferId = transferId, fileId = fileId, verified = verifiedByte == 1)
                    }
                }
            } catch (_: IllegalArgumentException) {
                // Constructor validation rejected wire-supplied values (blank ids, bad sizes...).
                null
            }
        }

        private fun readI32Le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * Little-endian scalar/string writer used by [ChunkFrame.serialize].
 *
 * okio's `writeShortLe`/`writeIntLe`/`writeLongLe` are the little-endian primitives the previous
 * `java.nio.ByteBuffer(ByteOrder.LITTLE_ENDIAN)` scratch provided, so no byte order is hand-rolled
 * here. [buffer] hands the accumulated payload to the caller so the frame header can be prefixed
 * with [Buffer.writeAll], which moves segments rather than copying bytes.
 */
private class PayloadWriter {

    private val out = Buffer()

    fun u8(v: Int) {
        out.writeByte(v and 0xFF)
    }

    fun u16(v: Int) {
        require(v in 0..0xFFFF) { "u16 out of range: $v" }
        out.writeShortLe(v)
    }

    fun i32(v: Int) {
        out.writeIntLe(v)
    }

    fun i64(v: Long) {
        out.writeLongLe(v)
    }

    fun bytes(b: ByteArray) {
        out.write(b)
    }

    fun rawAscii(s: String) {
        require(s.length <= ChunkFrame.MAX_STRING_BYTES) { "string too long: ${s.length}" }
        out.write(asciiBytes(s))
    }

    fun string(s: String) {
        // The intermediate array is deliberate: measuring the encoded length and writing the
        // payload must be the SAME bytes, so the u16 length prefix can never desynchronize from
        // what follows it. `utf8Size(s)` + `writeUtf8(s)` would agree today; a wire format should
        // not depend on two functions agreeing.
        val encoded = Buffer().writeUtf8(s).readByteArray()
        require(encoded.size <= ChunkFrame.MAX_STRING_BYTES) {
            "string too long for framing: ${encoded.size} > ${ChunkFrame.MAX_STRING_BYTES}"
        }
        u16(encoded.size)
        out.write(encoded)
    }

    /** The accumulated payload. Consuming it (e.g. via [Buffer.writeAll]) empties this writer. */
    fun buffer(): Buffer = out
}

/**
 * US-ASCII encoder for the fixed-width hex digest field, replacing
 * `String.toByteArray(Charsets.US_ASCII)` (`Charsets` is JVM-only).
 *
 * The JDK's US-ASCII encoder with `REPLACE` substitutes `'?'` (0x3F) for any character above
 * 0x7F; this reproduces that byte for byte. Callers pass digest hex, which is ASCII by
 * construction, so the substitution is unreachable in practice and exists only to keep the
 * function total.
 *
 * `Sha256.kt` has a private helper doing the same thing. It is duplicated rather than shared
 * because 13B-3a certified that file's byte-identity and this sub-step must not edit it; the two
 * copies are four lines each.
 */
private fun asciiBytes(s: String): ByteArray = ByteArray(s.length) { i ->
    val c = s[i].code
    if (c <= 0x7F) c.toByte() else '?'.code.toByte()
}

/**
 * Cursor over one frame payload; all reads throw [IllegalArgumentException] instead of returning
 * sentinels so [ChunkFrame.parse] can convert any truncation into a plain null.
 */
private class Reader(private val buf: ByteArray, start: Int, end: Int) {

    private var pos = start
    private val limit = end

    init {
        require(start in buf.indices && end <= buf.size && start <= end) { "bad payload bounds" }
    }

    fun exhausted(): Boolean = pos == limit

    fun remaining(): Int = limit - pos

    private fun need(n: Int) {
        if (limit - pos < n) throw IllegalArgumentException("truncated payload")
    }

    fun u8(): Int? {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun i32(): Int {
        need(4)
        var v = 0
        for (b in 3 downTo 0) {
            v = (v shl 8) or (buf[pos + b].toInt() and 0xFF)
        }
        pos += 4
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        for (b in 7 downTo 0) {
            v = (v shl 8) or (buf[pos + b].toLong() and 0xFF)
        }
        pos += 8
        return v
    }

    fun bytes(n: Int): ByteArray? {
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun string(): String {
        need(2)
        val b0 = buf[pos].toInt() and 0xFF
        val b1 = buf[pos + 1].toInt() and 0xFF
        pos += 2
        val len = (b1 shl 8) or b0
        if (len > ChunkFrame.MAX_STRING_BYTES) throw IllegalArgumentException("string too long")
        need(len)
        // ByteString.utf8() substitutes U+FFFD for malformed input, as `String(bytes,
        // Charsets.UTF_8)` did — the golden vectors pin the clean cases and the probe run in
        // 13B-3b confirmed the malformed ones byte for byte.
        val decoded = buf.toByteString(pos, len).utf8()
        pos += len
        return decoded
    }

    /**
     * Strict US-ASCII decode of [n] bytes, replacing `String(bytes, Charsets.US_ASCII)`.
     *
     * The JDK's US-ASCII decoder with `REPLACE` maps every byte >= 0x80 to U+FFFD — verified by
     * measurement in 13B-3b, not assumed: `byteArrayOf(0x41, 0xC3, 0x7F, 0x80)` decodes to
     * `41 efbfbd 7f efbfbd` in UTF-8. Decoding as UTF-8 instead would be wrong here, since it
     * would combine continuation bytes into one replacement char and change the string's length.
     */
    fun fixedString(n: Int): String {
        val raw = bytes(n) ?: throw IllegalArgumentException("truncated fixed string")
        val sb = StringBuilder(raw.size)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            sb.append(if (v <= 0x7F) v.toChar() else REPLACEMENT_CHAR)
        }
        return sb.toString()
    }
}

/**
 * U+FFFD REPLACEMENT CHARACTER, built from its code point rather than written as a literal so the
 * bytes this decoder produces do not depend on the source file's own encoding.
 */
private val REPLACEMENT_CHAR: Char = Char(0xFFFD)
