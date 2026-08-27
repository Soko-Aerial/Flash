package com.transfer.flash.core.network.resilience

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Overflow decision for [BoundedSendQueue.enqueue].
 */
internal sealed interface EnqueueResult<out T> {
    data class Enqueued<T>(val sizeAfterEnqueue: Int) : EnqueueResult<T>
    data class Rejected(val reason: RejectReason) : EnqueueResult<Nothing>
}

/**
 * Why an item was rejected. Callers MUST treat rejection as a typed error and
 * keep their own copy of the write (plan upgrade 5: "overflow ⇒ typed error,
 * outbox retains the write (C6 owns retry)").
 */
internal enum class RejectReason {
    /** Queue at capacity — nothing was evicted, nothing lost silently. */
    QueueFull,

    /** Queue closed for draining; no further accepts. */
    Closed,
}

/**
 * Per-peer bounded send queue (plan C4.5 / upgrade 5).
 *
 * Capacity 64 (documented choice): Little's-Law sizing for chat traffic —
 * at a typical interactive drain rate this absorbs multi-hundred-message
 * bursts (attachment fan-out to one peer) while bounding worst-case queue
 * latency and per-peer memory. It is deliberately NOT sized for sustained
 * overload: sustained producer>consumer means the session is dying anyway
 * and the outbox must retain writes.
 *
 * Backpressure mode: **reject-newest (fail fast)** — chosen over
 * drop-oldest and block:
 * - Not drop-oldest: Flash frames are user messages / transfer chunks where
 *   every item matters (research: "drop when newer data invalidates older" —
 *   live video/sensors — does not apply). Silent eviction would corrupt FIFO
 *   ordering guarantees C6 relies on for revision/receipt replay.
 * - Not block: a blocked producer holds the caller's coroutine on a dead
 *   peer's queue — deadlock-prone under exactly the failure conditions
 *   (half-open socket) this class exists to survive. Research: fail-fast is
 *   correct when "the caller has a fallback" — here the fallback is the
 *   durable outbox owned by C6.
 *
 * Thread-safe: multiple producers, single logical consumer (drain loop).
 */
internal class BoundedSendQueue<T>(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val buffer = ArrayDeque<T>(capacity)

    @Volatile
    private var closed: Boolean = false

    val size: Int get() = lock.withLock { buffer.size }
    val isClosed: Boolean get() = closed

    /**
     * Attempts to append [item]. Never blocks, never drops existing items;
     * returns [EnqueueResult.Rejected] when full or closed.
     */
    fun enqueue(item: T): EnqueueResult<T> = lock.withLock {
        if (closed) return@withLock EnqueueResult.Rejected(RejectReason.Closed)
        if (buffer.size >= capacity) return@withLock EnqueueResult.Rejected(RejectReason.QueueFull)
        buffer.addLast(item)
        notEmpty.signalAll()
        EnqueueResult.Enqueued(buffer.size)
    }

    /**
     * Non-blocking poll; null when empty or closed-and-drained.
     */
    fun poll(): T? = lock.withLock {
        val item = buffer.pollFirst()
        if (item != null) notEmpty.signalAll() // wake any close-waiters' producers
        item
    }

    /**
     * Blocking take with a real-time timeout in ms (JVM Condition await).
     * Returns null on timeout or once closed and drained. The timeout uses
     * wall-clock nanos rather than injected time because this is the only
     * blocking surface; pure logic paths use [poll]/[drainInto] instead and
     * stay deterministic.
     */
    fun takeOrNull(timeoutMs: Long): T? = lock.withLock {
        var remainingNanos = timeoutMs * 1_000_000
        while (buffer.isEmpty()) {
            if (closed) return@withLock null
            if (remainingNanos <= 0) return@withLock null
            remainingNanos = notEmpty.awaitNanos(remainingNanos)
        }
        buffer.pollFirst()
    }

    /**
     * Drains every currently-available item into [sink], in FIFO order.
     * Returns the number of items drained. Items enqueued concurrently
     * DURING the drain may or may not be included — callers wanting exact
     * batch semantics should quiesce producers first.
     */
    fun drainInto(sink: (T) -> Unit): Int {
        var count = 0
        while (true) {
            val item = poll() ?: break
            sink(item)
            count++
        }
        return count
    }

    /**
     * Blocks until the queue is fully drained by [consumer] or [timeoutMs]
     * elapses. Returns true when the queue reached empty. Used by drain-loop
     * owners that must guarantee flush-before-disconnect.
     */
    fun awaitDrained(timeoutMs: Long, consumer: (T) -> Unit): Boolean {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            val item = takeOrNull(if (remainingMs == 0L && deadlineNanos > System.nanoTime()) 1 else remainingMs)
                ?: return lock.withLock { buffer.isEmpty() }
            consumer(item)
            if (deadlineNanos - System.nanoTime() <= 0) return lock.withLock { buffer.isEmpty() }
        }
    }

    /**
     * Closes the queue: further enqueues are rejected with
     * [RejectReason.Closed]; already-buffered items remain drainable.
     */
    fun close() {
        lock.withLock {
            closed = true
            notEmpty.signalAll()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 64
    }
}
