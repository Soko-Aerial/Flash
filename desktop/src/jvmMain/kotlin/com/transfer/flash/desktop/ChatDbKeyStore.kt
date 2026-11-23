@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.security.identity.IdentityKeyVault
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * The passphrase of the desktop chat database, kept in `<stateDir>/chat/db-key.bin` and protected by the same vault as the
 * identity key (ADR-132): Windows DPAPI, the Secret Service keyring or the owner-only key file on Linux.
 *
 * File format: `0x01 || vault.protect(passphrase bytes)`. The header byte is not a hex digit, so it cannot be mistaken for the
 * **legacy** file, which held the 64-hex passphrase as plain text. A legacy file is read as before and rewritten in the new
 * format. The passphrase itself does not change, so the database is not touched and there is nothing to roll back.
 *
 * Failure rules (the file is the only copy of the key, so nothing here may destroy it):
 * - an unreadable or tampered protected file throws; it is **not** replaced by a fresh key, which would orphan the history;
 * - a legacy file whose rewrite fails is left in place and its key still returned (chat keeps working, unprotected as before);
 * - only a missing or empty file mints a new key.
 *
 * Threat model, stated plainly: this protects a copy of the state directory taken from the disk, a backup, or another Windows
 * user. Code running as the same user can unprotect the blob (the same tier as the identity key, ADR-035).
 */
internal class ChatDbKeyStore(
    private val dir: File,
    private val vault: IdentityKeyVault,
    private val random: SecureRandom = SecureRandom(),
    private val log: (String) -> Unit = {},
) {
    private val keyFile = File(dir, FILE_NAME)

    internal class DeferredKey(
        val passphrase: String,
        val minted: Boolean,
        private val onCommit: () -> Unit,
    ) {
        fun commit() = onCommit()
    }

    /** The database passphrase; creates it on first use and upgrades a legacy plain-text file. */
    fun passphrase(): String = loadOrMintDeferred().also { it.commit() }.passphrase

    /**
     * Loads the stored passphrase or mints a fresh one in memory without writing [keyFile] until
     * [DeferredKey.commit] is called. When [keyFile] already exists on disk but cannot be read or
     * unprotected, throws [IllegalStateException] and leaves [keyFile] untouched.
     */
    fun loadOrMintDeferred(): DeferredKey {
        dir.mkdirs()
        if (keyFile.exists()) {
            if (!keyFile.isFile) {
                throw IllegalStateException(
                    "The chat database key path ${keyFile.name} exists but is not a regular file. Leaving it untouched.",
                )
            }
            val bytes = try {
                keyFile.readBytes()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "The chat database key in ${keyFile.name} cannot be read. The file was left as it is.",
                    e,
                )
            }
            if (bytes.isNotEmpty() && !bytes.all { it.toInt().toChar().isWhitespace() }) {
                if (bytes[0] == HEADER) {
                    val plain = try {
                        vault.unprotect(bytes.copyOfRange(1, bytes.size))
                    } catch (e: Exception) {
                        throw IllegalStateException(
                            "The chat database key in ${keyFile.name} cannot be unprotected (a different Windows user, a restored copy, or a damaged file). " +
                                "The file was left as it is.",
                            e,
                        )
                    }
                    return DeferredKey(String(plain, Charsets.UTF_8), minted = false) {}
                }
                val legacy = String(bytes, Charsets.UTF_8).trim()
                runCatching { writeBlob(protectBlob(legacy)) }
                    .onSuccess { log("chat database key re-stored under the OS vault (was plain text)") }
                    .onFailure { log("chat database key stays in plain text: ${it.javaClass.simpleName}") }
                return DeferredKey(legacy, minted = false) {}
            }
        }
        val fresh = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        // Protect eagerly so an unavailable vault fails before the DB opens or commits.
        val blob = try {
            protectBlob(fresh)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Cannot protect a fresh chat database key", e)
        }
        return DeferredKey(fresh, minted = true) { writeBlob(blob) }
    }

    private fun protectBlob(passphrase: String): ByteArray =
        byteArrayOf(HEADER) + vault.protect(passphrase.toByteArray(Charsets.UTF_8))

    private fun writeBlob(blob: ByteArray) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeBytes(blob)
        runCatching { Files.move(tmp.toPath(), keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(tmp.toPath(), keyFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    internal companion object {
        const val FILE_NAME = "db-key.bin"
        const val HEADER: Byte = 0x01
    }
}
