package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.engine.FlashPathSanitizer
import com.transfer.flash.core.swarm.model.FileIdentity
import com.transfer.flash.core.swarm.model.PartialHandle
import com.transfer.flash.core.swarm.model.PieceStorage
import com.transfer.flash.core.swarm.model.SourceHandle
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Desktop/JVM filesystem implementation of [PieceStorage] (§5.2, SW-7).
 *
 * @param partialDir Directory for app-private `.part` files (e.g. `~/.flash/swarm/partial`).
 * @param destinationDir Directory for completed/finalized downloads (e.g. `FlashReceived` or `~/Downloads/Flash`).
 */
public class JvmPieceStorage(
    public val partialDir: File,
    public val destinationDir: File,
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

        // 2. Resolve the destination: serialize per directory, reserve the name atomically, then move onto it
        destinationDir.mkdirs()
        val safeName = sanitizeFileName(fileName)
        val canonicalDestDir = destinationDir.canonicalFile

        val destPrefix = if (canonicalDestDir.path.endsWith(File.separator)) canonicalDestDir.path else canonicalDestDir.path + File.separator
        // Two finalizes of the same name (two groups, two members' copies of "photo.jpg") used to both see "free" and then
        // the second move replaced the first file. The lock keeps the in-process case serial; the exclusive create below
        // is what protects against another process and against the user's own files.
        val targetFile = finalizeLockFor(canonicalDestDir.path).withLock {
            val base: String
            val ext: String
            val dotIndex = safeName.lastIndexOf('.')
            if (dotIndex > 0) {
                base = safeName.substring(0, dotIndex)
                ext = safeName.substring(dotIndex)
            } else {
                base = safeName
                ext = ""
            }
            var reserved: File? = null
            var count = 0
            while (reserved == null) {
                val candidateName = if (count == 0) safeName else "$base ($count)$ext"
                val candidate = File(canonicalDestDir, candidateName).canonicalFile
                val contained = candidate.path.startsWith(destPrefix, ignoreCase = (File.separatorChar == '\\')) ||
                    candidate.toPath().normalize().startsWith(canonicalDestDir.toPath().normalize())
                require(contained) {
                    "Path traversal escape detected for destination: $candidateName (file=${candidate.path}, prefix=$destPrefix)"
                }
                try {
                    // CREATE_NEW is an exclusive create (O_EXCL): it fails if ANYTHING already has this name.
                    Files.createFile(candidate.toPath())
                    reserved = candidate
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    count++
                }
            }
            checkNotNull(reserved)
        }

        // 3. Move the partial file onto the placeholder this call created. REPLACE_EXISTING is only ever applied to
        // that placeholder, never to a file that was already there.
        try {
            try {
                Files.move(file.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                try {
                    Files.move(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // The placeholder is ours; remove it so a failed finalize leaves no empty file behind.
            runCatching { targetFile.delete() }
            return@withContext StorageFinalizeResult(
                ok = false,
                errorMessage = "Failed to move file to destination: ${e.message}",
            )
        }

        val identity = FileIdentity(sizeBytes = targetFile.length(), lastModifiedMs = targetFile.lastModified())
        StorageFinalizeResult(
            ok = true,
            finalPath = targetFile.absolutePath,
            identity = identity,
        )
    }

    public companion object {
        /** One lock per destination directory, shared by every storage instance in the process. */
        private val finalizeLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun finalizeLockFor(dir: String): Mutex = finalizeLocks.getOrPut(dir) { Mutex() }

        /** See [com.transfer.flash.core.engine.FlashPathSanitizer.sanitizeFileName]: one rule set for every writer. */
        public fun sanitizeFileName(raw: String): String = FlashPathSanitizer.sanitizeFileName(raw)
    }
}
