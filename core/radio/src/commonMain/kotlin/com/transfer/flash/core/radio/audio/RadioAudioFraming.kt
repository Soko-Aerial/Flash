package com.transfer.flash.core.radio.audio

/**
 * Framing of the radio's audio channel: `0x7E`-delimited frames, `0x7D` escapes the next byte (`byte ^ 0x20`), and the
 * first byte inside a frame is the command. Verified against HTCommander's `audio_engine.dart` (Apache-2.0); see
 * `docs/network/RADIO-AUDIO.md`.
 */
internal object RadioAudioFraming {
    const val DELIMITER: Int = 0x7E
    const val ESCAPE: Int = 0x7D

    /** Received audio (SBC frames follow). The radio uses both values. */
    const val CMD_AUDIO: Int = 0x00
    const val CMD_AUDIO_ALT: Int = 0x03

    /** End of a transmission (either direction). */
    const val CMD_END: Int = 0x01
    const val CMD_ACK: Int = 0x02

    /** Echo of our own transmission; metering only, never played. */
    const val CMD_TX_ECHO: Int = 0x09

    /** Largest unescaped frame we accept; anything longer is dropped and counted. */
    const val MAX_FRAME: Int = 4096

    /** `7E 01 00 01 00 00 00 00 00 00 7E`: the END marker HTCommander sends after the last audio packet. */
    val END_FRAME: ByteArray = byteArrayOf(0x7E, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7E)

    /** `7E cmd escaped(payload) 7E`. */
    fun encode(command: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): ByteArray {
        var extra = 0
        for (i in offset until offset + length) {
            val b = payload[i].toInt() and 0xFF
            if (b == DELIMITER || b == ESCAPE) extra++
        }
        val cmdEscaped = command == DELIMITER || command == ESCAPE
        val out = ByteArray(3 + (if (cmdEscaped) 1 else 0) + length + extra)
        var p = 0
        out[p++] = DELIMITER.toByte()
        if (cmdEscaped) {
            out[p++] = ESCAPE.toByte()
            out[p++] = (command xor 0x20).toByte()
        } else {
            out[p++] = command.toByte()
        }
        for (i in offset until offset + length) {
            val b = payload[i].toInt() and 0xFF
            if (b == DELIMITER || b == ESCAPE) {
                out[p++] = ESCAPE.toByte()
                out[p++] = (b xor 0x20).toByte()
            } else {
                out[p++] = b.toByte()
            }
        }
        out[p] = DELIMITER.toByte()
        return out
    }
}

/** One decoded frame: [command] and its (unescaped) [payload]. */
internal class AudioFrame(val command: Int, val payload: ByteArray)

/** Streaming decoder: feed raw bytes, get whole frames. Bytes before the first delimiter are ignored. */
internal class AudioFrameDecoder(private val onFrame: (AudioFrame) -> Unit) {
    private val buf = ByteArray(RadioAudioFraming.MAX_FRAME)
    private var len = 0
    private var inFrame = false
    private var escaped = false
    private var overflow = false

    /** Frames dropped because they were too long. */
    var dropped: Int = 0
        private set

    fun reset() {
        len = 0; inFrame = false; escaped = false; overflow = false
    }

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            if (b == RadioAudioFraming.DELIMITER) {
                if (inFrame && len > 0 && !overflow) {
                    onFrame(AudioFrame(buf[0].toInt() and 0xFF, buf.copyOfRange(1, len)))
                } else if (inFrame && overflow) {
                    dropped++
                }
                len = 0; escaped = false; overflow = false; inFrame = true
                continue
            }
            if (!inFrame) continue
            var v = b
            if (escaped) {
                v = b xor 0x20; escaped = false
            } else if (b == RadioAudioFraming.ESCAPE) {
                escaped = true; continue
            }
            if (len >= buf.size) overflow = true else buf[len++] = v.toByte()
        }
    }
}
