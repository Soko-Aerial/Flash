@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.FileStates
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto
import com.transfer.flash.core.hubprotocol.rest.HubFileIds
import com.transfer.flash.core.hubprotocol.rest.HubInfo
import com.transfer.flash.core.security.enterprise.HubFileHasher
import kotlinx.coroutines.awaitCancellation

/**
 * An in-memory hub for the sealed-file routes, shared by several [FakeHubFilesApi] views (one per device), with the
 * server's rules of `FileStore` that the client depends on: idempotent create, idempotent identical PUT, a different
 * re-PUT is a conflict, complete needs every chunk, a recipient sees only a READY file, and the plaintext limit plus the
 * sealing allowance. Tests reach in to lose chunks, sweep a file or flip a stored byte.
 */
@FlashInternalApi
public class FakeHubFileServer(
    /** The plaintext limit (`HubInfo.maxFileBytes`). */
    public var maxFileBytes: Long = HubContract.DEFAULT_MAX_FILE_BYTES,
    public var clockMs: () -> Long = { 0L },
) {
    public class Stored(
        public val request: CreateFileRequestDto,
        public val sender: String,
        public var state: String,
        public val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        public var expiresAtMs: Long = 0L,
    )

    public val files: MutableMap<String, Stored> = mutableMapOf()

    /** Group rosters for a group file's recipients; a member removed here can no longer read. */
    public val groups: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** The upload window sweep (situation 4) or a cancel by someone else: the file is gone. */
    public fun sweep(fileId: String) {
        files.remove(fileId)
    }

    /** A restore that lost chunk bytes: the hub still knows the file but has fewer chunks. */
    public fun loseChunks(fileId: String, vararg indexes: Int) {
        val stored = files[fileId] ?: return
        indexes.forEach { stored.chunks.remove(it) }
    }

    /** Flips one stored byte, as a damaged disk or a lying hub would. */
    public fun tamper(fileId: String, index: Int) {
        val chunk = files[fileId]?.chunks?.get(index) ?: return
        chunk[chunk.size / 2] = (chunk[chunk.size / 2].toInt() xor 0x01).toByte()
    }

    internal fun status(s: Stored): FileStatusDto = FileStatusDto(
        fileId = s.request.fileId,
        state = s.state,
        totalBytes = s.request.totalBytes,
        chunkSize = s.request.chunkSize,
        chunkCount = s.request.chunkCount,
        sha256 = s.request.sha256,
        senderDeviceId = s.sender,
        recipients = recipientsOf(s).sorted(),
        createdAtMs = 0L,
        expiresAtMs = s.expiresAtMs,
        receivedChunks = s.chunks.size,
    )

    internal fun recipientsOf(s: Stored): Set<String> =
        s.request.toDeviceId?.let(::setOf) ?: (groups[s.request.groupId].orEmpty() - s.sender)

    internal fun expectedChunkBytes(r: CreateFileRequestDto, index: Int): Long =
        if (index < r.chunkCount - 1) r.chunkSize.toLong() else r.totalBytes - r.chunkSize.toLong() * (r.chunkCount - 1)
}

/**
 * [HubFilesApi] for one device against a [FakeHubFileServer], with fault injection: [offline] answers every call with
 * [HubFileError.Offline]; [failNext] answers the next calls with the queued errors; [stallNext] makes the next calls hang
 * until the caller's timeout cancels them (a half-open socket); [beforeCall] runs before each call so a test can change
 * the world mid-transfer (kill the network after chunk 3). [calls] counts every call by route name.
 */
