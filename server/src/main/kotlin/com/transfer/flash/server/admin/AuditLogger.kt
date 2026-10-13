package com.transfer.flash.server.admin

import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.storage.Migration
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.immediate
import com.transfer.flash.server.storage.longOrNull
import com.transfer.flash.server.storage.query
import com.transfer.flash.server.storage.queryOne
import com.transfer.flash.server.storage.scalarLong
import com.transfer.flash.server.storage.update
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * One audit record: a SHA-256 link to the previous record plus, when the hub has a delegate key, an ECDSA signature over
 * the record's hash ([signature], Base64; null for records written without a signer).
 */
data class AuditEntry(
    val index: Long,
    val timestampMs: Long,
    val actor: String,
    val action: String,
    val details: String,
    val prevHash: String,
    val hash: String,
    val signature: String? = null,
)

/** A periodic signed statement "the log had [index] + 1 records and the last hash was [headHash] at [timestampMs]". */
data class AuditAnchor(val index: Long, val headHash: String, val timestampMs: Long, val signature: String?)

/** Filter of [AuditLogger.query]; every field is optional and they combine with AND. */
data class AuditFilter(
    val actor: String? = null,
    /** Matches actions that START with this text (`device.` finds every device event). */
    val actionPrefix: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    /** Substring of the details. */
    val text: String? = null,
)

/** Result of [AuditLogger.verifyChain]: [ok], or the first bad record and why. */
data class AuditVerification(
    val ok: Boolean,
    val entries: Long,
    val anchors: Long,
    val firstBadIndex: Long? = null,
    val reason: String? = null,
)

/** Signs audit hashes and anchors with the hub's delegate key. */
class AuditSigner(private val privateKey: PrivateKey, val publicKey: String) {
    fun sign(bytes: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(bytes)
        return Base64.getEncoder().encodeToString(sig.sign())
    }
}

/**
 * Append-only audit log persisted in SQLite (component `audit`, table `admin_audit`), so it survives a restart and
 * stays tamper-evident (SEC-13):
 *
 * - `hash_i = SHA256(prev_hash | timestamp | actor | action | details)` (the original chain formula, unchanged).
 * - With an [AuditSigner] each record also carries an ECDSA signature over its hash, and every [anchorEvery] records a
 *   signed anchor `(index, head hash, time)` is stored in `audit_anchors`. [verifyChain] checks the links, every signature
 *   and every anchor, so deleting or rewriting records (even with a recomputed chain) is detected.
 * - Events an unauthenticated caller can cause ([logUnauthenticated]) are rate limited: past the budget they are
 *   counted, and one `audit.suppressed` record states how many were dropped, so the log can not be filled by an attacker.
 *
 * `details` are stored with control characters replaced by spaces (a newline in a request must not forge a log line).
 */
