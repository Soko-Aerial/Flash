package com.transfer.flash.core.swarm.codec

import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import okio.Buffer

public sealed interface DecodeResult {
    public data class Decoded(public val frame: SwarmFrame) : DecodeResult
    public data class Unknown(public val typeCode: Int, public val version: Int) : DecodeResult
    public data class Malformed(public val reason: String) : DecodeResult
}

/**
 * Binary codec for all 12 FSW1 frames (ADR-071, protocol §5.3, SW-3).
 *
 * All multi-byte integers are little-endian.
 * Decoding never throws exceptions; any violation produces [DecodeResult.Malformed].
 */
public object SwarmFrameCodec {
    public const val MAGIC: String = "FSWM_FRAME"
    public const val VERSION: Int = 1
    public const val ENVELOPE_HEADER_SIZE: Int = 12

    public const val MAX_GROUP_ID_BYTES: Int = 64
    public const val MAX_DEVICE_ID_BYTES: Int = 128
    public const val MAX_MESSAGE_ID_BYTES: Int = 128
    public const val MAX_SIGNATURE_BYTES: Int = 128

    public const val MAX_SUMMARY_TOMBSTONES: Int = 64
    public const val MAX_SUMMARY_ENTRIES: Int = 256
    public const val MAX_HAVE_RANGES: Int = 1024
    public const val MAX_REQUEST_PIECES: Int = 64
    public const val MAX_REJECT_PIECES: Int = 64
    public const val MAX_UNREQUEST_PIECES: Int = 64
    public const val MAX_MANIFEST_FRAGMENTS: Int = 9
    public const val MAX_PIECE_DATA_BYTES: Int = 1024 * 1024 // 1 MiB

