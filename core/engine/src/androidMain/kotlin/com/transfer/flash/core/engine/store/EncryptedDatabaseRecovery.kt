package com.transfer.flash.core.engine.store

import android.content.Context
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.FlashDatabaseOpener
import com.transfer.flash.core.persistence.db.FlashMigrations
import java.io.File

/**
 * Opens the encrypted chat database without locking the app out when its key is gone (audit B7).
 *
 * The SQLCipher passphrase is wrapped by an AndroidKeyStore key. If that key disappears (a restore
 * onto another device, a keystore wipe after a lock-screen change) or the wrapped blob is missing
 * while the DB file is present, [KeystorePassphraseProvider] has to mint a fresh passphrase, and the
 * existing file can never be opened with it. Production forbids destructive migration, so the old
 * behaviour was a crash on every launch.
 *
 * Here the unreadable database (and its WAL/SHM/journal sidecars) is moved aside, never deleted:
 * the history is unrecoverable without the lost key, but the bytes stay on disk for forensics, and
 * the app starts with an empty database instead of crash-looping.
 */
@FlashInternalApi
public object EncryptedDatabaseRecovery {

    private const val TAG = "DATABASE"
    private val SIDECAR_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    /** Opens the production DB, quarantining an unreadable one first. Call off the main thread. */
    public fun openRecoveringLostKey(
        context: Context,
        provider: KeystorePassphraseProvider = KeystorePassphraseProvider(context),
        nowMs: () -> Long = System::currentTimeMillis,
    ): FlashDatabase {
        // The passphrase is only held back here: if it was minted, the OLD wrapper stays on disk until the old database
        // is out of the way and the new one exists. A transient keystore failure throws (PassphraseUnavailableException)
        // and touches nothing.
        val passphrase = provider.passphraseDeferringCommit()
        val minted = provider.mintedNewPassphrase
        val dbFile = context.applicationContext.getDatabasePath(FlashDatabase.DATABASE_NAME)
        val moved = quarantineIfUnreadable(dbFile, minted, nowMs())
        if (moved.isNotEmpty()) {
            FlashLog.e(
                TAG,
                "Database key was lost (restore or keystore wipe); chat history is unrecoverable. " +
                    "Moved ${moved.size} file(s) aside: ${moved.joinToString { it.name }}",
            )
        }
        val remaining = SIDECAR_SUFFIXES.map { File(dbFile.path + it) }.filter { it.exists() }
        check(!minted || remaining.isEmpty()) {
            "The unreadable database or its sidecars (${remaining.joinToString { it.name }}) could not be moved aside; " +
                "the stored passphrase was left untouched"
        }
        // A fresh copy per call: SQLCipher may clear the array it is handed.
        val db = FlashDatabaseOpener.openEncrypted(context, { passphrase.copyOf() }, *FlashMigrations.ALL)
        if (minted) {
            // Room opens lazily; force the new file into existence before the wrapper that can open it replaces the old one.
            db.openHelper.writableDatabase
            provider.commitMintedPassphrase()
        }
        return db
    }

    /**
     * Moves [dbFile] and all of its sidecars (`-wal`, `-shm`, `-journal`) to
     * `<name>.unrecoverable-<stamp><suffix>` when the passphrase was freshly minted and any
     * database or orphaned sidecar exists. If any sidecar rename fails, already-moved files are
     * rolled back and an [IllegalStateException] is thrown so a fresh database is never opened
     * beside stale encrypted WAL/SHM files.
     */
    internal fun quarantineIfUnreadable(dbFile: File, mintedNewPassphrase: Boolean, stamp: Long): List<File> {
        if (!mintedNewPassphrase) return emptyList()
        val existing = SIDECAR_SUFFIXES.mapNotNull { suffix ->
            val source = File(dbFile.path + suffix)
            if (source.exists()) suffix to source else null
        }
        if (existing.isEmpty()) return emptyList()

        val parent = dbFile.parentFile
        var attempt = 0
        var prefix = "${dbFile.name}.unrecoverable-$stamp"
        while (existing.any { (suffix, _) -> File(parent, "$prefix$suffix").exists() }) {
            attempt++
            prefix = "${dbFile.name}.unrecoverable-$stamp-$attempt"
        }

        val movedPairs = ArrayList<Pair<File, File>>(existing.size)
        for ((suffix, source) in existing) {
            val target = File(parent, "$prefix$suffix")
            if (!source.renameTo(target)) {
                // Roll back already-moved files so the database and its sidecars stay consistent.
                for ((orig, moved) in movedPairs.asReversed()) {
                    moved.renameTo(orig)
                }
                throw IllegalStateException(
                    "Could not quarantine unreadable database sidecar ${source.name} to ${target.name}",
                )
            }
            movedPairs.add(source to target)
        }
        return movedPairs.map { it.second }
    }
}
