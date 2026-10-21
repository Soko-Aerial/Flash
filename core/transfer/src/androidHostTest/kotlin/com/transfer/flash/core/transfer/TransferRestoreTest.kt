package com.transfer.flash.core.transfer

import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import com.transfer.flash.core.transfer.store.TransferStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open-items C-09 / C-05: a 1:1 transfer row survives the process (identity + state in the store, restored at start),
 * a dismissed row does not come back, and a row nobody touched for 30 days is forgotten.
 */
class TransferRestoreTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private class MemoryStore : TransferStore {
        val rows = ConcurrentHashMap<String, TransferStore.StoredTransfer>()
        val dismissed = CopyOnWriteArraySet<String>()
        override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) = Unit
        override suspend fun setBytesDone(transferId: String, bytesDone: Long) = Unit
        override suspend fun setStatus(transferId: String, status: String) = Unit
        override suspend fun doneChunks(transferId: String): List<Int> = emptyList()
        override suspend fun markChunksDone(transferId: String, indexes: List<Int>) = Unit
        override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> = emptyList()
        override suspend fun saveIdentity(row: TransferStore.StoredTransfer) {
            rows[row.transferId] = row
        }
        override suspend fun restorableTransfers(sinceMs: Long): List<TransferStore.StoredTransfer> =
            rows.values.filter { it.transferId !in dismissed && it.updatedAtMs >= sinceMs }
        override suspend fun dismissStored(transferIds: List<String>) {
            dismissed += transferIds
        }
        override suspend fun deleteStoredBefore(beforeMs: Long): Int {
            val old = rows.values.filter { it.updatedAtMs < beforeMs }
            old.forEach { rows.remove(it.transferId) }
            return old.size
        }
    }

    private fun repo(store: TransferStore): RealFlashTransferRepository = RealFlashTransferRepository(
        streamChannelFactory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean = true
            }
        },
        fileSourceOpener = { Buffer().write(ByteArray(0)) },
        store = store,
        repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it },
        defaultStreams = 1,
    )

    private fun await(describe: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out: " + describe())
            Thread.sleep(5)
        }
    }

    private fun RealFlashTransferRepository.row(id: String): FlashTransfer? =
        activeTransfers.value.firstOrNull { it.id.value == id }

    private fun stored(id: String, state: String, updatedAtMs: Long, direction: String = "Receiving") = TransferStore.StoredTransfer(
        transferId = id, peerDeviceId = "peer-$id", peerName = "Ada", fileName = "$id.bin", direction = direction,
        totalBytes = 100L, bytesDone = 10L, state = state, errorMessage = null, sourceUri = null, wireFileId = "f-$id",
        localPath = null, updatedAtMs = updatedAtMs,
    )

    @Test
    fun `C-09 a cancelled row is written, and a new repository lists it again as restored`() = runBlocking {
        val store = MemoryStore()
        val first = repo(store)
        first.onIncomingOffered("tx-1", "fx-1", "doc.pdf", 1024L, "Pixel", "peer-1")
        assertTrue(first.declineIncoming(FlashTransferId("tx-1")) is com.transfer.flash.core.common.result.FlashResult.Success)
        await({ "the cancelled row is saved: ${store.rows}" }) { store.rows["tx-1"]?.state == "Cancelled" }
        assertEquals("doc.pdf", store.rows["tx-1"]?.fileName)
        assertEquals("peer-1", store.rows["tx-1"]?.peerDeviceId)

        val second = repo(store)
        await({ "the row is back" }) { second.row("tx-1") != null }
        val row = second.row("tx-1")!!
        assertEquals(FlashTransferState.Cancelled, row.state)
        assertEquals("doc.pdf", row.fileName)
        assertEquals("peer-1", row.peerDeviceId)
        assertTrue("it is marked as restored so hosts do not announce it", row.restored)
    }

    @Test
    fun `C-09 a transfer that was running when the process died comes back Failed with a sentence`() = runBlocking {
        val store = MemoryStore()
        val first = repo(store)
        first.onIncomingOffered("tx-2", "fx-2", "movie.mp4", 4096L, "Pixel", "peer-2")
        first.onIncomingStarted("tx-2", "fx-2", "movie.mp4", 4096L, "Pixel", "peer-2", null)
        await({ "the running row is saved" }) { store.rows["tx-2"]?.state == "Transferring" }

        val second = repo(store)
        await({ "the row is back" }) { second.row("tx-2") != null }
        val row = second.row("tx-2")!!
        assertEquals(FlashTransferState.Failed, row.state)
        assertEquals(TransferFailureText.INTERRUPTED_BY_RESTART, row.errorMessage)
        assertTrue(row.canRetry)
        await({ "the store agrees with the list" }) { store.rows["tx-2"]?.state == "Failed" }
    }

    @Test
    fun `C-10 a dismissed row is not restored`() = runBlocking {
        val store = MemoryStore()
        val first = repo(store)
        first.onIncomingOffered("tx-3", "fx-3", "a.bin", 10L, "Pixel", "peer-3")
        first.declineIncoming(FlashTransferId("tx-3"))
        await({ "saved" }) { store.rows["tx-3"]?.state == "Cancelled" }
        first.dismissTransfer(FlashTransferId("tx-3"))
        await({ "the dismissal is stored" }) { "tx-3" in store.dismissed }

        val second = repo(store)
        Thread.sleep(300)
        assertNull(second.row("tx-3"))
    }

    @Test
    fun `C-05 finished rows older than 30 days are forgotten and younger ones kept, a completed row is not restored`() = runBlocking {
        val store = MemoryStore()
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        store.rows["old"] = stored("old", "Failed", now - 31 * day)
        store.rows["young"] = stored("young", "Failed", now - 29 * day)
        store.rows["done"] = stored("done", "Completed", now - day)

        val repo = repo(store)
        await({ "the young row is back" }) { repo.row("young") != null }
        assertNull(repo.row("old"))
        assertFalse(store.rows.containsKey("old"))
        assertNull(repo.row("done"))
        assertNotNull(store.rows["done"])
    }
}