class AuditLogger(
    private val db: ServerDatabase = ServerDatabase.inMemory(),
    private val signer: AuditSigner? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val anchorEvery: Int = DEFAULT_ANCHOR_EVERY,
    private val unauthenticatedBudgetPerMinute: Int = DEFAULT_UNAUTH_BUDGET,
) : AuditSink {

    companion object {
        const val GENESIS_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"
        const val DEFAULT_ANCHOR_EVERY: Int = 100
        const val DEFAULT_UNAUTH_BUDGET: Int = 20 // REV-15: about 29k rows a day from anonymous callers at most (was 60 a minute = 86k)
        const val MAX_DETAILS_CHARS: Int = 2000
        const val COMPONENT: String = "audit"

        val MIGRATIONS: List<Migration> = listOf(
            Migration(1, "audit: chained log and signed anchors") { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS admin_audit(idx INTEGER PRIMARY KEY, ts INTEGER NOT NULL, actor TEXT NOT NULL, " +
                            "action TEXT NOT NULL, details TEXT NOT NULL, prev_hash TEXT NOT NULL, hash TEXT NOT NULL, sig TEXT)",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_admin_audit_action ON admin_audit(action)")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_admin_audit_actor ON admin_audit(actor)")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS audit_anchors(idx INTEGER PRIMARY KEY, head_hash TEXT NOT NULL, ts INTEGER NOT NULL, sig TEXT)",
                    )
                }
            },
            // REV-15: records written from now on hash their fields length-prefixed (a `|` inside a field can no longer be moved
            // across a field boundary without changing the hash). Existing rows keep version 1 and are verified with the old form.
            Migration(2, "audit: per-record hash version") { conn ->
                conn.createStatement().use { st ->
                    st.execute("ALTER TABLE admin_audit ADD COLUMN chain_ver INTEGER NOT NULL DEFAULT 1")
                }
            },
        )

        /** Hash form of records written by this build. Version 1 is the original `a|b|c` text form, still verified for old rows. */
        const val CHAIN_VERSION: Int = 2

        private val CSV_DANGEROUS = setOf('=', '+', '-', '@', '\t', '\r')

        /** Neutralises spreadsheet formulas in a cell and quotes it for CSV. */
        internal fun csvCell(value: String): String {
            val safe = if (value.isNotEmpty() && value[0] in CSV_DANGEROUS) "'$value" else value
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
    }

    private val lock = Any()

    // Unauthenticated-event budget.
    private var unauthWindowStart = 0L
    private var unauthCount = 0
    private var unauthSuppressed = 0

    init {
        Migrator(db).apply { register(COMPONENT, MIGRATIONS) }.migrate()
    }

    /** [AuditSink] entry point used by routes outside the admin package. */
    override fun record(actor: String, action: String, details: String) {
        log(actor, action, details)
    }

    /** Appends a record chained to the previous one. */
    fun log(actor: String, action: String, details: String): AuditEntry = synchronized(lock) {
        append(actor, action, details)
    }

    /**
     * Appends an event that an unauthenticated caller can trigger (failed logins, refused enrolments). Past the budget
     * the event is only counted; the next admitted event is preceded by an `audit.suppressed` record with the count.
     * Returns null when the event was suppressed.
     */
    fun logUnauthenticated(actor: String, action: String, details: String): AuditEntry? = synchronized(lock) {
        val now = clock()
        if (now - unauthWindowStart >= 60_000L) {
            unauthWindowStart = now
            unauthCount = 0
            if (unauthSuppressed > 0) {
                append("system", "audit.suppressed", "dropped $unauthSuppressed unauthenticated events in the previous minute")
                unauthSuppressed = 0
            }
        }
        if (unauthCount >= unauthenticatedBudgetPerMinute) {
            unauthSuppressed++
            return null
        }
        unauthCount++
        append(actor, action, details)
    }

    private fun append(actor: String, action: String, details: String): AuditEntry = db.immediate { conn ->
        val last = conn.queryOne("SELECT idx, hash FROM admin_audit ORDER BY idx DESC LIMIT 1") { it.getLong(1) to it.getString(2) }
        val index = (last?.first ?: -1L) + 1
        val prev = last?.second ?: GENESIS_HASH
        val ts = clock()
        val cleanActor = clean(actor, 128)
        val cleanAction = clean(action, 128)
        val cleanDetails = clean(details, MAX_DETAILS_CHARS)
        val hash = computeHash(CHAIN_VERSION, prev, ts, cleanActor, cleanAction, cleanDetails)
        val sig = signer?.sign(signedBytes(index, hash))
        conn.update(
            "INSERT INTO admin_audit(idx, ts, actor, action, details, prev_hash, hash, sig, chain_ver) VALUES(?,?,?,?,?,?,?,?,?)",
            index, ts, cleanActor, cleanAction, cleanDetails, prev, hash, sig, CHAIN_VERSION,
        )
        if (anchorEvery > 0 && (index + 1) % anchorEvery == 0L) writeAnchor(conn, index, hash, ts)
        AuditEntry(index, ts, cleanActor, cleanAction, cleanDetails, prev, hash, sig)
    }

    private fun writeAnchor(conn: java.sql.Connection, index: Long, hash: String, ts: Long) {
        val sig = signer?.sign(anchorBytes(index, hash, ts))
        conn.update("INSERT OR REPLACE INTO audit_anchors(idx, head_hash, ts, sig) VALUES(?,?,?,?)", index, hash, ts, sig)
    }

    /** Writes a signed anchor for the current head now (called before an export or on demand). */
    fun anchor(): AuditAnchor? = synchronized(lock) {
        db.immediate { conn ->
            val last = conn.queryOne("SELECT idx, hash FROM admin_audit ORDER BY idx DESC LIMIT 1") { it.getLong(1) to it.getString(2) }
                ?: return@immediate null
            val ts = clock()
            writeAnchor(conn, last.first, last.second, ts)
            AuditAnchor(last.first, last.second, ts, signer?.sign(anchorBytes(last.first, last.second, ts)))
        }
    }

    /** The newest record's index and hash, or null for an empty log. */
    fun head(): Pair<Long, String>? = db.withConnection { conn ->
        conn.queryOne("SELECT idx, hash FROM admin_audit ORDER BY idx DESC LIMIT 1") { it.getLong(1) to it.getString(2) }
    }

    fun anchors(limit: Int = 100): List<AuditAnchor> = db.withConnection { conn ->
        conn.query("SELECT idx, head_hash, ts, sig FROM audit_anchors ORDER BY idx DESC LIMIT ?", limit.coerceIn(1, 1000)) {
            AuditAnchor(it.getLong(1), it.getString(2), it.getLong(3), it.getString(4))
        }
    }

    /**
     * Verifies the whole log: every link, every recomputed hash, every signature (when [publicKey] or the logger's
     * signer key is known) and every anchor. Reports the first bad record.
     */
    fun verifyChain(publicKey: String? = signer?.publicKey): AuditVerification = db.withConnection { conn ->
        val verifier = publicKey?.let { runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it))) }.getOrNull() }
        var expectedPrev = GENESIS_HASH
        var expectedIndex = 0L
        var count = 0L
        var bad: Pair<Long, String>? = null
        conn.prepareStatement("SELECT idx, ts, actor, action, details, prev_hash, hash, sig, chain_ver FROM admin_audit ORDER BY idx").use { st ->
            st.executeQuery().use { rs ->
                while (rs.next() && bad == null) {
                    val idx = rs.getLong(1)
                    val prev = rs.getString(6)
                    val hash = rs.getString(7)
                    val sig = rs.getString(8)
                    when {
                        idx != expectedIndex -> bad = idx to "a record is missing before this one"
                        prev != expectedPrev -> bad = idx to "the link to the previous record is broken"
                        computeHash(rs.getInt(9), prev, rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)) != hash ->
                            bad = idx to "the record does not match its hash"
                        verifier != null && sig == null -> bad = idx to "the record is not signed"
                        verifier != null && !verifySig(verifier, signedBytes(idx, hash), sig!!) -> bad = idx to "the signature is invalid"
                    }
                    expectedPrev = hash
                    expectedIndex = idx + 1
                    count++
                }
            }
        }
        var anchorCount = 0L
        if (bad == null) {
            val anchorRows = conn.query("SELECT idx, head_hash, ts, sig FROM audit_anchors ORDER BY idx") {
                AuditAnchor(it.getLong(1), it.getString(2), it.getLong(3), it.getString(4))
            }
            for (a in anchorRows) {
                anchorCount++
                val entryHash = conn.queryOne("SELECT hash FROM admin_audit WHERE idx = ?", a.index) { it.getString(1) }
                when {
                    entryHash == null -> bad = a.index to "the log was truncated before a signed anchor"
                    entryHash != a.headHash -> bad = a.index to "a record does not match its signed anchor"
                    verifier != null && (a.signature == null || !verifySig(verifier, anchorBytes(a.index, a.headHash, a.timestampMs), a.signature)) ->
                        bad = a.index to "an anchor signature is invalid"
                }
                if (bad != null) break
            }
        }
        AuditVerification(bad == null, count, anchorCount, bad?.first, bad?.second)
    }

    /** Boolean form of [verifyChain] (the original API). */
    fun verifyIntegrity(): Boolean = verifyChain().ok

    fun getEntries(limit: Int = 100, offset: Int = 0): List<AuditEntry> = query(AuditFilter(), limit, offset)

    fun getRecent(limit: Int = 20): List<AuditEntry> = db.withConnection { conn ->
        conn.query("SELECT * FROM admin_audit ORDER BY idx DESC LIMIT ?", limit.coerceIn(1, 1000), map = ::entryRow)
    }

    fun getEntry(index: Long): AuditEntry? = db.withConnection { conn ->
        conn.queryOne("SELECT * FROM admin_audit WHERE idx = ?", index, map = ::entryRow)
    }

    fun size(): Int = db.withConnection { it.scalarLong("SELECT COUNT(*) FROM admin_audit").toInt() }

    /** Filtered, paginated records, oldest first (newest first when [newestFirst]). */
    fun query(filter: AuditFilter, limit: Int = 100, offset: Int = 0, newestFirst: Boolean = false): List<AuditEntry> = db.withConnection { conn ->
        val (where, params) = whereOf(filter)
        conn.query(
            "SELECT * FROM admin_audit $where ORDER BY idx ${if (newestFirst) "DESC" else "ASC"} LIMIT ? OFFSET ?",
            *params.toTypedArray(), limit.coerceIn(1, 5000), offset.coerceAtLeast(0), map = ::entryRow,
        )
    }

    fun count(filter: AuditFilter = AuditFilter()): Long = db.withConnection { conn ->
        val (where, params) = whereOf(filter)
        conn.scalarLong("SELECT COUNT(*) FROM admin_audit $where", *params.toTypedArray())
    }

    /** CSV export (header + one row per record); every cell is quoted and formula-neutralised. */
    fun exportCsv(filter: AuditFilter = AuditFilter(), max: Int = 50_000): String = buildString {
        append("index,timestamp,actor,action,details,hash,signature\n")
        for (e in query(filter, max, 0)) {
            append(e.index).append(',').append(e.timestampMs).append(',')
            append(csvCell(e.actor)).append(',').append(csvCell(e.action)).append(',').append(csvCell(e.details)).append(',')
            append(csvCell(e.hash)).append(',').append(csvCell(e.signature.orEmpty())).append('\n')
        }
    }

    /** Test hook: rewrites a record's details in the database (to prove tampering is detected). */
    internal fun tamperWithEntryForTesting(index: Int, newDetails: String) {
        db.withConnection { it.update("UPDATE admin_audit SET details = ? WHERE idx = ?", newDetails, index.toLong()) }
    }

    /** Test hook: deletes records at and after [fromIndex] (a truncation attack). */
    internal fun truncateForTesting(fromIndex: Long) {
        db.withConnection { it.update("DELETE FROM admin_audit WHERE idx >= ?", fromIndex) }
    }

    private fun whereOf(f: AuditFilter): Pair<String, List<Any?>> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        f.actor?.let { clauses += "actor = ?"; params += it }
        f.actionPrefix?.let { clauses += "action LIKE ? ESCAPE '\\'"; params += likeEscape(it) + "%" }
        f.fromMs?.let { clauses += "ts >= ?"; params += it }
        f.toMs?.let { clauses += "ts <= ?"; params += it }
        f.text?.let { clauses += "details LIKE ? ESCAPE '\\'"; params += "%" + likeEscape(it) + "%" }
        return (if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")) to params
    }

    private fun likeEscape(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun entryRow(rs: java.sql.ResultSet) = AuditEntry(
        index = rs.getLong("idx"),
        timestampMs = rs.getLong("ts"),
        actor = rs.getString("actor"),
        action = rs.getString("action"),
        details = rs.getString("details"),
        prevHash = rs.getString("prev_hash"),
        hash = rs.getString("hash"),
        signature = rs.getString("sig"),
    )

    private fun clean(text: String, max: Int): String =
        text.map { if (it.code < 0x20 || it.code == 0x7F) ' ' else it }.joinToString("").take(max)

    private fun computeHash(version: Int, prevHash: String, timestampMs: Long, actor: String, action: String, details: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (version <= 1) {
            digest.update("$prevHash|$timestampMs|$actor|$action|$details".toByteArray(Charsets.UTF_8))
        } else {
            // Version 2: a tag, then every field as a 4-byte big-endian length and its UTF-8 bytes. No separator to forge.
            fun field(text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
            field("flash-audit-chain-v2")
            field(prevHash)
            field(timestampMs.toString())
            field(actor)
            field(action)
            field(details)
        }
        return digest.digest().toHex()
    }

    private fun signedBytes(index: Long, hash: String): ByteArray = "flash-audit-v1|$index|$hash".toByteArray(Charsets.UTF_8)

    private fun anchorBytes(index: Long, hash: String, ts: Long): ByteArray =
        "flash-audit-anchor-v1|$index|$hash|$ts".toByteArray(Charsets.UTF_8)

    private fun verifySig(key: java.security.PublicKey, bytes: ByteArray, signature: String): Boolean = try {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(key)
        sig.update(bytes)
        sig.verify(Base64.getDecoder().decode(signature))
    } catch (_: Exception) {
        false
    }
}
