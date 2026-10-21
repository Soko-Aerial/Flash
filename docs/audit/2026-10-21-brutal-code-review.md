# Brutal Code Review

> **Fix status (2026-10-21, `dev`, uncommitted until the owner says otherwise; unit-tested, NOT device-verified):**
> F1 fixed (a text hit with a non-matching attachment gives one message hit, no file hit; test in `RealFlashChatRepositoryTest`).
> F2 pinned by `everyRouteDeclaredOrgRefusesADepartmentHead` (no live escalation found; `AdminRoutePolicies` KDoc now says it is a declaration, not enforcement).
> F3 fixed (`AdminAuthService.allowUnbackedSessions`, default false in production).
> F4 fixed (`FileStore.create` refuses a group file from a non-member).
> F5 fixed (`IdentityStore.activeDeviceIds`; head overview no longer capped at 500 rows).
> F8 fixed (desktop `ReceivedFilesManifest`: Clear deletes only what Flash wrote; the default `~/FlashReceived` root still clears fully; files received into a user-picked folder before this change are no longer cleared).
> F9 fixed (the pooled buffer is always copied and released; pinned by a pool-reuse test, mutation-checked).
> F10 comment corrected and shared frame fixtures run on both transports.
> **Not done:** F6 (needs the owner's decision: account-only lockout or IP rate limit), F7 (measure on devices first, EXP entry owed), section 8 SQL simplifications, INVESTIGATE 1.
> Pre-existing, not from this work: `WsFlashNetworkTest.testRedialLoopFollowsADiscoveredRouteAfterTheOldOneWentStale` fails on the baseline commit as well.

Scope: `dev` at `51469a842c3eccc822d8bb9def9442257318aed6` (1 commit ahead of `origin/dev`), reviewed 2026-10-21 in two passes: a security-first pass over the highest-risk paths, then a module-by-module pass (coverage table in §1). Areas actually read: `server/` (auth, admin API + console guard, client-address/CSRF, `FileStore`, `FileRoutes`, `RelayAuth`, `AnnouncementStore` + routes, `Main`/healthcheck), `core/enterprise` (`HubFileTransfers`, `HubFileSealing`, `KtorRelayTransport`), `core/security` (`PlatformCrypto` both actuals, `FlashCrypto`, `Hkdf`, `HubSealing`, `GroupInviteCodec`), `core/hub-protocol` (`AuthCanonicalWriter`, `RelayFrameCodec`, `HubJson`, `HubContract`), `core/messaging` (search path, `HubFileFrameCodec`, `FlashTextFraming`), `core/persistence` (schema steps, DB opener, search SQL), `core/network` (`WebSocketCodec` both copies, `ByteArrayPool`), `core/transfer` (`ResumeBitVector`, `MultiStreamDispatcher`, `Chunker`), `core/engine` (`FlashPathSanitizer`, `HubFileBinding`), `core/swarm` (`ServePolicy`), `core/radio` (`AndroidRadioDiscovery`), `app` (`MainActivity`, `FlashLogContext`), `desktop` (`DesktopHelpers`, `DesktopEngine`, `DesktopShell`), `ui/chat` (search scopes, settings storage card), `gradle/libs.versions.toml`.

Not read: `core/calling` beyond one method, `core/ptt`, `ui/callui`, `ui/theme`, `ui/platform-shims`, `server/relay/RelayFrameRouter` internals, the admin console's 44 JS files (beyond an `innerHTML` sweep), `tools/`, `third_party/` (vendored), and the uncommitted hub-UI redesign (a second agent is editing it).

Stack detected: Kotlin Multiplatform (Kotlin 2.2.10, AGP 9.3.1, Compose BOM 2025.12.00, Room 2.8.4, SQLCipher, Ktor server, SQLite), Gradle with a version catalog, vanilla-JS admin console, JUnit/kotlin-test.
Tools run: `tools\flash-gradle.cmd :ui:chat:compileKotlinJvm` — failed at 07:14 on a mid-edit `FlashHubMotion.kt` (another agent), **green on re-run at 07:29**. Consequently the working tree's build state is not a finding; it is recorded only in this line. No tests were run (a second agent holds the build lock; `:server:test` takes ~3.5 min and would have contended).
Line numbers: every `file:line` below refers to commit `51469a84` (the reviewed state). The working tree has since been edited by another agent — `RealFlashChatRepository.kt`, `DesktopShell.kt`, `MainActivity.kt`, `FlashChatSearchResults.kt` and others are dirty — so locate the quoted code, not the number.

## 1. Verdict

The code is far above average for a project this size: the security boundaries I traced (TOTP replay, PBKDF2 with upgrade path, CSRF triple layer, proxy/XFF handling, nonce construction, canonical signing, path sanitisation, quota transactions) are deliberate and correct, and I could not break them from the code. The two HIGH findings are of different kinds. **F8 can destroy data**: on Windows, Settings → Storage → "Clear received files" recursively deletes every child of whatever folder the user picked as Save location, so a user who points that at `Downloads` loses the folder, not just the received files. **F1 returns wrong answers** in the newest feature: every text hit that carries an attachment also emits a file hit for a name that did not match, and that reaches the Files and Media scopes directly. Beyond those: a declared-but-unenforced authorization policy table (F2), a deliberate pooling optimisation that silently stops working on exactly the frames it targets (F9), duplicated host transports with no drift check behind a comment that is already false (F10), and small correctness/DoS edges. **Nothing found here is a breach of confidentiality or of wire-protocol integrity.**

### Round 2 scope (module by module, 2026-10-21)

24 modules, ~312k lines. Read means the files named were opened and read; "spot" means a targeted grep plus one context read.

| Module | Lines | Read in round 2 | Verdict |
|---|---|---|---|
| `core/security` | 9.7k | `PlatformCrypto` (both actuals), `FlashCrypto`, `Hkdf`, `HubSealing`, `GroupInviteCodec`, `HubFileSealing` (R1) | sound: length-prefixed canonical writer, 12-byte nonce enforced on open, AAD binds sender/recipient/msgId/time, `verify` never throws |
| `core/hub-protocol` | 4.2k | `AuthCanonicalWriter`, `RelayFrameCodec` (decode + all validators), `HubJson`, `HubContract`, `HubErrorCodes` | sound: strict JSON, 4-byte length prefixes (no concat ambiguity), size caps checked before allocation |
| `core/common` | 4.4k | `Base64`, `Base64Url`, `ByteArrayPool`, `FlashTextFraming`, `Hkdf` seam | **F9** |
| `core/persistence` | 6.8k | `FlashDatabaseOpener`, `FlashMigrations`, `FlashSchemaSteps` (all 19 steps, every `ALTER`), `MessageDao` search SQL, `FlashJvmMigrations` | sound: no destructive fallback outside `openInMemory`, every `NOT NULL` add has a DEFAULT, no `INSERT…SELECT` data move |
| `core/network` | 20k | `WebSocketCodec` android **and** jvm in full (frame header, payload, MessageReader, readFully), `ByteArrayPool` usage, `WsConnection` binary path | **F9**, **F10** |
| `core/transfer` | 10.4k | `ResumeBitVector` (all), `MultiStreamDispatcher.send`/`awaitUnpause`, `Chunker` bounds | sound: padding-bit masking, popcount kept consistent, `coerceIn(1, size)` guarded by an early return |
| `core/messaging` | 35.9k | `RealFlashChatRepository.searchChats`/`escapeSqlLike`, `HubFileFrameCodec`, `FlashSearchText`, `FlashChatSearchResultsUi` | **F1** (round 1) |
| `core/enterprise` | 12.6k | `HubFileTransfers` (upload, download, verify, retry, `locked`), `KtorRelayTransport` (dispatch, overflow policy), `RelayAuth` | sound: delivery overflow closes the session so the hub replays; drops only affect acks/meta |
| `core/engine` | 11.8k | `FlashPathSanitizer`, `HubFileBinding` (download + `place`) | sound: `..`/reserved-name/illegal-char handling is centralised and tested |
| `core/swarm` | 11.3k | `ServePolicy` | sound (one unproven edge noted, not reported) |
| `core/radio` | 11k | `AndroidRadioDiscovery` (pair + `openLink`) | sound: the 25 s blocking SDP attempt is wrapped in `withContext(Dispatchers.IO)` |
| `core/calling` | 16.4k | `FlashGroupCallSession.closeLegLocked` (spot) | not cleared — one method only |
| `server` | 39k | R1 set + `FileRoutes`, `RelayAuth`, `ClientAddress`, `AdminWeb`/`CsrfGuard`, `AnnouncementStore` visibility, `ConsoleScope` | **F2–F6**; relay router internals not read |
| `app` | 16.4k | `MainActivity` (log export, storage scan, restart), `FlashLogContext` | sound: `runBlocking` confined to `Dispatchers.IO` |
| `desktop` | 15.3k | `DesktopHelpers` (received storage), `DesktopEngine` (root/save location), `DesktopShell` (storage card + picker) | **F8** |
| `ui/chat` | 58k | `FlashSearchScope`/`FlashSearchResultMath`, `ClearReceivedFilesDialog`, settings search list | **F8** consumer; search math sound |
| `core/calling`, `core/ptt`, `ui/callui`, `ui/theme`, `ui/platform-shims`, `server/relay/RelayFrameRouter`, `tools`, admin-ui JS | ~28k | grep-level over 127 files: no `TODO()`, no `NotImplementedError`, no `UnsupportedOperationException` throw in production; two empty `catch (_: Throwable) {}` blocks found, both in teardown (`FlashGroupCallSession.kt:1609-1611` closing a peer connection, `:2737-2739` releasing the native local stream) where swallowing is correct. `RelayFrameCodec`/`RelayAuth`/relay client read, the router itself not. | **not cleared** — a grep is not a review |

## 2. Critical Issues

### [F1] [HIGH] Global search emits a "file hit" for every attachment of a text-matching message

- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6567-6599` (sink at 6586)
- **Evidence:** both predicates are computed, one is discarded for files:

  ```kotlin
  val textMatches = FlashSearchText.contains(row.text, trimmed)                      // 6567
  val fileName = row.attachmentName?.takeIf { it.isNotBlank() }                      // 6568
  val nameMatches = fileName != null && FlashSearchText.contains(fileName, trimmed)   // 6569
  if (!textMatches && !nameMatches) continue                                         // 6570
  if (textMatches) { messageHits += ... }                                            // 6574
  if (fileName != null) {                                                            // 6586  <-- should be nameMatches
      fileHits += FlashFileSearchHitUi(fileName = fileName, ...)
  }
  ```

  `nameMatches` is used only at 6570 and never for the file hit itself.
- **Why it matters:** the single Room query returns rows matching `text` **or** `attachmentName` (`core/persistence/.../dao/MessageDao.kt:232-238`), so the two hit lists must be split by which predicate matched. As written, a message whose text matches but whose attachment does not still produces a **file** result carrying that unrelated name. Search `release` in a chat that says "ship the release build" and has `Budget_Q4.pdf` attached: `Budget_Q4.pdf` is listed as a file hit. The Files/Media scopes and their counters are built straight from `fileHits` (`ui/chat/.../FlashSearchScope.kt:113-115,135-139`), so the pollution is what the user sees, and in the null-safe `All` view it inflates the result count.
- **Fix:** `if (nameMatches) { fileHits += ... }` (with a local `val name = fileName ?: continue` if you need the smart cast). Keep `continue` at 6570 as is.

### [F8] [HIGH] Desktop "Clear received files" deletes every child of the user-chosen save folder

- **Location:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt:184-193`; reached from `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:2088-2090`; folder chosen at `DesktopShell.kt:2063-2073`; mount resolved at `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:907`.
- **Evidence:**

  ```kotlin
  // DesktopHelpers.kt:184
  fun clearReceivedFiles(engine: DesktopEngine) {
      val root = engine.receivedDirectory
      if (!root.exists()) return
      root.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }   // 192
  }
  ```

  `receivedDirectory` is not an app-private folder: `DesktopEngine.kt:907` builds it from `receivedRoot ?: File(settingsStore.loadSettings().saveLocation)`, and that setting is written by a **folder picker the user drives** (`DesktopShell.kt:2063-2073`: `onPickSaveLocation` → `engine.updateSettings { it.copy(saveLocation = selectedFile.absolutePath) }`, applied live at `DesktopEngine.kt:243-245`). Flash's own writes go into a per-transfer subdirectory (`DesktopEngine.kt:1793`: `File(canonicalRoot, safeId)`), which is exactly what the clear must remove — and what it does not distinguish from anything else in the folder.
- **Why it matters:** a "Save location" setting invites the user to pick `Downloads`, `Desktop` or `Documents`. After that, Settings → Storage → **Clear received files** recursively deletes the *entire* contents of that folder, including files Flash never saw. The clear is confirmed first (`ui/chat/.../FlashSettingsScreen.kt:555-564`), so this is one deliberate tap — but the confirmation says the opposite of what it does: `FlashSettingsScreen.kt:1699-1701`, *"Permanently delete $amount from Flash's received-files storage? This clears all received files and cannot be undone."* The `$amount` in that sentence is `model.receivedFilesBytes`, i.e. the whole-folder walk (`DesktopHelpers.kt:208-213`, `walkTopDown().filter { it.isFile }`), so a user who points the setting at a full `Downloads` sees one enormous number and no hint that unrelated files are in scope. Severity is HIGH rather than CRITICAL because it needs a non-default setting plus a confirmed tap; it is not a silent background action.
- **Fix:** delete only what the receive pipeline created — the per-transfer directories under the root (the `<root>/<safeId>/…` layout at `DesktopEngine.kt:1793`), not `root.listFiles()`; refuse (or warn explicitly with the folder path and a count of non-transfer entries) when the root contains anything else. Additionally, validate a chosen save location at pick time: accept a new/empty folder, or a folder that is already Flash's root, and reject/warn for one with foreign content.
- **Same class on Android:** no — and the Android host shows the intended discipline. `MainActivity.kt:610-621` (`deleteReceivedFilesInsideOwnedRoot`) walks `DiscoveryEngineHolder.receivedFilesRoot(this)` — an app-owned path that is never user-picked — and re-checks containment per entry (`check(canonical.path.startsWith(rootPrefix))`, throwing rather than deleting when a path escapes). The desktop clear has neither the owned-root guarantee nor the containment check.

## 3. Bugs Found

### [F5] [LOW] Department-head overview undercounts devices and reports only the ACTIVE state

- **Location:** `server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt:372` (used at 377-378)
- **Evidence:** `val devicesInScope = d.identities.listDevices(DeviceStatus.ACTIVE, limit = 500).filter { inScopeDept(it.department) }`, then `onlineDevices = devicesInScope.count { ... }` and, for a head, `devicesByStatus = mapOf(ACTIVE.name to devicesInScope.size.toLong())`.
- **Why it matters:** the 500-row cap is applied **before** the department filter, so in an organisation with more than 500 ACTIVE devices a head's "devices"/"online now" tiles silently stop counting (and the subset that survives the cap depends on the query's ordering). The tile then reads a number that is wrong but looks authoritative; the console's own Devices list would show a different total.
- **Fix:** pass the department into `listDevices` (`effectiveDept`, as the devices list route does at `AdminApi.kt:415-419`) instead of filtering up to 500 rows in memory, or use a `COUNT(*)` query.

