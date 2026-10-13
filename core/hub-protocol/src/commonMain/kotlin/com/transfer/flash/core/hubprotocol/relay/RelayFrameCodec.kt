package com.transfer.flash.core.hubprotocol.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one encoder/decoder for relay frames, used by the hub and by every client (SEC-15: strict
 * `kotlinx.serialization`, no regex JSON). Never throws on decode; throws [IllegalArgumentException] on an encode that
 * would violate a limit, because that is a programming error on the sending side.
 *
 * Limits enforced on both directions:
 * - the whole frame is at most [HubContract.MAX_FRAME_BYTES] UTF-8 bytes (a frame that has not completed the handshake
 *   is limited to [HubContract.MAX_HANDSHAKE_FRAME_BYTES], via the `maxBytes` argument);
 * - a `payload` is at most [HubContract.MAX_PAYLOAD_BYTES] UTF-8 bytes;
 * - identifiers are non-blank and at most [HubContract.MAX_ID_LENGTH] chars; names at most [HubContract.MAX_NAME_LENGTH].
 */
@FlashInternalApi
public object RelayFrameCodec {

    public const val PAD_BOUNDARY_256: Int = 256
    public const val PAD_BOUNDARY_512: Int = 512

    /** Why a frame was not accepted. */
    public enum class RejectReason { TOO_LARGE, MALFORMED, UNKNOWN_TYPE, INVALID_FIELD, PAYLOAD_TOO_LARGE, UNSUPPORTED_VERSION }

    /** Result of [decodeResult]. */
    public sealed interface Decoded {
        public data class Ok(public val frame: RelayFrame) : Decoded
        public data class Rejected(public val reason: RejectReason, public val detail: String = "") : Decoded
    }

    /** Encodes [frame] to its wire text. [RelayFrame.Meta] frames are padded to 256 / 512 bytes (P-REF-1). */
    public fun encode(frame: RelayFrame): String {
        val prepared = if (frame is RelayFrame.Meta) padded(frame) else frame
        val text = HubJson.frames.encodeToString(RelayFrame.serializer(), prepared)
        require(utf8Size(text) <= HubContract.MAX_FRAME_BYTES) { "relay frame exceeds ${HubContract.MAX_FRAME_BYTES} bytes" }
        when (frame) {
            is RelayFrame.Send -> require(utf8Size(frame.payload) <= HubContract.MAX_PAYLOAD_BYTES) { "payload exceeds ${HubContract.MAX_PAYLOAD_BYTES} bytes" }
            is RelayFrame.Deliver -> require(utf8Size(frame.payload) <= HubContract.MAX_PAYLOAD_BYTES) { "payload exceeds ${HubContract.MAX_PAYLOAD_BYTES} bytes" }
            else -> Unit
        }
        return text
    }

    /** Decodes [text], or returns null when it is not an acceptable frame. Prefer [decodeResult] when the reason matters. */
    public fun decode(text: String, maxBytes: Int = HubContract.MAX_FRAME_BYTES): RelayFrame? =
        (decodeResult(text, maxBytes) as? Decoded.Ok)?.frame

