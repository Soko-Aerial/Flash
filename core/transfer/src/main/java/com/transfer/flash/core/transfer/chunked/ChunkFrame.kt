package com.transfer.flash.core.transfer.chunked

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Framing v2 wire types for chunked, resumable transfers (C5.3), self-contained binary format.
 *
 * These frames travel as `FlashEnvelope` payloads (version negotiated per [com.transfer.flash.core.common.protocol.FlashProtocol]
 * assert-on-handshake) but are **binary**, not JSON: CHUNK payloads are up to 256 KB of opaque
 * bytes and must not pay base64/JSON-escape overhead. The layout below is the single source of
 * truth; the Windows/Linux client and the future Rust bridge implement from this doc.
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
            val body = payload.toByteArray()
            val out = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.LITTLE_ENDIAN)
            out.put(MAGIC)
            out.put(VERSION.toByte())
            out.put(frame.type.code)
            out.putInt(body.size)
            out.put(body)
            return out.array()
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

/** Little-endian scalar/string writer used by [ChunkFrame.serialize]. */
private class PayloadWriter {

    private val out = ByteArrayOutputStream()
    private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(v: Int) = out.write(v and 0xFF)

    fun u16(v: Int) {
        require(v in 0..0xFFFF) { "u16 out of range: $v" }
        scratch.clear()
        scratch.putShort(v.toShort())
        writeScratch(2)
    }

    fun i32(v: Int) {
        scratch.clear()
        scratch.putInt(v)
        writeScratch(4)
    }

    fun i64(v: Long) {
        scratch.clear()
        scratch.putLong(v)
        writeScratch(8)
    }

    fun bytes(b: ByteArray) = out.write(b)

    fun rawAscii(s: String) {
        require(s.length <= ChunkFrame.MAX_STRING_BYTES) { "string too long: ${s.length}" }
        out.write(s.toByteArray(Charsets.US_ASCII))
    }

    fun string(s: String) {
        val encoded = s.toByteArray(Charsets.UTF_8)
        require(encoded.size <= ChunkFrame.MAX_STRING_BYTES) {
            "string too long for framing: ${encoded.size} > ${ChunkFrame.MAX_STRING_BYTES}"
        }
        u16(encoded.size)
        out.write(encoded)
    }

    private fun writeScratch(n: Int) {
        out.write(scratch.array(), 0, n)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
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
        val raw = bytes(len) ?: throw IllegalArgumentException("truncated string")
        return String(raw, Charsets.UTF_8)
    }

    fun fixedString(n: Int): String {
        val raw = bytes(n) ?: throw IllegalArgumentException("truncated fixed string")
        return String(raw, Charsets.US_ASCII)
    }
}
