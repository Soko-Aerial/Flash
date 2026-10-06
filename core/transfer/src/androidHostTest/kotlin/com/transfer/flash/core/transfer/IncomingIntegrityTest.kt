package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.chunked.WholeFileCheck
import com.transfer.flash.core.transfer.chunked.WholeFileVerifier
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import com.transfer.flash.core.transfer.store.TransferStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ADR-068 / ERROR-098: the assembled file is checked against the digest the sender offered, on every host, and a
 * mismatch fails the transfer on both ends instead of showing a "Verified" completion.
 */
class IncomingIntegrityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val executor = Executors.newFixedThreadPool(4)
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        executor.shutdownNow()
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(dispatcher + SupervisorJob()).also { scopes += it }

    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val payloadHex = Sha256.digestHex(payload)

    private fun fileWith(bytes: ByteArray, name: String = "received.bin"): File =
        tempFolder.newFile(name).also { it.writeBytes(bytes) }

    /** Records what the repository told its store. */
    private class RecordingStore : TransferStore {
        val cleared = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) = Unit
        override suspend fun setBytesDone(transferId: String, bytesDone: Long) = Unit
        override suspend fun setStatus(transferId: String, status: String) {
            statuses.add(transferId to status)
        }
        override suspend fun doneChunks(transferId: String): List<Int> = emptyList()
        override suspend fun markChunksDone(transferId: String, indexes: List<Int>) = Unit
        override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> = emptyList()
        override suspend fun clearDoneChunks(transferId: String) {
            cleared.add(transferId)
        }
    }

    private fun repo(store: TransferStore? = null) = RealFlashTransferRepository(
        chunker = Chunker(),
        streamChannelFactory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean = true
            }
        },
        fileSourceOpener = { Buffer().write(ByteArray(0)) },
        store = store,
        repositoryScope = newScope(),
        workerDispatcher = dispatcher,
        defaultStreams = 1,
    )

    private fun RealFlashTransferRepository.row(id: String): FlashTransfer =
        activeTransfers.value.first { it.id.value == id }

    private fun RealFlashTransferRepository.startInbound(id: String, path: String) {
        onIncomingOffered(id, "f-$id", "received.bin", payload.size.toLong(), "Pixel", "peer-1")
        onIncomingStarted(id, "f-$id", "received.bin", payload.size.toLong(), "Pixel", "peer-1", path)
    }

    // ---------------------------------------------------------------- the check itself

    @Test
    fun `a file equal to the offered digest matches, in either hex case`() {
        val file = fileWith(payload)
        assertEquals(WholeFileCheck.MATCH, WholeFileVerifier.check(file.path, payloadHex))
        assertEquals(WholeFileCheck.MATCH, WholeFileVerifier.check(file.path, payloadHex.uppercase()))
    }

    @Test
    fun `one flipped byte is a mismatch`() {
        val damaged = payload.copyOf().also { it[123_456] = (it[123_456].toInt() xor 0x01).toByte() }
        assertEquals(WholeFileCheck.MISMATCH, WholeFileVerifier.check(fileWith(damaged).path, payloadHex))
    }

    @Test
    fun `a truncated or extended file is a mismatch`() {
        assertEquals(
            WholeFileCheck.MISMATCH,
            WholeFileVerifier.check(fileWith(payload.copyOf(payload.size - 1), "short.bin").path, payloadHex),
        )
        assertEquals(
            WholeFileCheck.MISMATCH,
            WholeFileVerifier.check(fileWith(payload + byteArrayOf(0), "long.bin").path, payloadHex),
        )
    }

    @Test
    fun `nothing to compare against, or nothing to read, is unverifiable and never a mismatch`() {
        val file = fileWith(payload)
        assertEquals(WholeFileCheck.UNVERIFIABLE, WholeFileVerifier.check(null, payloadHex))
        assertEquals(WholeFileCheck.UNVERIFIABLE, WholeFileVerifier.check(" ", payloadHex))
        assertEquals(WholeFileCheck.UNVERIFIABLE, WholeFileVerifier.check(file.path, null))
        assertEquals(WholeFileCheck.UNVERIFIABLE, WholeFileVerifier.check(file.path, "not-hex"))
        assertEquals(
            WholeFileCheck.UNVERIFIABLE,
            WholeFileVerifier.check(File(tempFolder.root, "missing.bin").path, payloadHex),
        )
    }

    // ---------------------------------------------------------------- the receiving repository

    @Test
    fun `a matching file completes verified and is kept`() {
        val file = fileWith(payload)
        val repository = repo()
        repository.startInbound("tx-ok", file.path)

        val check = repository.onIncomingFileAssembled("tx-ok", file.path, payloadHex)

        assertEquals(WholeFileCheck.MATCH, check)
        val row = repository.row("tx-ok")
        assertEquals(FlashTransferState.Completed, row.state)
        assertNull(row.errorMessage)
        assertEquals(file.path, row.localPath)
        assertTrue(file.exists())
    }

    @Test
    fun `a damaged file fails the transfer, is deleted, and its confirmed chunks are forgotten`() {
        val damaged = payload.copyOf().also { it[10] = (it[10].toInt() xor 0x7F).toByte() }
        val file = fileWith(damaged)
        val store = RecordingStore()
        val repository = repo(store)
        repository.startInbound("tx-bad", file.path)
        repository.onIncomingChunkConfirmed("tx-bad", listOf(0, 1, 2, 3, 4))
        assertEquals(5, repository.receiverDoneIndexes("tx-bad").size)

        val check = repository.onIncomingFileAssembled("tx-bad", file.path, payloadHex)

        assertEquals(WholeFileCheck.MISMATCH, check)
        val row = repository.row("tx-bad")
        // Never Completed: the list labels a Completed transfer "Verified".
        assertEquals(FlashTransferState.Failed, row.state)
        assertEquals(RealFlashTransferRepository.INTEGRITY_FAILED_MESSAGE, row.errorMessage)
        assertEquals(0L, row.bytesDone)
        assertNull(row.localPath)
        assertFalse("a damaged file must not stay on disk", file.exists())
        assertTrue("in-memory done-set must be forgotten", repository.receiverDoneIndexes("tx-bad").isEmpty())
        awaitStore { store.cleared.contains("tx-bad") }
        awaitStore { store.statuses.contains("tx-bad" to FlashTransferState.Failed.name) }
    }

    @Test
    fun `a pipeline that already reports unverified chunks is treated as damaged`() {
        val file = fileWith(payload)
        val repository = repo()
        repository.startInbound("tx-flag", file.path)

        val check = repository.onIncomingFileAssembled("tx-flag", file.path, payloadHex, chunksVerified = false)

        assertEquals(WholeFileCheck.MISMATCH, check)
        assertEquals(FlashTransferState.Failed, repository.row("tx-flag").state)
        assertFalse(file.exists())
    }

    @Test
    fun `no digest on record keeps the old behaviour (completed, flagged, file kept)`() {
        val file = fileWith(payload)
        val repository = repo()
        repository.startInbound("tx-nohash", file.path)

        val check = repository.onIncomingFileAssembled("tx-nohash", file.path, expectedSha256Hex = null)

        assertEquals(WholeFileCheck.UNVERIFIABLE, check)
        val row = repository.row("tx-nohash")
        assertEquals(FlashTransferState.Completed, row.state)
        assertEquals("completed without whole-file verification", row.errorMessage)
        assertTrue(file.exists())
    }

    // ---------------------------------------------------------------- the sending repository

    @Test(timeout = 60_000)
    fun `a receiver that reports the file damaged fails the sender and clears its confirmed chunks`() = runBlocking {
        val bytes = ByteArray(4 * Chunker.DEFAULT_CHUNK_SIZE_BYTES) { (it % 251).toByte() }
        val store = RecordingStore()
        lateinit var sender: RealFlashTransferRepository
        val lastChunk = 3
        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val parsed = ChunkFrame.parse(frameBytes)
                    if (parsed is ChunkFrame.Chunk) {
                        sender.onInboundFrame(
                            ChunkFrame.serialize(ChunkFrame.AckBatch(parsed.transferId, parsed.fileId, listOf(parsed.index))),
                        )
                        if (parsed.index == lastChunk) {
                            sender.onInboundFrame(
                                ChunkFrame.serialize(ChunkFrame.Complete(parsed.transferId, parsed.fileId, verified = false)),
                            )
                        }
                    }
                    return true
                }
            }
        }
        sender = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { Buffer().write(bytes) },
            store = store,
            repositoryScope = newScope(),
            workerDispatcher = dispatcher,
            defaultStreams = 1,
        )
        val peer = FlashDevice(FlashDeviceId("peer-damaged"), "Pixel", FlashTransportType.LAN)

        val id = (sender.sendFile(peer, "content://x/damaged.bin", "damaged.bin", bytes.size.toLong()) as FlashResult.Success).value

        awaitUntil({ "state=" + sender.row(id.value).state }) { sender.row(id.value).state == FlashTransferState.Failed }
        assertEquals(RealFlashTransferRepository.PEER_REPORTED_DAMAGE_MESSAGE, sender.row(id.value).errorMessage)
        awaitStore { store.cleared.contains(id.value) }
        Unit
    }

    private fun awaitStore(condition: () -> Boolean) = awaitUntil({ "store call not seen" }, condition)

    private fun awaitUntil(describe: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: ${describe()}" }
            Thread.sleep(10)
        }
    }
}
