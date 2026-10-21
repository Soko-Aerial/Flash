package com.transfer.flash.core.engine.store

import com.transfer.flash.core.persistence.db.dao.TransferChunkDao
import com.transfer.flash.core.persistence.db.dao.TransferDao
import com.transfer.flash.core.persistence.db.entity.TransferChunkEntity
import com.transfer.flash.core.persistence.db.entity.TransferEntity
import com.transfer.flash.core.transfer.store.TransferStore

/**
 * Room adapter for the transfer-owned [TransferStore] port (Phase 4 dependency inversion, ADR-024).
 *
 * Lives in `core:engine` — the only module that already `api`s both `core:transfer` and
 * `core:persistence`, so wiring the port to Room here introduces no dependency cycle. This keeps
 * Room / SQLCipher entirely off `core:transfer`'s classpath: a lightweight consumer that wants
 * transfers without a database simply passes `store = null`.
 *
 * Each method mirrors exactly what [com.transfer.flash.core.transfer.RealFlashTransferRepository]
 * used to call directly on the DAOs, so behavior is unchanged.
 */
public class RoomTransferStore(
    private val transferDao: TransferDao,
    private val transferChunkDao: TransferChunkDao,
) : TransferStore {

    override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) {
        // Keep the identity columns of a row that already exists (the write-behind identity save may have got there
        // first); a REPLACE would blank them.
        if (transferDao.resetProgress(transferId, totalBytes, status) > 0) return
        transferDao.insert(
            TransferEntity(
                transferId = transferId,
                totalBytes = totalBytes,
                bytesDone = 0L,
                status = status,
            ),
        )
    }

    override suspend fun saveIdentity(row: TransferStore.StoredTransfer) {
        val updated = transferDao.updateIdentity(
            transferId = row.transferId,
            totalBytes = row.totalBytes,
            bytesDone = row.bytesDone,
            status = row.state,
            peerDeviceId = row.peerDeviceId,
            peerName = row.peerName,
            fileName = row.fileName,
            direction = row.direction,
            sourceUri = row.sourceUri,
            wireFileId = row.wireFileId,
            localPath = row.localPath,
            errorMessage = row.errorMessage,
            updatedAtMs = row.updatedAtMs,
        )
        if (updated == 0) {
            transferDao.insert(
                TransferEntity(
                    transferId = row.transferId,
                    totalBytes = row.totalBytes,
                    bytesDone = row.bytesDone,
                    status = row.state,
                    peerDeviceId = row.peerDeviceId,
                    peerName = row.peerName,
                    fileName = row.fileName,
                    direction = row.direction,
                    sourceUri = row.sourceUri,
                    wireFileId = row.wireFileId,
                    localPath = row.localPath,
                    errorMessage = row.errorMessage,
                    updatedAtMs = row.updatedAtMs,
                ),
            )
        }
    }

    override suspend fun restorableTransfers(sinceMs: Long): List<TransferStore.StoredTransfer> =
        transferDao.restorable(sinceMs).map {
            TransferStore.StoredTransfer(
                transferId = it.transferId,
                peerDeviceId = it.peerDeviceId,
                peerName = it.peerName,
                fileName = it.fileName,
                direction = it.direction,
                totalBytes = it.totalBytes,
                bytesDone = it.bytesDone,
                state = it.status,
                errorMessage = it.errorMessage,
                sourceUri = it.sourceUri,
                wireFileId = it.wireFileId,
                localPath = it.localPath,
                updatedAtMs = it.updatedAtMs,
            )
        }

    override suspend fun dismissStored(transferIds: List<String>) {
        if (transferIds.isNotEmpty()) transferDao.dismiss(transferIds)
    }

    override suspend fun deleteStoredBefore(beforeMs: Long): Int = transferDao.deleteBefore(beforeMs)

    override suspend fun setBytesDone(transferId: String, bytesDone: Long) {
        transferDao.setBytesDone(transferId, bytesDone)
    }

    override suspend fun setStatus(transferId: String, status: String) {
        transferDao.setStatus(transferId, status)
    }

    override suspend fun doneChunks(transferId: String): List<Int> =
        transferChunkDao.doneChunks(transferId)

    override suspend fun markChunksDone(transferId: String, indexes: List<Int>) {
        transferChunkDao.insertAll(indexes.map { TransferChunkEntity(transferId, it, done = true) })
    }

    override suspend fun clearDoneChunks(transferId: String) {
        transferChunkDao.deleteChunks(transferId)
    }

    override suspend fun purgeFinishedChunks(): Int = transferChunkDao.purgeFinishedChunks()

    override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> =
        transferChunkDao.allDoneChunks().map { TransferStore.ChunkRef(it.transferId, it.chunkIndex) }
}
