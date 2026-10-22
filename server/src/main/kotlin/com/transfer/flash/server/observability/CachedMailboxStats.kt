package com.transfer.flash.server.observability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [MailboxStats] whose database-backed numbers (queue depth, queued bytes, the oldest waiting ages) come from a short-lived
 * snapshot (OPS-01, ADR-222). A metrics scrape, `/health/detail` or the console overview therefore never takes a database
 * connection on the caller's thread: the first snapshot is taken when this object is built (hub start-up), and a read of a
 * stale one returns the old numbers at once while ONE refresh runs on [scope]. A refresh that fails keeps the last numbers and
 * is tried again on a later read. The `*Total` counters are in memory already and pass straight through.
 *
 * The numbers can be up to about [ttlMs] old (plus the refresh time); for a dashboard that is the point.
 */
class CachedMailboxStats(
    private val delegate: MailboxStats,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MailboxStats by delegate {

    private class Snapshot(val takenAt: Long, val count: Long, val bytes: Long, val oldest: Map<String, Long>)

    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var snapshot: Snapshot = load() ?: Snapshot(takenAt = Long.MIN_VALUE / 2, count = 0, bytes = 0, oldest = emptyMap())

    private fun load(): Snapshot? = try {
        Snapshot(clock(), delegate.queuedCount(), delegate.queuedBytes(), delegate.oldestAgesMs(MAX_CACHED_AGES))
    } catch (_: Exception) {
        null
    }

    private fun current(): Snapshot {
        val s = snapshot
        if (clock() - s.takenAt >= ttlMs && refreshing.compareAndSet(false, true)) {
            scope.launch {
                try {
                    load()?.let { snapshot = it }
                } finally {
                    refreshing.set(false)
                }
            }
        }
        return s
    }

    override fun queuedCount(): Long = current().count

    override fun queuedBytes(): Long = current().bytes

    override fun oldestAgesMs(limit: Int): Map<String, Long> {
        val s = current()
        val drift = (clock() - s.takenAt).coerceIn(0L, ttlMs * 4)
        val out = LinkedHashMap<String, Long>()
        for ((id, age) in s.oldest) {
            if (out.size >= limit.coerceAtLeast(1)) break
            out[id] = age + drift
        }
        return out
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 5_000L

        /** How many of the oldest waiting devices a snapshot keeps (the console shows the first). */
        const val MAX_CACHED_AGES: Int = 50
    }
}
