package com.transfer.flash.core.swarm.bench

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OPT-O23-07 measurement (EXP-128): what one 1 MiB piece read/write costs in the piece storage, heap-wrapped NIO
 * (`FileChannel.read(ByteBuffer.wrap(bytes), position)`, which copies through a temporary direct buffer that the JDK
 * allocates per call for buffers above its cache size) versus `RandomAccessFile.seek + read` (one copy into the byte
 * array).
 *
 * The benchmark is desktop-only evidence; Android has its own libcore implementation of both paths.
 */
class SwarmPieceIoBench {

    private val pieceSize = 1024 * 1024
    private val rounds = 64

    @Test
    fun `piece read and write - heap-wrapped NIO versus RandomAccessFile seek`() {
        val dir = Files.createTempDirectory("swarm-io-bench")
        val file = dir.resolve("pieces.bin").toFile()
        val bytes = ByteArray(pieceSize) { (it * 7).toByte() }
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(pieceSize.toLong() * rounds)
                for (i in 0 until rounds) raf.write(bytes)
            }

            // --- reads ---
            val buffer = ByteArray(pieceSize)
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                repeat(10) { readNio(channel, buffer, it) }
                var start = System.nanoTime()
                for (i in 0 until rounds) readNio(channel, buffer, i)
                val nioNs = System.nanoTime() - start

                repeat(10) { readRaf(raf, buffer, it) }
                start = System.nanoTime()
                for (i in 0 until rounds) readRaf(raf, buffer, i)
                val rafNs = System.nanoTime() - start

                // A direct buffer that is reused instead of one the JDK allocates per call, then copied into the heap
                // array the frame needs: one copy instead of the JDK's temporary-buffer dance, but still one copy.
                val direct = java.nio.ByteBuffer.allocateDirect(pieceSize)
                repeat(10) { readDirect(channel, direct, buffer, it) }
                start = System.nanoTime()
                for (i in 0 until rounds) readDirect(channel, direct, buffer, i)
                val directNs = System.nanoTime() - start

                println(
                    "SWARM-IO-BENCH op=read piece_mb=1 rounds=$rounds " +
                        "nio_us=${"%.1f".format(nioNs / 1000.0 / rounds)} " +
                        "raf_us=${"%.1f".format(rafNs / 1000.0 / rounds)} " +
                        "direct_us=${"%.1f".format(directNs / 1000.0 / rounds)} " +
                        "ratio=${"%.2f".format(nioNs.toDouble() / rafNs)}",
                )
            }

            // --- writes ---
            RandomAccessFile(file, "rw").use { raf ->
                val channel = raf.channel
                repeat(10) { writeNio(channel, bytes, it) }
                var start = System.nanoTime()
                for (i in 0 until rounds) writeNio(channel, bytes, i)
                val nioNs = System.nanoTime() - start

                repeat(10) { writeRaf(raf, bytes, it) }
                start = System.nanoTime()
                for (i in 0 until rounds) writeRaf(raf, bytes, i)
                val rafNs = System.nanoTime() - start

                println(
                    "SWARM-IO-BENCH op=write piece_mb=1 rounds=$rounds " +
                        "nio_us=${"%.1f".format(nioNs / 1000.0 / rounds)} " +
                        "raf_us=${"%.1f".format(rafNs / 1000.0 / rounds)} " +
                        "ratio=${"%.2f".format(nioNs.toDouble() / rafNs)}",
                )
                raf.channel.force(false)
            }

            // The bytes must be identical either way; this pins that both paths move the same data.
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val a = ByteArray(pieceSize)
                val b = ByteArray(pieceSize)
                readNio(channel, a, 3)
                readRaf(raf, b, 3)
                assertTrue(a.contentEquals(b))
            }
        } finally {
            file.delete()
            dir.toFile().delete()
        }
    }

    private fun readNio(channel: FileChannel, into: ByteArray, index: Int) {
        val buf = ByteBuffer.wrap(into, 0, into.size)
        var total = 0
        while (buf.hasRemaining()) {
            val read = channel.read(buf, index.toLong() * into.size + total)
            if (read <= 0) break
            total += read
        }
    }

    private fun readRaf(raf: RandomAccessFile, into: ByteArray, index: Int) {
        raf.seek(index.toLong() * into.size)
        var total = 0
        while (total < into.size) {
            val read = raf.read(into, total, into.size - total)
            if (read <= 0) break
            total += read
        }
    }

    private fun readDirect(channel: FileChannel, direct: ByteBuffer, into: ByteArray, index: Int) {
        direct.clear()
        direct.limit(into.size)
        var total = 0
        while (direct.hasRemaining()) {
            val read = channel.read(direct, index.toLong() * into.size + total)
            if (read <= 0) break
            total += read
        }
        direct.flip()
        direct.get(into, 0, total)
    }

    private fun writeNio(channel: FileChannel, bytes: ByteArray, index: Int) {
        val buf = ByteBuffer.wrap(bytes)
        var total = 0
        while (buf.hasRemaining()) {
            val written = channel.write(buf, index.toLong() * bytes.size + total)
            if (written <= 0) break
            total += written
        }
    }

    private fun writeRaf(raf: RandomAccessFile, bytes: ByteArray, index: Int) {
        raf.seek(index.toLong() * bytes.size)
        raf.write(bytes)
    }
}
