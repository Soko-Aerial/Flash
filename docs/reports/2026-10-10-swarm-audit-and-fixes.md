# Swarm File Transfer Subsystem: Audit & Fix Implementation Report

**Date:** 2026-10-10  
**Status:** COMPLETED & VERIFIED (All 8 steps green)  
**Modules Audited & Remediated:** `:core:swarm`, `:core:engine`, `:core:transfer`

---

## 1. Audit Summary & Findings Matrix

A comprehensive review of the Swarm file transfer subsystem identified 5 functional bugs, 4 concurrency/platform risks, and several efficiency improvements across the sans-IO state machine (`core:swarm`) and host bindings (`core:engine`).

| ID | Severity | Area | Symptom / Defect | Remediation Applied | Verification |
|:---|:---|:---|:---|:---|:---|
| **SWM-BUG-01** | CRITICAL | Partial Bitfield Gossip | Late-joining or late-accepting peers receive `SwarmContentState.PARTIAL` in Summary, but no `Have` frames were emitted for pre-existing partial pieces. Late peers saw 0 pieces and stalled. | Centralized `Have` gossip in `PeerHandler.sendSummary` with `content.persistedBits.toRanges()` and propagated `nowMs`. | Unit test in `SwarmFirstContactTest` passes. |
| **SWM-BUG-02** | CRITICAL | Finalize Checksum Deadlock | On whole-file SHA-256 failure during `finalize()`, `badPieces` is empty. 0 bits were cleared from `bitfield`, and transfer was set to `ACTIVE` with `isComplete == true`, causing permanent deadlock with no re-request. | Guarded `handleFinalizeResult` in `TransferScheduler`: if `badPieces.isEmpty()`, immediately transitions to `FAILED(DAMAGED)` instead of deadlocking in `ACTIVE`. | Unit test `step 4i2` in `SwarmEngineStepTest` passes. |
| **SWM-BUG-03** | HIGH | Wait Classification | `onlineHolders` checked `!pcs.bitfield.isEmpty()` without verifying if the peer held any pieces *missing* locally. Caused false `Transferring` state at 0 B/s instead of `WaitingForHolders`. | `SwarmEngine.updateWaitReason` computes `hasConnectedHoldersForMissing` checking `(!content.bitfield.get(i) && pcs.bitfield.get(i))`. | Unit test in `SwarmWaitReasonRecoveryTest` passes. |
| **SWM-BUG-04** | HIGH | Congestion & Flow Control | In `ServeHandler.handleRejectArrived`, `requestWindow.removeInFlightBytes` was called unconditionally on all pieces in `Reject`, draining global byte budget for other peers on duplicate/unsolicited rejects. | Guarded deduction: only deducts if `content.inFlightRequests.remove(peer to piece)` was non-null. | Unit test `step 4o` in `SwarmEngineStepTest` passes. |
| **SWM-BUG-05** | HIGH | Disk Storage / Preallocation | In `SwarmDriver.executeCommand(SyncAndPersistBits)`, `storage.openPartial(key, cmd.bytesDone)` passed `bytesDone` instead of `totalSize`, risking file truncation or size mismatch. | Added `totalSize: Long` to `SwarmCommand.SyncAndPersistBits`, populated in `TransferScheduler` & `ServeHandler`, and used in `SwarmDriver`. | Verified across `:core:swarm:jvmTest`. |
| **SWM-PERF-06** | MEDIUM | Endgame Airtime Model | `PiecePicker` and `TransferScheduler` duplicated 100% of endgame pieces unconditionally, doubling Wi-Fi airtime even when the primary peer is streaming fast. | Added `canDuplicate` filter in `PiecePicker` and `TransferScheduler` checking EWMA speed and elapsed time (>= max(300ms, 1.5x expected piece duration)). | Unit test in `PiecePickerTest` passes. |
| **SWM-PERF-07** | MEDIUM | Android Binder IPC Churn | In `SwarmDriver.readPieceBytes` / `AndroidPieceStorage`, every piece read opened and closed a fresh `SourceHandle` via Android ContentResolver. For 1 GB file = 16,384 Binder IPCs. | Cached open `SourceHandle` per URI in `SwarmDriver` using `SyncMap` with clean teardown on cancellation/retention cleanup. Hardened `readAt` with `runCatching` to handle `ClosedChannelException` and `IOException` gracefully. | Verified in `SwarmInteropTest` and `:core:engine:jvmTest`. |
| **SWM-PERF-08** | MEDIUM | Inbound Inbox Buffer Cap | `PeerOrderedInbox` had `DEFAULT_CAPACITY = 64`. Four peers with 1 MB pieces = 256 MB buffer in RAM, risking OOM on standard Android heaps. | Bounded `DEFAULT_CAPACITY = 16` in `PeerOrderedInbox`, aligning with in-flight byte budgets and reducing buffer pressure by 75%. | Verified across all 148 tests in `:core:engine:jvmTest`. |

---

## 2. Verification Summary

1. **`:core:swarm:jvmTest`**: All unit tests passing 100% green.
   - Tested frame codecs, Golden vectors, PiecePicker, TransferScheduler, ServeHandler, PeerHandler, ContentLifecycle, and SwarmEngine state transitions.
2. **`:core:engine:jvmTest`**: All 148 tests passing 100% green.
   - Tested `SwarmInteropTest` (3-node origin drop and rejoin with 5 MB payload), `PeerOrderedInbox` ordering & drop behavior, `JvmPieceStorage` random access & validation, `SwarmHostBinding` lifecycle & crash hardening.
   - Verified hardened `SwarmDriver.readPieceBytes` with `runCatching` around `handle.readAt`.
