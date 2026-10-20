package com.transfer.flash.core.enterprise.files

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Android actuals for [HubFileDisk]. Different from the desktop file only in [replaceAtomically] and [move]: minSdk 24
 * has no `java.nio.file`, and `File.renameTo` over an existing file is an atomic `rename(2)` on Android's file systems.
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
        if (!temp.renameTo(target)) throw IOException("cannot replace file")
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
        if (!File(from).renameTo(File(to))) throw IOException("cannot move file")
    }

    actual fun usableSpace(directory: String): Long = File(directory).usableSpace
}
