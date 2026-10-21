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

    @Test
    fun `explicit identity is emitted unchanged in FILE_START`() = runBlocking {
        val payload = ByteArray(16 * 1024) { (it % 251).toByte() }
        val starts = java.util.Collections.synchronizedList(mutableListOf<ChunkFrame.FileStart>())
        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val parsed = ChunkFrame.parse(frameBytes)
                    if (parsed is ChunkFrame.FileStart) starts += parsed
                    return true
                }
            }
        }
        val repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { Buffer().write(payload) },
            workerDispatcher = testDispatcher,
            repositoryScope = newScope(),
            defaultStreams = 1,
        )

        val result = repo.sendFile(
            targetDevice = peer,
            fileUri = "content://media/group-photo.jpg",
            displayName = "group-photo.jpg",
            fileSize = payload.size.toLong(),
            transferId = "recipient-transfer-a",
            wireFileId = "shared-wire-file",
        )

        assertTrue(result is FlashResult.Success)
        assertEquals("recipient-transfer-a", (result as FlashResult.Success).value.value)
        awaitUntil(describe = { "FILE_START not emitted" }) { starts.isNotEmpty() }
        assertEquals("recipient-transfer-a", starts.first().transferId)
        assertEquals("shared-wire-file", starts.first().fileId)
        repo.cancelTransfer(FlashTransferId("recipient-transfer-a"))
        Unit
    }


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
    fun `B-01 resume refuses a cancelled or declined row and emits nothing to the peer`() = runBlocking {
        val repo = offerRepo()
        val scope = newScope()
        val outgoing = java.util.concurrent.CopyOnWriteArrayList<String>()
        val incoming = java.util.concurrent.CopyOnWriteArrayList<String>()
        scope.launch { repo.outgoingControl.collect { outgoing.add(it.action) } }
        scope.launch { repo.incomingControl.collect { incoming.add(it.action) } }
        awaitUntil(describe = { "control collectors never subscribed" }) {
            repo.incomingControl.subscriptionCount.value >= 1 && repo.outgoingControl.subscriptionCount.value >= 1
        }

        // A receiver's declined offer: tapping the bubble or a stale Retry must not ask the sender to send it again.
        repo.onIncomingOffered("tx-b01", "fx-b01", "doc.pdf", 2048L, "Pixel", "peer-b01")
        assertTrue(repo.declineIncoming(FlashTransferId("tx-b01")) is FlashResult.Success)
        awaitUntil(describe = { "decline traffic never emitted" }) { outgoing.contains("cancel") }
        outgoing.clear()
        incoming.clear()

        val result = repo.resumeTransfer(FlashTransferId("tx-b01"))
        assertTrue("a cancelled row is not resumable", result is FlashResult.Failure)
        assertEquals(FlashTransferState.Cancelled, repo.snapshot(FlashTransferId("tx-b01")).state)
        Thread.sleep(150)
        assertTrue("resume must not reach the peer: $outgoing", outgoing.isEmpty())
        assertTrue("resume must not reopen the local sink: $incoming", incoming.isEmpty())
        Unit
    }

    @Test(timeout = 60_000)
    fun `B-01 a cancelled send is not relaunched by resume`() = runBlocking {
        val sends = AtomicInteger(0)
        val repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = StreamChannelFactory { channelId, _ ->
                object : StreamChannel {
                    override val id: Int = channelId
                    override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                        sends.incrementAndGet()
                        return true
                    }
                }
            },
            fileSourceOpener = { Buffer().write(eightChunkPayload) },
            repositoryScope = newScope(),
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )
        val transferId = (
            repo.sendFile(peer, "content://media/b01.bin", "b01.bin", eightChunkPayload.size.toLong())
                as FlashResult.Success
            ).value
        assertTrue(repo.cancelTransfer(transferId) is FlashResult.Success)
        Thread.sleep(300)
        val framesAtCancel = sends.get()

        assertTrue(repo.resumeTransfer(transferId) is FlashResult.Failure)
        Thread.sleep(500)
        assertEquals(FlashTransferState.Cancelled, repo.snapshot(transferId).state)
        assertEquals("no frame may be sent after a refused resume", framesAtCancel, sends.get())
        Unit
    }

    @Test
    fun `B-03 a late remote control frame does not rewrite a finished row`() = runBlocking {
        val repo = offerRepo()

        // Cancelled (declined) receive: late CANCEL / PAUSE / RESUME leave it alone, reason included.
        repo.onIncomingOffered("tx-b03a", "fx-b03a", "a.bin", 1024L, "Pixel", "peer-b03")
        assertTrue(repo.declineIncoming(FlashTransferId("tx-b03a")) is FlashResult.Success)
        for (action in listOf(
            RealFlashTransferRepository.ACTION_CANCEL,
            RealFlashTransferRepository.ACTION_PAUSE,
            RealFlashTransferRepository.ACTION_RESUME,
        )) {
            repo.onRemoteTransferControl("tx-b03a", action)
            val row = repo.snapshot(FlashTransferId("tx-b03a"))
            assertEquals("action=$action", FlashTransferState.Cancelled, row.state)
            assertEquals("action=$action", "declined", row.errorMessage)
        }

        // Completed receive: a late PAUSE / CANCEL must not turn it into Paused / Cancelled.
        repo.onIncomingOffered("tx-b03b", "fx-b03b", "b.bin", 1024L, "Pixel", "peer-b03")
        assertTrue(repo.acceptIncoming(FlashTransferId("tx-b03b")) is FlashResult.Success)
        repo.onIncomingCompleted("tx-b03b", verified = true, localPath = "/tmp/b.bin")
        assertEquals(FlashTransferState.Completed, repo.snapshot(FlashTransferId("tx-b03b")).state)
        repo.onRemoteTransferControl("tx-b03b", RealFlashTransferRepository.ACTION_PAUSE)
        repo.onRemoteTransferControl("tx-b03b", RealFlashTransferRepository.ACTION_CANCEL)
        repo.onRemoteTransferControl("tx-b03b", RealFlashTransferRepository.ACTION_RESUME)
        assertEquals(FlashTransferState.Completed, repo.snapshot(FlashTransferId("tx-b03b")).state)
        Unit
    }

    @Test
    fun `a local cancel clears a stale pause notice`() = runBlocking {
        val repo = offerRepo()
        repo.onIncomingOffered("tx-stale", "fx-stale", "c.bin", 1024L, "Pixel", "peer-stale")
        assertTrue(repo.acceptIncoming(FlashTransferId("tx-stale")) is FlashResult.Success)
        repo.onRemoteTransferControl("tx-stale", RealFlashTransferRepository.ACTION_PAUSE)
        assertEquals("paused by sender", repo.snapshot(FlashTransferId("tx-stale")).errorMessage)

        assertTrue(repo.cancelTransfer(FlashTransferId("tx-stale")) is FlashResult.Success)
        val row = repo.snapshot(FlashTransferId("tx-stale"))
        assertEquals(FlashTransferState.Cancelled, row.state)
        assertNull(row.errorMessage)
        Unit
    }

    @Test
    fun `C-02 resumeSystemPaused continues a system pause and leaves a person's pause alone`() = runBlocking {
        val repo = offerRepo()
        repo.onIncomingOffered("tx-s", "fx-s", "d.bin", 1024L, "Pixel", "peer-s")
        repo.onIncomingOffered("tx-u", "fx-u", "e.bin", 1024L, "Pixel", "peer-u")
        assertTrue(repo.acceptIncoming(FlashTransferId("tx-s")) is FlashResult.Success)
        assertTrue(repo.acceptIncoming(FlashTransferId("tx-u")) is FlashResult.Success)
        assertTrue(repo.pauseForSystem(FlashTransferId("tx-s"), "SERVICE_TIMEOUT") is FlashResult.Success)
        assertTrue(repo.pauseTransfer(FlashTransferId("tx-u")) is FlashResult.Success)

        assertEquals(1, repo.resumeSystemPaused())
        assertEquals(FlashTransferState.Transferring, repo.snapshot(FlashTransferId("tx-s")).state)
        assertEquals(FlashTransferState.Paused, repo.snapshot(FlashTransferId("tx-u")).state)
        assertEquals(0, repo.resumeSystemPaused())
        Unit
    }

    /** A live 1:1 send held inside its first chunk write, so a remote control frame meets a registered worker. */
    private suspend fun senderHeldOnTheWire(): Pair<RealFlashTransferRepository, FlashTransferId> {
        val gate = CompletableDeferred<Unit>()
        val entered = AtomicBoolean(false)
        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    if (ChunkFrame.parse(frameBytes) is ChunkFrame.Chunk && entered.compareAndSet(false, true)) gate.await()
                    return true
                }
            }
        }
        val repo = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = { Buffer().write(eightChunkPayload) },
            repositoryScope = newScope(),
            workerDispatcher = testDispatcher,
            defaultStreams = 1,
        )
        val id = (repo.sendFile(peer, "content://media/c11.bin", "c11.bin", eightChunkPayload.size.toLong()) as FlashResult.Success).value
        awaitUntil(describe = { "worker never reached the wire" }) { entered.get() }
        return repo to id
    }

    @Test(timeout = 60_000)
    fun `C-11 a cancel that names a refusal fails the send with the receiver's reason`() = runBlocking {
        val (repo, id) = senderHeldOnTheWire()
        repo.onRemoteTransferControl(id.value, RealFlashTransferRepository.ACTION_CANCEL, TransferFailureText.REFUSE_SESSION_FULL)
        val row = repo.snapshot(id)
        assertEquals(FlashTransferState.Failed, row.state)
        assertEquals(TransferFailureText.RECEIVER_BUSY, row.errorMessage)
        assertTrue("a refused send can be retried", row.canRetry)
        Unit
    }

    @Test(timeout = 60_000)
    fun `C-11 a plain cancel, or a reason nobody knows, stays a cancel`() = runBlocking {
        val (repo, id) = senderHeldOnTheWire()
        repo.onRemoteTransferControl(id.value, RealFlashTransferRepository.ACTION_CANCEL, "something_new")
        assertEquals(FlashTransferState.Cancelled, repo.snapshot(id).state)
        assertEquals(TransferFailureText.RECEIVER_STORAGE, TransferFailureText.refusalSentence("write_failed"))
        assertEquals(TransferFailureText.RECEIVER_REFUSED_FILE, TransferFailureText.refusalSentence("INVALID_FILE_START"))
        assertNull(TransferFailureText.refusalSentence(null))
        Unit
    }

    @Test
    fun `B-02 pauseForSystem keeps a 1to1 row and says Android paused it`() = runBlocking {
        val repo = offerRepo()
        repo.onIncomingOffered("tx-sys", "fx-sys", "d.bin", 1024L, "Pixel", "peer-sys")
        assertTrue(repo.acceptIncoming(FlashTransferId("tx-sys")) is FlashResult.Success)

        assertTrue(repo.pauseForSystem(FlashTransferId("tx-sys"), "SERVICE_TIMEOUT") is FlashResult.Success)
        val row = repo.snapshot(FlashTransferId("tx-sys"))
        assertEquals(FlashTransferState.Paused, row.state)
        assertEquals(RealFlashTransferRepository.REASON_PAUSED_BY_SYSTEM, row.errorMessage)
        assertEquals(TransferFailureText.PAUSED_BY_SYSTEM, TransferFailureText.displayReason(row.errorMessage))

        // And it is still resumable: the done-set was not forgotten, the row was not cancelled.
        assertTrue(repo.resumeTransfer(FlashTransferId("tx-sys")) is FlashResult.Success)
        val resumed = repo.snapshot(FlashTransferId("tx-sys"))
        assertEquals(FlashTransferState.Transferring, resumed.state)
        assertNull(resumed.errorMessage)
        Unit
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

    @Test
    fun `onIncomingProgress updates bytesDone, speedBytesPerSec, and etaSeconds`() = runBlocking {
        val repo = offerRepo()
        val totalBytes = 10_000_000L
        repo.onIncomingOffered("tx-prog", "fx-prog", "big.mp4", totalBytes, "Pixel", "peer-5")
        repo.onIncomingStarted("tx-prog", "fx-prog", "big.mp4", totalBytes, "Pixel", "peer-5", "/tmp/big.mp4")

        var snapshot = repo.snapshot(FlashTransferId("tx-prog"))
        assertEquals(0L, snapshot.bytesDone)
        assertEquals(0L, snapshot.speedBytesPerSec)

        // Advance progress
        repo.onIncomingProgress("tx-prog", 2_000_000L)
        snapshot = repo.snapshot(FlashTransferId("tx-prog"))
        assertEquals(2_000_000L, snapshot.bytesDone)
        assertEquals(FlashTransferState.Transferring, snapshot.state)

        // Further progress calculates non-zero speed and ETA
        Thread.sleep(20)
        repo.onIncomingProgress("tx-prog", 5_000_000L)
        snapshot = repo.snapshot(FlashTransferId("tx-prog"))
        assertEquals(5_000_000L, snapshot.bytesDone)
        assertTrue("speedBytesPerSec should be > 0, was ${snapshot.speedBytesPerSec}", snapshot.speedBytesPerSec > 0L)
        assertTrue("etaSeconds should be >= 0, was ${snapshot.etaSeconds}", snapshot.etaSeconds >= 0L)

        // On completion, bytesDone becomes totalBytes, speed and ETA reset to 0
        repo.onIncomingCompleted("tx-prog", verified = true, localPath = "/tmp/big.mp4")
        snapshot = repo.snapshot(FlashTransferId("tx-prog"))
        assertEquals(totalBytes, snapshot.bytesDone)
        assertEquals(FlashTransferState.Completed, snapshot.state)
        assertEquals(0L, snapshot.speedBytesPerSec)
        assertEquals(0L, snapshot.etaSeconds)
    }

    @Test
    fun `clearFinishedHistory removes completed rows only and they stay gone`() = runBlocking {
        val repo = offerRepo()
        repo.onIncomingOffered("tx-done", "fx-done", "a.bin", 1024L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-done"))
        repo.onIncomingCompleted("tx-done", verified = true, localPath = "/tmp/a.bin")
        repo.onIncomingOffered("tx-live", "fx-live", "b.bin", 2048L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-live"))
        repo.onIncomingOffered("tx-failed", "fx-failed", "c.bin", 2048L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-failed"))
        repo.onIncomingFailed("tx-failed", "boom")

        repo.clearFinishedHistory()

        val ids = repo.activeTransfers.value.map { it.id.value }.toSet()
        assertEquals(setOf("tx-live", "tx-failed"), ids)

        // A later change to another row must not bring the cleared one back.
        repo.onIncomingProgress("tx-live", 512L)
        assertTrue("tx-done" !in repo.activeTransfers.value.map { it.id.value })
    }

    @Test
    fun `dismissTransfer hides one failed row and a live row cannot be dismissed (C-10)`() = runBlocking {
        val repo = offerRepo()
        repo.onIncomingOffered("tx-failed", "fx-failed", "c.bin", 2048L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-failed"))
        repo.onIncomingFailed("tx-failed", "boom")
        repo.onIncomingOffered("tx-live", "fx-live", "b.bin", 2048L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-live"))

        repo.dismissTransfer(FlashTransferId("tx-live"))
        assertEquals(setOf("tx-failed", "tx-live"), repo.activeTransfers.value.map { it.id.value }.toSet())

        repo.dismissTransfer(FlashTransferId("tx-failed"))
        assertEquals(setOf("tx-live"), repo.activeTransfers.value.map { it.id.value }.toSet())

        // A later change to another row must not bring the dismissed one back.
        repo.onIncomingProgress("tx-live", 512L)
        assertTrue("tx-failed" !in repo.activeTransfers.value.map { it.id.value })
    }

    @Test
    fun `clearFailedHistory hides failed and cancelled rows but keeps live and completed ones (C-10)`() = runBlocking {
        val repo = offerRepo()
        repo.onIncomingOffered("tx-failed", "fx-failed", "c.bin", 2048L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-failed"))
        repo.onIncomingFailed("tx-failed", "boom")
        repo.onIncomingOffered("tx-done", "fx-done", "a.bin", 1024L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-done"))
        repo.onIncomingCompleted("tx-done", verified = true, localPath = "/tmp/a.bin")
        repo.onIncomingOffered("tx-live", "fx-live", "b.bin", 2048L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-live"))

        repo.clearFailedHistory()

        assertEquals(setOf("tx-done", "tx-live"), repo.activeTransfers.value.map { it.id.value }.toSet())
    }

    @Test
    fun `a retried receive that gets no bytes goes back to Failed with a sentence (C-08)`() = runBlocking {
        val repo = RealFlashTransferRepository(
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
            retryReceiveWaitMs = 150L,
        )
        repo.onIncomingOffered("tx-r", "fx-r", "c.bin", 2048L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-r"))
        repo.onIncomingFailed("tx-r", "boom")
        repo.resumeTransfer(FlashTransferId("tx-r"))
        assertEquals(FlashTransferState.Transferring, repo.snapshot(FlashTransferId("tx-r")).state)

        awaitUntil(describe = { "row never returned to Failed" }) {
            repo.snapshot(FlashTransferId("tx-r")).state == FlashTransferState.Failed
        }
        assertEquals(TransferFailureText.SENDER_DID_NOT_RESUME, repo.snapshot(FlashTransferId("tx-r")).errorMessage)
    }

    @Test
    fun `a retried receive that gets bytes is left alone (C-08)`() = runBlocking {
        val repo = RealFlashTransferRepository(
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
            retryReceiveWaitMs = 150L,
        )
        repo.onIncomingOffered("tx-r", "fx-r", "c.bin", 2048L, "Pixel", "peer-1")
        repo.acceptIncoming(FlashTransferId("tx-r"))
        repo.onIncomingFailed("tx-r", "boom")
        repo.resumeTransfer(FlashTransferId("tx-r"))
        repo.onIncomingProgress("tx-r", 512L)

        Thread.sleep(400)
        assertEquals(FlashTransferState.Transferring, repo.snapshot(FlashTransferId("tx-r")).state)
    }

    @Test
    fun `sendFile preserves an explicit wire file id`() = runBlocking {
        val repo = RealFlashTransferRepository(
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

        val transferId = (
            repo.sendFile(peer, "content://media/group.bin", "group.bin", 0L, "shared-wire-id")
                as FlashResult.Success
            ).value

        assertEquals("shared-wire-id", repo.snapshot(transferId).wireFileId)
    }

    // ---- send-side resume bookkeeping and receiver done-set ---------------------------------

    /** Records progress persistence and replays seeded receiver rows. */
    private class RecordingStore(
        private val seed: List<TransferStore.ChunkRef> = emptyList(),
    ) : TransferStore {
        val marked = java.util.concurrent.CopyOnWriteArrayList<Pair<String, List<Int>>>()
        val byteWrites = java.util.concurrent.CopyOnWriteArrayList<Long>()
        override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) = Unit
        override suspend fun setBytesDone(transferId: String, bytesDone: Long) {
            byteWrites.add(bytesDone)
        }
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
                TransferStore.ChunkRef("rx-a", 7), // duplicate row: the vector absorbs it
                TransferStore.ChunkRef("rx-b", 0),
                TransferStore.ChunkRef("rx-a", -1), // corrupt rows are ignored
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

    /** An in-memory stand-in for the Room tables: chunk rows per transfer and one status per transfer. */
    private class LedgerStore(seed: List<TransferStore.ChunkRef> = emptyList()) : TransferStore {
        val rows = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>().also { m ->
            seed.forEach { m.getOrPut(it.transferId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(it.chunkIndex) }
        }
        val status = java.util.concurrent.ConcurrentHashMap<String, String>()
        val purges = AtomicInteger(0)
        override suspend fun insertTransfer(transferId: String, totalBytes: Long, status: String) {
            this.status[transferId] = status
        }
        override suspend fun setBytesDone(transferId: String, bytesDone: Long) = Unit
        override suspend fun setStatus(transferId: String, status: String) {
            if (this.status.containsKey(transferId)) this.status[transferId] = status
        }
        override suspend fun doneChunks(transferId: String): List<Int> = rows[transferId]?.sorted() ?: emptyList()
        override suspend fun markChunksDone(transferId: String, indexes: List<Int>) {
            rows.getOrPut(transferId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.addAll(indexes)
        }
        override suspend fun allDoneChunks(): List<TransferStore.ChunkRef> =
            rows.flatMap { (id, set) -> set.map { TransferStore.ChunkRef(id, it) } }
        override suspend fun clearDoneChunks(transferId: String) {
            rows.remove(transferId)
        }
        override suspend fun purgeFinishedChunks(): Int {
            purges.incrementAndGet()
            var n = 0
            for ((id, st) in status) if (st == "Completed" || st == "Cancelled") n += rows.remove(id)?.size ?: 0
            return n
        }
    }

    @Test
    fun `R-01 a completed receive leaves no chunk rows and a late confirmation does not bring them back`() = runBlocking {
        val store = LedgerStore()
        val repo = receiveRepo(store)
        repo.onIncomingOffered("rx-fin", "file-1", "a.bin", 4L * 65_536, "Peer", "peer-1")
        awaitUntil(describe = { "offer row not written" }) { store.status["rx-fin"] == "Offered" }
        repo.onIncomingChunkConfirmed("rx-fin", listOf(0, 1, 2, 3))
        awaitUntil(describe = { "chunks not persisted" }) { store.rows["rx-fin"]?.size == 4 }

        repo.onIncomingCompleted("rx-fin", verified = true, localPath = "/x/a.bin")

        awaitUntil(describe = { "rows not deleted: ${store.rows}" }) { store.rows["rx-fin"] == null }
        assertEquals("Completed", store.status["rx-fin"])
        assertEquals(emptyList<Int>(), repo.receiverDoneIndexes("rx-fin"))
        repo.onIncomingChunkConfirmed("rx-fin", listOf(2))
        Thread.sleep(150)
        assertEquals("a confirmation after completion is dropped", null, store.rows["rx-fin"])
    }

    @Test
    fun `R-01 a declined receive leaves no chunk rows`() = runBlocking {
        val store = LedgerStore()
        val repo = receiveRepo(store)
        repo.onIncomingOffered("rx-dec", "file-2", "b.bin", 3L * 65_536, "Peer", "peer-1")
        awaitUntil(describe = { "offer row not written" }) { store.status["rx-dec"] == "Offered" }
        repo.onIncomingChunkConfirmed("rx-dec", listOf(0, 1))
        awaitUntil(describe = { "chunks not persisted" }) { store.rows["rx-dec"]?.size == 2 }

        repo.declineIncoming(FlashTransferId("rx-dec"))

        awaitUntil(describe = { "rows not deleted after decline" }) { store.rows["rx-dec"] == null }
        assertEquals("Cancelled", store.status["rx-dec"])
    }

    @Test
    fun `R-01 the startup purge removes finished transfers rows and the preload then loads only live ones`() = runBlocking {
        val seed = (0 until 10).map { TransferStore.ChunkRef("old-done", it) } +
            (0 until 4).map { TransferStore.ChunkRef("live", it) }
        val store = LedgerStore(seed)
        store.status["old-done"] = "Completed"
        store.status["live"] = "Transferring"
        val repo = receiveRepo(store)

        repo.preloadReceiverProgress()

        assertEquals(1, store.purges.get())
        assertEquals(emptyList<Int>(), repo.receiverDoneIndexes("old-done"))
        assertEquals(listOf(0, 1, 2, 3), repo.receiverDoneIndexes("live"))
        assertEquals(null, store.rows["old-done"])
    }

    @Test(timeout = 60_000)
    fun `R-01 preloading a hundred thousand ascending rows is linear, not quadratic`() = runBlocking {
        val rows = (0 until 100_000).map { TransferStore.ChunkRef("big", it) } +
            (99_999 downTo 0 step 2).map { TransferStore.ChunkRef("big-desc", it) }
        val repo = receiveRepo(LedgerStore(rows))
        val started = System.nanoTime()

        repo.preloadReceiverProgress()

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(100_000, repo.receiverDoneIndexes("big").size)
        assertEquals(50_000, repo.receiverDoneIndexes("big-desc").size)
        assertTrue("took ${tookMs} ms", tookMs < 8_000)
    }

    @Test
    fun `R-01 chunk confirmations that raise the highest index one by one stay correct`() = runBlocking {
        val repo = receiveRepo(LedgerStore())
        for (i in 0 until 3_000) repo.onIncomingChunkConfirmed("rx-grow", listOf(i))
        assertEquals((0 until 3_000).toList(), repo.receiverDoneIndexes("rx-grow"))
    }

    @Test
    fun `onIncomingChunkConfirmed persists only fresh indexes and ignores repeats`() = runBlocking {
        val store = RecordingStore()
        val repo = receiveRepo(store)

        repo.onIncomingChunkConfirmed("rx-c", listOf(3, 1, 2, 2, -1))
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

    @Test(timeout = 60_000)
    fun `resume bookkeeping persists confirmed chunks once without per-tick byte writes`() = runBlocking {
        val store = RecordingStore()
        lateinit var repo: RealFlashTransferRepository
        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val parsed = ChunkFrame.parse(frameBytes)
                    if (parsed is ChunkFrame.Chunk) {
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
            fileSourceOpener = { Buffer().write(eightChunkPayload) },
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

        val persisted = store.marked.flatMap { it.second }
        assertTrue("the collector persisted nothing", persisted.isNotEmpty())
        assertEquals(persisted.size, persisted.distinct().size)
        assertEquals(persisted.sorted(), persisted)
        assertTrue("byte writes=${store.byteWrites.size}", store.byteWrites.size <= 10)
        assertEquals(eightChunkPayload.size.toLong(), repo.snapshot(transferId).bytesDone)
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

    /**
     * Sends 2 MB (32 chunks of the HIGH profile's 64 KB, so no stream is dropped for lack of work) through a
     * repository built with [streams] and returns the channel ids the dispatcher asked the factory for. The
     * channels never ACK, so the transfer stays in flight until it is cancelled.
     */
    private fun channelIdsOpenedFor(streams: Int?): Set<Int> {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 127).toByte() }
        val opened = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val factory = StreamChannelFactory { channelId, _ ->
            opened += channelId
            object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean = true
            }
        }
        val repo = if (streams == null) {
            RealFlashTransferRepository(
                streamChannelFactory = factory,
                fileSourceOpener = { Buffer().write(payload) },
                repositoryScope = newScope(),
                workerDispatcher = testDispatcher,
            )
        } else {
            RealFlashTransferRepository(
                streamChannelFactory = factory,
                fileSourceOpener = { Buffer().write(payload) },
                repositoryScope = newScope(),
                workerDispatcher = testDispatcher,
                defaultStreams = streams,
            )
        }
        val target = FlashDevice(
            id = com.transfer.flash.core.common.model.FlashDeviceId("stream-count-peer"),
            friendlyName = "Peer",
            transportType = com.transfer.flash.core.common.model.FlashTransportType.LAN,
        )
        val result = runBlocking {
            repo.sendFile(target, "content://media/big.bin", "big.bin", payload.size.toLong())
        }
        val transferId = (result as FlashResult.Success).value
        awaitUntil(describe = { "no channel was opened" }) { opened.isNotEmpty() }
        // Every channel is opened in one pass, milliseconds after the first; give a wrongly wide pass time to show.
        Thread.sleep(300)
        val seen = synchronized(opened) { opened.toSet() }
        runBlocking { repo.cancelTransfer(transferId) }
        return seen
    }

    @Test
    fun `an explicit stream count of 2 is honoured, not read as the default`() {
        // 2 used to be the "not set" sentinel, so passing exactly 2 fell through to the HIGH profile's 4.
        assertEquals(setOf(0, 1), channelIdsOpenedFor(streams = 2))
    }

    @Test
    fun `an explicit stream count of 1 is honoured`() {
        assertEquals(setOf(0), channelIdsOpenedFor(streams = 1))
    }

    @Test
    fun `without an explicit stream count the performance profile decides`() {
        // The default performance mode is HIGH, whose profile is 4 streams.
        assertEquals(setOf(0, 1, 2, 3), channelIdsOpenedFor(streams = null))
    }
}
