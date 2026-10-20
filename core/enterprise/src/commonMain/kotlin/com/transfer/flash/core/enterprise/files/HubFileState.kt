@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.rest.HubFileIds
import com.transfer.flash.core.security.enterprise.HubFileHasher
import com.transfer.flash.core.security.enterprise.HubFileLayout

/** Which chunks of one file are done. At most [HubContract.MAX_FILE_CHUNKS] bits (512 bytes on disk). */
@FlashInternalApi
public class HubChunkBitmap(public val size: Int) {
    private val bits = ByteArray((size + 7) / 8)

    init {
        require(size in 1..HubContract.MAX_FILE_CHUNKS) { "chunk count out of range" }
    }

    public operator fun get(index: Int): Boolean = (bits[index ushr 3].toInt() and (1 shl (index and 7))) != 0

    public fun set(index: Int) {
        bits[index ushr 3] = (bits[index ushr 3].toInt() or (1 shl (index and 7))).toByte()
    }

    public fun clear() {
        bits.fill(0)
    }

    /** How many chunks are done. O(size). */
    public fun count(): Int = (0 until size).count { get(it) }

    /** The first chunk not done, or -1 when all are. */
    public fun firstMissing(): Int = (0 until size).firstOrNull { !get(it) } ?: -1

    internal fun encode(): String = Base64.encode(bits)

    internal companion object {
        /** Null when [text] is not exactly the bitmap of [size] chunks (no stray bits past [size]). */
        fun decode(size: Int, text: String): HubChunkBitmap? {
            if (size !in 1..HubContract.MAX_FILE_CHUNKS) return null
            val raw = runCatching { Base64.decode(text) }.getOrNull() ?: return null
            val map = HubChunkBitmap(size)
            if (raw.size != map.bits.size) return null
            val spare = map.bits.size * 8 - size
            if (spare > 0 && raw.last().toInt() and 0xFF ushr (8 - spare) != 0) return null
            raw.copyInto(map.bits)
            return map
        }
    }
}

/**
 * Everything a resumed upload needs. [keyText] is the file's secret ([com.transfer.flash.core.security.enterprise.HubFileKey]
 * encoding): it is on disk so a resumed upload re-seals chunks to the bytes the hub already holds, in the app's private
 * storage next to the plaintext it protects (`docs/security.md`, hub files rows).
 */
internal class UploadState(
    val fileId: String,
    val hubUrl: String,
    val keyText: String,
    val toDeviceId: String?,
    val groupId: String?,
    val nameHint: String?,
    val plainBytes: Long,
    val cipherChunkBytes: Int,
    val chunkCount: Int,
    val cipherSha256: String,
    var created: Boolean,
    var completed: Boolean,
    var expiresAtMs: Long,
    val done: HubChunkBitmap,
)

/** Everything a resumed download needs. The key is NOT here: it comes with each request, from the sealed message. */
internal class DownloadState(
    val fileId: String,
    val hubUrl: String,
    val cipherSha256: String,
    val plainBytes: Long,
    val cipherChunkBytes: Int,
    val chunkCount: Int,
    /** How many whole-file verifications failed; the second failure ends the download (one full re-download allowed). */
    var verifyFailures: Int,
    val done: HubChunkBitmap,
)

/**
 * Text form of the state records: a version line, `key=value` lines, and a final `check=` line holding the SHA-256 of
 * everything before it. [decodeUpload]/[decodeDownload] return null (never throw) for a torn write, another version, a
 * missing field or any value outside its shape, and the engine then starts that transfer again instead of trusting it.
 * Unknown keys are ignored so a later version can add fields.
 */
internal object HubFileStateCodec {
    private const val HEADER = "flash-hub-file-state/1"
    private const val CHECK = "check="

    fun encode(state: UploadState): ByteArray = seal(
        listOf(
            "kind" to "upload",
            "fileId" to state.fileId,
            "hub" to Base64.encodeUtf8(state.hubUrl),
            "key" to state.keyText,
            "to" to (state.toDeviceId?.let(Base64::encodeUtf8) ?: ""),
            "group" to (state.groupId?.let(Base64::encodeUtf8) ?: ""),
            "name" to (state.nameHint?.let(Base64::encodeUtf8) ?: ""),
            "plainBytes" to state.plainBytes.toString(),
            "chunkBytes" to state.cipherChunkBytes.toString(),
            "chunkCount" to state.chunkCount.toString(),
            "sha256" to state.cipherSha256,
            "created" to state.created.toString(),
            "completed" to state.completed.toString(),
            "expiresAt" to state.expiresAtMs.toString(),
            "done" to state.done.encode(),
        ),
    )

