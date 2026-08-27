package com.transfer.flash.core.persistence.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Supplies the raw database passphrase bytes. Keystore wrapping (AndroidKeyStore-wrapped AES
 * key unwrapping the SQLCipher passphrase) is implemented in `:app` (C1.4 note); this module
 * stays Keystore-free per R2.
 */
public fun interface PassphraseProvider {
    public fun passphrase(): ByteArray
}

/**
 * Open paths for [FlashDatabase].
 *
 * Encrypted path: SQLCipher for Android (`net.zetetic.database.sqlcipher.SupportOpenHelperFactory`)
 * wired in as the Room `SupportSQLiteOpenHelper.Factory`. The native library must be loaded
 * before any SQLCipher class touches SQLite; `System.loadLibrary("sqlcipher")` is a no-op when
 * already loaded, so repeated calls are safe. The passphrase byte array is retained by the
 * factory until first open — callers must not zero/reuse it immediately after returning.
 *
 * The passphrase itself is never logged by this class.
 */
public object FlashDatabaseOpener {

    /**
     * Production path: full-database encryption via SQLCipher (decision D2). No destructive
     * fallback — unknown schema versions fail fast; migrations are supplied explicitly
     * (empty at v1) and expanded from v2 onward per C1.7.
     */
    public fun openEncrypted(
        context: Context,
        passphraseProvider: PassphraseProvider,
        vararg migrations: Migration,
    ): FlashDatabase {
        System.loadLibrary("sqlcipher")
        val openHelperFactory = SupportOpenHelperFactory(passphraseProvider.passphrase())
        return Room.databaseBuilder(
            context.applicationContext,
            FlashDatabase::class.java,
            FlashDatabase.DATABASE_NAME,
        )
            .openHelperFactory(openHelperFactory)
            .addMigrations(*migrations)
            .build()
    }

    /**
     * In-memory path for JVM unit tests only (Robolectric): no SQLCipher factory because the
     * native sqlcipher .so cannot load on the JVM; the framework SQLite driver is used.
     */
    public fun openInMemory(context: Context): FlashDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, FlashDatabase::class.java)
            // !!! TEST-ONLY: destructive fallback exists here ONLY so ad-hoc test schemas never
            // wedge the JVM suite. PRODUCTION FORBIDS destructive migration from v2 onward
            // (plan C1.7 / docs/decisions.md). Never copy this line into openEncrypted().
            .fallbackToDestructiveMigration(true)
            .build()
}
