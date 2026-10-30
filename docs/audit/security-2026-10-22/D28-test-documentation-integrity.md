# Domain D28 — Test and Documentation Integrity

## 1. Domain Overview
This domain audits the integrity, rigor, and reality alignment of the Flash test suites, mutation coverage claims, developer markers (`TODO`, `FIXME`, `HACK`), debug backdoors, and discrepancies between `docs/security.md` and actual production behavior.

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Integrity & Documentation Audit Summary

| Area | Documentation Claim | Actual Implementation | Evaluation |
|---|---|---|---|
| Integrity Hash Primitive | "BLAKE3 or another cryptographic hash" (`AGENTS.md`) | Exclusively SHA-256 via JCA and `Sha256.kt` | Documentation drift (`SEC-D28-02`). |
| Inbound Direct Chat Trust | "Direct 1:1 chat gated by PeerTrustPolicy.mayChat" (`docs/security.md`) | `RealFlashChatRepository:5947` ingests direct text without trust check | Vacuous test coverage (`SEC-D28-01`). |
| Transfer Hash Enforcement | "Transfers without valid hash are rejected" (`docs/security.md`) | `UNVERIFIABLE` transfers marked SUCCESS & "Verified" in UI (`SEC-D07-02`) | Defect in verification enforcement. |
| Debug Backdoors | None in production release | `--dev-embedded-root` gated by `FLASH_ALLOW_INSECURE=true` and logs loud errors | Safe; fail-closed in production (`REJ-D28-01`). |
| Developer Markers | Minimal `TODO` / `FIXME` in security paths | Only 2 non-security cleanup comments found across entire core codebase | Excellent developer hygiene. |
| Exception Swallowing in Tests | None | Security test assertions use `assertThrows` and fail-closed checks | No swallowed test assertions (`REJ-D28-02`). |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Security-relevant tests passing vacuously | DEFECT | `PeerTrustPolicyTest` verifies pure policy logic while the inbound direct chat intake pipeline never invokes `mayChat` (`SEC-D28-01`). |
| Discrepancies between docs and reality | DEFECT | Documentation claims BLAKE3 integrity and universal transfer verification contrary to code (`SEC-D28-02`). |
| `// TODO`, `FIXME`, `HACK` in security paths | PASS | Clean. Search across security paths revealed zero lingering workarounds or temporary shortcuts. |
| Debug backdoors / test flags in release code | PASS | All dev flags require explicit insecure mode flags and emit loud errors (`REJ-D28-01`). |
| `BuildConfig.DEBUG` leakage in release logic | PASS | `BuildConfig.DEBUG` only referenced in non-code documentation comments. |
| Test suite assertion integrity | PASS | Tests use strict assertions (`assertThrows`, `assertEquals`) without swallowing exceptions (`REJ-D28-02`). |

---

## 4. Files Audited
- `docs/security.md`
- `AGENTS.md`
- `core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/PeerTrustPolicyTest.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt`
- `server/src/main/kotlin/com/transfer/flash/server/Main.kt`

---

## 5. Findings

### SEC-D28-01 — Vacuous Security Verification: `PeerTrustPolicyTest` Validates Pure Logic Functions While Inbound Direct Chat Intake Remains Ungated
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A1, A2 (Adversary exploiting untested integration boundary)
- **Component:** `core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/PeerTrustPolicyTest.kt:88-100`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5947`
- **CWE / standard:** CWE-1068: Inconsistency Between Implementation and Documented Design; CWE-398: Indicator of Poor Code Quality; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:N (6.5)
- **What is wrong:** The Flash test suite contains thorough unit test suites for `PeerTrustPolicy` (`ORGT-U25` capability matrix tests), validating that `mayChat(isPaired = false, isOrg = false)` correctly returns `false` for unauthenticated strangers. However, this test exercises the pure mathematical policy function in isolation. In the live production engine (`RealFlashChatRepository.kt:5947`), inbound direct 1:1 text wire frames (`FLASH_CHAT_TEXT`) never invoke `PeerTrustPolicy.mayChat` or `isTrustedPeer()`. The presence of green unit tests for the policy creates a false assurance of capability enforcement, while the actual integration point is completely disconnected.
- **Impact:** False sense of security: unit tests pass 100% while an unauthenticated LAN stranger can inject direct chat messages.
- **Evidence:** `RealFlashChatRepository.kt:5947`: Inbound `FLASH_CHAT_TEXT` frame processor commits messages directly without checking `PeerTrustPolicy.mayChat`.
- **Why existing guards do not stop it:** Unit tests test `PeerTrustPolicyTest` without asserting against `RealFlashChatRepository` wire frame ingestion.
- **Verifier result:** survived. Code audit confirms `RealFlashChatRepository:5947` omits `mayChat`.
- **Recommended fix:** Add an end-to-end integration test verifying that synthetic `FLASH_CHAT_TEXT` frames from unauthenticated peers are dropped and emit an `org.gate.deny` probe.
- **Related:** `SEC-D05-01`, `SEC-D27-01`.

---

### SEC-D28-02 — Divergence Between `docs/security.md` Integrity Claims (BLAKE3 / Universal Hash Verification) and Reality
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** Documentation / Compliance auditor
- **Component:** `docs/security.md`, `AGENTS.md`, `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Sha256.kt`
- **CWE / standard:** CWE-1068: Inconsistency Between Implementation and Documented Design
- **What is wrong:** Both `AGENTS.md` and `docs/security.md` document that file transfers utilize "BLAKE3 or another explicitly documented cryptographic hash for integrity verification" and claim that transfers failing hash verification are rejected. In reality, BLAKE3 is not implemented anywhere in the repository; SHA-256 is used exclusively. Furthermore, in `RealFlashTransferRepository.kt:1475` (`SEC-D07-02`), transfers completed without whole-file hash verification (`UNVERIFIABLE`) are recorded as SUCCESS and displayed with a "Verified" badge in the UI.
- **Impact:** Misleading documentation obscures the true cryptographic primitives and verification guarantees of the system.
- **Evidence:** Repository search confirms zero BLAKE3 implementations; `Sha256.kt` is the sole hashing utility.
- **Why existing guards do not stop it:** Documentation is not validated by the compiler.
- **Verifier result:** survived. Confirmed discrepancy between documentation and codebase.
- **Recommended fix:** Update `docs/security.md` and `AGENTS.md` to accurately document SHA-256 as the sole hash algorithm and clearly explain the handling of legacy or unhashed transfers.
- **Related:** `SEC-D07-02`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D28-01: Debug Backdoors or Verification Bypasses Active in Release Builds**: Audited all dev flags and test hooks. Flags such as `--dev-embedded-root` require `FLASH_ALLOW_INSECURE=true`, log fatal warnings on startup, and fail closed if configured improperly.
- **REJ-D28-02: Swallowed Assertion Failures in Test Suites**: Audited unit test suites across all core modules. Test assertions strictly verify expected outcomes and exceptions; no catch-all blocks swallow assertion failures.
