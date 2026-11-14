@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.security.identity.IdentityKeyVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Desktop [GroupEpochKeyStore]: stores group epoch keys protected by the same [IdentityKeyVault]
 * (Windows DPAPI or owner-only sealed vault) and atomic `0600` temp-file swap as [FileHubSealingKeyStore].
 */
@FlashInternalApi
public class FileHubGroupEpochKeyStore(
    private val file: File,
    private val vault: IdentityKeyVault,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GroupEpochKeyStore {

    override suspend fun loadAll(): List<GroupEpochKey> = withContext(ioDispatcher) {
        if (!file.isFile) return@withContext emptyList()
        val plain = try {
            vault.unprotect(file.readBytes())
        } catch (t: Throwable) {
            FlashLog.e("SECURITY", "Failed to decrypt persisted Hub group epoch keys at ${file.name}", t)
            throw IllegalStateException("Failed to decrypt persisted Hub group epoch keys", t)
        }
        try {
            GroupEpochKeyCodec.decode(plain)
        } catch (t: Throwable) {
            FlashLog.e("SECURITY", "Corrupt persisted Hub group epoch key payload at ${file.name}", t)
            throw IllegalStateException("Corrupt persisted Hub group epoch key payload", t)
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun saveAll(keys: List<GroupEpochKey>): Unit = withContext(ioDispatcher) {
        val plain = GroupEpochKeyCodec.encode(keys)
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