## 4. Security Issues

### [F2] [MEDIUM] `AdminRoutePolicies` is a comment, not a control; the test that "enforces" it checks the table against itself

- **Location:** `server/src/main/kotlin/com/transfer/flash/server/admin/ConsoleScope.kt:48-136`, `server/src/test/kotlin/com/transfer/flash/server/admin/ConsoleScopeTest.kt:26-37`
- **Evidence:** the table declares `ORG` / `OWN` / `SELF` per route and its KDoc says "default deny: a route without an entry fails `AdminRoutePolicyTest`". The only consumer of `policyFor` in the whole module is `ConsoleScopeTest.everyAdminRouteHasADeclaredPolicy`, which iterates the real router's routes and asserts `AdminRoutePolicies.policyFor(method, template) != null`. Authorization itself is hand-written per handler (`call.requireOrg(d.scopeOf(session))` at `AdminApi.kt:472,488,651,666,680,698,704,717,737,757,795,862` and 16 sites in `AdminApiDirectory.kt`).
- **Why it matters:** the test proves only that a *row* exists, never that the row's promise is implemented. A new `ORG` route added with the table entry and no `requireOrg` call passes both the table and the test, and a department head gets an org-admin action. I read the `ORG` handlers in `AdminApi.kt` and found each one guarded, so I cannot show a live escalation today — this is the trap being pre-armed, and the "default deny" claim in the KDoc is false.
- **Fix:** make the table executable — wrap each admin route in a helper that resolves the session, reads the policy for the matched template, and applies `SELF`/`OWN`/`ORG` before the handler body — or, if that refactor is too large for now, add a test that calls every `ORG` route with a `DEPT_HEAD` session and asserts 403, and correct the KDoc so nobody trusts the table as an enforcement point.

