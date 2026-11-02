package com.transfer.flash.core.radio.chat

/**
 * Outbound radio texts whose cryptographic ACK has not arrived yet, bounded in size and age (OPT-O26-03 / ADR-331).
 *
 * A radio ACK is a normal frame on a lossy channel, so it is routinely lost in the field; the old unbounded map then
 * kept one entry per such text for the life of the process. The table is now a small insertion-ordered map with a hard
 * cap and a TTL, applied on insert so no timer is needed.
 *
 * **Why this cannot lose a delivery.** An evicted entry only means a later ACK is not matched. The outbox's own retry
 * then re-sends the identical encoded frame (ERROR-869, same counters), the receiver sees it as a duplicate and
 * acknowledges it again without storing it twice (ERROR-870). So eviction costs at most one retransmission and a
 * slightly longer "waiting for acknowledgement" line - never the message.
 *
 * One coroutine at a time: the caller holds `pendingMutex`.
 */
internal class PendingAckTable(
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMs: Long = TTL_MS,
) {
    /** One message waiting for its ACK. */
    internal class PendingOutbound(
        val messageId: String,
        val recipientId: String,
        /** When the message was submitted, for the round-trip figure in the `radio.rx.ack` probe. */
        val sentAtMs: Long,
        /** When this entry was recorded, for the TTL (a retry carries the original [sentAtMs]). */
        val recordedAtMs: Long,
    )

    private val entries = LinkedHashMap<Long, PendingOutbound>()

    /** How many messages are waiting for an ACK. */
    val size: Int get() = entries.size

    /** Records that the text sent as [counter] is waiting for its ACK; forgets expired entries and the oldest first. */
    fun put(counter: Long, messageId: String, recipientId: String, sentAtMs: Long, nowMs: Long) {
        expire(nowMs)
        // An update keeps the original insertion position; a re-send of the same counter is a new entry with a new
        // TTL, so remove first and re-insert at the end.
        entries.remove(counter)
        entries[counter] = PendingOutbound(messageId, recipientId, sentAtMs, nowMs)
        // `removeEldestEntry` is the obvious tool here, but an anonymous LinkedHashMap subclass trips a Kotlin 2.x
        // K2 backend bug ("No override for IR_EXTERNAL_JAVA_DECLARATION_STUB name:get"), so the cap is applied here.
        while (entries.size > maxEntries) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }

    /** Removes and returns the entry named by an ACK, or null when it is unknown or older than the TTL. */
    fun remove(counter: Long, nowMs: Long): PendingOutbound? {
        expire(nowMs)
        return entries.remove(counter)
    }

    /** The counters currently waiting, oldest first (tests and diagnostics). */
    fun counters(): List<Long> = entries.keys.toList()

    private fun expire(nowMs: Long) {
        if (entries.isEmpty()) return
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value.recordedAtMs < ttlMs) break // insertion order: the rest are newer
            it.remove()
        }
    }

    companion object {
        /**
         * Entries kept at most. Each holds two short strings and two numbers (well under 200 bytes), so this caps the
         * table at tens of kilobytes; a station cannot need more than a handful in flight on a 1200-baud channel.
         */
        const val MAX_ENTRIES: Int = 256

        /**
         * How long an entry may wait. The outbox gives up on a radio send after 30 minutes, so an older entry can no
         * longer produce a delivery the user is still waiting for.
         */
        const val TTL_MS: Long = 30L * 60L * 1000L
    }
}
