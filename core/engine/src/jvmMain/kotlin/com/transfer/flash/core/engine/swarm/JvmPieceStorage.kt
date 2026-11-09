package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.engine.ReceivedFilePlacement
import com.transfer.flash.core.engine.FlashPathSanitizer
import com.transfer.flash.core.swarm.model.FileIdentity
import com.transfer.flash.core.swarm.model.PartialHandle
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SourceHandle
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Desktop/JVM filesystem implementation of [PieceStorage] (§5.2, SW-7).
 *
 * @param partialDir Directory for app-private `.part` files (e.g. `~/.flash/swarm/partial`).
 * @param destinationDir Directory for completed/finalized downloads (e.g. `FlashReceived` or `~/Downloads/Flash`).
 * @param onPlaced Called with the final file after a download was moved into [destinationDir] (the desktop host records it
 *   so "Clear received files" deletes only what Flash wrote).
 */
public class JvmPieceStorage(
    public val partialDir: File,
    public val destinationDir: File,
    private val onPlaced: (File) -> Unit = {},
) : PieceStorage {

    private fun partialFileForKey(key: String): File {
        require(!key.contains("..") && !key.contains('/') && !key.contains('\\')) {
            "Path traversal escape detected for partial key: $key"
        }
        val fileName = if (key.endsWith(".part")) key else "$key.part"
        if (!partialDir.exists()) {
            partialDir.mkdirs()
        }
        val canonicalDir = partialDir.canonicalFile
        val file = File(canonicalDir, fileName).canonicalFile
        val prefix = if (canonicalDir.path.endsWith(File.separator)) canonicalDir.path else canonicalDir.path + File.separator
        val isContained = file.path.startsWith(prefix, ignoreCase = (File.separatorChar == '\\')) ||
            file.toPath().normalize().startsWith(canonicalDir.toPath().normalize())
        require(isContained) {
            "Path traversal escape detected for partial key: $key (file=${file.path}, prefix=$prefix)"
        }
        return file
    }

    override suspend fun openSource(uri: String): SourceHandle? = withContext(Dispatchers.IO) {
        val file = runCatching {
            if (uri.startsWith("file:")) {
                File(URI(uri))
            } else {
                File(uri)
            }
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
    ): StorageFinalizeResult = withContext(Dispatchers.IO) {
        val file = runCatching { partialFileForKey(key) }.getOrNull()
            ?: return@withContext StorageFinalizeResult(ok = false, errorMessage = "Invalid partial key: $key")

        if (!file.exists()) {
            return@withContext StorageFinalizeResult(ok = false, errorMessage = "Partial file not found for key: $key")
        }

        // 1. Whole-file SHA-256 pass
        val md = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(65536)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    md.update(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            return@withContext StorageFinalizeResult(ok = false, errorMessage = "Failed to hash partial file: ${e.message}")
        }

        val actualSha256 = md.digest()
        if (!actualSha256.contentEquals(expectedSha256)) {
            return@withContext StorageFinalizeResult(
                ok = false,
                errorMessage = "Whole-file SHA-256 mismatch",
            )
        }

        val targetFile = try {
            File(ReceivedFilePlacement.publish(file.path, destinationDir.path, fileName, mime))
        } catch (e: java.io.IOException) {
            return@withContext StorageFinalizeResult(ok = false, errorMessage = "Failed to place received file: ${e.message}")
        }

        runCatching { onPlaced(targetFile) }
        val identity = FileIdentity(sizeBytes = targetFile.length(), lastModifiedMs = targetFile.lastModified())
        StorageFinalizeResult(
            ok = true,
            finalPath = targetFile.absolutePath,
            identity = identity,
        )
    }

    public companion object {
        /** See [com.transfer.flash.core.engine.FlashPathSanitizer.sanitizeFileName]: one rule set for every writer. */
        public fun sanitizeFileName(raw: String): String = FlashPathSanitizer.sanitizeFileName(raw)
    }
}
