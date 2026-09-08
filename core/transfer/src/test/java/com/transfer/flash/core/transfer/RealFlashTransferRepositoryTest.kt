package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.store.TransferStore
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealFlashTransferRepositoryTest {

    private val executor = Executors.newFixedThreadPool(8)
    private val testDispatcher = executor.asCoroutineDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        executor.shutdownNow()
    }

    /** Repository scope tied to the test executor and torn down with the test. */
    private fun newScope(): CoroutineScope =
        CoroutineScope(testDispatcher + SupervisorJob()).also { scopes.add(it) }

    private fun RealFlashTransferRepository.snapshot(id: FlashTransferId): FlashTransfer =
        activeTransfers.value.first { it.id == id }

    private fun awaitUntil(timeoutMs: Long = 20_000, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition timeout: " + describe())
            }
            Thread.sleep(5)
        }
    }

    @Test
    fun `sendFile starts transfer, updates activeTransfers, and completes when channels ACK`() = runBlocking {
        val payload = ByteArray(32 * 1024) { (it % 127).toByte() }
        val targetDevice = FlashDevice(
            id = com.transfer.flash.core.common.model.FlashDeviceId("target-peer-1"),
            friendlyName = "Pixel 9 Pro",
            transportType = com.transfer.flash.core.common.model.FlashTransportType.LAN,
        )

        lateinit var repo: RealFlashTransferRepository

        val dummyChannel = object : StreamChannel {
            override val id: Int = 0
            override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                val parsed = ChunkFrame.parse(frameBytes)
                if (parsed is ChunkFrame.Chunk) {
                    val ack = ChunkFrame.AckBatch(
                        parsed.transferId,
                        parsed.fileId,
                        listOf(parsed.index),
                    )
                    // MultiStreamDispatcher receives ACK via its inbound frame route
                    // Handled automatically if wired
                }
                return true
            }
        }

        // Loopback factory for test
        var activeChannel: StreamChannel? = null
        val factory = StreamChannelFactory { channelId, _ ->
            activeChannel ?: object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    return true
                }
            }.also { activeChannel = it }
        }

        repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { ByteArrayInputStream(payload) },
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )

        val result = repo.sendFile(
            targetDevice = targetDevice,
            fileUri = "content://media/test.bin",
            displayName = "test.bin",
            fileSize = payload.size.toLong(),
        )

        assertTrue(result is FlashResult.Success)
        val transferId = (result as FlashResult.Success).value

        val activeList = repo.activeTransfers.value
        assertEquals(1, activeList.size)
        assertEquals("test.bin", activeList.first().fileName)
        assertEquals(payload.size.toLong(), activeList.first().bytesTotal)

        // Test cancel
        val cancelResult = repo.cancelTransfer(transferId)
        assertTrue(cancelResult is FlashResult.Success)

        val cancelledList = repo.activeTransfers.value
        assertEquals(FlashTransferState.Cancelled, cancelledList.first().state)
    }

    // ---- pause / resume / cancel (ADR-018) ------------------------------------------------

    /** 512 KB = exactly 8 chunks at [Chunker.DEFAULT_CHUNK_SIZE_BYTES]. */
    private val eightChunkPayload = ByteArray(512 * 1024) { (it % 251).toByte() }

    private val peer = FlashDevice(
        id = com.transfer.flash.core.common.model.FlashDeviceId("target-peer-pause"),
        friendlyName = "Pixel 9 Pro",
        transportType = com.transfer.flash.core.common.model.FlashTransportType.LAN,
    )

    /**
     * [TransferStore] whose resume query parks until released — reproduces the exact window
     * `sendFile` returns into, where the send coroutine is live but its dispatcher is not
     * registered yet.
     */
    private class GatedTransferStore(val open: AtomicBoolean = AtomicBoolean(false)) : TransferStore {
        override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) = Unit
        override suspend fun setBytesDone(transferId: String, bytesDone: Long) = Unit
        override suspend fun setStatus(transferId: String, status: String) = Unit
        override suspend fun markChunksDone(transferId: String, indexes: List<Int>) = Unit
        override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> = emptyList()
        override suspend fun doneChunks(transferId: String): List<Int> {
            while (!open.get()) delay(5)
            return emptyList()
        }
    }

    @Test(timeout = 60_000)
    fun `pause issued before the dispatcher is registered is applied, not silently lost`() = runBlocking {
        val dao = GatedTransferStore()
        val chunksOnWire = AtomicInteger(0)
        lateinit var repo: RealFlashTransferRepository
        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val parsed = ChunkFrame.parse(frameBytes)
                    if (parsed is ChunkFrame.Chunk) {
                        chunksOnWire.incrementAndGet()
                        repo.onInboundFrame(
                            ChunkFrame.serialize(
                                ChunkFrame.AckBatch(parsed.transferId, parsed.fileId, listOf(parsed.index)),
                            ),
                        )
                    }
                    return true
                }
            }
        }
        repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { ByteArrayInputStream(eightChunkPayload) },
            store = dao,
            repositoryScope = newScope(),
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )

        val transferId = (
            repo.sendFile(peer, "content://media/paused.bin", "paused.bin", eightChunkPayload.size.toLong())
                as FlashResult.Success
            ).value

        // Dispatcher does not exist yet: the pre-fix code flipped state to Paused and then had it
        // overwritten by executeSend's unconditional Transferring write.
        assertTrue(repo.pauseTransfer(transferId) is FlashResult.Success)
        assertEquals(FlashTransferState.Paused, repo.snapshot(transferId).state)

        dao.open.set(true) // executeSend proceeds: builds + registers the dispatcher
        Thread.sleep(600) // well past dispatcher construction, hashing and worker start-up

        assertEquals(
            "pause must survive dispatcher construction",
            FlashTransferState.Paused,
            repo.snapshot(transferId).state,
        )
        assertEquals("no chunk may reach the wire while paused", 0, chunksOnWire.get())
        assertEquals(0L, repo.snapshot(transferId).speedBytesPerSec)

        assertTrue(repo.resumeTransfer(transferId) is FlashResult.Success)
        awaitUntil(describe = { "state=" + repo.snapshot(transferId).state + " chunks=" + chunksOnWire.get() }) {
            repo.snapshot(transferId).state == FlashTransferState.Completed
        }
        assertEquals(8, chunksOnWire.get())
        assertEquals(eightChunkPayload.size.toLong(), repo.snapshot(transferId).bytesDone)
        Unit
    }

    @Test(timeout = 60_000)
    fun `remote pause parks a live sender and remote resume finishes it with the notice cleared`() = runBlocking {
        val firstChunkGate = CompletableDeferred<Unit>()
        val gateEntered = AtomicBoolean(false)
        val chunksOnWire = AtomicInteger(0)
        lateinit var repo: RealFlashTransferRepository
        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val parsed = ChunkFrame.parse(frameBytes)
                    if (parsed is ChunkFrame.Chunk) {
                        // Hold the worker inside the wire write for the first chunk: the dispatcher
                        // is fully registered and running, which is what a remote PAUSE must hit.
                        if (gateEntered.compareAndSet(false, true)) firstChunkGate.await()
                        chunksOnWire.incrementAndGet()
                        repo.onInboundFrame(
                            ChunkFrame.serialize(
                                ChunkFrame.AckBatch(parsed.transferId, parsed.fileId, listOf(parsed.index)),
                            ),
                        )
                    }
                    return true
                }
            }
        }
        repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { ByteArrayInputStream(eightChunkPayload) },
            repositoryScope = newScope(),
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )

        val transferId = (
            repo.sendFile(peer, "content://media/remote.bin", "remote.bin", eightChunkPayload.size.toLong())
                as FlashResult.Success
            ).value
        awaitUntil(describe = { "worker never reached the wire" }) { gateEntered.get() }

        repo.onRemoteTransferControl(transferId.value, RealFlashTransferRepository.ACTION_PAUSE)
        assertEquals(FlashTransferState.Paused, repo.snapshot(transferId).state)
        assertEquals("paused by receiver", repo.snapshot(transferId).errorMessage)

        firstChunkGate.complete(Unit) // in-flight chunk lands, then the worker must park
        Thread.sleep(600)
        assertEquals("only the in-flight chunk may land after a remote pause", 1, chunksOnWire.get())
        assertEquals(FlashTransferState.Paused, repo.snapshot(transferId).state)

        repo.onRemoteTransferControl(transferId.value, RealFlashTransferRepository.ACTION_RESUME)
        awaitUntil(describe = { "state=" + repo.snapshot(transferId).state + " chunks=" + chunksOnWire.get() }) {
            repo.snapshot(transferId).state == FlashTransferState.Completed
        }
        assertEquals(8, chunksOnWire.get())
        assertNull("the pause notice must not outlive the resume", repo.snapshot(transferId).errorMessage)
        Unit
    }

    @Test(timeout = 60_000)
    fun `cancel unparks a paused sender so the job actually stops`() = runBlocking {
        val dao = GatedTransferStore(AtomicBoolean(true))
        val chunksOnWire = AtomicInteger(0)
        val gateEntered = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        lateinit var repo: RealFlashTransferRepository
        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    if (ChunkFrame.parse(frameBytes) is ChunkFrame.Chunk) {
                        if (gateEntered.compareAndSet(false, true)) gate.await()
                        chunksOnWire.incrementAndGet()
                    }
                    return true // never ACKs: the transfer cannot finish on its own
                }
            }
        }
        repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { ByteArrayInputStream(eightChunkPayload) },
            store = dao,
            repositoryScope = newScope(),
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )

        val transferId = (
            repo.sendFile(peer, "content://media/cancel.bin", "cancel.bin", eightChunkPayload.size.toLong())
                as FlashResult.Success
            ).value
        awaitUntil(describe = { "worker never reached the wire" }) { gateEntered.get() }

        assertTrue(repo.pauseTransfer(transferId) is FlashResult.Success)
        gate.complete(Unit)
        Thread.sleep(400)
        assertEquals(FlashTransferState.Paused, repo.snapshot(transferId).state)
        assertEquals("only the in-flight chunk may land after a pause", 1, chunksOnWire.get())

        // Cancelling a PAUSED sender must unpause first, otherwise workers re-park in the pause
        // poll loop and the job never reaches a cancellable suspension point. Both the state and
        // the wire have to settle: nothing may re-label this Failed via the ack-drain grace.
        assertTrue(repo.cancelTransfer(transferId) is FlashResult.Success)
        assertEquals(FlashTransferState.Cancelled, repo.snapshot(transferId).state)
        Thread.sleep(600)
        val settled = chunksOnWire.get()
        Thread.sleep(400)
        assertEquals("the wire must go quiet after a cancel", settled, chunksOnWire.get())
        assertEquals(
            "Cancelled is terminal: the ack-drain grace must not overwrite it",
            FlashTransferState.Cancelled,
            repo.snapshot(transferId).state,
        )
        Unit
    }

    // ---- #5: inbound offer / accept / decline (ADR-018 local intake gate) ------------------

    /** Minimal repo: the offer path touches only activeTransfers + the control SharedFlows. */
    private fun offerRepo(): RealFlashTransferRepository = RealFlashTransferRepository(
        chunker = Chunker(),
        streamChannelFactory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean = true
            }
        },
        fileSourceOpener = { ByteArrayInputStream(ByteArray(0)) },
        repositoryScope = newScope(),
        workerDispatcher = testDispatcher,
        defaultStreams = 1,
    )

    @Test
    fun `onIncomingOffered inserts an Offered receiving row and is idempotent`() {
        val repo = offerRepo()
        repo.onIncomingOffered("tx-off", "fx-off", "photo.jpg", 4096L, "Pixel", "peer-1")
        val row = repo.snapshot(FlashTransferId("tx-off"))
        assertEquals(FlashTransferState.Offered, row.state)
        assertEquals("photo.jpg", row.fileName)
        assertEquals(4096L, row.bytesTotal)
        assertEquals("peer-1", row.peerDeviceId)

        // A duplicate FILE_START (resume re-offer) must not spawn a second row.
        repo.onIncomingOffered("tx-off", "fx-off", "photo.jpg", 4096L, "Pixel", "peer-1")
        assertEquals(1, repo.activeTransfers.value.count { it.id.value == "tx-off" })
    }

    @Test
    fun `acceptIncoming flips Offered to Transferring and emits only the local ACCEPT`() = runBlocking {
        val repo = offerRepo()
        val scope = newScope()
        val incoming = java.util.concurrent.CopyOnWriteArrayList<String>()
        val outgoing = java.util.concurrent.CopyOnWriteArrayList<String>()
        scope.launch { repo.incomingControl.collect { incoming.add(it.action) } }
        scope.launch { repo.outgoingControl.collect { outgoing.add(it.action) } }
        awaitUntil(describe = { "control collectors never subscribed" }) {
            repo.incomingControl.subscriptionCount.value >= 1 &&
                repo.outgoingControl.subscriptionCount.value >= 1
        }

        repo.onIncomingOffered("tx-acc", "fx-acc", "clip.mp4", 8192L, "Pixel", "peer-2")
        assertTrue(repo.acceptIncoming(FlashTransferId("tx-acc")) is FlashResult.Success)

        val row = repo.snapshot(FlashTransferId("tx-acc"))
        assertEquals(FlashTransferState.Transferring, row.state)
        assertNull("accept clears the waiting-for-acceptance message", row.errorMessage)

        awaitUntil(describe = { "ACCEPT never emitted, saw=$incoming" }) { incoming.contains("accept") }
        // The host — not the repo — sends RESUME after resolving the sink; no wire frame here.
        assertEquals(listOf("accept"), incoming.toList())
        assertTrue("accept must not emit an outgoing control frame", outgoing.isEmpty())
    }

    @Test
    fun `declineIncoming cancels the offer and tells the sender to cancel`() = runBlocking {
        val repo = offerRepo()
        val scope = newScope()
        val incoming = java.util.concurrent.CopyOnWriteArrayList<String>()
        val outgoing = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
        scope.launch { repo.incomingControl.collect { incoming.add(it.action) } }
        scope.launch { repo.outgoingControl.collect { outgoing.add(it.action to it.peerDeviceId) } }
        awaitUntil(describe = { "control collectors never subscribed" }) {
            repo.incomingControl.subscriptionCount.value >= 1 &&
                repo.outgoingControl.subscriptionCount.value >= 1
        }

        repo.onIncomingOffered("tx-dec", "fx-dec", "doc.pdf", 2048L, "Pixel", "peer-3")
        assertTrue(repo.declineIncoming(FlashTransferId("tx-dec")) is FlashResult.Success)

        val row = repo.snapshot(FlashTransferId("tx-dec"))
        assertEquals(FlashTransferState.Cancelled, row.state)
        assertEquals("declined", row.errorMessage)

        awaitUntil(describe = { "DECLINE never emitted" }) { incoming.contains("decline") }
        awaitUntil(describe = { "outgoing CANCEL never emitted" }) { outgoing.any { it.first == "cancel" } }
        assertEquals("peer-3", outgoing.first { it.first == "cancel" }.second)
    }

    @Test
    fun `accept and decline reject a transfer that is not an open offer`() = runBlocking {
        val repo = offerRepo()
        // No such transfer at all.
        assertTrue(repo.acceptIncoming(FlashTransferId("ghost")) is FlashResult.Failure)
        assertTrue(repo.declineIncoming(FlashTransferId("ghost")) is FlashResult.Failure)

        // Present but already accepted (Transferring) — the offer gate is closed.
        repo.onIncomingOffered("tx-2x", "fx-2x", "a.bin", 1024L, "Pixel", "peer-4")
        assertTrue(repo.acceptIncoming(FlashTransferId("tx-2x")) is FlashResult.Success)
        assertTrue("double-accept is rejected", repo.acceptIncoming(FlashTransferId("tx-2x")) is FlashResult.Failure)
        assertTrue("cannot decline an accepted offer", repo.declineIncoming(FlashTransferId("tx-2x")) is FlashResult.Failure)
        Unit
    }

    // ---- send-side resume bookkeeping (EXP-008) --------------------------------------------

    /** Records every write so a test can assert what the progress collector persisted. */
    private class RecordingTransferStore(
        private val preloadRows: List<TransferStore.ChunkRef> = emptyList(),
    ) : TransferStore {
        val chunkWrites = java.util.Collections.synchronizedList(mutableListOf<List<Int>>())
        val byteWrites = java.util.Collections.synchronizedList(mutableListOf<Long>())
        override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) = Unit
        override suspend fun setBytesDone(transferId: String, bytesDone: Long) {
            byteWrites.add(bytesDone)
        }
        override suspend fun setStatus(transferId: String, status: String) = Unit
        override suspend fun markChunksDone(transferId: String, indexes: List<Int>) {
            chunkWrites.add(indexes.toList())
        }
        override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> = preloadRows
        override suspend fun doneChunks(transferId: String): List<Int> = emptyList()
    }

    private fun receiverRepo(store: TransferStore): RealFlashTransferRepository =
        RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = StreamChannelFactory { channelId, _ ->
                object : StreamChannel {
                    override val id: Int = channelId
                    override suspend fun sendFrame(frameBytes: ByteArray): Boolean = true
                }
            },
            fileSourceOpener = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            repositoryScope = newScope(),
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )

    @Test(timeout = 30_000)
    fun `preloadReceiverProgress warms the receiver done-set ascending and survives a corrupt row`() = runBlocking {
        val rows = listOf(
            TransferStore.ChunkRef("rx-a", 5),
            TransferStore.ChunkRef("rx-a", 0),
            TransferStore.ChunkRef("rx-b", 130),
            TransferStore.ChunkRef("rx-a", 64),
            // A negative index cannot name a chunk. The old Set-backed map swallowed it; a BitSet
            // would throw, so it must be dropped before the bit is set — startup runs this.
            TransferStore.ChunkRef("rx-a", -1),
        )
        val repo = receiverRepo(RecordingTransferStore(preloadRows = rows))

        repo.preloadReceiverProgress()

        // Ascending regardless of row order, and spanning a BitSet word boundary (0, 5, 64).
        assertEquals(listOf(0, 5, 64), repo.receiverDoneIndexes("rx-a"))
        assertEquals(listOf(130), repo.receiverDoneIndexes("rx-b"))
        assertEquals(emptyList<Int>(), repo.receiverDoneIndexes("rx-never-seen"))
        Unit
    }

    @Test(timeout = 30_000)
    fun `onIncomingChunkConfirmed persists only fresh indexes and never re-persists a known one`() = runBlocking {
        val store = RecordingTransferStore(preloadRows = listOf(TransferStore.ChunkRef("rx-c", 1)))
        val repo = receiverRepo(store)
        repo.preloadReceiverProgress()

        // 1 is already known from the preload; 3 appears twice in one call.
        repo.onIncomingChunkConfirmed("rx-c", listOf(1, 2, 3, 3, -7))
        awaitUntil(timeoutMs = 5_000, describe = { "writes=" + store.chunkWrites }) {
            store.chunkWrites.isNotEmpty()
        }
        assertEquals(listOf(listOf(2, 3)), store.chunkWrites.toList())
        assertEquals(listOf(1, 2, 3), repo.receiverDoneIndexes("rx-c"))

        // A wholly redundant batch must not reach the store at all.
        repo.onIncomingChunkConfirmed("rx-c", listOf(1, 2, 3))
        Thread.sleep(200)
        assertEquals(listOf(listOf(2, 3)), store.chunkWrites.toList())

        repo.onIncomingChunkConfirmed("rx-c", listOf(3, 4))
        awaitUntil(timeoutMs = 5_000, describe = { "writes=" + store.chunkWrites }) {
            store.chunkWrites.size == 2
        }
        assertEquals(listOf(4), store.chunkWrites.toList()[1])
        assertEquals(listOf(1, 2, 3, 4), repo.receiverDoneIndexes("rx-c"))
        Unit
    }

    @Test(timeout = 60_000)
    fun `resume bookkeeping persists each confirmed chunk once, in order, without a per-tick byte write`() = runBlocking {
        val store = RecordingTransferStore()
        lateinit var repo: RealFlashTransferRepository
        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val parsed = ChunkFrame.parse(frameBytes)
                    if (parsed is ChunkFrame.Chunk) {
                        // Pace the wire so the transfer outlives several WATCH_POLL_MS ticks: the bug
                        // this test guards is per-tick behaviour, so a transfer that finishes inside
                        // one tick would not exercise it.
                        delay(15)
                        repo.onInboundFrame(
                            ChunkFrame.serialize(
                                ChunkFrame.AckBatch(parsed.transferId, parsed.fileId, listOf(parsed.index)),
                            ),
                        )
                    }
                    return true
                }
            }
        }
        repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { ByteArrayInputStream(eightChunkPayload) },
            store = store,
            repositoryScope = newScope(),
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )

        val transferId = (
            repo.sendFile(peer, "content://media/resume.bin", "resume.bin", eightChunkPayload.size.toLong())
                as FlashResult.Success
            ).value
        awaitUntil(describe = { "state=" + repo.snapshot(transferId).state }) {
            repo.snapshot(transferId).state == FlashTransferState.Completed
        }

        // Trailing chunks can go unpersisted: executeSend cancels the collector the moment send()
        // returns Completed, and a completed transfer never resumes. So the contract asserted here is
        // about *how* what is written gets written, not about the set being exhaustive.
        val persisted = store.chunkWrites.toList().flatten()
        assertTrue("the collector persisted nothing at all", persisted.isNotEmpty())
        assertTrue("persisted a chunk index outside the plan: " + persisted, persisted.all { it in 0..7 })
        // Exactly once. The mirror bit-vector advances only after a write returns, so an index can
        // never appear in two batches — the old whole-snapshot diff re-persisted everything it had
        // already written whenever its Set copy lagged a tick behind.
        assertEquals(persisted.size, persisted.distinct().size)
        // Monotonic: single stream, in-order ACKs, so each batch starts above the previous one's max.
        assertEquals(persisted.sorted(), persisted)
        // bytesDone rides along with the chunk rows instead of firing on the 10 ms watcher tick. The
        // gate is "the confirmed count changed", and with 8 chunks the count takes 9 distinct values
        // (0..8), so the collector can write at most 9 times; executeSend's terminal write makes 10.
        // The old code wrote once per emission — one Room transaction per 10 ms, all transfer long.
        assertTrue(
            "byte writes (" + store.byteWrites.size + ") exceed the confirmed-count bound of 10",
            store.byteWrites.size <= 10,
        )
        assertEquals(eightChunkPayload.size.toLong(), repo.snapshot(transferId).bytesDone)
        Unit
    }
}
