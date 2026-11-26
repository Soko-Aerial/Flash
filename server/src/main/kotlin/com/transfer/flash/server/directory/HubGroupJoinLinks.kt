package com.transfer.flash.server.directory

import com.transfer.flash.server.auth.SecretBox
import com.transfer.flash.server.storage.Migration
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.immediate
import com.transfer.flash.server.storage.longOrNull
import com.transfer.flash.server.storage.query
import com.transfer.flash.server.storage.update
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class JoinLinkRecord(
    val linkId: String,
    val groupId: String,
    val linkRevision: Long,
    val issuerDeviceId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val maxUses: Int? = null,
    val useCount: Int = 0,
    val revokedAt: Long? = null,
    val revokedBy: String? = null,
)

/**
 * Short-lived links to the ordinary Hub group join workflow.
 *
 * Uses its own sealing [secrets] (independent of `data.key`), accepts optional [fallbackSecrets] so rotating
 * `data.key` or `join-links.key` does not break unexpired tokens, and persists per-link metadata (issuer,
 * optional [maxUses], revocation state) when backed by [database].
 */
class HubGroupJoinLinks(
    private val secrets: SecretBox,
    private val clock: () -> Long,
    private val fallbackSecrets: List<SecretBox> = emptyList(),
    private val database: ServerDatabase? = null,
) {
    private val memoryLinks = ConcurrentHashMap<String, JoinLinkRecord>()
    private val memoryLock = Any()

    init {
        if (database != null) {
            Migrator(database).apply { register(COMPONENT, MIGRATIONS) }.migrate()
        }
    }

    fun issue(group: GroupCharter): String = issue(group, issuerDeviceId = "", maxUses = null)

    fun issue(group: GroupCharter, issuerDeviceId: String = "", maxUses: Int? = null): String {
        check(secrets.isActive)
        if (maxUses != null) require(maxUses > 0) { "maxUses must be positive" }
        val now = clock()
        val expiresAt = now + TTL_MS
        val linkId = UUID.randomUUID().toString()
        val record = JoinLinkRecord(
            linkId = linkId,
            groupId = group.id,
            linkRevision = group.joinLinkRevision,
            issuerDeviceId = issuerDeviceId,
            createdAt = now,
            expiresAt = expiresAt,
            maxUses = maxUses,
            useCount = 0,
        )
        saveRecord(record)
        val data = "$linkId\n${group.id}\n${group.joinLinkRevision}\n$expiresAt"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.sealBytes(data.encodeToByteArray(), AAD_V3))
    }

    fun linkIdOf(token: String): String? {
        val opened = openToken(token) ?: return null
        return if (opened.version == TokenFormat.V3) opened.linkId else null
    }

    fun listLinks(groupId: String): List<JoinLinkRecord> {
        val db = database
        if (db != null) {
            return db.withConnection { conn ->
                conn.query(
                    "SELECT * FROM dir_group_join_links WHERE group_id = ? ORDER BY created_at DESC",
                    groupId,
                    map = ::mapRow,
                )
            }
        }
        return synchronized(memoryLock) {
            memoryLinks.values.filter { it.groupId == groupId }.sortedByDescending { it.createdAt }
        }
    }

    fun belongsToGroup(groupId: String, linkId: String): Boolean {
        if (groupId.isBlank() || linkId.isBlank()) return false
        val db = database
        if (db != null) {
            return db.withConnection { conn ->
                conn.query(
                    "SELECT 1 FROM dir_group_join_links WHERE link_id = ? AND group_id = ? LIMIT 1",
                    linkId, groupId,
                ) { true }.isNotEmpty()
            }
        }
        synchronized(memoryLock) {
            return memoryLinks[linkId]?.groupId == groupId
        }
    }

    fun revokeLink(groupId: String, linkId: String, actorDeviceId: String = ""): Boolean {
        if (groupId.isBlank() || linkId.isBlank()) return false
        val now = clock()
        val db = database
        if (db != null) {
            return db.withConnection { conn ->
                conn.update(
                    "UPDATE dir_group_join_links SET revoked_at = ?, revoked_by = ? WHERE link_id = ? AND group_id = ? AND revoked_at IS NULL",
                    now, actorDeviceId, linkId, groupId,
                ) > 0
            }
        }
        synchronized(memoryLock) {
            val cur = memoryLinks[linkId] ?: return false
            if (cur.groupId != groupId || cur.revokedAt != null) return false
            memoryLinks[linkId] = cur.copy(revokedAt = now, revokedBy = actorDeviceId)
            return true
        }
    }

    fun revokeToken(groupId: String, token: String, actorDeviceId: String = ""): Boolean {
        val opened = openToken(token) ?: return false
        if (opened.version != TokenFormat.V3 || opened.groupId != groupId) return false
        val linkId = opened.linkId ?: return false
        return revokeLink(groupId, linkId, actorDeviceId)
    }

    fun revokeAllForGroup(groupId: String, actorDeviceId: String = ""): Int {
        val now = clock()
        val db = database
        if (db != null) {
            return db.withConnection { conn ->
                conn.update(
                    "UPDATE dir_group_join_links SET revoked_at = ?, revoked_by = ? WHERE group_id = ? AND revoked_at IS NULL",
                    now, actorDeviceId, groupId,
                )
            }
        }
        synchronized(memoryLock) {
            var count = 0
            for ((id, rec) in memoryLinks) {
                if (rec.groupId == groupId && rec.revokedAt == null) {
                    memoryLinks[id] = rec.copy(revokedAt = now, revokedBy = actorDeviceId)
                    count++
                }
            }
            return count
        }
    }

    fun resolve(
        token: String,
        directory: OrganizationDirectory,
        canResolve: (GroupCharter) -> Boolean = { true },
    ): GroupCharter? {
        val opened = openToken(token) ?: return null
        val now = clock()
        if (opened.expiresAt <= now || opened.expiresAt - now > TTL_MS) return null
        val group = directory.getGroup(opened.groupId)?.takeIf {
            val expectedRev = if (opened.version == TokenFormat.V1) it.version else it.joinLinkRevision
            expectedRev == opened.revision &&
                it.visibility != GroupVisibility.HIDDEN &&
                it.joinMode != JoinMode.ADMIN_ONLY &&
                canResolve(it)
        } ?: return null

        if (opened.version == TokenFormat.V3) {
            val linkId = opened.linkId ?: return null
            if (!consumeLink(linkId, group.id, opened.revision, now)) return null
        }
        return group
    }

    private enum class TokenFormat { V1, V2, V3 }

    private data class OpenedToken(
        val version: TokenFormat,
        val linkId: String?,
        val groupId: String,
        val revision: Long,
        val expiresAt: Long,
    )

    private fun openToken(token: String): OpenedToken? {
        if (token.length !in 1..MAX_TOKEN_LENGTH || !TOKEN_PATTERN.matches(token)) return null
        val candidates = (listOf(secrets) + fallbackSecrets).filter { it.isActive }
        if (candidates.isEmpty()) return null
        val sealed = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull() ?: return null
        for (box in candidates) {
            openWithBox(box, sealed, AAD_V3, TokenFormat.V3)?.let { return it }
            openWithBox(box, sealed, AAD_V2, TokenFormat.V2)?.let { return it }
            openWithBox(box, sealed, LEGACY_AAD, TokenFormat.V1)?.let { return it }
        }
        return null
    }

    private fun openWithBox(box: SecretBox, sealed: ByteArray, aad: String, format: TokenFormat): OpenedToken? {
        val fields = runCatching {
            box.openBytes(sealed, aad).decodeToString(throwOnInvalidSequence = true).split('\n')
        }.getOrNull() ?: return null
        return when (format) {
            TokenFormat.V3 -> {
                if (fields.size != 4) return null
                val linkId = fields[0].takeIf { it.isNotBlank() } ?: return null
                val rev = fields[2].toLongOrNull() ?: return null
                val exp = fields[3].toLongOrNull() ?: return null
                OpenedToken(TokenFormat.V3, linkId, fields[1], rev, exp)
            }
            TokenFormat.V2, TokenFormat.V1 -> {
                if (fields.size != 3) return null
                val rev = fields[1].toLongOrNull() ?: return null
                val exp = fields[2].toLongOrNull() ?: return null
                OpenedToken(format, null, fields[0], rev, exp)
            }
        }
    }

    private fun saveRecord(record: JoinLinkRecord) {
        val db = database
        if (db != null) {
            db.withConnection { conn ->
                conn.update(
                    """
                    INSERT INTO dir_group_join_links(
                        link_id, group_id, link_revision, issuer_device_id, created_at, expires_at, max_uses, use_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    record.linkId,
                    record.groupId,
                    record.linkRevision,
                    record.issuerDeviceId,
                    record.createdAt,
                    record.expiresAt,
                    record.maxUses,
                )
            }
            return
        }
        synchronized(memoryLock) {
            memoryLinks[record.linkId] = record
        }
    }

    private fun consumeLink(linkId: String, groupId: String, revision: Long, now: Long): Boolean {
        val db = database
        if (db != null) {
            return db.immediate { conn ->
                val updated = conn.update(
                    """
                    UPDATE dir_group_join_links
                    SET use_count = use_count + 1
                    WHERE link_id = ?
                      AND group_id = ?
                      AND link_revision = ?
                      AND revoked_at IS NULL
                      AND expires_at > ?
                      AND (max_uses IS NULL OR use_count < max_uses)
                    """.trimIndent(),
                    linkId, groupId, revision, now,
                )
                updated == 1
            }
        }
        synchronized(memoryLock) {
            val rec = memoryLinks[linkId] ?: return false
            if (rec.groupId != groupId || rec.linkRevision != revision) return false
            if (rec.revokedAt != null || rec.expiresAt <= now) return false
            if (rec.maxUses != null && rec.useCount >= rec.maxUses) return false
            memoryLinks[linkId] = rec.copy(useCount = rec.useCount + 1)
            return true
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): JoinLinkRecord = JoinLinkRecord(
        linkId = rs.getString("link_id"),
        groupId = rs.getString("group_id"),
        linkRevision = rs.getLong("link_revision"),
        issuerDeviceId = rs.getString("issuer_device_id").orEmpty(),
        createdAt = rs.getLong("created_at"),
        expiresAt = rs.getLong("expires_at"),
        maxUses = rs.getObject("max_uses")?.let { (it as Number).toInt() },
        useCount = rs.getInt("use_count"),
        revokedAt = rs.longOrNull("revoked_at"),
        revokedBy = rs.getString("revoked_by"),
    )

    companion object {
        const val COMPONENT: String = "group_join_links"
        private const val AAD_V3 = "flash-hub:group-join-link:v3"
        private const val AAD_V2 = "flash-hub:group-join-link:v2"
        private const val LEGACY_AAD = "flash-hub:group-join-link:v1"
        private const val TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_TOKEN_LENGTH = 2048
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+")

        val MIGRATIONS: List<Migration> = listOf(
            Migration(1, "group_join_links: per-link tracking, max uses, and revocation") { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS dir_group_join_links (
                            link_id TEXT PRIMARY KEY,
                            group_id TEXT NOT NULL,
                            link_revision INTEGER NOT NULL,
                            issuer_device_id TEXT NOT NULL DEFAULT '',
                            created_at INTEGER NOT NULL,
                            expires_at INTEGER NOT NULL,
                            max_uses INTEGER,
                            use_count INTEGER NOT NULL DEFAULT 0,
                            revoked_at INTEGER,
                            revoked_by TEXT
                        )
                        """.trimIndent(),
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_dir_group_join_links_group ON dir_group_join_links(group_id, revoked_at)")
                }
            },
        )
    }
}