    fun encode(state: DownloadState): ByteArray = seal(
        listOf(
            "kind" to "download",
            "fileId" to state.fileId,
            "hub" to Base64.encodeUtf8(state.hubUrl),
            "sha256" to state.cipherSha256,
            "plainBytes" to state.plainBytes.toString(),
            "chunkBytes" to state.cipherChunkBytes.toString(),
            "chunkCount" to state.chunkCount.toString(),
            "verifyFailures" to state.verifyFailures.toString(),
            "done" to state.done.encode(),
        ),
    )

    fun decodeUpload(bytes: ByteArray): UploadState? = runCatching {
        val f = open(bytes) ?: return null
        if (f["kind"] != "upload") return null
        val fileId = f["fileId"]?.takeIf(HubFileIds::isFileId) ?: return null
        val count = f["chunkCount"]?.toIntOrNull() ?: return null
        val chunkBytes = f["chunkBytes"]?.toIntOrNull()?.takeIf { it in 17..HubContract.MAX_CHUNK_BYTES } ?: return null
        val plainBytes = f["plainBytes"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (HubFileLayout.of(plainBytes, chunkBytes)?.chunkCount != count) return null
        val toDevice = f["to"]?.let(::optionalText) ?: return null
        val group = f["group"]?.let(::optionalText) ?: return null
        if ((toDevice.value == null) == (group.value == null)) return null
        UploadState(
            fileId = fileId,
            hubUrl = f["hub"]?.let(Base64::decodeUtf8) ?: return null,
            keyText = f["key"]?.takeIf { it.startsWith("hfk1.") } ?: return null,
            toDeviceId = toDevice.value,
            groupId = group.value,
            nameHint = f["name"]?.let(::optionalText)?.value,
            plainBytes = plainBytes,
            cipherChunkBytes = chunkBytes,
            chunkCount = count,
            cipherSha256 = f["sha256"]?.takeIf(HubFileIds::isSha256) ?: return null,
            created = f["created"]?.toBooleanStrictOrNull() ?: return null,
            completed = f["completed"]?.toBooleanStrictOrNull() ?: return null,
            expiresAtMs = f["expiresAt"]?.toLongOrNull() ?: return null,
            done = f["done"]?.let { HubChunkBitmap.decode(count, it) } ?: return null,
        )
    }.getOrNull()

    fun decodeDownload(bytes: ByteArray): DownloadState? = runCatching {
        val f = open(bytes) ?: return null
        if (f["kind"] != "download") return null
        val count = f["chunkCount"]?.toIntOrNull() ?: return null
        val plainBytes = f["plainBytes"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val chunkBytes = f["chunkBytes"]?.toIntOrNull()?.takeIf { it in 17..HubContract.MAX_CHUNK_BYTES } ?: return null
        // The three numbers must describe one layout; a record that disagrees with itself is not trusted.
        if (HubFileLayout.of(plainBytes, chunkBytes)?.chunkCount != count) return null
        DownloadState(
            fileId = f["fileId"]?.takeIf(HubFileIds::isFileId) ?: return null,
            hubUrl = f["hub"]?.let(Base64::decodeUtf8) ?: return null,
            cipherSha256 = f["sha256"]?.takeIf(HubFileIds::isSha256) ?: return null,
            plainBytes = plainBytes,
            cipherChunkBytes = chunkBytes,
            chunkCount = count,
            verifyFailures = f["verifyFailures"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
            done = f["done"]?.let { HubChunkBitmap.decode(count, it) } ?: return null,
        )
    }.getOrNull()

    private class Optional(val value: String?)

    /** "" is absent; anything else must be Base64 of UTF-8. Null when it is neither. */
    private fun optionalText(raw: String): Optional? =
        if (raw.isEmpty()) Optional(null) else runCatching { Optional(Base64.decodeUtf8(raw)) }.getOrNull()

    private fun seal(fields: List<Pair<String, String>>): ByteArray {
        val body = buildString {
            append(HEADER).append('\n')
            for ((k, v) in fields) {
                require('\n' !in v && '\r' !in v) { "state values are single-line" }
                append(k).append('=').append(v).append('\n')
            }
        }
        return (body + CHECK + digest(body) + "\n").encodeToByteArray()
    }

    private fun open(bytes: ByteArray): Map<String, String>? {
        val text = bytes.decodeToString()
        if (!text.startsWith("$HEADER\n") || !text.endsWith("\n")) return null
        val checkAt = text.lastIndexOf("\n$CHECK")
        if (checkAt < 0) return null
        val body = text.substring(0, checkAt + 1)
        val check = text.substring(checkAt + 1 + CHECK.length).removeSuffix("\n")
        if (check != digest(body)) return null
        val fields = mutableMapOf<String, String>()
        for (line in body.removePrefix("$HEADER\n").removeSuffix("\n").split('\n')) {
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            if (fields.put(line.substring(0, eq), line.substring(eq + 1)) != null) return null
        }
        return fields
    }

    private fun digest(text: String): String = HubFileHasher().also { it.update(text.encodeToByteArray()) }.digestHex()
}
