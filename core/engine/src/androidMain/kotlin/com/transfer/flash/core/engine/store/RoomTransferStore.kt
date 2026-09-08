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
        transferDao.insert(
            TransferEntity(
                transferId = transferId,
                totalBytes = totalBytes,
                bytesDone = 0L,
                status = status,
            ),
        )
    }

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

    override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> =
        transferChunkDao.allDoneChunks().map { TransferStore.ChunkRef(it.transferId, it.chunkIndex) }
}
