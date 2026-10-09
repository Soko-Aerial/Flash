package com.transfer.flash.core.messaging.protocol

/**
 * How far back a group lets a member who was not there catch up (ADR-100, owner decision 2026-10-08).
 *
 * A signed group setting (`historyCeiling`, ADR-074 mechanism): changed by an admin, enforced by every holder when it
 * answers a catch-up request. [NONE] means no earlier messages and no files. [ALL] is "everything the holders still
 * keep"; it is not a promise of more than the holders retain.
 */
public enum class GroupHistoryCeiling(public val windowMs: Long) {
    NONE(0L),
    H24(24L * HOUR_MS),
    D7(7L * 24L * HOUR_MS),
    D30(30L * 24L * HOUR_MS),
    ALL(Long.MAX_VALUE),
    ;

    public companion object {
        /** The default of a group nobody has changed (owner decision: new members get 30 days). */
        public val DEFAULT: GroupHistoryCeiling = D30

        /** Null for a name this build does not know, so an unknown value is refused rather than guessed. */
        public fun fromName(name: String?): GroupHistoryCeiling? =
            entries.firstOrNull { it.name == name?.trim()?.uppercase() }
    }
}

private const val HOUR_MS: Long = 60L * 60L * 1000L

/** What a new member (or the "Load older messages" action) asks for: a messages window and whether file offers come too. */
public data class GroupHistoryChoice(
    /** 0 = no earlier messages. Always inside the ceiling once [GroupHistoryPolicy.clamp] has seen it. */
    val messageWindowMs: Long,
    val includeFiles: Boolean,
)

/** The windows a holder serves one request with. A row is served when it is inside the window of its own kind. */
public data class GroupHistoryWindows(val textMs: Long, val fileMs: Long) {
    public val servesNothing: Boolean get() = textMs <= 0L && fileMs <= 0L
}

/**
 * The pure rules of group history sync (ADR-100): window math, clamping to the ceiling and where a request starts.
 * No clock, no storage; the repository supplies both. Unit-tested in `GroupHistoryPolicyTest`.
 */
public object GroupHistoryPolicy {
    /**
     * File offers reach back as far as the swarm keeps the content, 7 days (`SwarmConfig.retentionMs`). Owner
     * instruction 2026-10-09: files are kept and offered for 7 days; the swarm retention is not raised (O1).
     */
    public const val FILE_WINDOW_MS: Long = GroupPolicy.SWARM_OFFER_SYNC_TTL_MS

    /** A returning member is guaranteed this much of what it missed, up to the ceiling (decision 3, O2). */
    public const val RETURNING_FLOOR_MS: Long = 7L * 24L * 60L * 60L * 1000L

    /** The window of a request that carries none, i.e. an older build: today's fixed 24 hours of text. */
    public const val LEGACY_TEXT_WINDOW_MS: Long = GroupPolicy.SYNC_TTL_MS

    /** Rows one catch-up page carries (and one banner step): the existing per-round read budget. */
    public const val PAGE_SIZE: Int = GroupPolicy.MAX_PENDING_SYNC_MESSAGES

    /** The most rows a holder counts past a page to report how many remain; a larger history reports "at least". */
    public const val MAX_REMAINING_COUNT: Int = 2_000

    /** `group_history_state.cardState` values. */
    public const val STATE_PENDING: String = "PENDING"
    public const val STATE_DECIDED: String = "DECIDED"
    public const val STATE_SKIPPED: String = "SKIPPED"

    /** Pages one chain of continuation requests may take from a holder (100 pages of 100 rows = 10 000 messages). */
    public const val MAX_CONTINUATION_PAGES: Int = 100

    /** The choice a new member starts on: 30 days of messages and the files, both inside the ceiling. */
    public fun defaultChoice(ceiling: GroupHistoryCeiling): GroupHistoryChoice =
        clamp(GroupHistoryChoice(GroupHistoryCeiling.D30.windowMs, includeFiles = true), ceiling)

    /** [choice] cut down to [ceiling]: a window above it shrinks to it, and nothing at all comes from a [GroupHistoryCeiling.NONE] group. */
    public fun clamp(choice: GroupHistoryChoice, ceiling: GroupHistoryCeiling): GroupHistoryChoice =
        GroupHistoryChoice(
            messageWindowMs = choice.messageWindowMs.coerceIn(0L, ceiling.windowMs),
            includeFiles = choice.includeFiles && ceiling != GroupHistoryCeiling.NONE,
        )

    /** The message windows the card may offer under [ceiling], smallest first; 0 (none) is always among them. */
    public fun messageOptions(ceiling: GroupHistoryCeiling): List<Long> =
        (listOf(0L) + GroupHistoryCeiling.entries.filter { it != GroupHistoryCeiling.NONE }.map { it.windowMs })
            .filter { it <= ceiling.windowMs }
            .distinct()

    /**
     * The windows a holder serves a request with. [requestedWindowMs] is null for an older requester, which gets
     * today's fixed text window and the file window, but never more than the ceiling: holders enforce the setting
     * whatever the requester believes it may ask for.
     */
    public fun holderWindows(
        ceiling: GroupHistoryCeiling,
        requestedWindowMs: Long?,
        includeFiles: Boolean?,
    ): GroupHistoryWindows {
        val limit = ceiling.windowMs
        val text = (requestedWindowMs ?: LEGACY_TEXT_WINDOW_MS).coerceIn(0L, limit)
        // An older requester states no window and gets today's 7-day file offers; a requester that does state one gets
        // files only inside it (a "24 hours" choice must not bring a 5-day-old file offer), never beyond the 7 days.
        val files = when {
            includeFiles == false -> 0L
            requestedWindowMs == null -> FILE_WINDOW_MS.coerceAtMost(limit)
            else -> minOf(FILE_WINDOW_MS, text)
        }
        return GroupHistoryWindows(textMs = text, fileMs = files)
    }