    private val MAGIC_BYTES = byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte())

    public fun encode(frame: SwarmFrame): ByteArray {
        val body = Buffer()
        when (frame) {
            is SwarmFrame.Summary -> encodeSummary(body, frame)
            is SwarmFrame.ManifestGet -> {
                require(frame.fragmentIndex in 0 until MAX_MANIFEST_FRAGMENTS) {
                    "fragmentIndex ${frame.fragmentIndex} not in 0 until $MAX_MANIFEST_FRAGMENTS"
                }
                encodeCommonPrefix(body, frame.groupId, frame.root)
                body.writeShortLe(frame.fragmentIndex)
            }
            is SwarmFrame.ManifestPart -> {
                require(frame.fragmentCount in 1..MAX_MANIFEST_FRAGMENTS) {
                    "fragmentCount ${frame.fragmentCount} not in 1..$MAX_MANIFEST_FRAGMENTS"
                }
                require(frame.fragmentIndex in 0 until frame.fragmentCount) {
                    "fragmentIndex ${frame.fragmentIndex} not in 0 until ${frame.fragmentCount}"
                }
                require(frame.bytes.size in 1..65536) {
                    "ManifestPart bytes ${frame.bytes.size} not in 1..65536"
                }
                encodeCommonPrefix(body, frame.groupId, frame.root)
                body.writeShortLe(frame.fragmentIndex)
                body.writeShortLe(frame.fragmentCount)
                body.writeIntLe(frame.bytes.size)
                body.write(frame.bytes)
            }
            is SwarmFrame.Have -> {
                require(frame.ranges.size in 1..MAX_HAVE_RANGES) {
                    "ranges size ${frame.ranges.size} not in 1..$MAX_HAVE_RANGES"
                }
                encodeCommonPrefix(body, frame.groupId, frame.root)
                body.writeShortLe(frame.ranges.size)
                for (r in frame.ranges) {
                    body.writeIntLe(r.start)
                    body.writeIntLe(r.count)
                }
            }
            is SwarmFrame.HaveAll -> {
                encodeCommonPrefix(body, frame.groupId, frame.root)
            }
            is SwarmFrame.Request -> {
                require(frame.pieces.size in 1..MAX_REQUEST_PIECES) {
                    "pieces size ${frame.pieces.size} not in 1..$MAX_REQUEST_PIECES"
                }
                encodeCommonPrefix(body, frame.groupId, frame.root)
                body.writeByte(frame.pieces.size)
                for (p in frame.pieces) {
                    require(p in 0 until PieceMath.MAX_PIECE_COUNT) {
                        "piece index $p not in 0 until ${PieceMath.MAX_PIECE_COUNT}"
                    }
                    body.writeIntLe(p)
                }
            }
            is SwarmFrame.Piece -> {
                require(frame.index in 0 until PieceMath.MAX_PIECE_COUNT) {
                    "piece index ${frame.index} not in 0 until ${PieceMath.MAX_PIECE_COUNT}"
                }
                require(frame.bytes.size in 1..MAX_PIECE_DATA_BYTES) {
                    "piece bytes ${frame.bytes.size} not in 1..$MAX_PIECE_DATA_BYTES"
                }
                encodeCommonPrefix(body, frame.groupId, frame.root)
                body.writeIntLe(frame.index)
                body.writeIntLe(frame.bytes.size)
                body.write(frame.bytes)
            }
            is SwarmFrame.Reject -> {
                require(frame.pieces.size <= MAX_REJECT_PIECES) {
                    "pieces size ${frame.pieces.size} > $MAX_REJECT_PIECES"
                }
                if (frame.scopeAll) {
                    require(frame.pieces.isEmpty()) { "Reject pieces must be empty when scopeAll=true" }
                }
                encodeCommonPrefix(body, frame.groupId, frame.root)
                body.writeByte(frame.reason.wireValue)
                body.writeIntLe(frame.retryAfterMs.toInt())
                body.writeByte(if (frame.scopeAll) 1 else 0)
                body.writeByte(frame.pieces.size)
                for (p in frame.pieces) {
                    require(p in 0 until PieceMath.MAX_PIECE_COUNT) {
                        "piece index $p not in 0 until ${PieceMath.MAX_PIECE_COUNT}"
                    }
                    body.writeIntLe(p)
                }
            }
            is SwarmFrame.Cancel -> {
                encodeCommonPrefix(body, frame.groupId, frame.root)
                encodeStr(body, frame.originId, MAX_DEVICE_ID_BYTES)
                encodeStr(body, frame.messageId, MAX_MESSAGE_ID_BYTES)
                body.writeByte(frame.reason.wireValue)
                body.writeLongLe(frame.cancelledAtMs)
                encodeSig(body, frame.signature)
            }
            is SwarmFrame.CancelAck -> {
                encodeCommonPrefix(body, frame.groupId, frame.root)
                encodeStr(body, frame.messageId, MAX_MESSAGE_ID_BYTES)
            }
            is SwarmFrame.SourceStatus -> {
                encodeCommonPrefix(body, frame.groupId, frame.root)
                encodeStr(body, frame.originId, MAX_DEVICE_ID_BYTES)
                encodeStr(body, frame.messageId, MAX_MESSAGE_ID_BYTES)
                body.writeByte(frame.status.wireValue)
                body.writeByte(frame.reason.wireValue)
                body.writeLongLe(frame.atMs)
                encodeSig(body, frame.signature)
            }
            is SwarmFrame.Unrequest -> {
                require(frame.pieces.size in 1..MAX_UNREQUEST_PIECES) {
                    "pieces size ${frame.pieces.size} not in 1..$MAX_UNREQUEST_PIECES"
                }
                encodeCommonPrefix(body, frame.groupId, frame.root)
                body.writeByte(frame.pieces.size)
                for (p in frame.pieces) {
                    require(p in 0 until PieceMath.MAX_PIECE_COUNT) {
                        "piece index $p not in 0 until ${PieceMath.MAX_PIECE_COUNT}"
                    }
                    body.writeIntLe(p)
                }
            }
        }

        val out = Buffer()
        out.write(MAGIC_BYTES)
        out.writeByte(VERSION)
        out.writeByte(frame.typeCode)
        out.writeShortLe(0) // flags
        out.writeIntLe(body.size.toInt())
        out.writeAll(body)
        return out.readByteArray()
    }

    public fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < ENVELOPE_HEADER_SIZE) {
            return DecodeResult.Malformed("Frame too short for envelope: ${bytes.size} < $ENVELOPE_HEADER_SIZE")
        }
        val buffer = Buffer().write(bytes)
        val magic = buffer.readByteArray(4)
        if (!magic.contentEquals(MAGIC_BYTES)) {
            return DecodeResult.Malformed("Invalid FSW1 magic: ${magic.decodeToString()}")
        }
        val version = buffer.readByte().toInt() and 0xFF
        val typeCode = buffer.readByte().toInt() and 0xFF
        val flags = buffer.readShortLe().toInt() and 0xFFFF
        val bodyLength = buffer.readIntLe().toLong() and 0xFFFFFFFFL

        if (bytes.size.toLong() != ENVELOPE_HEADER_SIZE + bodyLength) {
            return DecodeResult.Malformed(
                "bodyLength $bodyLength does not match remaining frame bytes: ${bytes.size - ENVELOPE_HEADER_SIZE}",
            )
        }

        if (version != VERSION) {
            return DecodeResult.Unknown(typeCode = typeCode, version = version)
        }

        if (typeCode !in 1..12) {
            return DecodeResult.Unknown(typeCode = typeCode, version = version)
        }

        return try {
            val frame = when (typeCode) {
                1 -> decodeSummary(buffer)
                2 -> decodeManifestGet(buffer)
                3 -> decodeManifestPart(buffer)
                4 -> decodeHave(buffer)
                5 -> decodeHaveAll(buffer)
                6 -> decodeRequest(buffer)
                7 -> decodePiece(buffer)
                8 -> decodeReject(buffer)
                9 -> decodeCancel(buffer)
                10 -> decodeCancelAck(buffer)
                11 -> decodeSourceStatus(buffer)
                12 -> decodeUnrequest(buffer)
                else -> return DecodeResult.Unknown(typeCode = typeCode, version = version)
            }
            if (!buffer.exhausted()) {
                return DecodeResult.Malformed("Trailing bytes in frame body: ${buffer.size} bytes remaining")
            }
            DecodeResult.Decoded(frame)
        } catch (t: Throwable) {
            DecodeResult.Malformed("Malformed body for type $typeCode: ${t.message}")
        }
    }

    private fun encodeCommonPrefix(buffer: Buffer, groupId: String, root: ContentRoot) {
        encodeStr(buffer, groupId, MAX_GROUP_ID_BYTES)
        buffer.write(root.toByteArray())
    }

    private fun encodeStr(buffer: Buffer, value: String, maxBytes: Int) {
        val b = value.encodeToByteArray()
        require(b.size <= maxBytes) { "String bytes ${b.size} exceeds limit $maxBytes" }
        buffer.writeShortLe(b.size)
        buffer.write(b)
    }

    private fun encodeSig(buffer: Buffer, signature: ByteArray) {
        require(signature.size <= MAX_SIGNATURE_BYTES) {
            "Signature bytes ${signature.size} exceeds limit $MAX_SIGNATURE_BYTES"
        }
        buffer.writeShortLe(signature.size)
        buffer.write(signature)
    }

    private fun decodeStr(buffer: Buffer, maxBytes: Int): String {
        val len = buffer.readShortLe().toInt() and 0xFFFF
        if (len > maxBytes) throw IllegalArgumentException("String length $len exceeds limit $maxBytes")
        val b = buffer.readByteArray(len.toLong())
        return b.decodeToString(throwOnInvalidSequence = true)
    }

    private fun decodeSig(buffer: Buffer): ByteArray {
        val len = buffer.readShortLe().toInt() and 0xFFFF
        if (len > MAX_SIGNATURE_BYTES) throw IllegalArgumentException("Signature length $len exceeds limit $MAX_SIGNATURE_BYTES")
        return buffer.readByteArray(len.toLong())
    }

    private fun decodeCommonPrefix(buffer: Buffer): Pair<String, ContentRoot> {
        val groupId = decodeStr(buffer, MAX_GROUP_ID_BYTES)
        val rootBytes = buffer.readByteArray(32)
        return Pair(groupId, ContentRoot.fromBytes(rootBytes))
    }

    private fun encodeSummary(buffer: Buffer, frame: SwarmFrame.Summary) {
        encodeStr(buffer, frame.groupId, MAX_GROUP_ID_BYTES)
        require(frame.tombstones.size <= MAX_SUMMARY_TOMBSTONES) {
            "tombstones count ${frame.tombstones.size} exceeds limit $MAX_SUMMARY_TOMBSTONES"
        }
        buffer.writeShortLe(frame.tombstones.size)
        for (t in frame.tombstones) {
            buffer.write(t.root.toByteArray())
            encodeStr(buffer, t.originId, MAX_DEVICE_ID_BYTES)
            encodeStr(buffer, t.messageId, MAX_MESSAGE_ID_BYTES)
            buffer.writeByte(t.reason.wireValue)
            buffer.writeLongLe(t.cancelledAtMs)
            encodeSig(buffer, t.signature)
        }
        require(frame.entries.size <= MAX_SUMMARY_ENTRIES) {
            "entries count ${frame.entries.size} exceeds limit $MAX_SUMMARY_ENTRIES"
        }
        buffer.writeShortLe(frame.entries.size)
        for (e in frame.entries) {
            buffer.write(e.root.toByteArray())
            buffer.writeByte(e.state.wireValue)
            buffer.writeByte(if (e.servingEnabled) 1 else 0)
        }
    }

    private fun decodeSummary(buffer: Buffer): SwarmFrame.Summary {
        val groupId = decodeStr(buffer, MAX_GROUP_ID_BYTES)
        val tombCount = buffer.readShortLe().toInt() and 0xFFFF
        if (tombCount > MAX_SUMMARY_TOMBSTONES) {
            throw IllegalArgumentException("tombCount $tombCount exceeds limit $MAX_SUMMARY_TOMBSTONES")
        }
        val tombstones = ArrayList<SwarmTombstone>(tombCount)
        for (i in 0 until tombCount) {
            val rootBytes = buffer.readByteArray(32)
            val root = ContentRoot.fromBytes(rootBytes)
            val originId = decodeStr(buffer, MAX_DEVICE_ID_BYTES)
            val messageId = decodeStr(buffer, MAX_MESSAGE_ID_BYTES)
            val reasonWire = buffer.readByte().toInt() and 0xFF
            val reason = SwarmTombstoneReason.fromWire(reasonWire)
                ?: throw IllegalArgumentException("Unknown tombstone reason: $reasonWire")
            val cancelledAtMs = buffer.readLongLe()
            val signature = decodeSig(buffer)
            tombstones.add(
                SwarmTombstone(
                    groupId = groupId,
                    root = root,
                    originId = originId,
                    messageId = messageId,
                    reason = reason,
                    cancelledAtMs = cancelledAtMs,
                    signature = signature,
                ),
            )
        }

        val entryCount = buffer.readShortLe().toInt() and 0xFFFF
        if (entryCount > MAX_SUMMARY_ENTRIES) {
            throw IllegalArgumentException("entryCount $entryCount exceeds limit $MAX_SUMMARY_ENTRIES")
        }
        val entries = ArrayList<SwarmFrame.Summary.Entry>(entryCount)
        for (i in 0 until entryCount) {
            val rootBytes = buffer.readByteArray(32)
            val root = ContentRoot.fromBytes(rootBytes)
            val stateWire = buffer.readByte().toInt() and 0xFF
            val state = SwarmContentState.fromWire(stateWire)
                ?: throw IllegalArgumentException("Unknown content state: $stateWire")
            val entryFlags = buffer.readByte().toInt() and 0xFF
            val servingEnabled = (entryFlags and 1) != 0
            entries.add(SwarmFrame.Summary.Entry(root, state, servingEnabled))
        }

        return SwarmFrame.Summary(groupId, tombstones, entries)
    }

    private fun decodeManifestGet(buffer: Buffer): SwarmFrame.ManifestGet {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val fragmentIndex = buffer.readShortLe().toInt() and 0xFFFF
        if (fragmentIndex >= MAX_MANIFEST_FRAGMENTS) {
            throw IllegalArgumentException("fragmentIndex $fragmentIndex >= $MAX_MANIFEST_FRAGMENTS")
        }
        return SwarmFrame.ManifestGet(groupId, root, fragmentIndex)
    }

    private fun decodeManifestPart(buffer: Buffer): SwarmFrame.ManifestPart {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val fragmentIndex = buffer.readShortLe().toInt() and 0xFFFF
        val fragmentCount = buffer.readShortLe().toInt() and 0xFFFF
        if (fragmentCount !in 1..MAX_MANIFEST_FRAGMENTS) {
            throw IllegalArgumentException("fragmentCount $fragmentCount not in 1..$MAX_MANIFEST_FRAGMENTS")
        }
        if (fragmentIndex !in 0 until fragmentCount) {
            throw IllegalArgumentException("fragmentIndex $fragmentIndex not in 0 until $fragmentCount")
        }
        val length = buffer.readIntLe()
        if (length !in 1..65536) {
            throw IllegalArgumentException("ManifestPart length $length not in 1..65536")
        }
        val bytes = buffer.readByteArray(length.toLong())
        return SwarmFrame.ManifestPart(groupId, root, fragmentIndex, fragmentCount, bytes)
    }

    private fun decodeHave(buffer: Buffer): SwarmFrame.Have {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val rangeCount = buffer.readShortLe().toInt() and 0xFFFF
        if (rangeCount !in 1..MAX_HAVE_RANGES) {
            throw IllegalArgumentException("rangeCount $rangeCount not in 1..$MAX_HAVE_RANGES")
        }
        val ranges = ArrayList<PieceRange>(rangeCount)
        var lastEnd = -1L
        for (i in 0 until rangeCount) {
            val start = buffer.readIntLe()
            val count = buffer.readIntLe()
            if (start < 0 || count <= 0) {
                throw IllegalArgumentException("Invalid range: start=$start, count=$count")
            }
            if (start.toLong() <= lastEnd) {
                throw IllegalArgumentException("Ranges not sorted/overlapping: start=$start <= lastEnd=$lastEnd")
            }
            val end = start.toLong() + count.toLong()
            if (end > PieceMath.MAX_PIECE_COUNT) {
                throw IllegalArgumentException("Range end $end exceeds MAX_PIECE_COUNT")
            }
            ranges.add(PieceRange(start, count))
            lastEnd = end - 1L
        }
        return SwarmFrame.Have(groupId, root, ranges)
    }

    private fun decodeHaveAll(buffer: Buffer): SwarmFrame.HaveAll {
        val (groupId, root) = decodeCommonPrefix(buffer)
        return SwarmFrame.HaveAll(groupId, root)
    }

    private fun decodeRequest(buffer: Buffer): SwarmFrame.Request {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val count = buffer.readByte().toInt() and 0xFF
        if (count !in 1..MAX_REQUEST_PIECES) {
            throw IllegalArgumentException("Request count $count not in 1..$MAX_REQUEST_PIECES")
        }
        val pieces = ArrayList<Int>(count)
        val seen = HashSet<Int>(count)
        for (i in 0 until count) {
            val index = buffer.readIntLe()
            if (index !in 0 until PieceMath.MAX_PIECE_COUNT) {
                throw IllegalArgumentException("Piece index $index not in 0 until ${PieceMath.MAX_PIECE_COUNT}")
            }
            if (!seen.add(index)) throw IllegalArgumentException("Duplicate piece index: $index")
            pieces.add(index)
        }
        return SwarmFrame.Request(groupId, root, pieces)
    }

    private fun decodePiece(buffer: Buffer): SwarmFrame.Piece {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val index = buffer.readIntLe()
        if (index !in 0 until PieceMath.MAX_PIECE_COUNT) {
            throw IllegalArgumentException("Piece index $index not in 0 until ${PieceMath.MAX_PIECE_COUNT}")
        }
        val length = buffer.readIntLe()
        if (length !in 1..MAX_PIECE_DATA_BYTES) {
            throw IllegalArgumentException("Piece length $length not in 1..$MAX_PIECE_DATA_BYTES")
        }
        val bytes = buffer.readByteArray(length.toLong())
        return SwarmFrame.Piece(groupId, root, index, bytes)
    }

    private fun decodeReject(buffer: Buffer): SwarmFrame.Reject {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val reasonWire = buffer.readByte().toInt() and 0xFF
        val reason = SwarmRejectReason.fromWire(reasonWire)
        val retryAfterMs = buffer.readIntLe().toLong() and 0xFFFFFFFFL
        val scopeByte = buffer.readByte().toInt() and 0xFF
        val scopeAll = scopeByte != 0
        val count = buffer.readByte().toInt() and 0xFF
        if (count > MAX_REJECT_PIECES) {
            throw IllegalArgumentException("Reject count $count > $MAX_REJECT_PIECES")
        }
        if (scopeAll && count != 0) {
            throw IllegalArgumentException("Reject count must be 0 when scopeAll=true, got $count")
        }
        val pieces = ArrayList<Int>(count)
        for (i in 0 until count) {
            val idx = buffer.readIntLe()
            if (idx !in 0 until PieceMath.MAX_PIECE_COUNT) {
                throw IllegalArgumentException("Piece index $idx not in 0 until ${PieceMath.MAX_PIECE_COUNT}")
            }
            pieces.add(idx)
        }
        return SwarmFrame.Reject(groupId, root, reason, retryAfterMs, scopeAll, pieces)
    }

    private fun decodeCancel(buffer: Buffer): SwarmFrame.Cancel {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val originId = decodeStr(buffer, MAX_DEVICE_ID_BYTES)
        val messageId = decodeStr(buffer, MAX_MESSAGE_ID_BYTES)
        val reasonWire = buffer.readByte().toInt() and 0xFF
        val reason = SwarmTombstoneReason.fromWire(reasonWire)
            ?: throw IllegalArgumentException("Unknown tombstone reason: $reasonWire")
        val cancelledAtMs = buffer.readLongLe()
        val signature = decodeSig(buffer)
        if (originId.isEmpty()) throw IllegalArgumentException("originId must not be empty")
        if (messageId.isEmpty()) throw IllegalArgumentException("messageId must not be empty")
        if (cancelledAtMs <= 0) throw IllegalArgumentException("cancelledAtMs must be > 0")
        if (signature.isEmpty()) throw IllegalArgumentException("signature must not be empty")
        return SwarmFrame.Cancel(groupId, root, originId, messageId, reason, cancelledAtMs, signature)
    }

    private fun decodeCancelAck(buffer: Buffer): SwarmFrame.CancelAck {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val messageId = decodeStr(buffer, MAX_MESSAGE_ID_BYTES)
        return SwarmFrame.CancelAck(groupId, root, messageId)
    }

    private fun decodeSourceStatus(buffer: Buffer): SwarmFrame.SourceStatus {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val originId = decodeStr(buffer, MAX_DEVICE_ID_BYTES)
        val messageId = decodeStr(buffer, MAX_MESSAGE_ID_BYTES)
        val statusWire = buffer.readByte().toInt() and 0xFF
        val status = SourceState.fromWire(statusWire)
            ?: throw IllegalArgumentException("Unknown source status: $statusWire")
        val reasonWire = buffer.readByte().toInt() and 0xFF
        val reason = SourceReason.fromWire(reasonWire)
            ?: throw IllegalArgumentException("Unknown source reason: $reasonWire")
        val atMs = buffer.readLongLe()
        val signature = decodeSig(buffer)
        if (originId.isEmpty()) throw IllegalArgumentException("originId must not be empty")
        if (messageId.isEmpty()) throw IllegalArgumentException("messageId must not be empty")
        if (atMs <= 0) throw IllegalArgumentException("atMs must be > 0")
        if (signature.isEmpty()) throw IllegalArgumentException("signature must not be empty")
        return SwarmFrame.SourceStatus(groupId, root, originId, messageId, status, reason, atMs, signature)
    }

    private fun decodeUnrequest(buffer: Buffer): SwarmFrame.Unrequest {
        val (groupId, root) = decodeCommonPrefix(buffer)
        val count = buffer.readByte().toInt() and 0xFF
        if (count !in 1..MAX_UNREQUEST_PIECES) {
            throw IllegalArgumentException("Unrequest count $count not in 1..$MAX_UNREQUEST_PIECES")
        }
        val pieces = ArrayList<Int>(count)
        for (i in 0 until count) {
            val idx = buffer.readIntLe()
            if (idx !in 0 until PieceMath.MAX_PIECE_COUNT) {
                throw IllegalArgumentException("Piece index $idx not in 0 until ${PieceMath.MAX_PIECE_COUNT}")
            }
            pieces.add(idx)
        }
        return SwarmFrame.Unrequest(groupId, root, pieces)
    }
}