### [F3] [LOW] Production session validation accepts a "test principal" when the hub has no administrators

- **Location:** `server/src/main/kotlin/com/transfer/flash/server/admin/AdminAuthService.kt:509` and `:518-519`
- **Evidence:** `val stillAdmin = ... || isTestPrincipal(session.username)` with `isTestPrincipal(username) = loadAdmin(username) == null && !hasAdmins()`.
- **Why it matters:** a session row whose administrator no longer exists is normally killed; here it survives while `admin_users` is empty. Reaching it requires a session row already in the database (tokens are 256-bit random and only `createSession` writes rows), so I cannot construct an external exploit — but it is a test seam wired into the production validator, and an admin-row deletion path added later (or a database restored from a test fixture) turns into a valid session with no owner and no audit subject.
- **Fix:** gate it behind an explicit constructor flag defaulted off in `HubServices`, e.g. `allowUnbackedSessions = false`, and have the tests that need it pass `true`.

### [F4] [LOW] A group file can be created by a device that is not a member of the group

- **Location:** `server/src/main/kotlin/com/transfer/flash/server/files/FileStore.kt:82-88` (creation) vs `:534-539` (read gate)
- **Evidence:** creation resolves the group and takes every member except the sender:

  ```kotlin
  val members = groupId?.let(groupMembers) ?: return ... UNKNOWN_RECIPIENT
  val rest = (members.toSet() - sender).toList().sorted()
  ```

  There is no `sender in members` check. Reads, by contrast, re-check the live roster for anyone who is not the sender (`:534-539`).
