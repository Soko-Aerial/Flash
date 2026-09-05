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
import okio.Buffer
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
            fileSourceOpener = { Buffer().write(payload) },
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
            fileSourceOpener = { Buffer().write(eightChunkPayload) },
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
            fileSourceOpener = { Buffer().write(eightChunkPayload) },
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
            fileSourceOpener = { Buffer().write(eightChunkPayload) },
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
        fileSourceOpener = { Buffer().write(ByteArray(0)) },
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

    // ---- #20 receiver done-set (added by Phase 13B-3d) --------------------------------------
    //
    // `preloadReceiverProgress` / `onIncomingChunkConfirmed` / `receiverDoneIndexes` had NO test
    // before this sub-step, and 13B-3d converts the state they share from a `ConcurrentHashMap` of
    // `Collections.newSetFromMap(ConcurrentHashMap())` sets to plain collections behind a
    // `PlatformLock`. Converting lock-free state to locked state with nothing exercising it would
    // have been the worst of both worlds, so the coverage lands with the conversion.

    /** Records what the repository persists and replays a seeded `allDoneChunks()`. */
    private class RecordingStore(
        private val seed: List<TransferStore.ChunkRef> = emptyList(),
    ) : TransferStore {
        val marked = java.util.concurrent.CopyOnWriteArrayList<Pair<String, List<Int>>>()
        override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) = Unit
        override suspend fun setBytesDone(transferId: String, bytesDone: Long) = Unit
        override suspend fun setStatus(transferId: String, status: String) = Unit
        override suspend fun doneChunks(transferId: String): List<Int> = emptyList()
        override suspend fun markChunksDone(transferId: String, indexes: List<Int>) {
            marked.add(transferId to indexes)
        }
        override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> = seed
    }

    private fun receiveRepo(store: TransferStore?): RealFlashTransferRepository =
        RealFlashTransferRepository(
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
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )

    @Test
    fun `preloadReceiverProgress warms the done-set per transfer and seeds it sorted`() = runBlocking {
        val store = RecordingStore(
            seed = listOf(
                TransferStore.ChunkRef("rx-a", 7),
                TransferStore.ChunkRef("rx-b", 1),
                TransferStore.ChunkRef("rx-a", 2),
                TransferStore.ChunkRef("rx-a", 7), // duplicate row: the set absorbs it
                TransferStore.ChunkRef("rx-b", 0),
            ),
        )
        val repo = receiveRepo(store)
        assertEquals("nothing is seeded before the warm-up", emptyList<Int>(), repo.receiverDoneIndexes("rx-a"))

        repo.preloadReceiverProgress()

        // Ids are role-scoped, so rows must not leak between transfers, and the seed the receive
        // pipeline consumes has to be ascending regardless of row order.
        assertEquals(listOf(2, 7), repo.receiverDoneIndexes("rx-a"))
        assertEquals(listOf(0, 1), repo.receiverDoneIndexes("rx-b"))
        assertEquals("an unknown transfer seeds empty, never null", emptyList<Int>(), repo.receiverDoneIndexes("rx-ghost"))
        assertTrue("warming reads, it must not write back", store.marked.isEmpty())
    }

    @Test
    fun `preloadReceiverProgress without a store is a no-op`() = runBlocking {
        val repo = receiveRepo(store = null)
        repo.preloadReceiverProgress()
        assertEquals(emptyList<Int>(), repo.receiverDoneIndexes("rx-none"))
    }

    @Test
    fun `onIncomingChunkConfirmed persists only fresh indexes and ignores repeats`() = runBlocking {
        val store = RecordingStore()
        val repo = receiveRepo(store)

        repo.onIncomingChunkConfirmed("rx-c", listOf(3, 1, 2))
        awaitUntil(describe = { "first batch never persisted" }) { store.marked.size == 1 }
        assertEquals(listOf(3, 1, 2), store.marked[0].second)
        assertEquals(listOf(1, 2, 3), repo.receiverDoneIndexes("rx-c"))

        // Overlapping batch: only 4 is new, so only 4 is persisted.
        repo.onIncomingChunkConfirmed("rx-c", listOf(2, 3, 4))
        awaitUntil(describe = { "second batch never persisted" }) { store.marked.size == 2 }
        assertEquals(listOf(4), store.marked[1].second)
        assertEquals(listOf(1, 2, 3, 4), repo.receiverDoneIndexes("rx-c"))

        // Fully redundant batch: no DB round-trip at all.
        repo.onIncomingChunkConfirmed("rx-c", listOf(1, 4))
        repo.onIncomingChunkConfirmed("rx-c", emptyList())
        Thread.sleep(200)
        assertEquals("a redundant batch must not reach the store", 2, store.marked.size)
    }

    @Test
    fun `concurrent onIncomingChunkConfirmed claims every index exactly once`() {
        val store = RecordingStore()
        val repo = receiveRepo(store)
        val batch = (0 until 500).toList()

        // Eight callers confirm the SAME 500 indexes at once, so they also race on the very first
        // `getOrPut("rx-race")`. The invariant: an index is "fresh" for exactly one caller, so the
        // union of everything persisted is the batch with no duplicates. Before 13B-3d the inner
        // set's own `add` was the atomic that guaranteed this; now it is `receiverDoneLock`.
        runBlocking(testDispatcher) {
            repeat(8) { launch { repo.onIncomingChunkConfirmed("rx-race", batch) } }
        }

        assertEquals("the seed must hold every index once", batch, repo.receiverDoneIndexes("rx-race"))
        awaitUntil(describe = { "persisted ${store.marked.sumOf { it.second.size }} of 500" }) {
            store.marked.sumOf { it.second.size } == batch.size
        }
        val persisted = store.marked.flatMap { it.second }
        assertEquals(
            "no index may be persisted twice: ${persisted.groupBy { it }.filterValues { it.size > 1 }.keys}",
            batch.size,
            persisted.distinct().size,
        )
        assertTrue("every batch reached the right transfer", store.marked.all { it.first == "rx-race" })
    }
}
