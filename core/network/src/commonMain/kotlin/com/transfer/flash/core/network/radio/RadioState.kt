package com.transfer.flash.core.network.radio

/** Outcome of checking a counter against a [ReplayWindow]. */
public enum class ReplayVerdict { FRESH, DUPLICATE, TOO_OLD }

/**
 * Sliding anti-replay window over uint32 counters (the IPsec ESP / RFC 4303 section 3.4.3 scheme, 64 wide). Radio delivers
 * out of order and drops frames, so strictly-increasing would reject honest traffic; a window accepts each counter once and
 * rejects anything older than 64 behind the highest.
 *
 * The caller MUST call [commit] only for frames that authenticated: committing on an unauthenticated counter would let an
 * attacker slide the window forward and lock the honest sender out.
 */
public class ReplayWindow(highest: Long = -1, bitmap: Long = 0) {
    /** Highest accepted counter, or -1 when nothing was accepted yet. */
    public var highest: Long = highest
        private set

    /** Bit i set means counter `highest - i` was accepted. */
    public var bitmap: Long = bitmap
        private set

    /** Classifies [counter] without changing state. */
    public fun check(counter: Long): ReplayVerdict {
        if (counter > highest) return ReplayVerdict.FRESH
        val diff = highest - counter
        if (diff >= WIDTH) return ReplayVerdict.TOO_OLD
        return if ((bitmap ushr diff.toInt()) and 1L == 1L) ReplayVerdict.DUPLICATE else ReplayVerdict.FRESH
    }

    /** Records an authenticated [counter]. */
    public fun commit(counter: Long) {
        if (counter > highest) {
            val shift = counter - highest
            bitmap = if (highest < 0 || shift >= WIDTH) 1L else (bitmap shl shift.toInt()) or 1L
            highest = counter
        } else {
            val diff = highest - counter
            if (diff < WIDTH) bitmap = bitmap or (1L shl diff.toInt())
        }
    }

    /** Window width in counters. */
    public companion object {
        /** 64 counters. */
        public const val WIDTH: Int = 64
    }
}

/** Durable per-peer replay state (survives a restart: a captured frame must not become replayable after one). */
public interface RadioReplayStore {
    /** `highest to bitmap` for [key], or null. */
    public fun load(key: String): Pair<Long, Long>?

    /** Persists the window for [key]. */
    public fun store(key: String, highest: Long, bitmap: Long)
}

/** Durable send-counter reservation (see [RadioSendCounter]). */
public interface RadioCounterStore {
    /** The persisted high-water mark for [key], or null. */
    public fun loadCounter(key: String): Long?

    /** Persists the high-water mark: the lowest counter that has NOT been reserved. */
    public fun storeCounter(key: String, reservedUpTo: Long)
}

/** In-memory store for tests and the diagnostic tool (state is lost with the process, which the time floor then covers). */
public class InMemoryRadioStore : RadioReplayStore, RadioCounterStore {
    private val replay = HashMap<String, Pair<Long, Long>>()
    private val counters = HashMap<String, Long>()

    override fun load(key: String): Pair<Long, Long>? = replay[key]

    override fun store(key: String, highest: Long, bitmap: Long) {
        replay[key] = highest to bitmap
    }

    override fun loadCounter(key: String): Long? = counters[key]

    override fun storeCounter(key: String, reservedUpTo: Long) {
        counters[key] = reservedUpTo
    }
}

/**
 * Issues strictly increasing uint32 send counters that are never reused, even after a crash.
 *
 * The counter is half of the AEAD nonce, so reuse under one key would break GCM. Two defences: (1) reservation: before issuing
 * counter n the store is told that n + [reserve] is reserved, so after a crash the next run starts at the persisted mark and
 * skips at most [reserve] values; (2) a time floor: the first value is at least the seconds since 2026-01-01, so even if the
 * store is lost, a station that sends less than one frame per second on average (a 1200 baud radio cannot) still starts above
 * anything it ever used, and the peer's replay window does not shut it out.
 */
public class RadioSendCounter(
    private val store: RadioCounterStore,
    private val reserve: Int = 64,
    private val floorOf: (nowMs: Long) -> Long = ::secondsSince2026,
) {
    private val next = HashMap<String, Long>()
    private val reservedUpTo = HashMap<String, Long>()

    init {
        require(reserve >= 1) { "reserve must be positive" }
    }

    /** The next unused counter for [key] (one per peer direction). Throws [IllegalStateException] when uint32 is exhausted. */
    public fun next(key: String, nowMs: Long): Long {
        var n = next[key]
        if (n == null) {
            n = maxOf(store.loadCounter(key) ?: 0L, floorOf(nowMs))
            next[key] = n
            reservedUpTo[key] = n // force a reservation below
        }
        check(n <= MAX) { "send counter exhausted for $key: re-key the pairing" }
        if (n >= (reservedUpTo[key] ?: 0L)) {
            val newMark = minOf(n + reserve, MAX + 1)
            store.storeCounter(key, newMark)
            reservedUpTo[key] = newMark
        }
        next[key] = n + 1
        return n
    }

    /** Counter limits and defaults. */
    public companion object {
        /** Largest uint32. */
        public const val MAX: Long = 0xFFFF_FFFFL
    }
}

