package com.transfer.flash.server

import com.transfer.flash.server.admin.totpSecretContext
import com.transfer.flash.server.auth.KeyFileException
import com.transfer.flash.server.auth.SecretBox
import com.transfer.flash.server.mailbox.mailboxPayloadContext
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.immediate
import com.transfer.flash.server.storage.query
import com.transfer.flash.server.storage.queryOne
import com.transfer.flash.server.storage.scalarLong
import com.transfer.flash.server.storage.update
import java.io.IOException
import java.nio.file.Path

private const val VERIFIER_KEY = "data-key.verifier.v1"
private const val VERIFIER_CONTEXT = "hub_meta:$VERIFIER_KEY"
private const val VERIFIER_VALUE = "Flash Hub data key v1"
private const val LEGACY_SAMPLES = 16

/** Binds a key to this database before any sealed data is used or written. Identity migrations must already be applied. */
internal fun loadHubDataKey(database: ServerDatabase, keyFile: Path): SecretBox = try {
    database.immediate { conn ->
        val verifier = conn.queryOne("SELECT v FROM hub_meta WHERE k = ?", VERIFIER_KEY) { it.getString(1) }
        if (verifier != null) {
            val box = SecretBox.loadOrCreate(keyFile, createIfMissing = false)
            if (!box.isSealed(verifier) || !authenticates { box.open(verifier, VERIFIER_CONTEXT) == VERIFIER_VALUE }) {
                throw KeyFileException("the database verifier does not match")
            }
            return@immediate box
        }

        // Older databases have no verifier. Probe existing ciphertext before accepting any key. Pre-sealing schemas
        // may have neither an admin table nor the mailbox marker column, and can start with a fresh key.
        val seeds = if (conn.scalarLong("SELECT COUNT(*) FROM sqlite_schema WHERE type = 'table' AND name = 'admin_users'") > 0) {
            conn.query(
                "SELECT username, totp_secret FROM admin_users WHERE totp_secret GLOB ? LIMIT ?",
                "${SecretBox.PREFIX}*", LEGACY_SAMPLES,
            ) { rs -> totpSecretContext(rs.getString(1)) to rs.getString(2) }
        } else emptyList()
        val payloads = if (conn.scalarLong("SELECT COUNT(*) FROM pragma_table_info('mailbox_envelopes') WHERE name = 'sealed'") > 0) {
            conn.query(
                "SELECT origin_device_id, target_device_id, msg_id, payload FROM mailbox_envelopes WHERE sealed = 1 LIMIT ?",
                LEGACY_SAMPLES,
            ) { rs -> mailboxPayloadContext(rs.getString(1), rs.getString(2), rs.getString(3)) to rs.getBytes(4) }
        } else emptyList()
        val hasSealedData = seeds.isNotEmpty() || payloads.isNotEmpty()
        val box = SecretBox.loadOrCreate(keyFile, createIfMissing = !hasSealedData)
        if (hasSealedData) {
            val hasValidSeed = seeds.any { (context, stored) -> authenticates { box.open(stored, context).isNotEmpty() } }
            val hasValidPayload = !hasValidSeed && payloads.any { (context, stored) ->
                authenticates {
                    box.openBytes(stored, context)
                    true
                }
            }
            if (!hasValidSeed && !hasValidPayload) throw KeyFileException("the key could not authenticate legacy sealed data")
        }
        conn.update("INSERT INTO hub_meta(k, v) VALUES (?, ?)", VERIFIER_KEY, box.seal(VERIFIER_VALUE, VERIFIER_CONTEXT))
        box
    }
} catch (e: KeyFileException) {
    throw HubStartupException("Data key validation failed. Restore the matching data.key with this Hub database.", e)
} catch (e: IOException) {
    throw HubStartupException("Data key file could not be accessed. Restore the matching data.key with this Hub database.", e)
}

private inline fun authenticates(check: () -> Boolean): Boolean = try {
    check()
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}
