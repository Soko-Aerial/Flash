package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.SQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * JVM open paths for [FlashDatabase] (Phase 2, slice 1).
 *
 * ## Why this lives in `jvmMain` and not `commonMain`
 *
 * The first version of this seam was a `commonMain` function calling
 * `Room.databaseBuilder(name, factory)`. That overload exists only on the JVM: the Android
 * target's `Room` API takes a `Context` (`databaseBuilder(context, klass, name)`), so the
 * common file broke `:core:persistence:compileAndroidMain`. Room's builder is expect/actual
 * per platform with different signatures — there is no common no-Context overload in this
 * Room version — so a common open function cannot exist. The open always happens in
 * platform code anyway ([RealFlashChatRepository] takes DAOs, never a database), which is
 * also why Android keeps its own `androidMain` [FlashDatabaseOpener] untouched.
 */

/**
 * Encrypted, file-backed open path for [FlashDatabase] on the JVM (Phase 2, slice 1).
 *
 * This is the call `:desktop` uses. It builds the module-internal [JdbcCipherSQLiteDriver]
 * (willena `sqlite-jdbc-crypt`, ChaCha20 by default) and delegates to the target-free
 * [openFlashDatabase] seam — so the desktop never names the driver type, and the driver
 * stays hidden from every other module.
 *
 * ## Contract
 *
 * - [file] is the database file itself (e.g. `<stateDir>/chat/flash.db`). Missing parent
 *   directories are created, mirroring how `DesktopIdentityStore` treats `stateDir`.
 * - [key] must be non-empty. An empty passphrase is accepted by some ciphers as "no
 *   encryption", i.e. silently plaintext — the driver rejects it at construction with
 *   `IllegalArgumentException`, and that failure surfaces from this call before any query
 *   runs, never as an empty database.
 * - **Not SQLCipher-compatible.** Android opens the same schema via Zetetic SQLCipher;
 *   this driver encrypts via Utelle SQLite3MultipleCiphers. A desktop `.db` cannot be
 *   opened by the Android build or vice versa. Deliberate per D5 = C's third answer
 *   (OSS-only, no parity required): stores are per-device and are never transferred.
 * - A wrong [key] on an existing file FAILS on first access rather than reporting empty
 *   tables (proven by `JdbcCipherSQLiteDriverTest`) — so a corrupted key reads as an
 *   error, never as "your chat history was deleted".
 */
public fun openEncryptedFlashDatabase(file: File, key: String): FlashDatabase {
    file.parentFile?.mkdirs()
    return openFlashDatabase(file.absolutePath, JdbcCipherSQLiteDriver(key))
}

/**
 * Target-free (driver-parameterised) open for [FlashDatabase] on the JVM.
 *
 * Opens through the generated-constructor route (`@ConstructedBy` +
 * [FlashDatabaseConstructor]::initialize), exactly the route `FlashDatabaseJvmTest` proves.
 * The `factory` argument is passed explicitly on purpose — Room's default would find
 * `FlashDatabase_Impl` by reflection and pass even if the generated constructor were
 * missing, which would verify nothing (see that suite's KDoc).
 *
 * ## Encryption note (D5 = C, answered 2026-09-14)
 *
 * Whether the resulting file is encrypted depends ENTIRELY on the driver passed in. This
 * function does not inspect it — a test-only unencrypted driver opened at a file path would
 * be "B without C" under the charter, which is why `BundledSQLiteDriver` stays confined to
 * `:memory:` in `jvmTest` and the desktop path goes through [openEncryptedFlashDatabase].
 */
public fun openFlashDatabase(name: String, driver: SQLiteDriver): FlashDatabase =
    Room.databaseBuilder<FlashDatabase>(
        name = name,
        factory = FlashDatabaseConstructor::initialize,
    )
        .setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
