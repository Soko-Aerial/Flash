# Current Handoff — Kernel Stream (K)

## Integration stopping point — 2026-11-01
- **Branch/worktree:** `agent/opt-kernel`, `E:/FlashWork/opt-kernel`; actual merge-base `baf77753`, delivered tip `6d9c9227`.
- **Phase:** review complete; repaired code targeted-tested, repairs and corrected fragments still uncommitted/unmerged.
- **Last verified build:** repaired worktree, 602 tests, zero failures/errors, two documented POSIX-only skips;
  BUILD SUCCESSFUL in 3m 50s, all 32 tasks executed, exit 0. Exact command in `INTEGRATOR-REVIEW.md`/progress.
- **Working structural changes:** level/lambda logging, flat/direct-format probes with stable export snapshot,
  writer formatting with in-flight-aware flush and stable gzip API, immutable public-key LRU, fresh keyed JCA
  operations from cached Provider selection, non-keyed SHA reuse, KeyFileVault shared RNG, equivalent cached NFKD.
- **Rejected approach:** keyed Cipher/Mac ThreadLocals retain keyed capability; ERROR-952 workaround superseded.
- **Limits:** no performance figure established; gzip has no app caller, RNG/transfer hashing targets partial.
  Android Share logs remains text/plain. Raw bounded queues do not authorize secret logging or durable-delivery claims.
- **Owed:** PERF-K01..K05. No physical provider/device verification, push or deployment.
- **Next:** fold corrected records, commit repairs, merge --no-ff, run both expanded gates on merged dev, then
  stop daemons and remove this worktree only after green integration (keep branch).
- **Authority:** `INTEGRATOR-REVIEW.md`, reviewed ADR/error/experiment/backlog fragments.

## SUPERSEDED delivered handoff (preserved; not the current stopping point)

## Current branch
`agent/opt-kernel` (worktree `E:\FlashWork\opt-kernel`, branched from `dev` at `44eed960`)

## Last verified build
`d13ed7d9`

## Current phase
Wave 4 — Kernel Stream (K) Optimizations Complete

## Working features
- Level-gated inline logging (`FlashLog.d`, `i`, `w`, `e`) with zero lambda allocation in disabled paths (`OPT-O20-01`).
- `FlashProbe` flat overloads (`emit` with 1-8 fields) and zero-allocation field formatting preserving `PROBES.md` contract (`OPT-O20-04`).
- Off-thread regex redaction and date formatting in `RotatingFileLogSink` via non-blocking queue (`OPT-O20-02`).
- Direct gzip diagnostic log export (`exportGzip`) without uncompressed disk duplication (`OPT-O20-06`).
- 1024-entry SPKI public key LRU cache in `ecP256Verify`, cutting verification CPU time in half (`OPT-O15-01`).
- Shared `SecureRandom` singleton elimination of provider resolution/FD churn (`OPT-O15-02`).
- ThreadLocal `Cipher.getInstance(AES_GCM)` caching with SunJCE IV-reuse fallback (`OPT-O15-03`).
- ThreadLocal `MessageDigest` and `Mac` caching in `PlatformCrypto` (`OPT-O15-04`).
- ASCII fast-path, top-level diacritics regex, and 512-entry cached NFKD in `foldForSearch` (`OPT-O25-03`).
- Full retention of all 170 security tests in Gate 1 (`OPT-O27-06`).
- Gate 1 and Gate 2 100% green with 0 test failures.

## In progress
None. All 10 findings in Stream K scope are complete and verified.

## Broken
None. All 3,234+ tests in Gate 1 and 1,701+ tests in Gate 2 pass.

## Last change
`d13ed7d9`: `perf(common): top-level diacritics regex, ASCII fast-path and cached NFKD in foldForSearch (OPT-O25-03)`

## Last test
- Integration Gate 1: `:core:common:jvmTest :core:security:jvmTest :core:persistence:jvmTest :core:messaging:jvmTest :core:transfer:jvmTest :core:swarm:jvmTest :core:network:jvmTest :ui:chat:jvmTest :ui:theme:jvmTest :ui:callui:jvmTest :core:enterprise:jvmTest :desktop:compileKotlinJvm :app:compileDebugKotlin` -> **BUILD SUCCESSFUL in 1m 38s**, 0 failures.
- Integration Gate 2: `:core:messaging:testAndroidHostTest :core:persistence:testAndroidHostTest :ui:chat:testAndroidHostTest :app:testDebugUnitTest :core:common:testAndroidHostTest` -> **BUILD SUCCESSFUL in 7m 34s**, 0 failures.

## Known blockers
None.

## Recommended next task
Integrator review and merge `agent/opt-kernel` into `dev`.

## Files most relevant to next task
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/logging/FlashLog.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/logging/FlashProbe.kt`
- `core/common/src/androidMain/kotlin/com/transfer/flash/core/common/logging/RotatingFileLogSink.kt`
- `core/common/src/androidMain/kotlin/com/transfer/flash/core/common/text/SearchFolding.android.kt`
- `core/common/src/jvmMain/kotlin/com/transfer/flash/core/common/text/SearchFolding.jvm.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.android.kt`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.jvm.kt`
- `logs/streams/kernel/`
