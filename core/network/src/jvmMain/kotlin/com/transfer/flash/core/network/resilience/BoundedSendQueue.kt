package com.transfer.flash.core.network.resilience

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.withLock

/**
 * DESKTOP DUPLICATE (Phase 15-3) of the `androidMain` original - byte-identical apart from
 * this note. D1 = Option B forbids a shared JVM tier, so JDK-bound plumbing is duplicated
 * per target (CONVENTIONS.md R5 amendment). Do NOT edit one copy without the other.
 */

/**
 * Overflow decision for [BoundedSendQueue.enqueue].
 */
internal sealed interface EnqueueResult<out T> {
    data class Enqueued<T>(val sizeAfterEnqueue: Int) : EnqueueResult<T>
    data class Rejected(val reason: RejectReason) : EnqueueResult<Nothing>
}

/**
 * Why an item was rejected. Callers MUST treat rejection as a typed error and
 * keep their own copy of the write (plan upgrade 5: "overflow -> typed error,
 * outbox retains the write (C6 owns retry)").
 */
internal enum class RejectReason {
    /** Queue at capacity - nothing was evicted, nothing lost silently. */
    QueueFull,

    /** Queue closed for draining; no further accepts. */
    Closed,
}

/**
 * Per-peer bounded send queue (plan C4.5 / upgrade 5).
 *
 * Capacity 64 (documented choice): Little's-Law sizing for chat traffic -
 * at a typical interactive drain rate this absorbs multi-hundred-message
 * bursts (attachment fan-out to one peer) while bounding worst-case queue
 * latency and per-peer memory. It is deliberately NOT sized for sustained
 * overload: sustained producer>consumer means the session is dying anyway
 * and the outbox must retain writes.
 *
 * Backpressure mode: **reject-newest (fail fast)** - chosen over
 * drop-oldest and block:
 * - Not drop-oldest: Flash frames are user messages / transfer chunks where
 *   every item matters (research: "drop when newer data invalidates older" -
 *   live video/sensors - does not apply). Silent eviction would corrupt FIFO
 *   ordering guarantees C6 relies on for revision/receipt replay.
 * - Not block, by default: a blocked producer holds the caller's coroutine on a
 *   dead peer's queue - deadlock-prone under exactly the failure conditions
 *   (half-open socket) this class exists to survive. Research: fail-fast is
 *   correct when "the caller has a fallback" - here the fallback is the
 *   durable outbox owned by C6.
 *
 * [enqueueBlocking] is the bounded exception, for the one caller with NO
 * fallback: the WebSocket write path, where a rejected frame is read as
 * *permanent death of that transfer stream* (`StreamChannel` contract) and
 * throws away every un-ACKed chunk on it. A writer that is simply behind must
 * not be mistaken for a dead peer (integrator review, 2026-10-31). It waits at
 * most the caller's own bound and still reports QueueFull once that expires.
 *
 * Thread-safe: multiple producers, single logical consumer (drain loop).
 */
