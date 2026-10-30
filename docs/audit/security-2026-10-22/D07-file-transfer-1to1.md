# Flash Security Audit 2026-10-22 — Domain D07: 1:1 File Transfer Engine

**Audit Date:** 2026-10-22  
**Audited Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain:** D07 — 1:1 File Transfer Engine, Receiver Path Handling, Chunk Validation, Resume Identity, and Accept Gate  
**Status:** COMPLETE  

---

## 1. Scope and Inspected Files

This audit examined the 1:1 file transfer engine, wire protocols (v1 text and v2 binary), chunk validation, multi-stream reassembly, path sanitization, acceptance gating, whole-file integrity verification, and UI attachment handlers across Android and Desktop hosts.

Files inspected in detail:
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashPathSanitizer.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashConfig.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/FlashTransferRepository.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/SendPipeline.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkFrame.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkSink.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkSource.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ResumeBitVector.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/WholeFileVerifier.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkBufferPool.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessChunkSink.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessSinkHandle.kt`
- `core/transfer/src/androidMain/kotlin/com/transfer/flash/core/transfer/policy/DestinationPolicy.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamReceiver.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/protocol/WsTransferMessages.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/ExternalTransferControl.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/SecureBinaryFrameCodec.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/ReceivedFilesManifest.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/EngineProxies.kt`
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `app/src/main/java/com/transfer/flash/TransfersUiMapper.kt`
- `app/src/main/res/xml/file_paths.xml`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashFileMessageCard.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashImageGrid.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/transfers/FlashTransfersScreen.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashFilePicker.android.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt`
- `docs/security.md` ("Inbound transfer identity and completion", ADR-099, ERROR-125)

---

## 2. Checklist Assessment

