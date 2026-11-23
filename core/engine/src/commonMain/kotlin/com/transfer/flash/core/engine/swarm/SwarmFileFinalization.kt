package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.engine.ReceivedFilePlacement
import com.transfer.flash.core.swarm.model.FileIdentity
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.StorageFinalizeResult
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.transfer.chunked.IncrementalSha256
import com.transfer.flash.core.transfer.chunked.Sha256
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

internal fun finalizeSwarmFile(
    partial: Path,
    destinationDir: Path,
    fileName: String,
    mime: String,
    expectedSha256: ByteArray,
    manifest: SwarmManifest?,
): StorageFinalizeResult = try {
    verifyAndPublish(partial, destinationDir, fileName, mime, expectedSha256, manifest)
} catch (error: IOException) {
    if (manifest != null) throw error
    StorageFinalizeResult(ok = false, errorMessage = "Failed to finalize partial file: ${error.message}")
}

private fun verifyAndPublish(
    partial: Path,
    destinationDir: Path,
    fileName: String,
    mime: String,
    expectedSha256: ByteArray,
    manifest: SwarmManifest?,
): StorageFinalizeResult {
    val fileSystem = FileSystem.SYSTEM
    val physicalSize = fileSystem.metadataOrNull(partial)?.size ?: return StorageFinalizeResult(
        ok = false,
        badPieces = manifest?.pieceHashes?.indices?.toList().orEmpty(),
        errorMessage = "Partial file not found",
    )
    val expectedSize = manifest?.totalSize ?: physicalSize
    val digest = IncrementalSha256()
    val buffer = ByteArray(65_536)
    var remaining = expectedSize
    fileSystem.source(partial).buffer().use { source ->
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }
    if (remaining != 0L || !digest.digestRaw().contentEquals(expectedSha256)) {
        return StorageFinalizeResult(
            ok = false,
            badPieces = manifest?.let { findBadPieces(fileSystem, partial, it) }.orEmpty(),
            errorMessage = "Whole-file SHA-256 mismatch",
        )
    }

    // A resumed partial can have a stale tail. Only the verified manifest bytes may be published.
    val size = fileSystem.metadata(partial).size ?: throw IOException("Partial file size is unavailable")
    if (size < expectedSize || (manifest == null && size != expectedSize)) {
        throw IOException("Partial file length changed during verification")
    }
    if (size > expectedSize) {
        fileSystem.openReadWrite(partial, mustExist = true).use { file -> file.resize(expectedSize) }
    }
    val finalPath = ReceivedFilePlacement.publish(partial.toString(), destinationDir.toString(), fileName, mime)
    val metadata = runCatching { fileSystem.metadataOrNull(finalPath.toPath()) }.getOrNull()
    return StorageFinalizeResult(
        ok = true,
        finalPath = finalPath,
        identity = FileIdentity(sizeBytes = expectedSize, lastModifiedMs = metadata?.lastModifiedAtMillis ?: 0L),
    )
}

private fun findBadPieces(fileSystem: FileSystem, partial: Path, manifest: SwarmManifest): List<Int> {
    val size = fileSystem.metadata(partial).size ?: 0L
    return fileSystem.source(partial).buffer().use { source ->
        manifest.pieceHashes.indices.filter { index ->
            val length = PieceMath.pieceLength(index, manifest.totalSize, manifest.pieceSize)
            val end = PieceMath.pieceOffset(index, manifest.pieceSize) + length
            end > size || !Sha256.digest(source.readByteArray(length.toLong())).contentEquals(manifest.pieceHashes[index])
        }
    }
}
