# Integrator review — Kernel (K)

Reviewed 2026-11-01 against actual base `baf77753`, delivered tip `6d9c9227`, code, callers and tests. Review is complete;
repairs are targeted-tested but not yet committed/merged at this entry. No physical-device or performance claim.

## Dispositions

| Finding | Disposition |
|---|---|
| OPT-O20-01 | KEEP: INFO-default level gate and inline lambdas; eager arguments remain eager, no broad caller migration |
| OPT-O20-04 | KEEP, REPAIRED: flat 0..8 fields/direct append; stable header snapshot, ERROR-955 |
| OPT-O20-02 | KEEP, REPAIRED: ordinary writer formatting; synchronized dequeue/flush, ERROR-956 |
| OPT-O20-06 | KEEP AS API ONLY: stable gzip snapshot/source guard; no app caller |
| OPT-O15-01 | KEEP, REPAIRED: immutable copied SPKI cache key, ERROR-953 |
| OPT-O15-02 | PARTIAL: KeyFileVault shares RNG; Android PassphraseKeeper unchanged/outside scope |
| OPT-O15-03 | KEEP PROVIDER SELECTION; REJECT keyed Cipher ThreadLocal, ERROR-954 |
| OPT-O15-04 | PARTIAL: non-keyed security SHA reuse retained; keyed Mac reuse rejected; transfer helper unchanged |
| OPT-O25-03 | KEEP: NFKD/marks/lowercase, ASCII fast path, 512-entry LRU |
| OPT-O27-06 | KEEP POLICY: no tests excluded; expand gates to Android actuals |

Ten audit IDs; four confirmed defects repaired. Provider/factory isolation and gzip overwrite rejection refine those fixes.
Full rationale: ADR-310..314, ERROR-953..956, EXP-120..124 and PERF-K01..K05 in this stream's fragments.

## Verification

Forced repaired run through the machine lock:
`powershell.exe -NoProfile -File tools/flash-gradle.ps1 :core:common:jvmTest :core:common:testAndroidHostTest :core:security:jvmTest :core:security:testAndroidHostTest --continue --no-configuration-cache --no-daemon --rerun-tasks --no-build-cache --console=plain`

BUILD SUCCESSFUL in 3m 50s, 32/32 actionable tasks executed, exit 0. Common JVM 19; common Android host 144; security JVM
174 (two documented Windows/POSIX skips); security Android host 265 in 33 suites. Total 602, zero failures/errors. Only
source KDoc clarification followed this run. Mutable-SPKI and in-flight-flush regressions failed on delivered behavior
and pass repaired; HMAC/AES key-change tests cover correctness, not absence of retained keyed state or zeroization.

## Evidence limits and stopping point

No reproducible paired benchmark delivered; every ns/us/percentage/FPS/allocation/compression headline is withdrawn.
Flat calls avoid Pair/array creation, not all allocations; vararg callers remain. Ordinary queue records are raw/bounded;
explicit flush/crash/export may block. Retention is five ~1,000,000-byte files plus line overshoot. Gzip's standard CRC
trailer is not independent verification, and Android Share logs still creates text/plain. Public cache membership is not
trust. Fresh keyed operations do not promise JCA zeroization. No algorithm, nonce/AAD, key size, pin or wire change.

Next: finish/fold records, commit reviewed repairs, merge --no-ff, force both expanded gates on merged dev. Preserve
ERROR-951/952 history; ERROR-952's keyed-cache workaround is superseded. No push, deployment, phone access or Wave 5 work.