    /**
     * The window of a returning member (decision 3, O2): the time it was away, never less than the 7-day floor and never
     * more than the ceiling. An unknown last contact counts as no gap, so the floor applies.
     */
    public fun returningWindowMs(ceiling: GroupHistoryCeiling, lastContactAtMs: Long?, nowMs: Long): Long {
        val away = if (lastContactAtMs == null || lastContactAtMs <= 0L) 0L else (nowMs - lastContactAtMs).coerceAtLeast(0L)
        return maxOf(RETURNING_FLOOR_MS, away).coerceAtMost(ceiling.windowMs)
    }

    /** What this device remembers of one group's history decision (the stored `group_history_state` row, minus ids). */
    public data class RequestState(
        val pending: Boolean,
        val chosenWindowMs: Long,
        val includeFiles: Boolean,
        val decidedAtMs: Long,
        val lastContactAtMs: Long,
    )

    /**
     * The window and file choice a catch-up request carries, or null when none must be sent. [state] null is a group that
     * predates the feature (a returning member by definition).
     *
     * - A member that has not chosen yet asks for nothing (the join card is still up).
     * - Before any holder has served it, a member asks for what it chose plus the time since it chose.
     * - After that it is a returning member: [returningWindowMs], but never reaching further back than what it asked to
     *   see (a member that chose "none" gets what happened since, not the history before it joined).
     * - Always inside the ceiling; a request that would carry neither messages nor files is not sent.
     */
    public fun requestFor(ceiling: GroupHistoryCeiling, state: RequestState?, nowMs: Long): GroupHistoryChoice? {
        if (state == null) {
            val window = returningWindowMs(ceiling, null, nowMs)
            return GroupHistoryChoice(window, includeFiles = true).takeIf { window > 0L }
        }
        if (state.pending) return null
        val sinceDecision = (nowMs - state.decidedAtMs).coerceAtLeast(0L)
        val reachBack = if (state.chosenWindowMs > Long.MAX_VALUE - sinceDecision) Long.MAX_VALUE else state.chosenWindowMs + sinceDecision
        val wanted = if (state.lastContactAtMs <= 0L) reachBack else minOf(reachBack, returningWindowMs(ceiling, state.lastContactAtMs, nowMs))
        val choice = clamp(GroupHistoryChoice(wanted, state.includeFiles), ceiling)
        return choice.takeIf { it.messageWindowMs > 0L }
    }

    /** `nowMs - windowMs` without overflow; a window of "everything" starts at 0. */
    public fun floorMs(nowMs: Long, windowMs: Long): Long =
        if (windowMs >= nowMs) 0L else nowMs - windowMs

    /**
     * Where a request starts: after the contiguous watermark of the asked holder when there is one (defect S1: the
     * newest local row says nothing about older rows missed), else after the newest local row (an older holder, or a
     * group with no watermark yet: today's behaviour), else the beginning; and never earlier than the window allows.
     */
    public fun requestStart(
        watermark: GroupSyncCursor?,
        newestLocal: GroupSyncCursor?,
        nowMs: Long,
        windowMs: Long,
    ): GroupSyncCursor {
        val base = watermark ?: newestLocal ?: GroupSyncCursor(0L, "")
        val floor = GroupSyncCursor(floorMs(nowMs, windowMs), "")
        return maxOf(base, floor)
    }

    /** Whether a holder serves a row of this age and kind under [windows]. */
    public fun inWindow(sentAt: Long, isFile: Boolean, nowMs: Long, windows: GroupHistoryWindows): Boolean {
        val window = if (isFile) windows.fileMs else windows.textMs
        return window > 0L && sentAt >= floorMs(nowMs, window)
    }

    /**
     * The watermark after a page: it moves only when the whole page was received (every pushed row seen, even one
     * refused as unsigned or a duplicate), and never backwards. A page that lost a frame leaves it where it was, so the
     * next session asks again from there (resume).
     */
    public fun advanceWatermark(
        current: GroupSyncCursor?,
        pageEnd: GroupSyncCursor?,
        pushedCount: Int,
        seenCount: Int,
    ): GroupSyncCursor? {
        if (pageEnd == null || seenCount < pushedCount) return current
        return if (current == null || pageEnd > current) pageEnd else current
    }

    /** The "of about N" the banner shows: what has landed plus what the holder says remains after this page. */
    public fun expectedTotal(received: Int, remaining: Int): Int = received + remaining.coerceAtLeast(0)
}

/**
 * Who may change the signed history ceiling (ADR-100, owner decision "any admin"). There is one seam on purpose: today
 * an admin is the group owner or a member holding an `admin` certificate (ADR-063; both can already sign GM-9
 * settings), so a later co-admin or enterprise-delegation rule changes this one function and nothing else.
 */
public object GroupAdminPolicy {
    /** One roster row as the policy reads it: the device, its certified role and whether the row is active. */
    public data class Member(val deviceId: String, val role: String, val isActive: Boolean)

    /** True when [memberId] is an active owner or an active admin of the roster ([ownerId] names the charter's owner). */
    public fun isAdmin(memberId: String, ownerId: String?, roster: Collection<Member>): Boolean {
        val row = roster.firstOrNull { it.deviceId == memberId } ?: return false
        if (!row.isActive) return false
        return memberId == ownerId || row.role == MemberCert.ROLE_ADMIN
    }

    /** The ceiling is a group rule, so it follows the same admin rule as every other signed setting. */
    public fun canChangeHistoryCeiling(memberId: String, ownerId: String?, roster: Collection<Member>): Boolean =
        isAdmin(memberId, ownerId, roster)
}