internal class BoundedSendQueue<T>(
    val capacity: Int = DEFAULT_CAPACITY,
    val priorityCapacity: Int = DEFAULT_PRIORITY_CAPACITY,
) {

    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(priorityCapacity > 0) { "priorityCapacity must be > 0" }
    }

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    /** Signalled whenever an item leaves the queue, waking producers parked in [enqueueBlocking]. */
    private val notFull = lock.newCondition()

    private val priorityBuffer = ArrayDeque<T>(priorityCapacity)
    private val buffer = ArrayDeque<T>(capacity)

    @Volatile
    private var closed: Boolean = false

    val size: Int get() = lock.withLock { priorityBuffer.size + buffer.size }
    val isClosed: Boolean get() = closed

    /**
     * Attempts to append [item] to the normal FIFO queue. Never blocks, never drops existing items;
     * returns [EnqueueResult.Rejected] when full or closed.
     */
    fun enqueue(item: T): EnqueueResult<T> = lock.withLock {
        if (closed) return@withLock EnqueueResult.Rejected(RejectReason.Closed)
        if (buffer.size >= capacity) return@withLock EnqueueResult.Rejected(RejectReason.QueueFull)
        buffer.addLast(item)
        notEmpty.signalAll()
        EnqueueResult.Enqueued(priorityBuffer.size + buffer.size)
    }

    /**
     * Attempts to append [item] to the high-priority queue (drained before normal items).
     * Never blocks, never drops existing items; returns [EnqueueResult.Rejected] when full or closed.
     */
    fun enqueuePriority(item: T): EnqueueResult<T> = lock.withLock {
        if (closed) return@withLock EnqueueResult.Rejected(RejectReason.Closed)
        if (priorityBuffer.size >= priorityCapacity) return@withLock EnqueueResult.Rejected(RejectReason.QueueFull)
        priorityBuffer.addLast(item)
        notEmpty.signalAll()
        EnqueueResult.Enqueued(priorityBuffer.size + buffer.size)
    }

    /**
     * Non-blocking poll; drains priority queue first, then normal queue.
     * Null when empty or closed-and-drained.
     */
    fun poll(): T? = lock.withLock {
        val item = priorityBuffer.pollFirst() ?: buffer.pollFirst()
        if (item != null) {
            notEmpty.signalAll() // wake any close-waiters' producers
            notFull.signalAll() // wake producers waiting for room ([enqueueBlocking])
        }
        item
    }

    /**
     * Appends [item] to the normal FIFO queue, or to the high-priority queue when [priority] is
     * set, waiting up to [timeoutMs] for the drain loop to make room first.
     *
     * The caller this exists for is the WebSocket write path: it has no fallback for a rejected
     * frame, because the transfer dispatcher reads a `false` write as *permanent death of this
     * channel* and returns its un-ACKed chunks to the shared pool ([EnqueueResult] KDoc). Waiting
     * is also strictly less blocking than the code it replaced - the pre-queue write path took the
     * write lock and wrote the frame itself, with no bound at all.
     *
     * Returns [EnqueueResult.Enqueued], or [Rejected] with [RejectReason.QueueFull] once
     * [timeoutMs] elapsed without room (or [RejectReason.Closed] when the queue closed while
     * waiting).
     */
    fun enqueueBlocking(item: T, timeoutMs: Long, priority: Boolean = false): EnqueueResult<T> {
        // Explicit lock/unlock rather than `withLock`: this body returns from inside an infinite
        // loop, which an inline lambda's return-type inference cannot express as EnqueueResult<T>.
        lock.lock()
        try {
            var remainingNanos = timeoutMs * 1_000_000
            while (true) {
                if (closed) return EnqueueResult.Rejected(RejectReason.Closed)
                val full = if (priority) priorityBuffer.size >= priorityCapacity else buffer.size >= capacity
                if (!full) {
                    if (priority) priorityBuffer.addLast(item) else buffer.addLast(item)
                    notEmpty.signalAll()
                    return EnqueueResult.Enqueued(priorityBuffer.size + buffer.size)
                }
                if (remainingNanos <= 0) return EnqueueResult.Rejected(RejectReason.QueueFull)
                remainingNanos = notFull.awaitNanos(remainingNanos)
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Blocking take with a real-time timeout in ms (JVM Condition await).
     * Returns null on timeout or once closed and drained. Drains priority queue first.
     */
    fun takeOrNull(timeoutMs: Long): T? = lock.withLock {
        var remainingNanos = timeoutMs * 1_000_000
        while (priorityBuffer.isEmpty() && buffer.isEmpty()) {
            if (closed) return@withLock null
            if (remainingNanos <= 0) return@withLock null
            remainingNanos = notEmpty.awaitNanos(remainingNanos)
        }
        val taken = priorityBuffer.pollFirst() ?: buffer.pollFirst()
        if (taken != null) notFull.signalAll()
        taken
    }

    /**
     * Drains every currently-available item into [sink], in FIFO order (priority items first).
     * Returns the number of items drained. Items enqueued concurrently
     * DURING the drain may or may not be included - callers wanting exact
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
                ?: return lock.withLock { priorityBuffer.isEmpty() && buffer.isEmpty() }
            consumer(item)
            if (deadlineNanos - System.nanoTime() <= 0) return lock.withLock { priorityBuffer.isEmpty() && buffer.isEmpty() }
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
            // Wake producers parked in [enqueueBlocking] too: without this they sleep out the rest
            // of their bound and then report [RejectReason.QueueFull], when the truthful answer is
            // [RejectReason.Closed] and they could have had it at once (integrator review,
            // 2026-10-31). Matters on the close path of a socket that has senders mid-offer.
            notFull.signalAll()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 64
        const val DEFAULT_PRIORITY_CAPACITY: Int = 32
    }
}