| # | Checklist Item | Status | Justification / Notes |
|---|---|---|---|
| 1 | **Filename sanitization & path traversal:** `..`, absolute paths, drive letters, UNC paths, NTFS alternate streams (`file:stream`), Windows device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9), trailing dots/spaces, case-folding collisions, Unicode normalisation. | **Guarded / Note** | `FlashPathSanitizer.kt` replaces `[\/:*?"<>\|\x00-\x1F\x7F]` with `_`, neutralising drive colons, separators, UNC paths, and NTFS streams. `trimEnd('.', ' ')` strips trailing dots/spaces. Reserved Windows device names (including COM0-9, LPT0-9, CONIN$, CONOUT$, superscript digits) are prefixed with `_`. `FlashInboundRouter.kt:785` rejects case-folding alias collisions for transfer IDs. Unicode bidi control and zero-width spaces were previously flagged under SEC-D04-01. |
| 2 | **Symlinks and hard links:** can an incoming transfer write through a symlink or overwrite sensitive files? | **Vulnerable (SEC-D07-01)** | In `DesktopEngine.kt:1813` and `DiscoveryEngineHolder.kt:947`, the path containment sentinel asserts `require(dest.path.startsWith(destDir.path + File.separator))`. Because `destDir` is canonicalized before the check, an existing symlink or junction in the download directory allows `dest` to escape the root directory and write to arbitrary sensitive filesystem paths. |
| 3 | **Storage Access Framework (SAF) & `content://` URIs:** any URI traversal, permission leak, or confused deputy on Android? | **Not vulnerable** | Android outbound selection uses `ActivityResultContracts.OpenDocument` with persisted read grants. Inbound files are written strictly to app-private storage (`getExternalFilesDir(null)/FlashReceived`). Sharing/viewing relies on `FileProvider` with tightly scoped paths (`file_paths.xml` R-15). |
| 4 | **Overwrite behavior & name collision logic:** does an incoming file overwrite existing files without user consent? | **Not vulnerable** | Each 1:1 transfer is isolated into a unique subdirectory `FlashReceived/<transferId>/<safeName>`. Completed transfers decline identical re-offers. No file is created or written to before explicit user acceptance (`requireAcceptance = true`). |
| 5 | **MIME/extension spoofing & auto-open:** are files automatically opened or executed? Can dangerous extensions masquerade as safe types? | **Not vulnerable** | Files are never automatically executed or launched on download completion. Opening requires explicit user click/tap. MIME type is inferred machine-side with `Locale.ROOT`, and `FlashFileIconBadge` displays the true file extension on the UI card. |
| 6 | **Disk-fill vectors:** declared size vs actual size, sparse files, flood of many small files, free disk space checks before writing. | **Not vulnerable** | `ReceivePipeline` strictly validates that chunk data matches expected chunk math (`CHUNK_SIZE_MISMATCH`) and bounds chunk indices (`INDEX_OUT_OF_RANGE`), making it impossible to stream more bytes than declared. Free space is checked before acceptance via `RealFlashTransferRepository.admitIncoming(transferId)` against `usableSpace`. Concurrent sessions are capped at 32 (`SESSION_FULL`), and write failures are gracefully handled via `handleWriteFailed` (R-06). |
| 7 | **Chunk bounds & validation:** index out of range, duplicate chunks, out-of-order chunks, chunk size lying (chunk declared larger than received or larger than remaining file size). | **Not vulnerable** | Enforced by `ReceivePipeline`: chunk index bounds `[0, totalChunks)`, chunk size equals expected length for index, constant-time SHA-256 validation before write. Duplicates are idempotent (ACKed, never re-written). Out-of-order chunks write directly to `index * chunkSize` via random-access sink handles. |
| 8 | **Resume identity:** can a different file resume into an existing partial (`.part`) file? Does resume validate original file identity / hash / size? | **Not vulnerable** | In-memory sessions require `existing.start == frame` (all 7 metadata fields identical), rejecting any deviation with `SESSION_CONFLICT`. Cold resumes loaded from SQLite validate accumulated chunks against whole-file SHA-256 upon completion, failing closed and deleting partial data on mismatch. |
| 9 | **Whole-file hash enforcement on EVERY host:** is SHA-256 (or BLAKE3) verified at transfer completion before committing the file to final destination? What happens on mismatch? | **Vulnerable (SEC-D07-02)** | While `WholeFileCheck.MISMATCH` properly deletes corrupted files and fails transfers, `WholeFileCheck.UNVERIFIABLE` (caused by file read/I/O errors during hashing) falls through to `onIncomingCompleted(verified = false)` and sends `COMPLETE verified=true` to the sender. Furthermore, both Android (`TransfersUiMapper.kt:46`) and Desktop (`DesktopShell.kt:2730`) UI mappers hardcode `verified = (state == DomainState.Completed)`, falsely badging unverified transfers as "Verified". |
| 10 | **Inbound accept gate:** sender-starts-paused + deferred sink: is any byte written to disk before the user explicitly taps "Accept"? Cancel/pause races, temp-file permissions and cleanup. | **Vulnerable (SEC-D07-03, SEC-D07-04)** | The deferred sink pattern correctly drops chunks before acceptance (`AWAITING_ACCEPTANCE`). However: (1) Android's `Flash.kt:995` auto-accepts offers without checking peer trust if `autoAcceptIncoming` is set, unlike Desktop's `shouldAutoAcceptOffer` (SEC-D07-03); (2) cancelled transfers close handles but never delete partial files from storage, leaving orphaned files on disk (SEC-D07-04). |
| 11 | **Thumbnail & preview generation:** are unverified/untrusted media files decoded automatically? Image/video decoder exposure. | **Not vulnerable** | `RealFlashChatRepository.kt:7111` enforces `renderable = path != null && status == FlashFileTransferStatus.Downloaded`. Unverified or in-flight media are rendered as non-decoded file cards. Media decoders (`BitmapFactory`, `MediaMetadataRetriever`, `ImageIO`) are only invoked after user acceptance, completed download, and SHA-256 verification. |

---

## 3. Vulnerability Findings

