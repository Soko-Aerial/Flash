package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.ChunkSource
import com.transfer.flash.core.transfer.chunked.Chunker
import com.transfer.flash.core.transfer.chunked.FileMeta
import com.transfer.flash.core.transfer.chunked.Sha256
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for C5.7 multi-stream dispatch: real threads via Dispatchers.Default,
 * ordering controlled by gates/polling — no kotlinx-coroutines-test.
 */
class MultiStreamDispatcherTest {

    companion object {
        private val liveExecutors = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.ExecutorService>()
        private val chunkSize = 16_384

        /** 300 KB => 19 chunks (18 full + tail); exercises end-game K=8 across N=3 streams. */
        private val totalBytes = 300_000L
        private val payload = ByteArray(totalBytes.toInt()) { ((it * 31) + (it ushr 5)).toByte() }
        private val meta = FileMeta("ms-t", "file-ms", "big.bin", totalBytes)
    }

    // ---- fixtures ------------------------------------------------------------------------


    @After
    fun tearDown() {
        liveExecutors.forEach { runCatching { it.shutdownNow() } }
        liveExecutors.clear()
    }

    private class Assembler(totalChunks: Int) : ChunkSink {
        val parts = arrayOfNulls<ByteArray>(totalChunks)
        var writes = 0

        override fun write(index: Int, data: ByteArray) {
            parts[index] = data.copyOf()
            writes++
        }

        fun matches(expected: ByteArray): Boolean =
            Sha256.hexEqualsConstantTime(
                Sha256.digestHex(assembled()),
                Sha256.digestHex(expected),
            )

        private fun assembled(): ByteArray {
            val flat = ByteArray(parts.sumOf { it!!.size })
            var offset = 0
            for (part in parts) {
                part!!.copyInto(flat, offset)
                offset += part.size
            }
            return flat
        }
    }

    /**
     * In-memory loopback channel: pushes sender frames into the shared [receiver] and feeds every
     * emitted feedback frame back into the dispatcher tagged with THIS channel id — receiver
     * replies always travel down their arrival channel, like a real socket pair.
     */
    private open class LoopbackChannel(
        override val id: Int,
        private val receiver: MultiStreamReceiver,
        private val dispatcherProvider: () -> MultiStreamDispatcher,
    ) : StreamChannel {

        val sentIndexes: MutableList<Int> = Collections.synchronizedList(ArrayList())

        /** Runs before a CHUNK is forwarded; return false = channel death at that chunk. */
        var interceptor: ((frame: ChunkFrame.Chunk) -> Boolean)? = null

        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            when (val frame = ChunkFrame.parse(frameBytes)) {
                is ChunkFrame.FileStart -> forward(frameBytes)
                is ChunkFrame.AckBatch, is ChunkFrame.Complete -> Unit
                is ChunkFrame.Chunk -> {
                    if (interceptor?.invoke(frame) == false) return false
                    sentIndexes.add(frame.index)
                    forward(frameBytes)
                }

                null -> return false
            }
            return true
        }