- **Why it matters:** any enrolled device that learns a group id (the hub knows it; a former member certainly does) can create a hub file addressed to that group, which inserts a `file_recipient` row per current member. That is a spam/notification surface and it consumes the sender's quota, and it makes the group's member list a live oracle for a removed device. The recipient still needs the key from a sealed 1:1 ticket to read anything, so this is not confidentiality.
- **Fix:** refuse creation unless `sender in members` (the branch already has the list), and add a test with a group whose roster does not contain the caller.

### [F6] [LOW] A failed-login lockout keyed on the client address locks every user behind that address

- **Location:** `server/src/main/kotlin/com/transfer/flash/server/admin/AdminAuthService.kt:357-361, 426-453`
- **Evidence:** `reserve(listOf(accountKey, ipKey))` runs before any secret is checked and locks both keys after 5 failures (`:442-450`); `ipKey` is the `/64`-folded client address (`AdminAuthService.kt:357`, `RateKeys.normalise` in `server/.../auth/ClientAddress.kt:117-130`). A locked key returns `AuthResult.Locked` at `:361`, before `verifyPassword`.
- **Why it matters:** five wrong passwords from anywhere behind the same NAT/office egress (or the same IPv6 /64) deny the **real** administrator a login for 15 minutes, and re-offending keeps the console shut. On a hub whose only admin console is the break-glass path, that is a cheap denial of administration. The code is deliberate about this (`SEC-10`), so treat it as a policy decision rather than a coding slip.
- **Fix (choose one, with the owner):** keep the account lockout, drop the IP-keyed *lock* to a rate limit only, or raise the IP threshold well above the account threshold so a single account cannot lock the address.

