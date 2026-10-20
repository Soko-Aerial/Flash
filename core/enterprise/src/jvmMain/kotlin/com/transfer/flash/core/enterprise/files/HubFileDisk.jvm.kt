package com.transfer.flash.core.enterprise.files

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop actuals for [HubFileDisk]. Different from the Android file only in [replaceAtomically] and [move], which use
 * `java.nio.file` (an atomic replace on Windows needs it; Android's minSdk 24 predates `java.nio.file`).
 */
internal actual object HubFileDisk {
    actual fun ensureDirectory(path: String) {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create directory")
    }

    actual fun readAll(path: String, maxBytes: Int): ByteArray? {
        val file = File(path)
        if (!file.isFile || file.length() > maxBytes) return null
        return file.readBytes()
    }

    actual fun replaceAtomically(path: String, bytes: ByteArray) {
        val target = File(path)
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    actual fun delete(path: String) {
        val file = File(path)
        if (file.exists() && !file.delete() && file.exists()) throw IOException("cannot delete file")
    }

    actual fun list(directory: String): List<String> =
        File(directory).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    actual fun writeAt(path: String, offset: Long, bytes: ByteArray) {
        RandomAccessFile(path, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
            raf.fd.sync()
        }
    }

    actual fun readAt(path: String, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        RandomAccessFile(path, "r").use { raf ->
            raf.seek(offset)
            try {
                raf.readFully(out)
            } catch (e: EOFException) {
                throw IOException("file shorter than expected", e)
            }
        }
        return out
    }

    actual fun length(path: String): Long {
        val file = File(path)
        return if (file.isFile) file.length() else -1L
    }

    actual fun move(from: String, to: String) {
        Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    actual fun usableSpace(directory: String): Long = File(directory).usableSpace
}
