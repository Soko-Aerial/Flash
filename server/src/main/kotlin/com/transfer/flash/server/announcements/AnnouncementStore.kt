package com.transfer.flash.server.announcements

import com.transfer.flash.core.hubprotocol.rest.AnnouncementFeedDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementLimitsDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementPreviewResponseDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementSearchResponseDto
import com.transfer.flash.server.storage.Migration
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

public class AnnouncementConflictException(
    public val currentItem: AnnouncementItemDto,
    message: String = "Announcement was modified concurrently",
) : IllegalStateException(message)

public class AnnouncementPinLimitException(
    message: String = "You can pin up to 3 announcements here. Unpin one first.",
) : IllegalStateException(message)

public class AnnouncementNotFoundException(
    message: String = "Announcement not found",
) : NoSuchElementException(message)

public class AnnouncementForbiddenException(
    message: String = "You are not authorized to perform this announcement action",
) : SecurityException(message)

/**
 * Persistence, changes feed, FTS5 search, and concurrency control for announcements (Plan A3 §3, ADR-138).
 */
public class AnnouncementStore(
    private val db: ServerDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(AnnouncementStore::class.java)

    public val limits: AnnouncementLimitsDto = AnnouncementLimitsDto(
        maxTitleChars = AnnouncementText.MAX_TITLE_CODE_POINTS,
        maxBodyChars = AnnouncementText.MAX_BODY_CODE_POINTS,
        maxPinsPerScope = MAX_PINS_PER_SCOPE,
    )

    init {
        initEpochAndSeqIfMissing()
    }

    private fun initEpochAndSeqIfMissing() {
        db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT OR IGNORE INTO announcement_state(key, value) VALUES ('epoch', '${UUID.randomUUID()}')"
                )
                st.execute(
                    "INSERT OR IGNORE INTO announcement_state(key, value) VALUES ('seq', '0')"
                )
            }
        }
    }

    public fun currentEpoch(): String = db.withConnection { conn ->
        readState(conn, "epoch") ?: UUID.randomUUID().toString()
    }

    public val epoch: String get() = currentEpoch()

    public fun currentSeq(): Long = db.withConnection { conn ->
        readState(conn, "seq")?.toLongOrNull() ?: 0L
    }

    public val seq: Long get() = currentSeq()

    // ---------------------------------------------------------------------------------------------
    // Writes (Create, Update, Delete, Pin/Unpin)
    // ---------------------------------------------------------------------------------------------

    public fun createAnnouncement(
        clientRequestId: String = UUID.randomUUID().toString(),
        scope: String, // "org" | "department"
        departmentId: String?,
        title: String,
        body: String,
        authorKind: String, // "device" | "console"
        authorRef: String,
        authorName: String,
        authorRole: String, // "admin" | "dept_admin"
        pinned: Boolean = false,
        expiresAtMs: Long? = null,
    ): AnnouncementItemDto {
        require(scope == "org" || scope == "department") { "Invalid announcement scope: $scope" }
        if (scope == "department") {
            require(!departmentId.isNullOrBlank()) { "Department scope requires departmentId" }
        }

        // Clean text first (throws ValidationException if invalid)
        val cleaned = AnnouncementText.clean(title, body)

        return db.withConnection { conn ->
            // 1. Idempotency check: check if clientRequestId was already processed within last 10 min
            pruneOldRequests(conn)
            val existingId = findByClientRequestId(conn, clientRequestId)
            if (existingId != null) {
                val existing = getByIdInternal(conn, existingId)
                if (existing != null) return@withConnection existing
            }

            val now = clock()

            // 2. Pin limit check
            if (pinned) {
                checkPinLimit(conn, scope, departmentId, excludeId = null, nowMs = now)
            }

            // 3. Increment seq monotonically
            val newSeq = nextSeq(conn)
            val id = UUID.randomUUID().toString()

            val sql = """
                INSERT INTO announcement (
                    id, scope, department_id, title, body,
                    author_kind, author_ref, author_name, author_role,
                    created_at, updated_at, edited, pinned, pinned_at,
                    expires_at, deleted_at, seq, kind, flags
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, NULL, ?, 'text', 0)
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.setString(2, scope)
                stmt.setString(3, if (scope == "department") departmentId else null)
                stmt.setString(4, cleaned.title)
                stmt.setString(5, cleaned.body)
                stmt.setString(6, authorKind)
                stmt.setString(7, authorRef)
                stmt.setString(8, authorName)
                stmt.setString(9, authorRole)
                stmt.setLong(10, now)
                stmt.setLong(11, now)
                stmt.setInt(12, if (pinned) 1 else 0)
                if (pinned) stmt.setLong(13, now) else stmt.setNull(13, java.sql.Types.INTEGER)
                if (expiresAtMs != null) stmt.setLong(14, expiresAtMs) else stmt.setNull(14, java.sql.Types.INTEGER)
                stmt.setLong(15, newSeq)
                stmt.executeUpdate()
            }

            // Record clientRequestId for idempotency
            conn.prepareStatement("INSERT OR REPLACE INTO announcement_request(client_request_id, announcement_id, created_at) VALUES (?, ?, ?)").use { stmt ->
                stmt.setString(1, clientRequestId)
                stmt.setString(2, id)
                stmt.setLong(3, now)
                stmt.executeUpdate()
            }

            AnnouncementItemDto(
                id = id,
                scope = scope,
                departmentId = if (scope == "department") departmentId else null,
                title = cleaned.title,
                body = cleaned.body,
                authorKind = authorKind,
                authorRef = authorRef,
                authorName = authorName,
                authorRole = authorRole,
                createdAt = now,
                updatedAt = now,
                edited = false,
                pinned = pinned,
                pinnedAt = if (pinned) now else null,
                expiresAt = expiresAtMs,
                deleted = false,
                seq = newSeq,
            )
        }
    }

    public fun updateAnnouncement(
        id: String,
        expectedSeq: Long? = null,
        title: String? = null,
        body: String? = null,
        pinned: Boolean? = null,
        expiresAtMs: Long? = null,
        actorRole: String, // "admin" | "dept_admin" | "ORG_ADMIN" | "DEPT_HEAD"
        actorDeptId: String? = null,
        actorRef: String = "",
        clientRequestId: String? = null,
    ): AnnouncementItemDto {
        return db.withConnection { conn ->
            val current = getByIdInternal(conn, id)
                ?: throw AnnouncementNotFoundException("Announcement $id does not exist")
            if (current.deleted) {
                throw AnnouncementNotFoundException("Announcement $id was removed")
            }

            // Optimistic concurrency check
            if (expectedSeq != null && current.seq != expectedSeq) {
                throw AnnouncementConflictException(current)
            }

            // Authorization: org admin can edit any; dept head can edit only in own department authored by a head
            checkCanModify(current, actorRole, actorDeptId, actorRef)

            val cleanedTitle = if (title != null) AnnouncementText.cleanTitle(title) else current.title
            val cleanedBody = if (body != null) AnnouncementText.cleanBody(body) else current.body

            val now = clock()
            val newPinned = pinned ?: current.pinned
            if (newPinned && !current.pinned) {
                checkPinLimit(conn, current.scope, current.departmentId, excludeId = id, nowMs = now)
            }

            val newSeq = nextSeq(conn)
            val newPinnedAt = if (newPinned) (current.pinnedAt ?: now) else null

            val sql = """
                UPDATE announcement SET
                    title = ?,
                    body = ?,
                    pinned = ?,
                    pinned_at = ?,
                    expires_at = ?,
                    updated_at = ?,
                    edited = 1,
                    seq = ?
                WHERE id = ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, cleanedTitle)
                stmt.setString(2, cleanedBody)
                stmt.setInt(3, if (newPinned) 1 else 0)
                if (newPinnedAt != null) stmt.setLong(4, newPinnedAt) else stmt.setNull(4, java.sql.Types.INTEGER)
                if (expiresAtMs != null) stmt.setLong(5, expiresAtMs) else stmt.setNull(5, java.sql.Types.INTEGER)
                stmt.setLong(6, now)
                stmt.setLong(7, newSeq)
                stmt.setString(8, id)
                stmt.executeUpdate()
            }

            current.copy(
                title = cleanedTitle,
                body = cleanedBody,
                pinned = newPinned,
                pinnedAt = newPinnedAt,
                expiresAt = expiresAtMs,
                updatedAt = now,
                edited = true,
                seq = newSeq,
            )
        }
    }

    public fun deleteAnnouncement(
        id: String,
        actorRole: String,
        actorDeptId: String? = null,
        actorRef: String = "",
    ): AnnouncementItemDto {
        return db.withConnection { conn ->
            val current = getByIdInternal(conn, id)
                ?: throw AnnouncementNotFoundException("Announcement $id does not exist")
            if (current.deleted) {
                throw AnnouncementNotFoundException("Announcement $id was already removed")
            }

            checkCanModify(current, actorRole, actorDeptId, actorRef)

            val now = clock()
            val newSeq = nextSeq(conn)

            // Tombstone: wipe title/body, set deleted_at and new seq
            val sql = """
                UPDATE announcement SET
                    title = '',
                    body = '',
                    deleted_at = ?,
                    pinned = 0,
                    pinned_at = NULL,
                    updated_at = ?,
                    seq = ?
                WHERE id = ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, now)
                stmt.setLong(2, now)
                stmt.setLong(3, newSeq)
                stmt.setString(4, id)
                stmt.executeUpdate()
            }

            current.copy(
                title = "",
                body = "",
                deleted = true,
                pinned = false,
                pinnedAt = null,
                updatedAt = now,
                seq = newSeq,
            )
        }
    }

    public fun setPinned(
        id: String,
        pinned: Boolean,
        actorRole: String,
        actorDeptId: String? = null,
        actorRef: String = "",
    ): AnnouncementItemDto {
        return db.withConnection { conn ->
            val current = getByIdInternal(conn, id)
                ?: throw AnnouncementNotFoundException("Announcement $id does not exist")
            if (current.deleted) {
                throw AnnouncementNotFoundException("Announcement $id was removed")
            }

            checkCanModify(current, actorRole, actorDeptId, actorRef)

            if (current.pinned == pinned) return@withConnection current

            val now = clock()
            if (pinned) {
                checkPinLimit(conn, current.scope, current.departmentId, excludeId = id, nowMs = now)
            }

            val newSeq = nextSeq(conn)
            val newPinnedAt = if (pinned) now else null

            conn.prepareStatement("UPDATE announcement SET pinned = ?, pinned_at = ?, seq = ?, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setInt(1, if (pinned) 1 else 0)
                if (newPinnedAt != null) stmt.setLong(2, newPinnedAt) else stmt.setNull(2, java.sql.Types.INTEGER)
                stmt.setLong(3, newSeq)
                stmt.setLong(4, now)
                stmt.setString(5, id)
                stmt.executeUpdate()
            }

            current.copy(
                pinned = pinned,
                pinnedAt = newPinnedAt,
                seq = newSeq,
                updatedAt = now,
            )
        }
    }

    public fun pinAnnouncement(
        id: String,
        actorRole: String,
        actorDeptId: String? = null,
        actorRef: String = "",
    ): AnnouncementItemDto = setPinned(id, true, actorRole, actorDeptId, actorRef)

    public fun unpinAnnouncement(
        id: String,
        actorRole: String,
        actorDeptId: String? = null,
        actorRef: String = "",
    ): AnnouncementItemDto = setPinned(id, false, actorRole, actorDeptId, actorRef)

    // ---------------------------------------------------------------------------------------------
    // Reads (Get, Changes Feed, Search)
    // ---------------------------------------------------------------------------------------------

    public fun getAnnouncement(
        id: String,
        callerDeptId: String?,
        isOrgAdmin: Boolean,
        nowMs: Long = clock(),
    ): AnnouncementItemDto? = db.withConnection { conn ->
        val item = getByIdInternal(conn, id) ?: return@withConnection null
        if (item.deleted) return@withConnection null

        // Check visibility
        if (!isOrgAdmin && item.scope == "department" && item.departmentId != callerDeptId) {
            return@withConnection null // Do not leak existence to outsiders
        }

        // Check expiration for non-admins
        val exp = item.expiresAt
        if (!isOrgAdmin && exp != null && exp <= nowMs) {
            return@withConnection null
        }

        item
    }

    public fun getChangesFeed(
        sinceSeq: Long,
        limit: Int = 100,
        callerDeptId: String?,
        isOrgAdmin: Boolean,
        nowMs: Long = clock(),
    ): AnnouncementFeedDto = db.withConnection { conn ->
        val safeLimit = limit.coerceIn(1, 200)
        val epoch = readState(conn, "epoch") ?: UUID.randomUUID().toString()
        val highestSeq = readState(conn, "seq")?.toLongOrNull() ?: 0L

        if (sinceSeq == 0L) {
            // Initial sync: newest 200 non-deleted, non-expired items (pinned first, then created_at DESC)
            val sql = if (isOrgAdmin) {
                """
                SELECT * FROM announcement
                WHERE deleted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY pinned DESC, created_at DESC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT * FROM announcement
                WHERE deleted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
                  AND (scope = 'org' OR (scope = 'department' AND department_id = ?))
                ORDER BY pinned DESC, created_at DESC
                LIMIT ?
                """.trimIndent()
            }

            val items = ArrayList<AnnouncementItemDto>()
            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setLong(idx++, nowMs)
                if (!isOrgAdmin) {
                    stmt.setString(idx++, callerDeptId ?: "")
                }
                stmt.setInt(idx, safeLimit)

                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        items.add(readItem(rs))
                    }
                }
            }

            return@withConnection AnnouncementFeedDto(
                epoch = epoch,
                seq = highestSeq,
                fullResync = true,
                items = items,
                nextCursor = null,
            )
        }

        // Delta sync: items with seq > sinceSeq (including tombstones)
        val sql = if (isOrgAdmin) {
            """
            SELECT * FROM announcement
            WHERE seq > ?
            ORDER BY seq ASC
            LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT * FROM announcement
            WHERE seq > ?
              AND (scope = 'org' OR (scope = 'department' AND department_id = ?))
            ORDER BY seq ASC
            LIMIT ?
            """.trimIndent()
        }

        val items = ArrayList<AnnouncementItemDto>()
        conn.prepareStatement(sql).use { stmt ->
            var idx = 1
            stmt.setLong(idx++, sinceSeq)
            if (!isOrgAdmin) {
                stmt.setString(idx++, callerDeptId ?: "")
            }
            stmt.setInt(idx, safeLimit)

            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    items.add(readItem(rs))
                }
            }
        }

        val nextCursor = if (items.size == safeLimit) items.last().seq.toString() else null

        AnnouncementFeedDto(
            epoch = epoch,
            seq = highestSeq,
            fullResync = false,
            items = items,
            nextCursor = nextCursor,
        )
    }

    public fun searchAnnouncements(
        query: String?,
        scope: String?,
        departmentId: String?,
        before: Long?,
        limit: Int = 50,
        callerDeptId: String?,
        isOrgAdmin: Boolean,
        nowMs: Long = clock(),
    ): AnnouncementSearchResponseDto = db.withConnection { conn ->
        val safeLimit = limit.coerceIn(1, 100)
        val safeBefore = before ?: Long.MAX_VALUE

        // Visibility constraint applied strictly in SQL
        val visibilityClause = when {
            isOrgAdmin -> "1=1"
            callerDeptId.isNullOrBlank() -> "a.scope = 'org'"
            else -> "(a.scope = 'org' OR (a.scope = 'department' AND a.department_id = '${escapeSql(callerDeptId)}'))"
        }

        // Optional filter constraint from query params
        val filterClause = when {
            scope == "org" -> "a.scope = 'org'"
            scope == "department" && !departmentId.isNullOrBlank() -> {
                if (!isOrgAdmin && departmentId != callerDeptId) {
                    return@withConnection AnnouncementSearchResponseDto() // Caller asking for another department -> empty
                }
                "a.scope = 'department' AND a.department_id = '${escapeSql(departmentId)}'"
            }
            else -> "1=1"
        }

        val trimmedQuery = query?.trim().orEmpty()
        val items = ArrayList<AnnouncementItemDto>()

        if (trimmedQuery.isNotEmpty()) {
            val hasFts = hasFtsTable(conn)
            var searchedWithFts = false
            if (hasFts) {
                val ftsQuery = escapeFts5Query(trimmedQuery)
                if (ftsQuery.isBlank()) {
                    return@withConnection AnnouncementSearchResponseDto()
                }
                try {
                    val sql = """
                        SELECT a.* FROM announcement a
                        JOIN announcement_fts fts ON a.rowid = fts.rowid
                        WHERE announcement_fts MATCH ?
                          AND a.deleted_at IS NULL
                          AND (a.expires_at IS NULL OR a.expires_at > ?)
                          AND a.created_at < ?
                          AND $visibilityClause
                          AND $filterClause
                        ORDER BY a.created_at DESC
                        LIMIT ?
                    """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, ftsQuery)
                        stmt.setLong(2, nowMs)
                        stmt.setLong(3, safeBefore)
                        stmt.setInt(4, safeLimit)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) items.add(readItem(rs))
                        }
                    }
                    searchedWithFts = true
                } catch (_: Exception) {
                    searchedWithFts = false
                }
            }

            if (!searchedWithFts) {
                // LIKE fallback
                val likeParam = "%" + escapeLike(trimmedQuery) + "%"
                val sql = """
                    SELECT a.* FROM announcement a
                    WHERE (a.title LIKE ? ESCAPE '\' OR a.body LIKE ? ESCAPE '\')
                      AND a.deleted_at IS NULL
                      AND (a.expires_at IS NULL OR a.expires_at > ?)
                      AND a.created_at < ?
                      AND $visibilityClause
                      AND $filterClause
                    ORDER BY a.created_at DESC
                    LIMIT ?
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, likeParam)
                    stmt.setString(2, likeParam)
                    stmt.setLong(3, nowMs)
                    stmt.setLong(4, safeBefore)
                    stmt.setInt(5, safeLimit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) items.add(readItem(rs))
                    }
                }
            }
        } else {
            // Recency list without text search
            val sql = """
                SELECT a.* FROM announcement a
                WHERE a.deleted_at IS NULL
                  AND (a.expires_at IS NULL OR a.expires_at > ?)
                  AND a.created_at < ?
                  AND $visibilityClause
                  AND $filterClause
                ORDER BY a.created_at DESC
                LIMIT ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, nowMs)
                stmt.setLong(2, safeBefore)
                stmt.setInt(3, safeLimit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) items.add(readItem(rs))
                }
            }
        }

        val nextBefore = if (items.size == safeLimit) items.last().createdAt else null

        AnnouncementSearchResponseDto(
            items = items,
            total = items.size,
            nextBefore = nextBefore,
        )
    }

    public fun previewAnnouncement(
        scope: String,
        departmentId: String?,
        title: String,
        body: String,
        departmentName: String? = null,
        estimatedRecipients: Int = 0,
    ): AnnouncementPreviewResponseDto {
        val cleaned = AnnouncementText.clean(title, body)
        val links = AnnouncementText.extractLinks(cleaned.body)

        val audienceText = if (scope == "department") {
            "Department: ${departmentName ?: departmentId ?: "Unknown"}"
        } else {
            "Whole company"
        }

        return AnnouncementPreviewResponseDto(
            title = cleaned.title,
            body = cleaned.body,
            titleCodePoints = cleaned.titleCodePoints,
            bodyCodePoints = cleaned.bodyCodePoints,
            links = links,
            audienceScope = audienceText,
            departmentName = departmentName,
            estimatedRecipients = estimatedRecipients,
            honestyNotice = "Announcements are stored as plain text on your company's server. Administrators can read them.",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Retention & Housekeeping
    // ---------------------------------------------------------------------------------------------

    public fun purgeExpiredAndOldTombstones(retentionDays: Int = 0, nowMs: Long = clock()): Int = db.withConnection { conn ->
        pruneOldRequests(conn, nowMs)

        var purgedCount = 0
        // 1. Purge tombstones older than 90 days
        val tombstoneCutoff = nowMs - (90L * 24 * 60 * 60 * 1000)
        conn.prepareStatement("DELETE FROM announcement WHERE deleted_at IS NOT NULL AND deleted_at < ?").use { stmt ->
            stmt.setLong(1, tombstoneCutoff)
            purgedCount += stmt.executeUpdate()
        }

        // 2. If retentionDays > 0, purge announcements older than retentionDays
        if (retentionDays > 0) {
            val retentionCutoff = nowMs - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
            conn.prepareStatement("DELETE FROM announcement WHERE created_at < ?").use { stmt ->
                stmt.setLong(1, retentionCutoff)
                purgedCount += stmt.executeUpdate()
            }
        }

        purgedCount
    }

    // ---------------------------------------------------------------------------------------------
    // Helper Methods
    // ---------------------------------------------------------------------------------------------

    private fun checkCanModify(
        announcement: AnnouncementItemDto,
        actorRole: String,
        actorDeptId: String?,
        actorRef: String,
    ) {
        val isOrgAdmin = actorRole == "admin" || actorRole == "ORG_ADMIN"
        if (isOrgAdmin) return

        val isDeptHead = actorRole == "dept_admin" || actorRole == "DEPT_HEAD"
        if (isDeptHead) {
            // A head can only edit announcements in their own department that were authored by a head
            val inOwnDept = announcement.scope == "department" && announcement.departmentId == actorDeptId
            val authoredByHead = announcement.authorRole == "dept_admin" || announcement.authorRole == "DEPT_HEAD"
            if (inOwnDept && authoredByHead) {
                return
            }
        }

        throw AnnouncementForbiddenException("Actor $actorRef ($actorRole) cannot modify announcement ${announcement.id}")
    }

    private fun checkPinLimit(conn: Connection, scope: String, departmentId: String?, excludeId: String?, nowMs: Long) {
        val sql = if (scope == "department") {
            """
            SELECT COUNT(*) FROM announcement
            WHERE scope = 'department' AND department_id = ?
              AND pinned = 1 AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
              AND (? IS NULL OR id != ?)
            """.trimIndent()
        } else {
            """
            SELECT COUNT(*) FROM announcement
            WHERE scope = 'org'
              AND pinned = 1 AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
              AND (? IS NULL OR id != ?)
            """.trimIndent()
        }

        conn.prepareStatement(sql).use { stmt ->
            var idx = 1
            if (scope == "department") {
                stmt.setString(idx++, departmentId)
            }
            stmt.setLong(idx++, nowMs)
            stmt.setString(idx++, excludeId)
            stmt.setString(idx, excludeId)

            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val count = rs.getInt(1)
                    if (count >= MAX_PINS_PER_SCOPE) {
                        throw AnnouncementPinLimitException()
                    }
                }
            }
        }
    }

    private fun nextSeq(conn: Connection): Long {
        conn.createStatement().use { st ->
            st.execute("UPDATE announcement_state SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) WHERE key = 'seq'")
        }
        return readState(conn, "seq")?.toLongOrNull() ?: 1L
    }

    private fun readState(conn: Connection, key: String): String? {
        conn.prepareStatement("SELECT value FROM announcement_state WHERE key = ?").use { stmt ->
            stmt.setString(1, key)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }

    private fun findByClientRequestId(conn: Connection, clientRequestId: String): String? {
        conn.prepareStatement("SELECT announcement_id FROM announcement_request WHERE client_request_id = ?").use { stmt ->
            stmt.setString(1, clientRequestId)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }

    private fun pruneOldRequests(conn: Connection, nowMs: Long = clock()) {
        val cutoff = nowMs - (10L * 60 * 1000) // 10 minutes
        conn.prepareStatement("DELETE FROM announcement_request WHERE created_at < ?").use { stmt ->
            stmt.setLong(1, cutoff)
            stmt.executeUpdate()
        }
    }

    private fun getByIdInternal(conn: Connection, id: String): AnnouncementItemDto? {
        conn.prepareStatement("SELECT * FROM announcement WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) readItem(rs) else null
            }
        }
    }

    private fun readItem(rs: ResultSet): AnnouncementItemDto {
        val deletedAt = rs.getLong("deleted_at")
        val isDeleted = !rs.wasNull() && deletedAt > 0
        val pinnedAt = rs.getLong("pinned_at")
        val pinnedAtOrNull = if (rs.wasNull()) null else pinnedAt
        val expiresAt = rs.getLong("expires_at")
        val expiresAtOrNull = if (rs.wasNull()) null else expiresAt

        return AnnouncementItemDto(
            id = rs.getString("id"),
            scope = rs.getString("scope"),
            departmentId = rs.getString("department_id"),
            title = if (isDeleted) "" else rs.getString("title"),
            body = if (isDeleted) "" else rs.getString("body"),
            authorKind = rs.getString("author_kind"),
            authorRef = rs.getString("author_ref"),
            authorName = rs.getString("author_name"),
            authorRole = rs.getString("author_role"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            edited = rs.getInt("edited") == 1,
            pinned = rs.getInt("pinned") == 1,
            pinnedAt = pinnedAtOrNull,
            expiresAt = expiresAtOrNull,
            deleted = isDeleted,
            seq = rs.getLong("seq"),
            kind = rs.getString("kind") ?: "text",
            flags = rs.getInt("flags"),
        )
    }

    private fun hasFtsTable(conn: Connection): Boolean {
        return try {
            conn.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='announcement_fts'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun escapeFts5Query(input: String): String {
        val rawWords = Regex("[\\p{L}\\p{N}]+").findAll(input).map { it.value }.toList()
        if (rawWords.isEmpty()) return ""
        val nonOpWords = rawWords.filter { it.uppercase() !in FTS_OPERATORS }
        val words = if (nonOpWords.isNotEmpty()) nonOpWords else rawWords
        return words.joinToString(" ") { "\"$it\"*" }
    }

    private fun escapeLike(input: String): String {
        return input.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    private fun escapeSql(input: String): String {
        return input.replace("'", "''")
    }

    public companion object {
        public const val COMPONENT: String = "announcements"
        public const val MAX_PINS_PER_SCOPE: Int = 3
        private val FTS_OPERATORS: Set<String> = setOf("AND", "OR", "NOT", "NEAR")

        public val MIGRATIONS: List<Migration> = listOf(
            Migration(1, "announcements: schema and FTS5 search table (Plan A3, ADR-138)") { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS announcement (
                            id TEXT PRIMARY KEY,
                            scope TEXT NOT NULL,
                            department_id TEXT,
                            title TEXT NOT NULL,
                            body TEXT NOT NULL,
                            author_kind TEXT NOT NULL,
                            author_ref TEXT NOT NULL,
                            author_name TEXT NOT NULL,
                            author_role TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            edited INTEGER NOT NULL DEFAULT 0,
                            pinned INTEGER NOT NULL DEFAULT 0,
                            pinned_at INTEGER,
                            expires_at INTEGER,
                            deleted_at INTEGER,
                            seq INTEGER NOT NULL,
                            kind TEXT NOT NULL DEFAULT 'text',
                            flags INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_announcement_scope_seq ON announcement(scope, department_id, seq)")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_announcement_created ON announcement(created_at DESC)")
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS announcement_state (
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS announcement_request (
                            client_request_id TEXT PRIMARY KEY,
                            announcement_id TEXT NOT NULL,
                            created_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_announcement_req_created ON announcement_request(created_at)")
                }

                // Attempt FTS5 table and triggers creation
                try {
                    conn.createStatement().use { st ->
                        st.execute(
                            """
                            CREATE VIRTUAL TABLE IF NOT EXISTS announcement_fts USING fts5(
                                title,
                                body,
                                content='announcement',
                                content_rowid='rowid'
                            )
                            """.trimIndent()
                        )
                        st.execute(
                            """
                            CREATE TRIGGER IF NOT EXISTS trg_announcement_ai AFTER INSERT ON announcement BEGIN
                                INSERT INTO announcement_fts(rowid, title, body) VALUES (new.rowid, new.title, new.body);
                            END
                            """.trimIndent()
                        )
                        st.execute(
                            """
                            CREATE TRIGGER IF NOT EXISTS trg_announcement_ad AFTER DELETE ON announcement BEGIN
                                INSERT INTO announcement_fts(announcement_fts, rowid, title, body) VALUES ('delete', old.rowid, old.title, old.body);
                            END
                            """.trimIndent()
                        )
                        st.execute(
                            """
                            CREATE TRIGGER IF NOT EXISTS trg_announcement_au AFTER UPDATE ON announcement BEGIN
                                INSERT INTO announcement_fts(announcement_fts, rowid, title, body) VALUES ('delete', old.rowid, old.title, old.body);
                                INSERT INTO announcement_fts(rowid, title, body) VALUES (new.rowid, new.title, new.body);
                            END
                            """.trimIndent()
                        )
                    }
                } catch (t: Throwable) {
                    LoggerFactory.getLogger(AnnouncementStore::class.java).warn(
                        "FTS5 virtual table setup skipped/failed: {}; search will fall back to LIKE",
                        t.message
                    )
                }
            }
        )

        public fun open(db: ServerDatabase, clock: () -> Long = System::currentTimeMillis): AnnouncementStore {
            Migrator(db).apply { register(COMPONENT, MIGRATIONS) }.migrate()
            return AnnouncementStore(db, clock)
        }
    }
}
