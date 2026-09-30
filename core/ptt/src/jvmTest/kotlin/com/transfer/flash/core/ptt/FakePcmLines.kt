package com.transfer.flash.core.ptt

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/** No hardware: [PttPcmLines] that hand out [FakePcmInput] / [FakePcmOutput] for the rates in [supportedRates]. */
internal class FakePcmLines(private val supportedRates: Set<Int> = setOf(16_000, 8_000)) : PttPcmLines {
    val inputRatesTried = Recorder<Int>()
    val inputs = Recorder<FakePcmInput>()
    val outputs = Recorder<FakePcmOutput>()

    override fun openInput(rateHz: Int, bufferBytes: Int): PttPcmInput? {
        inputRatesTried.add(rateHz)
        if (rateHz !in supportedRates) return null
        return FakePcmInput().also { inputs.add(it) }
    }

    override fun openOutput(rateHz: Int, bufferBytes: Int): PttPcmOutput? {
        if (rateHz !in supportedRates) return null
        return FakePcmOutput().also { outputs.add(it) }
    }
}

/**
 * A microphone: [feed] queues bytes, [read] hands them out and otherwise returns 0 after a short wait,
 * like a device that delivers nothing. [failReads] makes the next read fail the way a dead device does.
 */
internal class FakePcmInput : PttPcmInput {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var carry = ByteArray(0)

    @Volatile
    var stopped: Boolean = false

    @Volatile
    var closed: Boolean = false

    @Volatile
    var failReads: Boolean = false

    @Volatile
    var consumedBytes: Int = 0
        private set

    fun feed(bytes: ByteArray) = queue.put(bytes)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (failReads) return -1
        if (carry.isEmpty()) carry = queue.poll(READ_WAIT_MS, TimeUnit.MILLISECONDS) ?: return 0
        val n = minOf(length, carry.size)
        carry.copyInto(buffer, offset, 0, n)
        carry = carry.copyOfRange(n, carry.size)
        consumedBytes += n
        return n
    }

    override fun stop() {
        stopped = true
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val READ_WAIT_MS = 10L
    }
}

/**
 * A speaker: records every write. Each write takes ~1 ms so the playout loop is paced the way a full
 * line buffer paces it, instead of spinning. [failAfterWrites] makes writes fail once that many passed.
 */
internal class FakePcmOutput(private val failAfterWrites: Int = Int.MAX_VALUE) : PttPcmOutput {
    val writes = Recorder<ByteArray>()

    @Volatile
    var flushed: Boolean = false

    @Volatile
    var stopped: Boolean = false

    @Volatile
    var closed: Boolean = false

    override fun write(buffer: ByteArray, offset: Int, length: Int): Int {
        if (writes.size >= failAfterWrites) return -1
        writes.add(buffer.copyOfRange(offset, offset + length))
        Thread.sleep(1)
        return length
    }

    override fun flush() {
        flushed = true
    }

    override fun stop() {
        stopped = true
    }

    override fun close() {
        closed = true
    }
}
