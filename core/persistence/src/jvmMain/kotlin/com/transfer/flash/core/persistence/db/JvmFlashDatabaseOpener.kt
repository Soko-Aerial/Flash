@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.perf.MemoryGovernor
import com.transfer.flash.core.common.perf.MemoryTrimLevel
import com.transfer.flash.core.common.perf.MemoryTrimListener
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
public sealed interface JvmDatabaseOpenResult {
    public data class Opened(
        public val database: FlashDatabase,
        public val recoveredAction: String? = null,
    ) : JvmDatabaseOpenResult

    public data class UnavailablePreserved(
        public val reason: Throwable,
    ) : JvmDatabaseOpenResult
}

public fun openEncryptedFlashDatabase(file: File, key: String): FlashDatabase {
    file.parentFile?.mkdirs()
    return openFlashDatabase(file.absolutePath, JdbcCipherSQLiteDriver(key))
}

/**
 * Opens the desktop encrypted [FlashDatabase] and validates it eagerly, performing safe recovery
 * without data loss when needed:
 *
 * - If [mintedNewKey] is true and [file] (or any `-wal`/`-shm`/`-journal` sidecar) already exists,
 *   the unreadable store and all sidecars are atomically quarantined to
 *   `<name>.unrecoverable-<stamp><suffix>` (with rollback on partial failure) before opening a
 *   fresh store.
 * - If [mintedNewKey] is false and opening/validation fails with a non-corruption error (busy/lock,
 *   I/O, permission, or schema-version mismatch), existing files are preserved untouched and
 *   [JvmDatabaseOpenResult.UnavailablePreserved] is returned.
 * - If opening/validation fails with SQLite corruption (`SQLITE_CORRUPT` / `SQLITE_NOTADB`):
 *   a full forensic backup is taken first; if a WAL-less copy passes `PRAGMA integrity_check` and
 *   [validate], only the corrupt `-wal`/`-shm`/`-journal` sidecars are quarantined so intact chat
 *   history survives; otherwise the unrecoverable database and sidecars are quarantined and a fresh
 *   database is opened.
 */
