package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.identity.IdentityKeyVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * [HubSealingKeyStore] for the desktop (ADR-126): the key lives in [file], protected at rest by the SAME vault as the
 * desktop device identity ([IdentityKeyVault]: Windows DPAPI, otherwise the sealed key file / keyring of the Linux plan).
 * The file is written whole to a private temp file and moved into place, so a crash never leaves half a key, and it is
 * created owner-only (`0600`) where the file system has POSIX modes.
 *
 * Stated plainly (as in ADR-035): code running as the same desktop user can unprotect the blob. A copy of the file
 * taken from the disk is not enough to open it.
 */
@FlashInternalApi
public class FileHubSealingKeyStore(
    private val file: File,
    private val vault: IdentityKeyVault,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HubSealingKeyStore {

    override suspend fun load(): HubSealingKeyPair? = withContext(ioDispatcher) {
        if (!file.isFile) return@withContext null
        val plain = runCatching { vault.unprotect(file.readBytes()) }.getOrNull() ?: return@withContext null
        try {
            HubSealingKeyCodec.decode(plain)
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun save(key: HubSealingKeyPair): Unit = withContext(ioDispatcher) {
        val plain = HubSealingKeyCodec.encode(key)
        val blob = try {
            vault.protect(plain)
        } finally {
            plain.fill(0)
        }
        writeOwnerOnly(file, blob)
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        Files.deleteIfExists(file.toPath())
        Unit
    }
}

/** [HubSealingPinStore] over a small text file (public fingerprints; owner-only where possible). */
@FlashInternalApi
public class FileHubSealingPinStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HubSealingPinStore {

    override suspend fun load(): Map<String, HubSealingPin> = withContext(ioDispatcher) {
        if (!file.isFile) emptyMap() else HubSealingPinCodec.decode(runCatching { file.readText() }.getOrNull())
    }

    override suspend fun save(pins: Map<String, HubSealingPin>): Unit = withContext(ioDispatcher) {
        writeOwnerOnly(file, HubSealingPinCodec.encode(pins).encodeToByteArray())
    }
}

/** Writes [bytes] to a private temp file next to [target] and moves it over [target]. */
private fun writeOwnerOnly(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val path = target.toPath()
    val tmp = path.resolveSibling(target.name + "." + UUID.randomUUID() + ".tmp")
    try {
        try {
            Files.createFile(
                tmp,
                PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
            )
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system (Windows: the vault is DPAPI, the user profile folder is already private).
            Files.createFile(tmp)
        }
        Files.write(tmp, bytes)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    } catch (failure: Exception) {
        Files.deleteIfExists(tmp)
        throw failure
    }
}