## 5. Performance & Resource Concerns

### [F9] [LOW] A pooled WebSocket frame buffer escapes the codec, so the pool drains on exactly 16 KB / 32 KB frames

- **Location:** `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt:224-233` (the fast path returns the payload) and `:362-376` (the payload may *be* a pool buffer); `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/ByteArrayPool.kt:38-51,57-70`
- **Evidence:** `readFramePayload` acquires a pool buffer for `length <= 32 KB` and, when the frame is *exactly* the pool size, returns that very array instead of a copy:

  ```kotlin
  val pooled = ByteArrayPool.acquire(length)          // 363  -> a 16 KB or 32 KB array
  readFullyInto(input, pooled, 0, length)             // 364
  ...
  if (pooled.size == length) pooled else { val exact = pooled.copyOf(length); ByteArrayPool.release(pooled); exact }  // 370-376
  ```

  `MessageReader.read` then returns it to the caller for binary frames (`:231-233`), and **nothing outside this file calls `ByteArrayPool.release`** — the only six `release` call sites are the codec's own (202, 213, 218, 229, 238, 242, 374). So every binary frame whose length is exactly 16 384 or 32 768 bytes permanently removes one buffer from a pool capped at `MAX_POOL_CAPACITY = 8`.
- **Why it matters:** `core/transfer/.../chunked/Chunker.kt:83` sets `MIN_CHUNK_SIZE_BYTES = 16 * 1024`, and adaptive sizing drops to exactly that on a slow link — so on the slow-device case the pool exists for, the first eight 16 KB frames empty it and every later frame allocates a fresh 16 KB array in Android's large-object space. That is the allocation churn `ByteArrayPool`'s KDoc says it was built to prevent on the BelFone SCP810 ("triggers severe GC churn and pauses on low-memory devices").
- **Fix:** make ownership explicit and documented: either `copyOf` before returning (keeping the pool for the codec) or give the binary message a `release()` that the single receive-path consumer calls once it is done, and document who owns the array in `Message.Binary`.
- **Benchmark required** for the size of the effect (allocation count and GC pauses per transferred megabyte on a slow-chunk path).

### [F7] [LOW] Hub transfers can touch the payload three times; upload seals every chunk twice

