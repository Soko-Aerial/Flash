@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.perf.MemoryGovernor
import com.transfer.flash.core.common.perf.MemoryTrimLevel
import com.transfer.flash.core.common.perf.MemoryTrimListener
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
public fun openFlashDatabase(name: String, driver: SQLiteDriver): FlashDatabase {
    val trim = JvmDatabaseTrimRegistration()
    return Room.databaseBuilder<FlashDatabase>(
        name = name,
        factory = FlashDatabaseConstructor::initialize,
    )
        .setDriver(driver)
        // Every schema bump ships a step (C1.7); without this an older file cannot be opened and
        // no fallback exists. See FlashSchemaSteps for how to add one.
        .addMigrations(*FlashJvmMigrations.ALL)
        .addCallback(object : RoomDatabase.Callback() {
            /**
             * O23-05: `auto_vacuum` only takes effect on a file with no pages, and Room's `onCreate`
             * runs after the tables exist. The pragma is therefore only recorded there and applied by
             * the one-shot VACUUM in [onOpen] (a `VACUUM` cannot run inside the `onCreate`
             * transaction). An existing file keeps NONE until some later VACUUM, which is why the trim
             * hook below also issues `incremental_vacuum`.
             */
            private var justCreated = false

            override fun onCreate(connection: SQLiteConnection) {
                super.onCreate(connection)
                justCreated = true
                try {
                    connection.execSQL("PRAGMA auto_vacuum = INCREMENTAL;")
                } catch (e: Throwable) {
                    FlashLog.w("DATABASE", "Failed to set auto_vacuum on create on Desktop: ${e.message}")
                }
            }

            override fun onOpen(connection: SQLiteConnection) {
                super.onOpen(connection)
                if (justCreated) {
                    justCreated = false
                    runCatching { connection.execSQL("VACUUM;") }
                        .onSuccess { FlashLog.i("DATABASE", "Applied auto_vacuum after create on Desktop") }
                        .onFailure { FlashLog.w("DATABASE", "VACUUM after create failed on Desktop: ${it.message}") }
                }
                val pragmas = listOf(
                    "PRAGMA journal_mode = WAL;",
                    "PRAGMA synchronous = NORMAL;",
                    "PRAGMA busy_timeout = 5000;",
                    "PRAGMA temp_store = MEMORY;",
                    "PRAGMA cache_size = -4096;",
                    "PRAGMA journal_size_limit = 4194304;",
                )
                for (pragma in pragmas) {
                    try {
                        connection.execSQL(pragma)
                    } catch (e: Throwable) {
                        FlashLog.w("DATABASE", "Failed to set SQLite pragma '$pragma' on Desktop: ${e.message}")
                    }
                }
                trim.attach(
                    isOpen = { true },
                    shrink = {
                        try {
                            connection.execSQL("PRAGMA shrink_memory;")
                            // Hands at most `cache_size` free pages back to the filesystem; without
                            // it the auto_vacuum setting above never reclaims anything.
                            connection.execSQL("PRAGMA incremental_vacuum;")
                            FlashLog.i("DATABASE", "Executed PRAGMA shrink_memory + incremental_vacuum on Desktop")
                        } catch (e: Throwable) {
                            FlashLog.w("DATABASE", "Failed to execute PRAGMA shrink_memory on Desktop: ${e.message}")
                        }
                    },
                )
            }
        })
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
        .also { it.trimRegistration = trim }
}

internal class JvmDatabaseTrimRegistration(
    private val register: (MemoryTrimListener) -> Unit = MemoryGovernor::registerListener,
    private val unregister: (MemoryTrimListener) -> Unit = MemoryGovernor::unregisterListener,
) : AutoCloseable {
    private var current: MemoryTrimListener? = null
    private val lock = Any()

    fun attach(isOpen: () -> Boolean, shrink: () -> Unit) {
        synchronized(lock) {
            current?.let(unregister)
            val listener = MemoryTrimListener { level ->
                if (level == MemoryTrimLevel.RUNNING_LOW ||
                    level == MemoryTrimLevel.RUNNING_CRITICAL ||
                    level == MemoryTrimLevel.COMPLETE
                ) {
                    try {
                        if (isOpen()) shrink()
                    } catch (e: Throwable) {
                        FlashLog.w("DATABASE", "Failed to execute PRAGMA shrink_memory on Desktop: ${e.message}")
                    }
                }
            }
            current = listener
            register(listener)
        }
    }

    fun detach() {
        synchronized(lock) {
            current?.let(unregister)
            current = null
        }
    }

    override fun close() {
        detach()
    }
}