        private fun forward(bytes: ByteArray) {
            for (event in receiver.onFrame(id, bytes)) {
                val feedback = when (event) {
                    is RoutedReceiveEvent.SessionStarted -> null
                    is RoutedReceiveEvent.AckBatchReady -> event.frameBytes
                    is RoutedReceiveEvent.Completed -> event.frameBytes
                    is RoutedReceiveEvent.Rejected -> throw AssertionError("rejected: $event")
                } ?: continue
                assertTrue(dispatcherProvider().onInboundFrame(id, feedback))
            }
        }
    }

    /** Channel whose CHUNK sends die after [failAfterChunks] successes. */
    private class DyingChannel(
        id: Int,
        receiver: MultiStreamReceiver,
        dispatcherProvider: () -> MultiStreamDispatcher,
        private val failAfterChunks: Int,
    ) : LoopbackChannel(id, receiver, dispatcherProvider) {
        var chunkAttempts = 0

        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            if (ChunkFrame.parse(frameBytes) is ChunkFrame.Chunk) {
                if (chunkAttempts >= failAfterChunks) return false
                chunkAttempts++
            }
            return super.sendFrame(frameBytes)
        }
    }

    /** Channel that blocks inside its first CHUNK send until [gate] completes. */
    private class GatedChannel(
        id: Int,
        receiver: MultiStreamReceiver,
        dispatcherProvider: () -> MultiStreamDispatcher,
        private val gate: CompletableFuture<Unit>,
    ) : LoopbackChannel(id, receiver, dispatcherProvider) {
        /** Set BEFORE blocking so observers can detect "slow channel engaged". */
        @Volatile
        var startedSending = false

        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            if (!startedSending && ChunkFrame.parse(frameBytes) is ChunkFrame.Chunk) {
                startedSending = true
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    gate.get(30, TimeUnit.SECONDS)
                }
            }
            return super.sendFrame(frameBytes)
        }
    }

    /** Channel that suspends its first CHUNK send until [waitFor] passes (start-order control). */
    private class OrderedFastChannel(
        id: Int,
        receiver: MultiStreamReceiver,
        dispatcherProvider: () -> MultiStreamDispatcher,
        private val waitFor: suspend () -> Unit,
    ) : LoopbackChannel(id, receiver, dispatcherProvider) {
        private var waited = false

        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
            if (!waited && ChunkFrame.parse(frameBytes) is ChunkFrame.Chunk) {
                waited = true
                waitFor()
            }
            return super.sendFrame(frameBytes)
        }
    }

    /** Builds a harness whose stream 1 is gated; streams 0/2 wait until stream 1 is blocked. */
    private fun gatedHarness(): Triple<Harness, CompletableFuture<Unit>, List<LoopbackChannel>> {
        val gate = CompletableFuture<Unit>()
        var slowRef: GatedChannel? = null
        val h = Harness({ id, r, p ->
            if (id == 1) {
                GatedChannel(id, r, p, gate).also { slowRef = it }
            } else {
                OrderedFastChannel(id, r, p) {
                    awaitUntil(condition = { slowRef?.startedSending == true })
                }
            }
        })
        return Triple(h, gate, h.channels)
    }


    private class Harness(
        val channelsFactory: (
            id: Int,
            receiver: MultiStreamReceiver,
            provider: () -> MultiStreamDispatcher,
        ) -> LoopbackChannel = ::LoopbackChannel,
        val streamCount: Int = 3,
    ) {
        private val testExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(8) { r -> Thread(r, "ms-harness-worker") }
        val testDispatcher = testExecutor.asCoroutineDispatcher()

        init {
            liveExecutors.add(testExecutor)
        }
        val assembler = Assembler(Chunker.totalChunks(totalBytes, chunkSize))
        val receiver = MultiStreamReceiver(assembler)
        lateinit var dispatcher: MultiStreamDispatcher
        val channels: List<LoopbackChannel> = (0 until streamCount).map { id ->
            channelsFactory(id, receiver) { dispatcher }
        }

        fun build(): MultiStreamDispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { Buffer().write(payload) },
            factory = StreamChannelFactory { id, _ -> channels.firstOrNull { it.id == id } },
            streamCount = streamCount,
            requestedChunkSize = chunkSize,
            workerDispatcher = testDispatcher,
        ).also { dispatcher = it }

        fun fastSentTotal(excludeId: Int): Int =
            channels.filter { it.id != excludeId }.sumOf { it.sentIndexes.size }
    }

    private fun awaitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean, describe: () -> String = { "" }) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                val stacks = java.lang.management.ManagementFactory.getThreadMXBean()
                    .dumpAllThreads(true, false)
                val interesting = stacks.joinToString("\n") { st ->
                    val frames = st.stackTrace.take(12).joinToString(" | ") { f -> f.className.substringAfterLast(".") + ":" + f.methodName }
                    "THREAD " + st.threadName + " state=" + st.threadState + " :: " + frames
                }
                throw AssertionError("condition timeout " + describe() + "\n" + interesting)
            }
            Thread.sleep(5)
        }
    }

    // ---- tests ---------------------------------------------------------------------------

    @Test
    fun `three streams move 300KB end to end - bytes identical, exactly once, endgame single-file`() =
        runBlocking {
            val h = Harness()
            val dispatcher = h.build()

            val result = dispatcher.send()

            val completed = result as? MultiStreamResult.Completed
                ?: throw AssertionError(
                    "expected Completed got $result",
                    (result as? MultiStreamResult.Failed)?.let { java.lang.AssertionError(it.reason) },
                )
            assertEquals(19, completed.totalChunks)
            assertEquals(
                "sentPerChannel=" + h.channels.joinToString { c -> "${c.id}:${c.sentIndexes.size}(${c.sentIndexes})" },
                19,
                completed.chunksSent,
            )
            assertEquals(0, completed.chunksSkippedResume)
            assertEquals(totalBytes, completed.bytesSent)
            assertEquals(true, completed.verified)
            assertTrue(completed.deadChannelIds.isEmpty())
            assertTrue(h.assembler.matches(payload))
            assertEquals("exactly-once writes", 19, h.assembler.writes)

            // End-game revision (ADR-015 follow-up): no exclusive owner — every tail
            // chunk still flows EXACTLY ONCE across channels (atomic claims).
            for (index in 11 until 19) {
                val senders = h.channels.count { it.sentIndexes.contains(index) }
                assertEquals("tail chunk $index exactly-once", 1, senders)
            }

            // Aggregate progress reached completion; terminal COMPLETE emitted exactly once.
            val finalProgress = dispatcher.progress.value
            assertEquals(totalBytes, finalProgress.bytesDone)
            assertNotNull(completed.completeFrameBytes)
            val parsedComplete = ChunkFrame.parse(completed.completeFrameBytes!!)
            assertTrue(parsedComplete is ChunkFrame.Complete)
        }

    @Test(timeout = 60_000)
    fun `slow gated channel - others finish the file, no deadlock, slow completes its one chunk`() =
        runBlocking {
            val (h, gate, channels) = gatedHarness()
            val dispatcher = h.build()
            var result: MultiStreamResult? = null
            val job = launch(Dispatchers.Default) { result = dispatcher.send() }

            val slow = channels[1]
            awaitUntil(condition = { h.fastSentTotal(excludeId = 1) == 13 }, describe = { "fast=" + h.fastSentTotal(1) + " dead=" + h.dispatcher.deadChannelsSnapshot() + " confirmed=" + h.dispatcher.confirmedCountSnapshot() })
            val slowGated = slow as GatedChannel
            assertTrue("slow engaged before fasts finished", slowGated.startedSending)
            gate.complete(Unit)
            job.join()

            assertTrue(result is MultiStreamResult.Completed)
            assertTrue(h.assembler.matches(payload))
            assertEquals(19, h.assembler.writes)
        }
    @Test
    fun `channel death mid transfer - unacked claims return to pool, survivor completes`() =
        runBlocking {
            val h = Harness(channelsFactory = { id, r, p ->
                if (id == 1) DyingChannel(id, r, p, failAfterChunks = 2) else LoopbackChannel(id, r, p)
            })
            val dispatcher = h.build()

            val result = dispatcher.send()

            assertTrue(result is MultiStreamResult.Completed)
            assertTrue(h.assembler.matches(payload))
            assertEquals(19, h.assembler.writes)
            assertTrue(dispatcher.deadChannelsSnapshot().contains(1))
            assertEquals(19, (result as MultiStreamResult.Completed).chunksSent)
        }

    @Test
    fun `all channels die - Failed with unconfirmed set`() = runBlocking {
        val h = Harness(channelsFactory = { id, r, p -> DyingChannel(id, r, p, failAfterChunks = 0) })
        val dispatcher = h.build()

        val result = dispatcher.send()

        val failed = result as MultiStreamResult.Failed
        assertTrue(failed.reason.isNotEmpty())
        assertEquals(3, failed.deadChannelIds.size)
        assertEquals(setOf(0, 1, 2), failed.deadChannelIds.toSet())
        assertEquals(19, failed.unconfirmedIndexes.size)
        assertEquals(0, h.assembler.writes)
    }

    // Regression (open-failure deadlock): a factory that refuses/throws on open must NOT hang.
    // The old code did `return@coroutineScope failWith(...)`, which stranded the already-launched
    // materializer in feeds[i].send() (buffer full, no worker draining) so coroutineScope never
    // returned. The `timeout` here turns any regression into a test failure instead of a hung suite.
    @Test(timeout = 30_000)
    fun `all streams fail to open - fails fast without hanging`() = runBlocking {
        val executor =
            java.util.concurrent.Executors.newFixedThreadPool(8) { r -> Thread(r, "ms-open-fail") }
        liveExecutors.add(executor)
        val dispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { Buffer().write(payload) },
            factory = StreamChannelFactory { _, _ -> null }, // every open refuses
            streamCount = 3,
            requestedChunkSize = chunkSize,
            workerDispatcher = executor.asCoroutineDispatcher(),
        )

        val result = dispatcher.send()

        val failed = result as? MultiStreamResult.Failed
            ?: throw AssertionError("expected Failed got $result")
        assertTrue(failed.reason.isNotEmpty())
        assertEquals(setOf(0, 1, 2), failed.deadChannelIds.toSet())
    }

    // Regression (skip-slot contract, StreamChannel KDoc): when only SOME streams open, the failed
    // slot becomes a dead worker that drains its feed and redistributes to the survivors, which
    // must still deliver the whole file exactly once.
    @Test(timeout = 60_000)
    fun `one stream fails to open - survivors complete the file`() = runBlocking {
        val executor =
            java.util.concurrent.Executors.newFixedThreadPool(8) { r -> Thread(r, "ms-open-skip") }
        liveExecutors.add(executor)
        val assembler = Assembler(Chunker.totalChunks(totalBytes, chunkSize))
        val receiver = MultiStreamReceiver(assembler)
        lateinit var dispatcher: MultiStreamDispatcher
        // Only ids 0 and 2 have real channels; id 1's open returns null (skipped slot).
        val channels = listOf(0, 2).map { id -> LoopbackChannel(id, receiver) { dispatcher } }
        dispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { Buffer().write(payload) },
            factory = StreamChannelFactory { id, _ -> channels.firstOrNull { it.id == id } },
            streamCount = 3,
            requestedChunkSize = chunkSize,
            workerDispatcher = executor.asCoroutineDispatcher(),
        )

        val result = dispatcher.send()

        val completed = result as? MultiStreamResult.Completed
            ?: throw AssertionError("expected Completed got $result")
        assertEquals(19, completed.chunksSent)
        assertTrue(completed.deadChannelIds.contains(1))
        assertTrue(assembler.matches(payload))
        assertEquals("exactly-once writes", 19, assembler.writes)
    }

    @Test(timeout = 60_000)
    fun `progress is monotonic and eta sane while transferring`() = runBlocking {
        val (h, gate, _) = gatedHarness()
        val dispatcher = h.build()
        val samples = Collections.synchronizedList(ArrayList<MultiStreamProgress>())
        val sampler = launch(Dispatchers.Default) {
            while (dispatcher.progress.value.bytesDone < totalBytes && !gate.isDone) {
                samples.add(dispatcher.progress.value)
                Thread.sleep(2)
            }
        }
        val job = launch(Dispatchers.Default) { dispatcher.send() }
        awaitUntil(condition = { h.fastSentTotal(excludeId = 1) == 13 }, describe = { "fast=" + h.fastSentTotal(1) + " dead=" + h.dispatcher.deadChannelsSnapshot() + " confirmed=" + h.dispatcher.confirmedCountSnapshot() })
        gate.complete(Unit)
        job.join()
        sampler.join()

        assertTrue(samples.isNotEmpty())
        var previous = 0L
        for (sample in samples) {
            assertTrue("bytesDone monotonic", sample.bytesDone >= previous)
            previous = sample.bytesDone
            assertTrue(sample.bytesDone <= totalBytes)
            assertTrue(sample.fraction in 0f..1f)
            if (sample.instantBytesPerSec > 0.0 && sample.bytesDone < totalBytes) {
                assertTrue(sample.etaMs >= 0)
            } else {
                assertEquals(-1L, sample.etaMs)
            }
        }
        assertEquals(totalBytes, dispatcher.progress.value.bytesDone)
    }

    @Test
    fun `terminal COMPLETE frame is emitted exactly once under racing full-coverage ACKs`() {
        val emitted = Collections.synchronizedList(ArrayList<ByteArray>())
        val dispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { Buffer().write(payload) },
            factory = StreamChannelFactory { _, _ -> null },
            streamCount = 1,
            requestedChunkSize = chunkSize,
            onCompleteFrame = { emitted.add(it) },
            workerDispatcher = Dispatchers.Default,
            completeGraceMs = 0, // coverage resolves immediately; no channels needed
        )
        val ack = ChunkFrame.serialize(
            ChunkFrame.AckBatch(meta.transferId, meta.fileId, (0 until 19).toList()),
        )
        val threads = (0 until 8).map { _ ->
            Thread {
                repeat(50) { ackRound -> dispatcher.onInboundFrame(ackRound % 3, ack) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("COMPLETE emitted once despite 400 redundant ACKs", 1, emitted.size)
        assertEquals(19, dispatcher.confirmedCountSnapshot())
        val parsed = ChunkFrame.parse(emitted[0])
        assertTrue(parsed is ChunkFrame.Complete)
        assertEquals(meta.transferId, (parsed as ChunkFrame.Complete).transferId)
    }

    @Test
    fun `resume seeding - doneIndexes skipped, progress starts at resumed bytes`() = runBlocking {
        val rxExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val rxDipatcher = rxExecutor.asCoroutineDispatcher()
        val sentIndexes = Collections.synchronizedList(ArrayList<Int>())
        lateinit var dispatcherRef: MultiStreamDispatcher
        val dummyChannels = (0 until 2).map { id ->
            object : StreamChannel {
                override val id: Int = id
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                    val parsed = ChunkFrame.parse(frameBytes)
                    if (parsed is ChunkFrame.Chunk) {
                        sentIndexes.add(parsed.index)
                        // Only ACK this chunk until all 18 pending chunks are sent, then also ACK chunk 0
                        val ackIndexes = if (sentIndexes.size == 18) (0 until 19).toList() else listOf(parsed.index)
                        val ack = ChunkFrame.AckBatch(meta.transferId, meta.fileId, ackIndexes)
                        dispatcherRef.onInboundFrame(id, ChunkFrame.serialize(ack))
                    }
                    return true
                }
            }
        }
        val dispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { Buffer().write(payload) },
            factory = StreamChannelFactory { id, _ -> dummyChannels.firstOrNull { it.id == id } },
            streamCount = 2,
            requestedChunkSize = chunkSize,
            doneIndexes = listOf(0),
            workerDispatcher = rxDipatcher,
            completeGraceMs = 0L,
        ).also { dispatcherRef = it }
        var result: MultiStreamResult? = null
        val job = launch(Dispatchers.Default) { result = dispatcher.send() }
        job.join()

        val completed = result as? MultiStreamResult.Completed
            ?: throw AssertionError("Expected Completed but was: $result; reason=${(result as? MultiStreamResult.Failed)?.reason}")
        assertEquals(18, completed.chunksSent)
        assertEquals(1, completed.chunksSkippedResume)
        assertEquals(chunkSize.toLong(), completed.bytesSkippedResume)
        assertFalse("chunk 0 was sent in sentIndexes=$sentIndexes", sentIndexes.contains(0))
        assertEquals(totalBytes - chunkSize, completed.bytesSent)
        assertEquals(totalBytes, dispatcher.progress.value.bytesDone)
        rxDipatcher.close()
        rxExecutor.shutdownNow()
        Unit
    }

    // ---- cooperative pause (ADR-018) -----------------------------------------------------

    @Test(timeout = 60_000)
    fun `pause before start holds the wire silent, resume delivers the whole file`() = runBlocking {
        val h = Harness()
        val dispatcher = h.build()

        // Pausing BEFORE send() mirrors the Dev Console race the field report hit: the pause is
        // requested while the dispatcher is still being wired up.
        dispatcher.setPaused(true)
        var result: MultiStreamResult? = null
        val job = launch(Dispatchers.Default) { result = dispatcher.send() }

        Thread.sleep(300) // several PAUSE_POLL_MS/WATCH_POLL_MS cycles
        assertTrue("paused dispatcher reports it", dispatcher.isPaused)
        assertEquals(
            "no CHUNK may reach a wire while paused: " +
                h.channels.joinToString { c -> "${c.id}:${c.sentIndexes}" },
            0,
            h.channels.sumOf { it.sentIndexes.size },
        )
        assertEquals(0L, dispatcher.progress.value.bytesDone)
        assertEquals("paused telemetry is a hard zero", 0.0, dispatcher.progress.value.instantBytesPerSec, 0.0)
        assertEquals(-1L, dispatcher.progress.value.etaMs)
        assertTrue(job.isActive)

        dispatcher.setPaused(false)
        job.join()

        assertFalse(dispatcher.isPaused)
        assertTrue("expected Completed got $result", result is MultiStreamResult.Completed)
        assertEquals(19, (result as MultiStreamResult.Completed).chunksSent)
        assertTrue(h.assembler.matches(payload))
        assertEquals(19, h.assembler.writes)
    }

    @Test(timeout = 60_000)
    fun `receiver COMPLETE arriving while paused resolves send instead of parking forever`() =
        runBlocking {
            // Regression: the materializer and every worker polled awaitUnpause() unconditionally,
            // and send() joins all of them — a terminal outcome reached during a pause left the
            // call suspended forever with no way out but cancellation.
            val h = Harness()
            val dispatcher = h.build()
            dispatcher.setPaused(true)

            var result: MultiStreamResult? = null
            val job = launch(Dispatchers.Default) { result = dispatcher.send() }
            Thread.sleep(200)
            assertTrue("still paused and running", job.isActive)

            val complete = ChunkFrame.serialize(ChunkFrame.Complete(meta.transferId, meta.fileId, true))
            assertTrue(dispatcher.onInboundFrame(0, complete))

            job.join() // must return while STILL paused
            assertTrue(dispatcher.isPaused)
            assertTrue("expected Completed got $result", result is MultiStreamResult.Completed)
        }

    @Test(timeout = 60_000)
    fun `paused sender is not failed by the ack-drain grace, only after resume`() = runBlocking {
        // A paused receiver deliberately stops draining and ACKing, so the drain grace must not
        // count while paused — otherwise every pause longer than ACK_DRAIN_GRACE_MS killed the
        // transfer with "ack drain timeout".
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val sent = Collections.synchronizedList(ArrayList<Int>())
        val silentChannel = object : StreamChannel {
            override val id: Int = 0
            override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                (ChunkFrame.parse(frameBytes) as? ChunkFrame.Chunk)?.let { sent.add(it.index) }
                return true // accepts everything, never ACKs
            }
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        liveExecutors.add(executor)
        val dispatcher = MultiStreamDispatcher(
            chunker = Chunker(),
            meta = meta,
            source = ChunkSource { Buffer().write(payload) },
            factory = StreamChannelFactory { _, _ -> silentChannel },
            streamCount = 1,
            requestedChunkSize = chunkSize,
            nowMs = { clock.get() },
            workerDispatcher = executor.asCoroutineDispatcher(),
        )

        var result: MultiStreamResult? = null
        val job = launch(Dispatchers.Default) { result = dispatcher.send() }
        awaitUntil(condition = { sent.size == 19 }, describe = { "sent=" + sent.size })

        dispatcher.setPaused(true)
        // Volatile write lands before the clock jump, so every watcher tick that sees the advanced
        // clock also sees the pause. Repeated jumps prove the grace stays disarmed, not just skewed.
        repeat(10) {
            clock.addAndGet(60_000) // far past ACK_DRAIN_GRACE_MS on the injected clock
            Thread.sleep(30)
        }
        assertTrue("paused transfer must survive the drain grace", job.isActive)
        assertTrue("no terminal outcome while paused, got $result", result == null)

        // Resume: the watcher must re-arm the deadline at the current instant and then expire it.
        // Advancing inside the poll avoids a race where the jump lands before the re-arm tick.
        dispatcher.setPaused(false)
        awaitUntil(
            condition = {
                clock.addAndGet(20_000)
                !job.isActive
            },
            describe = { "resumed transfer should hit the ack drain timeout" },
        )
        job.join()

        val failed = result as? MultiStreamResult.Failed
            ?: throw AssertionError("expected Failed after resume got $result")
        assertTrue("reason=${failed.reason}", failed.reason.contains("ack drain timeout"))
    }

    @Test(timeout = 60_000)
    fun `concurrent sessions - two peers transfer at the same time and both complete`() = runBlocking {
        // Two fully independent harness pairs (own receiver, assembler, executor): proves the
        // dispatcher holds no global state and simultaneous multi-peer transfers interleave safely.
        val hA = Harness()
        val hB = Harness()
        val dispatcherA = hA.build()
        val dispatcherB = hB.build()

        val deferred = listOf(
            async { dispatcherA.send() },
            async { dispatcherB.send() },
        ).awaitAll()

        for ((i, result) in deferred.withIndex()) {
            val h = if (i == 0) hA else hB
            val completed = result as? MultiStreamResult.Completed
                ?: throw AssertionError("session $i expected Completed got $result")
            assertEquals(19, completed.chunksSent)
            assertEquals(totalBytes, completed.bytesSent)
            assertTrue("session $i assembler matches", h.assembler.matches(payload))
            assertEquals("session $i exactly-once writes", 19, h.assembler.writes)
            assertEquals(totalBytes, h.dispatcher.progress.value.bytesDone)
        }
    }
}
