package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import com.transfer.flash.core.transfer.store.TransferStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.IOException
import okio.Source
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-069: a receive that cannot fit is refused by name (FA-2), failures are worded for people (FA-3), and a
 * zero-byte or unreadable file produces a visible Failed row instead of a send that never starts (FA-6).
 */
class TransferRefusalAndWordingTest {

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

    private val peer = FlashDevice(FlashDeviceId("peer-1"), "Pixel", FlashTransportType.LAN)

    private val channelOpens = AtomicInteger(0)

    private fun repo(
        free: () -> Long? = { null },
        opener: FileSourceOpener = FileSourceOpener { Buffer() },
        store: TransferStore? = null,
    ) = RealFlashTransferRepository(
        chunker = Chunker(),
        streamChannelFactory = StreamChannelFactory { channelId, _ ->
            channelOpens.incrementAndGet()
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean = true
            }
        },
        fileSourceOpener = opener,
        store = store,
        repositoryScope = newScope(),
        workerDispatcher = dispatcher,
        defaultStreams = 1,
        freeSpaceBytes = free,
    )

    private fun RealFlashTransferRepository.row(id: String): FlashTransfer =
        activeTransfers.value.first { it.id.value == id }

    private fun RealFlashTransferRepository.offer(id: String, bytes: Long) =
        onIncomingOffered(id, "f-$id", "movie.mkv", bytes, "Pixel", "peer-1")

    private class Seen(repository: RealFlashTransferRepository, scope: CoroutineScope) {
        val incoming = CopyOnWriteArrayList<RealFlashTransferRepository.IncomingControl>()
        val outgoing = CopyOnWriteArrayList<RealFlashTransferRepository.OutgoingControl>()

        init {
            // SharedFlow drops values while nobody collects, so subscribe before the call under test.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { repository.incomingControl.collect { incoming += it } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { repository.outgoingControl.collect { outgoing += it } }
        }
    }

    private fun awaitUntil(describe: () -> String = { "condition" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: ${describe()}" }
            Thread.sleep(10)
        }
    }

    // ---------------------------------------------------------------- FA-3: the wording

    @Test
    fun `known reasons become a sentence and the raw text never reaches the user`() {
        val cases = mapOf(
            "source length mismatch: read=4 expected=9 extraByte=-1" to TransferFailureText.FILE_CHANGED,
            "source read failed: open failed: ENOENT (No such file or directory)" to TransferFailureText.UNREADABLE_FILE,
            "all channels failed" to TransferFailureText.CONNECTION_LOST,
            "data channel closed" to TransferFailureText.CONNECTION_LOST,
            "peer disconnected" to TransferFailureText.CONNECTION_LOST,
            "ack drain timeout: 3 chunks unconfirmed" to TransferFailureText.PEER_NOT_RESPONDING,
            "cancelled by peer" to TransferFailureText.CANCELLED_BY_PEER,
        )
        for ((raw, expected) in cases) {
            val text = TransferFailureText.friendly(raw)
            assertEquals(raw, expected, text)
            assertFalse("raw text leaked: $text", text.contains(raw))
        }
    }

    @Test
    fun `an unknown or missing reason becomes the generic sentence, not the exception text`() {
        val raw = "java.lang.IllegalStateException: boom at Foo.kt:12"
        assertEquals(TransferFailureText.GENERIC, TransferFailureText.friendly(raw))
        assertEquals(TransferFailureText.GENERIC, TransferFailureText.friendly(null))
        assertEquals(TransferFailureText.GENERIC, TransferFailureText.friendly("   "))
    }

    @Test
    fun `sizes are formatted in decimal units and a refusal names both amounts`() {
        assertEquals("0 B", TransferFailureText.formatSize(-5))
        assertEquals("999 B", TransferFailureText.formatSize(999))
        assertEquals("812 KB", TransferFailureText.formatSize(812_345))
        assertEquals("1.4 MB", TransferFailureText.formatSize(1_450_000))
        assertEquals("2.5 GB", TransferFailureText.formatSize(2_500_000_000))
        val text = TransferFailureText.notEnoughSpace(needed = 2_500_000_000, free = 812_345)
        assertTrue(text, text.contains("2.5 GB") && text.contains("812 KB"))
    }

    @Test
    fun `a failure reported for an inbound transfer is worded for people on the row`() {
        val repository = repo()
        repository.offer("tx-fail", 1_000)
        repository.onIncomingFailed("tx-fail", "peer disconnected")

        val row = repository.row("tx-fail")
        assertEquals(FlashTransferState.Failed, row.state)
        assertEquals(TransferFailureText.CONNECTION_LOST, row.errorMessage)
    }

    @Test(timeout = 60_000)
    fun `a source that cannot be read fails the send with the unreadable-file sentence`() = runBlocking {
        val failing = FileSourceOpener {
            object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("open failed: EACCES")
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }
        }
        val repository = repo(opener = failing)

        val id = (repository.sendFile(peer, "content://x/locked.bin", "locked.bin", 2_000_000L) as FlashResult.Success).value

        awaitUntil({ "state=" + repository.row(id.value).state }) {
            repository.row(id.value).state == FlashTransferState.Failed
        }
        assertEquals(TransferFailureText.UNREADABLE_FILE, repository.row(id.value).errorMessage)
        Unit
    }

    // ---------------------------------------------------------------- FA-2: the free-space gate

    @Test(timeout = 60_000)
    fun `an offer that does not fit is refused by name, declined locally and cancelled to the sender`() {
        val store = object : TransferStore {
            val statuses = CopyOnWriteArrayList<Pair<String, String>>()
            override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) = Unit
            override suspend fun setBytesDone(transferId: String, bytesDone: Long) = Unit
            override suspend fun setStatus(transferId: String, status: String) {
                statuses += transferId to status
            }
            override suspend fun doneChunks(transferId: String): List<Int> = emptyList()
            override suspend fun markChunksDone(transferId: String, indexes: List<Int>) = Unit
            override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> = emptyList()
        }
        val repository = repo(free = { 1_000L }, store = store)
        val seen = Seen(repository, newScope())
        repository.offer("tx-big", 5_000L)

        assertFalse(repository.admitIncoming("tx-big"))

        val row = repository.row("tx-big")
        assertEquals(FlashTransferState.Failed, row.state)
        assertEquals(TransferFailureText.notEnoughSpace(needed = 5_000L, free = 1_000L), row.errorMessage)
        awaitUntil({ "controls in=${seen.incoming} out=${seen.outgoing}" }) {
            seen.incoming.any { it.transferId == "tx-big" && it.action == RealFlashTransferRepository.ACTION_DECLINE } &&
                seen.outgoing.any {
                    it.transferId == "tx-big" && it.peerDeviceId == "peer-1" &&
                        it.action == RealFlashTransferRepository.ACTION_CANCEL
                }
        }
        awaitUntil({ "store statuses=${store.statuses}" }) { store.statuses.contains("tx-big" to "Failed") }
    }

    @Test
    fun `an offer that exactly fits, or has no known free space, is admitted untouched`() {
        val fits = repo(free = { 5_000L })
        val seenFits = Seen(fits, newScope())
        fits.offer("tx-exact", 5_000L)
        assertTrue(fits.admitIncoming("tx-exact"))
        assertEquals(FlashTransferState.Offered, fits.row("tx-exact").state)

        val unknown = repo(free = { null })
        unknown.offer("tx-unknown", 9_000_000_000_000L)
        assertTrue(unknown.admitIncoming("tx-unknown"))

        val throwing = repo(free = { error("statfs failed") })
        throwing.offer("tx-throws", 1L)
        assertTrue("a failing probe must never block a transfer", throwing.admitIncoming("tx-throws"))

        assertTrue(seenFits.incoming.isEmpty() && seenFits.outgoing.isEmpty())
        assertTrue("an unknown row is admitted", fits.admitIncoming("no-such-transfer"))
    }

    @Test
    fun `a resumed transfer only needs room for what is left`() {
        val repository = repo(free = { 120_000L })
        repository.offer("tx-resume", 300_000L)
        repository.onIncomingStarted("tx-resume", "f-tx-resume", "movie.mkv", 300_000L, "Pixel", "peer-1", "/tmp/x")
        repository.onIncomingProgress("tx-resume", 200_000L)

        // 300 000 total, 200 000 already written: 100 000 needed, 120 000 free.
        assertTrue(repository.admitIncoming("tx-resume"))
        assertNotEquals(FlashTransferState.Failed, repository.row("tx-resume").state)
    }

    // ---------------------------------------------------------------- FA-6: empty and unknown-size files

    @Test(timeout = 60_000)
    fun `an empty file or a negative size gives a visible Failed row, not a send`() = runBlocking {
        for (size in listOf(0L, -1L)) {
            val repository = repo(opener = FileSourceOpener { Buffer() })

            val result = repository.sendFile(peer, "content://x/empty-$size.txt", "empty.txt", size)

            // Callers ignore Failure, so the refusal must be a row, and the call still succeeds.
            val id = (result as FlashResult.Success).value
            val row = repository.row(id.value)
            assertEquals(FlashTransferState.Failed, row.state)
            assertEquals(TransferFailureText.EMPTY_FILE, row.errorMessage)
            assertEquals(0L, row.bytesTotal)
        }
        assertEquals("nothing may be offered to the peer", 0, channelOpens.get())
        Unit
    }

    @Test(timeout = 60_000)
    fun `an unknown size is measured from the source and the send goes ahead with it`() = runBlocking {
        val bytes = ByteArray(777_777) { (it % 251).toByte() }
        val repository = repo(opener = FileSourceOpener { Buffer().write(bytes) })

        val id = (repository.sendFile(peer, "content://x/unknown.bin", "unknown.bin", 0L) as FlashResult.Success).value

        val row = repository.row(id.value)
        assertEquals(bytes.size.toLong(), row.bytesTotal)
        assertNotEquals(FlashTransferState.Failed, row.state)
        Unit
    }

    @Test(timeout = 60_000)
    fun `an unknown size from a source that cannot be opened fails with the unreadable-file sentence`() = runBlocking {
        val repository = repo(opener = FileSourceOpener { throw IOException("no such file") })

        val id = (repository.sendFile(peer, "content://x/gone.bin", "gone.bin", 0L) as FlashResult.Success).value

        val row = repository.row(id.value)
        assertEquals(FlashTransferState.Failed, row.state)
        assertEquals(TransferFailureText.UNREADABLE_FILE, row.errorMessage)
        Unit
    }
}
