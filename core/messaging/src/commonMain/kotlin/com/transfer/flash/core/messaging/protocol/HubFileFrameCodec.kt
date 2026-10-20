@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.hubprotocol.rest.HubFileIds
import com.transfer.flash.core.security.enterprise.HubFileKey
import com.transfer.flash.core.security.enterprise.HubFileLayout

/**
 * The message that hands a recipient a file sealed on the hub (ADR-215, ADR-216, HUB-FILES P3): its ticket (file id,
 * key, hashes, layout) plus what the bubble shows.
 *
 * It only ever travels inside a **sealed** 1:1 hub envelope (inner type `HUB_FILE`), never in a readable or group
 * frame: [ticket]'s key opens the file. The recipient fetches from its own enrolled hub, so no address is carried.
 */
public data class HubFileFrame(
    /** The sender's message id; the recipient stores the message under it, so a replay is a no-op. */
    val localId: String,
    val senderId: String,
    val senderName: String,
    val sentAt: Long,
    val ticket: HubFileTicket,
    val fileName: String,
    val mime: String,
    /** The voice-note metadata the text column carries for an audio file (duration and waveform), else empty. */
    val meta: String,
) {
    /** Never prints the key: data classes print their fields, and this one sits next to log lines. */
    override fun toString(): String =
        "HubFileFrame(localId=${localId.take(8)}, sender=${senderId.take(8)}, file=${ticket.fileId.take(8)}, bytes=${ticket.plainBytes})"
}

/**
 * Text codec for [HubFileFrame] (`FLASH_HUBFILE v=1 ...`, `docs/protocol.md` "Hub file message").
 *
 * [decode] never throws. It returns null for any other text, and [Result.Invalid] (drop the frame) for a recognised frame
 * that breaks a rule: a bad id or hash, a key that does not decode, a layout that does not match its sizes, a field
 * over its cap, a name that is blank or holds a path separator or a control character, or a version this build does not
 * know. Unknown extra keys are ignored, as in every Flash text frame.
 */
public object HubFileFrameCodec {

    public const val PREFIX: String = "FLASH_HUBFILE"
    public const val VERSION: Int = 1

    /** Caps in characters; a sealed hub payload is bounded anyway, these keep a single field from filling it. */
    public const val MAX_ID_CHARS: Int = 128
    public const val MAX_NAME_CHARS: Int = 255
    public const val MAX_MIME_CHARS: Int = 127
    public const val MAX_META_CHARS: Int = 4096

    /**
     * Senders seal 1 MiB chunks. A tiny chunk size is legal for the hub but would make a recipient issue one request per
     * few bytes, so a ticket below this is refused (a 50 MB file stays under 800 requests).
     */
    public const val MIN_CHUNK_BYTES: Int = 64 * 1024

    public sealed interface Result {
        public data class Frame(val frame: HubFileFrame) : Result

        /** The reason names a field, never its value. */
        public data class Invalid(val reason: String) : Result
    }

    public fun encode(frame: HubFileFrame): String {
        val t = frame.ticket
        return FlashTextFraming.encodeFields(
            PREFIX,
            listOf(
                "v" to VERSION.toString(),
                "localId" to frame.localId,
                "senderId" to frame.senderId,
                "senderName" to frame.senderName,
                "sentAt" to frame.sentAt.toString(),
                "fileId" to t.fileId,
                "key" to t.key.encode(),
                "sha256" to t.cipherSha256,
                "size" to t.plainBytes.toString(),
                "chunkBytes" to t.cipherChunkBytes.toString(),
                "chunks" to t.chunkCount.toString(),
                "expiresAt" to t.expiresAtMs.toString(),
                "name" to frame.fileName,
                "mime" to frame.mime,
                "meta" to frame.meta,
            ),
        )
    }

    public fun decode(text: String): Result? {
        val f = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        if (f["v"]?.toIntOrNull() != VERSION) return Result.Invalid("version")
        // A group id here would mean someone tried to hand a group's key out in the open; the format has no such field.
        if ("groupId" in f) return Result.Invalid("groupId")

        val localId = f["localId"]?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS && it.none(Char::isISOControl) }
            ?: return Result.Invalid("localId")
        val senderId = f["senderId"]?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS && it.none(Char::isISOControl) }
            ?: return Result.Invalid("senderId")
        val senderName = (f["senderName"] ?: "").takeIf { it.length <= MAX_NAME_CHARS } ?: return Result.Invalid("senderName")
        val sentAt = f["sentAt"]?.toLongOrNull()?.takeIf { it >= 0L } ?: return Result.Invalid("sentAt")

        val fileId = f["fileId"]?.takeIf(HubFileIds::isFileId) ?: return Result.Invalid("fileId")
        val key = f["key"]?.let(HubFileKey::decode) ?: return Result.Invalid("key")
        val sha = f["sha256"]?.takeIf(HubFileIds::isSha256) ?: return Result.Invalid("sha256")
        val size = f["size"]?.toLongOrNull()?.takeIf { it > 0L } ?: return Result.Invalid("size")
        val chunkBytes = f["chunkBytes"]?.toIntOrNull()?.takeIf { it >= MIN_CHUNK_BYTES } ?: return Result.Invalid("chunkBytes")
        val chunks = f["chunks"]?.toIntOrNull() ?: return Result.Invalid("chunks")
        val layout = HubFileLayout.of(size, chunkBytes) ?: return Result.Invalid("layout")
        if (layout.chunkCount != chunks) return Result.Invalid("chunks")
        val expiresAt = (f["expiresAt"] ?: "0").toLongOrNull()?.takeIf { it >= 0L } ?: return Result.Invalid("expiresAt")

        val name = f["name"]?.takeIf(::isSafeName) ?: return Result.Invalid("name")
        val mime = (f["mime"] ?: "").ifBlank { DEFAULT_MIME }
            .takeIf { it.length <= MAX_MIME_CHARS && it.none(Char::isISOControl) }
            ?: return Result.Invalid("mime")
        val meta = (f["meta"] ?: "").takeIf { it.length <= MAX_META_CHARS } ?: return Result.Invalid("meta")

        return Result.Frame(
            HubFileFrame(
                localId = localId,
                senderId = senderId,
                senderName = senderName,
                sentAt = sentAt,
                ticket = HubFileTicket(
                    fileId = fileId,
                    key = key,
                    cipherSha256 = sha,
                    plainBytes = size,
                    cipherChunkBytes = chunkBytes,
                    chunkCount = chunks,
                    expiresAtMs = expiresAt,
                ),
                fileName = name,
                mime = mime,
                meta = meta,
            ),
        )
    }

    /** A display name: not blank, not a path, not `.`/`..`, no control characters, within the cap. */
    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= MAX_NAME_CHARS &&
            name.trim() != "." && name.trim() != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    private const val DEFAULT_MIME = "application/octet-stream"
}
