package com.transfer.flash.core.persistence.retention

/**
 * A single prunable record (message row, transfer row, ...) described only by
 * what the retention policy needs. Kept free of Room/DAO types so the policy
 * stays a pure Kotlin decision function; the DB-backed adapter lives behind
 * [PrunableSource] and is wired in C6/C7 once the DAO layer (concurrently
 * developed under `.../db/`) and the WorkManager scheduling decision land.
 *
 * @property localId stable primary key of the underlying row.
 * @property createdAt epoch milliseconds of the record creation timestamp
 *   used for age computation.
 * @property protected true when the entry must never be deleted regardless of
 *   age (plan C1.6: pinned conversations are respected through this flag —
 *   the caller maps "belongs to a pinned conversation" to protected=true).
 */
public data class PrunableEntry(
    val localId: String,
    val createdAt: Long,
    val protected: Boolean,
)

/**
 * Pure retention policy for core-upgrade-plan step C1.6: periodic pruning of
 * messages/transfers older than the configured [FlashSettingsDataStore]
 * retention window, while sparing protected entries.
 *
 * ## Cutoff math
 *
 * `cutoff = nowMs - retentionDays * 86_400_000`
 *
 * An entry is eligible when BOTH hold:
 * 1. `entry.createdAt < cutoff` (strictly older than the cutoff), and
 * 2. `!entry.protected`.
 *
 * Strict inequality means an entry created exactly at the cutoff instant is
 * SPARED: an entry must be strictly older than `retentionDays` days to be
 * pruned. With whole-day granularity this yields the intuitive behaviour that
 * a record survives its entire retention day and is pruned only after it is
 * fully exceeded. Callers re-evaluating on later sweeps will prune it then;
 * nothing is lost by keeping boundary entries one sweep longer.
 *
 * Multiplication uses Long arithmetic (`retentionDays.toLong() * MILLIS_PER_DAY`)
 * so large day counts cannot overflow Int.
 *
 * ## Disabled mode
 *
 * `retentionDays <= 0` DISABLES pruning entirely: [cutoffMsOrNull] returns
 * null and [eligibleForDeletion] returns an empty list without evaluating any
 * entries. Rationale: zero or negative windows would otherwise mean "prune
 * everything immediately", which is destructive and surprising as a default;
 * the product meaning of `retentionDays = 0` (also the persisted default is
 * 365, but users may set 0) is "keep history forever". If a "delete all"
 * feature is ever wanted it must be an explicit user action, not a side
 * effect of the retention setting.
 */
public object RetentionPolicy {

    public const val MILLIS_PER_DAY: Long = 86_400_000L

    /**
     * The deletion cutoff in epoch millis, or null when retention is disabled
     * ([retentionDays] <= 0). Entries with createdAt strictly before this
     * cutoff (and not protected) are eligible for deletion.
     */
    public fun cutoffMsOrNull(nowMs: Long, retentionDays: Int): Long? {
        if (retentionDays <= 0) return null
        return nowMs - retentionDays.toLong() * MILLIS_PER_DAY
    }

    /**
     * Returns the localIds of entries eligible for deletion per the rules in
     * the class KDoc. Order of results follows the input order.
     */
    public fun eligibleForDeletion(
        nowMs: Long,
        retentionDays: Int,
        entries: List<PrunableEntry>,
    ): List<String> {
        val cutoff = cutoffMsOrNull(nowMs, retentionDays) ?: return emptyList()
        return entries.asSequence()
            .filter { !it.protected && it.createdAt < cutoff }
            .map { it.localId }
            .toList()
    }
}

/**
 * Read/delete seam the future DB-backed pruner worker will implement
 * (C6/C7 hook — intentionally DAO-free here so this module's retention API
 * can be finalized before the concurrently-developed db package exists).
 *
 * An implementation will typically:
 * - back [entriesOlderThan] with a keyset query like
 *   `SELECT ... WHERE sentAt < :cutoffMs` over MessageDao / TransferDao rows,
 *   projecting each into a [PrunableEntry] with protected=true when the row
 *   belongs to a pinned conversation (C1.6 requirement);
 * - implement [delete] as a single transactional batch delete, idempotent
 *   under re-delivery of the same id list (at-least-once execution by the
 *   scheduler must not be harmful).
 */
public interface PrunableSource {
    /** All unprotected-and-protected rows older than [cutoffMs], protection resolved by the implementation. */
    public suspend fun entriesOlderThan(cutoffMs: Long): List<PrunableEntry>

    /** Deletes the rows identified by [ids]; unknown ids are ignored. */
    public suspend fun delete(ids: List<String>)
}
