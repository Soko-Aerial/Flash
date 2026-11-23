package com.transfer.flash.core.engine

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-style regression for the retry timing the coordinator review called out:
 * the receiver's durable-set announcement arrives **after** FILE_START, not preloaded before the
 * sender starts. The sender must not replay the whole receiver-durable prefix while it waits for
 * that announcement; only the bounded in-flight window may be replayed.
 *
 * The receive side is the real [FlashInboundRouter] path used by the desktop host; the send side is
 * the real [RealFlashTransferRepository] with no transfer store (the desktop configuration).
 */
class ResumeAnnouncementTimingTest {

    private val executor = Executors.newFixedThreadPool(4)
    private val workerDispatcher = executor.asCoroutineDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        executor.shutdownNow()
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(workerDispatcher + SupervisorJob()).also { scopes += it }

    private fun awaitUntil(timeoutMs: Long = 20_000, describe: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition timeout: ${describe()}")
            Thread.sleep(5)
        }
    }

    private val chunkSize = 64 * 1024
    private val totalChunks = 16
    private val durablePrefix = 8
    private val payload = ByteArray(totalChunks * chunkSize) { ((it * 31) + (it ushr 5)).toByte() }
    private val peer = FlashDevice(FlashDeviceId("peer-resume-timing"), "Pixel", FlashTransportType.LAN)

    private class Assembler(private val totalChunks: Int) : ChunkSink {
        val parts = arrayOfNulls<ByteArray>(totalChunks)
        var writes: Int = 0
            private set

        fun seed(index: Int, data: ByteArray) {
            parts[index] = data.copyOf()
        }

        override fun write(index: Int, data: ByteArray) {
            parts[index] = data.copyOf()
            writes++
        }

        fun matches(expected: ByteArray): Boolean {
            val flat = ByteArray(parts.sumOf { it!!.size })
            var offset = 0
            for (part in parts) {
                part!!.copyInto(flat, offset)
                offset += part.size
            }
            return flat.size == expected.size &&
                Sha256.hexEqualsConstantTime(Sha256.digestHex(flat), Sha256.digestHex(expected))
        }

        fun missingIndexes(): List<Int> = parts.indices.filter { parts[it] == null }
    }

    @Test(timeout = 60_000)
    fun `receiver announcement after FILE_START bounds durable-prefix replay to the in-flight window`() = runBlocking {
        val assembler = Assembler(totalChunks)
        for (index in 0 until durablePrefix) {
            val start = index * chunkSize
            assembler.seed(index, payload.copyOfRange(start, start + chunkSize))
        }

        val receivePipeline = ReceivePipeline(
            sink = assembler,
            emitSessionStarted = true,
            resumeIndexesProvider = { (0 until durablePrefix).toList() },
        )
        val incomingMeta = mutableMapOf<String, ChunkFrame.FileStart>()
        val receivedPaths = mutableMapOf<String, String>()
        val openHandles = mutableMapOf<String, RandomAccessSinkHandle>()
        val incomingOwners = mutableMapOf<String, String>()

        lateinit var sender: RealFlashTransferRepository
        val wireChunks = Collections.synchronizedList(mutableListOf<Int>())
        val receivedAcks = Collections.synchronizedList(mutableListOf<List<Int>>())
        val pendingReplies = mutableListOf<ByteArray>()
        var chunksSinceFileStart = 0
        var gateOpen = false
        val releaseAfterChunks = 2

        fun deliver(bytes: ByteArray) {
            val ack = ChunkFrame.parse(bytes)
            if (ack is ChunkFrame.AckBatch) receivedAcks.add(ack.indexes)
            sender.onInboundFrame(bytes)
        }

        fun openGateIfNeeded() {
            if (!gateOpen && chunksSinceFileStart >= releaseAfterChunks) {
                gateOpen = true
                val queued = pendingReplies.toList()
                pendingReplies.clear()
                queued.forEach { deliver(it) }
            }
        }

        val factory = StreamChannelFactory { channelId, _ ->
            object : StreamChannel {
                override val id: Int = channelId

                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val parsed = ChunkFrame.parse(frameBytes)
                    if (parsed is ChunkFrame.Chunk) {
                        wireChunks.add(parsed.index)
                        chunksSinceFileStart++
                    }

                    val replies = mutableListOf<ByteArray>()
                    FlashInboundRouter.routeInboundBinary(
                        peerDeviceId = peer.id.value,
                        data = frameBytes,
                        reply = { bytes -> replies += bytes; true },
                        peerLabel = "Pixel",
                        receivePipeline = receivePipeline,
                        incomingMeta = incomingMeta,
                        receivedPaths = receivedPaths,
                        openHandles = openHandles,
                        fileExistsAndSizeMatches = { _, _ -> false },
                        onOfferReceived = { _, _, _ -> },
                        incomingOwners = incomingOwners,
                    )
                    for (reply in replies) {
                        if (gateOpen) {
                            deliver(reply)
                        } else {
                            pendingReplies += reply
                        }
                    }
                    if (parsed is ChunkFrame.Chunk) openGateIfNeeded()
                    return true
                }
            }
        }

        sender = RealFlashTransferRepository(
            chunker = Chunker(),
            streamChannelFactory = factory,
            fileSourceOpener = FileSourceOpener { Buffer().write(payload) },
            repositoryScope = newScope(),
            workerDispatcher = workerDispatcher,
            defaultStreams = 1,
        )

        val id = (sender.sendFile(peer, "content://media/timing.bin", "timing.bin", payload.size.toLong())
            as FlashResult.Success).value
        awaitUntil(describe = { "row=" + sender.activeTransfers.value.firstOrNull { it.id == id } }) {
            sender.activeTransfers.value.firstOrNull { it.id == id }?.state == FlashTransferState.Completed
        }

        val prefixOnWire = wireChunks.count { it < durablePrefix }
        println("[resume-timing] durablePrefix=$durablePrefix prefixOnWire=$prefixOnWire wireChunks=$wireChunks acks=$receivedAcks")
        assertTrue(
            "the durable prefix was not replayed at all (no timing evidence): prefixOnWire=$prefixOnWire",
            prefixOnWire >= releaseAfterChunks,
        )
        assertTrue(
            "the durable prefix replayed beyond the in-flight window: $prefixOnWire of $durablePrefix",
            prefixOnWire <= releaseAfterChunks + 4,
        )
        assertTrue(
            "sender wrote all $totalChunks chunks, so the announcement did not take effect",
            wireChunks.size < totalChunks,
        )
        val missing = assembler.missingIndexes()
        assertTrue(
            "receiver never wrote indexes $missing; wire=$wireChunks acks=$receivedAcks",
            missing.isEmpty(),
        )
        assertTrue("assembled file differs from the source", assembler.matches(payload))
    }
}
