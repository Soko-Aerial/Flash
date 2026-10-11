@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashTextFraming

/**
 * Encoder and decoder for enterprise relay wire frames.
 *
 * Implements:
 * - Line-based framing with [FlashTextFraming]
 * - `P-REF-1`: Wire-level frame padding to 256 B / 512 B boundaries for metadata/ephemeral traffic
 * - `P-REF-2`: MTU bounds validation (control frames <= 1200 bytes, data chunks <= 32 KB)
 */
@FlashInternalApi
public object RelayFrameCodec {

    public const val MAX_CONTROL_FRAME_BYTES: Int = 1200
    public const val MAX_PAYLOAD_CHUNK_BYTES: Int = 32 * 1024 // 32 KB

    public const val PAD_BOUNDARY_256: Int = 256
    public const val PAD_BOUNDARY_512: Int = 512

    /**
     * Encodes a [RelayFrame] into standard wire text.
     */
    public fun encode(frame: RelayFrame): String {
        return when (frame) {
            is RelayFrame.Hello -> {
                val fields = mutableListOf(
                    "deviceId" to frame.deviceId,
                    "name" to frame.friendlyName,
                    "role" to frame.role,
                )
                if (frame.authSignature != null) fields.add("signature" to frame.authSignature)
                if (frame.clientNonce != null) fields.add("nonce" to frame.clientNonce)
                FlashTextFraming.encodeFields("FLASH_HUB_HELLO", fields)
            }

            is RelayFrame.Welcome -> {
                FlashTextFraming.encodeFields(
                    "FLASH_HUB_WELCOME",
                    "deviceId" to frame.deviceId,
                    "status" to frame.status,
                    "timestamp" to frame.timestamp.toString(),
                )
            }

            is RelayFrame.Data -> {
                FlashTextFraming.encodeFields(
                    "FLASH_RELAY",
                    "recipient" to frame.recipientId,
                    "id" to frame.id,
                    "type" to frame.type,
                    "sender" to frame.senderId,
                    "sentAt" to frame.sentAt.toString(),
                    "payload" to frame.payload,
                )
            }

            is RelayFrame.Ack -> {
                FlashTextFraming.encodeFields(
                    "FLASH_ACK",
                    "id" to frame.id,
                    "recipient" to frame.recipientId,
                    "status" to frame.status,
                    "timestamp" to frame.timestamp.toString(),
                )
            }

            is RelayFrame.Ping -> {
                FlashTextFraming.encodeFields(
                    "FLASH_PING",
                    "timestamp" to frame.timestamp.toString(),
                )
            }

            is RelayFrame.Pong -> {
                FlashTextFraming.encodeFields(
                    "FLASH_PONG",
                    "timestamp" to frame.timestamp.toString(),
                )
            }

            is RelayFrame.Meta -> {
                encodeMetaPadded(frame)
            }

            is RelayFrame.Error -> {
                FlashTextFraming.encodeFields(
                    "FLASH_ERROR",
                    "code" to frame.code,
                    "message" to frame.message,
                )
            }
        }
    }

    /**
     * Encodes a metadata frame with `P-REF-1` wire padding to the next power-of-two / fixed boundary
     * (256 or 512 bytes) to disguise typing cadence and activity lengths.
     */
    private fun encodeMetaPadded(frame: RelayFrame.Meta): String {
        val baseFields = listOf(
            "action" to frame.action,
            "conversationId" to frame.conversationId,
            "sender" to frame.senderId,
            "timestamp" to frame.timestamp.toString(),
        )
        val unpadded = FlashTextFraming.encodeFields("FLASH_META", baseFields)
        val unpaddedBytes = unpadded.encodeToByteArray().size

        val targetSize = when {
            unpaddedBytes < PAD_BOUNDARY_256 -> PAD_BOUNDARY_256
            unpaddedBytes < PAD_BOUNDARY_512 -> PAD_BOUNDARY_512
            else -> return unpadded
        }

        // We append " pad=<chars>" so overhead is ' pad=' (5 chars) + padLength chars
        val overhead = 5
        val neededPad = targetSize - unpaddedBytes - overhead
        if (neededPad <= 0) return unpadded

        val padContent = "0".repeat(neededPad)
        val paddedFields = baseFields + ("pad" to padContent)
        return FlashTextFraming.encodeFields("FLASH_META", paddedFields)
    }

    /**
     * Decodes a raw wire [text] into a [RelayFrame], or null if unparseable.
     */
    public fun decode(text: String): RelayFrame? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // 1. FLASH_HUB_WELCOME
        FlashTextFraming.parseFields(trimmed, "FLASH_HUB_WELCOME")?.let { f ->
            val deviceId = f["deviceId"] ?: return null
            val status = f["status"] ?: "CONNECTED"
            val timestamp = f["timestamp"]?.toLongOrNull() ?: 0L
            return RelayFrame.Welcome(deviceId = deviceId, status = status, timestamp = timestamp)
        }

        // 2. FLASH_ACK
        FlashTextFraming.parseFields(trimmed, "FLASH_ACK")?.let { f ->
            val id = f["id"] ?: return null
            val recipientId = f["recipient"] ?: f["target"] ?: ""
            val status = f["status"] ?: RelayFrame.Ack.STATUS_DELIVERED
            val timestamp = f["timestamp"]?.toLongOrNull() ?: 0L
            return RelayFrame.Ack(id = id, recipientId = recipientId, status = status, timestamp = timestamp)
        }