    /** Decodes and validates [text]. [maxBytes] lets a hub bound pre-handshake frames more tightly. */
    public fun decodeResult(text: String, maxBytes: Int = HubContract.MAX_FRAME_BYTES): Decoded {
        // UTF-8 size is >= char count, so the cheap bound is checked first and the exact one only when it could matter.
        if (text.length > maxBytes || utf8Size(text) > maxBytes) return Decoded.Rejected(RejectReason.TOO_LARGE)
        val element = try {
            HubJson.frames.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return Decoded.Rejected(RejectReason.MALFORMED)
        } catch (_: IllegalArgumentException) {
            return Decoded.Rejected(RejectReason.MALFORMED)
        }
        val obj = element as? JsonObject ?: return Decoded.Rejected(RejectReason.MALFORMED)
        val type = (obj["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type == null || type !in KNOWN_TYPES) return Decoded.Rejected(RejectReason.UNKNOWN_TYPE)
        val frame = try {
            HubJson.frames.decodeFromJsonElement(RelayFrame.serializer(), obj)
        } catch (_: SerializationException) {
            return Decoded.Rejected(RejectReason.MALFORMED)
        } catch (_: IllegalArgumentException) {
            return Decoded.Rejected(RejectReason.MALFORMED)
        }
        return validate(frame)
    }

    private fun validate(frame: RelayFrame): Decoded {
        fun bad(field: String) = Decoded.Rejected(RejectReason.INVALID_FIELD, field)
        fun id(value: String) = value.isNotBlank() && value.length <= HubContract.MAX_ID_LENGTH
        fun name(value: String) = value.length <= HubContract.MAX_NAME_LENGTH
        fun version(v: Int) = v == HubContract.RELAY_WIRE_VERSION

        when (frame) {
            is RelayFrame.Challenge -> {
                if (!version(frame.v)) return Decoded.Rejected(RejectReason.UNSUPPORTED_VERSION)
                if (!id(frame.serverId)) return bad("serverId")
                if (!id(frame.serverNonce)) return bad("serverNonce")
            }
            is RelayFrame.Hello -> {
                if (!version(frame.v)) return Decoded.Rejected(RejectReason.UNSUPPORTED_VERSION)
                if (!id(frame.deviceId)) return bad("deviceId")
                if (!id(frame.clientNonce)) return bad("clientNonce")
                if (!name(frame.name)) return bad("name")
                if (frame.signature.isBlank()) return bad("signature")
                if (frame.delegationChain.size > MAX_CHAIN) return bad("delegationChain")
            }
            is RelayFrame.Welcome -> {
                if (!version(frame.v)) return Decoded.Rejected(RejectReason.UNSUPPORTED_VERSION)
                if (!id(frame.principal.deviceId)) return bad("principal.deviceId")
                if (frame.limits.maxFrameBytes <= 0 || frame.limits.maxPayloadBytes <= 0) return bad("limits")
            }
            is RelayFrame.Error -> if (frame.code.isBlank() || frame.code.length > HubContract.MAX_ID_LENGTH) return bad("code")
            is RelayFrame.Send -> {
                if (frame.group != null) {
                    if (!id(frame.group)) return bad("group")
                } else if (!id(frame.to)) {
                    return bad("to")
                }
                if (!id(frame.id)) return bad("id")
                if (!id(frame.type)) return bad("type")
                if (frame.sentAt <= 0) return bad("sentAt")
                if (utf8Size(frame.payload) > HubContract.MAX_PAYLOAD_BYTES) return Decoded.Rejected(RejectReason.PAYLOAD_TOO_LARGE)
            }
            is RelayFrame.Deliver -> {
                if (!id(frame.from)) return bad("from")
                if (frame.group != null && !id(frame.group)) return bad("group")
                if (!id(frame.id)) return bad("id")
                if (!id(frame.type)) return bad("type")
                if (frame.sentAt <= 0) return bad("sentAt")
                if (utf8Size(frame.payload) > HubContract.MAX_PAYLOAD_BYTES) return Decoded.Rejected(RejectReason.PAYLOAD_TOO_LARGE)
            }
            is RelayFrame.RecvAck -> if (!id(frame.msgId)) return bad("msgId")
            is RelayFrame.Ack -> if (!id(frame.id)) return bad("id")
            is RelayFrame.QueueState -> if (!id(frame.id)) return bad("id")
            is RelayFrame.Meta -> {
                if (!id(frame.action)) return bad("action")
                if (frame.conversationId.length > HubContract.MAX_ID_LENGTH) return bad("conversationId")
                if (frame.to != null && !id(frame.to)) return bad("to")
                if (frame.from != null && !id(frame.from)) return bad("from")
            }
            is RelayFrame.Ping, is RelayFrame.Pong, is RelayFrame.Revoked, is RelayFrame.Kick -> Unit
        }
        return Decoded.Ok(frame)
    }

    /** Pads a [RelayFrame.Meta] so its encoded form is exactly 256 or 512 bytes (P-REF-1); larger frames are left alone. */
    private fun padded(meta: RelayFrame.Meta): RelayFrame.Meta {
        val bare = meta.copy(pad = "")
        val size = utf8Size(HubJson.frames.encodeToString(RelayFrame.serializer(), bare))
        val target = when {
            size <= PAD_BOUNDARY_256 -> PAD_BOUNDARY_256
            size <= PAD_BOUNDARY_512 -> PAD_BOUNDARY_512
            else -> return bare
        }
        // `"pad":""` is already in the bare form, so every filler char adds exactly one byte.
        return bare.copy(pad = "0".repeat(target - size))
    }

    /** True when [payload] fits a single relay frame. */
    public fun isWithinPayloadBounds(payload: String): Boolean = utf8Size(payload) <= HubContract.MAX_PAYLOAD_BYTES

    /**
     * Splits [payload] into pieces of at most [maxBytes] UTF-8 bytes each, cutting only between code points, so every
     * piece is valid text and `pieces.joinToString("") == payload` (CLI-11: the previous implementation cut at byte
     * offsets and corrupted multi-byte characters). Not used on the relay wire (a message is never fragmented, an
     * oversize payload is refused); kept for callers that must stay under a byte budget.
     */
    public fun chunkPayload(payload: String, maxBytes: Int = HubContract.MAX_PAYLOAD_BYTES): List<String> {
        require(maxBytes >= 4) { "maxBytes must hold at least one 4-byte code point" }
        if (utf8Size(payload) <= maxBytes) return listOf(payload)
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < payload.length) {
            val c = payload[i]
            val width = if (c.isHighSurrogate() && i + 1 < payload.length && payload[i + 1].isLowSurrogate()) 2 else 1
            val piece = payload.substring(i, i + width)
            val bytes = utf8Size(piece)
            if (currentBytes + bytes > maxBytes) {
                chunks.add(current.toString())
                current.clear()
                currentBytes = 0
            }
            current.append(piece)
            currentBytes += bytes
            i += width
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    private const val MAX_CHAIN: Int = 4

    private val KNOWN_TYPES: Set<String> = setOf(
        "FLASH_HUB_CHALLENGE", "FLASH_HUB_HELLO", "FLASH_HUB_WELCOME", "FLASH_ERROR", "FLASH_RELAY", "FLASH_DELIVER",
        "FLASH_RECV_ACK", "FLASH_ACK", "FLASH_QUEUE_STATE", "FLASH_META", "FLASH_PING", "FLASH_PONG", "FLASH_REVOKED", "FLASH_KICK",
    )

    /** UTF-8 byte length of [s] without allocating (an unpaired surrogate is counted as 3 bytes: a safe over-estimate of its 1-byte replacement). */
    public fun utf8Size(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.code < 0x80 -> n += 1
                c.code < 0x800 -> n += 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    n += 4
                    i++
                }
                else -> n += 3
            }
            i++
        }
        return n
    }
}
