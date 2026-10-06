# Feature-completeness audit against the project goals

**Date:** 2026-10-02 · **Branch:** `dev` · **HEAD:** `367167a3` (+ uncommitted in-call work, ADR-067)
**Scope:** the features the project says it needs: AGENTS.md section 2 (goals) and the blueprint's
"Definition of Done for Version 1" (`Project Goal and Blueprint/...plan.md` section 42), MVP-1..4 and the
failure-handling and security lists (sections 31-33).
**Method:** read the production code paths (not the docs' claims) and grepped for what is *absent*. **Nothing was run,
no test was executed for this audit, no device was used.** Each finding is labelled:

- **VERIFIED-IN-CODE**: I read the lines that show it (paths given).
- **ABSENT**: a search found no implementation (search terms given, so it can be re-checked).
- **NOT RUN**: a consequence I reasoned out but did not execute.

Earlier audits (`2026-09-23-full-audit.md`, `2026-09-28-...`, `2026-10-01-chat-edge-case-audit.md`) are not repeated;
open items from them are listed at the end.

---

## 1. Feature matrix (goal -> state)

"Built" means code exists and has unit tests. Only the 2026-09-23 owner check (pairing, chat, calls, transfers, upgrade
with v1 pairings) counts as device-verified; the backlog records **0 PASS of 123 tests**.

| Goal (AGENTS section 2 / blueprint 42) | State | Evidence / gap |
|---|---|---|
| LAN discovery (NSD) | Built, partly device-verified | DR1-DR3/DR5 extra sources are unit-tested only |
| Wi-Fi Direct transport | **Absent** | no `WifiP2pManager` anywhere; postponed by the owner (ADR-056). The "two devices discover over Wi-Fi Direct" and "LAN vs Wi-Fi Direct measured" items of the Definition of Done are unmet |
| One transfer engine over any transport | Built | chat and transfers share the WebSocket mesh (ADR-016) |
| TLS + pinned identity + pairing | Built, device-verified 2026-09-23 | TLS fails closed; inbound client auth; pairing v2 |
| Single file transfer | Built, device-verified 2026-09-23 | |
| Multiple files / folders | **Partial** (FA-5) | system share-target takes several files; the in-app picker takes one; folder model unused |
| Large files, streaming, no RAM copy | Built | chunked, per-chunk SHA-256 |
| Pause / cancel / resume | Built | pause intent lost on process death (ADR-021, known) |
| Recovery after app/process restart | **Partial** (FA-4) | chunk bitmap persists; transfer identity and source do not |
| Hash verification detects corruption | Fixed in code 2026-10-02 for all three hosts (FA-1, ADR-068); device check `FA-01` owed | before: Android yes, desktop no |
| Status survives app recreation | **Not met** (FA-4) | |
| Notifications report transfer state | Built | FGS notification: progress, speed, ETA, cancel (`FlashBackgroundService`) |
| Modern foreground-service rules | Built, not claimed supported | declared types, EXP-002 shows handset-dependent liveness |
| Storage rules (SAF, sandboxed receive) | Built | receive dir is app-scoped; path-traversal guard in all three hosts |
| Friendly handling of major failures | **Fixed in code 2026-10-03, not device-verified** (FA-2, FA-3, FA-6; ADR-069) | typed reason + Retry not built |
| Throughput measured on real devices | **Not done** | `MEAS-*` owed; EXP-001..017 are anecdotes, not the section 23 matrix |
| Chat, groups, calls, PTT | Built; mostly unit-tested only | see `TEST-BACKLOG.md` |

## 2. Findings

### FA-1 - Windows desktop never verifies the whole received file (HIGH, VERIFIED-IN-CODE)
> **Status 2026-10-02: FIXED IN CODE, unit-tested and mutation-checked, NOT device-verified** (ADR-068, ERROR-098, `FA-01`).
> **Correction to the text below:** the library facade `core/engine/.../Flash.kt` did **not** share the gap; it had its own private copy
> of `verifyWholeFile`. Only the Windows desktop was missing the check. Facade, Android and desktop now share one implementation.
> A second finding surfaced while fixing it: both UIs label **every** Completed transfer "Verified" (`verified = state == Completed`
> in `TransfersUiMapper.kt` and `DesktopShell.kt`), so on Android a failed check was shown as "Verified" too. A failed check now
> fails the transfer.

- **Android** recomputes the file hash at completion and compares it with the offered one
  (`app/.../debug/DiscoveryEngineHolder.kt:2231-2236`, `verifyWholeFile`).
- **Desktop** passes the pipeline's flag straight through: `desktop/.../DesktopEngine.kt:1459`
  `transfer.onIncomingCompleted(transferId, event.frame.verified, path)`. `ReceivePipeline` only computes anything
  else when `recheckWholeFileDigest = true`, and **no production construction sets it** (`DesktopEngine.kt:1008`,
  `DiscoveryEngineHolder.kt:760`, `core/engine/.../Flash.kt:315`), so `verified` is always `true`.
- Why it matters: per-chunk SHA-256 protects transit, but the resume seam *assumes* the bytes of already-done chunks are
  on disk and names the whole-file recheck as "the integrity backstop" (`ReceivePipeline.kt` KDoc on
  `resumeIndexesProvider`). On desktop that backstop does not exist; a resumed transfer with a damaged partial file
  would be reported verified. (The `Flash.kt` facade did have its own check, see the correction above.)
- AGENTS section 19 / blueprint section 13 require file integrity verification.
- **Fix direction (not done):** move `verifyWholeFile` into `core:transfer` as the shared completion check so all three
  hosts use it; add a desktop test that corrupts a resumed partial file and expects `verified = false`.

### FA-2 - No free-space check before accepting or writing (MEDIUM, ABSENT)
- **Status 2026-10-03: FIXED IN CODE (ADR-069, ERROR-099), unit-tested, not device-verified (`FA-02`).** `admitIncoming` at the top of every host's `acceptOffer`.
- Searched `StatFs|usableSpace|getFreeSpace|ENOSPC|availableBytes|not enough space|disk full` across `core`, `app`,
  `desktop`, `ui`: no production hit.
- Blueprint section 31 lists "Out of space" as a required failure state. An offer larger than the free space is
  accepted, written until the disk fills, then fails with whatever the OS exception text is (FA-3). NOT RUN on a device.
- Both Android (`FileRandomAccessSinkHandle(dest, start.totalBytes)`) and desktop know `totalBytes` up front, so a check
  at accept time is cheap.

### FA-3 - Failures reach the user as raw exception text (MEDIUM, VERIFIED-IN-CODE)
- **Status 2026-10-03: FIXED IN CODE (ADR-069, ERROR-099), unit-tested, not device-verified (`FA-03`).** `TransferFailureText.friendly` at the three failure sites. A typed reason on the model and a Retry action are NOT done.
- `RealFlashTransferRepository.kt:404` `errorMessage = t.message ?: "Transfer aborted unexpectedly"` and `:384`
  `errorMessage = result.reason`; `FlashTransfersScreen.kt:195` shows `item.errorMessage` as is.
- Messages such as `source length mismatch: read=... expected=... extraByte=...` or
  `whole-file digest changed between passes (resume/source-identity guard)` (`Chunker.kt`) are therefore user-visible.
  The model has no typed failure reason (`FlashTransferState.Failed` + a string). Blueprint section 31 asks for
  "explain what happened without exposing internals" and a Try-Again action.
- Consequence: no way to tell "file changed", "disk full", "peer gone", "hash mismatch" apart in UI or tests.

### FA-4 - Transfer identity is not persisted, so "status survives app recreation" is not met (MEDIUM-HIGH, VERIFIED-IN-CODE)
- `TransferEntity` = `transferId, totalBytes, bytesDone, status` (`core/persistence/.../TransferEntity.kt`); the store port
  adds only the chunk done-set (`TransferStore.kt`). No file name, peer id, direction, `sourceUri`, or wire file id.
- `RealFlashTransferRepository._activeTransfers` starts as `emptyList()` (line 77) and nothing reloads it from the store.
- Effects (reasoned from the above, NOT RUN): after a process restart the Transfers screen is empty; a sender cannot
  resume because `sourceUri` is in memory only (`TransferReconnectResumePolicy` needs it); a receiver can only resume if
  the sender re-offers the same transfer id. Resume survives a *connection* drop and a session reconnect, not a process
  death. AGENTS section 18 lists "App restart" and "Process restart" as required resume cases.
- Not checked: whether the chat message rows keep a usable file state after restart; that may mask this in the chat UI.

### FA-5 - Multiple files and folders cannot be chosen in the app (MEDIUM, VERIFIED-IN-CODE + ABSENT)
- The Android picker is `ActivityResultContracts.OpenDocument()` (one file) (`ui/platform-shims/.../FlashFilePicker.android.kt`);
  the desktop one is a single-selection `JFileChooser`. No `OpenMultipleDocuments` or `OpenDocumentTree` in the app.
- The system **share target** does accept `SEND_MULTIPLE` (`AndroidManifest.xml`, `MainActivity.kt:308`).
- `TransferManifest` (relative paths for a folder) is referenced only by its own test (`grep TransferManifest`), although the
  Android receive path already sanitizes relative sub-paths for "a folder transfer" (`sanitizeRelativePath`).
- AGENTS section 2 lists "File/folder selection"; the Definition of Done lists "Multiple files transfer successfully".

### FA-6 - A zero-byte file is probably unsendable (LOW-MEDIUM, VERIFIED-IN-CODE reading, NOT RUN)
- **Status 2026-10-03: FIXED IN CODE (ADR-069, ERROR-099), unit-tested, not device-verified (`FA-06`).** Reading was incomplete: the exception was thrown outside the `try` of
  `executeSend`, so the row stayed Queued, not Failed. An empty file is now a Failed row with a clear message; an unknown size is measured.
- `Chunker.kt:32,102` `require(totalBytes > 0)` and `ReceivePipeline.validateFileStart` rejects `totalBytes <= 0`. The picker returns
  size 0 for an empty file (`MainActivity.kt:3013` fallback). No guard was found that handles or refuses an empty file
  with a clear message, so the likely result is a Failed transfer with the `require` text (FA-3). Needs a one-minute test.

### FA-7 - Hygiene gaps against AGENTS section 5 (LOW)
- `docs/known-issues.md`, `docs/troubleshooting.md`, `docs/performance.md` and `docs/testing.md` do not exist (the
  open issues live in `logs/errors.md`, which is ~7,000 lines with about 18 entries still OPEN).
- The blueprint checklists (sections 25-28, 42) are all unticked `[ ]` although much of MVP-1/2 is built; a reader cannot see
  progress without reading three logs.

## 3. What is solid (checked, no finding)

- Path traversal: canonical-path containment in all three receive hosts plus per-component sanitising with Windows
  reserved names and length caps (`DiscoveryEngineHolder.kt:2352-2393`, `DesktopEngine.kt:1008`).
- Consent: `requireAcceptance = true`; nothing is created on disk before the user accepts (Android and desktop).
- Message size limits: 64 KiB before HELLO, 4 MiB after (`WebSocketCodec`), chunk size and count validated in `validateFileStart`.
- Per-chunk SHA-256 verified before write; duplicate-idempotent; ACK batching.
- Transfer notification with progress, speed, ETA and a cancel action.

## 4. Priorities (recommendation, not a decision)

1. **FA-1** (desktop whole-file verification): smallest change, closes a stated security requirement, shared code.
2. **FA-4** (persist transfer identity and rehydrate the list): the largest Definition-of-Done gap that is not a platform
   limit; needs an ADR (schema migration on Android *and* desktop, see the migration lessons in ERROR-080).
3. **FA-2 + FA-3 + FA-6** together (DONE in code 2026-10-03, ADR-069): typed failure reasons, a free-space check at accept, an empty-file message.
4. **FA-5**: multi-select picker first (cheap), folder manifest later.
5. Wi-Fi Direct stays postponed (owner decision); device verification and measurements stay owed (`TEST-BACKLOG.md`).

## 5. Still-open items from earlier audits

`docs/audit/2026-09-28-architectural-audit-and-tasks.md` sections 3.2 (blocking writes under `writeLock`), S6 (unauthenticated
desktop activation IPC) and 3.11; ERROR-073 until `AUD-01`; the chat edge-case audit's ERROR-089..094 and the owner's pending
group-ownership decision (no ownership code before it).

## 6. Not audited here

Performance (no measurement was taken), UI visual quality (UI-045 gate), accessibility, battery, the Linux/iOS plans, and
`:app` behaviour on API levels other than those already exercised. Every item above marked NOT RUN needs a test (backlog
section 4s).
