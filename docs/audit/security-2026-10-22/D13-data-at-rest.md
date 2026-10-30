# Domain D13 — Data at Rest & Local Persistence

## 1. Domain Overview
Domain D13 audits local data persistence across Android and Desktop platforms. This encompasses Android Room with Zetetic SQLCipher (`FlashDatabaseOpener`, `KeystorePassphraseProvider`, `PassphraseKeeper`), Desktop SQLite with SQLite3MultipleCiphers (`JvmFlashDatabaseOpener`, `JdbcCipherSQLiteDriver`, `ChatDbKeyStore`), Android backup rules (`data_extraction_rules.xml`, `backup_rules.xml`), SQLite Write-Ahead Logging (`-wal`, `-shm`), search indexing (`MessageDao`, `RecentSearchDao`), diagnostic logging (`FlashLog`), data retention policy (`RetentionPolicy`), and in-memory cryptographic secret hygiene.

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`

Files examined:
- `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherSQLiteDriver.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabase.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/RecentSearchDao.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/retention/RetentionPolicy.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/KeystorePassphraseProvider.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/PassphraseKeeper.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileStorage.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/swarm/AndroidPieceStorage.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/ChatDbKeyStore.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/res/xml/backup_rules.xml`
- `desktop/build.gradle.kts`

---

## 2. Findings Ledger

| ID | Title | Severity | CWE | CVSS v3.1 | Attacker Model | Status |
|---|---|---|---|---|---|---|
| `SEC-D13-01` | Plaintext chat message bodies leaked to unencrypted desktop log file (`desktop.log`) | High | CWE-532 | 7.1 | A6, A7 | CONFIRMED |
| `SEC-D13-02` | Android Auto Backup exfiltrates enterprise hub files and swarm pieces to unencrypted cloud storage | Medium | CWE-200 | 5.5 | A7, A4 | CONFIRMED |
| `SEC-D13-03` | Dead retention policy leaves chat and transfer history unpruned indefinitely despite user-configured retention window | Low | CWE-459 | 3.3 | A7 | CONFIRMED |
| `SEC-D13-04` | JVM `-XX:+HeapDumpOnOutOfMemoryError` dumps plaintext database passphrases and private keys to working directory | Low | CWE-312 | 3.3 | A6, A7 | CONFIRMED |

---

## 3. Detailed Findings

### SEC-D13-01: Plaintext Chat Message Bodies Leaked to Unencrypted Desktop Log File
- **File & Line:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1621,1625`
- **Attacker Model:** A6 (Other app or other local user on the same device) / A7 (Thief / forensic examiner / disk holder)
- **CWE:** CWE-532: Insertion of Sensitive Information into Log File / CWE-312: Cleartext Storage of Sensitive Information
- **CVSS v3.1 Score:** 7.1 (`CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N`)
- **Vulnerability Description:**
  In `DesktopEngine.kt`, the callbacks for inbound text messages contain diagnostic log statements:
  ```kotlin
  onInboundTextMessage = { conversationId, senderName, text ->
      FlashLog.i(TAG_WS, "Inbound chat in $conversationId from ${senderName ?: "?"}: ${text.take(120)}")
      onInboundMessageNotification?.invoke(conversationId, senderName, text, null)
  },
  onInboundTextMessageWithGroupTitle = { conversationId, senderName, text, groupTitle ->
      FlashLog.i(TAG_WS, "Inbound chat in $conversationId from ${senderName ?: "?"} (group $groupTitle): ${text.take(120)}")
      onInboundMessageNotification?.invoke(conversationId, senderName, text, groupTitle)
  },
  ```
  These statements write the first 120 characters of every incoming chat message to `FlashLog.i(TAG_WS, ...)`.
  In `DesktopMain.kt:520-547`, `installDesktopLogSink()` directs all `FlashLog` entries to `<stateDir>/desktop.log` (default `~/.flash/desktop.log` or `~/.local/share/flash/desktop.log`).
  On Linux, `desktop.log` is created with world-readable permissions (`0644`, see `SEC-D12-03`). On Windows, it is readable by any local unprivileged process running under the user.
  This completely defeats at-rest encryption of the SQLite database: even though the database is encrypted with ChaCha20/AES via `SQLite3MultipleCiphers`, plaintext message excerpts are recorded permanently in cleartext in the log file.