/** Seconds since 2026-01-01T00:00:00Z (epoch second 1767225600), never negative. */
public fun secondsSince2026(nowMs: Long): Long = maxOf(0L, nowMs / 1000 - 1_767_225_600L)

/**
 * Message-level duplicate suppression: a bounded, time-limited set of keys (typically `origin/counter`). Dedup is separate
 * from the replay window: the window protects one pairwise stream, this protects against the same message arriving over two
 * paths (radio and LAN, or two gateways).
 */
public class DedupStore(private val capacity: Int = 512, private val ttlMs: Long = 60 * 60 * 1000L) {
    private val seen = LinkedHashMap<String, Long>()

    init {
        require(capacity >= 1 && ttlMs >= 1)
    }

    /** True if [key] was already recorded within the TTL; otherwise records it and returns false. */
    public fun seenBefore(key: String, nowMs: Long): Boolean {
        expire(nowMs)
        val at = seen[key]
        if (at != null) return true
        seen[key] = nowMs
        while (seen.size > capacity) seen.remove(seen.keys.first())
        return false
    }

    /** Number of remembered keys. */
    public val size: Int get() = seen.size

    private fun expire(nowMs: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value > ttlMs) it.remove() else break // insertion order is time order
        }
    }
}

/** Result of adding a segment. */
public sealed interface SegmentResult {
    /** More segments are needed. [duplicate] is true when this index was already held. */
    public class Incomplete(public val received: Int, public val total: Int, public val duplicate: Boolean) : SegmentResult

    /** The message is whole. [firstCounter] is the counter of segment 0 (what an ACK names). */
    public class Complete(public val body: ByteArray, public val firstCounter: Long, public val segments: Int) : SegmentResult

    /** The segment is malformed or contradicts earlier segments of the same message; nothing was stored. */
    public class Rejected(public val reason: String) : SegmentResult
}

/**
 * Reassembles segmented messages. Segments are individually authenticated before they get here, so a stranger cannot poison a
 * reassembly; the bounds protect against an enrolled-but-misbehaving sender and against slow memory growth:
 * at most [maxPending] incomplete messages (oldest evicted), at most [maxSegments] per message, entries expire after [timeoutMs].
 */
public class SegmentReassembler(
    private val maxPending: Int = 8,
    private val maxSegments: Int = 16,
    private val timeoutMs: Long = 10 * 60 * 1000L,
) {
    private class Pending(val total: Int, val createdAtMs: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
        var firstCounter: Long = -1
        var count = 0
    }

    private val pending = LinkedHashMap<String, Pending>()

    /** Number of incomplete messages held. */
    public val pendingCount: Int get() = pending.size

    /** Adds one authenticated segment. */
    public fun add(
        peerId: String,
        msgId: Int,
        index: Int,
        total: Int,
        counter: Long,
        chunk: ByteArray,
        nowMs: Long,
    ): SegmentResult {
        expire(nowMs)
        if (total < 2 || total > maxSegments) return SegmentResult.Rejected("total_out_of_range")
        if (index !in 0 until total) return SegmentResult.Rejected("index_out_of_range")
        val key = "$peerId/$msgId"
        var p = pending[key]
        if (p == null) {
            p = Pending(total, nowMs)
            pending[key] = p
            while (pending.size > maxPending) pending.remove(pending.keys.first())
        } else if (p.total != total) {
            return SegmentResult.Rejected("total_mismatch")
        }
        if (p.parts[index] != null) return SegmentResult.Incomplete(p.count, p.total, duplicate = true)
        p.parts[index] = chunk.copyOf()
        p.count++
        if (index == 0) p.firstCounter = counter
        if (p.count < p.total) return SegmentResult.Incomplete(p.count, p.total, duplicate = false)
        pending.remove(key)
        var size = 0
        for (part in p.parts) size += part!!.size
        val out = ByteArray(size)
        var at = 0
        for (part in p.parts) {
            part!!.copyInto(out, at)
            at += part.size
        }
        return SegmentResult.Complete(out, p.firstCounter, p.total)
    }

    private fun expire(nowMs: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value.createdAtMs > timeoutMs) it.remove()
        }
    }
}