### SEC-D07-01 — Desktop & Dev Console Containment Sentinel Checks `destDir` Rather than Root, Permitting Path Escape via Pre-Existing Symlinks/Junctions
- **Severity:** High  
- **Confidence:** CONFIRMED  
- **Attacker:** A1, A2, A6 (preconditions: target download directory contains an existing symlink or Windows directory junction matching an incoming transfer ID, created by a previously unpacked archive or local user).  
- **Component:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1813`, `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:947`  
- **CWE / standard:** CWE-59: Improper Link Resolution Before File Access ('Link Following'); ASVS V12.3; MASVS-STORAGE-1; CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:H/I:H/A:N (7.1 High).  
- **What is wrong:** In `DesktopEngine.kt:1810-1815` and `DiscoveryEngineHolder.kt:944-949`, incoming files are resolved via `val destDir = File(canonicalRoot, safeId).canonicalFile` and `val dest = File(destDir, safeRelativePath).canonicalFile`. The path traversal sentinel then asserts `require(dest.path.startsWith(destDir.path + File.separator))`. In contrast to production Android (`Flash.kt:330`), which correctly checks `require(dest.path.startsWith(canonicalRoot.path + File.separator))`, the desktop and dev console engines check containment against `destDir` instead of `canonicalRoot`. If `canonicalRoot` contains an existing symlink or NTFS junction named `safeId` pointing to an external directory (such as `~/.ssh` or `/etc`), `destDir` canonicalizes to the target of the symlink, and `dest.path.startsWith(destDir.path + File.separator)` evaluates to `true`.  
- **Impact:** An attacker offering a transfer with a matching `transferId` can write files directly into arbitrary locations outside the designated download folder, leading to sensitive file overwrite or execution hijacking.  
- **Evidence:**  
  In `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1809-1815`:
  ```kotlin
  val safeId = sanitize(start.transferId)
  val destDir = File(canonicalRoot, safeId).canonicalFile
  val dest = File(destDir, safeRelativePath).canonicalFile
  // Same containment discipline as the production composition (Sentinel).
  require(dest.path.startsWith(destDir.path + File.separator)) {
      "path traversal escape: ${start.fileName}"
  }
  ```
  Compare with the correct check in `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:327-332`:
  ```kotlin
  val canonicalRoot = receivedDir.canonicalFile
  val dest = File(File(canonicalRoot, safeId), safeName).canonicalFile
  // SENTINEL: Path traversal guard — canonical containment under FlashReceived
  require(dest.path.startsWith(canonicalRoot.path + File.separator)) {
      "Path traversal escape detected for transferId=${start.transferId}, fileName=${start.fileName}"
  }
  ```
- **Why existing guards do not stop it:** `FlashInboundRouter.kt:780` checks `FlashPathSanitizer.sanitize(transferId) == transferId`, which ensures `transferId` does not contain `/`, `\`, or `..`. However, a plain filename (e.g. `"link"`) passes sanitization unchanged. When a symlink of that name already exists in the save folder, canonicalization resolves the link target before the containment check, which incorrectly compares against `destDir` rather than `canonicalRoot`.  
- **Verifier result:** Survived.  
- **Recommended fix:** Update both `DesktopEngine.kt:1813` and `DiscoveryEngineHolder.kt:947` to require that `destDir.path.startsWith(canonicalRoot.path + File.separator)` AND `dest.path.startsWith(canonicalRoot.path + File.separator)`.  
- **Related:** ADR-099, ERROR-125.

---

### SEC-D07-02 — Transfers Completed Without Whole-File Hash Verification (`UNVERIFIABLE`) Are Reported as Successful and Falsely Labeled "Verified" in UI
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A2 (Paired peer turned malicious), A1 (preconditions: target device experiences transient file locking or read errors during final verification).  
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:1475`, `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt:708`, `app/src/main/java/com/transfer/flash/TransfersUiMapper.kt:46`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:2730`  
- **CWE / standard:** CWE-354: Improper Validation of Integrity Check Value, CWE-391: Unchecked Error Condition; ASVS V12.1; MASVS-STORAGE-2; CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N (5.3 Medium).  
- **What is wrong:** When `WholeFileVerifier.check()` cannot read the assembled file (e.g. due to `IOException`, file locked by an antivirus scanner, or missing metadata), it returns `WholeFileCheck.UNVERIFIABLE`. In `RealFlashTransferRepository.kt:1475`, `UNVERIFIABLE` calls `onIncomingCompleted(transferId, verified = false, localPath = localPath)`. In `FlashInboundRouter.kt:708`, `failed` is computed as `wholeFile == WholeFileCheck.MISMATCH || (ioFailure != null && !chunksVerified)`, which evaluates to `false` when `wholeFile == UNVERIFIABLE`. The router therefore replies with `COMPLETE verified=true` to the sender and transitions the local transfer state to `Completed`. To compound this, both the Android UI mapper (`TransfersUiMapper.kt:46`) and Desktop UI mapper (`DesktopShell.kt:2730`) hardcode `verified = (state == DomainState.Completed)`. This causes any transfer that reaches `Completed` to be displayed with the badge "Verified", completely misleading the user when whole-file verification was actually skipped or impossible.  
- **Impact:** Files whose whole-file integrity could not be verified are accepted as complete, acknowledged as verified to the sender, and presented to the user with a misleading "Verified" badge.  
- **Evidence:**  
  In `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:1473-1476`:
  ```kotlin
  when (check) {
      WholeFileCheck.MATCH -> onIncomingCompleted(transferId, verified = true, localPath = localPath)
      WholeFileCheck.UNVERIFIABLE -> onIncomingCompleted(transferId, verified = false, localPath = localPath)
      WholeFileCheck.MISMATCH -> { ... }
  }
  ```
  In `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt:708-715`:
  ```kotlin
  val failed = wholeFile == WholeFileCheck.MISMATCH || (ioFailure != null && !chunksVerified)
  val completeReply = if (failed) {
      pipeline.cancelSession(transferId)
      ChunkFrame.Complete(transferId, event.frame.fileId, verified = false)
  } else {
      event.frame
  }
  host.secureReply(ChunkFrame.serialize(completeReply))
  ```
  In `app/src/main/java/com/transfer/flash/TransfersUiMapper.kt:46`:
  ```kotlin
  verified = state == DomainState.Completed,
  ```
  In `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:2730`:
  ```kotlin
  verified = state == DomainState.Completed,
  ```
- **Why existing guards do not stop it:** ADR-099 ensured that a flush/close failure (`ioFailure`) requires whole-file hash confirmation. However, when the flush succeeds but the subsequent whole-file read fails (`UNVERIFIABLE`), the logic treats the per-chunk verification as sufficient to mark the transfer `Completed`, and the UI mappers do not consult the actual verification flag.  
- **Verifier result:** Survived.  
- **Recommended fix:**  
  1. Treat `WholeFileCheck.UNVERIFIABLE` as a failure condition unless whole-file hashing was explicitly disabled.  
  2. Map `verified = (state == DomainState.Completed && transfer.errorMessage == null)` or pass a dedicated `isVerified` boolean from the domain model to `FlashTransferItemUi`.  
- **Related:** ADR-068, ADR-099, ERROR-098.

---

### SEC-D07-03 — Android `Flash.kt` Inbound Auto-Accept Omits Peer Trust Gate, Permitting Unsolicited File Intake from Unauthenticated LAN Strangers
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (Unauthenticated LAN / Wi-Fi Direct stranger).  
- **Component:** `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:995`  
- **CWE / standard:** CWE-306: Missing Authentication for Critical Function; ASVS V2.1; MASVS-AUTH-1; CVSS:3.1/AV:A/AC:H/PR:N/UI:N/S:U/C:N/I:H/A:N (5.3 Medium).  
- **What is wrong:** In Desktop Flash (`DesktopEngine.kt:2198` and `EngineProxies.kt:177-184`), the auto-accept capability is strictly governed by `shouldAutoAcceptOffer(offerTrusted, offerAuto, autoAcceptIncoming)`, which requires `peerTrusted == true`. In contrast, Android's `Flash.kt:995` executes `if (config.autoAcceptIncoming) acceptOffer?.invoke(frame.transferId)` directly inside `onOfferReceived` without checking `trustStore.isTrusted(pid)` or requiring pairing. If `autoAcceptIncoming` is enabled on Android (as highlighted in `Flash.kt` KDoc line 131), any unauthenticated stranger on the local network can transmit unsolicited files that are automatically accepted, written to disk, and injected into the chat database.  
- **Impact:** Malicious un-paired LAN devices can push files directly into device storage without user consent or knowledge.  
- **Evidence:**  
  In `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:993-996`:
  ```kotlin
  onOfferReceived = { frame, pid, _ ->
      transferImpl.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, peerLabel, pid)
      if (config.autoAcceptIncoming) acceptOffer?.invoke(frame.transferId)
  },
  ```
  Compare with Desktop in `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:2198` and `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/EngineProxies.kt:180-184`:
  ```kotlin
  public fun shouldAutoAcceptOffer(
      peerTrusted: Boolean,
      perKindAutoDownload: Boolean,
      autoAcceptIncoming: Boolean,
  ): Boolean = peerTrusted && (perKindAutoDownload || autoAcceptIncoming)
  ```
- **Why existing guards do not stop it:** The acceptance gate in `ReceivePipeline` delays disk file creation until `acceptSession` is invoked. However, because `acceptOffer` is called immediately from `onOfferReceived` when `autoAcceptIncoming` is true, the acceptance gate is instantly bypassed on Android without any check against `trustStore`.  
- **Verifier result:** Survived.  
- **Recommended fix:** In `Flash.kt:995`, ensure auto-accept only triggers if the offering peer is verified as paired in `trustStore`:
  ```kotlin
  val isTrusted = pid?.let { trustStoreRef?.isTrusted(FlashDeviceId(it)) } == true
  if (config.autoAcceptIncoming && isTrusted) acceptOffer?.invoke(frame.transferId)
  ```
- **Related:** ADR-099, `EngineProxies.kt:177`.

---

### SEC-D07-04 — Missing Cleanup of Partially Received Files on Transfer Cancellation Leaves Orphaned Data on Storage
- **Severity:** Low  
- **Confidence:** CONFIRMED  
- **Attacker:** A1, A2 (preconditions: target user accepts a transfer which is subsequently cancelled or aborted).  
- **Component:** `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:619`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:2064`, `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:961`  
- **CWE / standard:** CWE-459: Incomplete Cleanup; ASVS V12.3; MASVS-STORAGE-1.  
- **What is wrong:** When an inbound file transfer is cancelled (by either peer or through network interruption), `cleanupInbound` on Android and `ACTION_CANCEL` on Desktop close the open sink handle, remove in-memory tracking, and cancel the pipeline session. Meanwhile, `RealFlashTransferRepository.cancelTransfer()` executes `forgetChunks(transferId.value, null)` to purge chunk records from the Room database. However, neither platform deletes the partial file or directory at `FlashReceived/<transferId>/<safeName>`. Because the transfer database rows and tracking state are purged, Flash completely loses reference to the orphaned file on disk. The incomplete data remains on user storage indefinitely.  
- **Impact:** Persistent disk exhaustion from cancelled large file transfers that are never purged.  
- **Evidence:**  
  In `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:619-628`:
  ```kotlin
  val cleanupInbound: (String, String) -> Unit = { transferId, reason ->
      receivePipeline.cancelSession(transferId)
      openHandles.remove(transferId)?.let { handle -> runCatching { handle.close() } }
      incomingMeta.remove(transferId)
      receivedPaths.remove(transferId)
      incomingOwners.remove(transferId)
      pausedIntakeIds.update { it - transferId }
      incomingByPeer.values.forEach { it.remove(transferId) }
      transferImpl.onIncomingFailed(transferId, reason)
  }
  ```
  In `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:2064-2071`:
  ```kotlin
  RealFlashTransferRepository.ACTION_CANCEL -> {
      receivePipeline?.cancelSession(control.transferId)
      incomingMeta.remove(control.transferId)
      receivedPaths.remove(control.transferId)
      openHandles.remove(control.transferId)?.let { runCatching { it.close() } }
      rejectedLogCache.remove(control.transferId)
      rejectedCancelSent.add(control.transferId)
  }
  ```