- **Proof of Concept:**
  1. Peer sends a confidential 1:1 message: `"Secret meeting passcode: 994821"`.
  2. The message arrives and is stored in encrypted `flash.db`.
  3. However, `desktop.log` contains:
     `14:32:01.120 I/WS: Inbound chat in conv-42 from Alice: Secret meeting passcode: 994821`
  4. An adversary or local process inspects `~/.flash/desktop.log` and reads the message.
- **Guard Analysis:** No redaction or sanitization is performed on `text`. The 120-character substring preserves almost all typical short text messages in full.
- **Remediation:**
  Remove message text logging entirely from `DesktopEngine.kt:1621,1625`. Log only metadata:
  `FlashLog.i(TAG_WS, "Inbound chat in $conversationId from ${senderName ?: "?"} (length=${text.length})")`

---

### SEC-D13-02: Android Auto Backup Exfiltrates Enterprise Hub Files and Swarm Pieces to Cloud Storage
- **File & Line:** `app/src/main/res/xml/data_extraction_rules.xml:18-25`, `app/src/main/res/xml/backup_rules.xml:8-12`, `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileStorage.kt:45-50`, `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/swarm/AndroidPieceStorage.kt:23-28`
- **Attacker Model:** A7 (Cloud backup access / compromised Google account) / A4 (Enterprise data compliance)
- **CWE:** CWE-200: Exposure of Sensitive Information to an Unauthorized Actor / CWE-538: Insertion of Sensitive Information into Externally-Accessible File or Directory
- **CVSS v3.1 Score:** 5.5 (`CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N`)
- **Vulnerability Description:**
  In `data_extraction_rules.xml`:
  ```xml
  <cloud-backup>
      <exclude domain="sharedpref" path="." />
      <exclude domain="database" path="." />
      <exclude domain="device_sharedpref" path="." />
      <exclude domain="device_database" path="." />
      <exclude domain="external" path="." />
      <exclude domain="file" path="logs" />
  </cloud-backup>
  ```
  The rule explicitly excludes `sharedpref`, `database`, `external`, and `file path="logs"`.
  However, in `DiskHubFileStorage` (`HubFileStorage.kt:45`), downloaded enterprise organization files and pending download parts are stored under `context.filesDir/hub-files` (`done/<fileId>.bin`, `down/<fileId>.part`). Similarly, swarm partial pieces are stored under `context.filesDir/swarm/partial/<key>.part` (`AndroidPieceStorage.kt:23`).
  Because `hub-files` and `swarm` reside within `context.filesDir` (the `file` domain) and are NOT excluded in `data_extraction_rules.xml` or `backup_rules.xml`, Android Auto Backup automatically uploads downloaded enterprise documents and partial files to the user's personal Google Drive backup storage.
- **Proof of Concept:**
  An employee enrolls their personal Android phone with their employer's Flash Enterprise Hub and downloads a confidential company strategy PDF. Android OS automatically runs an overnight Auto Backup to the employee's personal Google account, copying `context.filesDir/hub-files/done/<id>.bin` to Google Cloud servers.
- **Guard Analysis:** Only `logs` was excluded under domain `file`. Enterprise files and swarm parts were omitted from the exclusion list.
- **Remediation:**
  Update `data_extraction_rules.xml` and `backup_rules.xml` to explicitly exclude `hub-files` and `swarm`:
  ```xml
  <exclude domain="file" path="hub-files" />
  <exclude domain="file" path="swarm" />
  ```
  Alternatively, set `android:allowBackup="false"` or store temporary/cached downloads in `context.noBackupFilesDir` or `context.cacheDir`.

---

### SEC-D13-03: Dead Retention Policy Leaves Chat and Transfer History Unpruned Indefinitely
- **File & Line:** `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/retention/RetentionPolicy.kt:57-86`, `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/settings/FlashSettingsDataStore.kt:75`
- **Attacker Model:** A7 (Forensic examiner / device seizure)
- **CWE:** CWE-459: Incomplete Cleanup / CWE-1059: Incomplete Documentation
- **CVSS v3.1 Score:** 3.3 (`CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:L/I:N/A:N`)
- **Vulnerability Description:**
  `FlashSettingsDataStore.Keys.retentionDays` exposes a user setting for message retention, defaulting to 365 days. `RetentionPolicy.kt` implements the pruning calculation logic (`cutoffMsOrNull`, `eligibleForDeletion`) and declares the `PrunableSource` interface.
  However, investigation reveals that `RetentionPolicy` has **zero production invocations** across the entire codebase. There is no `WorkManager` worker, background task, or startup sweep that queries messages or transfer chunks older than `retentionDays` and purges them from `FlashDatabase`.
  As a result, all messages, receipts, and transfer metadata are retained forever, giving users a false sense of security regarding automatic history expiration.