        // 3. FLASH_RELAY / FLASH_DATA / FLASH_ENVELOPE / FLASH_MSG
        for (prefix in listOf("FLASH_RELAY", "FLASH_DATA", "FLASH_ENVELOPE", "FLASH_MSG")) {
            FlashTextFraming.parseFields(trimmed, prefix)?.let { f ->
                val recipientId = f["recipient"] ?: f["to"] ?: f["targetDeviceId"] ?: return null
                val id = f["id"] ?: f["msgId"] ?: f["envelopeId"] ?: return null
                val type = f["type"] ?: "MSG_TEXT"
                val senderId = f["sender"] ?: f["from"] ?: f["senderId"] ?: return null
                val sentAt = f["sentAt"]?.toLongOrNull() ?: 0L
                val payload = f["payload"] ?: f["payloadJson"] ?: "{}"
                return RelayFrame.Data(
                    recipientId = recipientId,
                    id = id,
                    type = type,
                    senderId = senderId,
                    sentAt = sentAt,
                    payload = payload,
                )
            }
        }

        // 4. FLASH_PING
        FlashTextFraming.parseFields(trimmed, "FLASH_PING")?.let { f ->
            val ts = f["timestamp"]?.toLongOrNull() ?: 0L
            return RelayFrame.Ping(timestamp = ts)
        }

        // 5. FLASH_PONG
        FlashTextFraming.parseFields(trimmed, "FLASH_PONG")?.let { f ->
            val ts = f["timestamp"]?.toLongOrNull() ?: 0L
            return RelayFrame.Pong(timestamp = ts)
        }

        // 6. FLASH_META
        FlashTextFraming.parseFields(trimmed, "FLASH_META")?.let { f ->
            val action = f["action"] ?: return null
            val conversationId = f["conversationId"] ?: return null
            val senderId = f["sender"] ?: return null
            val ts = f["timestamp"]?.toLongOrNull() ?: 0L
            val pad = f["pad"] ?: ""
            return RelayFrame.Meta(
                action = action,
                conversationId = conversationId,
                senderId = senderId,
                timestamp = ts,
                padLength = pad.length,
            )
        }

        // 7. FLASH_HUB_HELLO / FLASH_HELLO
        for (helloPrefix in listOf("FLASH_HUB_HELLO", "FLASH_HELLO")) {
            FlashTextFraming.parseFields(trimmed, helloPrefix)?.let { f ->
                val deviceId = f["deviceId"] ?: f["id"] ?: return null
                val name = f["name"] ?: f["friendlyName"] ?: deviceId
                val role = f["role"] ?: "member"
                val sig = f["signature"]
                val nonce = f["nonce"]
                return RelayFrame.Hello(
                    deviceId = deviceId,
                    friendlyName = name,
                    role = role,
                    authSignature = sig,
                    clientNonce = nonce,
                )
            }
        }

        // 8. FLASH_ERROR
        FlashTextFraming.parseFields(trimmed, "FLASH_ERROR")?.let { f ->
            val code = f["code"] ?: "UNKNOWN"
            val message = f["message"] ?: "An error occurred"
            return RelayFrame.Error(code = code, message = message)
        }

        // 9. JSON Envelope Fallback
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return decodeJsonEnvelope(trimmed)
        }

        return null
    }

    private fun decodeJsonEnvelope(json: String): RelayFrame.Data? {
        fun extractString(key: String): String? {
            val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return pattern.find(json)?.groupValues?.getOrNull(1)
        }
        fun extractLong(key: String): Long? {
            val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
            return pattern.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }

        val recipientId = extractString("recipientId") ?: extractString("recipient") ?: extractString("to") ?: return null
        val id = extractString("id") ?: extractString("msgId") ?: return null
        val type = extractString("type") ?: "MSG_TEXT"
        val senderId = extractString("senderId") ?: extractString("sender") ?: return null
        val sentAt = extractLong("sentAt") ?: 0L
        val payload = extractString("payloadJson") ?: extractString("payload") ?: "{}"

        return RelayFrame.Data(
            recipientId = recipientId,
            id = id,
            type = type,
            senderId = senderId,
            sentAt = sentAt,
            payload = payload,
        )
    }

    /**
     * Checks if a control frame obeys `P-REF-2` MTU bounds (<= 1200 bytes).
     */
    public fun isWithinControlBounds(frame: RelayFrame): Boolean {
        if (frame is RelayFrame.Data) return true // Data frames are validated via payload bounds
        val encoded = encode(frame)
        return encoded.encodeToByteArray().size <= MAX_CONTROL_FRAME_BYTES
    }

    /**
     * Checks if a data frame payload chunk obeys `P-REF-2` bounds (<= 32 KB).
     */
    public fun isWithinPayloadBounds(data: RelayFrame.Data): Boolean {
        return data.payload.encodeToByteArray().size <= MAX_PAYLOAD_CHUNK_BYTES
    }

    /**
     * Splits a large payload string into chunks conforming to `P-REF-2` bounds (<= 32 KB).
     */
    public fun chunkPayload(payload: String, chunkSize: Int = MAX_PAYLOAD_CHUNK_BYTES): List<String> {
        val bytes = payload.encodeToByteArray()
        if (bytes.size <= chunkSize) return listOf(payload)

        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            chunks.add(bytes.decodeToString(offset, end))
            offset = end
        }
        return chunks
    }
}
