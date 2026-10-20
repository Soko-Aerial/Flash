package com.transfer.flash.server.files

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.FileStates
import com.transfer.flash.core.hubprotocol.rest.FileStatsDto
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto
import com.transfer.flash.core.hubprotocol.rest.FileUsageRowDto
import com.transfer.flash.server.storage.Migration
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet

/**
 * Persistence for sealed files (ADR-215, `docs/plans/HUB-FILES-PLAN.md` section 3.2).
 *
 * The hub stores **ciphertext only**: it never sees a key, never parses a chunk, and never derives anything from the
 * plaintext. Metadata lives in SQLite, chunk bytes live under [rootDir] (`<rootDir>/<fileId>/<index>.chunk`), and every
 * mutating operation runs in a `BEGIN IMMEDIATE` transaction so two senders can never jointly overshoot a quota.
 *
 * Blocking JDBC and filesystem work: call these from `Dispatchers.IO` (the routes do), like the other stores.
 */
class FileStore(
    private val db: ServerDatabase,
    private val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: () -> FileLimits = { FileLimits() },
    /** Resolves a group's current members, or `null` when the group is unknown (a member check must fail closed). */
    private val groupMembers: (String) -> List<String>? = { null },
    /** Whether a device is enrolled; a direct send to an unknown device is refused by name. */
    private val knownDevice: (String) -> Boolean = { true },
) {

    private val log = LoggerFactory.getLogger(FileStore::class.java)

    // ----- creation ---------------------------------------------------------------------------------------------

    fun create(sender: String, request: CreateFileRequestDto): FileOutcome<FileTicketDto> {
        if (!isFileId(request.fileId)) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_ID)
        if (!isSha256(request.sha256)) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_HASH)
        if (request.totalBytes <= 0L) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.EMPTY_FILE)
        if (request.totalBytes > limits().maxFileBytes) {
            return FileOutcome.Refused(HubErrorCodes.ERR_TOO_LARGE, FileMessages.TOO_LARGE)
        }
        val lim = limits()
        if (request.chunkSize <= 0 || request.chunkSize > lim.chunkBytes) {
            return FileOutcome.Refused(HubErrorCodes.ERR_TOO_LARGE, FileMessages.CHUNK_SIZE_LIMIT)
        }
        if (request.chunkCount <= 0 || request.chunkCount > HubContract.MAX_FILE_CHUNKS ||
            request.chunkCount != chunkCountOf(request.totalBytes, request.chunkSize)
        ) {
            return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_SIZES)
        }
        if ((request.toDeviceId == null) == (request.groupId == null)) {
            return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_RECIPIENT)
        }
        val recipients: List<String> = when {
            request.toDeviceId != null -> {
                val target = request.toDeviceId!!
                if (target == sender) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_RECIPIENT)
                if (!knownDevice(target)) {
                    return FileOutcome.Refused(HubErrorCodes.ERR_UNKNOWN_RECIPIENT, FileMessages.UNKNOWN_RECIPIENT)
                }
                listOf(target)
            }
            else -> {
                val groupId = request.groupId!!
                val members = groupMembers(groupId)
                    ?: return FileOutcome.Refused(HubErrorCodes.ERR_UNKNOWN_RECIPIENT, FileMessages.UNKNOWN_RECIPIENT)
                val rest = (members.toSet() - sender).toList().sorted()
                if (rest.isEmpty()) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.UNKNOWN_RECIPIENT)
                rest
            }
        }

        val now = clock()
        val outcome = try {
            db.withTransaction { conn ->
                val existing = selectBlob(conn, request.fileId)
                if (existing != null) {
                    val same = existing.senderDeviceId == sender &&
                        existing.totalBytes == request.totalBytes &&
                        existing.chunkSize == request.chunkSize &&
                        existing.chunkCount == request.chunkCount &&
                        existing.sha256 == request.sha256.lowercase() &&
                        existing.groupId == request.groupId &&
                        existing.targetDeviceId == request.toDeviceId
                    if (!same) {
                        return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_CONFLICT, FileMessages.CONFLICT_META)
                    }
                    if (existing.state != FileStates.UPLOADING) {
                        return@withTransaction FileOutcome.Refused(
                            HubErrorCodes.ERR_CONFLICT,
                            FileMessages.NOT_UPLOADING,
                        )
                    }
                    return@withTransaction FileOutcome.Ok(
                        FileTicketDto(existing.fileId, existing.uploadExpiresAtMs, lim.chunkBytes),
                    )
                }

                val senderBytes = sumBytes(conn, "sender_device_id = ?", sender)
                if (senderBytes + request.totalBytes > lim.deviceStoredBytes) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_QUOTA_EXCEEDED, FileMessages.DEVICE_QUOTA)
                }
                val globalBytes = sumBytes(conn, "1 = 1")
                if (globalBytes + request.totalBytes > lim.globalStoredBytes) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_QUOTA_EXCEEDED, FileMessages.GLOBAL_QUOTA)
                }
                if (countFiles(conn, sender) >= lim.maxFilesPerDevice) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_QUOTA_EXCEEDED, FileMessages.TOO_MANY_FILES)
                }

                val uploadExpiresAt = now + lim.uploadWindowMs
                conn.prepareStatement(
                    """
                    INSERT INTO file_blob (
                        file_id, sender_device_id, group_id, target_device_id, state, total_bytes, chunk_size,
                        chunk_count, sha256, name_hint, received_chunks, received_bytes, created_at,
                        upload_expires_at, expires_at, completed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0, NULL)
                    """.trimIndent(),
                ).use { st ->
                    st.setString(1, request.fileId)
                    st.setString(2, sender)
                    st.setString(3, request.groupId)
                    st.setString(4, request.toDeviceId)
                    st.setString(5, FileStates.UPLOADING)
                    st.setLong(6, request.totalBytes)
                    st.setInt(7, request.chunkSize)
                    st.setInt(8, request.chunkCount)
                    st.setString(9, request.sha256.lowercase())
                    st.setString(10, request.nameHint?.take(200))
                    st.setLong(11, now)
                    st.setLong(12, uploadExpiresAt)
                    st.executeUpdate()
                }
                conn.prepareStatement("INSERT OR IGNORE INTO file_recipient(file_id, device_id) VALUES (?, ?)").use { st ->
                    for (id in recipients) {
                        st.setString(1, request.fileId)
                        st.setString(2, id)
                        st.addBatch()
                    }
                    st.executeBatch()
                }
                FileOutcome.Ok(FileTicketDto(request.fileId, uploadExpiresAt, lim.chunkBytes))
            }
        } catch (t: Throwable) {
            log.warn("file create failed for {}: {}", request.fileId.take(8), t.message)
            return FileOutcome.Failed(t)
        }

        // The directory is created after the commit: a crash between the two leaves an upload row with no directory,
        // which the first PUT creates again and the sweeper removes if it is never used.
        if (outcome is FileOutcome.Ok) {
            try {
                fileDir(request.fileId).mkdirs()
            } catch (e: IOException) {
                log.warn("file create {} could not make its directory: {}", request.fileId.take(8), e.message)
                return FileOutcome.Failed(e)
            }
        }
        return outcome
    }

    // ----- upload -----------------------------------------------------------------------------------------------

    fun putChunk(sender: String, fileId: String, index: Int, bytes: ByteArray, declaredSha256: String?): FileOutcome<PutResult> {
        if (!isFileId(fileId)) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_ID)
        if (index < 0) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.CHUNK_INDEX)
        val digest = sha256Hex(bytes)
        if (declaredSha256 != null && !declaredSha256.equals(digest, ignoreCase = true)) {
            return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.CHUNK_HASH)
        }
        val now = clock()
        return try {
            db.withTransaction { conn ->
                val blob = selectBlob(conn, fileId)
                    ?: return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
                if (blob.senderDeviceId != sender) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_FORBIDDEN, FileMessages.NOT_SENDER)
                }
                if (blob.state != FileStates.UPLOADING || now > blob.uploadExpiresAtMs) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_CONFLICT, FileMessages.NOT_UPLOADING)
                }
                if (index >= blob.chunkCount) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.CHUNK_INDEX)
                }
                val expected = expectedChunkBytes(blob, index)
                if (bytes.size > blob.chunkSize) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_TOO_LARGE, FileMessages.CHUNK_TOO_BIG)
                }
                if (bytes.size.toLong() != expected) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.CHUNK_SIZE)
                }

                val stored = selectChunk(conn, fileId, index)
                if (stored != null) {
                    return@withTransaction if (stored.sha256 == digest && stored.bytes == bytes.size.toLong()) {
                        FileOutcome.Ok(PutResult(newChunk = false, receivedChunks = blob.receivedChunks))
                    } else {
                        FileOutcome.Refused(HubErrorCodes.ERR_CONFLICT, FileMessages.CONFLICT_CHUNK)
                    }
                }

                writeChunkFile(fileId, index, bytes)
                conn.prepareStatement(
                    "INSERT INTO file_chunk(file_id, idx, bytes, sha256) VALUES (?, ?, ?, ?)",
                ).use { st ->
                    st.setString(1, fileId)
                    st.setInt(2, index)
                    st.setLong(3, bytes.size.toLong())
                    st.setString(4, digest)
                    st.executeUpdate()
                }
                conn.prepareStatement(
                    "UPDATE file_blob SET received_chunks = received_chunks + 1, received_bytes = received_bytes + ? " +
                        "WHERE file_id = ?",
                ).use { st ->
                    st.setLong(1, bytes.size.toLong())
                    st.setString(2, fileId)
                    st.executeUpdate()
                }
                FileOutcome.Ok(PutResult(newChunk = true, receivedChunks = blob.receivedChunks + 1))
            }
        } catch (t: Throwable) {
            log.warn("file put {}#{} failed: {}", fileId.take(8), index, t.message)
            FileOutcome.Failed(t)
        }
    }

    fun complete(sender: String, fileId: String): FileOutcome<FileStatusDto> {
        if (!isFileId(fileId)) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_ID)
        val now = clock()
        return try {
            db.withTransaction { conn ->
                val blob = selectBlob(conn, fileId)
                    ?: return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
                if (blob.senderDeviceId != sender) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_FORBIDDEN, FileMessages.NOT_SENDER)
                }
                if (blob.state == FileStates.READY) {
                    return@withTransaction FileOutcome.Ok(statusDto(conn, blob))
                }
                if (blob.state != FileStates.UPLOADING || now > blob.uploadExpiresAtMs) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_CONFLICT, FileMessages.NOT_UPLOADING)
                }
                val stored = chunkTotals(conn, fileId)
                if (stored.first != blob.chunkCount || stored.second != blob.totalBytes) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_CONFLICT, FileMessages.INCOMPLETE)
                }
                val missingOnDisk = (0 until blob.chunkCount).any { !chunkFile(fileId, it).isFile }
                if (missingOnDisk) return@withTransaction FileOutcome.Unavailable

                val expiresAt = now + limits().retentionMs
                conn.prepareStatement(
                    "UPDATE file_blob SET state = ?, completed_at = ?, expires_at = ? WHERE file_id = ?",
                ).use { st ->
                    st.setString(1, FileStates.READY)
                    st.setLong(2, now)
                    st.setLong(3, expiresAt)
                    st.setString(4, fileId)
                    st.executeUpdate()
                }
                FileOutcome.Ok(statusDto(conn, blob.copy(state = FileStates.READY, completedAtMs = now, expiresAtMs = expiresAt)))
            }
        } catch (t: Throwable) {
            log.warn("file complete {} failed: {}", fileId.take(8), t.message)
            FileOutcome.Failed(t)
        }
    }

    // ----- read -------------------------------------------------------------------------------------------------

    fun status(requester: String, fileId: String): FileOutcome<FileStatusDto> {
        if (!isFileId(fileId)) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_ID)
        return try {
            db.withConnection { conn ->
                val blob = selectBlob(conn, fileId)
                    ?: return@withConnection FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
                access(conn, blob, requester, now = clock())?.let { return@withConnection it }
                FileOutcome.Ok(statusDto(conn, blob))
            }
        } catch (t: Throwable) {
            FileOutcome.Failed(t)
        }
    }

    fun readChunk(requester: String, fileId: String, index: Int): FileOutcome<ByteArray> {
        if (!isFileId(fileId)) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_ID)
        if (index < 0) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.CHUNK_INDEX)
        return try {
            db.withConnection { conn ->
                val blob = selectBlob(conn, fileId)
                    ?: return@withConnection FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
                access(conn, blob, requester, now = clock())?.let { return@withConnection it }
                if (blob.state != FileStates.READY) {
                    return@withConnection FileOutcome.Refused(HubErrorCodes.ERR_CONFLICT, FileMessages.NOT_READY)
                }
                if (index >= blob.chunkCount) {
                    return@withConnection FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.CHUNK_INDEX)
                }
                val stored = selectChunk(conn, fileId, index)
                    ?: return@withConnection FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.CHUNK_INDEX)
                val file = chunkFile(fileId, index)
                if (!file.isFile || file.length() != stored.bytes) {
                    log.warn("file chunk {}#{} is in the index but not on disk", fileId.take(8), index)
                    return@withConnection FileOutcome.Unavailable
                }
                FileOutcome.Ok(file.readBytes())
            }
        } catch (t: Throwable) {
            log.warn("file read {}#{} failed: {}", fileId.take(8), index, t.message)
            FileOutcome.Failed(t)
        }
    }

    fun cancel(sender: String, fileId: String): FileOutcome<Unit> {
        if (!isFileId(fileId)) return FileOutcome.Refused(HubErrorCodes.ERR_BAD_REQUEST, FileMessages.BAD_ID)
        return try {
            val removed = db.withTransaction { conn ->
                val blob = selectBlob(conn, fileId)
                    ?: return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
                if (blob.senderDeviceId != sender) {
                    return@withTransaction FileOutcome.Refused(HubErrorCodes.ERR_FORBIDDEN, FileMessages.NOT_SENDER)
                }
                deleteBlobRows(conn, fileId)
                FileOutcome.Ok(Unit)
            }
            if (removed is FileOutcome.Ok) deleteFileDir(fileId)
            removed
        } catch (t: Throwable) {
            log.warn("file cancel {} failed: {}", fileId.take(8), t.message)
            FileOutcome.Failed(t)
        }
    }

    // ----- housekeeping -----------------------------------------------------------------------------------------

    /** Deletes abandoned uploads and expired files, bytes first, then rows. Returns what it removed. */
    fun sweep(nowMs: Long = clock()): SweepResult {
        val victims: List<Triple<String, Long, Boolean>> = try {
            db.withConnection { conn ->
                conn.prepareStatement(
                    "SELECT file_id, total_bytes, state FROM file_blob WHERE " +
                        "(state = ? AND upload_expires_at < ?) OR (state = ? AND expires_at < ?)",
                ).use { st ->
                    st.setString(1, FileStates.UPLOADING)
                    st.setLong(2, nowMs)
                    st.setString(3, FileStates.READY)
                    st.setLong(4, nowMs)
                    st.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(Triple(rs.getString(1), rs.getLong(2), rs.getString(3) == FileStates.UPLOADING))
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            log.warn("file sweep could not list victims: {}", t.message)
            return SweepResult(0, 0, 0L)
        }
        if (victims.isEmpty()) return SweepResult(0, 0, 0L)

        var uploads = 0
        var files = 0
        var bytes = 0L
        for ((id, size, uploading) in victims) {
            val deleted = try {
                db.withTransaction { conn ->
                    deleteBlobRows(conn, id)
                    true
                }
            } catch (t: Throwable) {
                log.warn("file sweep could not delete {}: {}", id.take(8), t.message)
                false
            }
            if (!deleted) continue
            deleteFileDir(id)
            bytes += size
            if (uploading) uploads++ else files++
        }
        if (uploads + files > 0) {
            log.info("file sweep: {} abandoned uploads, {} expired files, {} bytes freed", uploads, files, bytes)
        }
        return SweepResult(uploads, files, bytes)
    }

    /**
     * Startup consistency pass: removes chunk rows whose bytes are gone from an unfinished upload (so the sender can
     * send them again) and directories that no row refers to. A **ready** file whose bytes are missing keeps its rows:
     * downloads answer 503 and the sweeper removes it after retention, which is honest rather than silently empty.
     */
    fun reconcile(): Int {
        var repaired = 0
        try {
            val uploading = db.withConnection { conn ->
                conn.prepareStatement("SELECT file_id, chunk_count FROM file_blob WHERE state = ?").use { st ->
                    st.setString(1, FileStates.UPLOADING)
                    st.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) }
                    }
                }
            }
            for ((id, count) in uploading) {
                val present = (0 until count).filter { chunkFile(id, it).isFile }.toSet()
                db.withTransaction { conn ->
                    val rows = conn.prepareStatement("SELECT idx, bytes FROM file_chunk WHERE file_id = ?").use { st ->
                        st.setString(1, id)
                        st.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.getInt(1) to rs.getLong(2)) }
                        }
                    }
                    val orphans = rows.filter { it.first !in present }
                    if (orphans.isNotEmpty()) {
                        conn.prepareStatement("DELETE FROM file_chunk WHERE file_id = ? AND idx = ?").use { st ->
                            for ((idx, _) in orphans) {
                                st.setString(1, id)
                                st.setInt(2, idx)
                                st.addBatch()
                            }
                            st.executeBatch()
                        }
                        val freed = orphans.sumOf { it.second }
                        conn.prepareStatement(
                            "UPDATE file_blob SET received_chunks = received_chunks - ?, " +
                                "received_bytes = received_bytes - ? WHERE file_id = ?",
                        ).use { st ->
                            st.setInt(1, orphans.size)
                            st.setLong(2, freed)
                            st.setString(3, id)
                            st.executeUpdate()
                        }
                        repaired += orphans.size
                        log.info("file reconcile: {} chunk row(s) of {} had no bytes and were dropped", orphans.size, id.take(8))
                    }
                }
            }

            val known = db.withConnection { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT file_id FROM file_blob").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
            rootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in known) {
                    dir.deleteRecursively()
                    repaired += 1
                }
            }
        } catch (t: Throwable) {
            log.warn("file reconcile failed: {}", t.message)
        }
        return repaired
    }

    fun stats(): FileStatsDto {
        return try {
            db.withConnection { conn ->
                var uploading = 0
                var ready = 0
                var uploadingBytes = 0L
                var readyBytes = 0L
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT state, COUNT(*), COALESCE(SUM(total_bytes), 0) FROM file_blob GROUP BY state",
                    ).use { rs ->
                        while (rs.next()) {
                            when (rs.getString(1)) {
                                FileStates.UPLOADING -> {
                                    uploading = rs.getInt(2)
                                    uploadingBytes = rs.getLong(3)
                                }
                                FileStates.READY -> {
                                    ready = rs.getInt(2)
                                    readyBytes = rs.getLong(3)
                                }
                            }
                        }
                    }
                }
                val devices = conn.prepareStatement(
                    "SELECT sender_device_id, COUNT(*), COALESCE(SUM(total_bytes), 0) FROM file_blob " +
                        "GROUP BY sender_device_id ORDER BY SUM(total_bytes) DESC LIMIT 5",
                ).use { st ->
                    st.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(FileUsageRowDto(rs.getString(1), rs.getInt(2), rs.getLong(3)))
                            }
                        }
                    }
                }
                FileStatsDto(uploading, ready, uploadingBytes, readyBytes, devices)
            }
        } catch (t: Throwable) {
            log.warn("file stats failed: {}", t.message)
            FileStatsDto()
        }
    }

    // ----- internals --------------------------------------------------------------------------------------------

    /** The authorization every read goes through. Returns a refusal, or `null` when [requester] may read. */
    private fun access(conn: Connection, blob: BlobRow, requester: String, now: Long): FileOutcome.Refused? {
        val isSender = blob.senderDeviceId == requester
        val listed = conn.prepareStatement("SELECT 1 FROM file_recipient WHERE file_id = ? AND device_id = ?").use { st ->
            st.setString(1, blob.fileId)
            st.setString(2, requester)
            st.executeQuery().use { it.next() }
        }
        if (!isSender && !listed) {
            return FileOutcome.Refused(HubErrorCodes.ERR_FORBIDDEN, FileMessages.NOT_RECIPIENT)
        }
        if (!isSender && blob.groupId != null) {
            val live = groupMembers(blob.groupId) ?: emptyList()
            if (requester !in live) {
                return FileOutcome.Refused(HubErrorCodes.ERR_FORBIDDEN, FileMessages.NOT_RECIPIENT)
            }
        }
        if (!isSender) {
            if (blob.state != FileStates.READY) {
                return FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
            }
            if (blob.expiresAtMs in 1..now) {
                return FileOutcome.Refused(HubErrorCodes.ERR_NOT_FOUND, FileMessages.NO_SUCH_FILE)
            }
        }
        return null
    }

    private fun statusDto(conn: Connection, blob: BlobRow): FileStatusDto {
        val recipients = conn.prepareStatement("SELECT device_id FROM file_recipient WHERE file_id = ? ORDER BY device_id").use { st ->
            st.setString(1, blob.fileId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        return FileStatusDto(
            fileId = blob.fileId,
            state = blob.state,
            totalBytes = blob.totalBytes,
            chunkSize = blob.chunkSize,
            chunkCount = blob.chunkCount,
            sha256 = blob.sha256,
            senderDeviceId = blob.senderDeviceId,
            recipients = recipients,
            createdAtMs = blob.createdAtMs,
            expiresAtMs = blob.expiresAtMs,
            receivedChunks = blob.receivedChunks,
        )
    }

    private fun writeChunkFile(fileId: String, index: Int, bytes: ByteArray) {
        val dir = fileDir(fileId)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.name}")
        val target = chunkFile(fileId, index)
        val part = File(dir, "$index.part")
        Files.write(part.toPath(), bytes)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteFileDir(fileId: String) {
        try {
            fileDir(fileId).deleteRecursively()
        } catch (e: Exception) {
            log.warn("could not delete the files of {}: {}", fileId.take(8), e.message)
        }
    }

    private fun deleteBlobRows(conn: Connection, fileId: String) {
        conn.prepareStatement("DELETE FROM file_chunk WHERE file_id = ?").use { st ->
            st.setString(1, fileId)
            st.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM file_recipient WHERE file_id = ?").use { st ->
            st.setString(1, fileId)
            st.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM file_blob WHERE file_id = ?").use { st ->
            st.setString(1, fileId)
            st.executeUpdate()
        }
    }

    private fun sumBytes(conn: Connection, where: String, vararg args: Any): Long {
        val sql = "SELECT COALESCE(SUM(total_bytes), 0) FROM file_blob WHERE state IN (?, ?) AND $where"
        return conn.prepareStatement(sql).use { st ->
            st.setString(1, FileStates.UPLOADING)
            st.setString(2, FileStates.READY)
            var i = 3
            for (a in args) {
                when (a) {
                    is String -> st.setString(i, a)
                    is Long -> st.setLong(i, a)
                    else -> st.setObject(i, a)
                }
                i++
            }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    private fun countFiles(conn: Connection, sender: String): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM file_blob WHERE sender_device_id = ? AND state IN (?, ?)").use { st ->
            st.setString(1, sender)
            st.setString(2, FileStates.UPLOADING)
            st.setString(3, FileStates.READY)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun chunkTotals(conn: Connection, fileId: String): Pair<Int, Long> =
        conn.prepareStatement("SELECT COUNT(*), COALESCE(SUM(bytes), 0) FROM file_chunk WHERE file_id = ?").use { st ->
            st.setString(1, fileId)
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) to rs.getLong(2) else 0 to 0L
            }
        }

    private fun selectBlob(conn: Connection, fileId: String): BlobRow? =
        conn.prepareStatement(
            "SELECT file_id, sender_device_id, group_id, target_device_id, state, total_bytes, chunk_size, " +
                "chunk_count, sha256, received_chunks, created_at, upload_expires_at, expires_at, completed_at " +
                "FROM file_blob WHERE file_id = ?",
        ).use { st ->
            st.setString(1, fileId)
            st.executeQuery().use { rs -> if (rs.next()) readBlob(rs) else null }
        }

    private fun readBlob(rs: ResultSet) = BlobRow(
        fileId = rs.getString(1),
        senderDeviceId = rs.getString(2),
        groupId = rs.getString(3),
        targetDeviceId = rs.getString(4),
        state = rs.getString(5),
        totalBytes = rs.getLong(6),
        chunkSize = rs.getInt(7),
        chunkCount = rs.getInt(8),
        sha256 = rs.getString(9),
        receivedChunks = rs.getInt(10),
        createdAtMs = rs.getLong(11),
        uploadExpiresAtMs = rs.getLong(12),
        expiresAtMs = rs.getLong(13),
        completedAtMs = rs.getLong(14).takeIf { it > 0 },
    )

    private fun selectChunk(conn: Connection, fileId: String, index: Int): ChunkRow? =
        conn.prepareStatement("SELECT bytes, sha256 FROM file_chunk WHERE file_id = ? AND idx = ?").use { st ->
            st.setString(1, fileId)
            st.setInt(2, index)
            st.executeQuery().use { rs ->
                if (rs.next()) ChunkRow(rs.getLong(1), rs.getString(2)) else null
            }
        }

    private fun fileDir(fileId: String) = File(rootDir, fileId)

    private fun chunkFile(fileId: String, index: Int) = File(fileDir(fileId), "$index.chunk")

    private fun expectedChunkBytes(blob: BlobRow, index: Int): Long =
        if (index == blob.chunkCount - 1) {
            blob.totalBytes - blob.chunkSize.toLong() * (blob.chunkCount - 1)
        } else {
            blob.chunkSize.toLong()
        }

    private data class BlobRow(
        val fileId: String,
        val senderDeviceId: String,
        val groupId: String?,
        val targetDeviceId: String?,
        val state: String,
        val totalBytes: Long,
        val chunkSize: Int,
        val chunkCount: Int,
        val sha256: String,
        val receivedChunks: Int,
        val createdAtMs: Long,
        val uploadExpiresAtMs: Long,
        val expiresAtMs: Long,
        val completedAtMs: Long?,
    )

    private data class ChunkRow(val bytes: Long, val sha256: String)

    companion object {
        /** Migration component name. */
        const val COMPONENT: String = "files"

        /** The only schema step so far. */
        const val SCHEMA_VERSION: Int = 1

        val MIGRATIONS: List<Migration> = listOf(
            Migration(SCHEMA_VERSION, "sealed file metadata, chunks and recipients") { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS file_blob (
                            file_id TEXT PRIMARY KEY,
                            sender_device_id TEXT NOT NULL,
                            group_id TEXT,
                            target_device_id TEXT,
                            state TEXT NOT NULL,
                            total_bytes INTEGER NOT NULL,
                            chunk_size INTEGER NOT NULL,
                            chunk_count INTEGER NOT NULL,
                            sha256 TEXT NOT NULL,
                            name_hint TEXT,
                            received_chunks INTEGER NOT NULL DEFAULT 0,
                            received_bytes INTEGER NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL,
                            upload_expires_at INTEGER NOT NULL,
                            expires_at INTEGER NOT NULL DEFAULT 0,
                            completed_at INTEGER
                        )
                        """.trimIndent(),
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS file_recipient (" +
                            "file_id TEXT NOT NULL, device_id TEXT NOT NULL, PRIMARY KEY(file_id, device_id))",
                    )
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS file_chunk (
                            file_id TEXT NOT NULL,
                            idx INTEGER NOT NULL,
                            bytes INTEGER NOT NULL,
                            sha256 TEXT NOT NULL,
                            PRIMARY KEY(file_id, idx)
                        )
                        """.trimIndent(),
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS file_blob_state ON file_blob(state, expires_at)")
                    st.execute("CREATE INDEX IF NOT EXISTS file_blob_sender ON file_blob(sender_device_id, state)")
                    st.execute("CREATE INDEX IF NOT EXISTS file_recipient_device ON file_recipient(device_id)")
                }
            },
        )

        /** Opens the store, applying its migration and running the startup consistency pass. */
        fun open(
            db: ServerDatabase,
            rootDir: File,
            clock: () -> Long = System::currentTimeMillis,
            limits: () -> FileLimits = { FileLimits() },
            groupMembers: (String) -> List<String>? = { null },
            knownDevice: (String) -> Boolean = { true },
        ): FileStore {
            Migrator(db).apply { register(COMPONENT, MIGRATIONS) }.migrate()
            rootDir.mkdirs()
            return FileStore(db, rootDir, clock, limits, groupMembers, knownDevice).also { it.reconcile() }
        }

        /** Ceil(`total / chunk`), the only chunk arithmetic the hub accepts. */
        fun chunkCountOf(totalBytes: Long, chunkSize: Int): Int =
            if (chunkSize <= 0) 0 else ((totalBytes + chunkSize - 1) / chunkSize).toInt()

        /** 32 lowercase hex characters, and nothing else. This is the only value that becomes a path component. */
        fun isFileId(id: String): Boolean =
            id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }

        /** 64 lowercase hex characters. */
        fun isSha256(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
