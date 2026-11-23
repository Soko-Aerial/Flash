package com.transfer.flash.core.engine.swarm

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.transfer.flash.core.engine.FlashPathSanitizer
import com.transfer.flash.core.swarm.model.FileIdentity
import com.transfer.flash.core.swarm.model.ManifestPieceStorage
import com.transfer.flash.core.swarm.model.PartialHandle
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SourceHandle
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import com.transfer.flash.core.swarm.model.SwarmManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import okio.Path.Companion.toPath

/**
 * Android implementation of [PieceStorage] (§5.2, SW-7).
 *
 * Random-access reading of origin source files supports:
 * 1. `content://` URIs via [android.content.ContentResolver.openFileDescriptor] and positional [FileChannel.read].
 * 2. `file://` URIs and filesystem paths via [RandomAccessFile] / [FileChannel].
 *
 * Partial (.part) files are stored in app-private storage:
 * `<context.filesDir>/swarm/partial/<key>.part`.
 *
 * Completed files are moved to [destinationDir] (defaulting to `<externalFilesDir>/FlashReceived`).
 */
public class AndroidPieceStorage(
    private val context: Context,
    public val partialDir: File = File(context.filesDir, "swarm/partial"),
    public val destinationDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "FlashReceived"),
) : ManifestPieceStorage {

    private fun partialFileForKey(key: String): File {
        require(!key.contains("..") && !key.contains('/') && !key.contains('\\')) {
            "Path traversal escape detected for partial key: $key"
        }
        val fileName = if (key.endsWith(".part")) key else "$key.part"
        val canonicalDir = partialDir.canonicalFile
        val file = File(canonicalDir, fileName).canonicalFile
        val prefix = if (canonicalDir.path.endsWith(File.separator)) canonicalDir.path else canonicalDir.path + File.separator
        require(file.path.startsWith(prefix)) {
            "Path traversal escape detected for partial key: $key"
        }
        return file
    }

    override suspend fun openSource(uri: String): SourceHandle? = withContext(Dispatchers.IO) {
        if (uri.startsWith("content://")) {
            val parsedUri = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null
            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(parsedUri, "r")
            }.getOrNull() ?: return@withContext null

            val fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel

            object : SourceHandle {
                override suspend fun readAt(offset: Long, into: ByteArray, length: Int): Int = withContext(Dispatchers.IO) {
                    val buf = ByteBuffer.wrap(into, 0, length)
                    var totalRead = 0
                    while (buf.hasRemaining()) {
                        val read = channel.read(buf, offset + totalRead)
                        if (read < 0) {
                            if (totalRead == 0) return@withContext -1
                            break
                        }
                        if (read == 0) break
                        totalRead += read
                    }
                    totalRead
                }

                override suspend fun identity(): FileIdentity? = withContext(Dispatchers.IO) {
                    var size: Long = -1L
                    var lastModified: Long = 0L
                    runCatching {
                        context.contentResolver.query(
                            parsedUri,
                            arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                    size = cursor.getLong(sizeIdx)
                                }
                                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                                if (modIdx >= 0 && !cursor.isNull(modIdx)) {
                                    lastModified = cursor.getLong(modIdx)
                                }
                            }
                        }
                    }
                    if (size < 0) {
                        size = runCatching { pfd.statSize }.getOrDefault(-1L)
                    }
                    if (size < 0) null
                    else FileIdentity(sizeBytes = size, lastModifiedMs = lastModified)
                }

                override fun close() {
                    runCatching { channel.close() }
                    runCatching { fis.close() }
                    runCatching { pfd.close() }
                }
            }
        } else {
            val file = runCatching {
                if (uri.startsWith("file:")) File(Uri.parse(uri).path ?: "")
                else File(uri)
            }.getOrNull() ?: return@withContext null

            if (!file.exists() || !file.canRead() || file.isDirectory) {
                return@withContext null
            }

            val raf = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return@withContext null
            val channel = raf.channel

            object : SourceHandle {
                override suspend fun readAt(offset: Long, into: ByteArray, length: Int): Int = withContext(Dispatchers.IO) {
                    val buf = ByteBuffer.wrap(into, 0, length)
                    var totalRead = 0
                    while (buf.hasRemaining()) {
                        val read = channel.read(buf, offset + totalRead)
                        if (read < 0) {
                            if (totalRead == 0) return@withContext -1
                            break
                        }
                        if (read == 0) break
                        totalRead += read
                    }
                    totalRead
                }

                override suspend fun identity(): FileIdentity? = withContext(Dispatchers.IO) {
                    if (!file.exists()) null
                    else FileIdentity(sizeBytes = file.length(), lastModifiedMs = file.lastModified())
                }

                override fun close() {
                    runCatching { channel.close() }
                    runCatching { raf.close() }
                }
            }
        }
    }

    override suspend fun openPartial(key: String, size: Long): PartialHandle? = withContext(Dispatchers.IO) {
        partialDir.mkdirs()
        val file = partialFileForKey(key)
        file.parentFile?.mkdirs()

        val raf = runCatching { RandomAccessFile(file, "rw") }.getOrNull() ?: return@withContext null
        if (size > 0 && raf.length() < size) {
            runCatching { raf.setLength(size) }
        }
        val channel = raf.channel

        object : PartialHandle {
            override suspend fun writeAt(offset: Long, bytes: ByteArray, length: Int): Unit = withContext(Dispatchers.IO) {
                val buf = ByteBuffer.wrap(bytes, 0, length)
                var totalWritten = 0
                while (buf.hasRemaining()) {
                    val written = channel.write(buf, offset + totalWritten)
                    if (written <= 0) break
                    totalWritten += written
                }
            }

            override suspend fun sync(): Unit = withContext(Dispatchers.IO) {
                channel.force(false)
            }

            override suspend fun readAt(offset: Long, into: ByteArray, length: Int): Int = withContext(Dispatchers.IO) {
                val buf = ByteBuffer.wrap(into, 0, length)
                var totalRead = 0
                while (buf.hasRemaining()) {
                    val read = channel.read(buf, offset + totalRead)
                    if (read < 0) {
                        if (totalRead == 0) return@withContext -1
                        break
                    }
                    if (read == 0) break
                    totalRead += read
                }
                totalRead
            }

            override suspend fun identity(): FileIdentity? = withContext(Dispatchers.IO) {
                if (!file.exists()) null
                else FileIdentity(sizeBytes = file.length(), lastModifiedMs = file.lastModified())
            }

            override fun close() {
                runCatching { channel.force(false) }
                runCatching { channel.close() }
                runCatching { raf.close() }
            }
        }
    }

    override suspend fun freeBytesFor(key: String): Long = withContext(Dispatchers.IO) {
        if (!partialDir.exists()) {
            partialDir.mkdirs()
        }
        partialDir.usableSpace
    }

    override suspend fun deletePartial(key: String): Unit = withContext(Dispatchers.IO) {
        val file = runCatching { partialFileForKey(key) }.getOrNull() ?: return@withContext
        if (file.exists()) {
            file.delete()
        }
    }

    override suspend fun purgeOrphanedPartials(activeKeys: Set<String>): Unit = withContext(Dispatchers.IO) {
        val files = partialDir.listFiles() ?: return@withContext
        for (file in files) {
            if (file.isFile && file.name.endsWith(".part")) {
                val key = file.name.removeSuffix(".part")
                if (key !in activeKeys) {
                    file.delete()
                }
            }
        }
    }

    override suspend fun finalize(
        key: String,
        fileName: String,
        mime: String,
        expectedSha256: ByteArray,
    ): StorageFinalizeResult = finalizeFile(key, fileName, mime, expectedSha256, null)

    override suspend fun finalize(
        key: String,
        fileName: String,
        mime: String,
        manifest: SwarmManifest,
    ): StorageFinalizeResult = finalizeFile(key, fileName, mime, manifest.fileSha256, manifest)

    private suspend fun finalizeFile(
        key: String,
        fileName: String,
        mime: String,
        expectedSha256: ByteArray,
        manifest: SwarmManifest?,
    ): StorageFinalizeResult = withContext(Dispatchers.IO) {
        val file = runCatching { partialFileForKey(key) }.getOrNull()
            ?: return@withContext StorageFinalizeResult(ok = false, errorMessage = "Invalid partial key: $key")
        finalizeSwarmFile(file.path.toPath(), destinationDir.path.toPath(), fileName, mime, expectedSha256, manifest)
    }

    public companion object {
        /** See [com.transfer.flash.core.engine.FlashPathSanitizer.sanitizeFileName]: one rule set for every writer. */
        public fun sanitizeFileName(raw: String): String = FlashPathSanitizer.sanitizeFileName(raw)
    }
}