- **Proof of Concept:**
  A user sets `retentionDays` to 30 days in Settings, expecting sensitive communications older than one month to be permanently deleted. Two years later, a forensic examiner acquires the device and discovers all messages from the preceding two years remain fully intact in the database.
- **Guard Analysis:** No worker exists to execute pruning.
- **Remediation:**
  Implement a scheduled background pruning worker (e.g. `RetentionPruneWorker` via Android `WorkManager` and desktop startup coroutine) that calls `RetentionPolicy.eligibleForDeletion` on `MessageDao` and `TransferDao` and deletes expired, unpinned entries.

---

### SEC-D13-04: JVM `-XX:+HeapDumpOnOutOfMemoryError` Dumps Plaintext Database Passphrases and Private Keys to Working Directory
- **File & Line:** `desktop/build.gradle.kts:153`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/ChatDbKeyStore.kt:60`
- **Attacker Model:** A6 (Other app or other local user on the same device) / A7 (Forensic examiner)
- **CWE:** CWE-312: Cleartext Storage of Sensitive Information / CWE-200: Exposure of Sensitive Information to an Unauthorized Actor
- **CVSS v3.1 Score:** 3.3 (`CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N`)
- **Vulnerability Description:**
  In `desktop/build.gradle.kts:153`:
  `jvmArgs += listOf("-Xmx1g", "-XX:+HeapDumpOnOutOfMemoryError")`
  If the desktop JVM encounters an OutOfMemoryError, the JVM dumps the complete 1 GB heap to disk (`java_pid<pid>.hprof`) in the process's working directory.
  Because cryptographic secrets (including the 64-hex database passphrase in `ChatDbKeyStore.passphrase(): String`, unsealed session keys in `DesktopTrustStore.sessionKeys`, and identity private keys) are stored in the JVM heap as immutable `String` and `ByteArray` objects, an unencrypted dump of heap memory written to disk exposes all keys in cleartext.
- **Proof of Concept:**
  Flash Desktop crashes with an OutOfMemoryError. A 1 GB `java_pid1234.hprof` dump is created in the working directory. A local user runs `strings java_pid1234.hprof | grep -E "^[0-9a-f]{64}$"` to recover the chat database passphrase.
- **Guard Analysis:** `-XX:+HeapDumpOnOutOfMemoryError` was added as a developer tuning aid for memory analysis, but was left enabled for production desktop packaging.
- **Remediation:**
  Remove `-XX:+HeapDumpOnOutOfMemoryError` from production release configurations in `desktop/build.gradle.kts` (or restrict it to debug / test executions).

---

## 4. Refuted / Rejected Hypotheses

### REJ-D13-01: Unencrypted Android SQLite Database Fallback
- **Hypothesis:** When SQLCipher initialization fails or the keystore passphrase cannot be unwrapped, Room falls back to an unencrypted plaintext database.
- **Refutation:** `FlashDatabaseOpener.openEncrypted()` strictly passes `SupportOpenHelperFactory` to Room. If the passphrase cannot be resolved or SQLCipher native library fails, an exception is thrown and database initialization halts. The unencrypted fallback (`openInMemory`) is confined to unit tests (`fallbackToDestructiveMigration(true)` is only in `openInMemory`).

### REJ-D13-02: Unencrypted Full-Text Search (FTS) Auxiliary Tables
- **Hypothesis:** Full-text message search creates unencrypted auxiliary shadow tables or an unencrypted external Lucene/FTS index.
- **Refutation:** Search is performed via SQL `LIKE` queries (`MessageDao.searchMessages` and `searchConversationMessages`) directly on the `messages` table inside `FlashDatabase`. Recent searches are stored in the `recent_searches` table inside the same encrypted database. No external unencrypted search index exists.

### REJ-D13-03: SQLite Write-Ahead Logging (WAL) Plaintext Page Leakage
- **Hypothesis:** SQLite Write-Ahead Logging (`-wal`) leaks unencrypted database pages to disk.
- **Refutation:** Both Zetetic SQLCipher (Android) and SQLite3MultipleCiphers (Desktop) operate at the SQLite pager level above the OS filesystem VFS. All pages written to `-wal` are fully encrypted with the database key and per-page initialization vectors. The `-shm` shared memory file contains only integer page indexes and hash tables, not page payloads.
