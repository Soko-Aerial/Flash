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
        val passphrase = provider.passphrase()
        val dbFile = context.applicationContext.getDatabasePath(FlashDatabase.DATABASE_NAME)
        val moved = quarantineIfUnreadable(dbFile, provider.mintedNewPassphrase, nowMs())
        if (moved.isNotEmpty()) {
            FlashLog.e(
                TAG,
                "Database key was lost (restore or keystore wipe); chat history is unrecoverable. " +
                    "Moved ${moved.size} file(s) aside: ${moved.joinToString { it.name }}",
            )
        }
        // A fresh copy per call: SQLCipher may clear the array it is handed.
        return FlashDatabaseOpener.openEncrypted(context, { passphrase.copyOf() }, *FlashMigrations.ALL)
    }

    /**
     * Moves [dbFile] and its sidecars to `<name>.unrecoverable-<stamp><suffix>` when the passphrase
     * was freshly minted but a database already exists. Returns the new locations (empty when nothing
     * needed moving).
     */
    internal fun quarantineIfUnreadable(dbFile: File, mintedNewPassphrase: Boolean, stamp: Long): List<File> {
        if (!mintedNewPassphrase || !dbFile.exists()) return emptyList()
        return SIDECAR_SUFFIXES.mapNotNull { suffix ->
            val source = File(dbFile.path + suffix)
            if (!source.exists()) return@mapNotNull null
            val target = File(dbFile.parentFile, "${dbFile.name}.unrecoverable-$stamp$suffix")
            if (source.renameTo(target)) target else null
        }
    }
}
