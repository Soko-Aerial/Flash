package com.transfer.flash.server.admin

import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Immutable audit log record with blockchain-style SHA-256 cryptographic chaining.
 */
data class AuditEntry(
    val index: Long,
    val timestampMs: Long,
    val actor: String,
    val action: String,
    val details: String,
    val prevHash: String,
    val hash: String
)

/**
 * Append-only cryptographically hashed audit logger.
 *
 * Implements requirement:
 * hash_i = SHA256(prev_hash + "|" + timestamp + "|" + actor + "|" + action + "|" + details)
 *
 * Provides tamper-evidence and chain integrity verification for all administrative actions:
 * - CREATE_DEPARTMENT
 * - CREATE_GROUP
 * - INVITE_EMPLOYEE
 * - REVOKE_CERT
 * - CHANGE_ROLE
 * - LOGIN_SUCCESS / LOGIN_FAILED
 * - STEP_UP_CHALLENGE / STEP_UP_VERIFIED
 */
class AuditLogger {

    companion object {
        const val GENESIS_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    private val entries = CopyOnWriteArrayList<AuditEntry>()
    private val lock = Any()

    /**
     * Appends a new audit record to the log, computing its cryptographic hash
     * chained to the previous entry's hash.
     */
    fun log(actor: String, action: String, details: String): AuditEntry {
        synchronized(lock) {
            val index = entries.size.toLong()
            val timestampMs = System.currentTimeMillis()
            val prevHash = if (entries.isEmpty()) {
                GENESIS_HASH
            } else {
                entries.last().hash
            }

            val hash = computeHash(prevHash, timestampMs, actor, action, details)
            val entry = AuditEntry(
                index = index,
                timestampMs = timestampMs,
                actor = actor,
                action = action,
                details = details,
                prevHash = prevHash,
                hash = hash
            )
            entries.add(entry)
            return entry
        }
    }

    /**
     * Verifies the entire cryptographic chain integrity.
     * Returns true if all entries are intact and match their hashes; false if tampered.
     */
    fun verifyIntegrity(): Boolean {
        synchronized(lock) {
            if (entries.isEmpty()) return true

            var expectedPrevHash = GENESIS_HASH
            for (entry in entries) {
                if (entry.prevHash != expectedPrevHash) {
                    return false
                }
                val recomputed = computeHash(
                    entry.prevHash,
                    entry.timestampMs,
                    entry.actor,
                    entry.action,
                    entry.details
                )
                if (entry.hash != recomputed) {
                    return false
                }
                expectedPrevHash = entry.hash
            }
            return true
        }
    }

    fun getEntries(limit: Int = 100, offset: Int = 0): List<AuditEntry> {
        val all = entries.toList()
        if (offset >= all.size) return emptyList()
        return all.drop(offset).take(limit)
    }

    fun getRecent(limit: Int = 20): List<AuditEntry> {
        return entries.takeLast(limit).reversed()
    }

    fun getEntry(index: Long): AuditEntry? {
        val i = index.toInt()
        return if (i in entries.indices) entries[i] else null
    }

    fun size(): Int = entries.size

    /**
     * For testing only: alters an entry in memory to verify that tampering breaks the chain.
     */
    internal fun tamperWithEntryForTesting(index: Int, newDetails: String) {
        synchronized(lock) {
            val old = entries[index]
            val tampered = old.copy(details = newDetails)
            entries[index] = tampered
        }
    }

    private fun computeHash(
        prevHash: String,
        timestampMs: Long,
        actor: String,
        action: String,
        details: String
    ): String {
        val canonical = "$prevHash|$timestampMs|$actor|$action|$details"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }
}
