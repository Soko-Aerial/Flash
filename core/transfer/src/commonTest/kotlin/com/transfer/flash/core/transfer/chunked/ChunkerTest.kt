package com.transfer.flash.core.transfer.chunked

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Moved to `commonTest` in Phase 13B-3e together with `Chunker.kt` itself. Three conversions beyond
 * the mechanical import swap:
 *  * JUnit's `assertThrows` with a `::class.java` literal became `assertFailsWith<X> { }`, since
 *    `::class.java` is JVM-only.
 *  * `assertTrue(message, condition)` → `assertTrue(condition, message)` — `kotlin.test` puts the
 *    message LAST where `org.junit.Assert` puts it first.
 *  * the redundant `import java.util.NoSuchElementException` is gone; the name resolves to
 *    `kotlin.NoSuchElementException`, which is what `ChunkStream.next()` now throws in common code.
 *
 * The `.use { }` blocks needed no change and are the reason [ChunkStream]'s supertype had to become
 * `kotlin.AutoCloseable` rather than being dropped: `kotlin.io.use` is `java.io.Closeable`-only.
 */
class ChunkerTest {

    private val chunker = Chunker()

    @Test
    fun `plan computes totalChunks and clamps requested size`() {
        val meta = meta(100_000)
        val plan = chunker.plan(meta, 16_384)
        assertEquals(16_384, plan.chunkSize)
        assertEquals(7, plan.totalChunks) // 6 full + 1_696-byte remainder

        val clamped = chunker.plan(meta, 1) // below min -> min
        assertEquals(Chunker.MIN_CHUNK_SIZE_BYTES, clamped.chunkSize)
        val capped = chunker.plan(meta, Int.MAX_VALUE) // above max -> max
        assertEquals(Chunker.MAX_CHUNK_SIZE_BYTES, capped.chunkSize)
    }

