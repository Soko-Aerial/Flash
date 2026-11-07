package com.transfer.flash.core.engine

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** Atomic even on the older Android filesystem implementation. */
internal expect fun createReceiveReservation(path: Path): Boolean

/** Publishes an assembled file under the host's receive root and returns the path to keep in history. */
public object ReceivedFilePlacement {
    public fun publish(
        source: String,
        root: String,
        fileName: String,
        mime: String? = null,
        preserveFolders: Boolean = false,
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): String {
        val from = source.toPath()
        val rootPath = root.toPath()
        fileSystem.createDirectories(rootPath)
        val canonicalRoot = fileSystem.canonicalize(rootPath)
        val relative = if (preserveFolders) FlashPathSanitizer.sanitizeRelativePath(fileName).split('/') else {
            listOf(FlashPathSanitizer.sanitizeFileName(fileName))
        }
        val safeName = relative.last()
        val parents = if (relative.size > 1) listOf("Folders") + relative.dropLast(1) else listOf(category(safeName, mime))
        val directory = parents.fold(canonicalRoot) { parent, name ->
            val child = parent / name
            fileSystem.createDirectory(child, mustCreate = false)
            if (fileSystem.canonicalize(child) != child) throw IOException("Linked receive folder")
            child
        }
        val sourceParent = fileSystem.canonicalize(from).parent
        val target = reserve(fileSystem, directory, safeName)
        try {
            try {
                fileSystem.atomicMove(from, target)
            } catch (_: IOException) {
                // Cross-volume placement keeps the source until the copy is complete.
                val scratch = reserve(fileSystem, directory, ".${safeName}.receiving")
                try {
                    fileSystem.copy(from, scratch)
                    fileSystem.atomicMove(scratch, target)
                    runCatching { fileSystem.delete(from) }
                } finally {
                    runCatching { fileSystem.delete(scratch, mustExist = false) }
                }
            }
        } catch (e: IOException) {
            runCatching { fileSystem.delete(target, mustExist = false) }
            throw e
        }
        if (preserveFolders && sourceParent != directory) {
            removeEmptyStagingParents(fileSystem, sourceParent, canonicalRoot)
        }
        return target.toString()
    }

    private fun reserve(fileSystem: FileSystem, directory: Path, name: String): Path {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        for (index in 0..9999) {
            val candidate = directory / if (index == 0) name else "${name.take(dot)} ($index)${name.drop(dot)}"
            if (fileSystem === FileSystem.SYSTEM) {
                if (createReceiveReservation(candidate)) return candidate
                continue
            }
            try {
                fileSystem.sink(candidate, mustCreate = true).close()
                return candidate
            } catch (e: IOException) {
                if (fileSystem.metadataOrNull(candidate) == null) throw e
            }
        }
        throw IOException("No available receive filename")
    }

    private fun removeEmptyStagingParents(fileSystem: FileSystem, sourceParent: Path?, root: Path) {
        if (generateSequence(sourceParent) { it.parent }.none { it == root }) return
        var parent = sourceParent
        while (parent != null && parent != root) {
            val current = parent
            val removed = runCatching {
                if (fileSystem.list(current).isNotEmpty()) false else {
                    fileSystem.delete(current)
                    true
                }
            }.getOrDefault(false)
            if (!removed) break
            parent = current.parent
        }
    }

    private fun category(name: String, mime: String?): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            (type.startsWith("audio/") || extension in audioExtensions) &&
                name.substringBeforeLast('.').equals("Voice message", ignoreCase = true) -> "Voice messages"
            type.startsWith("image/") || extension in imageExtensions -> "Images"
            type.startsWith("video/") || extension in videoExtensions -> "Videos"
            type.startsWith("audio/") || extension in audioExtensions -> "Audio"
            extension in archiveExtensions -> "Archives"
            type.startsWith("text/") || extension in documentExtensions -> "Documents"
            else -> "Other"
        }
    }

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "svg", "tif", "tiff")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "wmv", "mpeg", "mpg")
    private val audioExtensions = setOf("wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "wma", "aiff")
    private val archiveExtensions = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz")
    private val documentExtensions = setOf("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "odt", "ods", "odp", "rtf", "epub")
}