- **Location:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt:435-445` (`hashSource`), `:337-345` (PUT loop seals again), `:599-614` (`verify`)
- **Evidence:** `hashSource()` loops every chunk through `sealedChunkBlocking` to learn the ciphertext hash; `body()` then calls `sealedChunk(index)` for every chunk to send it. On download, `verify()` reads the assembled part file chunk by chunk and **re-seals each chunk** with AES-GCM to compare against `ticket.cipherSha256`. The KDoc at 435 and 597 both acknowledge "O(file) reads".
- **Why it matters:** upload = 2 source reads + 2 AES-GCM passes; download = N reads + 1 write + 1 read + 1 AES pass. On mid-range phones and a 50 MB file this is seconds; on a 500 MB file plus a phone's storage it is the difference between "network-bound" and "storage/CPU-bound", and it is exactly the class of cost AGENTS §23 says must be measured, not assumed.
- **Benchmark required.** Measure on one phone + one desktop: wall time and CPU for a 50 MB and a 500 MB hub upload and download, with the sealing passes instrumented (probe timings around `hashSource`, the PUT loop and `verify`), and compare against LAN-direct for the same file. If `hashSource` dominates, stream the hash from the same read as the PUTs (seal once, keep a bounded ring of sealed chunks) instead of a separate pass.

## 6. Dependency Concerns

None found. `gradle/libs.versions.toml` pins every version and documents the non-obvious ones (Compose Multiplatform 1.9.3 held below Kotlin 2.2.10's stdlib ceiling; `jmdns` pinned by decision; `jcodec` BSD-2-Clause verified). No `-SNAPSHOT`, no dynamic versions, no lockfile drift observed. I did not audit transitive CVE status — that needs a scanner, not a reader.

## 7. Dead / Inert / Duplicate / Useless Code

| Location | What is inert | Proof |
|---|---|---|
| `server/.../admin/ConsoleScope.kt:53-136` | `AdminRoutePolicies.ALL` / `policyFor` have no production consumer | only caller is `ConsoleScopeTest.kt:34`; see F2 (not repeated as a separate finding) |
| `server/.../admin/AdminApi.kt:358` | `|| (scope is ConsoleScope.Org)` is unreachable | `ConsoleScope.Org.allows()` already returns `true` for every input (`ConsoleScope.kt:34`); NIT, no behaviour change |

### [F10] [LOW] The duplicated host transports have no drift check, and the comment that stands in for one is false

- **Location:** `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt:14-16` (the claim) vs `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt:361-376` (what the Android copy actually does); line counts of the pairs: `WebSocketCodec` 381/353, `WsConnection` 412/415, `SecureSocketUpgrader` 276/279, `WsFlashNetwork` 1207/855.
- **Evidence:**
  - The JVM copy says: *"DESKTOP DUPLICATE (Phase 15-3) of the `androidMain` original - byte-identical apart from this note."* (`:14-15`).
  - The Android copy's `readFramePayload` acquires from `ByteArrayPool` and only copies when the frame is not exactly a pool size (`:362-376`). The JVM copy is seven lines with no pool, no `ByteArrayPool` import, and allocates per frame (`:355-364`). The Android copy's message-size check also runs against `maxMessageBytes` from `MessageReader` state; the JVM copy carries its own copy of that logic — they must be read side by side to know they still agree.
  - `WsFlashNetwork` is not a copy at all: 1207 lines on Android against 855 on the JVM. Whatever the reason, the "duplicate" framing does not describe it.
- **Why it matters:** duplication here is a documented decision (`D1 = Option B`, CONVENTIONS R5); the risk it creates is silent divergence on the transport path, where both hosts must produce identical wire behaviour. The mitigation in place is the comment, and the comment is already wrong. There is no cross-target test that could catch drift: these classes live in `androidMain`/`jvmMain`, so they cannot be referenced from `commonTest` — unlike the crypto seam, which has a behavioural parity suite that runs on both targets (`core/security/src/commonTest/.../PlatformCryptoParityTest.kt`). The transport duplicates have no equivalent.
- **Fix (cheap):** one `jvmTest`+`androidHostTest` pair that runs the *same* fixture table (a list of encoded frames → expected header/payload/opcode, including 16 384-byte and 32 768-byte payloads, a masked frame, a 127-length frame and a truncated frame) against each target's codec, so a divergence fails a test instead of a user. Then correct the comments to say what each copy does not share (`ByteArrayPool` on Android, none on the JVM).
- **Do not** try to merge the copies: D1 = Option B forbids a shared JVM tier, and that decision is the owner's (recorded in `docs/decisions.md`).

## 8. Overcomplicated Code & Simpler Alternatives

| Location | Current approach | Simpler alternative |
|---|---|---|
| `server/.../files/FileStore.kt:607-623` | `sumBytes(conn, where, vararg args)` splices a SQL `where` fragment passed as a `String` (today: `"sender_device_id = ?"`, `"1 = 1"`), then hand-numbers parameters from 3 | two plain prepared statements (per-sender, global). Keeps SQL out of string parameters entirely, which is the property that makes the injection review a one-minute job |
| `server/.../announcements/AnnouncementStore.kt:507-523` | `visibilityClause`/`filterClause` build SQL with `'${escapeSql(value)}'` interpolated literals | the same clause with `?` placeholders and bound values; `escapeSql` then disappears and cannot be reused wrongly |

Neither is a defect today (`escapeSql` doubles quotes and SQLite has no backslash escapes; `where` never receives user input). Both are the kind of thing that becomes a defect in someone else's hands.

## 9. Missing Tests

| Missing test | Why it matters | Suggested test location |
|---|---|---|
| Search where the **text** matches and the **attachment name** does not | This is exactly F1; the current fixture (`m1` name-match, `m3` text-only) passes with and without the fix | `core/messaging/src/androidHostTest/.../RealFlashChatRepositoryTest.kt:1177` |
| Group file created by a non-member device | F4; no test exercises `sender !in members` | `server/src/test/.../files/` (alongside `E2eHubFileTransferContractTest`) |
| Every `ORG` route called with a `DEPT_HEAD` session → 403 | F2; proves enforcement instead of table completeness | `server/src/test/.../admin/` |
| Head overview with >500 devices in the org | F5; the cap is invisible in small fixtures | `server/src/test/.../admin/` |
| `validateSession` with a session row whose admin was deleted | F3; the test-principal branch has no coverage | `server/src/test/.../AdminAuthServiceTest.kt` |
| Clear received files when the save folder holds a foreign file | F8; the existing desktop test (`DesktopReceivedStorageTest`) only clears a temp root Flash created itself, so the destructive case is untested | `desktop/src/test/.../DesktopReceivedStorageTest.kt` |
| Two consecutive 16 384-byte binary frames, asserting both payloads are intact and are not the same array instance | F9; no test pins who owns a pooled buffer, so a future "ownership fix" can silently alias two frames | `core/network/src/test/.../WebSocketCodecTest.kt` (both source sets) |
| The same frame fixture table run on the Android and the JVM codec | F10; the only thing that can catch drift between the duplicated hosts | new `commonTest`-style pair of suites (one per target, same fixtures) |

## 10. Recommended Fix Plan

1. **F8** — stop `clearReceivedFiles` from deleting a user-chosen folder's contents; only per-transfer directories. This is the only data-loss item in the report and should go first because it needs no owner decision beyond the wording of the warning. Verify `desktop/src/test/.../DesktopReceivedStorageTest.kt` plus a new case: a root containing a foreign file survives a clear.
2. **F1** — one-token change at `RealFlashChatRepository.kt:6586`, plus the regression test in §9. Verify by `tools\flash-gradle.cmd :core:messaging:testAndroidHostTest --tests "*RealFlashChatRepository*"` and by reading the new assertion: a text hit with a non-matching attachment must yield **one** message hit and **zero** file hits.
3. **F4** — add the `sender in members` refusal in `FileStore.create`, with the non-member test. Verify `:server:test --tests "*File*"`.
4. **F5** — move the department filter into `IdentityStore.listDevices` (or count in SQL) and re-run the head-scope tests; add the >500 fixture.
5. **F3** — put `isTestPrincipal` behind a default-off constructor flag; the existing tests opt in. Verify `AdminAuthServiceTest` and `ConsoleScopeTest` still pass and that the production wiring in `HubServices` does not set it.
6. **F6** — owner decision (account-only lockout vs IP rate limit). If accepted, change `reserve` to lock only the account key and let the existing `limits.adminLogin` limiter cover the address.
7. **F2** — either the enforcing wrapper (large, do last) or, cheaply, the `DEPT_HEAD`-hits-every-ORG-route test plus a corrected KDoc on `AdminRoutePolicies`. Do not leave the KDoc claiming enforcement that does not exist.
8. **F9** — make the pooled buffer's ownership explicit before measuring anything; the correctness consequence is nil, the performance consequence is the one the class exists to avoid, so the fix is a two-line ownership decision, not a benchmark.
9. **F10** — add the cross-target codec fixture suites, then correct the `byte-identical` comments to say what differs. Do not merge the copies (D1 = Option B).
10. **F7** — measure before changing anything; only then decide whether to merge the hash pass into the PUT pass.

## 11. Report for the Fixing Agent

- TASK 0 [F8] [HIGH]: Make "Clear received files" delete only files Flash received.
  - Files: `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt:184-193` (the delete), `:208-214` (`receivedFilesBytes`, which must count the same set), `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1793` (where a received file is written: `File(canonicalRoot, safeId)`), `:907` and `:243-245` (how the root follows the user's save location).
  - Change: delete only the per-transfer directories Flash created under the root, never `root.listFiles()` wholesale. Get the set from `DesktopEngine` if it tracks transfer ids (it writes them at `:1793`); if it does not, treat a directory as Flash's own only when it matches the transfer-id shape the engine writes and leave every other entry untouched. Make `receivedFilesBytes` sum the same set so the confirmation's byte figure matches what will actually be deleted. Keep the API signatures used by `DesktopShell` (`:1082`, `:2088-2090`) unchanged.
  - Verify: new test in `desktop/src/test/.../DesktopReceivedStorageTest.kt`: build a root containing `transfer-1/payload.bin` **and** `holiday.jpg`; call `clearReceivedFiles`; assert `payload.bin` is gone, `holiday.jpg` still exists, and `receivedFilesBytes` never counted it. Then `tools\flash-gradle.cmd :desktop:test --tests "*DesktopReceivedStorage*"`.
  - Do NOT: change the Android path (`MainActivity.kt:609-621`) — it is already correct and is the model to copy; do not make the clear recursive over the root "for tidiness"; do not remove the confirmation dialog.
- TASK 1 [F1] [HIGH]: Emit a file hit only when the attachment name matched the query.
  - Files: `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6586-6599`
  - Change: replace the `if (fileName != null)` guard with `if (nameMatches)`; keep the smart cast by hoisting `val matchedName = fileName` inside the branch if the compiler complains. Change nothing else in `searchChats`.
  - Verify: add a test to `core/messaging/src/androidHostTest/.../RealFlashChatRepositoryTest.kt` next to line 1177: one message whose text contains the query and whose `attachmentName` does not; assert `messageHits.size == 1` and `fileHits.isEmpty()`. Run `tools\flash-gradle.cmd :core:messaging:testAndroidHostTest --tests "*RealFlashChatRepository*"` (use the lock script, never `gradlew`).
  - Do NOT: change the DAO query, the escaping, or `searchMessageBodies`; do not touch `ui/chat` scope code.
- TASK 2 [F4] [LOW]: Refuse a group hub-file creation when the sender is not in the group roster.
  - Files: `server/src/main/kotlin/com/transfer/flash/server/files/FileStore.kt:82-88`
  - Change: in the `else ->` (group) branch, after resolving `members`, return `FileOutcome.Refused(HubErrorCodes.ERR_FORBIDDEN, FileMessages.NOT_RECIPIENT)` when `sender !in members`, before computing `rest`. Do not change the 1:1 branch.
  - Verify: new test in `server/src/test/.../files/` calling `create` with a sender absent from `groupMembers`; assert the refusal code and that no `file_blob` row exists.
  - Do NOT: change `access()` (its live-roster check is correct), the DTOs, or the client.
- TASK 3 [F5] [LOW]: Filter the head's overview devices in SQL, not by slicing 500 rows.
  - Files: `server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt:368-392`
  - Change: pass the head's department set into `d.identities.listDevices(...)` (the devices list route at `:415-419` shows the intended shape) or add a count query; keep the `ConsoleScope.Org` behaviour byte-identical.
  - Verify: a test with >500 devices where every device is in one department returns that department's real count for a head.
  - Do NOT: change `OverviewDto` fields or the console JS.
- TASK 4 [F3] [LOW]: Remove the unbacked-session escape hatch from the production path.
  - Files: `server/src/main/kotlin/com/transfer/flash/server/admin/AdminAuthService.kt:509,518-519`; wiring in `server/src/main/kotlin/com/transfer/flash/server/HubServices.kt`
  - Change: add `private val allowUnbackedSessions: Boolean = false`, use it in `stillAdmin`, and pass `true` only from the tests that need it.
  - Verify: `AdminAuthServiceTest`, `ConsoleScopeTest`, `DepartmentHeadScopeTest` green; grep that no production wiring sets it true.
  - Do NOT: change token hashing, session TTL/idle logic, or the cookie layer.
- TASK 5 [F2] [MEDIUM]: Make the declared route policy real, or stop pretending it is.
  - Files: `server/src/main/kotlin/com/transfer/flash/server/admin/ConsoleScope.kt:45-136`, `server/src/test/kotlin/com/transfer/flash/server/admin/ConsoleScopeTest.kt`
  - Change: prefer the wrapper that applies `AdminRoutePolicies` at the route boundary (one place, `ORG`/`OWN`/`SELF`), then delete the per-handler `requireOrg` duplication only where the wrapper provably covers it. If the wrapper is out of scope for this change, add the `DEPT_HEAD` → 403 matrix test over every `ORG` route and rewrite the `AdminRoutePolicies` KDoc to say the table is a declared intent checked by that test, not an enforcer.
  - Verify: `tools\flash-gradle.cmd :server:test --tests "*ConsoleScope*" --tests "*DepartmentHeadScope*" --tests "*RouteTable*"`, then the full `:server:test`.
  - Do NOT: change any route's actual access rule while refactoring (a refactor that quietly widens access is worse than the status quo); do not touch `AdminConsoleWeb`/`CsrfGuard`.
- TASK 6 [F7] [LOW]: Measure before optimising.
  - Files: `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt:258,337-345,599-614`
  - Change: add probe timings only (no behaviour change) around `hashSource`, the PUT loop and `verify`; record the numbers in `logs/experiments.md` as a new `EXP-` entry with device, file size and CPU.
  - Verify: one 50 MB and one 500 MB upload and download through a local hub, times split per phase.
  - Do NOT: restructure the sealing or the resume bitmap before the numbers exist.
- TASK 7 [F9] [LOW]: Give the pooled frame buffer an owner.
  - Files: `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt:224-233,362-376`
  - Change: pick one — either always hand the caller an exact copy (`pooled.copyOf(length)` then `release(pooled)`, i.e. drop the `if (pooled.size == length) pooled` shortcut), or document ownership on `Message.Binary` and release it in the single consumer. Do not add a `release()` that callers may forget without also making the failure loud.
  - Verify: extend `core/network/src/test/.../WebSocketCodecTest.kt`: decode two 16 384-byte binary frames back to back and assert both payloads are intact and `!=` the same array instance; run `tools\flash-gradle.cmd :core:network:testDebugUnitTest --tests "*WebSocketCodec*"` (and the JVM equivalent).
  - Do NOT: change `ByteArrayPool`'s capacity, the message-size caps, or the JVM copy.
- TASK 8 [F10] [LOW]: Make the duplicated transports checkable and honest.
  - Files: `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt:14-16` (the false claim), `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt:361-376`
  - Change: (a) correct the comment to state what the JVM copy does not share (no `ByteArrayPool`), and check the same wording on `WsConnection`, `SecureSocketUpgrader`, `WsFlashNetwork`; (b) add a fixture-driven test whose *expected values* are shared between the two source sets' test suites (same table of frames → expected opcode/payload, including exactly 16 384 and 32 768 bytes, a masked frame, a 127-length frame, a truncated frame).
  - Verify: both suites green (`:core:network:testDebugUnitTest`, `:core:network:jvmTest`), and deliberately breaking one codec makes exactly one suite fail.
  - Do NOT: merge the two implementations (D1 = Option B forbids a shared JVM tier); do not touch TLS pinning or the handshake while doing this.
- INVESTIGATE 1 (no finding): round 2 read the modules listed in §1's coverage table; it is still not a clean bill for `core/calling` (one method), `core/ptt`, `ui/callui`, `ui/theme`, `ui/platform-shims`, the `server` `RelayFrameRouter` internals (852 lines), the admin-console JS beyond the `innerHTML` sweep, or `tools/`. Those need a third pass; nothing in this report should be read as clearing them.
