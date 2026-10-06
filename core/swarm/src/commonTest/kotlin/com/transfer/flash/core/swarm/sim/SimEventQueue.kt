package com.transfer.flash.core.swarm.sim

/**
 * Priority queue for deterministic event dispatch ordered by virtual time and sequence ID (SW-5).
 */
class SimEventQueue(private val clock: SimClock) {
    private var nextSeq: Long = 0L

    class ScheduledItem(
        val timeMs: Long,
        val seq: Long,
        val action: () -> Unit,
    ) : Comparable<ScheduledItem> {
        override fun compareTo(other: ScheduledItem): Int {
            val c = timeMs.compareTo(other.timeMs)
            return if (c != 0) c else seq.compareTo(other.seq)
        }
    }

    private val heap = mutableListOf<ScheduledItem>()

    val size: Int get() = heap.size
    val isEmpty: Boolean get() = heap.isEmpty()

    fun schedule(delayMs: Long, action: () -> Unit) {
        require(delayMs >= 0L) { "Delay cannot be negative: $delayMs" }
        val targetTime = clock.nowMs + delayMs
        val item = ScheduledItem(targetTime, nextSeq++, action)
        push(item)
    }

    fun scheduleAt(timeMs: Long, action: () -> Unit) {
        val targetTime = timeMs.coerceAtLeast(clock.nowMs)
        val item = ScheduledItem(targetTime, nextSeq++, action)
        push(item)
    }

    fun step(): Boolean {
        if (heap.isEmpty()) return false
        val item = pop() ?: return false
        if (item.timeMs > clock.nowMs) {
            clock.nowMs = item.timeMs
        }
        item.action()
        return true
    }

    fun runUntil(maxVirtualTimeMs: Long, predicate: () -> Boolean): Boolean {
        while (!isEmpty && clock.nowMs <= maxVirtualTimeMs) {
            if (predicate()) return true
            step()
            if (predicate()) return true
        }
        return predicate()
    }

    private fun push(item: ScheduledItem) {
        heap.add(item)
        siftUp(heap.size - 1)
    }

    private fun pop(): ScheduledItem? {
        if (heap.isEmpty()) return null
        val top = heap[0]
        val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) {
            heap[0] = last
            siftDown(0)
        }
        return top
    }

    private fun siftUp(index: Int) {
        var curr = index
        while (curr > 0) {
            val parent = (curr - 1) / 2
            if (heap[curr] < heap[parent]) {
                val tmp = heap[curr]
                heap[curr] = heap[parent]
                heap[parent] = tmp
                curr = parent
            } else {
                break
            }
        }
    }

    private fun siftDown(index: Int) {
        var curr = index
        val size = heap.size
        while (true) {
            val left = 2 * curr + 1
            val right = 2 * curr + 2
            var smallest = curr
            if (left < size && heap[left] < heap[smallest]) {
                smallest = left
            }
            if (right < size && heap[right] < heap[smallest]) {
                smallest = right
            }
            if (smallest != curr) {
                val tmp = heap[curr]
                heap[curr] = heap[smallest]
                heap[smallest] = tmp
                curr = smallest
            } else {
                break
            }
        }
    }
}