    @Test
    fun `exact multiple yields full-size last chunk`() {
        val bytes = ByteArray(3 * 16_384) { (it % 253).toByte() }
        val frames = collectFrames(bytes, 16_384)
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.data.size == 16_384 })
    }

    @Test
    fun `partial last chunk carries only the remainder`() {
        val bytes = ByteArray(2 * 16_384 + 999) { (it % 199).toByte() }
        val frames = collectFrames(bytes, 16_384)
        assertEquals(3, frames.size)
        assertEquals(16_384, frames[0].data.size)
        assertEquals(16_384, frames[1].data.size)
        assertEquals(999, frames[2].data.size)
        assertTrue(frames[2].data.contentEquals(bytes.copyOfRange(2 * 16_384, bytes.size)))
    }

    @Test
    fun `per-chunk hashes match data and indexes ascend from zero`() {
        val bytes = ByteArray(50_000) { (it * 31).toByte() }
        val frames = collectFrames(bytes, 16_384)
        assertEquals(listOf(0, 1, 2, 3), frames.map { it.index })
        frames.forEach { f ->
            assertTrue(
                Sha256.rawEqualsConstantTime(Sha256.digest(f.data), f.chunkSha256),
            )
        }
    }

    @Test
    fun `hashOnly matches one-shot digest and expect-hash guard accepts matching pass`() {
        val bytes = ByteArray(123_457) { (it % 241).toByte() }
        val source = sourceOf(bytes)
        val hex = chunker.hashOnly(source)
        assertEquals(Sha256.digestHex(bytes), hex)

        val meta = meta(bytes.size.toLong())
        val plan = chunker.plan(meta, 16_384)
        chunker.openChunkStream(source, meta, plan, expectFileSha256Hex = hex).use { stream ->
            while (stream.hasNext()) stream.next()
        }
    }

    @Test
    fun `expect-hash guard rejects sources that changed between passes`() {
        val bytes = ByteArray(40_000) { it.toByte() }
        val meta = meta(bytes.size.toLong())
        val plan = chunker.plan(meta, 16_384)
        val stream = chunker.openChunkStream(
            sourceOf(bytes),
            meta,
            plan,
            expectFileSha256Hex = Sha256.digestHex("different".encodeToByteArray()),
        )
        assertFailsWith<IllegalStateException> {
            while (stream.hasNext()) stream.next()
        }
        stream.close()
    }

    @Test
    fun `short source is rejected instead of silently truncating`() {
        val declared = 100_000L
        val actual = ByteArray(10_000)
        val meta = meta(declared)
        val plan = chunker.plan(meta, 16_384)
        val stream = chunker.openChunkStream(sourceOf(actual), meta, plan)
        assertFailsWith<IllegalStateException> {
            while (stream.hasNext()) stream.next()
        }
        stream.close()
    }

    @Test
    fun `exhausted iterator throws NoSuchElementException on extra next`() {
        val bytes = ByteArray(16_384)
        val meta = meta(bytes.size.toLong())
        val plan = chunker.plan(meta, 16_384)
        chunker.openChunkStream(sourceOf(bytes), meta, plan).use { stream ->
            assertTrue(stream.hasNext())
            stream.next()
            assertTrue(!stream.hasNext())
            assertFailsWith<NoSuchElementException> { stream.next() }
        }
    }

    @Test
    fun `adaptiveSize respects bounds anchors monotonicity and granularity`() {
        assertEquals(Chunker.MIN_CHUNK_SIZE_BYTES, Chunker.adaptiveSize(-5.0))
        assertEquals(Chunker.MIN_CHUNK_SIZE_BYTES, Chunker.adaptiveSize(Double.NaN))
        assertEquals(Chunker.MIN_CHUNK_SIZE_BYTES, Chunker.adaptiveSize(0.0))
        assertEquals(Chunker.MIN_CHUNK_SIZE_BYTES, Chunker.adaptiveSize(256.0 * 1024))
        assertEquals(Chunker.MAX_CHUNK_SIZE_BYTES, Chunker.adaptiveSize(64.0 * 1024 * 1024))
        assertEquals(Chunker.MAX_CHUNK_SIZE_BYTES, Chunker.adaptiveSize(1024.0 * 1024 * 1024))

        var previous = -1
        var throughput = 1.0
        while (throughput <= 512.0 * 1024 * 1024) {
            val size = Chunker.adaptiveSize(throughput)
            assertTrue(size in Chunker.MIN_CHUNK_SIZE_BYTES..Chunker.MAX_CHUNK_SIZE_BYTES)
            assertEquals(0, size % Chunker.SIZE_GRANULARITY_BYTES)
            assertTrue(size >= previous, "non-monotonic at $throughput")
            previous = size
            throughput *= 1.5
        }
    }

    @Test
    fun `totalChunks math and overflow guard`() {
        assertEquals(1, Chunker.totalChunks(1, 16_384))
        assertEquals(1, Chunker.totalChunks(16_384, 16_384))
        assertEquals(2, Chunker.totalChunks(16_385, 16_384))
        assertFailsWith<IllegalArgumentException> { Chunker.totalChunks(0, 16_384) }
        assertFailsWith<IllegalArgumentException> {
            Chunker.totalChunks(Long.MAX_VALUE, Chunker.MIN_CHUNK_SIZE_BYTES)
        }
    }

    private fun meta(totalBytes: Long) = FileMeta("t", "f", "file.bin", totalBytes)

    private fun sourceOf(bytes: ByteArray) = ChunkSource { Buffer().write(bytes) }

    private fun collectFrames(data: ByteArray, chunkSize: Int): List<ChunkFrame.Chunk> {
        val meta = meta(data.size.toLong())
        val plan = chunker.plan(meta, chunkSize)
        chunker.openChunkStream(sourceOf(data), meta, plan).use { stream ->
            return generateSequence { if (stream.hasNext()) stream.next() else null }.toList()
        }
    }
}
