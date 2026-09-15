package com.transfer.flash.core.persistence.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import java.sql.DriverManager
import org.sqlite.mc.SQLiteMCConfig

/**
 * The encrypted, file-backed JVM driver — **09B-2**, and the artifact D5 = C was blocked on.
 *
 * PHASE-09B's amendment 8 records that `jvmMain` was never created for this module, and that the
 * only thing missing for desktop persistence was "an encrypted, file-backed JVM driver"
 * (`BundledSQLiteDriver` is unencrypted and is confined to `:memory:` in `jvmTest` by that phase's
 * charter). This is it.
 *
 * ## Why this driver
 *
 * `io.github.willena:sqlite-jdbc` — *"SQLite JDBC library with encryption and authentication
 * support"* (a fork of xerial's driver, `sqlite-jdbc-crypt`, Apache-2.0). The human answered D5's
 * three sub-questions on 2026-09-14: this driver, OSS-only (ruling out Zetetic's commercial
 * SQLCipher), and **no** file-format parity with Android required. Encryption itself was never
 * negotiable — `DECISIONS.md` forbids "B without C" — and this driver encrypts.
 *
 * ## What it does *not* give you
 *
 * **Not SQLCipher-compatible.** The fork encrypts via Utelle's SQLite3MultipleCiphers with a
 * selectable cipher, whereas Android uses Zetetic's SQLCipher. A desktop `.db` cannot be opened by
 * the Android build, or vice versa. That is a recorded, deliberate consequence of the third answer,
 * not an oversight: stores are per-device and are never transferred between them.
 *
 * ## Threading
 *
 * `hasConnectionPool` is left at its `false` default: each [open] returns a fresh JDBC connection
 * and nothing here is pooled. That is the signal Room uses to manage connections externally, which
 * is what we want — Room's `setQueryCoroutineContext` serialises access, and a half-hearted internal
 * pool would be worse than none.
 */
internal class JdbcCipherSQLiteDriver(
    /** The passphrase. Held for the driver's lifetime; never written anywhere by this class. */
    private val key: String,
) : SQLiteDriver {

    init {
        // Fail loudly at construction rather than handing back a database that is silently
        // plaintext because the key was empty. An empty passphrase is accepted by some ciphers as
        // "no encryption", and this whole class exists to prevent exactly that.
        require(key.isNotEmpty()) { "encrypted driver requires a non-empty key" }
        // The JDBC driver must be registered before `DriverManager.getConnection` is reachable.
        // Loading it explicitly also makes the dependency visible at the call site rather than a
        // classpath side effect (shaded/relocated builds lose the service loader entry).
        Class.forName("org.sqlite.JDBC")
    }

    override fun open(fileName: String): SQLiteConnection {
        // Windows paths: xerial accepts backslashes, but forward slashes avoid any doubt about how
        // the URL is parsed, and `:memory:` must be passed through untouched.
        val url = if (fileName == MEMORY) {
            "jdbc:sqlite:$MEMORY"
        } else {
            "jdbc:sqlite:" + fileName.replace('\\', '/')
        }
        // `withKey` is the fork's cipher-key entry point; the default cipher (ChaCha20) is applied
        // when none is named, and naming one is not required for a per-device store that nothing
        // else has to read.
        val properties = SQLiteMCConfig.Builder().withKey(key).build().toProperties()
        val connection = DriverManager.getConnection(url, properties)
        return JdbcCipherConnection(connection)
    }

    private companion object {
        const val MEMORY = ":memory:"
    }
}