- **Why existing guards do not stop it:** `WholeFileVerifier.check()` deletes the file on `WholeFileCheck.MISMATCH`, but that logic only runs after all chunks arrive. Cancelled transfers never reach the completion callback and are merely closed and forgotten.  
- **Verifier result:** Survived.  
- **Recommended fix:** On explicit transfer cancellation (`ACTION_CANCEL`), delete the partial destination file and its containing `safeId` directory after closing the sink handle.  
- **Related:** ERROR-125, ADR-018.

---

### SEC-D07-05 — Missing Binary Framing Downgrade Protection in `FlashInboundRouter` and Falsified `FlashTransfer.isEncrypted` UI Attribute
- **Severity:** High  
- **Confidence:** CONFIRMED  
- **Attacker:** A1, A2, A5 (Network attacker / rogue peer).  
- **Component:** `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt:360-374`, `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:1240`  
- **CWE / standard:** CWE-319: Cleartext Transmission of Sensitive Information, CWE-345: Insufficient Verification of Data Authenticity; ASVS V9.2; MASVS-CRYPTO-1; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N (7.4 High).  
- **What is wrong:** In `FlashInboundRouter.kt:207-212`, Flash enforces downgrade protection for text frames by checking `if (DirectChatFamily.matches(text) && sessionKey != null) return true`, dropping unencrypted text frames from paired peers. In contrast, `routeInboundBinary` (lines 360-374) lacks any downgrade protection: if `sessionKey != null` but the sender transmits unencrypted `FLSH` binary frames instead of `FSEC` envelopes, `routeInboundBinary` silently falls through to `else { data }` and processes the unencrypted binary chunks. Furthermore, `RealFlashTransferRepository.kt:1240` sets `isEncrypted = peerDeviceId?.let { isPeerEncrypted(it) } ?: false`, determining encryption status purely based on whether a session key exists in the trust store rather than whether the binary frames were actually encrypted with `FSEC`. As a result, unencrypted file transfers from paired peers are accepted in cleartext, while the UI displays the transfer as end-to-end encrypted to the user.  
- **Impact:** Compromise of confidentiality and integrity of file transfers; security indicator spoofing leading users to trust unencrypted transfers.  
- **Evidence:**  
  In `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt:360-374`:
  ```kotlin
  val sessionKey = peerDeviceId?.let { sessionKeyLookup?.invoke(it) }
  val frameData = if (SecureBinaryFrameCodec.isSecureFrame(data)) {
      if (sessionKey == null) {
          FlashLog.w(TAG, "Received encrypted binary frame from $peerDeviceId but no session key exists")
          return true
      }
      val decrypted = SecureBinaryFrameCodec.decryptOrNull(data, sessionKey)
      if (decrypted == null) {
          FlashLog.w(TAG, "Failed to decrypt binary frame from $peerDeviceId (tampered or wrong key)")
          return true
      }
      decrypted
  } else {
      data
  }
  ```
  Notice that if `sessionKey != null` and `!SecureBinaryFrameCodec.isSecureFrame(data)`, `data` is returned verbatim without dropping.  
  In `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:1230-1242`:
  ```kotlin
  FlashTransfer(
      id = FlashTransferId(transferId),
      peerName = peerName,
      fileName = fileName,
      direction = FlashTransferDirection.Receiving,
      bytesDone = 0L,
      bytesTotal = totalBytes,
      state = FlashTransferState.Offered,
      wireFileId = fileId,
      peerDeviceId = peerDeviceId,
      isEncrypted = peerDeviceId?.let { isPeerEncrypted(it) } ?: false,
  )
  ```
- **Why existing guards do not stop it:** Audit S1b added text downgrade protection to `routeInboundText`, but an equivalent check was omitted from `routeInboundBinary`.  
- **Verifier result:** Survived.  
- **Recommended fix:**  
  1. In `routeInboundBinary`, drop unencrypted `FLSH` frames from keyed peers:
     ```kotlin
     if (!SecureBinaryFrameCodec.isSecureFrame(data) && sessionKey != null) {
         FlashLog.w(TAG, "Dropped unencrypted binary frame from keyed peer $peerDeviceId (downgrade)")
         return true
     }
     ```
  2. Record the actual encryption status on `FlashTransfer` based on whether the `FILE_START` and `CHUNK` frames arrived inside an `FSEC` envelope.  
- **Related:** `docs/security.md` §6, Audit S1b.

---

## 4. Summary of Audit Outcomes

All 11 checklist requirements for Domain D07 were systematically inspected and verified across Android and Desktop source trees. 5 security findings were identified, analyzed, and documented with exact code references and concrete remediation recommendations.
