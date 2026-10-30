package com.transfer.flash.core.transfer

import com.transfer.flash.core.transfer.chunked.*
import com.transfer.flash.core.transfer.multistream.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.source
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue

class TransferLoopbackBenchmarkTest {

    private val mbean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    // Deterministic synthetic stream generator that generates 200 MB without keeping 200 MB in RAM
    private class SyntheticInputStream(private val totalBytes: Long) : InputStream() {
        private var pos = 0L
        override fun read(): Int {
            if (pos >= totalBytes) return -1
            val b = ((pos * 31L) and 0xFF).toInt()
            pos++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= totalBytes) return -1
            val toRead = minOf(len.toLong(), totalBytes - pos).toInt()
            for (i in 0 until toRead) {
                b[off + i] = (((pos + i) * 31L) and 0xFF).toByte()
            }
            pos += toRead
            return toRead
        }
    }

    private class MemorySink(val totalChunks: Int) : ChunkSink {
        var bytesWritten = 0L
        var chunkCount = 0
        override fun write(index: Int, data: ByteArray) {
            bytesWritten += data.size
            chunkCount++
        }
    }

    private fun runPipelineBenchmark(
        totalBytes: Long,
        chunkSize: Int,
        streamCount: Int,
        label: String
    ): BenchmarkResult {
        // Force GC before measurement
        System.gc()
        Thread.sleep(100)

        val chunker = Chunker()
        val meta = FileMeta("bench-transfer", "bench-file", "benchmark.dat", totalBytes)
        val plan = chunker.plan(meta, chunkSize)

        val wire = Channel<ByteArray>(Channel.UNLIMITED)
        val ackWire = Channel<ByteArray>(Channel.UNLIMITED)

        val sink = MemorySink(plan.totalChunks)
        val receiver = ReceivePipeline(sink = sink, ackEvery = 32)

        val source = ChunkSource {
            SyntheticInputStream(totalBytes).source()
        }

        val startAlloc = mbean.getThreadAllocatedBytes(Thread.currentThread().id)
        val startCpu = mbean.getCurrentThreadCpuTime()
        val startWall = System.nanoTime()

        runBlocking {
            // Receiver coroutine
            val receiverJob = launch {
                while (true) {
                    val frameBytes = wire.receive()
                    val events = receiver.onFrame(frameBytes)
                    for (event in events) {
                        when (event) {
                            is ReceiveEvent.AckBatchReady -> {
                                ackWire.send(ChunkFrame.serialize(event.frame))
                            }
                            is ReceiveEvent.Completed -> {
                                ackWire.send(ChunkFrame.serialize(event.frame))
                                return@launch
                            }
                            is ReceiveEvent.Rejected -> {
                                error("Received rejection: $event")
                            }
                            else -> {}
                        }
                    }
                }
            }

            // Sender
            var senderRef: SendPipeline? = null
            val sender = SendPipeline(chunker) { frameBytes ->
                wire.send(frameBytes)
                // Drain any available ACKs
                var ack = ackWire.tryReceive().getOrNull()
                while (ack != null) {
                    senderRef?.onFrame(ack)
                    ack = ackWire.tryReceive().getOrNull()
                }
                true
            }
            senderRef = sender

            // Compute whole file hash once
            val digest = chunker.hashOnly(source)
            val sendResult = sender.send(
                meta = meta,
                source = source,
                requestedChunkSize = chunkSize,
                fileSha256Hex = digest
            )
            assertTrue(sendResult is SendResult.Completed)

            receiverJob.join()
        }

        val endWall = System.nanoTime()
        val endCpu = mbean.getCurrentThreadCpuTime()
        val endAlloc = mbean.getThreadAllocatedBytes(Thread.currentThread().id)

        val wallMs = (endWall - startWall) / 1_000_000.0
        val cpuMs = (endCpu - startCpu) / 1_000_000.0
        val allocBytes = endAlloc - startAlloc
        val mb = totalBytes / (1024.0 * 1024.0)
        val mbPerSec = (mb / (wallMs / 1000.0))

        return BenchmarkResult(label, totalBytes, chunkSize, streamCount, wallMs, cpuMs, allocBytes, mbPerSec)
    }

    data class BenchmarkResult(
        val label: String,
        val totalBytes: Long,
        val chunkSize: Int,
        val streams: Int,
        val wallMs: Double,
        val cpuMs: Double,
        val allocBytes: Long,
        val mbPerSec: Double
    )

    @Test
    fun benchmark200MbTransfer() {
        println("=== Flash Transfer Pipeline Benchmark ===")
        println("OS: ${System.getProperty("os.name")}, Arch: ${System.getProperty("os.arch")}, Java: ${System.getProperty("java.version")}")
        val cpus = Runtime.getRuntime().availableProcessors()
        println("Available Processors: $cpus")

        // 1. Warm-up run with 20 MB
        println("\nWarming up JIT with 20 MB...")
        runPipelineBenchmark(20 * 1024 * 1024L, 64 * 1024, 1, "Warmup")
        println("Warm-up complete.")

        // 2. Measure 200 MB with varying chunk sizes
        val totalBytes = 200 * 1024 * 1024L // 200 MB
        val chunkSizes = listOf(64 * 1024, 256 * 1024)

        val results = mutableListOf<BenchmarkResult>()

        for (chunkSize in chunkSizes) {
            val label = "200MB_Chunk${chunkSize / 1024}KB"
            println("\nRunning 3 iterations for $label...")
            val iterResults = mutableListOf<BenchmarkResult>()
            for (i in 1..3) {
                val res = runPipelineBenchmark(totalBytes, chunkSize, 1, "$label-run$i")
                println("  Run $i: ${String.format("%.1f", res.mbPerSec)} MB/s, Wall: ${String.format("%.1f", res.wallMs)} ms, CPU: ${String.format("%.1f", res.cpuMs)} ms, Alloc: ${res.allocBytes / (1024 * 1024)} MB")
                iterResults.add(res)
            }
            // Sort by throughput and pick median
            iterResults.sortBy { it.mbPerSec }
            val median = iterResults[1]
            results.add(median)
        }

        // 3. Measure SHA-256 microbenchmark: Okio HashingSink vs Direct MessageDigest
        println("\n=== SHA-256 Microbenchmark (200 MB, 64 KB blocks) ===")
        val block = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        val blocks = (200 * 1024) / 64 // 3200 blocks

        // Test A: Current Sha256.digest (Okio HashingSink + JCA lookup per block)
        System.gc()
        val allocA1 = mbean.getThreadAllocatedBytes(Thread.currentThread().id)
        val tA1 = System.nanoTime()
        for (i in 0 until blocks) {
            Sha256.digest(block)
        }
        val tA2 = System.nanoTime()
        val allocA2 = mbean.getThreadAllocatedBytes(Thread.currentThread().id)
        val timeA = (tA2 - tA1) / 1_000_000.0
        val allocA = (allocA2 - allocA1) / (1024.0 * 1024.0)

        // Test B: Reusable MessageDigest (Direct in-place)
        System.gc()
        val allocB1 = mbean.getThreadAllocatedBytes(Thread.currentThread().id)
        val tB1 = System.nanoTime()
        val md = MessageDigest.getInstance("SHA-256")
        val outDigest = ByteArray(32)
        for (i in 0 until blocks) {
            md.update(block)
            md.digest(outDigest, 0, 32)
        }
        val tB2 = System.nanoTime()
        val allocB2 = mbean.getThreadAllocatedBytes(Thread.currentThread().id)
        val timeB = (tB2 - tB1) / 1_000_000.0
        val allocB = (allocB2 - allocB1) / (1024.0 * 1024.0)

        println("Sha256.digest (current Okio HashingSink): ${String.format("%.1f", timeA)} ms, Alloc: ${String.format("%.1f", allocA)} MB, Rate: ${String.format("%.1f", 200.0 / (timeA / 1000.0))} MB/s")
        println("MessageDigest.reset (reusable in-place):  ${String.format("%.1f", timeB)} ms, Alloc: ${String.format("%.2f", allocB)} MB, Rate: ${String.format("%.1f", 200.0 / (timeB / 1000.0))} MB/s")
        val speedup = (timeA / timeB)
        println("Direct MessageDigest Speedup: ${String.format("%.2f", speedup)}x, Alloc reduction: ${String.format("%.1f", allocA - allocB)} MB")

        println("\n=== Final Benchmark Summary ===")
        for (r in results) {
            println(String.format("%-20s: %6.1f MB/s | Wall: %7.1f ms | CPU: %7.1f ms | Heap Alloc: %6d MB",
                r.label, r.mbPerSec, r.wallMs, r.cpuMs, r.allocBytes / (1024 * 1024)))
        }
    }
}