@FlashInternalApi
public class FakeHubFilesApi(
    private val server: FakeHubFileServer,
    private val deviceId: String,
    /** The hub this device is enrolled with; any other URL is [HubFileError.NotThisHub]. */
    private val hubUrl: String = "https://hub.example",
) : HubFilesApi {

    public var offline: Boolean = false
    public val failNext: ArrayDeque<HubFileError> = ArrayDeque()
    public var stallNext: Int = 0
    public var beforeCall: (route: String) -> Unit = {}
    public val calls: MutableMap<String, Int> = mutableMapOf()

    private suspend fun <T> gate(route: String, url: String, block: () -> HubFileResult<T>): HubFileResult<T> {
        calls[route] = (calls[route] ?: 0) + 1
        beforeCall(route)
        if (stallNext > 0) {
            stallNext--
            awaitCancellation()
        }
        if (offline) return HubFileResult.Err(HubFileError.Offline)
        failNext.removeFirstOrNull()?.let { return HubFileResult.Err(it) }
        if (url.trimEnd('/') != hubUrl) return HubFileResult.Err(HubFileError.NotThisHub)
        return block()
    }

    private fun refused(code: String = HubErrorCodes.ERR_BAD_REQUEST) = HubFileResult.Err(HubFileError.Refused(code))

    override suspend fun create(hubUrl: String, request: CreateFileRequestDto): HubFileResult<FileTicketDto> =
        gate(ROUTE_CREATE, hubUrl) {
            val r = request
            if (!HubFileIds.isFileId(r.fileId) || !HubFileIds.isSha256(r.sha256) || r.totalBytes <= 0) return@gate refused()
            val allowance = r.chunkCount.coerceIn(0, HubContract.MAX_FILE_CHUNKS).toLong() * HubContract.SEALED_CHUNK_OVERHEAD_BYTES
            if (r.totalBytes > server.maxFileBytes + allowance) return@gate HubFileResult.Err(HubFileError.TooLarge(null))
            if (r.chunkSize !in 1..HubContract.MAX_CHUNK_BYTES) return@gate HubFileResult.Err(HubFileError.TooLarge(null))
            val count = ((r.totalBytes + r.chunkSize - 1) / r.chunkSize).toInt()
            if (count != r.chunkCount || count > HubContract.MAX_FILE_CHUNKS) return@gate refused()
            if ((r.toDeviceId == null) == (r.groupId == null)) return@gate refused()
            val existing = server.files[r.fileId]
            if (existing != null) {
                if (existing.request != r || existing.sender != deviceId) return@gate HubFileResult.Err(HubFileError.Conflict)
                if (existing.state != FileStates.UPLOADING) return@gate HubFileResult.Err(HubFileError.Conflict)
            } else {
                server.files[r.fileId] = FakeHubFileServer.Stored(r, deviceId, FileStates.UPLOADING)
            }
            HubFileResult.Ok(FileTicketDto(r.fileId, server.clockMs() + UPLOAD_WINDOW_MS, HubContract.MAX_CHUNK_BYTES))
        }

    override suspend fun putChunk(
        hubUrl: String,
        fileId: String,
        index: Int,
        bytes: ByteArray,
        sha256Hex: String,
    ): HubFileResult<Unit> = gate(ROUTE_PUT, hubUrl) {
        val s = server.files[fileId] ?: return@gate HubFileResult.Err(HubFileError.NotFound)
        if (s.sender != deviceId) return@gate HubFileResult.Err(HubFileError.Forbidden)
        if (s.state != FileStates.UPLOADING) return@gate HubFileResult.Err(HubFileError.Conflict)
        if (index !in 0 until s.request.chunkCount) return@gate refused()
        if (bytes.size.toLong() != server.expectedChunkBytes(s.request, index)) return@gate refused()
        if (HubFileHasher().also { it.update(bytes) }.digestHex() != sha256Hex) return@gate refused()
        val held = s.chunks[index]
        if (held != null) {
            return@gate if (held.contentEquals(bytes)) HubFileResult.Ok(Unit) else HubFileResult.Err(HubFileError.Conflict)
        }
        s.chunks[index] = bytes.copyOf()
        HubFileResult.Ok(Unit)
    }

    override suspend fun complete(hubUrl: String, fileId: String): HubFileResult<FileStatusDto> = gate(ROUTE_COMPLETE, hubUrl) {
        val s = server.files[fileId] ?: return@gate HubFileResult.Err(HubFileError.NotFound)
        if (s.sender != deviceId) return@gate HubFileResult.Err(HubFileError.Forbidden)
        if (s.state == FileStates.READY) return@gate HubFileResult.Ok(server.status(s))
        if (s.chunks.size != s.request.chunkCount) return@gate HubFileResult.Err(HubFileError.Conflict)
        s.state = FileStates.READY
        s.expiresAtMs = server.clockMs() + RETENTION_MS
        HubFileResult.Ok(server.status(s))
    }

    override suspend fun status(hubUrl: String, fileId: String): HubFileResult<FileStatusDto> = gate(ROUTE_STATUS, hubUrl) {
        val s = server.files[fileId] ?: return@gate HubFileResult.Err(HubFileError.NotFound)
        access(s)?.let { return@gate HubFileResult.Err(it) }
        HubFileResult.Ok(server.status(s))
    }

    override suspend fun readChunk(hubUrl: String, fileId: String, index: Int, expectedBytes: Int): HubFileResult<ByteArray> =
        gate(ROUTE_READ, hubUrl) {
            val s = server.files[fileId] ?: return@gate HubFileResult.Err(HubFileError.NotFound)
            if (s.state != FileStates.READY) return@gate HubFileResult.Err(HubFileError.NotReady)
            access(s)?.let { return@gate HubFileResult.Err(it) }
            val chunk = s.chunks[index] ?: return@gate HubFileResult.Err(HubFileError.Unavailable)
            if (chunk.size != expectedBytes) return@gate HubFileResult.Err(HubFileError.Integrity)
            HubFileResult.Ok(chunk.copyOf())
        }

    override suspend fun cancel(hubUrl: String, fileId: String): HubFileResult<Unit> = gate(ROUTE_CANCEL, hubUrl) {
        val s = server.files[fileId] ?: return@gate HubFileResult.Ok(Unit)
        if (s.sender != deviceId) return@gate HubFileResult.Err(HubFileError.Forbidden)
        server.files.remove(fileId)
        HubFileResult.Ok(Unit)
    }

    override suspend fun hubInfo(hubUrl: String): HubFileResult<HubInfo> = gate(ROUTE_INFO, hubUrl) {
        HubFileResult.Ok(HubInfo(serverId = "fake-hub", maxFileBytes = server.maxFileBytes))
    }

    private fun access(s: FakeHubFileServer.Stored): HubFileError? {
        if (s.sender == deviceId) return null
        if (deviceId !in server.recipientsOf(s)) return HubFileError.Forbidden
        if (s.state != FileStates.READY) return HubFileError.NotFound
        if (s.expiresAtMs in 1..server.clockMs()) return HubFileError.NotFound
        return null
    }

    public companion object {
        public const val ROUTE_CREATE: String = "create"
        public const val ROUTE_PUT: String = "put"
        public const val ROUTE_COMPLETE: String = "complete"
        public const val ROUTE_STATUS: String = "status"
        public const val ROUTE_READ: String = "read"
        public const val ROUTE_CANCEL: String = "cancel"
        public const val ROUTE_INFO: String = "info"

        private const val UPLOAD_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