public fun openEncryptedFlashDatabaseWithRecovery(
    file: File,
    key: String,
    mintedNewKey: Boolean = false,
    nowMs: () -> Long = System::currentTimeMillis,
    validate: suspend (FlashDatabase) -> Unit = { it.conversationDao().get("") },
): JvmDatabaseOpenResult {
    file.parentFile?.mkdirs()
    val stamp = nowMs()

    if (mintedNewKey) {
        val quarantined = try {
            quarantineFiles(file, DB_ALL_SUFFIXES, "unrecoverable-$stamp")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return JvmDatabaseOpenResult.UnavailablePreserved(e)
        }
        return tryOpenValidated(file, key, validate) { db ->
            JvmDatabaseOpenResult.Opened(
                database = db,
                recoveredAction = if (quarantined.isNotEmpty()) {
                    "quarantined ${quarantined.size} unreadable database file(s) after missing key: ${quarantined.joinToString { it.name }}"
                } else {
                    null
                },
            )
        }
    }

    val initial = tryOpenValidated(file, key, validate) { JvmDatabaseOpenResult.Opened(it) }
    if (initial is JvmDatabaseOpenResult.Opened) return initial
    val failure = (initial as JvmDatabaseOpenResult.UnavailablePreserved).reason
    if (!isCorruptionOrKeyMismatch(failure)) {
        return initial
    }

    // Preserve a full copy before attempting WAL triage or quarantine.
    val backups = try {
        backupFiles(file, DB_ALL_SUFFIXES, "backup-$stamp")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return JvmDatabaseOpenResult.UnavailablePreserved(e)
    }

    // If WAL/SHM sidecars exist alongside the main DB, check whether the main DB alone is intact.
    val hasWalOrJournal = DB_SIDECAR_ONLY_SUFFIXES.any { File(file.path + it).exists() }
    if (file.exists() && hasWalOrJournal) {
        val probePrefix = uniqueName(file, "repair-check-$stamp", listOf(""))
        val probeFile = File(file.parentFile, probePrefix)
        try {
            Files.copy(file.toPath(), probeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val probeResult = tryOpenValidated(
                file = probeFile,
                key = key,
                validate = { db ->
                    val ok = db.useWriterConnection { writer ->
                        writer.usePrepared("PRAGMA integrity_check") { stmt ->
                            stmt.step() && stmt.getText(0).equals("ok", ignoreCase = true)
                        }
                    }
                    if (!ok) error("integrity_check failed")
                    validate(db)
                },
                onSuccess = { JvmDatabaseOpenResult.Opened(it) },
            )
            if (probeResult is JvmDatabaseOpenResult.Opened) {
                runCatching { probeResult.database.closeWithTrim() }
                val quarantinedSidecars = quarantineFiles(file, DB_SIDECAR_ONLY_SUFFIXES, "corrupt-wal-$stamp")
                val reopened = tryOpenValidated(file, key, validate) { db ->
                    JvmDatabaseOpenResult.Opened(
                        database = db,
                        recoveredAction = "repaired database by quarantining corrupt sidecar(s) ${quarantinedSidecars.joinToString { it.name }}; backup kept at ${backups.firstOrNull()?.name}",
                    )
                }
                if (reopened is JvmDatabaseOpenResult.Opened) {
                    return reopened
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Fall through to full quarantine below; backups remain on disk.
        } finally {
            DB_ALL_SUFFIXES.forEach { suffix ->
                runCatching { File(probeFile.path + suffix).delete() }
            }
        }
    }

    // Main database itself is unrecoverable with this key: quarantine it (never delete) and open fresh.
    val quarantined = try {
        quarantineFiles(file, DB_ALL_SUFFIXES, "unrecoverable-$stamp")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return JvmDatabaseOpenResult.UnavailablePreserved(e)
    }
    // Redundant pre-quarantine copy is no longer needed once the original files are safely quarantined.
    backups.forEach { runCatching { it.delete() } }

    return tryOpenValidated(file, key, validate) { db ->
        JvmDatabaseOpenResult.Opened(
            database = db,
            recoveredAction = "quarantined corrupt/unreadable database (${quarantined.joinToString { it.name }}) and opened a fresh database",
        )
    }
}

private inline fun tryOpenValidated(
    file: File,
    key: String,
    crossinline validate: suspend (FlashDatabase) -> Unit,
    onSuccess: (FlashDatabase) -> JvmDatabaseOpenResult,
): JvmDatabaseOpenResult {
    val candidate = try {
        openEncryptedFlashDatabase(file, key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return JvmDatabaseOpenResult.UnavailablePreserved(e)
    }
    return try {
        runBlocking { validate(candidate) }
        onSuccess(candidate)
    } catch (e: CancellationException) {
        runCatching { candidate.closeWithTrim() }
        throw e
    } catch (e: Throwable) {
        runCatching { candidate.closeWithTrim() }
        JvmDatabaseOpenResult.UnavailablePreserved(e)
    }
}

private fun isCorruptionOrKeyMismatch(error: Throwable): Boolean {
    return generateSequence(error) { it.cause }.any { cause ->
        val msg = cause.message?.lowercase().orEmpty()
        msg.contains("sqlite_corrupt") ||
            msg.contains("sqlite_notadb") ||
            msg.contains("database disk image is malformed") ||
            msg.contains("file is not a database") ||
            msg.contains("file is encrypted or is not a database")
    }
}

private fun uniqueName(baseFile: File, tag: String, suffixes: List<String>): String {
    val parent = baseFile.parentFile
    var attempt = 0
    var prefix = "${baseFile.name}.$tag"
    while (suffixes.any { suffix -> File(parent, "$prefix$suffix").exists() }) {
        attempt++
        prefix = "${baseFile.name}.$tag-$attempt"
    }
    return prefix
}

private fun backupFiles(baseFile: File, suffixes: List<String>, tag: String): List<File> {
    val existing = suffixes.mapNotNull { suffix ->
        File(baseFile.path + suffix).takeIf { it.exists() }?.let { suffix to it }
    }
    if (existing.isEmpty()) return emptyList()
    val parent = baseFile.parentFile
    val prefix = uniqueName(baseFile, tag, suffixes)
    return existing.map { (suffix, source) ->
        val target = File(parent, "$prefix$suffix")
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        target
    }
}

private fun quarantineFiles(baseFile: File, suffixes: List<String>, tag: String): List<File> {
    val existing = suffixes.mapNotNull { suffix ->
        File(baseFile.path + suffix).takeIf { it.exists() }?.let { suffix to it }
    }
    if (existing.isEmpty()) return emptyList()
    val parent = baseFile.parentFile
    val prefix = uniqueName(baseFile, tag, suffixes)
    val movedPairs = ArrayList<Pair<File, File>>(existing.size)
    for ((suffix, source) in existing) {
        val target = File(parent, "$prefix$suffix")
        val moved = runCatching {
            Files.move(source.toPath(), target.toPath())
            true
        }.getOrElse {
            source.renameTo(target)
        }
        if (!moved) {
            for ((orig, dst) in movedPairs.asReversed()) {
                runCatching { Files.move(dst.toPath(), orig.toPath()) }
                    .onFailure { dst.renameTo(orig) }
            }
            throw IllegalStateException("Could not quarantine ${source.name} to ${target.name}")
        }
        movedPairs.add(source to target)
    }
    return movedPairs.map { it.second }
}

private val DB_ALL_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
private val DB_SIDECAR_ONLY_SUFFIXES = listOf("-wal", "-shm", "-journal")

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
    val opened = AtomicBoolean(false)
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
                opened.set(true)
            }
        })
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
        .also { it.trimRegistration = JvmDatabaseTrimRegistration(it, opened) }
}

private class JvmDatabaseTrimRegistration(
    db: FlashDatabase,
    opened: AtomicBoolean,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduled = AtomicBoolean(false)
    private val listener = MemoryTrimListener { level ->
        if (level != MemoryTrimLevel.RUNNING_LOW &&
            level != MemoryTrimLevel.RUNNING_CRITICAL &&
            level != MemoryTrimLevel.COMPLETE
        ) return@MemoryTrimListener
        if (!opened.get() || !scheduled.compareAndSet(false, true)) return@MemoryTrimListener

        // Lease the writer through Room. Its onOpen connection may be in use or already closed.
        scope.launch {
            try {
                db.useWriterConnection { writer ->
                    writer.usePrepared("PRAGMA shrink_memory") { it.step() }
                    writer.usePrepared("PRAGMA incremental_vacuum") { statement ->
                        while (statement.step()) { /* Drain the pragma result. */ }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FlashLog.w("DATABASE", "Desktop database trim failed: ${e.message}")
            }
        }.invokeOnCompletion { scheduled.set(false) }
    }

    init {
        MemoryGovernor.registerListener(listener)
    }

    override fun close() {
        MemoryGovernor.unregisterListener(listener)
        scope.cancel()
    }
}
