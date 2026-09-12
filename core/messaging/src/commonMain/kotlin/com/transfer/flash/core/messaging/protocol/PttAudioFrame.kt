package com.transfer.flash.core.messaging.protocol

/**
 * Binary PTT voice frame (ADR-032, Phase 1).
 *
 * Layout, all multi-byte integers little-endian (matches [ChunkFrame] scalar order):
 * ```
 * magic      4B  "PTT1" — disjoint from the transfer pipeline's "FLSH", so audio can be
 *                 routed before it with a cheap pre-check ([isPttAudio]).
 * version    u8  1
 * sessionLen u16 sessionId UTF-8 byte length (<= [MAX_SESSION_ID_BYTES]).
 * sessionId  N   UTF-8 session UUID.
 * seq        u32 packet sequence within the session (monotonic; wraps are compared
 *                 ordinally by the jitter buffer, which only needs ordering + gaps).
 * captureTs  u64 sender capture clock in ms (scheduling + age stats, never a wall clock).
 * pcm        ..  LE int16 mono samples ([MAX_PCM_BYTES] cap).
 * ```
 * Sample rate / packet duration are NOT carried per packet: the `start` control frame
 * fixes them for the session, and repeating them per 20 ms packet would be pure overhead.
 */
public data class PttAudioFrame(
    val sessionId: String,
    val seq: Long,
    val captureTsMs: Long,
    val pcm: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PttAudioFrame) return false
        return sessionId == other.sessionId && seq == other.seq &&
            captureTsMs == other.captureTsMs && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + captureTsMs.hashCode()
        result = 31 * result + pcm.contentHashCode()
        return result
    }

    public companion object {
        public const val VERSION: Int = 1
        public const val MAX_SESSION_ID_BYTES: Int = 128
        public const val MAX_PCM_BYTES: Int = 4096

        private val MAGIC: ByteArray = byteArrayOf(0x50, 0x54, 0x54, 0x31) // "PTT1"

        /** PCM16 mono payload size fixed by the Start frame, or null for an invalid format. */
        public fun expectedPcmBytes(sampleRateHz: Int, packetMs: Int): Int? {
            if (sampleRateHz !in PttSessionCodec.ALLOWED_RATES) return null
            if (packetMs !in PttSessionCodec.ALLOWED_PACKET_MS) return null
            return sampleRateHz * packetMs / 1000 * 2
        }

        /** True only when this packet matches the format negotiated for its session. */
        public fun hasExpectedPcmSize(
            frame: PttAudioFrame,
            sampleRateHz: Int,
            packetMs: Int,
        ): Boolean = frame.pcm.size == expectedPcmBytes(sampleRateHz, packetMs)

        /** Cheap pre-check for the inbound binary branch: magic only, no allocation. */
        public fun isPttAudio(data: ByteArray): Boolean {
            if (data.size < MAGIC.size + 1) return false
            for (i in MAGIC.indices) {
                if (data[i] != MAGIC[i]) return false
            }
            return true
        }

        public fun encode(frame: PttAudioFrame): ByteArray {
            val sessionBytes = frame.sessionId.encodeToByteArray()
            require(frame.sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(sessionBytes.size <= MAX_SESSION_ID_BYTES) { "sessionId too long" }
            require(frame.pcm.isNotEmpty() && frame.pcm.size % 2 == 0) { "pcm must be non-empty PCM16" }
            require(frame.pcm.size <= MAX_PCM_BYTES) { "pcm too large" }
            require(frame.seq >= 0) { "seq must be non-negative" }
            val out = ByteArray(MAGIC.size + 1 + 2 + sessionBytes.size + 4 + 8 + frame.pcm.size)
            var pos = 0
            MAGIC.copyInto(out, pos); pos += MAGIC.size
            out[pos++] = VERSION.toByte()
            putU16Le(out, pos, sessionBytes.size); pos += 2
            sessionBytes.copyInto(out, pos); pos += sessionBytes.size
            putU32Le(out, pos, frame.seq); pos += 4
            putU64Le(out, pos, frame.captureTsMs); pos += 8
            frame.pcm.copyInto(out, pos)
            return out
        }

        /** Decodes only when the PCM payload has the caller's negotiated byte count. */
        public fun decodeExpectedSize(data: ByteArray, expectedPcmBytes: Int): PttAudioFrame? {
            if (expectedPcmBytes <= 0 || expectedPcmBytes > MAX_PCM_BYTES) return null
            val frame = decode(data) ?: return null
            return frame.takeIf { it.pcm.size == expectedPcmBytes }
        }

        /** Returns null for wrong magic/version, truncation, or out-of-range lengths. */
        public fun decode(data: ByteArray): PttAudioFrame? {
            if (!isPttAudio(data)) return null
            var pos = MAGIC.size
            if (data[pos++].toInt() != VERSION) return null
            if (data.size < pos + 2) return null
            val sessionLen = getU16Le(data, pos); pos += 2
            if (sessionLen <= 0 || sessionLen > MAX_SESSION_ID_BYTES) return null
            if (data.size < pos + sessionLen + 4 + 8) return null
            val sessionId = try {
                data.decodeToString(pos, pos + sessionLen)
            } catch (_: Exception) {
                return null
            }
            pos += sessionLen
            val seq = getU32Le(data, pos); pos += 4
            val captureTs = getU64Le(data, pos); pos += 8
            if (sessionId.isBlank()) return null
            val pcm = data.copyOfRange(pos, data.size)
            if (pcm.isEmpty() || pcm.size > MAX_PCM_BYTES || pcm.size % 2 != 0) return null
            return PttAudioFrame(sessionId, seq, captureTs, pcm)
        }

        private fun putU16Le(out: ByteArray, pos: Int, value: Int) {
            out[pos] = (value and 0xFF).toByte()
            out[pos + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        private fun putU32Le(out: ByteArray, pos: Int, value: Long) {
            out[pos] = (value and 0xFF).toByte()
            out[pos + 1] = ((value ushr 8) and 0xFF).toByte()
            out[pos + 2] = ((value ushr 16) and 0xFF).toByte()
            out[pos + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        private fun putU64Le(out: ByteArray, pos: Int, value: Long) {
            for (i in 0 until 8) {
                out[pos + i] = ((value ushr (8 * i)) and 0xFF).toByte()
            }
        }

        private fun getU16Le(data: ByteArray, pos: Int): Int =
            (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)

        private fun getU32Le(data: ByteArray, pos: Int): Long {
            var value = 0L
            for (i in 0 until 4) {
                value = value or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
            }
            return value
        }

        private fun getU64Le(data: ByteArray, pos: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = value or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
            }
            return value
        }
    }
}
